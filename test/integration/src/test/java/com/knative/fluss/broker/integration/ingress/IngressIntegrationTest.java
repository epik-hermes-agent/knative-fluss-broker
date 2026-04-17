package com.knative.fluss.broker.integration.ingress;

import com.knative.fluss.broker.common.config.FlussConfig;
import com.knative.fluss.broker.common.config.SchemaConfig;
import com.knative.fluss.broker.ingress.handler.IngressHandler;
import com.knative.fluss.broker.schema.registry.SchemaRegistry;
import com.knative.fluss.broker.storage.fluss.client.FlussConnectionManager;
import com.knative.fluss.broker.storage.fluss.client.FlussEventWriter;
import com.knative.fluss.broker.storage.fluss.tables.FlussTableManager;
import com.knative.fluss.broker.storage.fluss.tables.FlussTablePath;
import io.cloudevents.core.builder.CloudEventBuilder;
import org.junit.jupiter.api.*;

import java.net.URI;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration test: CloudEvent ingress to Fluss persistence pipeline.
 * Requires docker-compose services running:
 *   docker compose -f docker/docker-compose.yml up -d fluss-coordinator fluss-tablet zookeeper
 *
 * <p>Uses the Fluss client to connect to the docker-compose Fluss cluster
 * at 127.0.0.1:9123 (fixed port mapping in docker-compose.yml).
 */
class IngressIntegrationTest {

    private static final String FLUSS_ENDPOINT = "fluss://127.0.0.1:9123";

    private IngressHandler handler;
    private FlussEventWriter writer;
    private FlussConnectionManager connectionManager;
    private FlussTableManager tableManager;
    private FlussTablePath tablePath;

    @BeforeEach
    void setUp() {
        FlussConfig flussConfig = new FlussConfig(
                FLUSS_ENDPOINT,
                100, 50, 5000, 3, 100
        );
        connectionManager = new FlussConnectionManager(flussConfig);
        tableManager = new FlussTableManager(connectionManager);

        // Create the database and table in real Fluss
        tablePath = FlussTablePath.brokerTable("test", "default");
        tableManager.ensureDatabase("test");
        tableManager.ensureBrokerTable(tablePath);

        SchemaConfig schemaConfig = SchemaConfig.defaults();
        writer = new FlussEventWriter(connectionManager, flussConfig);
        var schemaRegistry = new SchemaRegistry(schemaConfig, FlussTablePath.schemaRegistry("test"));
        handler = new IngressHandler(writer, schemaRegistry, schemaConfig, tablePath);
    }

    @AfterEach
    void tearDown() {
        if (writer != null) writer.close();
        if (connectionManager != null) connectionManager.close();
    }

    @Test
    void shouldAcceptValidCloudEvent() throws Exception {
        var event = CloudEventBuilder.v1()
                .withId("integration-test-1")
                .withSource(URI.create("/test/producer"))
                .withType("com.example.order.created")
                .withDataContentType("application/json")
                .withData("{\"orderId\":\"12345\",\"amount\":99.99}".getBytes())
                .build();

        var envelope = handler.handle(event).get();

        assertThat(envelope.eventId()).isEqualTo("integration-test-1");
        assertThat(envelope.eventType()).isEqualTo("com.example.order.created");
        assertThat(envelope.schemaId()).isNotNull();
        assertThat(envelope.ingestionTime()).isNotNull();
    }

    @Test
    void shouldRejectInvalidCloudEvent() {
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
                .withData("{\"field1\":\"value\"}".getBytes())
                .build();

        var envelope = handler.handle(event).get();
        assertThat(envelope.schemaId()).isGreaterThan(0);
        assertThat(envelope.schemaVersion()).isEqualTo(1);
    }
}
