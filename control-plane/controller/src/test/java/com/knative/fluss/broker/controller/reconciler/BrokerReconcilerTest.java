package com.knative.fluss.broker.controller.reconciler;

import com.knative.fluss.broker.api.model.Broker;
import com.knative.fluss.broker.api.model.BrokerSpec;
import com.knative.fluss.broker.storage.fluss.tables.FlussTableManager;
import com.knative.fluss.broker.storage.fluss.tables.FlussTablePath;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BrokerReconcilerTest {

    @Mock
    private KubernetesClient kubernetesClient;

    @Mock
    private FlussTableManager tableManager;

    private BrokerReconciler reconciler;

    @BeforeEach
    void setUp() {
        reconciler = new BrokerReconciler(kubernetesClient, tableManager);
    }

    @Test
    void reconcile_shouldEnsureDatabaseAndTable() throws Exception {
        var broker = createBroker("default", "test-broker");

        Duration result = reconciler.reconcile(broker);

        assertThat(result).isEqualTo(Duration.ofSeconds(30));
        verify(tableManager).ensureDatabase("knative_default");
        var pathCaptor = ArgumentCaptor.forClass(FlussTablePath.class);
        verify(tableManager).ensureBrokerTable(pathCaptor.capture());
        assertThat(pathCaptor.getValue().database()).isEqualTo("knative_default");
        assertThat(pathCaptor.getValue().table()).isEqualTo("broker_test_broker");
    }

    @Test
    void reconcile_shouldReturnShortRetryOnError() throws Exception {
        var broker = createBroker("default", "fail-broker");
        doThrow(new RuntimeException("Fluss down")).when(tableManager).ensureDatabase(any());

        Duration result = reconciler.reconcile(broker);

        assertThat(result).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void reconcile_shouldUseNamespaceAsDatabase() throws Exception {
        var broker = createBroker("payments", "orders");

        reconciler.reconcile(broker);

        verify(tableManager).ensureDatabase("knative_payments");
        var pathCaptor = ArgumentCaptor.forClass(FlussTablePath.class);
        verify(tableManager).ensureBrokerTable(pathCaptor.capture());
        assertThat(pathCaptor.getValue().database()).isEqualTo("knative_payments");
    }

    @Test
    void reconcile_shouldHandleMultipleBrokers() throws Exception {
        var broker1 = createBroker("default", "broker-a");
        var broker2 = createBroker("default", "broker-b");

        reconciler.reconcile(broker1);
        reconciler.reconcile(broker2);

        verify(tableManager, times(2)).ensureDatabase("knative_default");
    }

    @Test
    void eventHandler_shouldReconcileOnAdd() {
        var handler = reconciler.eventHandler();
        var broker = createBroker("default", "test");
        doNothing().when(tableManager).ensureDatabase(any());
        doNothing().when(tableManager).ensureBrokerTable(any());

        handler.onAdd(broker);

        verify(tableManager).ensureDatabase("knative_default");
    }

    @Test
    void eventHandler_shouldReconcileOnUpdate() {
        var handler = reconciler.eventHandler();
        var oldBroker = createBroker("default", "test");
        var newBroker = createBroker("default", "test");
        doNothing().when(tableManager).ensureDatabase(any());
        doNothing().when(tableManager).ensureBrokerTable(any());

        handler.onUpdate(oldBroker, newBroker);

        verify(tableManager).ensureDatabase("knative_default");
    }

    @Test
    void eventHandler_shouldLogOnDelete() {
        var handler = reconciler.eventHandler();
        var broker = createBroker("default", "test");

        // Should not throw
        handler.onDelete(broker, false);

        // No interaction with tableManager for deletes
        verifyNoInteractions(tableManager);
    }

    private static Broker createBroker(String namespace, String name) {
        var broker = new Broker();
        var metadata = new ObjectMeta();
        metadata.setNamespace(namespace);
        metadata.setName(name);
        broker.setMetadata(metadata);
        broker.setSpec(new BrokerSpec());
        return broker;
    }
}
