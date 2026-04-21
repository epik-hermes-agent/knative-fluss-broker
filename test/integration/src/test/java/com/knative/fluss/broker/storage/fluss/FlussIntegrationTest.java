package com.knative.fluss.broker.storage.fluss;

import com.knative.fluss.broker.common.config.FlussConfig;
import com.knative.fluss.broker.common.model.Envelope;
import com.knative.fluss.broker.storage.fluss.client.FlussConnectionManager;
import com.knative.fluss.broker.storage.fluss.client.FlussEventScanner;
import com.knative.fluss.broker.storage.fluss.client.FlussEventWriter;
import com.knative.fluss.broker.storage.fluss.tables.FlussTableManager;
import com.knative.fluss.broker.storage.fluss.tables.FlussTablePath;
import org.junit.jupiter.api.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Real Fluss integration tests.
 * Requires docker-compose services running:
 *   docker compose -f docker/docker-compose.yml up -d fluss-coordinator fluss-tablet zookeeper
 *
 * <p>Uses the Fluss client to connect to the docker-compose Fluss cluster
 * at 127.0.0.1:9123 (fixed port mapping in docker-compose.yml).
 */
class FlussIntegrationTest {

    private static final String FLUSS_ENDPOINT = "fluss://127.0.0.1:9123";

    private FlussConnectionManager connectionManager;
    private FlussTableManager tableManager;
    private FlussEventWriter writer;
    private FlussTablePath brokerTablePath;
    private FlussTablePath dlqTablePath;

    @BeforeEach
    void setUp() {
        FlussConfig config = new FlussConfig(
                FLUSS_ENDPOINT,
                50,    // writeBatchSize
                100,   // writeBatchTimeoutMs
                10000, // ackTimeoutMs
                3,     // writeMaxRetries
                100    // writeRetryBackoffMs
        );

        connectionManager = new FlussConnectionManager(config);
        tableManager = new FlussTableManager(connectionManager);
        writer = new FlussEventWriter(connectionManager, config);
        brokerTablePath = FlussTablePath.brokerTable("integration", "test-broker");
        dlqTablePath = FlussTablePath.dlqTable("integration", "test-broker", "test-trigger");

        // Ensure the database and tables exist
        tableManager.ensureDatabase("integration");
        tableManager.ensureBrokerTable(brokerTablePath);
        tableManager.ensureDlqTable(dlqTablePath);
    }

    @AfterEach
    void tearDown() {
        if (writer != null) writer.close();
        if (connectionManager != null) connectionManager.close();
    }

    // ─────────────────────────────────────────────
    // Connection tests
    // ─────────────────────────────────────────────

    @Test
    void shouldEstablishConnection() {
        assertThat(connectionManager.getConnection()).isNotNull();
        assertThat(connectionManager.getConfig().endpoint()).contains("127.0.0.1:9123");
    }

    @Test
    void shouldThrowAfterClose() {
        FlussConfig config = FlussConfig.defaults();
        var conn = new FlussConnectionManager(config);
        conn.close();
        assertThatThrownBy(conn::getConnection)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("closed");
    }

    // ─────────────────────────────────────────────
    // DDL tests (Admin operations)
    // ─────────────────────────────────────────────

    @Test
    void shouldCreateDatabase() {
        assertThat(tableManager.isDatabaseEnsured("integration")).isTrue();
    }

    @Test
    void shouldCreateBrokerTable() {
        assertThat(tableManager.isTableEnsured(brokerTablePath)).isTrue();
    }

    @Test
    void voidShouldCreateDlqTable() {
        assertThat(tableManager.isTableEnsured(dlqTablePath)).isTrue();
    }

    @Test
    void shouldHandleIdempotentDatabaseCreation() {
        tableManager.ensureDatabase("integration");
        assertThat(tableManager.isDatabaseEnsured("integration")).isTrue();
    }

    @Test
    void voidShouldHandleIdempotentTableCreation() {
        tableManager.ensureBrokerTable(brokerTablePath);
        assertThat(tableManager.isTableEnsured(brokerTablePath)).isTrue();
    }

