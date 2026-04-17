package com.knative.fluss.broker.test.fixtures;

import com.knative.fluss.broker.common.model.Envelope;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

/**
 * Factory for creating test Envelopes with sensible defaults.
 */
public final class EnvelopeTestFactory {

    private static final String TEST_DATA = "{\"key\":\"value\"}";

    private EnvelopeTestFactory() {}

    public static Envelope createDefault() {
        return Envelope.builder()
            .eventId("test-event-" + System.nanoTime())
            .eventSource("/test/source")
            .eventType("com.example.test.event")
            .contentType("application/json")
            .data(TEST_DATA.getBytes())
            .build();
    }

    public static Envelope create(String eventType, String source, String data) {
        return Envelope.builder()
            .eventId("test-event-" + System.nanoTime())
            .eventSource(source)
            .eventType(eventType)
            .contentType("application/json")
            .data(data.getBytes())
            .build();
    }

    public static Envelope createWithAttributes(Map<String, String> attributes) {
        return Envelope.builder()
            .eventId("test-event-" + System.nanoTime())
            .eventSource("/test/source")
            .eventType("com.example.test.event")
            .contentType("application/json")
            .data("{}".getBytes())
            .attributes(attributes)
            .build();
    }

    public static Envelope createWithSchema(int schemaId, int schemaVersion) {
        return Envelope.builder()
            .eventId("test-event-" + System.nanoTime())
            .eventSource("/test/source")
            .eventType("com.example.test.event")
            .contentType("application/json")
            .data(TEST_DATA.getBytes())
            .schemaId(schemaId)
            .schemaVersion(schemaVersion)
            .build();
    }
}
