package com.knative.fluss.broker.controller.reconciler;

import com.knative.fluss.broker.api.model.Broker;
import com.knative.fluss.broker.api.model.BrokerSpec;
import com.knative.fluss.broker.storage.fluss.tables.FlussTableManager;
import com.knative.fluss.broker.storage.fluss.tables.FlussTablePath;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Kubernetes reconciler for Broker CRDs.
 * Watches Broker resources and ensures the corresponding Fluss infrastructure exists.
 *
 * <p>Reconciliation flow:
 * <ol>
 *   <li>Validate spec</li>
 *   <li>Ensure Fluss database</li>
 *   <li>Ensure Fluss log table</li>
 *   <li>Ensure ingress Deployment + Service</li>
 *   <li>Update Broker status conditions</li>
 * </ol>
 */
public class BrokerReconciler {

    private static final Logger log = LoggerFactory.getLogger(BrokerReconciler.class);

    /** Default ingress container image when not specified by annotation. */
    static final String DEFAULT_INGRESS_IMAGE = "fluss-broker-ingress:latest";

    /** Label key for ingress components. */
    static final String LABEL_APP = "app";

    /** Label value for ingress components. */
    static final String LABEL_VALUE_INGRESS = "fluss-ingress";

    private final KubernetesClient kubernetesClient;
    private final FlussTableManager tableManager;

    public BrokerReconciler(KubernetesClient kubernetesClient, FlussTableManager tableManager) {
        this.kubernetesClient = kubernetesClient;
        this.tableManager = tableManager;
    }

    /**
     * Reconcile a Broker resource.
     *
     * @param broker the Broker to reconcile
     * @return requeue interval (30s for periodic health check)
     */
    public Duration reconcile(Broker broker) {
        String namespace = broker.getMetadata().getNamespace();
        String name = broker.getMetadata().getName();
        log.info("Reconciling Broker {}/{}", namespace, name);

        try {
            // Step 1: Ensure Fluss database (matches FlussTablePath convention: knative_{namespace})
            FlussTablePath tablePath = FlussTablePath.brokerTable(namespace, name);
            tableManager.ensureDatabase(tablePath.database());

            // Step 2: Ensure Fluss log table
            tableManager.ensureBrokerTable(tablePath);

            // Step 3: Ensure ingress deployment + service
            ensureIngressDeployment(broker);

            // Step 4: Update status
            updateBrokerStatus(broker, true, "AllComponentsReady");

            return Duration.ofSeconds(30);

        } catch (Exception e) {
            log.error("Error reconciling Broker {}/{}", namespace, name, e);
            updateBrokerStatus(broker, false, "ReconcileError");
            return Duration.ofSeconds(5); // Retry sooner on error
        }
    }

    // ─────────────────────────────────────────────
    // Step 3: Ingress Deployment + Service
    // ─────────────────────────────────────────────

