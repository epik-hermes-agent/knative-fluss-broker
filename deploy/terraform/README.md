# Knative Fluss Broker — EKS Deployment (POC)

Manual for deploying the Knative Fluss Broker to AWS EKS.

## What This Creates

```
AWS Account
├── VPC (10.0.0.0/16)
│   ├── 2 public subnets (2 AZs)
│   └── Internet Gateway
├── EKS Cluster (1.29)
│   ├── OIDC Provider (for pod IRSA)
│   ├── EKS Access Entry (admin access for deployer)
│   └── 3x t3.large nodes (spot by default)
├── S3 Buckets
│   ├── fluss-broker-fluss-<acct>       (tiered storage)
│   └── fluss-broker-iceberg-<acct>     (warehouse)
└── IAM
    ├── EKS cluster role
    ├── EKS node role
    └── Fluss S3 role (IRSA via OIDC)
```

After Terraform, you manually install:
- ZooKeeper + Fluss (Helm)
- Knative Eventing (kubectl)
- CRDs + Controller (kubectl)

## Prerequisites

| Tool | Version | Install |
|------|---------|---------|
| AWS CLI | v2 | `brew install awscli` |
| Terraform | >= 1.3 | `brew install terraform` |
| kubectl | latest | `brew install kubectl` |
| helm | v3 | `brew install helm` |
| Docker | latest | For building controller image |

AWS CLI must be configured: `aws configure`

## Deployment Steps

### Step 1: Configure

```bash
cd deploy/terraform
cp terraform.tfvars.example terraform.tfvars
# Edit terraform.tfvars — at minimum set aws_region
```

### Step 2: Deploy AWS Infrastructure

```bash
terraform init
terraform plan
terraform apply     # ~15 min — creates VPC, EKS, nodes, S3, IAM, OIDC
```

### Step 3: Configure kubectl

```bash
aws eks update-kubeconfig --name fluss-broker-poc --region eu-west-1
kubectl get nodes   # should show 3 nodes Ready
```

### Step 4: Install Knative Eventing

```bash
KNATIVE_VERSION="knative-v1.16.0"

kubectl apply -f "https://github.com/knative/eventing/releases/download/${KNATIVE_VERSION}/eventing-crds.yaml"
kubectl apply -f "https://github.com/knative/eventing/releases/download/${KNATIVE_VERSION}/eventing-core.yaml"

kubectl wait --for=condition=Ready pods --all -n knative-eventing --timeout=300s
```

### Step 5: Install ZooKeeper

```bash
helm repo add bitnami https://charts.bitnami.com/bitnami
helm repo update

helm install zk bitnami/zookeeper \
  --namespace fluss --create-namespace \
  --set replicaCount=3 \
  --set auth.enabled=false \
  --set persistence.enabled=false \
  --set resources.requests.cpu=250m \
  --set resources.requests.memory=512Mi
```

Wait for ZK:

```bash
kubectl wait --for=condition=Ready pods -l app.kubernetes.io/name=zookeeper -n fluss --timeout=300s
```

### Step 6: Install Fluss

Download the Fluss Helm chart. For 1.0-SNAPSHOT, build from source or use a local chart:

```bash
# Option A: Build helm chart from source (if you have the Fluss repo)
cd /path/to/fluss && ./mvnw clean package -DskipTests -Phelm
# Chart will be in fluss-dist/target/fluss-helm-chart-1.0-SNAPSHOT.tgz

# Option B: Download a release chart (if available)
# Check https://fluss.apache.org/ for the latest release chart URL
curl -L -o /tmp/fluss-helm-chart.tgz \
  "https://downloads.apache.org/incubator/fluss/fluss-helm-chart-1.0-SNAPSHOT/fluss-helm-chart-1.0-SNAPSHOT.tgz"

# Extract to verify it's valid
tar tzf /tmp/fluss-helm-chart.tgz | head -5
```

Install with IRSA annotation and S3 config:

