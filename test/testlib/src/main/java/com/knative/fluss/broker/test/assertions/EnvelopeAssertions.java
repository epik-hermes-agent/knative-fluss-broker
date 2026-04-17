package com.knative.fluss.broker.test.assertions;

import com.knative.fluss.broker.common.model.Envelope;
import org.assertj.core.api.Assertions;

/**
 * Custom AssertJ assertions for Envelope objects.
 */
public final class EnvelopeAssertions extends Assertions {

    private EnvelopeAssertions() {}

    public static void assertEnvelopeValid(Envelope envelope) {
        assertThat(envelope).isNotNull();
        assertThat(envelope.eventId()).isNotBlank();
        assertThat(envelope.eventSource()).isNotBlank();
        assertThat(envelope.eventType()).isNotBlank();
        assertThat(envelope.contentType()).isNotBlank();
        assertThat(envelope.ingestionTime()).isNotNull();
        assertThat(envelope.ingestionDate()).isNotNull();
    }

    public static void assertEnvelopeHasSchema(Envelope envelope, int expectedSchemaId, int expectedVersion) {
        assertThat(envelope.schemaId()).isEqualTo(expectedSchemaId);
        assertThat(envelope.schemaVersion()).isEqualTo(expectedVersion);
    }
}
