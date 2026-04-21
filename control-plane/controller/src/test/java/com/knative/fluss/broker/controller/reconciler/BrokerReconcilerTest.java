package com.knative.fluss.broker.controller.reconciler;

import com.knative.fluss.broker.api.model.Broker;
import com.knative.fluss.broker.api.model.BrokerSpec;
import com.knative.fluss.broker.storage.fluss.tables.FlussTableManager;
import com.knative.fluss.broker.storage.fluss.tables.FlussTablePath;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceList;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.AppsAPIGroupDSL;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.dsl.RollableScalableResource;
import io.fabric8.kubernetes.client.dsl.ServiceResource;
import io.fabric8.kubernetes.client.dsl.NamespaceableResource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Duration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BrokerReconcilerTest {

    @Mock
    private KubernetesClient kubernetesClient;

    @Mock
    private FlussTableManager tableManager;

    // ── Deployment mocks (correct fabric8 6.x types) ──
    @Mock
    private AppsAPIGroupDSL apps;

    @Mock
    private MixedOperation<Deployment, DeploymentList, RollableScalableResource<Deployment>> deploymentOps;

    @Mock
    private NonNamespaceOperation<Deployment, DeploymentList, RollableScalableResource<Deployment>> deploymentNamespaceOps;

    @Mock
    private RollableScalableResource<Deployment> deploymentResource;

    // ── Service mocks (correct fabric8 6.x types) ──
    @Mock
    private MixedOperation<Service, ServiceList, ServiceResource<Service>> serviceOps;

    @Mock
    private NonNamespaceOperation<Service, ServiceList, ServiceResource<Service>> serviceNamespaceOps;

    @Mock
    private ServiceResource<Service> serviceResource;

    // ── Broker resource mocking ──
    @Mock
    private NamespaceableResource<Broker> brokerResource;

    private BrokerReconciler reconciler;

    @BeforeEach
    void setUp() {
        reconciler = new BrokerReconciler(kubernetesClient, tableManager);

        // Wire up Deployment mock chain
        when(kubernetesClient.apps()).thenReturn(apps);
        when(apps.deployments()).thenReturn(deploymentOps);
        when(deploymentOps.inNamespace(anyString())).thenReturn(deploymentNamespaceOps);
        when(deploymentNamespaceOps.resource(any(Deployment.class))).thenReturn(deploymentResource);
        when(deploymentResource.createOrReplace()).thenReturn(mock(Deployment.class));

        // Wire up Service mock chain
        when(kubernetesClient.services()).thenReturn(serviceOps);
        when(serviceOps.inNamespace(anyString())).thenReturn(serviceNamespaceOps);
        when(serviceNamespaceOps.resource(any(Service.class))).thenReturn(serviceResource);
        when(serviceResource.createOrReplace()).thenReturn(mock(Service.class));

        // Wire up broker resource — use resource(Broker) overload
        when(kubernetesClient.resource(any(Broker.class))).thenReturn(brokerResource);
        // patchStatus returns the patched resource
        when(brokerResource.patchStatus()).thenReturn(null);
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
    void reconcile_shouldCreateIngressDeployment() throws Exception {
        var broker = createBroker("default", "my-broker");

        reconciler.reconcile(broker);

        ArgumentCaptor<Deployment> deployCaptor = ArgumentCaptor.forClass(Deployment.class);
        verify(deploymentNamespaceOps).resource(deployCaptor.capture());

        Deployment created = deployCaptor.getValue();
        assertThat(created.getMetadata().getName()).isEqualTo("fluss-ingress-my-broker");
        assertThat(created.getMetadata().getNamespace()).isEqualTo("default");
        assertThat(created.getSpec().getReplicas()).isEqualTo(1);
        assertThat(created.getSpec().getTemplate().getSpec().getContainers()).hasSize(1);
        assertThat(created.getSpec().getTemplate().getSpec().getContainers().get(0).getName())
            .isEqualTo("ingress");
        assertThat(created.getSpec().getTemplate().getSpec().getContainers().get(0).getImage())
            .isEqualTo("fluss-broker-ingress:latest");
    }

    @Test
    void reconcile_shouldCreateIngressService() throws Exception {
        var broker = createBroker("default", "my-broker");

        reconciler.reconcile(broker);

        ArgumentCaptor<Service> svcCaptor = ArgumentCaptor.forClass(Service.class);
        verify(serviceNamespaceOps).resource(svcCaptor.capture());

        Service created = svcCaptor.getValue();
        assertThat(created.getMetadata().getName()).isEqualTo("fluss-ingress-my-broker");
        assertThat(created.getMetadata().getNamespace()).isEqualTo("default");
        assertThat(created.getSpec().getType()).isEqualTo("ClusterIP");
        assertThat(created.getSpec().getPorts()).hasSize(1);
        assertThat(created.getSpec().getPorts().get(0).getPort()).isEqualTo(8080);
    }

    @Test
    void reconcile_shouldSetOwnerReferences() throws Exception {
        var broker = createBroker("default", "my-broker");
        broker.getMetadata().setUid("test-uid-123");

        reconciler.reconcile(broker);

        // Check Deployment owner reference
        ArgumentCaptor<Deployment> deployCaptor = ArgumentCaptor.forClass(Deployment.class);
        verify(deploymentNamespaceOps).resource(deployCaptor.capture());
        var deployOwnerRefs = deployCaptor.getValue().getMetadata().getOwnerReferences();
        assertThat(deployOwnerRefs).hasSize(1);
        assertThat(deployOwnerRefs.get(0).getKind()).isEqualTo("Broker");
        assertThat(deployOwnerRefs.get(0).getName()).isEqualTo("my-broker");
        assertThat(deployOwnerRefs.get(0).getUid()).isEqualTo("test-uid-123");
        assertThat(deployOwnerRefs.get(0).getBlockOwnerDeletion()).isTrue();

        // Check Service owner reference
        ArgumentCaptor<Service> svcCaptor = ArgumentCaptor.forClass(Service.class);
        verify(serviceNamespaceOps).resource(svcCaptor.capture());
        var svcOwnerRefs = svcCaptor.getValue().getMetadata().getOwnerReferences();
        assertThat(svcOwnerRefs).hasSize(1);
        assertThat(svcOwnerRefs.get(0).getKind()).isEqualTo("Broker");
        assertThat(svcOwnerRefs.get(0).getUid()).isEqualTo("test-uid-123");
    }

    @Test
    void reconcile_shouldUseIngressReplicasFromSpec() throws Exception {
        var broker = createBroker("default", "my-broker");
        var ingressConfig = new BrokerSpec.IngressConfig(3, null);
        broker.getSpec().setIngress(ingressConfig);

        reconciler.reconcile(broker);

        ArgumentCaptor<Deployment> deployCaptor = ArgumentCaptor.forClass(Deployment.class);
        verify(deploymentNamespaceOps).resource(deployCaptor.capture());
        assertThat(deployCaptor.getValue().getSpec().getReplicas()).isEqualTo(3);
    }

    @Test
    void reconcile_shouldReturnShortRetryOnError() throws Exception {
        var broker = createBroker("default", "fail-broker");
        doThrow(new RuntimeException("Fluss down")).when(tableManager).ensureDatabase(any());

        Duration result = reconciler.reconcile(broker);

        assertThat(result).isEqualTo(Duration.ofSeconds(5));
        // Deployment should NOT be created on error
        verifyNoInteractions(deploymentNamespaceOps);
        verifyNoInteractions(serviceNamespaceOps);
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

        ArgumentCaptor<Deployment> captor = ArgumentCaptor.forClass(Deployment.class);
        verify(deploymentNamespaceOps, times(2)).resource(captor.capture());
        var names = captor.getAllValues().stream()
            .map(d -> d.getMetadata().getName())
            .toList();
        assertThat(names).containsExactlyInAnyOrder(
            "fluss-ingress-broker-a", "fluss-ingress-broker-b");
    }

    @Test
    void reconcile_shouldPatchStatusWithConditions() throws Exception {
        var broker = createBroker("default", "my-broker");

        reconciler.reconcile(broker);

        verify(brokerResource).patchStatus();
        assertThat(broker.getStatus().getConditions()).hasSize(3);
        assertThat(broker.getStatus().getConditions().get(0).getType()).isEqualTo("Ready");
        assertThat(broker.getStatus().getConditions().get(0).getStatus()).isEqualTo("True");
        assertThat(broker.getStatus().getConditions().get(1).getType()).isEqualTo("TableReady");
        assertThat(broker.getStatus().getConditions().get(2).getType()).isEqualTo("IngressReady");
        assertThat(broker.getStatus().getConditions().get(2).getStatus()).isEqualTo("True");
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

        handler.onDelete(broker, false);

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
