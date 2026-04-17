package com.knative.fluss.broker.dispatcher.backpressure;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Token-bucket credit system for backpressure management.
 * Each delivery lane has its own credit bucket.
 *
 * <p>Credits are consumed on event delivery and returned on ack/nack.
 * A periodic refill timer adds credits up to the configured maximum.
 */
public class CreditBucket {

    private final int maxCredits;
    private final int refillRate;
    private final Duration refillInterval;

    private int availableCredits;
    private Instant lastRefill;
    private final ReentrantLock lock = new ReentrantLock();

    public CreditBucket(int initialCredits, int maxCredits, int refillRate, Duration refillInterval) {
        this.maxCredits = maxCredits;
        this.refillRate = refillRate;
        this.refillInterval = refillInterval;
        this.availableCredits = Math.min(initialCredits, maxCredits);
        this.lastRefill = Instant.now();
    }

    /**
     * Try to acquire a credit for event delivery.
     *
     * @return true if a credit was acquired, false if none available
     */
    public boolean tryAcquire() {
        lock.lock();
        try {
            refill();
            if (availableCredits > 0) {
                availableCredits--;
                return true;
            }
            return false;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Return a credit after delivery completes (ack or nack).
     */
    public void release() {
        lock.lock();
        try {
            availableCredits = Math.min(availableCredits + 1, maxCredits);
        } finally {
            lock.unlock();
        }
    }

    /** Get the current number of available credits. */
    public int getAvailableCredits() {
        lock.lock();
        try {
            refill();
            return availableCredits;
        } finally {
            lock.unlock();
        }
    }

    /** Check if credits are available. */
    public boolean hasCredits() {
        return getAvailableCredits() > 0;
    }

    private void refill() {
        var now = Instant.now();
        var elapsed = Duration.between(lastRefill, now);
        long periods = elapsed.dividedBy(refillInterval);
        if (periods > 0) {
            int added = (int) periods * refillRate;
            availableCredits = Math.min(availableCredits + added, maxCredits);
            // Advance by exact periods to avoid drift (not to 'now')
            lastRefill = lastRefill.plus(periods * refillInterval.toMillis(), java.time.temporal.ChronoUnit.MILLIS);
        }
    }
}
