package com.knative.fluss.broker.common.model;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class EnvelopeTest {

    @Test
    void shouldCreateEnvelopeWithBuilder() {
        var envelope = Envelope.builder()
            .eventId("evt-1")
            .eventSource("/src")
            .eventType("com.example.test")
            .contentType("application/json")
            .data("{\"k\":\"v\"}".getBytes())
            .build();

        assertThat(envelope.eventId()).isEqualTo("evt-1");
        assertThat(envelope.eventSource()).isEqualTo("/src");
        assertThat(envelope.eventType()).isEqualTo("com.example.test");
        assertThat(envelope.contentType()).isEqualTo("application/json");
        assertThat(envelope.data()).isEqualTo("{\"k\":\"v\"}".getBytes());
        assertThat(envelope.ingestionTime()).isNotNull();
        assertThat(envelope.ingestionDate()).isNotNull();
    }

    @Test
    void shouldAutoSetIngestionTimestamp() {
        var before = Instant.now();
        var envelope = Envelope.builder()
            .eventId("evt-1")
            .eventSource("/src")
            .eventType("com.example.test")
            .build();
        var after = Instant.now();

        assertThat(envelope.ingestionTime()).isAfterOrEqualTo(before);
        assertThat(envelope.ingestionTime()).isBeforeOrEqualTo(after);
        assertThat(envelope.ingestionDate()).isEqualTo(java.time.LocalDate.now(java.time.ZoneOffset.UTC));
    }

    @Test
    void shouldRejectNullRequiredFields() {
        assertThatThrownBy(() -> Envelope.builder().eventSource("/s").eventType("t").build())
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("eventId");

        assertThatThrownBy(() -> Envelope.builder().eventId("e").eventType("t").build())
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("eventSource");
    }

    @Test
    void shouldCopyAttributes() {
        var attrs = Map.of("key1", "val1", "key2", "val2");
        var envelope = Envelope.builder()
            .eventId("e1").eventSource("/s").eventType("t")
            .attributes(attrs)
            .build();

        assertThat(envelope.attributes()).containsAllEntriesOf(attrs);
        // Verify it's an unmodifiable copy
        assertThatThrownBy(() -> envelope.attributes().put("new", "val"))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldDefaultAttributesToEmpty() {
        var envelope = Envelope.builder()
            .eventId("e1").eventSource("/s").eventType("t")
            .build();
        assertThat(envelope.attributes()).isEmpty();
    }
}
