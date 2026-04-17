package com.knative.fluss.broker.common.config;

/**
 * Delivery retry and backoff configuration.
 */
public record DeliveryConfig(
    int maxAttempts,
    long initialDelayMs,
    double backoffMultiplier,
    long maxDelayMs,
    long jitterMaxMs,
    long subscriberTimeoutMs
) {
    public static DeliveryConfig defaults() {
        return new DeliveryConfig(
            5,          // maxAttempts
            1000,       // initialDelayMs
            2.0,        // backoffMultiplier
            60000,      // maxDelayMs
            250,        // jitterMaxMs
            30000       // subscriberTimeoutMs
        );
    }
}
