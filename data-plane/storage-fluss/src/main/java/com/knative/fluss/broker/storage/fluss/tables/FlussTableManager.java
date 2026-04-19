package com.knative.fluss.broker.storage.fluss.tables;

import com.knative.fluss.broker.storage.fluss.client.FlussConnectionManager;
import org.apache.fluss.client.admin.Admin;
import org.apache.fluss.metadata.DatabaseDescriptor;
import org.apache.fluss.metadata.TableChange;
import org.apache.fluss.metadata.TableDescriptor;
import org.apache.fluss.metadata.TablePath;
import org.apache.fluss.types.DataTypes;
import org.apache.fluss.metadata.Schema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Manages Fluss database and table lifecycle for broker instances.
 * Uses the real Fluss {@link Admin} client to execute DDL statements.
 *
 * <p>All operations are idempotent — safe to call repeatedly without errors.
 * Ensured state is cached locally to avoid redundant DDL round-trips.
 */
public class FlussTableManager {

    private static final Logger log = LoggerFactory.getLogger(FlussTableManager.class);
    private static final long DDL_TIMEOUT_SECONDS = 30;
    private static final int DEFAULT_BUCKET_COUNT = 8;

    private final FlussConnectionManager connectionManager;
    private final ConcurrentHashMap<String, Boolean> ensuredDatabases = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> ensuredTables = new ConcurrentHashMap<>();

    public FlussTableManager(FlussConnectionManager connectionManager) {
        this.connectionManager = connectionManager;
    }

    /**
     * Execute a DDL operation with a fresh Admin client.
     * Admin is NOT thread-safe, so we create a new one per operation.
     */
    @FunctionalInterface
    private interface DdlOperation {
        void execute(Admin admin) throws Exception;
    }

