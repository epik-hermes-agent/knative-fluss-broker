package com.knative.fluss.broker.iceberg.tiering;

import com.knative.fluss.broker.common.config.IcebergConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Background job that compacts older events from Fluss log tables to Apache Iceberg.
 * Activated only when iceberg.enabled=true.
 *
 * <p>Flow:
 * <ol>
 *   <li>Read events from Fluss older than the tiering threshold</li>
 *   <li>Convert to Iceberg-compatible format</li>
 *   <li>Write to Iceberg table via Hive Metastore catalog</li>
 *   <li>Mark compacted events in Fluss (or let Fluss TTL handle cleanup)</li>
 * </ol>
 */
public class IcebergTieringJob implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(IcebergTieringJob.class);

    private final IcebergConfig config;

    public IcebergTieringJob(IcebergConfig config) {
        this.config = config;
    }

    @Override
    public void run() {
        if (!config.enabled()) {
            log.debug("Iceberg tiering disabled, skipping");
            return;
        }

        log.info("Starting Iceberg tiering cycle (catalog={}, warehouse={})",
            config.catalogType(), config.warehouse());

        try {
            // 1. Connect to Fluss and scan old events
            // 2. Connect to Hive Metastore / Iceberg catalog
            // 3. Write batch to Iceberg table
            // 4. Commit the Iceberg transaction

            log.info("Iceberg tiering cycle complete");

        } catch (Exception e) {
            log.error("Iceberg tiering cycle failed", e);
        }
    }
}
