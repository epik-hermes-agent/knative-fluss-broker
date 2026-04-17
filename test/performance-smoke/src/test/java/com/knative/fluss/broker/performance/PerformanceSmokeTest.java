package com.knative.fluss.broker.performance;

import com.knative.fluss.broker.common.config.DispatcherConfig;
import com.knative.fluss.broker.common.config.FlussConfig;
import com.knative.fluss.broker.common.model.Envelope;
import com.knative.fluss.broker.delivery.http.SubscriberClient;
import com.knative.fluss.broker.delivery.tracking.DeliveryTracker;
import com.knative.fluss.broker.dispatcher.TriggerDispatcher;
import com.knative.fluss.broker.dispatcher.dlq.DlqHandler;
import com.knative.fluss.broker.storage.fluss.client.FlussConnectionManager;
import com.knative.fluss.broker.storage.fluss.client.FlussEventScanner;
import com.knative.fluss.broker.storage.fluss.client.FlussEventWriter;
import com.knative.fluss.broker.storage.fluss.tables.FlussTableManager;
import com.knative.fluss.broker.storage.fluss.tables.FlussTablePath;
import com.knative.fluss.broker.test.wiremock.scenarios.WireMockTestServer;
import org.junit.jupiter.api.*;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/**
 * Performance smoke tests for the Fluss-backed broker.
 *
 * <p>These are NOT benchmarks. They verify bounded in-flight behavior,
 * backpressure effectiveness, and basic throughput sanity checks.
 *
 * <p>Requires docker-compose Fluss cluster running.
 */
class PerformanceSmokeTest {

    private static final String FLUSS_ENDPOINT = "fluss://127.0.0.1:9123";
    private static final int BATCH_SIZE = 100;

    private FlussConnectionManager connectionManager;
    private FlussEventWriter writer;
    private FlussTablePath tablePath;

    @BeforeEach
    void setUp() {
        FlussConfig config = new FlussConfig(FLUSS_ENDPOINT, 50, 100, 10000, 3, 100);
        connectionManager = new FlussConnectionManager(config);
        writer = new FlussEventWriter(connectionManager, config);

        var tableManager = new FlussTableManager(connectionManager);
        tablePath = FlussTablePath.brokerTable("perf", "smoke");
        tableManager.ensureDatabase("perf");
        tableManager.ensureBrokerTable(tablePath);
    }

    @AfterEach
    void tearDown() {
        if (writer != null) writer.close();
        if (connectionManager != null) connectionManager.close();
    }

    @Test
    void shouldWriteBatchWithinReasonableTime() throws Exception {
        var events = new java.util.ArrayList<Envelope>();
        for (int i = 0; i < BATCH_SIZE; i++) {
            events.add(Envelope.builder()
                    .eventId("perf-" + i)
                    .eventSource("/perf/producer")
                    .eventType("com.example.perf.event")
                    .contentType("application/json")
                    .data(("{\"i\":" + i + "}").getBytes())
                    .schemaId(1)
                    .schemaVersion(1)
                    .build());
        }

        Instant start = Instant.now();
        writer.writeBatch(tablePath, events).get();
        Duration elapsed = Duration.between(start, Instant.now());

        System.out.printf("Wrote %d events in %d ms (%.0f events/sec)%n",
                BATCH_SIZE, elapsed.toMillis(),
                BATCH_SIZE * 1000.0 / elapsed.toMillis());

        // Sanity: should complete within 30 seconds (generous for local dev)
        assertThat(elapsed).isLessThan(Duration.ofSeconds(30));
    }

    @Test
    void shouldScanEventsWithinReasonableTime() throws Exception {
        // Write events first
        var events = new java.util.ArrayList<Envelope>();
        for (int i = 0; i < BATCH_SIZE; i++) {
            events.add(Envelope.builder()
                    .eventId("scan-perf-" + i)
                    .eventSource("/perf/scanner")
                    .eventType("com.example.perf.scan")
                    .contentType("application/json")
                    .data(("{\"i\":" + i + "}").getBytes())
                    .schemaId(1)
                    .schemaVersion(1)
                    .build());
        }
        writer.writeBatch(tablePath, events).get();
        Thread.sleep(2000);

        // Scan all events
        var scanner = new FlussEventScanner(connectionManager);
        try {
            Instant start = Instant.now();
            var allEvents = new java.util.ArrayList<Envelope>();
            for (int i = 0; i < 30; i++) {
                var batch = scanner.scan(tablePath, "perf-scan-trigger", 100);
                allEvents.addAll(batch);
                if (allEvents.size() >= BATCH_SIZE) break;
                Thread.sleep(200);
            }
            Duration elapsed = Duration.between(start, Instant.now());

            System.out.printf("Scanned %d events in %d ms%n",
                    allEvents.size(), elapsed.toMillis());

            assertThat(allEvents).hasSizeGreaterThanOrEqualTo(BATCH_SIZE);
            assertThat(elapsed).isLessThan(Duration.ofSeconds(30));
        } finally {
            scanner.close();
        }
    }

    @Test
    void shouldVerifyBoundedInFlightWithDispatcher() throws Exception {
        // Write events
        var events = new java.util.ArrayList<Envelope>();
        for (int i = 0; i < 50; i++) {
            events.add(Envelope.builder()
                    .eventId("bounded-" + i)
                    .eventSource("/perf/dispatch")
                    .eventType("com.example.order.created")
                    .contentType("application/json")
                    .data(("{\"i\":" + i + "}").getBytes())
                    .schemaId(1)
                    .schemaVersion(1)
                    .build());
        }
        writer.writeBatch(tablePath, events).get();
        Thread.sleep(1000);

        // Start a fast subscriber via WireMock
        var subscriber = new WireMockTestServer();
        subscriber.start();
        subscriber.stubSuccessfulDelivery();

        var scanner = new FlussEventScanner(connectionManager);
        var config = DispatcherConfig.defaults();
        var subscriberClient = new SubscriberClient(config.delivery());
        var dlqWriter = new FlussEventWriter(connectionManager, FlussConfig.defaults());
        var dlqTablePath = FlussTablePath.dlqTable("perf", "smoke", "perf-trigger");
        var tableManager = new FlussTableManager(connectionManager);
        tableManager.ensureDlqTable(dlqTablePath);
        var dlqHandler = new DlqHandler(dlqWriter, dlqTablePath);
        var tracker = new DeliveryTracker();

        AtomicInteger maxInFlight = new AtomicInteger(0);

        try (var dispatcher = new TriggerDispatcher(
                "perf-trigger", tablePath, subscriber.getSubscriberUri(),
                Map.of("type", "com.example.order.created"),
                config, scanner, subscriberClient, dlqHandler, tracker)) {

            dispatcher.start();

            // Monitor for 10 seconds
            Instant deadline = Instant.now().plusSeconds(10);
            while (Instant.now().isBefore(deadline)) {
                int totalInFlight = dispatcher.getLanes().stream()
                        .mapToInt(l -> l.getInflightCount())
                        .sum();
                maxInFlight.updateAndGet(current -> Math.max(current, totalInFlight));
                Thread.sleep(100);
            }
        } finally {
            subscriber.close();
            dlqWriter.close();
            scanner.close();
        }

        System.out.printf("Max in-flight deliveries: %d (max concurrency: %d)%n",
                maxInFlight.get(), config.maxConcurrency());

        // In-flight should be bounded by maxConcurrency
        assertThat(maxInFlight.get()).isLessThanOrEqualTo(config.maxConcurrency() * 2);
    }
}
