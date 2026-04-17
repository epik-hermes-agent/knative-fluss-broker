package com.knative.fluss.broker.storage.fluss.tables;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class FlussTablePathTest {

    @Test
    void shouldCreateBrokerTablePath() {
        var path = FlussTablePath.brokerTable("default", "my-broker");
        assertThat(path.database()).isEqualTo("knative_default");
        assertThat(path.table()).isEqualTo("broker_my_broker");
        assertThat(path.fullPath()).isEqualTo("knative_default.broker_my_broker");
    }

    @Test
    void shouldCreateDlqTablePath() {
        var path = FlussTablePath.dlqTable("default", "my-broker", "my-trigger");
        assertThat(path.database()).isEqualTo("knative_default");
        assertThat(path.table()).isEqualTo("dlq_my_trigger");
    }

    @Test
    void shouldCreateSchemaRegistryPath() {
        var path = FlussTablePath.schemaRegistry("default");
        assertThat(path.fullPath()).isEqualTo("knative_default.schema_registry");
    }

    @Test
    void shouldSanitizeSpecialCharacters() {
        var path = FlussTablePath.brokerTable("my-ns", "broker.name");
        assertThat(path.database()).isEqualTo("knative_my_ns");
        assertThat(path.table()).isEqualTo("broker_broker_name");
    }
}
