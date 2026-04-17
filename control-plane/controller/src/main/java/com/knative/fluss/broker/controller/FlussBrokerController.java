package com.knative.fluss.broker.controller;

import com.knative.fluss.broker.api.model.Broker;
import com.knative.fluss.broker.api.model.Trigger;
import com.knative.fluss.broker.common.config.FlussConfig;
import com.knative.fluss.broker.controller.reconciler.BrokerReconciler;
import com.knative.fluss.broker.controller.reconciler.TriggerReconciler;
import com.knative.fluss.broker.storage.fluss.client.FlussConnectionManager;
import com.knative.fluss.broker.storage.fluss.tables.FlussTableManager;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.informers.SharedInformerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Main entry point for the Fluss Broker controller.
 * Watches Broker and Trigger CRDs and reconciles them.
 *
 * <p>Deployment: runs as a Deployment in the same namespace as the CRDs.
 * Requires RBAC: get/list/watch on brokers, triggers, and pods.
 */
public class FlussBrokerController {

    private static final Logger log = LoggerFactory.getLogger(FlussBrokerController.class);

    public static void main(String[] args) throws Exception {
        log.info("Starting Fluss Broker Controller");

        // --- Fluss connection ---
        String flussEndpoint = System.getenv().getOrDefault("FLUSS_ENDPOINT", "fluss://fluss-release:9123");
        FlussConfig flussConfig = new FlussConfig(
            flussEndpoint,   // endpoint
            100,             // writeBatchSize
            50,              // writeBatchTimeoutMs
            10000,           // ackTimeoutMs
            3,               // writeMaxRetries
            100              // writeRetryBackoffMs
        );
        FlussConnectionManager connectionManager = new FlussConnectionManager(flussConfig);
        FlussTableManager tableManager = new FlussTableManager(connectionManager);

        log.info("Fluss connection configured: {}", flussEndpoint);

        // --- Kubernetes client ---
        KubernetesClient kubernetesClient = new KubernetesClientBuilder().build();
        String namespace = System.getenv().getOrDefault("CONTROLLER_NAMESPACE", "default");

        log.info("Kubernetes client created, namespace: {}", namespace);

        // --- Reconcilers ---
        BrokerReconciler brokerReconciler = new BrokerReconciler(kubernetesClient, tableManager);
        TriggerReconciler triggerReconciler = new TriggerReconciler(kubernetesClient);

        // --- Informer-based event handling ---
        // In production, fabric8 SharedInformerFactory would be used.
        // For now, we use a polling-based reconciler loop.
        ScheduledExecutorService executor = Executors.newScheduledThreadPool(2);

        // --- Reconciler loop ---
        // Periodically reconcile all Brokers and Triggers
        Runnable reconcileLoop = () -> {
            try {
                // Reconcile all Brokers
                kubernetesClient.resources(Broker.class)
                    .inNamespace(namespace)
                    .list()
                    .getItems()
                    .forEach(brokerReconciler::reconcile);

                // Reconcile all Triggers
                kubernetesClient.resources(Trigger.class)
                    .inNamespace(namespace)
                    .list()
                    .getItems()
                    .forEach(triggerReconciler::reconcile);
            } catch (Exception e) {
                log.error("Error in reconciliation loop", e);
            }
        };

        executor.scheduleWithFixedDelay(reconcileLoop, 0, 10, TimeUnit.SECONDS);
        log.info("Reconciliation loop started (every 10s)");

        // --- Shutdown hook ---
        CountDownLatch latch = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down Fluss Broker Controller");
            executor.shutdown();
            try {
                executor.awaitTermination(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            connectionManager.close();
            kubernetesClient.close();
            latch.countDown();
        }));

        log.info("Fluss Broker Controller started successfully");
        latch.await();
    }
}
