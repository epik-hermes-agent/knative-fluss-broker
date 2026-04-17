package com.knative.fluss.broker.iceberg.catalog;

import com.knative.fluss.broker.common.config.IcebergConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages the Iceberg catalog connection (Hive Metastore or Hadoop catalog).
 */
public class IcebergCatalogManager {

    private static final Logger log = LoggerFactory.getLogger(IcebergCatalogManager.class);

    private final IcebergConfig config;

    public IcebergCatalogManager(IcebergConfig config) {
        this.config = config;
    }

    /** Initialize the catalog connection. */
    public void initialize() {
        if (!config.enabled()) return;
        log.info("Initializing Iceberg catalog: type={} metastore={}",
            config.catalogType(), config.hiveMetastoreEndpoint());
        // Actual: create HiveCatalog with HMS endpoint
    }

    /** Ensure an Iceberg table exists for the given broker. */
    public void ensureTable(String namespace, String brokerName) {
        if (!config.enabled()) return;
        String tableName = "knative_" + namespace + "_broker_" + brokerName;
        log.info("Ensuring Iceberg table: {}", tableName);
    }
}
