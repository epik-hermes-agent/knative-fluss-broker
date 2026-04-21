package com.knative.fluss.broker.tui.model;

import java.time.Instant;

/**
 * Iceberg tiering summary — how many rows have been tiered and when.
 */
public record TieringStatus(
    String tableName,
    long tieredRowCount,
    Instant lastTieringTime
) {}