    // ─────────────────────────────────────────────
    // Write tests (AppendWriter)
    // ─────────────────────────────────────────────

    @Test
    void shouldWriteSingleEvent() throws Exception {
        Envelope event = createTestEvent("write-test-1");
        writer.write(brokerTablePath, event).get();
    }

    @Test
    void shouldWriteBatchEvents() throws Exception {
        List<Envelope> events = List.of(
                createTestEvent("batch-1"),
                createTestEvent("batch-2"),
                createTestEvent("batch-3")
        );
        writer.writeBatch(brokerTablePath, events).get();
    }

    @Test
    void shouldWriteEventWithNullOptionalFields() throws Exception {
        Envelope event = Envelope.builder()
                .eventId("null-fields-test")
                .eventSource("/test/minimal")
                .eventType("com.example.minimal")
                .contentType("application/json")
                .build();
        writer.write(brokerTablePath, event).get();
    }

    @Test
    void shouldWriteEventWithExtensionAttributes() throws Exception {
        Map<String, String> attrs = Map.of(
                "correlationid", "abc-123",
                "region", "us-west-2",
                "traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"
        );

        Envelope event = Envelope.builder()
                .eventId("attrs-test")
                .eventSource("/test/attrs")
                .eventType("com.example.withattrs")
                .contentType("application/json")
                .data("{\"key\":\"value\"}".getBytes())
                .attributes(attrs)
                .schemaId(1)
                .schemaVersion(2)
                .build();
        writer.write(brokerTablePath, event).get();
    }

    // ─────────────────────────────────────────────
    // DLQ write tests
    // ─────────────────────────────────────────────

    @Test
    void shouldWriteToDlqTable() throws Exception {
        Envelope event = createTestEvent("dlq-test-1");
        writer.writeToDlq(dlqTablePath, event, "retries_exhausted", 5, "Max attempts reached").get();
    }

    // ─────────────────────────────────────────────
    // Scan tests (LogScanner)
    // ─────────────────────────────────────────────

    @Test
    void shouldWriteAndReadBackEvents() throws Exception {
        Envelope evt1 = createTestEvent("roundtrip-1");
        Envelope evt2 = createTestEvent("roundtrip-2");
        Envelope evt3 = createTestEvent("roundtrip-3");

        writer.write(brokerTablePath, evt1).get();
        writer.write(brokerTablePath, evt2).get();
        writer.write(brokerTablePath, evt3).get();

        Thread.sleep(5000);  // Fluss needs time to flush writes to the log table

        FlussEventScanner scanner = new FlussEventScanner(connectionManager);
        try {
            List<Envelope> allRead = new java.util.ArrayList<>();
            for (int i = 0; i < 15; i++) {
                List<Envelope> batch = scanner.scan(brokerTablePath, "test-trigger", 50);
                allRead.addAll(batch);
                if (allRead.size() >= 3) break;
                Thread.sleep(500);
            }

            assertThat(allRead).hasSizeGreaterThanOrEqualTo(3);

            List<String> readIds = allRead.stream().map(Envelope::eventId).toList();
            assertThat(readIds).contains("roundtrip-1", "roundtrip-2", "roundtrip-3");

            for (Envelope read : allRead) {
                if ("roundtrip-1".equals(read.eventId())) {
                    assertThat(read.eventType()).isEqualTo("com.example.test");
                    assertThat(read.eventSource()).isEqualTo("/test/integration");
                    assertThat(read.contentType()).isEqualTo("application/json");
                    assertThat(read.data()).isEqualTo("{\"key\":\"value\"}".getBytes());
                }
            }
        } finally {
            scanner.close();
        }
    }

    // ─────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────

    private Envelope createTestEvent(String id) {
        return Envelope.builder()
                .eventId(id)
                .eventSource("/test/integration")
                .eventType("com.example.test")
                .contentType("application/json")
                .data("{\"key\":\"value\"}".getBytes())
                .schemaId(1)
                .schemaVersion(1)
                .build();
    }
}
