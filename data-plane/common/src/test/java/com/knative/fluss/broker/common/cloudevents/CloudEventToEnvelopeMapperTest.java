package com.knative.fluss.broker.common.cloudevents;

import com.knative.fluss.broker.common.model.Envelope;
import io.cloudevents.core.builder.CloudEventBuilder;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.OffsetDateTime;
import static org.assertj.core.api.Assertions.*;

class CloudEventToEnvelopeMapperTest {

    @Test
    void shouldMapBasicCloudEvent() {
        var event = CloudEventBuilder.v1()
            .withId("test-123")
            .withSource(URI.create("/myapp/service"))
            .withType("com.example.order.created")
            .withDataContentType("application/json")
            .withData("{\"orderId\":\"123\"}".getBytes())
            .build();

        Envelope envelope = CloudEventToEnvelopeMapper.toEnvelope(event);

        assertThat(envelope.eventId()).isEqualTo("test-123");
        assertThat(envelope.eventSource()).isEqualTo("/myapp/service");
        assertThat(envelope.eventType()).isEqualTo("com.example.order.created");
        assertThat(envelope.contentType()).isEqualTo("application/json");
        assertThat(envelope.data()).isEqualTo("{\"orderId\":\"123\"}".getBytes());
    }

    @Test
    void shouldMapExtensionAttributes() {
        var event = CloudEventBuilder.v1()
            .withId("test-123")
            .withSource(URI.create("/src"))
            .withType("com.example.test")
            .withExtension("correlationid", "abc-123")
            .withExtension("region", "us-west-2")
            .build();

        Envelope envelope = CloudEventToEnvelopeMapper.toEnvelope(event);

        assertThat(envelope.attributes())
            .containsEntry("correlationid", "abc-123")
            .containsEntry("region", "us-west-2");
    }

    @Test
    void shouldMapEventTime() {
        var time = OffsetDateTime.parse("2024-01-15T10:30:00Z");
        var event = CloudEventBuilder.v1()
            .withId("test-123")
            .withSource(URI.create("/src"))
            .withType("com.example.test")
            .withTime(time)
            .build();

        Envelope envelope = CloudEventToEnvelopeMapper.toEnvelope(event);

        assertThat(envelope.eventTime()).isEqualTo(time.toInstant());
    }

    @Test
    void shouldDefaultContentTypeWhenMissing() {
        var event = CloudEventBuilder.v1()
            .withId("test-123")
            .withSource(URI.create("/src"))
            .withType("com.example.test")
            .build();

        Envelope envelope = CloudEventToEnvelopeMapper.toEnvelope(event);
        assertThat(envelope.contentType()).isEqualTo("application/json");
    }
}
