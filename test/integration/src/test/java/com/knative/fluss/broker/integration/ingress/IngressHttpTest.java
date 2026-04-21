package com.knative.fluss.broker.integration.ingress;

import com.knative.fluss.broker.common.config.FlussConfig;
import com.knative.fluss.broker.common.config.SchemaConfig;
import com.knative.fluss.broker.common.model.Envelope;
import com.knative.fluss.broker.ingress.server.IngressServer;
import com.knative.fluss.broker.schema.registry.SchemaRegistry;
import com.knative.fluss.broker.storage.fluss.client.FlussConnectionManager;
import com.knative.fluss.broker.storage.fluss.client.FlussEventScanner;
import com.knative.fluss.broker.storage.fluss.client.FlussEventWriter;
import com.knative.fluss.broker.storage.fluss.tables.FlussTableManager;
import com.knative.fluss.broker.storage.fluss.tables.FlussTablePath;
import org.junit.jupiter.api.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration test: HTTP POST → IngressServer → Fluss pipeline.
 *
 * <p>Tests the HTTP ingress path that external clients use to publish CloudEvents.
 * Starts a real IngressServer on a random port and sends HTTP requests to it.
 *
 * <p>Requires docker-compose services:
 *   docker compose -f docker/docker-compose.yml up -d fluss-coordinator fluss-tablet zookeeper
 */
class IngressHttpTest {

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
    void shouldAcceptBinaryCloudEventViaHttp() throws Exception {
        // Send binary-mode CloudEvent
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/test/default"))
            .header("ce-specversion", "1.0")
            .header("ce-id", "http-binary-1")
            .header("ce-type", "com.example.order.created")
            .header("ce-source", "/producer/orders")
            .header("ce-subject", "order-456")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{\"orderId\":\"456\",\"amount\":199.99}"))
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(202);
        assertThat(response.body()).contains("http-binary-1");

        // Verify event was written to Fluss
        Thread.sleep(5000);  // Fluss needs time to flush writes to the log table
        FlussTablePath tablePath = FlussTablePath.brokerTable("test", "default");
        tableManager.ensureDatabase(tablePath.database());
        tableManager.ensureBrokerTable(tablePath);

        List<Envelope> events = List.of();
        for (int i = 0; i < 10; i++) {
            events = scanner.scan(tablePath, "http-test", 50);
            if (!events.isEmpty()) break;
            Thread.sleep(500);
        }

        assertThat(events).isNotEmpty();
        assertThat(events.stream().anyMatch(e -> "http-binary-1".equals(e.eventId()))).isTrue();
    }

    @Test
    void shouldAcceptStructuredCloudEventViaHttp() throws Exception {
        // Send structured-mode CloudEvent
        String ceJson = """
            {
                "specversion": "1.0",
                "id": "http-structured-1",
                "type": "com.example.payment.processed",
                "source": "/payments/gateway",
                "datacontenttype": "application/json",
                "data": {"paymentId": "P-789", "amount": 49.99}
            }
            """;

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/test/default"))
            .header("Content-Type", "application/cloudevents+json")
            .POST(HttpRequest.BodyPublishers.ofString(ceJson))
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(202);
        assertThat(response.body()).contains("http-structured-1");
    }

    @Test
    void shouldRejectInvalidCloudEvent_missingCeId() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/test/default"))
            .header("ce-specversion", "1.0")
            // Missing ce-id
            .header("ce-type", "com.example.test")
            .header("ce-source", "/test")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{}"))
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("id");
    }

    @Test
    void shouldRejectInvalidPath() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/only-one-segment"))
            .header("ce-specversion", "1.0")
            .header("ce-id", "test-1")
            .header("ce-type", "com.example.test")
            .header("ce-source", "/test")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{}"))
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("Invalid path");
    }

    @Test
    void shouldReturnHealthCheck() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/health"))
            .GET()
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("ok");
    }

    @Test
    void shouldRejectNonPostMethod() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/test/default"))
            .GET()
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(405);
    }
}
