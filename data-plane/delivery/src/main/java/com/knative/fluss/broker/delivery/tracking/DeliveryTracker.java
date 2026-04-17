package com.knative.fluss.broker.delivery.tracking;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks delivery statistics per trigger for observability.
 */
public class DeliveryTracker {

    private final Map<String, TriggerStats> stats = new ConcurrentHashMap<>();

    public void recordDelivered(String triggerKey) {
        stats.computeIfAbsent(triggerKey, k -> new TriggerStats()).delivered.incrementAndGet();
    }

    public void recordRetried(String triggerKey) {
        stats.computeIfAbsent(triggerKey, k -> new TriggerStats()).retried.incrementAndGet();
    }

    public void recordDlq(String triggerKey) {
        stats.computeIfAbsent(triggerKey, k -> new TriggerStats()).dlq.incrementAndGet();
    }

    public void recordLatency(String triggerKey, long millis) {
        TriggerStats s = stats.computeIfAbsent(triggerKey, k -> new TriggerStats());
        s.totalLatencyMs.addAndGet(millis);
        s.sampleCount.incrementAndGet();
    }

    public TriggerStats getStats(String triggerKey) {
        return stats.getOrDefault(triggerKey, new TriggerStats());
    }

    public static class TriggerStats {
        public final AtomicLong delivered = new AtomicLong();
        public final AtomicLong retried = new AtomicLong();
        public final AtomicLong dlq = new AtomicLong();
        public final AtomicLong totalLatencyMs = new AtomicLong();
        public final AtomicLong sampleCount = new AtomicLong();

        public double averageLatencyMs() {
            long count = sampleCount.get();
            return count > 0 ? (double) totalLatencyMs.get() / count : 0;
        }
    }
}
