package com.knative.fluss.broker.dispatcher.delivery;

import com.knative.fluss.broker.common.model.Envelope;
import com.knative.fluss.broker.common.config.DeliveryConfig;
import com.knative.fluss.broker.delivery.http.DeliveryResult;
import com.knative.fluss.broker.delivery.http.SubscriberClient;
import com.knative.fluss.broker.dispatcher.backpressure.CreditBucket;
import com.knative.fluss.broker.dispatcher.dlq.DlqHandler;
import com.knative.fluss.broker.dispatcher.retry.RetryQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.*;

/**
 * A single delivery lane within a trigger dispatcher.
 * Manages credit-based backpressure, delivery, and retry for one concurrency slot.
 */
public class DeliveryLane {

    private static final Logger log = LoggerFactory.getLogger(DeliveryLane.class);

    private final int laneId;
    private final CreditBucket credits;
    private final SubscriberClient subscriberClient;
    private final String subscriberUri;
    private final DlqHandler dlqHandler;
    private final RetryQueue retryQueue;
    private final DeliveryConfig deliveryConfig;

    private final BlockingQueue<Envelope> buffer;
    private final ConcurrentLinkedQueue<Envelope> inflightEvents = new ConcurrentLinkedQueue<>();
    private volatile LaneStatus status = LaneStatus.ACTIVE;

    public enum LaneStatus { ACTIVE, PAUSED, DRAINING, STOPPED }

    public DeliveryLane(int laneId, CreditBucket credits, SubscriberClient subscriberClient,
                        String subscriberUri, DlqHandler dlqHandler,
                        DeliveryConfig deliveryConfig, int bufferSize) {
        this.laneId = laneId;
        this.credits = credits;
        this.subscriberClient = subscriberClient;
        this.subscriberUri = subscriberUri;
        this.dlqHandler = dlqHandler;
        this.deliveryConfig = deliveryConfig;
        this.buffer = new ArrayBlockingQueue<>(bufferSize);
        this.retryQueue = new RetryQueue(deliveryConfig);
    }

    /**
     * Enqueue an event for delivery in this lane.
     *
     * @return true if enqueued, false if buffer is full
     */
    public boolean enqueue(Envelope envelope) {
        return buffer.offer(envelope);
    }

    /**
     * Run the delivery loop. Called from the dispatcher's thread pool.
     * This method blocks and should be run in a dedicated thread.
     */
    public void deliveryLoop() {
        while (status != LaneStatus.STOPPED) {
            // Check retries first
            processRetries();

            // Try to acquire a credit
            if (!credits.tryAcquire()) {
                status = LaneStatus.PAUSED;
                waitForCredits();
                status = LaneStatus.ACTIVE;
                continue;
            }

            // Poll for event
            Envelope event;
            try {
                event = buffer.poll(100, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                credits.release();
                break;
            }

            if (event == null) {
                credits.release(); // Return unused credit
                continue;
            }

            // Deliver
            inflightEvents.add(event);
            deliverWithRetry(event);
        }
    }

    private void deliverWithRetry(Envelope event) {
        subscriberClient.deliver(subscriberUri, event).whenComplete((result, error) -> {
            inflightEvents.remove(event);

            if (error != null || !result.success()) {
                handleDeliveryFailure(event, result, error);
            } else {
                // Successful delivery — credit returned
                credits.release();
                log.debug("Lane {} delivered event {}", laneId, event.eventId());
            }
        });
    }

    private void handleDeliveryFailure(Envelope event, DeliveryResult result, Throwable error) {
        if (result != null && result.isNonRetryableClientError()) {
            // 4xx — send to DLQ immediately
            credits.release();
            dlqHandler.sendToDlq(event, "non_retryable_client_error", 1,
                result.error() != null ? result.error() : "HTTP " + result.statusCode());
            log.warn("Lane {} DLQ event {} (4xx): {}", laneId, event.eventId(), result.statusCode());
        } else {
            // Retryable — add to retry queue
            retryQueue.add(event, 1);
            log.debug("Lane {} scheduled retry for event {}", laneId, event.eventId());
        }
    }

    private void processRetries() {
        Envelope retryEvent;
        while ((retryEvent = retryQueue.pollReady()) != null) {
            int attempt = retryQueue.getAttempt(retryEvent.eventId());
            if (attempt >= deliveryConfig.maxAttempts()) {
                // Exhausted retries — DLQ
                dlqHandler.sendToDlq(retryEvent, "retries_exhausted", attempt,
                    "Max retry attempts reached");
                log.warn("Lane {} DLQ event {} after {} attempts", laneId, retryEvent.eventId(), attempt);
            } else {
                // Re-deliver (does NOT consume new credit)
                deliverWithRetry(retryEvent);
            }
        }
    }

    private void waitForCredits() {
        // Spin-wait with short sleep until credits are available
        while (!credits.hasCredits() && status != LaneStatus.STOPPED) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    /** Initiate graceful shutdown — drain inflight events. */
    public void drain() {
        status = LaneStatus.DRAINING;
        while (!inflightEvents.isEmpty()) {
            try { Thread.sleep(100); } catch (InterruptedException e) { break; }
        }
        status = LaneStatus.STOPPED;
    }

    public int getLaneId() { return laneId; }
    public LaneStatus getStatus() { return status; }
    public boolean isActive() { return status == LaneStatus.ACTIVE; }
    public int getAvailableCredits() { return credits.getAvailableCredits(); }
    public int getInflightCount() { return inflightEvents.size(); }
    public int getBufferSize() { return buffer.size(); }
}
