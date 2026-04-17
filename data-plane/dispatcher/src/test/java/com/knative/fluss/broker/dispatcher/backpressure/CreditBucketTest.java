package com.knative.fluss.broker.dispatcher.backpressure;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.assertj.core.api.Assertions.*;

class CreditBucketTest {

    @Test
    void shouldStartWithInitialCredits() {
        var bucket = new CreditBucket(50, 100, 10, Duration.ofMillis(100));
        assertThat(bucket.getAvailableCredits()).isEqualTo(50);
    }

    @Test
    void shouldAcquireCredits() {
        var bucket = new CreditBucket(3, 100, 10, Duration.ofMillis(100));

        assertThat(bucket.tryAcquire()).isTrue();
        assertThat(bucket.tryAcquire()).isTrue();
        assertThat(bucket.tryAcquire()).isTrue();
        assertThat(bucket.getAvailableCredits()).isEqualTo(0);
    }

    @Test
    void shouldRejectWhenNoCredits() {
        var bucket = new CreditBucket(1, 100, 10, Duration.ofMillis(100));

        assertThat(bucket.tryAcquire()).isTrue();
        assertThat(bucket.tryAcquire()).isFalse();
        assertThat(bucket.hasCredits()).isFalse();
    }

    @Test
    void shouldReleaseCredits() {
        var bucket = new CreditBucket(1, 100, 10, Duration.ofMillis(100));

        bucket.tryAcquire();
        assertThat(bucket.getAvailableCredits()).isEqualTo(0);

        bucket.release();
        assertThat(bucket.getAvailableCredits()).isEqualTo(1);
    }

    @Test
    void shouldNotExceedMaxCredits() {
        var bucket = new CreditBucket(100, 100, 10, Duration.ofMillis(100));

        bucket.release(); // Already at max
        assertThat(bucket.getAvailableCredits()).isEqualTo(100);
    }

    @Test
    void shouldRefillCreditsOverTime() throws Exception {
        var bucket = new CreditBucket(0, 100, 10, Duration.ofMillis(100));
        assertThat(bucket.hasCredits()).isFalse();

        // Wait for refill
        Thread.sleep(250);

        // Should have refilled: ~2 periods * 10 = ~20 credits
        assertThat(bucket.getAvailableCredits()).isGreaterThanOrEqualTo(10);
    }
}