```bash
# Pull values from Terraform outputs
FLUSS_S3_ROLE=$(terraform output -raw fluss_s3_role_arn)
S3_BUCKET=$(terraform output -raw s3_bucket_fluss)
AWS_REGION=$(terraform output -raw aws_region)

helm install fluss /tmp/fluss-helm-chart.tgz \
  --namespace fluss \
  --set image.repository=apache/fluss \
  --set image.tag=1.0-SNAPSHOT \
  --set coordinator.replicas=1 \
  --set coordinator.storage.enabled=false \
  --set tablet.replicas=3 \
  --set tablet.storage.enabled=false \
  --set serviceAccount.create=true \
  --set serviceAccount.name=fluss \
  --set serviceAccount.annotations."eks\.amazonaws\.com/role-arn"="${FLUSS_S3_ROLE}" \
  --set configurationOverrides."zookeeper\.address"="zk-zookeeper.fluss.svc.cluster.local:2181" \
  --set configurationOverrides."zookeeper\.path\.root"="/fluss" \
  --set configurationOverrides."default\.bucket\.number"="3" \
  --set configurationOverrides."default\.replication\.factor"="3" \
  --set configurationOverrides."remote\.data\.dir"="s3://${S3_BUCKET}/remote" \
  --set configurationOverrides."s3\.region"="${AWS_REGION}" \
  --set configurationOverrides."s3\.path\.style\.access"="true"
```

Wait for Fluss:

```bash
kubectl wait --for=condition=Ready pods -l app=fluss -n fluss --timeout=300s
```

### Step 7: (Optional) Enable Iceberg Tiering on EKS

For native Iceberg tiering, you need Polaris and Fluss plugins. The local
`docker-compose --profile lakehouse` has a working reference — this section
translates it to EKS.

```bash
# 1. Deploy Polaris (Iceberg REST catalog)
kubectl apply -n fluss -f - <<EOF
apiVersion: apps/v1
kind: Deployment
metadata:
  name: polaris
spec:
  replicas: 1
  selector:
    matchLabels:
      app: polaris
  template:
    metadata:
      labels:
        app: polaris
    spec:
      containers:
        - name: polaris
          image: apache/polaris:1.3.0-incubating
          ports:
            - containerPort: 8181
          env:
            - name: POLARIS_BOOTSTRAP_CREDENTIALS
              value: "default-realm,root,s3cr3t"
---
apiVersion: v1
kind: Service
metadata:
  name: polaris
spec:
  selector:
    app: polaris
  ports:
    - port: 8181
EOF

# 2. Ensure Fluss image has plugins (fluss-fs-s3 + fluss-lake-iceberg)
#    The 1.0-SNAPSHOT image bundles these in /opt/fluss/plugins/.

# 3. Add datalake config to Fluss Helm values:
#    datalake.enabled=true
#    datalake.storage.type=iceberg
#    datalake.iceberg.catalog.type=rest
#    datalake.iceberg.catalog.uri=http://polaris.fluss.svc.cluster.local:8181/api/catalog
#    datalake.iceberg.catalog.warehouse=s3://<iceberg-bucket>/warehouse
#    datalake.iceberg.catalog.credential=default-realm:root:s3cr3t
#    datalake.iceberg.catalog.s3.region=<region>
#    s3.assumed.role.arn=<fluss-s3-role-arn>

# 4. Tables opt in with: table.datalake.enabled = 'true'
```

### Step 8: Build & Push Controller Image

```bash
# Create ECR repo
aws ecr create-repository --repository-name fluss-broker --region eu-west-1

# Login
ACCOUNT=$(aws sts get-caller-identity --query Account --output text)
REGION=eu-west-1
ECR="${ACCOUNT}.dkr.ecr.${REGION}.amazonaws.com"

aws ecr get-login-password --region ${REGION} | \
  docker login --username AWS --password-stdin ${ECR}

# Build + push (from project root)
docker build -f docker/Dockerfile.controller -t ${ECR}/fluss-broker:latest .
docker push ${ECR}/fluss-broker:latest
```

### Step 9: Deploy CRDs + Controller

From the project root:

