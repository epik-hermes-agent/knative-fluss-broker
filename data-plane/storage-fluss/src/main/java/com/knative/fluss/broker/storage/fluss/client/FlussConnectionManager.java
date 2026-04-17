package com.knative.fluss.broker.storage.fluss.client;

import com.knative.fluss.broker.common.config.FlussConfig;
import org.apache.fluss.client.Connection;
import org.apache.fluss.client.ConnectionFactory;
import org.apache.fluss.config.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages the Fluss {@link Connection} lifecycle.
 * A single thread-safe Connection is created per application and shared across
 * writers, scanners, and admin operations. Callers obtain {@link org.apache.fluss.client.table.Table}
 * and {@link org.apache.fluss.client.admin.Admin} instances per-thread from this connection.
 *
 * <p>This manager owns the connection and is responsible for closing it on shutdown.
 */
public class FlussConnectionManager implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(FlussConnectionManager.class);

    private final Connection connection;
    private final FlussConfig config;
    private volatile boolean closed = false;

    /**
     * Create a new connection manager and establish the Fluss connection.
     *
     * @param config Fluss connection configuration
     */
    public FlussConnectionManager(FlussConfig config) {
        this.config = config;
        Configuration flussConf = new Configuration();
        // Strip the "fluss://" scheme if present — Fluss expects just host:port
        String bootstrap = config.endpoint();
        if (bootstrap.startsWith("fluss://")) {
            bootstrap = bootstrap.substring("fluss://".length());
        }
        flussConf.setString("bootstrap.servers", bootstrap);
        log.info("Connecting to Fluss cluster at {}", bootstrap);
        this.connection = ConnectionFactory.createConnection(flussConf);
        log.info("Fluss connection established");
    }

    /**
     * Get the shared Fluss connection. The connection is thread-safe.
     *
     * @return the Fluss connection
     * @throws IllegalStateException if the manager has been closed
     */
    public Connection getConnection() {
        if (closed) {
            throw new IllegalStateException("FlussConnectionManager has been closed");
        }
        return connection;
    }

    /**
     * Get the Fluss configuration used for this connection.
     */
    public FlussConfig getConfig() {
        return config;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        log.info("Closing Fluss connection");
        try {
            connection.close();
        } catch (Exception e) {
            log.error("Error closing Fluss connection", e);
        }
    }
}
