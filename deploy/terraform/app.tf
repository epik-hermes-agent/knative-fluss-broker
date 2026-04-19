# ─────────────────────────────────────────────────────
# K8s Resources — CRDs, RBAC
# Controller deployment is done manually after image push (see README step 8)
# ─────────────────────────────────────────────────────

# ── Broker CRD ────────────────────────────────────

resource "kubernetes_manifest" "broker_crd" {
  depends_on = [aws_eks_node_group.main]

  manifest = {
    apiVersion = "apiextensions.k8s.io/v1"
    kind       = "CustomResourceDefinition"
    metadata = {
      name = "brokers.eventing.fluss.io"
      labels = {
        "app.kubernetes.io/name"    = "knative-fluss-broker"
        "app.kubernetes.io/part-of" = "knative-fluss-broker"
      }
    }
    spec = {
      group = "eventing.fluss.io"
      scope = "Namespaced"
      names = {
        plural     = "brokers"
        singular   = "broker"
        kind       = "Broker"
        shortNames = ["fb"]
      }
      versions = [{
        name    = "v1alpha1"
        served  = true
        storage = true
        schema = {
          openAPIV3Schema = {
            type = "object"
            properties = {
              spec = {
                type = "object"
                properties = {
                  fluss = {
                    type = "object"
                    properties = {
                      endpoint = { type = "string", default = "fluss://fluss-coordinator.fluss.svc.cluster.local:9123" }
                    }
                  }
                  ingress = {
                    type = "object"
                    properties = {
                      replicas = { type = "integer", default = 1, minimum = 1 }
                    }
                  }
                  schema = {
                    type = "object"
                    properties = {
                      enabled = { type = "boolean", default = true }
                    }
                  }
                }
              }
              status = {
                type = "object"
                properties = {
                  conditions = {
                    type = "array"
                    items = {
                      type = "object"
                      properties = {
                        type               = { type = "string" }
                        status             = { type = "string" }
                        reason             = { type = "string" }
                        message            = { type = "string" }
                        lastTransitionTime = { type = "string" }
                      }
                    }
                  }
                  observedGeneration = { type = "integer" }
                }
              }
            }
          }
        }
        subresources = { status = {} }
      }]
    }
  }
}

# ── Trigger CRD ───────────────────────────────────

resource "kubernetes_manifest" "trigger_crd" {
  depends_on = [aws_eks_node_group.main]

  manifest = {
    apiVersion = "apiextensions.k8s.io/v1"
    kind       = "CustomResourceDefinition"
    metadata = {
      name = "triggers.eventing.fluss.io"
      labels = {
        "app.kubernetes.io/name"    = "knative-fluss-broker"
        "app.kubernetes.io/part-of" = "knative-fluss-broker"
      }
    }
    spec = {
      group = "eventing.fluss.io"
      scope = "Namespaced"
      names = {
        plural     = "triggers"
        singular   = "trigger"
        kind       = "Trigger"
        shortNames = ["ft"]
      }
      versions = [{
        name    = "v1alpha1"
        served  = true
        storage = true
        schema = {
          openAPIV3Schema = {
            type = "object"
            properties = {
              spec = {
                type     = "object"
                required = ["broker", "subscriber"]
                properties = {
                  broker = { type = "string" }
                  filter = {
                    type = "object"
                    properties = {
                      attributes = {
                        type                 = "object"
                        additionalProperties = { type = "string" }
                      }
                    }
                  }
                  subscriber = {
                    type     = "object"
                    required = ["uri"]
                    properties = {
                      uri = { type = "string", pattern = "^https?://" }
                      delivery = {
                        type = "object"
                        properties = {
                          retry         = { type = "integer", default = 5 }
                          backoffPolicy = { type = "string", default = "exponential" }
                          backoffDelay  = { type = "string", default = "PT1S" }
                        }
                      }
                    }
                  }
                }
              }
              status = {
                type = "object"
                properties = {
                  conditions = {
                    type = "array"
                    items = {
                      type = "object"
                      properties = {
                        type               = { type = "string" }
                        status             = { type = "string" }
                        reason             = { type = "string" }
                        message            = { type = "string" }
                        lastTransitionTime = { type = "string" }
                      }
                    }
                  }
                  observedGeneration = { type = "integer" }
                }
              }
            }
          }
        }
        subresources = { status = {} }
      }]
    }
  }
}

# ── Controller RBAC ───────────────────────────────

resource "kubernetes_namespace" "broker" {
  metadata {
    name = "fluss-broker"
    labels = {
      "knative-eventing-injection" = "enabled"
    }
  }

  depends_on = [aws_eks_node_group.main]
}

resource "kubernetes_service_account" "controller" {
  metadata {
    name      = "fluss-broker-controller"
    namespace = kubernetes_namespace.broker.metadata[0].name
  }
}

resource "kubernetes_cluster_role" "controller" {
  metadata { name = "fluss-broker-controller" }

  rule {
    api_groups = ["eventing.fluss.io"]
    resources  = ["brokers", "triggers"]
    verbs      = ["get", "list", "watch", "create", "update", "patch", "delete"]
  }
  rule {
    api_groups = ["eventing.fluss.io"]
    resources  = ["brokers/status", "triggers/status", "brokers/finalizers", "triggers/finalizers"]
    verbs      = ["get", "update", "patch"]
  }
  rule {
    api_groups = [""]
    resources  = ["pods", "services", "configmaps", "endpoints"]
    verbs      = ["get", "list", "watch", "create", "update", "patch", "delete"]
  }
  rule {
    api_groups = ["apps"]
    resources  = ["deployments"]
    verbs      = ["get", "list", "watch", "create", "update", "patch", "delete"]
  }
  rule {
    api_groups = [""]
    resources  = ["events"]
    verbs      = ["create", "patch"]
  }
  rule {
    api_groups = [""]
    resources  = ["namespaces"]
    verbs      = ["get", "list", "watch"]
  }
}

resource "kubernetes_cluster_role_binding" "controller" {
  metadata { name = "fluss-broker-controller" }
  role_ref {
    api_group = "rbac.authorization.k8s.io"
    kind      = "ClusterRole"
    name      = kubernetes_cluster_role.controller.metadata[0].name
  }
  subject {
    kind      = "ServiceAccount"
    name      = kubernetes_service_account.controller.metadata[0].name
    namespace = kubernetes_namespace.broker.metadata[0].name
  }
}

# NOTE: Controller Deployment is NOT created by Terraform.
# Deploy it manually after pushing the image to ECR (see README step 8).
