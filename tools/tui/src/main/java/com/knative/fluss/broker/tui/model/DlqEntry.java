package com.knative.fluss.broker.tui.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

/**
 * DLQ record — extends the event envelope with dead-letter failure context.
 */
public record DlqEntry(
    String eventId,
    String eventType,
    String eventSource,
    Instant eventTime,
    String contentType,
    String dataContent,       // decoded payload
    Integer schemaId,
    Integer schemaVersion,
    Map<String, String> attributes,
    Instant ingestionTime,
    LocalDate ingestionDate,
    String dlqReason,
    Integer dlqAttempts,
    String dlqLastError,
    Instant dlqTimestamp
) {}
