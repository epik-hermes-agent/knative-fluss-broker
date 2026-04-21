package com.knative.fluss.broker.tui.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DlqEntryTest {

    @Test
    void constructor_shouldCreateRecordWithAllFields() {
        // Given
        var now = Instant.now();
        var today = now.atZone(java.time.ZoneOffset.UTC).toLocalDate();
        var attrs = Map.of("key", "value");

        // When
        var entry = new DlqEntry(
                "evt-1", "test.type", "test.source", now,
                "application/json", "{\"data\":1}", 1, 0,
                attrs, now, today, "RETRY_EXHAUSTED", 3, "timeout", now
        );

        // Then
        assertThat(entry.eventId()).isEqualTo("evt-1");
        assertThat(entry.eventType()).isEqualTo("test.type");
        assertThat(entry.eventSource()).isEqualTo("test.source");
        assertThat(entry.eventTime()).isEqualTo(now);
        assertThat(entry.contentType()).isEqualTo("application/json");
        assertThat(entry.dataContent()).isEqualTo("{\"data\":1}");
        assertThat(entry.schemaId()).isEqualTo(1);
        assertThat(entry.schemaVersion()).isEqualTo(0);
        assertThat(entry.attributes()).containsEntry("key", "value");
        assertThat(entry.ingestionTime()).isEqualTo(now);
        assertThat(entry.ingestionDate()).isEqualTo(today);
        assertThat(entry.dlqReason()).isEqualTo("RETRY_EXHAUSTED");
        assertThat(entry.dlqAttempts()).isEqualTo(3);
        assertThat(entry.dlqLastError()).isEqualTo("timeout");
        assertThat(entry.dlqTimestamp()).isEqualTo(now);
    }

    @Test
    void recordsWithSameFields_shouldBeEqual() {
        // Given
        var now = Instant.now();
        var today = now.atZone(java.time.ZoneOffset.UTC).toLocalDate();

        // When
        var a = new DlqEntry(
                "evt-1", "test.type", "test.source", now,
                "application/json", "data", null, null,
                Map.of(), now, today, "RETRY_EXHAUSTED", 1, null, now
        );
        var b = new DlqEntry(
                "evt-1", "test.type", "test.source", now,
                "application/json", "data", null, null,
                Map.of(), now, today, "RETRY_EXHAUSTED", 1, null, now
        );

        // Then
        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void recordsWithDifferentEventIds_shouldNotBeEqual() {
        // Given
        var now = Instant.now();
        var today = now.atZone(java.time.ZoneOffset.UTC).toLocalDate();

        // When
        var a = new DlqEntry(
                "evt-1", "test.type", "test.source", now,
                "application/json", "data", null, null,
                Map.of(), now, today, "RETRY_EXHAUSTED", 1, null, now
        );
        var b = new DlqEntry(
                "evt-2", "test.type", "test.source", now,
                "application/json", "data", null, null,
                Map.of(), now, today, "RETRY_EXHAUSTED", 1, null, now
        );

        // Then
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void nullOptionalFields_shouldBeAllowed() {
        // Given
        var now = Instant.now();

        // When
        var entry = new DlqEntry(
                "evt-1", "test.type", "test.source", now,
                null, null, null, null,
                null, null, null, null, null, null, null
        );

        // Then
        assertThat(entry.eventId()).isEqualTo("evt-1");
        assertThat(entry.schemaId()).isNull();
        assertThat(entry.dlqReason()).isNull();
        assertThat(entry.attributes()).isNull();
    }
}
