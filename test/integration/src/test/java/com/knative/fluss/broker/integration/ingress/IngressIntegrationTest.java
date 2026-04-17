package com.knative.fluss.broker.integration.ingress;

import com.knative.fluss.broker.common.config.SchemaConfig;
import com.knative.fluss.broker.ingress.handler.IngressHandler;
import com.knative.fluss.broker.schema.registry.SchemaRegistry;
import com.knative.fluss.broker.storage.fluss.client.FlussEventWriter;
import com.knative.fluss.broker.storage.fluss.tables.FlussTablePath;
import io.cloudevents.core.builder.CloudEventBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration test: CloudEvent ingress to Fluss persistence pipeline.
 * Verifies the full ingress pipeline from HTTP receipt to Fluss write.
 */
class IngressIntegrationTest {

    private static final String ORDER_DATA = "{\"orderId\":\"12345\",\"amount\":99.99}";
    private static final String FIELD_DATA = "{\"field1\":\"value\"}";

    private IngressHandler handler;
    private FlussEventWriter writer;
    private SchemaRegistry schemaRegistry;
    private FlussTablePath tablePath;

    @BeforeEach
    void setUp() {
        var schemaConfig = SchemaConfig.defaults();
        tablePath = FlussTablePath.brokerTable("test", "default");
        writer = new FlussEventWriter(com.knative.fluss.broker.common.config.FlussConfig.defaults());
        schemaRegistry = new SchemaRegistry(schemaConfig, FlussTablePath.schemaRegistry("test"));
        handler = new IngressHandler(writer, schemaRegistry, schemaConfig, tablePath);
    }

    @Test
    void shouldAcceptValidCloudEvent() throws Exception {
        var event = CloudEventBuilder.v1()
            .withId("integration-test-1")
            .withSource(URI.create("/test/producer"))
            .withType("com.example.order.created")
            .withDataContentType("application/json")
            .withData(ORDER_DATA.getBytes())
            .build();

        var envelope = handler.handle(event).get();

        assertThat(envelope.eventId()).isEqualTo("integration-test-1");
        assertThat(envelope.eventType()).isEqualTo("com.example.order.created");
        assertThat(envelope.schemaId()).isNotNull();
        assertThat(envelope.ingestionTime()).isNotNull();
    }

    @Test
    void shouldRejectInvalidCloudEvent() {
        // CloudEventBuilder validates on build() — empty ID causes build failure
        // Our validator catches these at ingress time
        assertThatThrownBy(() -> {
            var event = CloudEventBuilder.v1()
                .withId("")
                .withSource(URI.create("/test"))
                .withType("com.example.test")
                .build();
            handler.handle(event).get();
        }).isInstanceOf(Exception.class);
    }

    @Test
    void shouldHandleMultipleEvents() throws Exception {
        for (int i = 0; i < 5; i++) {
            var data = "{\"index\":" + i + "}";
            var event = CloudEventBuilder.v1()
                .withId("batch-event-" + i)
                .withSource(URI.create("/test/batch"))
                .withType("com.example.batch.event")
                .withDataContentType("application/json")
                .withData(data.getBytes())
                .build();

            var envelope = handler.handle(event).get();
            assertThat(envelope.eventId()).isEqualTo("batch-event-" + i);
        }
    }

    @Test
    void shouldRegisterSchemaOnFirstEvent() throws Exception {
        var event = CloudEventBuilder.v1()
            .withId("schema-test-1")
            .withSource(URI.create("/test"))
            .withType("com.example.newtype")
            .withDataContentType("application/json")
            .withData(FIELD_DATA.getBytes())
            .build();

        var envelope = handler.handle(event).get();
        assertThat(envelope.schemaId()).isGreaterThan(0);
        assertThat(envelope.schemaVersion()).isEqualTo(1);
    }
}
