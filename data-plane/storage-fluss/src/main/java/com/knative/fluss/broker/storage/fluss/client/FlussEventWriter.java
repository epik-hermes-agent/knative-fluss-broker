package com.knative.fluss.broker.storage.fluss.client;

import com.knative.fluss.broker.common.config.FlussConfig;
import com.knative.fluss.broker.common.model.Envelope;
import com.knative.fluss.broker.storage.fluss.mapping.EnvelopeRowMapper;
import com.knative.fluss.broker.storage.fluss.tables.FlussTablePath;
import org.apache.fluss.client.table.Table;
import org.apache.fluss.client.table.writer.AppendWriter;
import org.apache.fluss.metadata.TablePath;
import org.apache.fluss.row.BinaryString;
import org.apache.fluss.row.GenericRow;
import org.apache.fluss.row.TimestampLtz;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Writes events (Envelopes) to Fluss log tables using the real Fluss {@link AppendWriter}.
 *
 * <p>Uses Fluss append-only log tables — events are written as {@link GenericRow}
 * via the {@link EnvelopeRowMapper}. Writes are asynchronous: the {@code CompletableFuture}
 * completes when Fluss has acknowledged the write.
 *
 * <p><b>Thread safety:</b> The Fluss {@link Table} and {@link AppendWriter} are NOT thread-safe,
 * so this class serializes writes through a single-threaded executor.
 */
public class FlussEventWriter implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(FlussEventWriter.class);

    private final FlussConnectionManager connectionManager;
    private final FlussConfig config;
    private final ExecutorService writeExecutor;

    public FlussEventWriter(FlussConnectionManager connectionManager, FlussConfig config) {
        this.connectionManager = connectionManager;
        this.config = config;
        this.writeExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "fluss-writer");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Write a single event to the Fluss log table.
     *
     * @param tablePath target table
     * @param envelope  the event envelope to write
     * @return future that completes when Fluss confirms the write
     */
    public CompletableFuture<Void> write(FlussTablePath tablePath, Envelope envelope) {
        return CompletableFuture.runAsync(() -> {
            try {
                Table table = getTable(tablePath);
                AppendWriter writer = table.newAppend().createWriter();
                try {
                    GenericRow row = EnvelopeRowMapper.toGenericRow(envelope);
                    // append() returns CompletableFuture<AppendResult> — we wait for acknowledgment
                    writer.append(row).get(config.ackTimeoutMs(), TimeUnit.MILLISECONDS);
                    writer.flush();
                    log.debug("Wrote event {} to {}", envelope.eventId(), tablePath.fullPath());
                } catch (Exception e) {
                    throw new RuntimeException("Fluss write failed for event: " + envelope.eventId(), e);
                }
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                log.error("Failed to write event {} to {}",
                        envelope.eventId(), tablePath.fullPath(), e);
                throw new RuntimeException("Fluss write failed for event: " + envelope.eventId(), e);
            }
        }, writeExecutor);
    }

    /**
     * Write a batch of events to the Fluss log table.
     *
     * @param tablePath target table
     * @param envelopes batch of event envelopes
     * @return future that completes when all events are confirmed
     */
    public CompletableFuture<Void> writeBatch(FlussTablePath tablePath, List<Envelope> envelopes) {
        return CompletableFuture.runAsync(() -> {
            try {
                Table table = getTable(tablePath);
                AppendWriter writer = table.newAppend().createWriter();
                try {
                    CompletableFuture<?>[] futures = new CompletableFuture<?>[envelopes.size()];
                    for (int i = 0; i < envelopes.size(); i++) {
                        GenericRow row = EnvelopeRowMapper.toGenericRow(envelopes.get(i));
                        futures[i] = writer.append(row);
                    }
                    // Wait for all appends to complete
                    CompletableFuture.allOf(futures)
                            .get(config.ackTimeoutMs() * envelopes.size(), TimeUnit.MILLISECONDS);
                    writer.flush();
                    log.debug("Wrote batch of {} events to {}", envelopes.size(), tablePath.fullPath());
                } catch (Exception e) {
                    throw new RuntimeException("Fluss batch write failed", e);
                }
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                log.error("Failed to write batch of {} events to {}",
                        envelopes.size(), tablePath.fullPath(), e);
                throw new RuntimeException("Fluss batch write failed", e);
            }
        }, writeExecutor);
    }

    /**
     * Write an event to the DLQ table with DLQ metadata columns.
     *
     * <p>The DLQ table has additional columns: dlq_reason, dlq_attempts, dlq_last_error,
     * dlq_timestamp. This method builds an extended row that includes these fields.
     *
     * @param dlqTablePath the DLQ table path
     * @param envelope     the failed event
     * @param reason       reason for DLQ (e.g., "retries_exhausted", "non_retryable_client_error")
     * @param attempts     number of delivery attempts made
     * @param lastError    last error message
     * @return future that completes when the DLQ write is confirmed
     */
    public CompletableFuture<Void> writeToDlq(FlussTablePath dlqTablePath, Envelope envelope,
                                              String reason, int attempts, String lastError) {
        return CompletableFuture.runAsync(() -> {
            try {
                Table table = getTable(dlqTablePath);
                AppendWriter writer = table.newAppend().createWriter();
                try {
                    // Build base row from envelope
                    GenericRow baseRow = EnvelopeRowMapper.toGenericRow(envelope);
                    // DLQ table has 15 columns (11 base + 4 DLQ metadata)
                    GenericRow dlqRow = new GenericRow(15);
                    // Copy base columns
                    for (int i = 0; i < EnvelopeRowMapper.COLUMN_COUNT; i++) {
                        dlqRow.setField(i, baseRow.getField(i));
                    }
                    // DLQ metadata columns (indices 11-14)
                    dlqRow.setField(11, BinaryString.fromString(reason));
                    dlqRow.setField(12, attempts);
                    dlqRow.setField(13, lastError != null
                            ? BinaryString.fromString(lastError)
                            : null);
                    dlqRow.setField(14, TimestampLtz.fromEpochMillis(
                            Instant.now().toEpochMilli()));

                    writer.append(dlqRow).get(config.ackTimeoutMs(), TimeUnit.MILLISECONDS);
                    writer.flush();
                    log.info("Wrote event {} to DLQ {}: reason={}, attempts={}",
                            envelope.eventId(), dlqTablePath.fullPath(), reason, attempts);
                } catch (Exception e) {
                    throw new RuntimeException("Fluss DLQ write failed", e);
                }
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                log.error("Failed to write event {} to DLQ {}",
                        envelope.eventId(), dlqTablePath.fullPath(), e);
                throw new RuntimeException("Fluss DLQ write failed", e);
            }
        }, writeExecutor);
    }

    /**
     * Get the Fluss Table handle for the given path.
     */
    private Table getTable(FlussTablePath tablePath) {
        TablePath flussPath = TablePath.of(tablePath.database(), tablePath.table());
        return connectionManager.getConnection().getTable(flussPath);
    }

    @Override
    public void close() {
        writeExecutor.shutdown();
        try {
            writeExecutor.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            writeExecutor.shutdownNow();
        }
    }
}
