package com.knative.fluss.broker.ingress.mapping;

import com.knative.fluss.broker.common.model.Envelope;
import org.junit.jupiter.api.Test;

import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class EventFilterTest {

    @Test
    void shouldMatchWhenNoFilter() {
        var envelope = Envelope.builder()
            .eventId("e1").eventSource("/src").eventType("com.example.test")
            .build();

        assertThat(EventFilter.matches(envelope, Map.of())).isTrue();
        assertThat(EventFilter.matches(envelope, null)).isTrue();
    }

    @Test
    void shouldMatchExactType() {
        var envelope = Envelope.builder()
            .eventId("e1").eventSource("/src").eventType("com.example.order.created")
            .build();

        assertThat(EventFilter.matches(envelope, Map.of("type", "com.example.order.created"))).isTrue();
        assertThat(EventFilter.matches(envelope, Map.of("type", "com.example.order.cancelled"))).isFalse();
    }

    @Test
    void shouldMatchWildcardSource() {
        var envelope = Envelope.builder()
            .eventId("e1").eventSource("/myapp/orders/service")
            .eventType("com.example.test")
            .build();

        assertThat(EventFilter.matches(envelope, Map.of("source", "/myapp/*"))).isTrue();
        assertThat(EventFilter.matches(envelope, Map.of("source", "/otherapp/*"))).isFalse();
    }

    @Test
    void shouldMatchExtensionAttributes() {
        var envelope = Envelope.builder()
            .eventId("e1").eventSource("/src").eventType("com.example.test")
            .attributes(Map.of("region", "us-west-2"))
            .build();

        assertThat(EventFilter.matches(envelope, Map.of("ext_region", "us-west-2"))).isTrue();
        assertThat(EventFilter.matches(envelope, Map.of("ext_region", "us-east-1"))).isFalse();
    }

    @Test
    void shouldRequireAllAttributesToMatch() {
        var envelope = Envelope.builder()
            .eventId("e1").eventSource("/src").eventType("com.example.test")
            .attributes(Map.of("region", "us-west-2"))
            .build();

        var filter = Map.of(
            "type", "com.example.test",
            "ext_region", "us-west-2"
        );
        assertThat(EventFilter.matches(envelope, filter)).isTrue();

        var wrongFilter = Map.of(
            "type", "com.example.test",
            "ext_region", "us-east-1"
        );
        assertThat(EventFilter.matches(envelope, wrongFilter)).isFalse();
    }

    @Test
    void shouldTestWildcardMatching() {
        assertThat(EventFilter.matchesFilter("/myapp/orders", "/myapp/*")).isTrue();
        assertThat(EventFilter.matchesFilter("/other/thing", "/myapp/*")).isFalse();
        assertThat(EventFilter.matchesFilter("exact", "exact")).isTrue();
        assertThat(EventFilter.matchesFilter("not-exact", "exact")).isFalse();
    }
}
