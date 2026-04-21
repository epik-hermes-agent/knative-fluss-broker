package com.knative.fluss.broker.ingress.server;

import com.knative.fluss.broker.common.config.FlussConfig;
import com.knative.fluss.broker.common.config.SchemaConfig;
import com.knative.fluss.broker.storage.fluss.client.FlussConnectionManager;
import com.knative.fluss.broker.storage.fluss.client.FlussEventWriter;
import com.knative.fluss.broker.storage.fluss.tables.FlussTableManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point for the Fluss broker ingress HTTP server.
 *
 * <p>Reads configuration from environment variables:
 * <ul>
 *   <li>{@code FLUSS_HOST} — Fluss coordinator hostname (default: {@code localhost})</li>
 *   <li>{@code FLUSS_PORT} — Fluss native protocol port (default: {@code 9123})</li>
 *   <li>{@code SERVER_PORT} — HTTP port to bind (default: {@code 8080})</li>
 * </ul>
 *
 * <p>The server accepts CloudEvents via HTTP POST at {@code /{namespace}/{broker}}
 * and writes them to the corresponding Fluss log table.
 */
public class IngressServerMain {

    private static final Logger log = LoggerFactory.getLogger(IngressServerMain.class);

    private IngressServerMain() {}

    public static void main(String[] args) {
        String flussHost = getEnvOrDefault("FLUSS_HOST", "localhost");
        int flussPort = Integer.parseInt(getEnvOrDefault("FLUSS_PORT", "9123"));
        int serverPort = Integer.parseInt(getEnvOrDefault("SERVER_PORT", "8080"));

        log.info("Starting Fluss broker ingress server");
        log.info("  Fluss endpoint: {}:{}", flussHost, flussPort);
        log.info("  HTTP port: {}", serverPort);

        // Build Fluss connection config
        String endpoint = "fluss://" + flussHost + ":" + flussPort;
        FlussConfig flussConfig = FlussConfig.defaults();
        // Override endpoint from env (FlussConfig is a record, create new one)
        flussConfig = new FlussConfig(
            endpoint,
            flussConfig.writeBatchSize(),
            flussConfig.writeBatchTimeoutMs(),
            flussConfig.ackTimeoutMs(),
            flussConfig.writeMaxRetries(),
            flussConfig.writeRetryBackoffMs()
        );

        // Initialize Fluss components
        FlussConnectionManager connectionManager = new FlussConnectionManager(flussConfig);
        FlussTableManager tableManager = new FlussTableManager(connectionManager);
        FlussEventWriter writer = new FlussEventWriter(connectionManager, flussConfig);
        SchemaConfig schemaConfig = SchemaConfig.defaults();

        // Create and start HTTP server
        IngressServer server = new IngressServer(
            connectionManager, tableManager, writer, schemaConfig, serverPort);

        // Register shutdown hook for clean teardown
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down ingress server...");
            server.close();
            writer.close();
            connectionManager.close();
            log.info("Ingress server shutdown complete");
        }, "ingress-shutdown"));

        server.start();
        log.info("Ingress server ready — accepting CloudEvents at http://0.0.0.0:{}/{{namespace}}/{{broker}}",
            serverPort);

        // Block main thread
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            log.info("Main thread interrupted, shutting down");
            Thread.currentThread().interrupt();
        }
    }

    private static String getEnvOrDefault(String name, String defaultValue) {
        String value = System.getenv(name);
        return (value != null && !value.isBlank()) ? value : defaultValue;
    }
}
