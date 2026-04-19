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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * End-to-end test: CloudEvent → Ingress → Fluss → Flink SQL → Iceberg → verification.
 *
 * <p>Tests the complete lakehouse pipeline:
 * <ol>
 *   <li>CloudEvent ingestion through IngressHandler → Fluss</li>
 *   <li>Scanner reads events back from Fluss (data integrity verification)</li>
 *   <li>Flink SQL tiers data from Fluss to Iceberg</li>
 *   <li>Iceberg data verified through Flink SQL query</li>
 * </ol>
 *
 * <p><b>Infrastructure:</b> Requires docker-compose lakehouse profile running.
 * Run: {@code docker compose -f docker/docker-compose.yml --profile lakehouse up -d}
 *
 * <p><b>Note on Fluss-native tiering:</b> The fluss-flink-tiering job has a docker-compose
 * networking limitation (advertised listener = localhost, unreachable from Docker network).
 * This test uses Flink SQL directly (via docker exec) to tier data to Iceberg, which tests
 * the same data integrity path.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class E2ELakehouseTest {

    private static final Logger log = LoggerFactory.getLogger(E2ELakehouseTest.class);

    private static final String FLUSS_ENDPOINT = "fluss://127.0.0.1:9123";

    private static FlussConnectionManager connectionManager;
    private static FlussTableManager tableManager;
    private static FlussEventWriter writer;
    private static FlussEventScanner scanner;
    private static FlussTablePath tablePath;

    @BeforeAll
    static void initInfrastructure() {
        var flussConfig = FlussConfig.defaults();
        connectionManager = new FlussConnectionManager(flussConfig);
        tableManager = new FlussTableManager(connectionManager);
        writer = new FlussEventWriter(connectionManager, flussConfig);
        scanner = new FlussEventScanner(connectionManager);

        tablePath = FlussTablePath.brokerTable("lakehouse", "test");
        tableManager.ensureDatabase("lakehouse");
        tableManager.ensureBrokerTable(tablePath);
    }

    @AfterAll
    static void cleanup() {
        if (writer != null) writer.close();
        if (scanner != null) scanner.close();
        if (connectionManager != null) connectionManager.close();
    }

    /**
     * Step 1: Ingest CloudEvents through IngressHandler.
     * Verifies correct mapping from CloudEvent → Envelope → Fluss persistence.
     */
    @Test
    @Order(1)
    void shouldIngestCloudEventsThroughIngress() throws Exception {
        var schemaConfig = SchemaConfig.defaults();
        var schemaRegistry = new SchemaRegistry(schemaConfig, FlussTablePath.schemaRegistry("lakehouse"));
        var ingress = new IngressHandler(writer, schemaRegistry, schemaConfig, tablePath);

        var events = List.of(
            CloudEventBuilder.v1()
                .withId("lakehouse-order-1")
                .withSource(URI.create("/producer/orders"))
                .withType("com.example.order.created")
                .withDataContentType("application/json")
                .withData("{\"orderId\":\"O-100\",\"amount\":99.99}".getBytes())
                .withExtension("correlationid", "corr-001")
                .withExtension("region", "us-west-2")
                .build(),
            CloudEventBuilder.v1()
                .withId("lakehouse-order-2")
                .withSource(URI.create("/producer/orders"))
                .withType("com.example.order.created")
                .withDataContentType("application/json")
                .withData("{\"orderId\":\"O-101\",\"amount\":149.50}".getBytes())
                .withExtension("correlationid", "corr-002")
                .withExtension("region", "us-east-1")
                .build(),
            CloudEventBuilder.v1()
                .withId("lakehouse-payment-1")
                .withSource(URI.create("/producer/payments"))
                .withType("com.example.payment.processed")
                .withDataContentType("application/json")
                .withData("{\"paymentId\":\"P-200\",\"status\":\"success\"}".getBytes())
                .build()
        );

        var envelopes = new ArrayList<Envelope>();
        for (var event : events) {
            envelopes.add(ingress.handle(event).get());
        }

        assertThat(envelopes).hasSize(3);
        assertThat(envelopes.get(0).eventId()).isEqualTo("lakehouse-order-1");
        assertThat(envelopes.get(0).eventType()).isEqualTo("com.example.order.created");
        assertThat(envelopes.get(0).attributes()).containsEntry("correlationid", "corr-001");
        assertThat(envelopes.get(0).schemaId()).isNotNull();
        assertThat(envelopes.get(0).ingestionTime()).isNotNull();
    }

    /**
     * Step 2: Scan events from Fluss and verify data integrity.
     * Ensures the data persisted through Ingress matches the original CloudEvent content.
     */
    @Test
    @Order(2)
    void shouldScanBackFromFlussWithIntegrity() throws Exception {
        Thread.sleep(2000);

        List<Envelope> scanned = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            scanned.addAll(scanner.scan(tablePath, "lakehouse-integrity", 50));
            if (scanned.size() >= 3) break;
            Thread.sleep(500);
        }

        assertThat(scanned).hasSizeGreaterThanOrEqualTo(3);

        // Verify order events
        var orderEvents = scanned.stream()
            .filter(e -> "com.example.order.created".equals(e.eventType()))
            .toList();
        assertThat(orderEvents).hasSizeGreaterThanOrEqualTo(2);
        assertThat(orderEvents).allMatch(e -> "/producer/orders".equals(e.eventSource()));

        // Verify data content
        var order1 = scanned.stream()
            .filter(e -> "lakehouse-order-1".equals(e.eventId()))
            .findFirst().orElseThrow();
        assertThat(new String(order1.data())).contains("\"orderId\":\"O-100\"");
        assertThat(new String(order1.data())).contains("\"amount\":99.99");
        assertThat(order1.attributes()).containsEntry("region", "us-west-2");

        // Verify payment event
        var payment = scanned.stream()
            .filter(e -> "lakehouse-payment-1".equals(e.eventId()))
            .findFirst().orElseThrow();
        assertThat(payment.eventType()).isEqualTo("com.example.payment.processed");
        assertThat(new String(payment.data())).contains("\"paymentId\":\"P-200\"");
    }

    /**
     * Step 3: Submit Flink SQL job to tier data from Fluss to Iceberg.
     * The INSERT runs as a streaming job — data arrives asynchronously.
     */
    @Test
    @Order(3)
    void shouldTierDataToIcebergViaFlinkSql() throws Exception {
        // Write test data for tiering verification
        for (int i = 0; i < 5; i++) {
            var envelope = Envelope.builder()
                .eventId("tier-event-" + i)
                .eventSource("/producer/tiering")
                .eventType("com.example.tier.test")
                .contentType("application/json")
                .data(("{\"index\":" + i + ",\"value\":\"tier-value-" + i + "\"}").getBytes())
                .schemaId(1)
                .schemaVersion(1)
                .build();
            writer.write(tablePath, envelope).get();
        }
        Thread.sleep(3000);

        // Verify events are in Fluss
        List<Envelope> tierScanned = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            tierScanned.addAll(scanner.scan(tablePath, "tier-scan", 50));
            if (tierScanned.size() >= 8) break;
            Thread.sleep(500);
        }
        assertThat(tierScanned).hasSizeGreaterThanOrEqualTo(8);
        log.info("Verified {} total events in Fluss before tiering", tierScanned.size());

        // Submit streaming INSERT job: Fluss → Iceberg (runs asynchronously)
        String sql = String.join("\n",
            "CREATE CATALOG fluss_catalog WITH (",
            "  'type' = 'fluss',",
            "  'bootstrap.servers' = 'fluss-coordinator:9125'",
            ");",
            "",
            "CREATE CATALOG iceberg_catalog WITH (",
            "  'type' = 'iceberg',",
            "  'catalog-type' = 'rest',",
            "  'uri' = 'http://polaris:8181/api/catalog',",
            "  'warehouse' = 'fluss',",
            "  'credential' = 'root:s3cr3t',",
            "  'scope' = 'PRINCIPAL_ROLE:ALL',",
            "  's3.endpoint' = 'http://localstack:4566',",
            "  's3.access-key-id' = 'test',",
            "  's3.secret-access-key' = 'test',",
            "  's3.path-style-access' = 'true'",
            ");",
            "",
            "USE CATALOG iceberg_catalog;",
            "CREATE DATABASE IF NOT EXISTS lakehouse;",
            "",
            "CREATE TABLE IF NOT EXISTS lakehouse.tiered_events (",
            "  event_id STRING,",
            "  event_source STRING,",
            "  event_type STRING,",
            "  content_type STRING,",
            "  data BYTES,",
            "  schema_id INT,",
            "  schema_version INT,",
            "  ingestion_time TIMESTAMP(3)",
            ");",
            "",
            "INSERT INTO lakehouse.tiered_events",
            "SELECT event_id, event_source, event_type, content_type, data, schema_id, schema_version, ingestion_time",
            "FROM fluss_catalog.knative_lakehouse.broker_test;"
        );

        // Submit the streaming job (it runs in the Flink cluster, not the CLI session)
        java.nio.file.Files.writeString(java.nio.file.Path.of("/tmp/fluss-tiering.sql"), sql);
        execDocker("cp", "/tmp/fluss-tiering.sql", "fluss-flink-jobmanager:/tmp/fluss-tiering.sql");
        String result = execDocker("exec", "fluss-flink-jobmanager",
            "/opt/flink/bin/sql-client.sh", "-f", "/tmp/fluss-tiering.sql");
        log.info("Tiering job submission result:\n{}", result);
        assertThat(result).contains("successfully submitted");
    }

    /**
     * Step 4: Verify Iceberg data via Fluss $lake system table.
     * Queries the Fluss table's Iceberg tiered data to confirm the tiering pipeline works.
     */
    @Test
    @Order(4)
    void shouldReadBackFromIceberg() throws Exception {
        // The streaming INSERT from step 3 writes to Iceberg, but also the native
        // tiering job continuously tiers data. Verify via the $lake system table.
        // Wait for tiering job to pick up the table (polls every 30s)
        Thread.sleep(35000);

        java.nio.file.Path sqlFile = java.nio.file.Path.of("/tmp/fluss-verify.sql");
        java.nio.file.Files.writeString(sqlFile, String.join("\n",
            "SET sql-client.execution.result-mode=TABLEAU;",
            "",
            "CREATE CATALOG fluss_catalog WITH (",
            "  'type' = 'fluss',",
            "  'bootstrap.servers' = 'fluss-coordinator:9125'",
            ");",
            "USE CATALOG fluss_catalog;",
            "USE knative_lakehouse;",
            "SELECT event_id, event_type FROM `broker_test$lake` LIMIT 20;"
        ));

        // Use Awaitility to poll until tiering has written data
        org.awaitility.Awaitility.await()
            .atMost(java.time.Duration.ofSeconds(180))
            .pollInterval(java.time.Duration.ofSeconds(10))
            .until(() -> {
                execDocker("cp", "/tmp/fluss-verify.sql", "fluss-flink-jobmanager:/tmp/fluss-verify.sql");
                String result = execDocker("exec", "fluss-flink-jobmanager",
                    "/opt/flink/bin/sql-client.sh", "-f", "/tmp/fluss-verify.sql");
                return result.contains("lakehouse-order-1") || result.contains("tier-event-0");
            });

        // Final verification
        execDocker("cp", "/tmp/fluss-verify.sql", "fluss-flink-jobmanager:/tmp/fluss-verify.sql");
        String result = execDocker("exec", "fluss-flink-jobmanager",
            "/opt/flink/bin/sql-client.sh", "-f", "/tmp/fluss-verify.sql");
        log.info("Iceberg verification result:\n{}", result);
        assertThat(result).containsAnyOf("lakehouse-order-1", "tier-event-0");
    }

    // ─────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────

    private static String execDocker(String... args) throws Exception {
        // Build full command with docker binary path — ProcessBuilder in forked JVM
        // may not have docker in PATH on macOS
        String dockerBin = findDocker();
        String[] fullCmd = new String[args.length + 1];
        fullCmd[0] = dockerBin;
        System.arraycopy(args, 0, fullCmd, 1, args.length);
        var pb = new ProcessBuilder(fullCmd);
        pb.redirectErrorStream(true);
        // Ensure PATH includes common docker locations
        var env = pb.environment();
        env.put("PATH", env.getOrDefault("PATH", "") + ":/usr/local/bin:/opt/homebrew/bin");
        var process = pb.start();
        var output = new String(process.getInputStream().readAllBytes());
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            log.warn("Docker command failed (exit {}): {}", exitCode, output.substring(0, Math.min(500, output.length())));
        }
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
