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
        assertThat(config.catalogType()).isEqualTo("rest");
        assertThat(config.warehouse()).isEqualTo("s3a://iceberg-warehouse/");
        assertThat(config.catalogUri()).isEqualTo("http://localhost:8181/api/catalog");
        assertThat(config.s3Endpoint()).isEqualTo("http://localhost:4566");
        assertThat(config.catalogCredential()).isEqualTo("root:s3cr3t");
    }

    @Test
    void toFlussPropertiesShouldMapAllBaseFields() {
        var config = new IcebergConfig(
            true, "30s", "rest", "s3a://my-warehouse/",
            "http://polaris:8181/api/catalog",
            "http://localstack:4566", "test", "test",
            "root:s3cr3t"
        );

        Map<String, String> props = config.toFlussProperties();

        assertThat(props.get("datalake.format")).isEqualTo("iceberg");
        assertThat(props.get("datalake.iceberg.type")).isEqualTo("rest");
        assertThat(props.get("datalake.iceberg.warehouse")).isEqualTo("s3a://my-warehouse/");
        assertThat(props.get("datalake.iceberg.s3.endpoint")).isEqualTo("http://localstack:4566");
        assertThat(props.get("datalake.iceberg.s3.access-key-id")).isEqualTo("test");
        assertThat(props.get("datalake.iceberg.s3.secret-access-key")).isEqualTo("test");
        assertThat(props.get("datalake.iceberg.s3.path.style.access")).isEqualTo("true");
    }

    @Test
    void restCatalogShouldIncludeUriAndCredential() {
        var config = new IcebergConfig(
            true, "30s", "rest", "s3a://warehouse/",
            "http://polaris:8181/api/catalog",
            "http://localstack:4566", "ak", "sk",
            "root:s3cr3t"
        );

        Map<String, String> props = config.toFlussProperties();

        assertThat(props.get("datalake.iceberg.uri")).isEqualTo("http://polaris:8181/api/catalog");
        assertThat(props.get("datalake.iceberg.credential")).isEqualTo("root:s3cr3t");
        assertThat(props).doesNotContainKey("datalake.iceberg.jdbc.user");
    }

    @Test
    void hiveCatalogShouldIncludeUri() {
        var config = new IcebergConfig(
            true, "30s", "hive", "s3a://warehouse/",
            "thrift://hms:9083",
            "http://localstack:4566", "ak", "sk",
            null
        );

        Map<String, String> props = config.toFlussProperties();

        assertThat(props.get("datalake.iceberg.uri")).isEqualTo("thrift://hms:9083");
        assertThat(props).doesNotContainKey("datalake.iceberg.credential");
    }
}
