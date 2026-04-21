package com.knative.fluss.broker.ingress.server;

import com.knative.fluss.broker.common.config.SchemaConfig;
import com.knative.fluss.broker.common.model.Envelope;
import com.knative.fluss.broker.ingress.handler.IngressHandler;
import com.knative.fluss.broker.ingress.validation.InvalidCloudEventException;
import com.knative.fluss.broker.schema.registry.SchemaRegistry;
import com.knative.fluss.broker.storage.fluss.client.FlussConnectionManager;
import com.knative.fluss.broker.storage.fluss.client.FlussEventWriter;
import com.knative.fluss.broker.storage.fluss.tables.FlussTableManager;
import com.knative.fluss.broker.storage.fluss.tables.FlussTablePath;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.cloudevents.CloudEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/**
 * HTTP server that accepts CloudEvents for the Fluss broker ingress.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code POST /{namespace}/{broker}} — publish a CloudEvent (binary or structured mode)</li>
 *   <li>{@code GET /health} — health check, returns 200 OK</li>
 * </ul>
 *
 * <p>For each {@code namespace/broker} combination, a dedicated {@link IngressHandler} is created
 * on first request and cached for subsequent requests. The handler resolves the Fluss table path
 * via {@link FlussTablePath#brokerTable(String, String)} and ensures the database and table exist
 * through {@link FlussTableManager}.
 */
