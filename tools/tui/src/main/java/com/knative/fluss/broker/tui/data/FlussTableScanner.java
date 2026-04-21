package com.knative.fluss.broker.tui.data;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knative.fluss.broker.common.config.FlussConfig;
import com.knative.fluss.broker.common.model.Envelope;
import com.knative.fluss.broker.storage.fluss.client.FlussConnectionManager;
import com.knative.fluss.broker.storage.fluss.mapping.EnvelopeRowMapper;
import com.knative.fluss.broker.storage.fluss.tables.FlussTablePath;
import org.apache.fluss.client.table.scanner.ScanRecord;
import org.apache.fluss.client.table.scanner.log.LogScanner;
import org.apache.fluss.client.table.scanner.log.TypedLogScanner;
import org.apache.fluss.metadata.TableBucket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Direct Fluss Java client for reading broker event data.
 *
 * <p>Uses the Fluss {@link LogScanner} API (same as FlussEventScanner in the storage module)
 * to read events from Fluss log tables and deserialize them into Envelopes.
 *
 * <p>This replaces the Flink SQL Gateway approach which has metadata serialization
 * compatibility issues with Fluss 1.0-SNAPSHOT.
 *
 * <p>Two data sources are queried:
 * <ul>
 *   <li><b>Hot storage (Fluss log):</b> Direct LogScanner reads from the Fluss tablet servers
 *   <li><b>Iceberg tiering (cold):</b> Polaris REST API for Iceberg metadata
 * </ul>
 */
