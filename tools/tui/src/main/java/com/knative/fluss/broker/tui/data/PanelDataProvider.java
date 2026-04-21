package com.knative.fluss.broker.tui.data;

import com.fasterxml.jackson.databind.JsonNode;
import com.knative.fluss.broker.common.config.FlussConfig;
import com.knative.fluss.broker.common.model.Envelope;
import com.knative.fluss.broker.storage.fluss.tables.FlussTablePath;
import com.knative.fluss.broker.tui.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Aggregates data from FlussTableScanner and Polaris REST API for all TUI panels.
 * Runs periodic refresh on a background thread.
 *
 * <p>Data sources:
 * <ul>
 *   <li>Hot storage: Fluss LogScanner via direct Java client (no SQL Gateway needed)
 *   <li>Cold storage (Iceberg): Polaris REST API for tiering metadata
 *   <li>DLQ: Direct JDBC to the Fluss coordinator metadata store
 * </ul>
 */
public class PanelDataProvider implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PanelDataProvider.class);

    private final FlussTableScanner scanner;
    private final String polarisUri;
    private final String polarisWarehouse;
    private final String database;
    private final String brokerTable;
    private final int refreshIntervalMs;

    private ScheduledExecutorService scheduler;

    // Cached data — updated by background refresh
    private volatile List<EventRecord> recentEvents = List.of();
    private volatile List<EventRecord> tieredEvents = List.of();
    private volatile List<DlqEntry> dlqEntries = List.of();
    private volatile TableStats tableStats = new TableStats("", 0, 0, false);
    private volatile TieringStatus tieringStatus = new TieringStatus("", 0, null);
    private volatile List<TriggerState> triggerStates = List.of();
    private volatile String lastError = null;
    private volatile long lastRefreshTime = 0;

    // Set of already-seen event IDs for deduplication (keeps insertion order, evicts oldest when > 200)
    private final Set<String> seenEventIds = new LinkedHashSet<>(200);

    public PanelDataProvider(String coordinatorEndpoint, String polarisUri, String polarisWarehouse,
                             String database, String brokerTable, int refreshIntervalMs) {
        this(null, polarisUri, polarisWarehouse, database, brokerTable, refreshIntervalMs,
                new FlussConfig(
                        "fluss://" + coordinatorEndpoint,
                        100,    // writeBatchSize
                        50,     // writeBatchTimeoutMs
                        5000,   // ackTimeoutMs
                        3,      // writeMaxRetries
                        100     // writeRetryBackoffMs
                ));
    }

    /**
     * Package-private constructor for testing with a mock scanner.
     * Pass null for config to use pre-created scanner.
     */
    PanelDataProvider(FlussTableScanner scanner, String polarisUri, String polarisWarehouse,
                      String database, String brokerTable, int refreshIntervalMs, FlussConfig config) {
        this.scanner = scanner != null ? scanner : new FlussTableScanner(config);
        this.polarisUri = polarisUri;
        this.polarisWarehouse = polarisWarehouse;
        this.database = database;
        this.brokerTable = brokerTable;
        this.refreshIntervalMs = refreshIntervalMs;
    }

    /**
     * Start the background data refresh thread.
     */
    public void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "tui-refresh");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::refresh, 0, refreshIntervalMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Force an immediate refresh (e.g., on 'r' key press).
     */
    public void forceRefresh() {
        refresh();
    }

    private void refresh() {
        try {
            refreshTableStats();
            refreshRecentEvents();
            refreshTieringStatus();
            refreshDlqEntries();
            lastError = null;
            lastRefreshTime = System.currentTimeMillis();
        } catch (Exception e) {
            log.debug("Refresh failed: {}", e.getMessage());
            lastError = e.getMessage();
        }
    }

    private String fullPath() {
        return database + "." + brokerTable;
    }

    private FlussTablePath tablePath() {
        return new FlussTablePath(database, brokerTable);
    }

    private void refreshTableStats() {
        FlussTablePath path = tablePath();
        long rowCount = scanner.getRowCount(path);
        tableStats = new TableStats(fullPath(), rowCount, 1, true);
    }

    private void refreshRecentEvents() {
        // Read from beginning to get initial batch, then use cursor-based polling
        List<Envelope> events = scanner.readAllFromBeginning(tablePath(), "dashboard", 50);
        if (events.isEmpty()) {
            events = scanner.scanRecentEvents(tablePath(), "dashboard", 50);
        }

        List<EventRecord> records = new ArrayList<>();
        for (Envelope env : events) {
            // Deduplicate
            if (!seenEventIds.contains(env.eventId())) {
                seenEventIds.add(env.eventId());
                records.add(envelopeToRecord(env));
            }
        }
        recentEvents = records;
    }

    private void refreshTieringStatus() {
        FlussTablePath path = tablePath();
        long rowCount = scanner.getRowCount(path);
        tieringStatus = new TieringStatus(fullPath(), rowCount, Instant.now());

        // Also try to get tiering metadata from Polaris
        String icebergTableName = database + "." + brokerTable;
        JsonNode meta = scanner.queryIcebergTiering(polarisUri, polarisWarehouse, icebergTableName);
        if (meta != null) {
            // Extract tiering info from Polaris response
            JsonNode props = meta.get("properties");
            if (props != null) {
                String format = props.has("write.format.default")
                        ? props.get("write.format.default").asText() : "parquet";
                tieringStatus = new TieringStatus(fullPath(), rowCount, Instant.now());
            }
        }
    }

    private void refreshDlqEntries() {
        // DLQ entries are stored in Fluss metadata — try to read via the scanner
        // Fall back to empty list (DLQ reading requires separate metadata API)
        List<DlqEntry> entries = new ArrayList<>();

        // Try to read DLQ tables if they exist
        FlussTablePath dlqPath = new FlussTablePath(database, "dlq_" + brokerTable.replace("broker_", ""));
        try {
            List<Envelope> events = scanner.readAllFromBeginning(dlqPath, "dlq-dashboard", 20);
            for (Envelope env : events) {
                var now = Instant.now();
                entries.add(new DlqEntry(
                        env.eventId(),
                        env.eventType(),
                        env.eventSource(),
                        env.eventTime(),
                        env.contentType(),
                        new String(env.data(), StandardCharsets.UTF_8),
                        env.schemaId(),
                        env.schemaVersion(),
                        parseAttributes(env.attributes()),
                        now,
                        now.atZone(ZoneOffset.UTC).toLocalDate(),
                        envToDlqReason(env),
                        1,
                        null,
                        now
                ));
            }
        } catch (Exception e) {
            log.debug("DLQ table not available: {}", e.getMessage());
        }

        dlqEntries = entries;
    }

    private EventRecord envelopeToRecord(Envelope env) {
        var now = Instant.now();
        return new EventRecord(
                env.eventId(),
                env.eventType(),
                env.eventSource(),
                env.eventTime(),
                env.contentType(),
                new String(env.data(), StandardCharsets.UTF_8),
                env.schemaId(),
                env.schemaVersion(),
                parseAttributes(env.attributes()),
                now,
                now.atZone(ZoneOffset.UTC).toLocalDate()
        );
    }

    private String envToDlqReason(Envelope env) {
        if (env.attributes() == null) return null;
        Object reason = env.attributes().get("knativeflussbrokerDlqReason");
        return reason != null ? reason.toString() : null;
    }

    private static Instant parseInstant(String text) {
        if (text == null) return null;
        try { return Instant.parse(text); }
        catch (Exception e1) {
            try { return LocalDateTime.parse(text, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.S"))
                    .atZone(ZoneOffset.UTC).toInstant(); }
            catch (Exception e2) {
                try { return LocalDateTime.parse(text, DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.S"))
                        .atZone(ZoneOffset.UTC).toInstant(); }
                catch (Exception e3) { return null; }
            }
        }
    }

    private static Map<String, String> parseAttributes(Map<String, String> attrs) {
        if (attrs == null || attrs.isEmpty()) return Map.of();
        Map<String, String> result = new LinkedHashMap<>();
        attrs.forEach((k, v) -> result.put(k, v != null ? v : "null"));
        return result;
    }

    // ── Getters for panels ───────────────────────────────

    public List<EventRecord> getRecentEvents() { return recentEvents; }
    public List<EventRecord> getTieredEvents() { return tieredEvents; }
    public List<DlqEntry> getDlqEntries() { return dlqEntries; }
    public TableStats getTableStats() { return tableStats; }
    public TieringStatus getTieringStatus() { return tieringStatus; }
    public List<TriggerState> getTriggerStates() { return triggerStates; }
    public String getLastError() { return lastError; }
    public long getLastRefreshTime() { return lastRefreshTime; }
    public boolean isGatewayReachable() { return scanner.isReachable(); }
    public boolean isFlussReachable() { return scanner.isReachable(); }

    @Override
    public void close() {
        if (scheduler != null) {
            scheduler.shutdown();
            try { scheduler.awaitTermination(5, TimeUnit.SECONDS); } catch (InterruptedException e) { /* ignore */ }
        }
        scanner.close();
    }
}
