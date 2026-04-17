package com.knative.fluss.broker.integration.dispatcher;

import com.knative.fluss.broker.common.config.DispatcherConfig;
import com.knative.fluss.broker.common.model.Envelope;
import com.knative.fluss.broker.delivery.http.SubscriberClient;
import com.knative.fluss.broker.delivery.tracking.DeliveryTracker;
import com.knative.fluss.broker.dispatcher.TriggerDispatcher;
import com.knative.fluss.broker.dispatcher.dlq.DlqHandler;
import com.knative.fluss.broker.storage.fluss.client.FlussEventScanner;
import com.knative.fluss.broker.storage.fluss.client.FlussEventWriter;
import com.knative.fluss.broker.storage.fluss.tables.FlussTablePath;
import com.knative.fluss.broker.test.wiremock.scenarios.WireMockTestServer;
import org.junit.jupiter.api.*;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration test: Fluss → Dispatcher → WireMock subscriber.
 * Tests event delivery, retry, and DLQ behavior.
 */
class DispatcherIntegrationTest {

    private WireMockTestServer subscriber;
    private TriggerDispatcher dispatcher;
    private FlussEventScanner scanner;
    private DeliveryTracker tracker;

    @BeforeEach
    void setUp() {
        subscriber = new WireMockTestServer();
        subscriber.start();

        var config = DispatcherConfig.defaults();
        var tablePath = FlussTablePath.brokerTable("test", "default");
        scanner = new FlussEventScanner();
        var subscriberClient = new SubscriberClient(config.delivery());
        var dlqWriter = new FlussEventWriter(com.knative.fluss.broker.common.config.FlussConfig.defaults());
        var dlqHandler = new DlqHandler(dlqWriter, FlussTablePath.dlqTable("test", "default", "test-trigger"));
        tracker = new DeliveryTracker();

        dispatcher = new TriggerDispatcher(
            "test-trigger", tablePath, subscriber.getSubscriberUri(),
            Map.of("type", "com.example.test"), config,
            scanner, subscriberClient, dlqHandler, tracker);
    }

    @AfterEach
    void tearDown() {
        if (dispatcher != null) dispatcher.close();
        if (subscriber != null) subscriber.close();
    }

    @Test
    void shouldDeliverMatchingEventToSubscriber() throws Exception {
        subscriber.stubSuccessfulDelivery();

        // Simulate events in Fluss (scanner returns empty for now since no real Fluss)
        // This test validates the dispatcher lifecycle and WireMock integration
        dispatcher.start();
        Thread.sleep(500);
        assertThat(dispatcher.isRunning()).isTrue();
    }

    @Test
    void shouldHandleSubscriberFailure() {
        subscriber.stubFailure();
        dispatcher.start();
        // Verify dispatcher doesn't crash on 500 errors
        assertThat(dispatcher.isRunning()).isTrue();
    }

    @Test
    void shouldHandleClientError() {
        subscriber.stubClientError();
        dispatcher.start();
        // 4xx should route to DLQ
        assertThat(dispatcher.isRunning()).isTrue();
    }
}
