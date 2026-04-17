package com.knative.fluss.broker.storage.fluss.client;

import com.knative.fluss.broker.common.model.Envelope;
import com.knative.fluss.broker.storage.fluss.tables.FlussTablePath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Scans events from Fluss log tables for dispatcher consumption.
 * Maintains per-trigger cursor offsets.
 */
public class FlussEventScanner {

    private static final Logger log = LoggerFactory.getLogger(FlussEventScanner.class);

    private final Map<String, Long> cursors = new ConcurrentHashMap<>();

    /**
     * Scan the next batch of events from the given table starting at the trigger's cursor.
     *
     * @param tablePath  table to scan
     * @param triggerKey unique key for this trigger (for cursor tracking)
     * @param batchSize  max events to read
     * @return list of envelopes (may be empty if no new events)
     */
    public List<Envelope> scan(FlussTablePath tablePath, String triggerKey, int batchSize) {
        long offset = cursors.getOrDefault(triggerKey, 0L);
        log.trace("Scanning {} from offset {} (batch={})", tablePath.fullPath(), offset, batchSize);

        // Actual implementation: flussClient.scan(tablePath, offset, batchSize)
        // Returns events after the cursor position
        return Collections.emptyList();
    }

    /**
     * Advance the cursor for a trigger after successful delivery.
     *
     * @param triggerKey the trigger's key
     * @param eventsRead number of events consumed in the last scan
     */
    public void advanceCursor(String triggerKey, int eventsRead) {
        cursors.merge(triggerKey, (long) eventsRead, Long::sum);
    }

    /** Get the current cursor position for a trigger. */
    public long getCursor(String triggerKey) {
        return cursors.getOrDefault(triggerKey, 0L);
    }
}
