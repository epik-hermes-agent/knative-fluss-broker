package com.knative.fluss.broker.tui.model;

/**
 * Per-trigger dispatcher state — delivery progress and backpressure info.
 */
public record TriggerState(
    String triggerKey,
    String subscriberUri,
    long cursor,
    int laneCount,
    int inflightCount,
    int creditsAvailable
) {}