```bash
# Namespace + RBAC + CRDs
kubectl apply -f config/manifests/namespace.yaml
kubectl apply -f config/manifests/service-account.yaml
kubectl apply -f config/manifests/cluster-role.yaml
kubectl apply -f config/manifests/cluster-role-binding.yaml
kubectl apply -f config/crd/broker-crd.yaml
kubectl apply -f config/crd/trigger-crd.yaml

# Controller (update image)
kubectl apply -f - <<EOF
apiVersion: apps/v1
kind: Deployment
metadata:
  name: fluss-broker-controller
  namespace: fluss-broker
  labels:
    app.kubernetes.io/name: fluss-broker
    app.kubernetes.io/component: controller
spec:
  replicas: 1
  selector:
    matchLabels:
      app.kubernetes.io/name: fluss-broker
      app.kubernetes.io/component: controller
  template:
    metadata:
      labels:
        app.kubernetes.io/name: fluss-broker
        app.kubernetes.io/component: controller
    spec:
      serviceAccountName: fluss-broker-controller
      containers:
        - name: controller
          image: ${ECR}/fluss-broker:latest
          env:
            - name: FLUSS_ENDPOINT
              value: "fluss://fluss-coordinator.fluss.svc.cluster.local:9123"
            - name: CONTROLLER_NAMESPACE
              value: "fluss-broker"
          resources:
            requests: { cpu: 100m, memory: 256Mi }
            limits: { cpu: 500m, memory: 512Mi }
EOF
```

### Step 10: Verify

```bash
# All pods running
kubectl get pods -n fluss              # ZK (3) + Fluss coordinator (1) + tablets (3)
kubectl get pods -n knative-eventing   # Knative
kubectl get pods -n fluss-broker       # Controller

# CRDs exist
kubectl get crd | grep fluss.io        # brokers, triggers

# Controller logs
kubectl logs -n fluss-broker deploy/fluss-broker-controller -f
```

### Step 11: Create a Broker + Trigger

```bash
kubectl apply -f config/samples/broker-default.yaml
kubectl apply -f config/samples/trigger-order-created.yaml
kubectl get brokers,triggers -n fluss-broker
```

## Costs (POC)

| Resource | Config | ~Monthly |
|----------|--------|----------|
| EKS control plane | — | $73 |
| EC2 spot | 3x t3.large | ~$50 |
| S3 | Minimal | ~$1 |
| **Total** | | **~$124/month** |

`terraform destroy` when not testing.

## File Structure

```
deploy/terraform/
├── main.tf                    VPC + EKS + OIDC + S3 + IAM (IRSA)
├── variables.tf               Input variables
├── outputs.tf                 Cluster name, endpoint, kubeconfig, S3, IAM
├── helm.tf                    Namespaces (Fluss, Knative, Broker)
├── app.tf                     CRDs + RBAC + controller deployment
├── fluss-values.yaml.tpl      Reference Fluss Helm values (not used by TF directly)
├── terraform.tfvars.example   Copy to terraform.tfvars
├── .gitignore
└── README.md
```

## What's Not Included (POC Limitations)

- **No EBS persistence** — Fluss/ZK use emptyDir. Pod restart = data loss. For prod, install `aws-ebs-csi-driver` EKS add-on.
- **No NAT gateway** — nodes have public IPs. For prod, add private subnets + NAT.
- **No Iceberg tiering** — the POC deploys Fluss only. For native Iceberg tiering on EKS, you also need: Polaris (Iceberg REST catalog), an S3 warehouse bucket (created by Terraform), and the `fluss-lake-iceberg` + `fluss-fs-s3` plugins in the Fluss image. The local `docker-compose --profile lakehouse` has a working reference implementation.
- **No TLS/ingress** — controller endpoint is ClusterIP only. For prod, add ALB Ingress Controller.
- **Single AZ tolerance** — 3 nodes but no pod disruption budgets.

## Cleanup

```bash
# Delete K8s resources first (S3 buckets may have objects from Fluss tiering)
kubectl delete namespace fluss-broker fluss knative-eventing

terraform destroy    # type 'yes'
```

## Troubleshooting

```bash
# Nodes not joining
aws eks describe-nodegroup --cluster-name fluss-broker-poc \
  --nodegroup-name fluss-broker-nodes --query 'nodegroup.status'

# Fluss pods can't access S3 (IRSA)
kubectl describe sa fluss -n fluss | grep role-arn
# Should show: eks.amazonaws.com/role-arn: arn:aws:iam::...
# If missing, the annotation wasn't applied — check Helm values

# Fluss pods can't find ZK
kubectl exec -n fluss deploy/fluss-coordinator -- \
  nslookup zk-zookeeper.fluss.svc.cluster.local

# Knative not ready
kubectl get pods -n knative-eventing
kubectl logs -n knative-eventing deploy/eventing-controller
```
