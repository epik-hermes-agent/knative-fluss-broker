package com.knative.fluss.broker.tui.model;

/**
 * Table-level stats — counts and metadata for a Fluss table.
 */
public record TableStats(
    String tableName,
    long rowCount,
    int bucketCount,
    boolean datalakeEnabled
) {}
