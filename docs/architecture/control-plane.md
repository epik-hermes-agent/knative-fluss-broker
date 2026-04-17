# Control Plane Architecture

The control plane manages the lifecycle of Broker and Trigger resources through Kubernetes reconciliation. This document covers the reconciler design, CRD model, and status condition management.

## Reconciler Overview

The control plane uses the Kubernetes controller pattern with the fabric8 Java client. Two primary reconcilers handle Broker and Trigger resources independently.

```
Kubernetes API Server
  |
  +-- Watch Broker CRD -------> BrokerReconciler
  |                                 |
  |                                 v
  |                          Ensure Fluss database
  |                          Ensure Fluss log table
  |                          Ensure ingress service + deployment
  |                          Update Broker status
  |
  +-- Watch Trigger CRD -----> TriggerReconciler
                                   |
                                   v
                            Ensure dispatcher deployment
                            Configure trigger filter
                            Update Trigger status
```

## CRD Model

### Broker CRD

```yaml
apiVersion: eventing.fluss.io/v1alpha1
kind: Broker
metadata:
  name: default
  namespace: my-namespace
spec:
  config:
    fluss:
      cluster:
        endpoint: "fluss://fluss-cluster:9123"
    ingress:
      replicas: 2
      resources:
        requests:
          cpu: "100m"
          memory: "256Mi"
    schema:
      enabled: true
      auto-register: true
    iceberg:
      enabled: false
      # When enabled:
      # catalog-type: "hive"
      # warehouse: "s3a://warehouse/"
      # hive-endpoint: "thrift://hive-metastore:9083"
status:
  conditions:
    - type: Ready
      status: "True"
      reason: AllComponentsReady
    - type: TableReady
      status: "True"
      reason: FlussTableExists
    - type: IngressReady
      status: "True"
      reason: DeploymentAvailable
  addresses:
    - name: http
      url: "http://broker-ingress.my-namespace.svc.cluster.local"
  observedGeneration: 1
```

### Trigger CRD

```yaml
apiVersion: eventing.fluss.io/v1alpha1
kind: Trigger
metadata:
  name: my-trigger
  namespace: my-namespace
spec:
  broker: default
  filter:
    attributes:
      type: "com.example.myevent"
      source: "/myapp/*"
  subscriber:
    uri: "http://my-service.my-namespace.svc.cluster.local/events"
    delivery:
      retry: 5
      backoffPolicy: "exponential"
      backoffDelay: "PT1S"
      deadLetterSink:
        uri: "http://dlq-handler.my-namespace.svc.cluster.local"
status:
  conditions:
    - type: Ready
      status: "True"
      reason: DispatcherReady
    - type: DispatcherReady
      status: "True"
      reason: DeploymentAvailable
    - type: SubscriberResolved
      status: "True"
      reason: EndpointReachable
  observedGeneration: 1
```

### CRD Schema (Java Model)

```java
// Broker
@Group("eventing.fluss.io")
@Version("v1alpha1")
public class Broker extends CustomResource<BrokerSpec, BrokerStatus>
    implements Namespaced {

  @Override
  protected BrokerStatus initStatus() {
    return new BrokerStatus();
  }
}

public class BrokerSpec {
  private FlussConfig fluss;
  private IngressConfig ingress;
  private SchemaConfig schema;
  private IcebergConfig iceberg;
}

// Trigger
@Group("eventing.fluss.io")
@Version("v1alpha1")
public class Trigger extends CustomResource<TriggerSpec, TriggerStatus>
    implements Namespaced {

  @Override
  protected TriggerStatus initStatus() {
    return new TriggerStatus();
  }
}

public class TriggerSpec {
  private String broker;
  private TriggerFilter filter;
  private SubscriberSpec subscriber;
  private DeliverySpec delivery;
}
```

## BrokerReconciler

### Reconciliation Flow

```
BrokerReconciler.reconcile(Broker broker):
  |
  1. Validate spec
  |    - fluss.endpoint is set
  |    - config is valid
  |
  2. Ensure Fluss database
  |    - database name: "knative_{namespace}"
  |    - CREATE DATABASE IF NOT EXISTS
  |
  3. Ensure Fluss log table
  |    - table name: "broker_{brokerName}"
  |    - Schema from schema-model.md
  |    - Partitioned by ingestion_date
  |
  4. Ensure ingress Deployment + Service
  |    - Namespace: broker.metadata.namespace
  |    - Replicas: broker.spec.config.ingress.replicas
  |    - Configure Fluss endpoint as env var
  |    - Configure schema registry setting
  |    - Create or update Kubernetes Deployment
  |    - Create or update Kubernetes Service
  |
  5. [If iceberg.enabled]
  |    - Ensure Iceberg tiering CronJob
  |    - Configure Hive Metastore connection
  |    - Configure S3 credentials
  |
  6. Update Broker status
  |    - Set condition Ready=True when all components are healthy
  |    - Set address to ingress service URL
  |    - Update observedGeneration
  |
  7. Return reconciliation result
        - requeueAfter: 30s (periodic health check)
        - requeue on error with exponential backoff
```

### Error Handling

| Error | Action | Status Update |
|-------|--------|---------------|
| Fluss unreachable | Retry with backoff (1s, 2s, 4s, ...) | TableReady=False |
| Fluss table creation fails | Log error, requeue | TableReady=False |
| Kubernetes deployment fails | Log error, requeue | IngressReady=False |
| Schema registry error | Log warning, continue | No condition (warning only) |

