package com.knative.fluss.broker.schema.registry;

import com.knative.fluss.broker.common.config.SchemaConfig;
import com.knative.fluss.broker.storage.fluss.tables.FlussTablePath;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class SchemaRegistryTest {

    private static final String SCHEMA_V1 = "{\"type\":\"object\",\"properties\":{\"orderId\":{\"type\":\"string\"}}}";
    private static final String SCHEMA_V2A = "{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"string\"}}}";
    private static final String SCHEMA_V2B = "{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"string\"},\"b\":{\"type\":\"integer\"}}}";
    private static final String SCHEMA_SIMPLE = "{\"type\":\"object\"}";

    private final SchemaConfig config = SchemaConfig.defaults();
    private final FlussTablePath tablePath = FlussTablePath.schemaRegistry("test");
    private final SchemaRegistry registry = new SchemaRegistry(config, tablePath);

    @Test
    void shouldRegisterNewSchema() {
        var schema = registry.resolveOrRegister(
            "com.example.order.created",
            "application/json",
            SCHEMA_V1
        );

        assertThat(schema.schemaId()).isGreaterThan(0);
        assertThat(schema.schemaVersion()).isEqualTo(1);
        assertThat(schema.eventType()).isEqualTo("com.example.order.created");
    }

    @Test
    void shouldReturnExistingSchemaForSameEventType() {
        var first = registry.resolveOrRegister("com.example.test", "application/json", SCHEMA_SIMPLE);
        var second = registry.resolveOrRegister("com.example.test", "application/json", SCHEMA_SIMPLE);

        assertThat(second.schemaId()).isEqualTo(first.schemaId());
        assertThat(second.schemaVersion()).isEqualTo(first.schemaVersion());
    }

    @Test
    void shouldReturnSyntheticSchemaWhenDisabled() {
        var disabledConfig = new SchemaConfig(false, false, false, 10, 100, 1000, 60);
        var disabledRegistry = new SchemaRegistry(disabledConfig, tablePath);

        var schema = disabledRegistry.resolveOrRegister("com.example.test", "application/json", "{}");

        assertThat(schema.schemaId()).isEqualTo(0);
        assertThat(schema.schemaVersion()).isEqualTo(0);
    }

    @Test
    void shouldIncrementVersionOnSchemaEvolution() {
        var first = registry.resolveOrRegister("com.example.test", "application/json", SCHEMA_V2A);

        var second = registry.resolveOrRegister("com.example.test", "application/json", SCHEMA_V2B);

        assertThat(second.schemaId()).isGreaterThan(first.schemaId());
        assertThat(second.schemaVersion()).isEqualTo(2);
    }

    @Test
    void shouldLookupLatestSchema() {
        registry.resolveOrRegister("com.example.test", "application/json", SCHEMA_SIMPLE);

        var found = registry.lookupLatest("com.example.test", "application/json");
        assertThat(found).isPresent();
        assertThat(found.get().eventType()).isEqualTo("com.example.test");
    }

    @Test
    void shouldReturnEmptyForUnknownEventType() {
        var found = registry.lookupLatest("com.unknown.event", "application/json");
        assertThat(found).isEmpty();
    }
}
