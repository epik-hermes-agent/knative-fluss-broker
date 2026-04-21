package com.knative.fluss.broker.tui.data;

import com.knative.fluss.broker.common.config.FlussConfig;
import com.knative.fluss.broker.storage.fluss.client.FlussConnectionManager;
import com.knative.fluss.broker.storage.fluss.tables.FlussTablePath;
import org.apache.fluss.client.admin.Admin;
import org.apache.fluss.metadata.TableInfo;
import org.apache.fluss.metadata.TablePath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Direct Fluss client for metadata queries that the SQL Gateway can't provide.
 * Connects to the Fluss coordinator for table info, bucket counts, and datalake status.
 */
public class FlussMetricsProvider implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(FlussMetricsProvider.class);

    private final FlussConnectionManager connectionManager;

    public FlussMetricsProvider(String coordinatorEndpoint) {
        FlussConfig config = new FlussConfig(
                coordinatorEndpoint,
                50,     // writeBatchSize (not used, but required)
                100,    // writeBatchTimeoutMs
                10000,  // ackTimeoutMs
                3,      // writeMaxRetries
                100     // writeRetryBackoffMs
        );
        this.connectionManager = new FlussConnectionManager(config);
    }

    /**
     * Get table info including bucket count and properties.
     * Returns null if the table doesn't exist or can't be queried.
     */
    public TableInfo getTableInfo(FlussTablePath tablePath) {
        try {
            Admin admin = connectionManager.getConnection().getAdmin();
            try {
                TablePath path = TablePath.of(tablePath.database(), tablePath.table());
                return admin.getTableInfo(path)
                        .get(10, TimeUnit.SECONDS);
            } finally {
                admin.close();
            }
        } catch (Exception e) {
            log.debug("Failed to get table info for {}: {}", tablePath.fullPath(), e.getMessage());
            return null;
        }
    }

    /**
     * Check if datalake tiering is enabled for a table.
     */
    public boolean isDatalakeEnabled(FlussTablePath tablePath) {
        TableInfo info = getTableInfo(tablePath);
        if (info == null) return false;
        Map<String, String> options = info.getProperties().toMap();
        return "true".equals(options.get("table.datalake.enabled"));
    }

    /**
     * Get the number of buckets for a table.
     */
    public int getBucketCount(FlussTablePath tablePath) {
        TableInfo info = getTableInfo(tablePath);
        if (info == null) return 0;
        return info.getNumBuckets();
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

    @Override
    public void close() {
        connectionManager.close();
    }
}
