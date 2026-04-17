package com.knative.fluss.broker.common.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;

/**
 * The fixed envelope schema for storing CloudEvents in Fluss log tables.
 * Each row represents a single event with its metadata.
 *
 * <p>Primary key: eventId
 * Sort key: ingestionTime (ascending)
 * Partition key: ingestionDate
 */
public record Envelope(
    String eventId,
    String eventSource,
    String eventType,
    Instant eventTime,
    String contentType,
    byte[] data,
    Integer schemaId,
    Integer schemaVersion,
    Map<String, String> attributes,
    Instant ingestionTime,
    LocalDate ingestionDate
) {
    public Envelope {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(eventSource, "eventSource must not be null");
        Objects.requireNonNull(eventType, "eventType must not be null");
        Objects.requireNonNull(contentType, "contentType must not be null");
        Objects.requireNonNull(ingestionTime, "ingestionTime must not be null");
        Objects.requireNonNull(ingestionDate, "ingestionDate must not be null");
        attributes = attributes != null ? Map.copyOf(attributes) : Map.of();
    }

    /** Create a builder for constructing envelopes. */
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String eventId;
        private String eventSource;
        private String eventType;
        private Instant eventTime;
        private String contentType = "application/json";
        private byte[] data;
        private Integer schemaId;
        private Integer schemaVersion;
        private Map<String, String> attributes = Map.of();
        private Instant ingestionTime;
        private LocalDate ingestionDate;

        public Builder eventId(String eventId) { this.eventId = eventId; return this; }
        public Builder eventSource(String eventSource) { this.eventSource = eventSource; return this; }
        public Builder eventType(String eventType) { this.eventType = eventType; return this; }
        public Builder eventTime(Instant eventTime) { this.eventTime = eventTime; return this; }
        public Builder contentType(String contentType) { this.contentType = contentType; return this; }
        public Builder data(byte[] data) { this.data = data; return this; }
        public Builder schemaId(Integer schemaId) { this.schemaId = schemaId; return this; }
        public Builder schemaVersion(Integer schemaVersion) { this.schemaVersion = schemaVersion; return this; }
        public Builder attributes(Map<String, String> attributes) { this.attributes = attributes; return this; }
        public Builder ingestionTime(Instant ingestionTime) { this.ingestionTime = ingestionTime; return this; }
        public Builder ingestionDate(LocalDate ingestionDate) { this.ingestionDate = ingestionDate; return this; }

        public Envelope build() {
            var now = Instant.now();
            if (ingestionTime == null) ingestionTime = now;
            if (ingestionDate == null) ingestionDate = now.atZone(java.time.ZoneOffset.UTC).toLocalDate();
            return new Envelope(eventId, eventSource, eventType, eventTime,
                contentType, data, schemaId, schemaVersion, attributes,
                ingestionTime, ingestionDate);
        }
    }
}
