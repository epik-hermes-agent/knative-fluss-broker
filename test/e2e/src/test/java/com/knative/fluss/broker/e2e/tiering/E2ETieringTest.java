package com.knative.fluss.broker.e2e.tiering;

import com.knative.fluss.broker.common.config.FlussConfig;
import com.knative.fluss.broker.common.config.SchemaConfig;
import com.knative.fluss.broker.common.model.Envelope;
import com.knative.fluss.broker.ingress.handler.IngressHandler;
import com.knative.fluss.broker.schema.registry.SchemaRegistry;
import com.knative.fluss.broker.storage.fluss.client.FlussConnectionManager;
import com.knative.fluss.broker.storage.fluss.client.FlussEventScanner;
import com.knative.fluss.broker.storage.fluss.client.FlussEventWriter;
import com.knative.fluss.broker.storage.fluss.tables.FlussTableManager;
import com.knative.fluss.broker.storage.fluss.tables.FlussTablePath;
import io.cloudevents.core.builder.CloudEventBuilder;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * End-to-end test: CloudEvent → Ingress → Fluss (datalake enabled) → native tiering → Iceberg.
 *
 * <p>Tests the REAL production pipeline — no workarounds:
 * <ol>
 *   <li>CloudEvents ingested via IngressHandler → Fluss with table.datalake.enabled=true</li>
 *   <li>Fluss-native tiering job (fluss-flink-tiering) tiers data to Iceberg</li>
 *   <li>Iceberg data verified via Flink SQL CLI (docker exec)</li>
 * </ol>
 *
 * <p><b>Infrastructure:</b> docker compose --profile lakehouse up -d
 * Requires: Fluss dual-listener (INTERNAL for Docker, CLIENT for host), Flink, LocalStack, Polaris
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class E2ETieringTest {

    private static final Logger log = LoggerFactory.getLogger(E2ETieringTest.class);
    private static final String FLUSS_ENDPOINT = "fluss://127.0.0.1:9123";

    private static FlussConnectionManager connectionManager;
    private static FlussTableManager tableManager;
    private static FlussEventWriter writer;
    private static FlussEventScanner scanner;
    private static FlussTablePath tablePath;

    @BeforeAll
    static void setUp() {
        var flussConfig = FlussConfig.defaults();
        connectionManager = new FlussConnectionManager(flussConfig);
        tableManager = new FlussTableManager(connectionManager);
        writer = new FlussEventWriter(connectionManager, flussConfig);
        scanner = new FlussEventScanner(connectionManager);

        // Table with datalake enabled (table.datalake.enabled=true in FlussTableManager)
        tablePath = FlussTablePath.brokerTable("tiering", "test");
        tableManager.ensureDatabase("tiering");
        tableManager.ensureBrokerTable(tablePath);
    }

    @AfterAll
    static void tearDown() {
        if (writer != null) writer.close();
        if (scanner != null) scanner.close();
        if (connectionManager != null) connectionManager.close();
    }

    /**
     * Step 1: Ingest CloudEvents through IngressHandler.
     * Verifies CloudEvent → Envelope → Fluss persistence works end-to-end.
     */
    @Test
    @Order(1)
    void shouldIngestCloudEvents() throws Exception {
        var schemaConfig = SchemaConfig.defaults();
        var schemaRegistry = new SchemaRegistry(schemaConfig, FlussTablePath.schemaRegistry("tiering"));
        var ingress = new IngressHandler(writer, schemaRegistry, schemaConfig, tablePath);

        var events = List.of(
            CloudEventBuilder.v1()
                .withId("tier-order-1")
                .withSource(URI.create("/producer/orders"))
                .withType("com.example.order.created")
                .withDataContentType("application/json")
                .withData("{\"orderId\":\"O-100\",\"amount\":99.99}".getBytes())
                .withExtension("correlationid", "corr-001")
                .build(),
            CloudEventBuilder.v1()
                .withId("tier-order-2")
                .withSource(URI.create("/producer/orders"))
                .withType("com.example.order.created")
                .withDataContentType("application/json")
                .withData("{\"orderId\":\"O-101\",\"amount\":149.50}".getBytes())
                .withExtension("correlationid", "corr-002")
                .build(),
            CloudEventBuilder.v1()
                .withId("tier-payment-1")
                .withSource(URI.create("/producer/payments"))
                .withType("com.example.payment.processed")
                .withDataContentType("application/json")
                .withData("{\"paymentId\":\"P-200\",\"status\":\"success\"}".getBytes())
                .build()
        );

        for (var event : events) {
            var envelope = ingress.handle(event).get();
            log.info("Ingested: id={} type={} schemaId={}",
                envelope.eventId(), envelope.eventType(), envelope.schemaId());
            assertThat(envelope.schemaId()).isNotNull();
            assertThat(envelope.ingestionTime()).isNotNull();
        }

        // Verify persistence in Fluss
        Thread.sleep(2000);
        List<Envelope> scanned = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            scanned.addAll(scanner.scan(tablePath, "tier-verify", 50));
            if (scanned.size() >= 3) break;
            Thread.sleep(500);
        }
        assertThat(scanned).hasSizeGreaterThanOrEqualTo(3);
        log.info("Verified {} events persisted in Fluss", scanned.size());
    }

    /**
     * Step 2: Verify data integrity — Fluss scan matches original CloudEvent content.
     */
    @Test
    @Order(2)
    void shouldPreserveDataIntegrity() throws Exception {
        Thread.sleep(1000);
        List<Envelope> scanned = scanner.scan(tablePath, "integrity-check", 50);
        assertThat(scanned).isNotEmpty();

        // Find specific events and verify content
        var order1 = scanned.stream()
            .filter(e -> "tier-order-1".equals(e.eventId()))
            .findFirst().orElseThrow();
        assertThat(order1.eventType()).isEqualTo("com.example.order.created");
        assertThat(order1.eventSource()).isEqualTo("/producer/orders");
        assertThat(new String(order1.data())).contains("\"orderId\":\"O-100\"");
        assertThat(order1.attributes()).containsEntry("correlationid", "corr-001");

        var payment = scanned.stream()
            .filter(e -> "tier-payment-1".equals(e.eventId()))
            .findFirst().orElseThrow();
        assertThat(payment.eventType()).isEqualTo("com.example.payment.processed");
        assertThat(new String(payment.data())).contains("\"paymentId\":\"P-200\"");
    }

    /**
     * Step 3: Wait for Fluss-native tiering to Iceberg, then verify via Flink SQL.
     * The native tiering job (fluss-flink-tiering) runs continuously.
     * We query the Fluss table directly from Flink SQL to verify data reached Iceberg.
     */
    @Test
    @Order(3)
    void shouldTierToIcebergViaNativeTiering() throws Exception {
        log.info("Waiting for Fluss-native tiering to Iceberg...");
        // Tiering job polls every 30s; allow 2 full poll cycles before querying
        Thread.sleep(65000);

        // Query from Fluss catalog using the $lake system table to read Iceberg tiered data
        String sql = String.join("\n",
            "SET sql-client.execution.result-mode=TABLEAU;",
            "",
            "CREATE CATALOG fluss_catalog WITH (",
            "  'type' = 'fluss',",
            "  'bootstrap.servers' = 'fluss-coordinator:9125'",
            ");",
            "USE CATALOG fluss_catalog;",
            "USE knative_tiering;",
            "SELECT event_id, event_type FROM `broker_test$lake` LIMIT 20;"
        );

        java.nio.file.Files.writeString(java.nio.file.Path.of("/tmp/tiering-query.sql"), sql);
        execHost("docker", "cp", "/tmp/tiering-query.sql",
            "fluss-flink-jobmanager:/tmp/tiering-query.sql");

        // Use Awaitility to poll until native tiering has written data to Iceberg
        org.awaitility.Awaitility.await()
            .atMost(java.time.Duration.ofSeconds(180))
            .pollInterval(java.time.Duration.ofSeconds(10))
            .until(() -> {
                execHost("docker", "cp", "/tmp/tiering-query.sql",
                    "fluss-flink-jobmanager:/tmp/tiering-query.sql");
                String result = execHost("docker", "exec", "fluss-flink-jobmanager",
                    "/opt/flink/bin/sql-client.sh", "-f", "/tmp/tiering-query.sql");
                return result.contains("tier-order-1");
            });

        // Final verification
        execHost("docker", "cp", "/tmp/tiering-query.sql",
            "fluss-flink-jobmanager:/tmp/tiering-query.sql");
        String result = execHost("docker", "exec", "fluss-flink-jobmanager",
            "/opt/flink/bin/sql-client.sh", "-f", "/tmp/tiering-query.sql");
        log.info("Tiering verification result:\n{}", result);
        assertThat(result)
            .as("Iceberg should contain tiered data from Fluss")
            .contains("tier-order-1")
            .contains("tier-order-2")
            .contains("tier-payment-1");
    }

    // ─────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────

    private static String execHost(String... args) throws Exception {
        // Build full command with docker binary path — ProcessBuilder in forked JVM
        // may not have docker in PATH on macOS
        String dockerBin = findDocker();
        String[] fullCmd;
        if (args.length > 0 && "docker".equals(args[0])) {
            fullCmd = new String[args.length];
            fullCmd[0] = dockerBin;
            System.arraycopy(args, 1, fullCmd, 1, args.length - 1);
        } else {
            fullCmd = args;
        }
        var pb = new ProcessBuilder(fullCmd);
        pb.redirectErrorStream(true);
        var env = pb.environment();
        env.put("PATH", env.getOrDefault("PATH", "") + ":/usr/local/bin:/opt/homebrew/bin");
        var process = pb.start();
        var output = new String(process.getInputStream().readAllBytes());
        process.waitFor();
        return output;
    }

    private static String findDocker() {
        for (String path : new String[]{"/usr/local/bin/docker", "/opt/homebrew/bin/docker", "docker"}) {
            try {
                if (new java.io.File(path).canExecute()) return path;
            } catch (Exception ignored) {}
        }
        return "docker";
    }
}
