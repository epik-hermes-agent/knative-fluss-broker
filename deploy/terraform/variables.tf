variable "aws_region" {
  description = "AWS region for EKS cluster"
  type        = string
  default     = "eu-west-1"
}

variable "project_name" {
  description = "Project name prefix for all resources"
  type        = string
  default     = "fluss-broker"
}

variable "cluster_name" {
  description = "EKS cluster name"
  type        = string
  default     = "fluss-broker-poc"
}

variable "k8s_version" {
  description = "Kubernetes version for EKS"
  type        = string
  default     = "1.29"
}

variable "node_instance_type" {
  description = "EC2 instance type for EKS nodes"
  type        = string
  default     = "t3.large"
}

variable "node_count" {
  description = "Number of EKS worker nodes"
  type        = number
  default     = 3
}

variable "use_spot" {
  description = "Use spot instances for nodes (cheaper for POC)"
  type        = bool
  default     = true
}

variable "force_destroy_s3" {
  description = "Allow terraform destroy to delete S3 buckets with content"
  type        = bool
  default     = true
}

variable "ecr_image" {
  description = "ECR image URI for the broker controller (push with: make docker-push)"
  type        = string
  default     = ""
}

variable "fluss_version" {
  description = "Apache Fluss version (Docker image tag and Helm chart). Use '1.0-SNAPSHOT' for local builds or 'X.Y.Z-incubating' for release."
  type        = string
  default     = "1.0-SNAPSHOT"
}

variable "knative_version" {
  description = "Knative Eventing version"
  type        = string
  default     = "1.16.0"
}

variable "fluss_chart_path" {
  description = "Local path to Fluss Helm chart (download from apache/fluss releases). Leave empty to use OCI registry."
  type        = string
  default     = ""
}