    private void executeDdl(DdlOperation op) {
        Admin admin = connectionManager.getConnection().getAdmin();
        try {
            op.execute(admin);
        } catch (ExecutionException e) {
            throw new RuntimeException("DDL operation failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("DDL operation interrupted", e);
        } catch (TimeoutException e) {
            throw new RuntimeException("DDL operation timed out", e);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("DDL operation failed", e);
        } finally {
            try {
                admin.close();
            } catch (Exception e) {
                log.warn("Error closing Admin client", e);
            }
        }
    }

    // ─────────────────────────────────────────────
    // Database operations
    // ─────────────────────────────────────────────

    /**
     * Ensure the Fluss database exists for the given Kubernetes namespace.
     * Idempotent — does nothing if the database already exists.
     *
     * @param namespace the Kubernetes namespace
     */
    public void ensureDatabase(String namespace) {
        String db = FlussTablePath.sanitize("knative_" + namespace);
        if (ensuredDatabases.containsKey(db)) {
            return;
        }

        log.info("Ensuring Fluss database: {}", db);
        executeDdl(admin -> {
            try {
                DatabaseDescriptor descriptor = DatabaseDescriptor.builder()
                        .comment("Knative Fluss broker database for namespace: " + namespace)
                        .build();
                admin.createDatabase(db, descriptor, true)
                        .get(DDL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                ensuredDatabases.put(db, Boolean.TRUE);
                log.info("Fluss database ensured: {}", db);
            } catch (ExecutionException e) {
                if (isAlreadyExists(e)) {
                    ensuredDatabases.put(db, Boolean.TRUE);
                    log.debug("Database {} already exists", db);
                } else {
                    throw new RuntimeException("Failed to create database: " + db, e);
                }
            }
        });
    }

    // ─────────────────────────────────────────────
    // Broker table operations
    // ─────────────────────────────────────────────

    /**
     * Ensure the broker log table exists with the envelope schema.
     *
     * @param tablePath the table path to ensure
     */
    public void ensureBrokerTable(FlussTablePath tablePath) {
        String key = tablePath.fullPath();
        if (ensuredTables.containsKey(key)) {
            return;
        }

        log.info("Ensuring Fluss broker table: {}", key);
        executeDdl(admin -> {
            try {
                Schema schema = buildBrokerTableSchema();
                TableDescriptor descriptor = TableDescriptor.builder()
                        .schema(schema)
                        .distributedBy(DEFAULT_BUCKET_COUNT, "event_id")
                        .comment("Knative broker event log table")
                        .build();
                TablePath flussPath = TablePath.of(tablePath.database(), tablePath.table());
                admin.createTable(flussPath, descriptor, true)
                        .get(DDL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                ensuredTables.put(key, Boolean.TRUE);
                log.info("Fluss broker table ensured: {}", key);
                // Enable datalake tiering via alterTable (post-create, per Fluss docs)
                try {
                    admin.alterTable(flussPath,
                            java.util.List.of(
                                TableChange.set("table.datalake.enabled", "true")), true)
                            .get(DDL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    log.info("Datalake enabled for table: {}", key);
                } catch (Exception e) {
                    log.warn("Could not enable datalake for table {}: {}", key, e.getMessage());
                }
            } catch (ExecutionException e) {
                if (isAlreadyExists(e)) {
                    ensuredTables.put(key, Boolean.TRUE);
                    log.debug("Table {} already exists", key);
                } else {
                    throw new RuntimeException("Failed to create broker table: " + key, e);
                }
            }
        });
    }

    // ─────────────────────────────────────────────
    // DLQ table operations
    // ─────────────────────────────────────────────

    /**
     * Ensure the DLQ table exists for dead-lettered events.
     *
     * @param tablePath the DLQ table path
     */
    public void ensureDlqTable(FlussTablePath tablePath) {
        String key = tablePath.fullPath();
        if (ensuredTables.containsKey(key)) {
            return;
        }

        log.info("Ensuring Fluss DLQ table: {}", key);
        executeDdl(admin -> {
            try {
                Schema schema = buildDlqTableSchema();
                TableDescriptor descriptor = TableDescriptor.builder()
                        .schema(schema)
                        .distributedBy(DEFAULT_BUCKET_COUNT, "event_id")
                        .comment("Dead letter queue for failed event deliveries")
                        .build();
                TablePath flussPath = TablePath.of(tablePath.database(), tablePath.table());
                admin.createTable(flussPath, descriptor, true)
                        .get(DDL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                ensuredTables.put(key, Boolean.TRUE);
                log.info("Fluss DLQ table ensured: {}", key);
            } catch (ExecutionException e) {
                if (isAlreadyExists(e)) {
                    ensuredTables.put(key, Boolean.TRUE);
                    log.debug("DLQ table {} already exists", key);
                } else {
                    throw new RuntimeException("Failed to create DLQ table: " + key, e);
                }
            }
        });
    }

    // ─────────────────────────────────────────────
    // Schema registry table operations
    // ─────────────────────────────────────────────

    /**
     * Ensure the schema registry table exists.
     *
     * @param tablePath the schema registry table path
     */
    public void ensureSchemaRegistryTable(FlussTablePath tablePath) {
        String key = tablePath.fullPath();
        if (ensuredTables.containsKey(key)) {
            return;
        }

        log.info("Ensuring Fluss schema registry table: {}", key);
        executeDdl(admin -> {
            try {
                Schema schema = buildSchemaRegistryTableSchema();
                TableDescriptor descriptor = TableDescriptor.builder()
                        .schema(schema)
                        .distributedBy(1, "schema_id")
                        .comment("Schema registry for event type schemas")
                        .build();
                TablePath flussPath = TablePath.of(tablePath.database(), tablePath.table());
                admin.createTable(flussPath, descriptor, true)
                        .get(DDL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                ensuredTables.put(key, Boolean.TRUE);
                log.info("Fluss schema registry table ensured: {}", key);
            } catch (ExecutionException e) {
                if (isAlreadyExists(e)) {
                    ensuredTables.put(key, Boolean.TRUE);
                    log.debug("Schema registry table {} already exists", key);
                } else {
                    throw new RuntimeException("Failed to create schema registry table: " + key, e);
                }
            }
        });
    }

    // ─────────────────────────────────────────────
    // Table deletion
    // ─────────────────────────────────────────────

    /**
     * Drop a table if it exists.
     *
     * @param tablePath the table path to drop
     */
    public void dropTable(FlussTablePath tablePath) {
        String key = tablePath.fullPath();
        log.info("Dropping Fluss table: {}", key);
        executeDdl(admin -> {
            try {
                TablePath flussPath = TablePath.of(tablePath.database(), tablePath.table());
                admin.dropTable(flussPath, true)
                        .get(DDL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                ensuredTables.remove(key);
                log.info("Fluss table dropped: {}", key);
            } catch (ExecutionException e) {
                if (!isNotFound(e)) {
                    throw new RuntimeException("Failed to drop table: " + key, e);
                }
                log.debug("Table {} does not exist, nothing to drop", key);
            }
        });
    }

    // ─────────────────────────────────────────────
    // Check state
    // ─────────────────────────────────────────────

    public boolean isTableEnsured(FlussTablePath tablePath) {
        return ensuredTables.containsKey(tablePath.fullPath());
    }

    public boolean isDatabaseEnsured(String namespace) {
        String db = FlussTablePath.sanitize("knative_" + namespace);
        return ensuredDatabases.containsKey(db);
    }

    // ─────────────────────────────────────────────
    // Schema builders
    // ─────────────────────────────────────────────

    /**
     * Build the schema for the broker event log table.
     * In Fluss 1.0-SNAPSHOT, columns are nullable by default.
     * Only columns that are part of the primary key are effectively NOT NULL.
     */
    private Schema buildBrokerTableSchema() {
        return Schema.newBuilder()
                .column("event_id", DataTypes.STRING())
                .column("event_source", DataTypes.STRING())
                .column("event_type", DataTypes.STRING())
                .column("event_time", DataTypes.TIMESTAMP(3))
                .column("content_type", DataTypes.STRING())
                .column("data", DataTypes.BYTES())
                .column("schema_id", DataTypes.INT())
                .column("schema_version", DataTypes.INT())
                .column("attributes", DataTypes.MAP(DataTypes.STRING(), DataTypes.STRING()))
                .column("ingestion_time", DataTypes.TIMESTAMP_LTZ(3))
                .column("ingestion_date", DataTypes.DATE())
                // NO primary key — this is a Log Table (append-only)
                .build();
    }

    private Schema buildDlqTableSchema() {
        return Schema.newBuilder()
                .column("event_id", DataTypes.STRING())
                .column("event_source", DataTypes.STRING())
                .column("event_type", DataTypes.STRING())
                .column("event_time", DataTypes.TIMESTAMP(3))
                .column("content_type", DataTypes.STRING())
                .column("data", DataTypes.BYTES())
                .column("schema_id", DataTypes.INT())
                .column("schema_version", DataTypes.INT())
                .column("attributes", DataTypes.MAP(DataTypes.STRING(), DataTypes.STRING()))
                .column("ingestion_time", DataTypes.TIMESTAMP_LTZ(3))
                .column("ingestion_date", DataTypes.DATE())
                .column("dlq_reason", DataTypes.STRING())
                .column("dlq_attempts", DataTypes.INT())
                .column("dlq_last_error", DataTypes.STRING())
                .column("dlq_timestamp", DataTypes.TIMESTAMP_LTZ(3))
                // NO primary key — this is a Log Table (append-only)
                .build();
    }

    private Schema buildSchemaRegistryTableSchema() {
        return Schema.newBuilder()
                .column("schema_id", DataTypes.INT())
                .column("schema_version", DataTypes.INT())
                .column("event_type", DataTypes.STRING())
                .column("content_type", DataTypes.STRING())
                .column("schema_definition", DataTypes.STRING())
                .column("created_at", DataTypes.TIMESTAMP_LTZ(3))
                .primaryKey("schema_id", "schema_version")
                .build();
    }

    // ─────────────────────────────────────────────
    // Exception helpers
    // ─────────────────────────────────────────────

    private static boolean isAlreadyExists(ExecutionException e) {
        Throwable cause = e.getCause();
        String msg = cause != null ? cause.getMessage() : e.getMessage();
        return msg != null && (
                msg.contains("already exists")
                || msg.contains("AlreadyExists")
                || msg.contains("ALREADY_EXISTS")
                || msg.contains("ObjectAlreadyExists")
        );
    }

    private static boolean isNotFound(ExecutionException e) {
        Throwable cause = e.getCause();
        String msg = cause != null ? cause.getMessage() : e.getMessage();
        return msg != null && (
                msg.contains("not found")
                || msg.contains("NotFound")
                || msg.contains("NOT_FOUND")
                || msg.contains("does not exist")
        );
    }
}
