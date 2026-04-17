package com.knative.fluss.broker.dispatcher.dlq;

import com.knative.fluss.broker.common.model.Envelope;
import com.knative.fluss.broker.storage.fluss.client.FlussEventWriter;
import com.knative.fluss.broker.storage.fluss.tables.FlussTablePath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles dead letter queue operations.
 * Sends failed events to a dedicated DLQ Fluss table.
 */
public class DlqHandler {

    private static final Logger log = LoggerFactory.getLogger(DlqHandler.class);

    private final FlussEventWriter writer;
    private final FlussTablePath dlqTablePath;

    public DlqHandler(FlussEventWriter writer, FlussTablePath dlqTablePath) {
        this.writer = writer;
        this.dlqTablePath = dlqTablePath;
    }

    /**
     * Send a failed event to the DLQ.
     *
     * @param envelope   the failed event
     * @param reason     why it was DLQ'd
     * @param attempts   number of delivery attempts made
     * @param lastError  last error message
     */
    public void sendToDlq(Envelope envelope, String reason, int attempts, String lastError) {
        log.info("DLQ event {} reason={} attempts={} error={}",
            envelope.eventId(), reason, attempts, lastError);

        writer.writeToDlq(dlqTablePath, envelope, reason, attempts, lastError)
            .exceptionally(e -> {
                log.error("Failed to write event {} to DLQ", envelope.eventId(), e);
                return null;
            });
    }
}
