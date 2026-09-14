output "cluster_name" {
  value = aws_eks_cluster.this.name
}

output "aws_account_id" {
  value = data.aws_caller_identity.current.account_id
}

output "aws_region" {
  value = var.aws_region
}

output "cluster_endpoint" {
  value = aws_eks_cluster.this.endpoint
}

output "ecr_repository_urls" {
  value = {
    for service, repository in aws_ecr_repository.service :
    service => repository.repository_url
  }
}

output "ecr_repository_prefix" {
  value = local.name_prefix
}

output "terraform_plan_role_arn" {
  value = aws_iam_role.terraform_plan.arn
}

output "terraform_apply_role_arn" {
  value = aws_iam_role.terraform_apply.arn
}

output "release_role_arn" {
  value = aws_iam_role.release.arn
}

output "release_namespace" {
  value = local.namespace
}

output "demo_limitations" {
  value = [
    "The public EKS API CIDR is broad by default for GitHub-hosted runners.",
    "A single NAT gateway reduces demo cost and is not highly available.",
    "Data dependencies and the identity provider are intentionally external.",
    "VPC CNI permissions remain on the disposable node role during bootstrap."
  ]
}
