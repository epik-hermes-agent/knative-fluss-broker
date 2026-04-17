package com.knative.fluss.broker.common.config;

/**
 * Dispatcher configuration including backpressure settings.
 */
public record DispatcherConfig(
    int maxConcurrency,
    int initialCredits,
    int maxCredits,
    int creditRefillRate,
    long creditRefillIntervalMs,
    int pauseThreshold,
    int resumeThreshold,
    int laneBufferSize,
    DeliveryConfig delivery
) {
    public static DispatcherConfig defaults() {
        return new DispatcherConfig(
            10,     // maxConcurrency
            100,    // initialCredits
            200,    // maxCredits
            10,     // creditRefillRate
            100,    // creditRefillIntervalMs
            0,      // pauseThreshold
            10,     // resumeThreshold
            50,     // laneBufferSize
            DeliveryConfig.defaults()
        );
    }
}
