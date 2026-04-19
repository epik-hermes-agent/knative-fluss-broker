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
 * @param catalogType       Catalog type: "rest", "jdbc", "hive", "hadoop"
 * @param warehouse         Warehouse location (e.g., "s3a://iceberg-warehouse/")
 * @param catalogUri        Catalog URI (REST endpoint, JDBC URL, or HMS thrift URI)
 * @param s3Endpoint        S3-compatible endpoint (e.g., "http://localhost:4566")
 * @param s3AccessKey       S3 access key
 * @param s3SecretKey       S3 secret key
 * @param catalogCredential Catalog credential for REST auth (e.g., "root:s3cr3t")
 */
public record IcebergConfig(
    boolean enabled,
    String freshness,
    String catalogType,
    String warehouse,
    String catalogUri,
    String s3Endpoint,
    String s3AccessKey,
    String s3SecretKey,
    String catalogCredential
) {
    /** Disabled by default — the system works correctly without Iceberg. */
    public static IcebergConfig disabled() {
        return new IcebergConfig(
            false, "1m", "rest", "s3a://iceberg-warehouse/",
            "http://localhost:8181/api/catalog",
            "http://localhost:4566", "test", "test",
            "root:s3cr3t"
        );
    }

    /**
     * Convert to Fluss {@code datalake.*} properties map.
     * Matches the FLUSS_PROPERTIES format.
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

        if ("rest".equalsIgnoreCase(catalogType)) {
            props.put("datalake.iceberg.uri", catalogUri);
            props.put("datalake.iceberg.credential", catalogCredential);
        } else if ("hive".equalsIgnoreCase(catalogType)) {
            props.put("datalake.iceberg.uri", catalogUri);
        } else if ("jdbc".equalsIgnoreCase(catalogType)) {
            props.put("datalake.iceberg.uri", catalogUri);
        }

        return props;
    }
}
