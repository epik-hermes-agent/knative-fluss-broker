package com.knative.fluss.broker.integration.dispatcher;

import com.knative.fluss.broker.dispatcher.backpressure.CreditBucket;
import com.knative.fluss.broker.common.model.Envelope;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration test: Credit-based backpressure behavior.
 * Verifies that lanes pause when credits are exhausted and resume after refill.
 */
class BackpressureIntegrationTest {

    @Test
    void shouldPauseWhenCreditsExhausted() {
        var bucket = new CreditBucket(3, 10, 5, Duration.ofMillis(100));

        // Acquire all credits
        for (int i = 0; i < 3; i++) {
            assertThat(bucket.tryAcquire()).isTrue();
        }
        assertThat(bucket.tryAcquire()).isFalse();
        assertThat(bucket.hasCredits()).isFalse();
    }

    @Test
    void shouldResumeAfterRefill() throws Exception {
        var bucket = new CreditBucket(1, 10, 5, Duration.ofMillis(100));
        bucket.tryAcquire();
        assertThat(bucket.hasCredits()).isFalse();

        // Wait for refill
        Thread.sleep(150);
        assertThat(bucket.hasCredits()).isTrue();
    }

    @Test
    void shouldHandleConcurrentAcquires() throws Exception {
        var bucket = new CreditBucket(100, 200, 10, Duration.ofMillis(100));
        var acquired = new AtomicInteger();
        var latch = new CountDownLatch(10);

        for (int i = 0; i < 10; i++) {
            new Thread(() -> {
                for (int j = 0; j < 15; j++) {
                    if (bucket.tryAcquire()) {
                        acquired.incrementAndGet();
                    }
                }
                latch.countDown();
            }).start();
        }

        latch.await(5, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(acquired.get()).isEqualTo(100); // Max credits
    }
}
