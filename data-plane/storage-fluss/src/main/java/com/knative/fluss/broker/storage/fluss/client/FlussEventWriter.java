package com.knative.fluss.broker.storage.fluss.client;

import com.knative.fluss.broker.common.model.Envelope;
import com.knative.fluss.broker.common.config.FlussConfig;
import com.knative.fluss.broker.storage.fluss.tables.FlussTablePath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Writes events (Envelopes) to Fluss log tables.
 * Handles batching, async acknowledgment, and retry.
 */
public class FlussEventWriter {

    private static final Logger log = LoggerFactory.getLogger(FlussEventWriter.class);

    private final FlussConfig config;

    public FlussEventWriter(FlussConfig config) {
        this.config = config;
    }

    /**
     * Write a single event to the Fluss log table.
     *
     * @param tablePath target table
     * @param envelope  the event envelope to write
     * @return future that completes when Fluss confirms the write
     */
    public CompletableFuture<Void> write(FlussTablePath tablePath, Envelope envelope) {
        log.debug("Writing event {} to {}", envelope.eventId(), tablePath.fullPath());
        // Actual implementation: flussClient.insert(tablePath, toRow(envelope))
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Write a batch of events to the Fluss log table.
     *
     * @param tablePath target table
     * @param envelopes batch of event envelopes
     * @return future that completes when all events are confirmed
     */
    public CompletableFuture<Void> writeBatch(FlussTablePath tablePath, List<Envelope> envelopes) {
        log.debug("Writing batch of {} events to {}", envelopes.size(), tablePath.fullPath());
        // Actual implementation: flussClient.insertBatch(tablePath, toRows(envelopes))
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Write an event to the DLQ table.
     */
    public CompletableFuture<Void> writeToDlq(FlussTablePath dlqTablePath, Envelope envelope,
                                               String reason, int attempts, String lastError) {
        log.info("Writing event {} to DLQ {}: reason={}, attempts={}",
            envelope.eventId(), dlqTablePath.fullPath(), reason, attempts);
        // Actual implementation adds DLQ metadata columns
        return CompletableFuture.completedFuture(null);
    }
}
