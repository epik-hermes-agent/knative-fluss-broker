package com.knative.fluss.broker.iceberg.config;

import com.knative.fluss.broker.common.config.IcebergConfig;

/**
 * Extended configuration for the Iceberg tiering job.
 */
public record IcebergTieringConfig(
    IcebergConfig base,
    long tieringAgeMinutes,    // Events older than this are tiered
    int scanBatchSize,        // Events per scan batch
    boolean dryRun            // If true, log but don't write
) {
    public static IcebergTieringConfig defaults(IcebergConfig base) {
        return new IcebergTieringConfig(base, 60, 1000, false);
    }
}
