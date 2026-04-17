package com.knative.fluss.broker.common.config;

/**
 * Top-level configuration for the Fluss broker.
 * Loaded from application.yaml or environment variables.
 */
public record BrokerConfig(
    FlussConfig fluss,
    DispatcherConfig dispatcher,
    SchemaConfig schema,
    IcebergConfig iceberg
) {
    public static BrokerConfig defaults() {
        return new BrokerConfig(
            FlussConfig.defaults(),
            DispatcherConfig.defaults(),
            SchemaConfig.defaults(),
            IcebergConfig.disabled()
        );
    }
}
