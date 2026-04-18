package com.knative.fluss.broker.dispatcher.delivery;

import com.knative.fluss.broker.common.config.DeliveryConfig;
import com.knative.fluss.broker.common.model.Envelope;
import com.knative.fluss.broker.delivery.http.DeliveryResult;
import com.knative.fluss.broker.delivery.http.SubscriberClient;
import com.knative.fluss.broker.dispatcher.backpressure.CreditBucket;
import com.knative.fluss.broker.dispatcher.dlq.DlqHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Duration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DeliveryLaneTest {

    @Mock SubscriberClient subscriberClient;
    @Mock DlqHandler dlqHandler;

    private CreditBucket credits;
    private DeliveryLane lane;

    @BeforeEach
    void setUp() {
        credits = new CreditBucket(10, 100, 10, Duration.ofMillis(100));
        lane = new DeliveryLane(0, credits, subscriberClient, "http://localhost:8080",
            dlqHandler, DeliveryConfig.defaults(), 64);
    }

    @Test
    void enqueueShouldReturnTrueWhenBufferHasSpace() {
        assertThat(lane.enqueue(envelope("e1"))).isTrue();
        assertThat(lane.getBufferSize()).isEqualTo(1);
    }

    @Test
    void enqueueShouldReturnFalseWhenBufferFull() {
        for (int i = 0; i < 64; i++) {
            assertThat(lane.enqueue(envelope("e" + i))).isTrue();
        }
        assertThat(lane.enqueue(envelope("overflow"))).isFalse();
    }

    @Test
    void laneShouldStartActive() {
        assertThat(lane.isActive()).isTrue();
        assertThat(lane.getStatus()).isEqualTo(DeliveryLane.LaneStatus.ACTIVE);
    }

    @Test
    void drainShouldTransitionToStoppedAfterInflightDrains() {
        lane.drain();
        assertThat(lane.getStatus()).isEqualTo(DeliveryLane.LaneStatus.STOPPED);
    }

    @Test
    void availableCreditsShouldMatchBucket() {
        assertThat(lane.getAvailableCredits()).isEqualTo(10);
        credits.tryAcquire();
        assertThat(lane.getAvailableCredits()).isEqualTo(9);
    }

    @Test
    void onSuccessfulDeliveryCreditShouldBeReturned() throws Exception {
        when(subscriberClient.deliver(anyString(), any()))
            .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(
                DeliveryResult.success(200, "OK", 10)));

        lane.enqueue(envelope("e1"));
        Thread t = new Thread(lane::deliveryLoop);
        t.setDaemon(true);
        t.start();

        // Poll until credit is returned (max 5 seconds)
        long deadline = System.currentTimeMillis() + 5000;
        while (lane.getAvailableCredits() < 10 && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }

        assertThat(lane.getAvailableCredits()).isGreaterThanOrEqualTo(10);

        lane.drain();
        t.join(2000);
    }

    @Test
    void onNonRetryableErrorShouldSendToDlqAndReturnCredit() throws Exception {
        when(subscriberClient.deliver(anyString(), any()))
            .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(
                DeliveryResult.failure(400, "Bad Request", 10, "HTTP 400")));

        lane.enqueue(envelope("e1"));
        Thread t = new Thread(lane::deliveryLoop);
        t.setDaemon(true);
        t.start();

        // Poll until DLQ is called or credit returned
        verify(dlqHandler, timeout(5000)).sendToDlq(any(), eq("non_retryable_client_error"), eq(1), anyString());

        // Credit should be returned
        long deadline = System.currentTimeMillis() + 2000;
        while (lane.getAvailableCredits() < 10 && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertThat(lane.getAvailableCredits()).isGreaterThanOrEqualTo(10);

        lane.drain();
        t.join(2000);
    }

    @Test
    void onRetryableErrorShouldNotSendToDlqImmediately() throws Exception {
        when(subscriberClient.deliver(anyString(), any()))
            .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(
                DeliveryResult.failure(503, "Unavailable", 10, "HTTP 503")));

        lane.enqueue(envelope("e1"));
        Thread t = new Thread(lane::deliveryLoop);
        t.setDaemon(true);
        t.start();

        Thread.sleep(500);
        lane.drain();
        t.join(2000);

        // Should NOT go to DLQ — goes to retry queue instead
        verify(dlqHandler, never()).sendToDlq(any(), anyString(), anyInt(), anyString());
    }

    // --- Helpers ---

    private Envelope envelope(String id) {
        return Envelope.builder()
            .eventId(id)
            .eventSource("/test")
            .eventType("com.example.test")
            .build();
    }
}
