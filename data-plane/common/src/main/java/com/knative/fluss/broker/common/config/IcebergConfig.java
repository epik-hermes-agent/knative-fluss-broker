package com.knative.fluss.broker.common.config;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Configuration for Fluss's native Iceberg tiering.
 * Maps directly to Fluss server's {@code datalake.*} properties.
 * The tiering job itself is Fluss's pre-built {@code fluss-flink-tiering} Flink JAR —
 * we do NOT implement custom tiering code.
 *
 * @param enabled           Whether tiering is active (sets {@code table.datalake.enabled} on tables)
 * @param freshness         Tiering freshness interval (e.g., "30s", "5m")
 * @param catalogType       Catalog type: "hive", "hadoop", "jdbc", "rest"
 * @param warehouse         Warehouse location (e.g., "s3a://iceberg-warehouse/")
 * @param hiveMetastoreUri  HMS/JDBC catalog URI
 * @param s3Endpoint        S3-compatible endpoint (e.g., "http://localhost:9000")
 * @param s3AccessKey       S3 access key
 * @param s3SecretKey       S3 secret key
 * @param jdbcUser          JDBC catalog username (used when catalogType is "jdbc")
 * @param jdbcPassword      JDBC catalog password (used when catalogType is "jdbc")
 */
public record IcebergConfig(
    boolean enabled,
    String freshness,
    String catalogType,
    String warehouse,
    String hiveMetastoreUri,
    String s3Endpoint,
    String s3AccessKey,
    String s3SecretKey,
    String jdbcUser,
    String jdbcPassword
) {
    /** Disabled by default — the system works correctly without Iceberg. */
    public static IcebergConfig disabled() {
        return new IcebergConfig(
            false, "1m", "jdbc", "s3a://iceberg-warehouse/",
            "jdbc:postgresql://localhost:5432/iceberg",
            "http://localhost:9000", "minioadmin", "minioadmin",
            "hive", "hive"
        );
    }

    /**
     * Convert to Fluss server {@code FLUSS_PROPERTIES} format.
     * Used for programmatic config generation in docker-compose and tests.
     */
    public Map<String, String> toFlussProperties() {
        Map<String, String> props = new LinkedHashMap<>();
        props.put("datalake.format", "iceberg");
        props.put("datalake.iceberg.type", catalogType);
        props.put("datalake.iceberg.warehouse", warehouse);
        props.put("datalake.iceberg.s3.endpoint", s3Endpoint);
        props.put("datalake.iceberg.s3.access-key-id", s3AccessKey);
        props.put("datalake.iceberg.s3.secret-access-key", s3SecretKey);
        props.put("datalake.iceberg.s3.path.style.access", "true");

        if ("hive".equalsIgnoreCase(catalogType)) {
            props.put("datalake.iceberg.uri", hiveMetastoreUri);
        } else if ("jdbc".equalsIgnoreCase(catalogType)) {
            props.put("datalake.iceberg.uri", hiveMetastoreUri);
            props.put("datalake.iceberg.jdbc.user", jdbcUser);
            props.put("datalake.iceberg.jdbc.password", jdbcPassword);
        }

        return props;
    }
}
