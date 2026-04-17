package com.knative.fluss.broker.dispatcher.retry;

import com.knative.fluss.broker.common.config.DeliveryConfig;
import com.knative.fluss.broker.common.model.Envelope;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class RetryQueueTest {

    private final DeliveryConfig config = DeliveryConfig.defaults();

    @Test
    void shouldAddAndPollReady() throws Exception {
        var queue = new RetryQueue(config);
        var envelope = Envelope.builder()
            .eventId("e1").eventSource("/s").eventType("t")
            .build();

        // Initial delay is 1000ms, so not ready immediately
        queue.add(envelope, 1);
        assertThat(queue.pollReady()).isNull();
        assertThat(queue.size()).isEqualTo(1);

        // After delay, should be ready
        Thread.sleep(1200);
        var ready = queue.pollReady();
        assertThat(ready).isNotNull();
        assertThat(ready.eventId()).isEqualTo("e1");
        assertThat(queue.size()).isEqualTo(0);
    }

    @Test
    void shouldTrackAttemptCount() {
        var queue = new RetryQueue(config);
        var envelope = Envelope.builder()
            .eventId("e1").eventSource("/s").eventType("t")
            .build();

        queue.add(envelope, 3);
        assertThat(queue.getAttempt("e1")).isEqualTo(3);
        assertThat(queue.getAttempt("unknown")).isEqualTo(0);
    }

    @Test
    void shouldReturnNullWhenNoRetriesReady() {
        var queue = new RetryQueue(config);
        assertThat(queue.pollReady()).isNull();
    }
}