    /**
     * Ensure the ingress Deployment and Service exist for this Broker.
     * Uses owner references so K8s garbage-collects when the Broker CRD is deleted.
     */
    void ensureIngressDeployment(Broker broker) {
        String namespace = broker.getMetadata().getNamespace();
        String name = broker.getMetadata().getName();
        String deploymentName = "fluss-ingress-" + name;
        String serviceName = "fluss-ingress-" + name;

        int replicas = getIngressReplicas(broker);
        String image = getIngressImage(broker);
        String flussEndpoint = getFlussEndpoint(broker);

        log.info("Ensuring ingress for Broker {}/{}: deployment={}, replicas={}, image={}",
            namespace, name, deploymentName, replicas, image);

        // Build owner reference pointing to the Broker CRD
        OwnerReference ownerRef = new OwnerReferenceBuilder()
            .withApiVersion("eventing.fluss.io/v1alpha1")
            .withKind("Broker")
            .withName(name)
            .withUid(broker.getMetadata().getUid())
            .withBlockOwnerDeletion(true)
            .build();

        // ── Deployment ──
        Deployment deployment = new DeploymentBuilder()
            .withNewMetadata()
                .withName(deploymentName)
                .withNamespace(namespace)
                .withLabels(Map.of(
                    LABEL_APP, LABEL_VALUE_INGRESS,
                    "broker", name,
                    "managed-by", "fluss-broker-controller"
                ))
                .withOwnerReferences(ownerRef)
            .endMetadata()
            .withNewSpec()
                .withReplicas(replicas)
                .withNewSelector()
                    .addToMatchLabels(LABEL_APP, LABEL_VALUE_INGRESS)
                    .addToMatchLabels("broker", name)
                .endSelector()
                .withNewTemplate()
                    .withNewMetadata()
                        .addToLabels(LABEL_APP, LABEL_VALUE_INGRESS)
                        .addToLabels("broker", name)
                    .endMetadata()
                    .withNewSpec()
                        .addNewContainer()
                            .withName("ingress")
                            .withImage(image)
                            .withPorts(
                                new ContainerPortBuilder()
                                    .withName("http")
                                    .withContainerPort(8080)
                                    .withProtocol("TCP")
                                    .build()
                            )
                            .withEnv(
                                new EnvVarBuilder()
                                    .withName("FLUSS_HOST")
                                    .withValue(flussEndpoint)
                                    .build(),
                                new EnvVarBuilder()
                                    .withName("FLUSS_PORT")
                                    .withValue("9123")
                                    .build(),
                                new EnvVarBuilder()
                                    .withName("SERVER_PORT")
                                    .withValue("8080")
                                    .build()
                            )
                            .withNewResources()
                                .addToRequests("cpu",
                                    getResourceQuantity(broker, "cpu", "100m"))
                                .addToRequests("memory",
                                    getResourceQuantity(broker, "memory", "256Mi"))
                            .endResources()
                            .withNewLivenessProbe()
                                .withNewHttpGet()
                                    .withPath("/health")
                                    .withNewPort(8080)
                                .endHttpGet()
                                .withInitialDelaySeconds(5)
                                .withPeriodSeconds(10)
                            .endLivenessProbe()
                            .withNewReadinessProbe()
                                .withNewHttpGet()
                                    .withPath("/health")
                                    .withNewPort(8080)
                                .endHttpGet()
                                .withInitialDelaySeconds(3)
                                .withPeriodSeconds(5)
                            .endReadinessProbe()
                        .endContainer()
                    .endSpec()
                .endTemplate()
            .endSpec()
            .build();

        kubernetesClient.apps().deployments()
            .inNamespace(namespace)
            .resource(deployment)
            .createOrReplace();

        log.info("Ingress Deployment ensured: {}/{}", namespace, deploymentName);

        // ── Service ──
        Service service = new ServiceBuilder()
            .withNewMetadata()
                .withName(serviceName)
                .withNamespace(namespace)
                .withLabels(Map.of(
                    LABEL_APP, LABEL_VALUE_INGRESS,
                    "broker", name,
                    "managed-by", "fluss-broker-controller"
                ))
                .withOwnerReferences(ownerRef)
            .endMetadata()
            .withNewSpec()
                .withType("ClusterIP")
                .addToSelector(LABEL_APP, LABEL_VALUE_INGRESS)
                .addToSelector("broker", name)
                .addNewPort()
                    .withName("http")
                    .withPort(8080)
                    .withTargetPort(new IntOrString(8080))
                    .withProtocol("TCP")
                .endPort()
            .endSpec()
            .build();

        kubernetesClient.services()
            .inNamespace(namespace)
            .resource(service)
            .createOrReplace();

        log.info("Ingress Service ensured: {}/{}", namespace, serviceName);
    }

    // ─────────────────────────────────────────────
    // Step 4: Status update
    // ─────────────────────────────────────────────

