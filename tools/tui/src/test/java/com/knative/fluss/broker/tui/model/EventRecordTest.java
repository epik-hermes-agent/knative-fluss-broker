package com.knative.fluss.broker.tui.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EventRecordTest {

    @Test
    void constructor_shouldCreateRecordWithAllFields() {
        // Given
        var now = Instant.now();
        var today = now.atZone(java.time.ZoneOffset.UTC).toLocalDate();
        var attrs = Map.of("ce-id", "evt-1", "ce-type", "test.type");

        // When
        var record = new EventRecord(
                "evt-1", "test.type", "test.source", now,
                "application/json", "{\"data\":1}", 1, 0,
                attrs, now, today
        );

        // Then
        assertThat(record.eventId()).isEqualTo("evt-1");
        assertThat(record.eventType()).isEqualTo("test.type");
        assertThat(record.eventSource()).isEqualTo("test.source");
        assertThat(record.eventTime()).isEqualTo(now);
        assertThat(record.contentType()).isEqualTo("application/json");
        assertThat(record.dataContent()).isEqualTo("{\"data\":1}");
        assertThat(record.schemaId()).isEqualTo(1);
        assertThat(record.schemaVersion()).isEqualTo(0);
        assertThat(record.attributes()).containsEntry("ce-id", "evt-1");
        assertThat(record.ingestionTime()).isEqualTo(now);
        assertThat(record.ingestionDate()).isEqualTo(today);
    }

    @Test
    void recordsWithSameFields_shouldBeEqual() {
        // Given
        var now = Instant.now();
        var today = now.atZone(java.time.ZoneOffset.UTC).toLocalDate();

        // When
        var a = new EventRecord(
                "evt-1", "test.type", "test.source", now,
                "application/json", "data", 1, 0,
                Map.of(), now, today
        );
        var b = new EventRecord(
                "evt-1", "test.type", "test.source", now,
                "application/json", "data", 1, 0,
                Map.of(), now, today
        );

        // Then
        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void nullOptionalFields_shouldBeAllowed() {
        // Given
        var now = Instant.now();

        // When
        var record = new EventRecord(
                "evt-1", "test.type", "test.source", now,
                null, null, null, null,
                null, null, null
        );

        // Then
        assertThat(record.eventId()).isEqualTo("evt-1");
        assertThat(record.schemaId()).isNull();
        assertThat(record.attributes()).isNull();
    }
}
