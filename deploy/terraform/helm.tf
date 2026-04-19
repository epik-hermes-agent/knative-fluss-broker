# ─────────────────────────────────────────────────────
# Kubernetes Namespaces
# Helm releases (ZK, Fluss) and Knative are installed
# manually after terraform apply (see README.md)
# ─────────────────────────────────────────────────────

# Configure providers after EKS is up
provider "kubernetes" {
  host                   = aws_eks_cluster.main.endpoint
  cluster_ca_certificate = base64decode(aws_eks_cluster.main.certificate_authority[0].data)
  exec {
    api_version = "client.authentication.k8s.io/v1beta1"
    command     = "aws"
    args        = ["eks", "get-token", "--cluster-name", aws_eks_cluster.main.name]
  }
}

provider "helm" {
  kubernetes {
    host                   = aws_eks_cluster.main.endpoint
    cluster_ca_certificate = base64decode(aws_eks_cluster.main.certificate_authority[0].data)
    exec {
      api_version = "client.authentication.k8s.io/v1beta1"
      command     = "aws"
      args        = ["eks", "get-token", "--cluster-name", aws_eks_cluster.main.name]
    }
  }
}

# ── Namespaces (pre-create so manual install is faster) ──
# These depend on the EKS cluster being ready. Without depends_on,
# Terraform may try to connect before the cluster exists.

resource "kubernetes_namespace" "fluss" {
  metadata { name = "fluss" }

  depends_on = [aws_eks_node_group.main]
}

resource "kubernetes_namespace" "knative_eventing" {
  metadata { name = "knative-eventing" }

  depends_on = [aws_eks_node_group.main]
}