    /**
     * Update the Broker status with conditions and addresses.
     */
    private void updateBrokerStatus(Broker broker, boolean ready, String reason) {
        String namespace = broker.getMetadata().getNamespace();
        String name = broker.getMetadata().getName();

        log.debug("Updating Broker {}/{} status: ready={} reason={}", namespace, name, ready, reason);

        try {
            // Build conditions
            Condition readyCondition = new Condition();
            readyCondition.setType("Ready");
            readyCondition.setStatus(ready ? "True" : "False");
            readyCondition.setReason(reason);
            readyCondition.setMessage(ready
                ? "All broker components are ready"
                : "Broker reconciliation encountered an error");
            readyCondition.setLastTransitionTime(java.time.Instant.now().toString());

            Condition tableCondition = new Condition();
            tableCondition.setType("TableReady");
            tableCondition.setStatus("True");
            tableCondition.setReason("TableEnsured");
            tableCondition.setMessage("Fluss log table exists");
            tableCondition.setLastTransitionTime(java.time.Instant.now().toString());

            Condition ingressCondition = new Condition();
            ingressCondition.setType("IngressReady");
            ingressCondition.setStatus(ready ? "True" : "False");
            ingressCondition.setReason(ready ? "DeploymentReady" : "DeploymentNotReady");
            ingressCondition.setMessage(ready
                ? "Ingress deployment is running"
                : "Ingress deployment is not ready");
            ingressCondition.setLastTransitionTime(java.time.Instant.now().toString());

            broker.getStatus().setConditions(List.of(readyCondition, tableCondition, ingressCondition));

            // Build address
            String ingressAddress = "http://fluss-ingress-" + name + "." + namespace + ".svc";
            BrokerSpec.IngressConfig ingressConfig = broker.getSpec().getIngress();
            broker.getStatus().setAddresses(List.of(
                new com.knative.fluss.broker.api.model.BrokerStatus.Address("http", ingressAddress)
            ));

            broker.getStatus().setObservedGeneration(broker.getMetadata().getGeneration());

            // Patch status — the broker object already has the status set above,
            // so we pass it directly to patchStatus() which sends only the status subresource
            kubernetesClient.resource(broker).patchStatus();

            log.info("Broker {}/{} status updated: ready={} ingress={}",
                namespace, name, ready, ingressAddress);

        } catch (Exception e) {
            log.warn("Failed to update Broker {}/{} status: {}", namespace, name, e.getMessage());
            // Non-fatal — the reconciler will retry on next reconciliation cycle
        }
    }

    // ─────────────────────────────────────────────
    // Config helpers
    // ─────────────────────────────────────────────

    private int getIngressReplicas(Broker broker) {
        BrokerSpec spec = broker.getSpec();
        if (spec != null && spec.getIngress() != null && spec.getIngress().replicas() > 0) {
            return spec.getIngress().replicas();
        }
        return 1;
    }

    private String getIngressImage(Broker broker) {
        // Check for annotation override
        Map<String, String> annotations = broker.getMetadata().getAnnotations();
        if (annotations != null && annotations.containsKey("fluss.io/ingress-image")) {
            return annotations.get("fluss.io/ingress-image");
        }
        return DEFAULT_INGRESS_IMAGE;
    }

    private String getFlussEndpoint(Broker broker) {
        BrokerSpec spec = broker.getSpec();
        if (spec != null && spec.getFluss() != null && spec.getFluss().endpoint() != null) {
            String endpoint = spec.getFluss().endpoint();
            // Strip fluss:// scheme if present
            if (endpoint.startsWith("fluss://")) {
                endpoint = endpoint.substring("fluss://".length());
            }
            // Extract just the host (before the port)
            return endpoint.split(":")[0];
        }
        return "fluss-coordinator";
    }

    private Quantity getResourceQuantity(Broker broker, String resource, String defaultValue) {
        BrokerSpec spec = broker.getSpec();
        if (spec != null && spec.getIngress() != null && spec.getIngress().resources() != null) {
            BrokerSpec.ResourceConfig resources = spec.getIngress().resources();
            String value = switch (resource) {
                case "cpu" -> resources.cpu();
                case "memory" -> resources.memory();
                default -> null;
            };
            if (value != null && !value.isBlank()) {
                return new Quantity(value);
            }
        }
        return new Quantity(defaultValue);
    }

    /** Create a ResourceEventHandler for watching Broker CRD changes. */
    public ResourceEventHandler<Broker> eventHandler() {
        return new ResourceEventHandler<>() {
            @Override public void onAdd(Broker broker) { reconcile(broker); }
            @Override public void onUpdate(Broker oldBroker, Broker newBroker) { reconcile(newBroker); }
            @Override public void onDelete(Broker broker, boolean deletedFinalStateUnknown) {
                log.info("Broker deleted: {}/{}", broker.getMetadata().getNamespace(), broker.getMetadata().getName());
            }
        };
    }
}
