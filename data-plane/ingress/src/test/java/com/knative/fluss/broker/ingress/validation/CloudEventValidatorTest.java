package com.knative.fluss.broker.ingress.validation;

import io.cloudevents.core.builder.CloudEventBuilder;
import org.junit.jupiter.api.Test;

import java.net.URI;
import static org.assertj.core.api.Assertions.*;

class CloudEventValidatorTest {

    @Test
    void shouldAcceptValidCloudEvent() {
        var event = CloudEventBuilder.v1()
            .withId("test-123")
            .withSource(URI.create("/src"))
            .withType("com.example.test")
            .build();

        assertThatCode(() -> CloudEventValidator.validate(event))
            .doesNotThrowAnyException();
    }

    @Test
    void shouldRejectNullEvent() {
        assertThatThrownBy(() -> CloudEventValidator.validate(null))
            .isInstanceOf(InvalidCloudEventException.class)
            .hasMessageContaining("null");
    }

    @Test
    void shouldRejectEmptyId() {
        var event = CloudEventBuilder.v1()
            .withId("")
            .withSource(URI.create("/src"))
            .withType("com.example.test")
            .build();

        assertThatThrownBy(() -> CloudEventValidator.validate(event))
            .isInstanceOf(InvalidCloudEventException.class)
            .hasMessageContaining("id");
    }

    @Test
    void shouldRejectEmptyType() {
        var event = CloudEventBuilder.v1()
            .withId("test-123")
            .withSource(URI.create("/src"))
            .withType("")
            .build();

        assertThatThrownBy(() -> CloudEventValidator.validate(event))
            .isInstanceOf(InvalidCloudEventException.class)
            .hasMessageContaining("type");
    }

    @Test
    void shouldRejectNullSource() {
        assertThatThrownBy(() -> {
            var event = CloudEventBuilder.v1()
                .withId("test-123")
                .withSource((URI) null)
                .withType("com.example.test")
                .build();
            CloudEventValidator.validate(event);
        }).isInstanceOf(Exception.class);
    }
}
