package com.knative.fluss.broker.dispatcher;

import com.knative.fluss.broker.common.config.DispatcherConfig;
import com.knative.fluss.broker.common.model.Envelope;
import com.knative.fluss.broker.delivery.http.SubscriberClient;
import com.knative.fluss.broker.delivery.tracking.DeliveryTracker;
import com.knative.fluss.broker.dispatcher.backpressure.CreditBucket;
import com.knative.fluss.broker.dispatcher.delivery.DeliveryLane;
import com.knative.fluss.broker.dispatcher.dlq.DlqHandler;
import com.knative.fluss.broker.common.model.EventFilter;
import com.knative.fluss.broker.storage.fluss.client.FlussEventScanner;
import com.knative.fluss.broker.storage.fluss.tables.FlussTablePath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Per-trigger event dispatcher. One instance per active Trigger CRD.
 *
 * <p>Reads events from Fluss, applies trigger filters, and delivers to the
 * subscriber through a pool of delivery lanes with credit-based backpressure.
 */
public class TriggerDispatcher implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(TriggerDispatcher.class);

    private final String triggerKey;
    private final FlussTablePath tablePath;
    private final String subscriberUri;
    private final Map<String, String> filterAttributes;
    private final DispatcherConfig config;

    private final FlussEventScanner scanner;
    private final SubscriberClient subscriberClient;
    private final DlqHandler dlqHandler;
    private final DeliveryTracker tracker;

    private final List<DeliveryLane> lanes;
    private final ExecutorService laneExecutor;
    private final ScheduledExecutorService refillExecutor;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public TriggerDispatcher(String triggerKey, FlussTablePath tablePath,
                             String subscriberUri, Map<String, String> filterAttributes,
                             DispatcherConfig config, FlussEventScanner scanner,
                             SubscriberClient subscriberClient, DlqHandler dlqHandler,
                             DeliveryTracker tracker) {
        this.triggerKey = triggerKey;
        this.tablePath = tablePath;
        this.subscriberUri = subscriberUri;
        this.filterAttributes = filterAttributes;
        this.config = config;
        this.scanner = scanner;
        this.subscriberClient = subscriberClient;
        this.dlqHandler = dlqHandler;
        this.tracker = tracker;

        // Create delivery lanes
        this.lanes = new ArrayList<>();
        for (int i = 0; i < config.maxConcurrency(); i++) {
            CreditBucket bucket = new CreditBucket(
                config.initialCredits(), config.maxCredits(),
                config.creditRefillRate(),
                Duration.ofMillis(config.creditRefillIntervalMs()));
            DeliveryLane lane = new DeliveryLane(
                i, bucket, subscriberClient, subscriberUri,
                dlqHandler, config.delivery(), config.laneBufferSize());
            lanes.add(lane);
        }

        this.laneExecutor = Executors.newFixedThreadPool(config.maxConcurrency(),
            r -> { var t = new Thread(r, "disp-" + triggerKey + "-lane"); t.setDaemon(true); return t; });
        this.refillExecutor = Executors.newSingleThreadScheduledExecutor(
            r -> { var t = new Thread(r, "disp-" + triggerKey + "-refill"); t.setDaemon(true); return t; });
    }

    /** Start the dispatcher — begins reading and delivering events. */
    public void start() {
        if (!running.compareAndSet(false, true)) {
            log.warn("Dispatcher already running for trigger {}", triggerKey);
            return;
        }

        log.info("Starting dispatcher for trigger {} -> {} ({} lanes)",
            triggerKey, subscriberUri, lanes.size());

        // Start delivery lanes
        for (DeliveryLane lane : lanes) {
            laneExecutor.submit(lane::deliveryLoop);
        }

        // Start scan loop
        laneExecutor.submit(this::scanLoop);
    }

    private void scanLoop() {
        int roundRobinIdx = 0;
        while (running.get()) {
            try {
                // Get active lanes
                List<DeliveryLane> activeLanes = lanes.stream()
                    .filter(DeliveryLane::isActive)
                    .toList();

                if (activeLanes.isEmpty()) {
                    Thread.sleep(100);
                    continue;
                }

                // Scan events from Fluss
                List<Envelope> events = scanner.scan(tablePath, triggerKey, 50);
                if (events.isEmpty()) {
                    Thread.sleep(50); // No events — back off
                    continue;
                }

                // Distribute to lanes (round-robin, skip paused)
                int distributed = 0;
                for (Envelope event : events) {
                    // Apply trigger filter
                    if (!EventFilter.matches(event, filterAttributes)) {
                        distributed++;
                        continue; // Skip non-matching events
                    }

                    // Find an active lane (round-robin)
                    boolean enqueued = false;
                    for (int attempt = 0; attempt < activeLanes.size() && !enqueued; attempt++) {
                        DeliveryLane lane = activeLanes.get(roundRobinIdx % activeLanes.size());
                        roundRobinIdx++;
                        if (lane.isActive() && lane.enqueue(event)) {
                            enqueued = true;
                        }
                    }

                    if (!enqueued) {
                        log.debug("All lanes full, will retry in next scan");
                        break;
                    }
                    distributed++;
                }

                // Advance cursor
                scanner.advanceCursor(triggerKey, distributed);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Error in scan loop for trigger {}", triggerKey, e);
                try { Thread.sleep(1000); } catch (InterruptedException ie) { break; }
            }
        }
    }

    /** Graceful shutdown — drain all lanes. */
    @Override
    public void close() {
        if (!running.compareAndSet(true, false)) return;
        log.info("Shutting down dispatcher for trigger {}", triggerKey);

        lanes.forEach(DeliveryLane::drain);
        laneExecutor.shutdown();
        refillExecutor.shutdown();

        try {
            laneExecutor.awaitTermination(30, TimeUnit.SECONDS);
            refillExecutor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            laneExecutor.shutdownNow();
            refillExecutor.shutdownNow();
        }

        subscriberClient.shutdown();
    }

    // Accessors for metrics/observability
    public String getTriggerKey() { return triggerKey; }
    public boolean isRunning() { return running.get(); }
    public List<DeliveryLane> getLanes() { return Collections.unmodifiableList(lanes); }
}
