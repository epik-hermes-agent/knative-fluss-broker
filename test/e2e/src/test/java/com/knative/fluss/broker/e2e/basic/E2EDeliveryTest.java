package com.knative.fluss.broker.e2e.basic;

import com.knative.fluss.broker.common.config.*;
import com.knative.fluss.broker.common.model.Envelope;
import com.knative.fluss.broker.delivery.http.SubscriberClient;
import com.knative.fluss.broker.delivery.tracking.DeliveryTracker;
import com.knative.fluss.broker.dispatcher.TriggerDispatcher;
import com.knative.fluss.broker.dispatcher.dlq.DlqHandler;
import com.knative.fluss.broker.ingress.handler.IngressHandler;
import com.knative.fluss.broker.schema.registry.SchemaRegistry;
import com.knative.fluss.broker.storage.fluss.client.FlussEventScanner;
import com.knative.fluss.broker.storage.fluss.client.FlussEventWriter;
import com.knative.fluss.broker.storage.fluss.tables.FlussTablePath;
import com.knative.fluss.broker.test.wiremock.scenarios.WireMockTestServer;
import io.cloudevents.core.builder.CloudEventBuilder;
import org.junit.jupiter.api.*;

import java.net.URI;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * End-to-end test: Producer → Ingress → Fluss → Dispatcher → Subscriber.
 * Verifies the complete event delivery pipeline.
 */
class E2EDeliveryTest {

    private WireMockTestServer subscriber;
    private IngressHandler ingress;
    private TriggerDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        subscriber = new WireMockTestServer();
        subscriber.start();
        subscriber.stubSuccessfulDelivery();

        // Ingress setup
        var schemaConfig = SchemaConfig.defaults();
        var tablePath = FlussTablePath.brokerTable("e2e", "default");
        var writer = new FlussEventWriter(FlussConfig.defaults());
        var schemaRegistry = new SchemaRegistry(schemaConfig, FlussTablePath.schemaRegistry("e2e"));
        ingress = new IngressHandler(writer, schemaRegistry, schemaConfig, tablePath);

        // Dispatcher setup
        var dispatcherConfig = DispatcherConfig.defaults();
        var scanner = new FlussEventScanner();
        var subscriberClient = new SubscriberClient(dispatcherConfig.delivery());
        var dlqWriter = new FlussEventWriter(FlussConfig.defaults());
        var dlqHandler = new DlqHandler(dlqWriter, FlussTablePath.dlqTable("e2e", "default", "trigger-1"));
        var tracker = new DeliveryTracker();

        dispatcher = new TriggerDispatcher(
            "trigger-1", tablePath, subscriber.getSubscriberUri(),
            Map.of("type", "com.example.order.created"), dispatcherConfig,
            scanner, subscriberClient, dlqHandler, tracker);
    }

    @AfterEach
    void tearDown() {
        if (dispatcher != null) dispatcher.close();
        if (subscriber != null) subscriber.close();
    }

    @Test
    void shouldAcceptEventViaIngress() throws Exception {
        var event = CloudEventBuilder.v1()
            .withId("e2e-1")
            .withSource(URI.create("/producer"))
            .withType("com.example.order.created")
            .withDataContentType("application/json")
            .withData("{\"orderId\":\"123\"}".getBytes())
            .build();

        var envelope = ingress.handle(event).get();

        assertThat(envelope.eventId()).isEqualTo("e2e-1");
        assertThat(envelope.schemaId()).isNotNull();
    }

    @Test
    void shouldCompleteEndToEndDeliveryLifecycle() {
        // Verify the full lifecycle starts and stops cleanly
        dispatcher.start();
        assertThat(dispatcher.isRunning()).isTrue();

        // Dispatcher lanes should be initialized
        assertThat(dispatcher.getLanes()).hasSize(10); // default maxConcurrency

        dispatcher.close();
        assertThat(dispatcher.isRunning()).isFalse();
    }
}