public class IngressServer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(IngressServer.class);

    private static final int DEFAULT_BACKLOG = 0; // system default

    private final HttpServer httpServer;
    private final FlussConnectionManager connectionManager;
    private final FlussTableManager tableManager;
    private final FlussEventWriter writer;
    private final SchemaConfig schemaConfig;
    private final int port;

    // Cache: "namespace/broker" -> IngressHandler
    private final Map<String, IngressHandler> handlerCache = new ConcurrentHashMap<>();

    /**
     * Create and bind the ingress HTTP server (does not start accepting connections yet).
     *
     * @param connectionManager shared Fluss connection
     * @param tableManager      DDL manager for ensuring databases/tables
     * @param writer            event writer to Fluss
     * @param schemaConfig      schema registry config
     * @param port              HTTP port to bind
     */
    public IngressServer(FlussConnectionManager connectionManager,
                         FlussTableManager tableManager,
                         FlussEventWriter writer,
                         SchemaConfig schemaConfig,
                         int port) {
        this.connectionManager = connectionManager;
        this.tableManager = tableManager;
        this.writer = writer;
        this.schemaConfig = schemaConfig;
        this.port = port;

        try {
            this.httpServer = HttpServer.create(new InetSocketAddress(port), DEFAULT_BACKLOG);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create HTTP server on port " + port, e);
        }

        // Register routes
        httpServer.createContext("/health", this::handleHealth);
        httpServer.createContext("/", this::handlePublish);

        // Use a fixed thread pool for request handling
        httpServer.setExecutor(Executors.newFixedThreadPool(
            Runtime.getRuntime().availableProcessors(),
            r -> {
                Thread t = new Thread(r, "ingress-http");
                t.setDaemon(true);
                return t;
            }
        ));

        log.info("Ingress server created on port {}", port);
    }

    /**
     * Start accepting connections.
     */
    public void start() {
        httpServer.start();
        log.info("Ingress server started on port {}", port);
    }

    /**
     * Stop the server, waiting for in-flight requests to complete.
     */
    public void stop() {
        httpServer.stop(5); // 5 second delay for in-flight requests
        log.info("Ingress server stopped");
    }

    @Override
    public void close() {
        stop();
    }

    /**
     * Get the port the server is listening on.
     * Returns the actual bound port (useful when port 0 was specified for random assignment).
     */
    public int getPort() {
        return httpServer.getAddress().getPort();
    }

    // ─────────────────────────────────────────────
    // HTTP handlers
    // ─────────────────────────────────────────────

    private void handleHealth(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "{\"error\":\"Method not allowed\"}");
            return;
        }
        sendResponse(exchange, 200, "{\"status\":\"ok\"}");
    }

    private void handlePublish(HttpExchange exchange) throws IOException {
        // Only accept POST
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "{\"error\":\"Method not allowed\"}");
            return;
        }

        // Parse path: /{namespace}/{broker}
        String path = exchange.getRequestURI().getPath();
        String[] segments = parsePathSegments(path);

        if (segments == null || segments.length < 2) {
            sendResponse(exchange, 400,
                "{\"error\":\"Invalid path. Expected: POST /{namespace}/{broker}\"}");
            return;
        }

        String namespace = segments[0];
        String brokerName = segments[1];

        // Resolve or create handler
        IngressHandler handler = getOrCreateHandler(namespace, brokerName);
        if (handler == null) {
            sendResponse(exchange, 500,
                "{\"error\":\"Failed to initialize handler for " + namespace + "/" + brokerName + "\"}");
            return;
        }

        // Parse HTTP → CloudEvent
        CloudEvent event;
        try {
            event = CloudEventFromHttp.parse(exchange);
        } catch (InvalidCloudEventException e) {
            log.warn("Invalid CloudEvent from {}: {}", exchange.getRemoteAddress(), e.getMessage());
            sendResponse(exchange, 400, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
            return;
        } catch (IOException e) {
            log.error("Failed to read request body from {}", exchange.getRemoteAddress(), e);
            sendResponse(exchange, 400, "{\"error\":\"Failed to read request body\"}");
            return;
        }

        // Process through ingress handler
        CompletableFuture<Envelope> future = handler.handle(event);

        future.whenComplete((envelope, throwable) -> {
            if (throwable != null) {
                log.error("Ingress handler failed for event {}: {}",
                    event.getId(), throwable.getMessage(), throwable);
                String error = throwable.getCause() != null
                    ? throwable.getCause().getMessage() : throwable.getMessage();
                sendResponse(exchange, 500,
                    "{\"error\":\"" + escapeJson(error) + "\"}");
            } else {
                log.debug("Ingress accepted event id={} type={} ns={} broker={}",
                    envelope.eventId(), envelope.eventType(), namespace, brokerName);
                sendResponse(exchange, 202, "{\"accepted\":true,\"eventId\":\""
                    + escapeJson(envelope.eventId()) + "\"}");
            }
        });
    }

    // ─────────────────────────────────────────────
    // Handler management
    // ─────────────────────────────────────────────

    private IngressHandler getOrCreateHandler(String namespace, String brokerName) {
        String key = namespace + "/" + brokerName;
        return handlerCache.computeIfAbsent(key, k -> {
            try {
                FlussTablePath tablePath = FlussTablePath.brokerTable(namespace, brokerName);

                // Ensure database and table exist
                tableManager.ensureDatabase(tablePath.database());
                tableManager.ensureBrokerTable(tablePath);

                // Create schema registry for this namespace
                FlussTablePath schemaRegistryPath = FlussTablePath.schemaRegistry(namespace);
                SchemaRegistry registry = new SchemaRegistry(schemaConfig, schemaRegistryPath);

                log.info("Created ingress handler for {}/{} → {}", namespace, brokerName, tablePath.fullPath());
                return new IngressHandler(writer, registry, schemaConfig, tablePath);

            } catch (Exception e) {
                log.error("Failed to create handler for {}: {}", key, e.getMessage(), e);
                return null;
            }
        });
    }

    // ─────────────────────────────────────────────
    // Response helpers
    // ─────────────────────────────────────────────

    private static void sendResponse(HttpExchange exchange, int statusCode, String body) {
        try {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            exchange.sendResponseHeaders(statusCode, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        } catch (IOException e) {
            log.error("Failed to send response to {}", exchange.getRemoteAddress(), e);
        } finally {
            exchange.close();
        }
    }

    /**
     * Parse a URL path into segments, skipping empty segments.
     * Returns null if fewer than 2 non-empty segments.
     */
    static String[] parsePathSegments(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        // Strip leading/trailing slashes, split
        String trimmed = path.startsWith("/") ? path.substring(1) : path;
        trimmed = trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;

        String[] parts = trimmed.split("/");
        // Filter out empty segments
        java.util.List<String> nonEmpty = new java.util.ArrayList<>();
        for (String part : parts) {
            if (!part.isBlank()) {
                nonEmpty.add(part);
            }
        }
        return nonEmpty.isEmpty() ? null : nonEmpty.toArray(new String[0]);
    }

    private static String escapeJson(String input) {
        if (input == null) return "";
        return input.replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r")
                    .replace("\t", "\\t");
    }
}
