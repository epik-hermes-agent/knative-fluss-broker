package com.knative.fluss.broker.common.config;

/**
 * Fluss cluster connection configuration.
 */
public record FlussConfig(
    String endpoint,
    int writeBatchSize,
    long writeBatchTimeoutMs,
    long ackTimeoutMs,
    int writeMaxRetries,
    long writeRetryBackoffMs
) {
    public static FlussConfig defaults() {
        return new FlussConfig(
            "fluss://localhost:9123",
            100,
            50,
            5000,
            3,
            100
        );
    }
}
