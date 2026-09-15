variable "project_name" {
  description = "Lowercase project prefix."
  type        = string
  default     = "socp"

  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{2,20}$", var.project_name))
    error_message = "project_name must be 3-21 lowercase letters, digits, or hyphens."
  }
}

variable "environment" {
  description = "Environment name used in tags and resource names."
  type        = string
  default     = "dev"

  validation {
    condition     = contains(["dev", "staging", "prod"], var.environment)
    error_message = "environment must be dev, staging, or prod."
  }
}

variable "aws_region" {
  description = "AWS region for disposable resources."
  type        = string
  default     = "ap-southeast-1"
}

variable "github_repository" {
  description = "GitHub repository in owner/name form trusted by AWS OIDC."
  type        = string

  validation {
    condition     = can(regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$", var.github_repository))
    error_message = "github_repository must use owner/name form."
  }
}

variable "infrastructure_environment" {
  description = "Protected GitHub Environment allowed to assume the apply role."
  type        = string
  default     = "infrastructure"
}

variable "release_environments" {
  description = "Protected GitHub Environments allowed to assume the release role."
  type        = set(string)
  default     = ["staging", "production"]

  validation {
    condition     = length(var.release_environments) > 0
    error_message = "At least one release environment is required."
  }
}

variable "create_github_oidc_provider" {
  description = "Create the account-level GitHub OIDC provider. Disable when it already exists."
  type        = bool
  default     = true
}

variable "existing_github_oidc_provider_arn" {
  description = "Existing GitHub OIDC provider ARN when creation is disabled."
  type        = string
  default     = null
  nullable    = true

  validation {
    condition = (
      var.create_github_oidc_provider ||
      (var.existing_github_oidc_provider_arn != null &&
      startswith(var.existing_github_oidc_provider_arn, "arn:"))
    )
    error_message = "Set existing_github_oidc_provider_arn when provider creation is disabled."
  }
}

variable "github_oidc_thumbprints" {
  description = "GitHub Actions OIDC root certificate thumbprints."
  type        = list(string)
  default = [
    "6938fd4d98bab03faadb97b34396831e3780aea1",
    "1b511abead59c6ce207077c0bf0e0043b1382612"
  ]
}

variable "state_bucket_name" {
  description = "Bootstrap output used to scope GitHub Terraform state permissions."
  type        = string
}

variable "state_kms_key_arn" {
  description = "Bootstrap output used to scope GitHub Terraform state encryption permissions."
  type        = string
}

variable "state_object_key" {
  description = "Exact S3 object key used by the environment backend."
  type        = string
  default     = "environments/dev/terraform.tfstate"
}

variable "vpc_cidr" {
  description = "IPv4 CIDR for the disposable VPC."
  type        = string
  default     = "10.42.0.0/16"
}

variable "kubernetes_version" {
  description = "EKS Kubernetes minor version."
  type        = string
  default     = "1.36"
}

variable "cluster_public_access_cidrs" {
  description = "CIDRs allowed to reach the public EKS API endpoint."
  type        = list(string)
  default     = ["0.0.0.0/0"]

  validation {
    condition = (
      var.environment != "prod" ||
      !contains(var.cluster_public_access_cidrs, "0.0.0.0/0")
    )
    error_message = "Production EKS API access must not allow 0.0.0.0/0."
  }
}

variable "node_instance_types" {
  description = "EC2 instance types for the disposable managed node group."
  type        = list(string)
  default     = ["t3.large"]
}

variable "node_min_size" {
  type    = number
  default = 1
}

variable "node_desired_size" {
  type    = number
  default = 2
}

variable "node_max_size" {
  type    = number
  default = 3
}

variable "node_disk_size_gib" {
  type    = number
  default = 50

  validation {
    condition     = var.node_disk_size_gib >= 20
    error_message = "node_disk_size_gib must be at least 20 GiB."
  }
}

variable "monthly_budget_usd" {
  description = "Monthly cost budget for the disposable environment."
  type        = number
  default     = 150

  validation {
    condition     = var.monthly_budget_usd > 0
    error_message = "monthly_budget_usd must be positive."
  }
}

variable "budget_alert_email" {
  description = "Optional email subscriber for actual and forecast budget alerts. An empty string is treated as unset."
  type        = string
  default     = null
  nullable    = true

  validation {
    condition = (
      var.budget_alert_email == null ||
      trimspace(var.budget_alert_email) == "" ||
      can(regex("^[^@[:space:]]+@[^@[:space:]]+\\.[^@[:space:]]+$", trimspace(var.budget_alert_email)))
    )
    error_message = "budget_alert_email must be null, blank, or a single email address."
  }
}

variable "control_plane_log_retention_days" {
  type    = number
  default = 14
}

variable "enable_enhanced_ecr_scanning" {
  description = "Enable account-level continuous Inspector scanning for SOCP repositories."
  type        = bool
  default     = false
}
