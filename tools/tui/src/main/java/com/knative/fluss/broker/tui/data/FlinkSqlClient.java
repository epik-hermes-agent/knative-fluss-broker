package com.knative.fluss.broker.tui.data;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * HTTP client for the Flink SQL Gateway REST API.
 *
 * <p>Talks to the gateway at the configured URL (default: http://localhost:8083).
 * Manages a single session that is reused across all queries.
 *
 * <p>REST flow:
 * <ol>
 *   <li>POST /v1/sessions → sessionHandle</li>
 *   <li>POST /v1/sessions/{sessionHandle}/statements → operationHandle</li>
 *   <li>GET /v1/sessions/{sessionHandle}/operations/{opHandle}/result/0 → columns + data</li>
 * </ol>
 */
public class FlinkSqlClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(FlinkSqlClient.class);
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final OkHttpClient http;
    private final String gatewayUrl;
    private final ObjectMapper mapper;
    private String sessionHandle;
    private boolean catalogInitialized;

    public FlinkSqlClient(String gatewayUrl) {
        this.gatewayUrl = gatewayUrl.endsWith("/") ? gatewayUrl.substring(0, gatewayUrl.length() - 1) : gatewayUrl;
        this.mapper = new ObjectMapper();
        this.http = new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .build();
        this.catalogInitialized = false;
    }

    /**
     * Open a SQL Gateway session. Must be called before executeQuery.
     */
    public synchronized void openSession() throws IOException {
        if (sessionHandle != null) {
            return;
        }
        Request request = new Request.Builder()
                .url(gatewayUrl + "/v1/sessions")
                .post(RequestBody.create("{}", JSON))
                .build();

        try (Response response = http.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Failed to open session: HTTP " + response.code() + " - " + responseBody(response));
            }
            JsonNode body = mapper.readTree(response.body().bytes());
            sessionHandle = body.get("sessionHandle").asText();
            log.info("SQL Gateway session opened: {}", sessionHandle);
        }
    }

    /**
     * Initialize the Fluss catalog in this session. Called once before the first data query.
     */
    public synchronized void initCatalog(String bootstrapServers) throws IOException {
        if (catalogInitialized) {
            return;
        }
        ensureSession();
        executeStatement("CREATE CATALOG fluss WITH ('type' = 'fluss', 'bootstrap.servers' = '" + bootstrapServers + "')");
        executeStatement("USE CATALOG fluss");
        catalogInitialized = true;
        log.info("Fluss catalog initialized with bootstrap.servers={}", bootstrapServers);
    }

    /**
     * Execute a SQL query and return the result rows as a list of JsonNode arrays.
     * Each row is a JsonArray of column values.
     */
    public List<JsonNode> executeQuery(String sql) throws IOException {
        ensureSession();
        ensureCatalog();

        String operationHandle = submitStatement(sql);

        // Poll for results
        String resultUrl = gatewayUrl + "/v1/sessions/" + sessionHandle
                + "/operations/" + operationHandle + "/result/0";

        List<JsonNode> allRows = new ArrayList<>();
        String nextUrl = resultUrl;

        while (nextUrl != null) {
            Request request = new Request.Builder()
                    .url(nextUrl)
                    .get()
                    .build();

            try (Response response = http.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    throw new IOException("Failed to fetch results: HTTP " + response.code() + " - " + responseBody(response));
                }
                JsonNode body = mapper.readTree(response.body().bytes());

                // Check if the operation is still running
                JsonNode resultType = body.get("resultType");
                if (resultType != null && "NOT_READY".equals(resultType.asText())) {
                    // Wait and retry
                    try { Thread.sleep(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                    continue;
                }

                JsonNode data = body.get("data");
                if (data != null && data.isArray()) {
                    for (JsonNode row : data) {
                        allRows.add(row);
                    }
                }

                // Check for next page
                JsonNode nextResultUri = body.get("nextResultUri");
                nextUrl = (nextResultUri != null && !nextResultUri.isNull())
                        ? gatewayUrl + nextResultUri.asText()
                        : null;
            }
        }

        return allRows;
    }

    /**
     * Execute a SQL query and return column metadata (name + type).
     */
    public List<String[]> executeQueryColumns(String sql) throws IOException {
        ensureSession();
        ensureCatalog();

        String operationHandle = submitStatement(sql);

        String resultUrl = gatewayUrl + "/v1/sessions/" + sessionHandle
                + "/operations/" + operationHandle + "/result/0";

        Request request = new Request.Builder()
                .url(resultUrl)
                .get()
                .build();

        try (Response response = http.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Failed to fetch columns: HTTP " + response.code());
            }
            JsonNode body = mapper.readTree(response.body().bytes());
            JsonNode columns = body.get("columns");
            List<String[]> result = new ArrayList<>();
            if (columns != null && columns.isArray()) {
                for (JsonNode col : columns) {
                    String name = col.get("name").asText();
                    String type = col.get("logicalType").get("type").asText();
                    result.add(new String[]{name, type});
                }
            }
            return result;
        }
    }

    /**
     * Execute a SQL statement that returns no results (DDL, USE, etc.).
     */
    public void executeStatement(String sql) throws IOException {
        ensureSession();
        String opHandle = submitStatement(sql);

        // Wait for completion
        String statusUrl = gatewayUrl + "/v1/sessions/" + sessionHandle
                + "/operations/" + opHandle + "/status";

        for (int i = 0; i < 50; i++) {
            Request request = new Request.Builder()
                    .url(statusUrl)
                    .get()
                    .build();

            try (Response response = http.newCall(request).execute()) {
                if (!response.isSuccessful()) break;
                JsonNode body = mapper.readTree(response.body().bytes());
                String status = body.get("status").asText();
                if ("FINISHED".equals(status) || "ERROR".equals(status)) {
                    if ("ERROR".equals(status)) {
                        log.warn("SQL statement failed: {}", sql);
                    }
                    return;
                }
            }
            try { Thread.sleep(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
    }

    /**
     * Execute a SQL COUNT query and return the result as a long.
     * Returns -1 if the query fails.
     */
    public long executeCountQuery(String sql) {
        try {
            List<JsonNode> rows = executeQuery(sql);
            if (!rows.isEmpty() && rows.get(0).isArray() && rows.get(0).size() > 0) {
                return rows.get(0).get(0).asLong();
            }
        } catch (IOException e) {
            log.debug("Count query failed: {}", e.getMessage());
        }
        return -1;
    }

    private String submitStatement(String sql) throws IOException {
        String json = mapper.writeValueAsString(Collections.singletonMap("statement", sql));
        Request request = new Request.Builder()
                .url(gatewayUrl + "/v1/sessions/" + sessionHandle + "/statements/")
                .post(RequestBody.create(json, JSON))
                .build();

        try (Response response = http.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Failed to submit SQL: HTTP " + response.code() + " - " + responseBody(response)
                        + " [sql=" + sql + "]");
            }
            JsonNode body = mapper.readTree(response.body().bytes());
            return body.get("operationHandle").asText();
        }
    }

    private void ensureSession() throws IOException {
        if (sessionHandle == null) {
            openSession();
        }
    }

    private void ensureCatalog() throws IOException {
        if (!catalogInitialized) {
            throw new IOException("Catalog not initialized. Call initCatalog() first.");
        }
    }

    private static String responseBody(Response response) {
        try {
            return response.body() != null ? response.body().string() : "(no body)";
        } catch (IOException e) {
            return "(failed to read body)";
        }
    }

    /**
     * Check if the gateway is reachable.
     */
    public boolean isReachable() {
        try {
            Request request = new Request.Builder()
                    .url(gatewayUrl + "/v1/info")
                    .get()
                    .build();
            try (Response response = http.newCall(request).execute()) {
                return response.isSuccessful();
            }
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public void close() {
        if (sessionHandle != null) {
            try {
                Request request = new Request.Builder()
                        .url(gatewayUrl + "/v1/sessions/" + sessionHandle)
                        .delete()
                        .build();
                try (Response response = http.newCall(request).execute()) {
                    if (response.isSuccessful()) {
                        log.info("SQL Gateway session closed: {}", sessionHandle);
                    }
                }
            } catch (IOException e) {
                log.warn("Failed to close session: {}", e.getMessage());
            }
            sessionHandle = null;
        }
        http.dispatcher().executorService().shutdown();
        http.connectionPool().evictAll();
    }
}
