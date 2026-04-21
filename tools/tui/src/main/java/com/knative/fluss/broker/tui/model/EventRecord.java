package com.knative.fluss.broker.tui.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

/**
 * Full event record carrying all 11 envelope fields plus decoded data content.
 * Used by Ingress, Fluss Storage, and Iceberg panels.
 */
public record EventRecord(
    String eventId,
    String eventType,
    String eventSource,
    Instant eventTime,
    String contentType,
    String dataContent,       // decoded from byte[] — JSON or UTF-8 text
    Integer schemaId,
    Integer schemaVersion,
    Map<String, String> attributes,
    Instant ingestionTime,
    LocalDate ingestionDate
) {}
