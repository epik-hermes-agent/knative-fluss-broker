package com.knative.fluss.broker.common.config;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class IcebergConfigTest {

    @Test
    void disabledShouldHaveSensibleDefaults() {
        var config = IcebergConfig.disabled();

        assertThat(config.enabled()).isFalse();
        assertThat(config.freshness()).isEqualTo("1m");
        assertThat(config.catalogType()).isEqualTo("jdbc");
        assertThat(config.warehouse()).isEqualTo("s3a://iceberg-warehouse/");
        assertThat(config.hiveMetastoreUri()).isNotNull();
        assertThat(config.jdbcUser()).isNotNull();
        assertThat(config.jdbcPassword()).isNotNull();
    }

    @Test
    void toFlussPropertiesShouldMapAllBaseFields() {
        var config = new IcebergConfig(
            true, "30s", "jdbc", "s3a://my-warehouse/",
            "jdbc:postgresql://pg:5432/iceberg",
            "http://minio:9000", "access123", "secret456",
            "admin", "admin123"
        );

        Map<String, String> props = config.toFlussProperties();

        assertThat(props.get("datalake.format")).isEqualTo("iceberg");
        assertThat(props.get("datalake.iceberg.type")).isEqualTo("jdbc");
        assertThat(props.get("datalake.iceberg.warehouse")).isEqualTo("s3a://my-warehouse/");
        assertThat(props.get("datalake.iceberg.s3.endpoint")).isEqualTo("http://minio:9000");
        assertThat(props.get("datalake.iceberg.s3.access-key-id")).isEqualTo("access123");
        assertThat(props.get("datalake.iceberg.s3.secret-access-key")).isEqualTo("secret456");
        assertThat(props.get("datalake.iceberg.s3.path.style.access")).isEqualTo("true");
    }

    @Test
    void jdbcCatalogShouldIncludeCredentials() {
        var config = new IcebergConfig(
            true, "30s", "jdbc", "s3a://warehouse/",
            "jdbc:postgresql://pg:5432/iceberg",
            "http://minio:9000", "ak", "sk",
            "jdbcreader", "jdbcsecret"
        );

        Map<String, String> props = config.toFlussProperties();

        assertThat(props.get("datalake.iceberg.uri")).isEqualTo("jdbc:postgresql://pg:5432/iceberg");
        assertThat(props.get("datalake.iceberg.jdbc.user")).isEqualTo("jdbcreader");
        assertThat(props.get("datalake.iceberg.jdbc.password")).isEqualTo("jdbcsecret");
    }

    @Test
    void hiveCatalogShouldNotIncludeJdbcCredentials() {
        var config = new IcebergConfig(
            true, "30s", "hive", "s3a://warehouse/",
            "thrift://hms:9083",
            "http://minio:9000", "ak", "sk",
            "user", "pass"
        );

        Map<String, String> props = config.toFlussProperties();

        assertThat(props.get("datalake.iceberg.uri")).isEqualTo("thrift://hms:9083");
        assertThat(props).doesNotContainKey("datalake.iceberg.jdbc.user");
        assertThat(props).doesNotContainKey("datalake.iceberg.jdbc.password");
    }
}
