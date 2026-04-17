package com.knative.fluss.broker.common.metrics;

import io.micrometer.core.instrument.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * Centralized metrics registry for the Fluss broker.
 * Exposes Prometheus-compatible metrics via Micrometer.
 */
public final class BrokerMetrics {

    private final MeterRegistry registry;
    private final Map<String, Counter> counters = new ConcurrentHashMap<>();
    private final Map<String, Timer> timers = new ConcurrentHashMap<>();

    public BrokerMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** Get the underlying Micrometer registry. */
    public MeterRegistry registry() {
        return registry;
    }

    /** Increment a named counter. */
    public void increment(String name, String... tags) {
        counters.computeIfAbsent(name, k -> Counter.builder(k).tags(tags).register(registry))
                .increment();
    }

    /** Record a timing sample. */
    public void recordTimer(String name, long millis, String... tags) {
        timers.computeIfAbsent(name, k -> Timer.builder(k).tags(tags).register(registry))
              .record(java.time.Duration.ofMillis(millis));
    }

    /** Create or get a gauge. */
    @SuppressWarnings("unchecked")
    public <T extends Number> T gauge(String name, T obj, String... tags) {
        Gauge.builder(name, obj, Number::doubleValue).tags(tags).register(registry);
        return obj;
    }

    /** Create a new BrokerMetrics backed by a simple in-memory registry for testing. */
    public static BrokerMetrics noop() {
        return new BrokerMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    }
}
