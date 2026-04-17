package com.knative.fluss.broker.common.config;

/**
 * Optional Iceberg tiering configuration.
 * Disabled by default — the system works correctly without Iceberg.
 */
public record IcebergConfig(
    boolean enabled,
    String catalogType,
    String warehouse,
    String hiveMetastoreEndpoint,
    long tieringIntervalMinutes,
    int commitBatchSize
) {
    public static IcebergConfig disabled() {
        return new IcebergConfig(
            false, "hive", "s3a://iceberg-warehouse/",
            "thrift://localhost:9083", 10, 1000
        );
    }
}
