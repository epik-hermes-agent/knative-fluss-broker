output "cluster_name" {
  description = "EKS cluster name"
  value       = aws_eks_cluster.main.name
}

output "cluster_endpoint" {
  description = "EKS cluster API endpoint"
  value       = aws_eks_cluster.main.endpoint
}

output "cluster_ca_certificate" {
  description = "EKS cluster CA certificate (base64)"
  value       = aws_eks_cluster.main.certificate_authority[0].data
  sensitive   = true
}

output "kubeconfig_command" {
  description = "Run this to configure kubectl"
  value       = "aws eks update-kubeconfig --name ${aws_eks_cluster.main.name} --region ${var.aws_region}"
}

output "vpc_id" {
  description = "VPC ID"
  value       = aws_vpc.main.id
}

output "s3_bucket_fluss" {
  description = "S3 bucket for Fluss tiered storage"
  value       = aws_s3_bucket.fluss_data.bucket
}

output "s3_bucket_iceberg" {
  description = "S3 bucket for Iceberg warehouse"
  value       = aws_s3_bucket.iceberg_warehouse.bucket
}

output "fluss_s3_role_arn" {
  description = "IAM role ARN for Fluss S3 access (IRSA)"
  value       = aws_iam_role.fluss_s3.arn
}

output "aws_region" {
  description = "AWS region"
  value       = var.aws_region
}
