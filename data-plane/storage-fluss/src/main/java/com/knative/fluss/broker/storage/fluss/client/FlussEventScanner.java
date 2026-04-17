package com.knative.fluss.broker.storage.fluss.client;

import com.knative.fluss.broker.common.model.Envelope;
import com.knative.fluss.broker.storage.fluss.mapping.EnvelopeRowMapper;
import com.knative.fluss.broker.storage.fluss.tables.FlussTablePath;
import org.apache.fluss.client.table.Table;
import org.apache.fluss.client.table.scanner.ScanRecord;
import org.apache.fluss.client.table.scanner.log.LogScanner;
import org.apache.fluss.metadata.TablePath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Scans events from Fluss log tables for dispatcher consumption.
 *
 * <p>Uses the real Fluss {@link LogScanner} to read events. Each trigger maintains
 * its own cursor (subscription offset) so independent dispatchers can consume
 * the same table at different rates.
 *
 * <p><b>Thread safety:</b> One scanner per trigger key. The Fluss {@link LogScanner}
 * is NOT thread-safe — each trigger's scanner is isolated.
 */
public class FlussEventScanner implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(FlussEventScanner.class);

    /** Default poll timeout when scanning for new records. */
    private static final Duration DEFAULT_POLL_TIMEOUT = Duration.ofMillis(200);

    private final FlussConnectionManager connectionManager;
    private final Map<String, LogScanner> scanners = new ConcurrentHashMap<>();
    private final Map<String, Boolean> subscribed = new ConcurrentHashMap<>();
    private final Map<String, Long> cursors = new ConcurrentHashMap<>();

    public FlussEventScanner(FlussConnectionManager connectionManager) {
        this.connectionManager = connectionManager;
    }

    /**
     * Scan the next batch of events from the given table for a specific trigger.
     *
     * <p>On first call for a trigger key, subscribes to all buckets from the beginning
     * (for log tables, this ensures no events are missed). Subsequent calls poll for new records.
     *
     * @param tablePath  table to scan
     * @param triggerKey unique key for this trigger (for cursor tracking)
     * @param batchSize  max events to read in this poll (used as the poll timeout hint)
     * @return list of envelopes (may be empty if no new events)
     */
    public List<Envelope> scan(FlussTablePath tablePath, String triggerKey, int batchSize) {
        try {
            LogScanner scanner = getOrCreateScanner(tablePath, triggerKey);
            ensureSubscribed(tablePath, scanner, triggerKey);

            // Poll for records with a short timeout
            var scanRecords = scanner.poll(DEFAULT_POLL_TIMEOUT);
            List<Envelope> envelopes = new ArrayList<>();

            for (ScanRecord record : scanRecords) {
                try {
                    // Use EnvelopeRowMapper.fromInternalRow for correct type conversion
                    Envelope envelope = EnvelopeRowMapper.fromInternalRow(record.getRow());
                    envelopes.add(envelope);
                } catch (Exception e) {
                    log.warn("Failed to deserialize scan record from trigger {} table {}",
                            triggerKey, tablePath.fullPath(), e);
                }
            }

            if (!envelopes.isEmpty()) {
                log.debug("Scanned {} events from {} for trigger {}",
                        envelopes.size(), tablePath.fullPath(), triggerKey);
            }

            return envelopes;
        } catch (Exception e) {
            log.error("Error scanning table {} for trigger {}", tablePath.fullPath(), triggerKey, e);
            throw new RuntimeException("Fluss scan failed", e);
        }
    }

    /**
     * Advance the cursor for a trigger after successful delivery.
     *
     * @param triggerKey the trigger's key
     * @param eventsRead number of events consumed in the last scan
     */
    public void advanceCursor(String triggerKey, int eventsRead) {
        cursors.merge(triggerKey, (long) eventsRead, Long::sum);
    }

    /** Get the current cursor position (total events scanned) for a trigger. */
    public long getCursor(String triggerKey) {
        return cursors.getOrDefault(triggerKey, 0L);
    }

    /**
     * Get or create a LogScanner for a trigger. Reuses existing scanners.
     */
    private LogScanner getOrCreateScanner(FlussTablePath tablePath, String triggerKey) {
        return scanners.computeIfAbsent(triggerKey, k -> {
            log.info("Creating LogScanner for trigger {} on table {}", k, tablePath.fullPath());
            Table table = connectionManager.getConnection().getTable(
                    TablePath.of(tablePath.database(), tablePath.table()));
            return table.newScan().createLogScanner();
        });
    }

    /**
     * Subscribe the scanner to all buckets if not already subscribed.
     * For log tables, we subscribe from the beginning to ensure no events are missed.
     */
    private void ensureSubscribed(FlussTablePath tablePath, LogScanner scanner, String triggerKey) {
        if (subscribed.containsKey(triggerKey)) {
            return;
        }
        Table table = connectionManager.getConnection().getTable(
                TablePath.of(tablePath.database(), tablePath.table()));
        int numBuckets = table.getTableInfo().getNumBuckets();
        log.info("Subscribing trigger {} to {} buckets on table {}",
                triggerKey, numBuckets, tablePath.fullPath());
        for (int bucket = 0; bucket < numBuckets; bucket++) {
            scanner.subscribeFromBeginning(bucket);
        }
        subscribed.put(triggerKey, Boolean.TRUE);
    }

    /**
     * Close all scanners and release resources.
     */
    @Override
    public void close() {
        log.info("Closing FlussEventScanner ({} scanners)", scanners.size());
        for (var entry : scanners.entrySet()) {
            try {
                entry.getValue().close();
            } catch (Exception e) {
                log.warn("Error closing scanner for trigger {}", entry.getKey(), e);
            }
        }
        scanners.clear();
        subscribed.clear();
    }
}