## TriggerReconciler

### Reconciliation Flow

```
TriggerReconciler.reconcile(Trigger trigger):
  |
  1. Validate spec
  |    - broker reference exists
  |    - subscriber.uri is valid
  |    - filter attributes are valid
  |
  2. Verify Broker is Ready
  |    - Get referenced Broker CRD
  |    - Check status.conditions for Ready=True
  |    - If not ready: requeue with backoff
  |
  3. Ensure dispatcher Deployment
  |    - One dispatcher per trigger
  |    - Configure:
  |      - Fluss table path (from Broker status)
  |      - Subscriber URI
  |      - Filter expression
  |      - Delivery parameters (retry, backoff, DLQ)
  |      - Concurrency and credit settings
  |    - Create or update Kubernetes Deployment
  |
  4. Verify subscriber reachability
  |    - HTTP OPTIONS or HEAD to subscriber.uri
  |    - 5 minute timeout before marking SubscriberResolved=False
  |
  5. Update Trigger status
  |    - Set condition Ready=True when dispatcher is healthy
  |    - Set DispatcherReady based on deployment status
  |    - Set SubscriberResolved based on connectivity check
  |    - Update observedGeneration
  |
  6. Return reconciliation result
        - requeueAfter: 30s
        - requeue on error
```

### Trigger Filter Evaluation

Filters are evaluated in the dispatcher, not the reconciler. The reconciler passes the filter expression as configuration, and the dispatcher applies it when scanning events:

```yaml
filter:
  attributes:
    type: "com.example.order.created"
    source: "/orders/*"
    # ext_ prefix for CloudEvent extensions
    ext_region: "us-west-2"
```

Filter logic:
- All attributes must match (AND logic)
- Attribute values support exact match and prefix wildcards (`*`)
- The `data` field is not filterable in v1 (only attributes)

## Status Conditions

### Condition Types

#### Broker Conditions

| Type | Reason | Description |
|------|--------|-------------|
| Ready | AllComponentsReady | All sub-conditions are True |
| TableReady | FlussTableExists | Fluss log table exists with correct schema |
| IngressReady | DeploymentAvailable | Ingress deployment has available replicas |
| SchemaReady | RegistryInitialized | Schema registry is operational (if enabled) |
| TieringReady | TieringJobActive | Iceberg tiering job is running (if enabled) |

#### Trigger Conditions

| Type | Reason | Description |
|------|--------|-------------|
| Ready | DispatcherReady | All sub-conditions are True |
| DispatcherReady | DeploymentAvailable | Dispatcher deployment has available replicas |
| SubscriberResolved | EndpointReachable | Subscriber endpoint is reachable |
| DeliveryReady | ConfigValid | Delivery configuration is valid |

### Condition Transition Rules

```
Initial state: all conditions unknown
  |
  v
Reconciler runs
  |
  +-- All checks pass --> Ready=True
  |
  +-- Fluss unreachable --> TableReady=False, Ready=False
  |
  +-- Deployment not ready --> IngressReady=False, Ready=False
  |
  +-- Subscriber unreachable --> SubscriberResolved=False
  |     (does NOT block Ready in v1 - dispatcher will retry connection)
  |
  +-- Error during reconcile --> Ready=False
       (existing conditions retain their previous state)
```

### Status Updates

Status updates are performed using optimistic locking (resourceVersion). If a status update conflict occurs (another reconciler run modified the status), the reconciler retries from step 1.

```java
// Status update pattern
var status = broker.getStatus();
status.getConditions().removeIf(c -> c.getType().equals("Ready"));
status.getConditions().add(new ConditionBuilder()
    .withType("Ready")
    .withStatus("True")
    .withReason("AllComponentsReady")
    .withMessage("All broker components are healthy")
    .build());
kubernetesClient.resource(broker).updateStatus();
```

## RBAC

The reconciler service account requires:

```yaml
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRole
metadata:
  name: fluss-broker-controller
rules:
  # Watch own CRDs
  - apiGroups: ["eventing.fluss.io"]
    resources: ["brokers", "triggers"]
    verbs: ["get", "list", "watch", "update", "patch"]
  - apiGroups: ["eventing.fluss.io"]
    resources: ["brokers/status", "triggers/status"]
    verbs: ["get", "update", "patch"]
  # Manage owned resources
  - apiGroups: ["apps"]
    resources: ["deployments"]
    verbs: ["get", "list", "watch", "create", "update", "patch", "delete"]
  - apiGroups: [""]
    resources: ["services", "configmaps", "secrets"]
    verbs: ["get", "list", "watch", "create", "update", "patch", "delete"]
  - apiGroups: ["batch"]
    resources: ["cronjobs", "jobs"]
    verbs: ["get", "list", "watch", "create", "update", "patch", "delete"]
```

## Future Considerations

### Horizontal Dispatcher Scaling (v2)

For high-throughput triggers, the dispatcher could be sharded by Fluss partition:
- Each shard reads from one or more Fluss partitions
- Shards coordinate via a distributed offset tracker
- Reconciler manages shard count based on throughput metrics

### Multi-Tenant Isolation (v2)

- Separate Fluss databases per tenant
- Namespace-scoped RBAC for trigger management
- Resource quotas per broker instance
- Network policies between tenant dispatchers
