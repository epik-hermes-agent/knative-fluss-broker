package com.knative.fluss.broker.storage.fluss.mapping;

import com.knative.fluss.broker.common.model.Envelope;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

/**
 * Maps between {@link Envelope} objects and Fluss row representations.
 * Converts the envelope record to the column format expected by the Fluss client.
 */
public final class EnvelopeRowMapper {

    private EnvelopeRowMapper() {}

    /**
     * Convert an Envelope to a map of column name -> value for Fluss insertion.
     */
    public static Map<String, Object> toRow(Envelope envelope) {
        Map<String, Object> row = new HashMap<>();
        row.put("event_id", envelope.eventId());
        row.put("event_source", envelope.eventSource());
        row.put("event_type", envelope.eventType());
        row.put("event_time", envelope.eventTime());
        row.put("content_type", envelope.contentType());
        row.put("data", envelope.data());
        row.put("schema_id", envelope.schemaId());
        row.put("schema_version", envelope.schemaVersion());
        row.put("attributes", envelope.attributes());
        row.put("ingestion_time", envelope.ingestionTime());
        row.put("ingestion_date", envelope.ingestionDate());
        return row;
    }

    /**
     * Convert a Fluss row map back to an Envelope.
     */
    @SuppressWarnings("unchecked")
    public static Envelope fromRow(Map<String, Object> row) {
        return Envelope.builder()
            .eventId((String) row.get("event_id"))
            .eventSource((String) row.get("event_source"))
            .eventType((String) row.get("event_type"))
            .eventTime((Instant) row.get("event_time"))
            .contentType((String) row.get("content_type"))
            .data((byte[]) row.get("data"))
            .schemaId((Integer) row.get("schema_id"))
            .schemaVersion((Integer) row.get("schema_version"))
            .attributes((Map<String, String>) row.get("attributes"))
            .ingestionTime((Instant) row.get("ingestion_time"))
            .ingestionDate((LocalDate) row.get("ingestion_date"))
            .build();
    }
}
