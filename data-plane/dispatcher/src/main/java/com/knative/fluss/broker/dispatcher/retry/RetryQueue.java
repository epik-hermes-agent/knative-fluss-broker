package com.knative.fluss.broker.dispatcher.retry;

import com.knative.fluss.broker.common.config.DeliveryConfig;
import com.knative.fluss.broker.common.model.Envelope;

import java.time.Instant;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.*;

/**
 * Delay-based retry queue with exponential backoff and jitter.
 * Events are scheduled for re-delivery after a calculated delay.
 */
public class RetryQueue {

    private final DeliveryConfig config;
    private final Random jitterRandom = new Random();
    private final Map<String, RetryEntry> entries = new ConcurrentHashMap<>();

    private final PriorityBlockingQueue<RetryEntry> queue =
        new PriorityBlockingQueue<>(100, (a, b) -> a.retryAt.compareTo(b.retryAt));

    public RetryQueue(DeliveryConfig config) {
        this.config = config;
    }

    /**
     * Schedule an event for retry.
     *
     * @param envelope the event to retry
     * @param attempt  the current attempt number
     */
    public void add(Envelope envelope, int attempt) {
        long delayMs = calculateDelay(attempt);
        Instant retryAt = Instant.now().plusMillis(delayMs);

        RetryEntry entry = new RetryEntry(envelope, attempt, retryAt);
        entries.put(envelope.eventId(), entry);
        queue.put(entry);
    }

    /**
     * Poll the next event that's ready for retry.
     *
     * @return the event ready for re-delivery, or null if none ready
     */
    public Envelope pollReady() {
        RetryEntry entry = queue.peek();
        if (entry != null && entry.retryAt.isBefore(Instant.now())) {
            queue.poll();
            entries.remove(entry.envelope.eventId());
            return entry.envelope;
        }
        return null;
    }

    /** Get the current attempt count for an event. */
    public int getAttempt(String eventId) {
        RetryEntry entry = entries.get(eventId);
        return entry != null ? entry.attempt : 0;
    }

    /** Get the number of events currently in the retry queue. */
    public int size() {
        return queue.size();
    }

    private long calculateDelay(int attempt) {
        double delay = config.initialDelayMs() * Math.pow(config.backoffMultiplier(), attempt - 1);
        long cappedDelay = Math.min((long) delay, config.maxDelayMs());
        // Add jitter: 0 to jitterMaxMs
        long jitter = (long) (jitterRandom.nextDouble() * config.jitterMaxMs());
        return cappedDelay + jitter;
    }

    private record RetryEntry(Envelope envelope, int attempt, Instant retryAt) {}
}
