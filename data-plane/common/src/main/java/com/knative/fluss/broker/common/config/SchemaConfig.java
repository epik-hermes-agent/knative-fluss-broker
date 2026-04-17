package com.knative.fluss.broker.common.config;

/**
 * Schema registry configuration.
 */
public record SchemaConfig(
    boolean enabled,
    boolean autoRegister,
    boolean enforce,
    int inferenceMaxDepth,
    int inferenceMaxProperties,
    int cacheSize,
    long cacheTtlMinutes
) {
    public static SchemaConfig defaults() {
        return new SchemaConfig(
            true,   // enabled
            true,   // autoRegister
            false,  // enforce (v1: lenient)
            10,     // inferenceMaxDepth
            100,    // inferenceMaxProperties
            1000,   // cacheSize
            60      // cacheTtlMinutes
        );
    }
}
