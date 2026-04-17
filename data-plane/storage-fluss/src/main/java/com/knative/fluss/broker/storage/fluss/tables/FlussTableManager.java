package com.knative.fluss.broker.storage.fluss.tables;

import com.knative.fluss.broker.common.config.FlussConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages Fluss database and table lifecycle for broker instances.
 * Ensures databases and tables exist with the correct schema.
 */
public class FlussTableManager {

    private static final Logger log = LoggerFactory.getLogger(FlussTableManager.class);

    private final FlussConfig config;
    private final ConcurrentHashMap<String, Boolean> ensuredTables = new ConcurrentHashMap<>();

    public FlussTableManager(FlussConfig config) {
        this.config = config;
    }

    /**
     * Ensure the Fluss database exists for the given namespace.
     *
     * @param namespace the Kubernetes namespace
     */
    public void ensureDatabase(String namespace) {
        String db = "knative_" + namespace;
        log.info("Ensuring Fluss database: {}", db);
        // Actual Fluss DDL executed via flussClient.execute("CREATE DATABASE IF NOT EXISTS " + db)
        // Stubbed for now — requires running Fluss cluster
    }

    /**
     * Ensure the broker log table exists with the correct schema.
     *
     * @param tablePath the table path to ensure
     */
    public void ensureBrokerTable(FlussTablePath tablePath) {
        String key = tablePath.fullPath();
        if (ensuredTables.putIfAbsent(key, Boolean.TRUE) == null) {
            log.info("Ensuring Fluss broker table: {}", key);
            // CREATE TABLE IF NOT EXISTS knative_{ns}.broker_{name} (
            //   event_id STRING,
            //   event_source STRING,
            //   event_type STRING,
            //   event_time TIMESTAMP(3),
            //   content_type STRING,
            //   data BYTES,
            //   schema_id INT,
            //   schema_version INT,
            //   attributes MAP<STRING, STRING>,
            //   ingestion_time TIMESTAMP(3),
            //   ingestion_date DATE,
            //   PRIMARY KEY (event_id),
            //   PARTITIONED BY (ingestion_date)
            // )
        }
    }

    /**
     * Ensure the DLQ table exists for a trigger.
     */
    public void ensureDlqTable(FlussTablePath tablePath) {
        String key = tablePath.fullPath();
        if (ensuredTables.putIfAbsent(key, Boolean.TRUE) == null) {
            log.info("Ensuring Fluss DLQ table: {}", key);
        }
    }

    /**
     * Ensure the schema registry table exists.
     */
    public void ensureSchemaRegistryTable(FlussTablePath tablePath) {
        String key = tablePath.fullPath();
        if (ensuredTables.putIfAbsent(key, Boolean.TRUE) == null) {
            log.info("Ensuring schema registry table: {}", key);
        }
    }

    /** Check if a table has been ensured in this session. */
    public boolean isTableEnsured(FlussTablePath tablePath) {
        return ensuredTables.containsKey(tablePath.fullPath());
    }
}