public class FlussTableScanner implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(FlussTableScanner.class);

    private final FlussConnectionManager connectionManager;
    private final ObjectMapper objectMapper;

    // Per-table scanners cached by trigger key
    private final Map<String, LogScanner> scanners = new ConcurrentHashMap<>();
    private final Map<String, Boolean> subscribed = new ConcurrentHashMap<>();

    public FlussTableScanner(FlussConfig config) {
        this.connectionManager = new FlussConnectionManager(config);
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Scan recent events from the broker's Fluss log table.
     * Returns events in reverse chronological order (most recent first).
     *
     * @param tablePath  the Fluss table path
     * @param triggerKey unique key for scanner cursor (use "dashboard" for TUI)
     * @param limit      max events to return
     * @return list of Envelope records
     */
    public List<Envelope> scanRecentEvents(FlussTablePath tablePath, String triggerKey, int limit) {
        List<Envelope> events = new ArrayList<>(limit);
        LogScanner scanner = getOrCreateScanner(tablePath, triggerKey);
        ensureSubscribed(tablePath, scanner, triggerKey);

        try {
            // Poll for a batch of records
            var scanRecords = scanner.poll(java.time.Duration.ofMillis(200));
            for (ScanRecord record : scanRecords) {
                try {
                    Envelope envelope = EnvelopeRowMapper.fromInternalRow(record.getRow());
                    events.add(envelope);
                    if (events.size() >= limit) {
                        break;
                    }
                } catch (Exception e) {
                    log.warn("Failed to deserialize record from {}: {}", tablePath.fullPath(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("Scanner poll failed for {}: {}", tablePath.fullPath(), e.getMessage());
        }

        return events;
    }

    /**
     * Read the latest events from the beginning (for initial dashboard population).
     * Reads up to 'limit' events across all buckets.
     */
    public List<Envelope> readAllFromBeginning(FlussTablePath tablePath, String triggerKey, int limit) {
        List<Envelope> events = new ArrayList<>(limit);
        try {
            var table = connectionManager.getConnection()
                    .getTable(org.apache.fluss.metadata.TablePath.of(tablePath.database(), tablePath.table()));
            int numBuckets = table.getTableInfo().getNumBuckets();

            LogScanner scanner = table.newScan().createLogScanner();
            // Subscribe all buckets from beginning
            for (int bucket = 0; bucket < numBuckets; bucket++) {
                scanner.subscribeFromBeginning(bucket);
            }

            // Read events
            Set<String> seen = new HashSet<>();
            long deadline = System.currentTimeMillis() + 5000; // 5 second timeout
            while (events.size() < limit && System.currentTimeMillis() < deadline) {
                var records = scanner.poll(java.time.Duration.ofMillis(100));
                if (records.isEmpty()) {
                    break; // No more records
                }
                for (ScanRecord record : records) {
                    try {
                        Envelope env = EnvelopeRowMapper.fromInternalRow(record.getRow());
                        if (!seen.contains(env.eventId())) {
                            seen.add(env.eventId());
                            events.add(env);
                        }
                        if (events.size() >= limit) break;
                    } catch (Exception e) {
                        log.debug("Deserialize error: {}", e.getMessage());
                    }
                }
            }
            scanner.close();
        } catch (Exception e) {
            log.warn("Failed to read from beginning for {}: {}", tablePath.fullPath(), e.getMessage());
        }
        return events;
    }

    /**
     * Get the total row count in a table by subscribing all buckets and polling once.
     * Returns -1 if the count cannot be determined (e.g. table is empty or error).
     */
    public long getRowCount(FlussTablePath tablePath) {
        try {
            var table = connectionManager.getConnection()
                    .getTable(org.apache.fluss.metadata.TablePath.of(tablePath.database(), tablePath.table()));
            int numBuckets = table.getTableInfo().getNumBuckets();

            LogScanner scanner = table.newScan().createLogScanner();
            for (int bucket = 0; bucket < numBuckets; bucket++) {
                scanner.subscribeFromBeginning(bucket);
            }

            var records = scanner.poll(java.time.Duration.ofMillis(1000));
            long count = records.count();
            scanner.close();
            return count;
        } catch (Exception e) {
            log.debug("Failed to get row count for {}: {}", tablePath.fullPath(), e.getMessage());
            return -1;
        }
    }

    /**
     * Check if the Fluss cluster is reachable.
     */
    public boolean isReachable() {
        try {
            connectionManager.getConnection();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Query Iceberg tiering metadata from Polaris REST API.
     * Returns metadata about tiered files for a given Iceberg table.
     *
     * @param polarisUri  Polaris REST API URI (e.g. http://localhost:8181)
     * @param warehouse    Iceberg warehouse name
     * @param tableName   Iceberg table name (e.g. "knative_default.broker_default")
     * @return JSON node with tiering metadata, or null on failure
     */
    public JsonNode queryIcebergTiering(String polarisUri, String warehouse, String tableName) {
        try {
            // Get OAuth token
            String token = getPolarisToken(polarisUri);
            if (token == null) return null;

            // List table metadata from Polaris
            String url = polarisUri + "/api/catalog/v1/tables/" + warehouse + "/" + tableName;
            java.net.URL obj = new java.net.URL(url);
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) obj.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Authorization", "Bearer " + token);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            int responseCode = conn.getResponseCode();
            if (responseCode == 200) {
                try (var reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(conn.getInputStream()))) {
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                    return objectMapper.readTree(response.toString());
                }
            }
        } catch (Exception e) {
            log.debug("Iceberg query failed for {}/{}: {}", warehouse, tableName, e.getMessage());
        }
        return null;
    }

    /**
     * Get list of Iceberg table names from Polaris.
     */
    public List<String> listIcebergTables(String polarisUri, String warehouse) {
        List<String> tables = new ArrayList<>();
        try {
            String token = getPolarisToken(polarisUri);
            if (token == null) return tables;

            String url = polarisUri + "/api/catalog/v1/tables/" + warehouse;
            java.net.URL obj = new java.net.URL(url);
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) obj.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Authorization", "Bearer " + token);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            if (conn.getResponseCode() == 200) {
                try (var reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(conn.getInputStream()))) {
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) response.append(line);
                    JsonNode root = objectMapper.readTree(response.toString());
                    JsonNode tableList = root.get("tables");
                    if (tableList != null && tableList.isArray()) {
                        for (JsonNode t : tableList) {
                            tables.add(t.asText());
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Failed to list Iceberg tables: {}", e.getMessage());
        }
        return tables;
    }

    private String getPolarisToken(String polarisUri) {
        try {
            String url = polarisUri + "/api/catalog/v1/oauth/tokens";
            java.net.URL obj = new java.net.URL(url);
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) obj.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            conn.setDoOutput(true);
            String postData = "grant_type=client_credentials&client_id=root&client_secret=s3cr3t&scope=PRINCIPAL_ROLE:ALL";
            try (var os = conn.getOutputStream()) {
                os.write(postData.getBytes());
            }
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            if (conn.getResponseCode() == 200) {
                try (var reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(conn.getInputStream()))) {
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) response.append(line);
                    JsonNode tokenResponse = objectMapper.readTree(response.toString());
                    return tokenResponse.get("access_token").asText();
                }
            }
        } catch (Exception e) {
            log.debug("Failed to get Polaris token: {}", e.getMessage());
        }
        return null;
    }

    private LogScanner getOrCreateScanner(FlussTablePath tablePath, String triggerKey) {
        return scanners.computeIfAbsent(triggerKey, k -> {
            log.info("Creating LogScanner for dashboard on table {}", tablePath.fullPath());
            var table = connectionManager.getConnection()
                    .getTable(org.apache.fluss.metadata.TablePath.of(tablePath.database(), tablePath.table()));
            return table.newScan().createLogScanner();
        });
    }

    private void ensureSubscribed(FlussTablePath tablePath, LogScanner scanner, String triggerKey) {
        if (subscribed.containsKey(triggerKey)) return;
        try {
            var table = connectionManager.getConnection()
                    .getTable(org.apache.fluss.metadata.TablePath.of(tablePath.database(), tablePath.table()));
            int numBuckets = table.getTableInfo().getNumBuckets();
            for (int bucket = 0; bucket < numBuckets; bucket++) {
                scanner.subscribeFromBeginning(bucket);
            }
            subscribed.put(triggerKey, Boolean.TRUE);
            log.info("Subscribed dashboard scanner to {} buckets on {}", numBuckets, tablePath.fullPath());
        } catch (Exception e) {
            log.warn("Failed to subscribe to {}: {}", tablePath.fullPath(), e.getMessage());
        }
    }

    @Override
    public void close() {
        log.info("Closing FlussTableScanner");
        for (var entry : scanners.entrySet()) {
            try {
                entry.getValue().close();
            } catch (Exception e) {
                log.warn("Error closing scanner: {}", e.getMessage());
            }
        }
        scanners.clear();
        subscribed.clear();
        connectionManager.close();
    }
}
