package com.knative.fluss.broker.controller.reconciler;

import com.knative.fluss.broker.api.model.Broker;
import com.knative.fluss.broker.api.model.Trigger;
import com.knative.fluss.broker.api.model.TriggerSpec;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TriggerReconcilerTest {

    @Mock
    private KubernetesClient kubernetesClient;

    @Mock
    private MixedOperation brokerOperation;

    @Mock
    private NonNamespaceOperation brokerNamespaceOp;

    @Mock
    private Resource brokerResource;

    private TriggerReconciler reconciler;

    @BeforeEach
    void setUp() {
        reconciler = new TriggerReconciler(kubernetesClient);
        lenient().when(kubernetesClient.resources(Broker.class)).thenReturn(brokerOperation);
        lenient().when(brokerOperation.inNamespace(anyString())).thenReturn(brokerNamespaceOp);
        lenient().when(brokerNamespaceOp.withName(anyString())).thenReturn(brokerResource);
    }

    @Test
    void reconcile_shouldReturnShortRetryWhenBrokerNotFound() {
        var trigger = createTrigger("default", "my-trigger", "missing-broker");
        when(brokerResource.get()).thenReturn(null);

        Duration result = reconciler.reconcile(trigger);

        assertThat(result).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void reconcile_shouldReturnNormalIntervalWhenBrokerExists() {
        var trigger = createTrigger("default", "my-trigger", "my-broker");
        when(brokerResource.get()).thenReturn(createBroker("default", "my-broker"));

        Duration result = reconciler.reconcile(trigger);

        assertThat(result).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void reconcile_shouldReturnShortRetryOnException() {
        var trigger = createTrigger("default", "error-trigger", "my-broker");
        when(kubernetesClient.resources(Broker.class)).thenThrow(new RuntimeException("K8s unavailable"));

        Duration result = reconciler.reconcile(trigger);

        assertThat(result).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void eventHandler_shouldReconcileOnAdd() {
        var handler = reconciler.eventHandler();
        var trigger = createTrigger("default", "test", "broker");
        when(brokerResource.get()).thenReturn(createBroker("default", "broker"));

        handler.onAdd(trigger);

        verify(kubernetesClient).resources(Broker.class);
    }

    @Test
    void eventHandler_shouldReconcileOnUpdate() {
        var handler = reconciler.eventHandler();
        var oldTrigger = createTrigger("default", "test", "broker");
        var newTrigger = createTrigger("default", "test", "broker");
        when(brokerResource.get()).thenReturn(createBroker("default", "broker"));

        handler.onUpdate(oldTrigger, newTrigger);

        verify(kubernetesClient).resources(Broker.class);
    }

    @Test
    void eventHandler_shouldNotThrowOnDelete() {
        var handler = reconciler.eventHandler();
        var trigger = createTrigger("default", "test", "broker");

        handler.onDelete(trigger, false);

        verifyNoInteractions(kubernetesClient);
    }

    private static Trigger createTrigger(String namespace, String name, String brokerName) {
        var trigger = new Trigger();
        var metadata = new ObjectMeta();
        metadata.setNamespace(namespace);
        metadata.setName(name);
        trigger.setMetadata(metadata);

        var spec = new TriggerSpec();
        spec.setBroker(brokerName);
        trigger.setSpec(spec);
        return trigger;
    }

    private static Broker createBroker(String namespace, String name) {
        var broker = new Broker();
        var metadata = new ObjectMeta();
        metadata.setNamespace(namespace);
        metadata.setName(name);
        broker.setMetadata(metadata);
        return broker;
    }
}
