package com.knative.fluss.broker.e2e.basic;

import com.knative.fluss.broker.common.config.*;
import com.knative.fluss.broker.common.model.Envelope;
import com.knative.fluss.broker.ingress.server.IngressServer;
import com.knative.fluss.broker.schema.registry.SchemaRegistry;
import com.knative.fluss.broker.storage.fluss.client.FlussConnectionManager;
import com.knative.fluss.broker.storage.fluss.client.FlussEventScanner;
import com.knative.fluss.broker.storage.fluss.client.FlussEventWriter;
import com.knative.fluss.broker.storage.fluss.tables.FlussTableManager;
import com.knative.fluss.broker.storage.fluss.tables.FlussTablePath;
import com.knative.fluss.broker.test.wiremock.scenarios.WireMockTestServer;
import org.junit.jupiter.api.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * End-to-end test: HTTP POST → IngressServer → Fluss → Scanner (full HTTP pipeline).
 *
 * <p>This test validates the REAL client-facing path — what happens when a client
 * sends a CloudEvent via HTTP to the broker ingress. Unlike {@link E2EDeliveryTest}
 * which calls IngressHandler directly, this test exercises the HTTP server layer.
 *
 * <p>Requires docker-compose services:
 *   docker compose -f docker/docker-compose.yml up -d fluss-coordinator fluss-tablet zookeeper
 */
class E2EDeliveryHttpTest {

    private static final String FLUSS_ENDPOINT = "fluss://127.0.0.1:9123";

    private FlussConnectionManager connectionManager;
    private FlussTableManager tableManager;
    private FlussEventWriter writer;
    private FlussEventScanner scanner;
    private IngressServer server;
    private HttpClient httpClient;
    private int port;

    @BeforeEach
    void setUp() throws Exception {
        FlussConfig flussConfig = new FlussConfig(FLUSS_ENDPOINT, 100, 50, 10000, 3, 100);
        connectionManager = new FlussConnectionManager(flussConfig);
        tableManager = new FlussTableManager(connectionManager);
        writer = new FlussEventWriter(connectionManager, flussConfig);
        scanner = new FlussEventScanner(connectionManager);

        SchemaConfig schemaConfig = SchemaConfig.defaults();

        // Start ingress server on a random free port
        server = new IngressServer(connectionManager, tableManager, writer, schemaConfig, 0);
        server.start();
        port = server.getPort();

        httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.close();
        if (writer != null) writer.close();
        if (scanner != null) scanner.close();
        if (connectionManager != null) connectionManager.close();
    }

    @Test
    void shouldCompleteFullPipelineHttpPostToFluss() throws Exception {
        // Step 1: Send a CloudEvent via HTTP (binary mode)
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/e2e/default"))
            .header("ce-specversion", "1.0")
            .header("ce-id", "e2e-http-pipeline-1")
            .header("ce-type", "com.example.order.created")
            .header("ce-source", "/producer/orders")
            .header("ce-subject", "order-123")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{\"orderId\":\"123\",\"amount\":99.99}"))
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        // Verify HTTP response
        assertThat(response.statusCode()).isEqualTo(202);
        assertThat(response.body()).contains("e2e-http-pipeline-1");

        // Step 2: Scan the event from Fluss
        FlussTablePath tablePath = FlussTablePath.brokerTable("e2e", "default");
        tableManager.ensureDatabase(tablePath.database());
        tableManager.ensureBrokerTable(tablePath);

        Thread.sleep(2000);

        List<Envelope> scanned = List.of();
        for (int i = 0; i < 15; i++) {
            scanned = scanner.scan(tablePath, "e2e-http-trigger", 50);
            if (!scanned.isEmpty()) break;
            Thread.sleep(500);
        }

        assertThat(scanned).isNotEmpty();
        Envelope readBack = scanned.stream()
                .filter(e -> "e2e-http-pipeline-1".equals(e.eventId()))
                .findFirst()
                .orElseThrow();

        assertThat(readBack.eventType()).isEqualTo("com.example.order.created");
        assertThat(readBack.eventSource()).isEqualTo("/producer/orders");
        assertThat(readBack.schemaId()).isNotNull();
        assertThat(readBack.ingestionTime()).isNotNull();
    }

    @Test
    void shouldHandleMultipleHttpPublishes() throws Exception {
        // Send 3 events via HTTP
        for (int i = 0; i < 3; i++) {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/e2e/default"))
                .header("ce-specversion", "1.0")
                .header("ce-id", "e2e-http-multi-" + i)
                .header("ce-type", "com.example.test.event")
                .header("ce-source", "/test/multi")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"index\":" + i + "}"))
                .build();

            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(resp.statusCode()).isEqualTo(202);
        }

        // Verify all 3 events are in Fluss
        FlussTablePath tablePath = FlussTablePath.brokerTable("e2e", "default");
        tableManager.ensureDatabase(tablePath.database());
        tableManager.ensureBrokerTable(tablePath);

        Thread.sleep(3000);

        List<Envelope> allEvents = new java.util.ArrayList<>();
        for (int i = 0; i < 15; i++) {
            List<Envelope> batch = scanner.scan(tablePath, "e2e-multi-trigger", 50);
            allEvents.addAll(batch);
            if (allEvents.size() >= 3) break;
            Thread.sleep(500);
        }

        List<String> ids = allEvents.stream().map(Envelope::eventId).toList();
        assertThat(ids).contains("e2e-http-multi-0", "e2e-http-multi-1", "e2e-http-multi-2");
    }

    @Test
    void shouldHandleStructuredModePublish() throws Exception {
        String ceJson = """
            {
                "specversion": "1.0",
                "id": "e2e-structured-1",
                "type": "com.example.payment.refunded",
                "source": "/payments/refund",
                "datacontenttype": "application/json",
                "data": {"refundId": "R-100", "amount": 25.00}
            }
            """;

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/e2e/default"))
            .header("Content-Type", "application/cloudevents+json")
            .POST(HttpRequest.BodyPublishers.ofString(ceJson))
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(202);
        assertThat(response.body()).contains("e2e-structured-1");
    }
}
