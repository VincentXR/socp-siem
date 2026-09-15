provider "aws" {
  region = var.aws_region

  default_tags {
    tags = {
      Project     = var.project_name
      Environment = var.environment
      ManagedBy   = "terraform"
      Purpose     = "aws-reference-environment"
    }
  }
}

data "aws_caller_identity" "current" {}

data "aws_partition" "current" {}

data "aws_availability_zones" "available" {
  state = "available"
}

locals {
  name_prefix  = "${var.project_name}-${var.environment}"
  cluster_name = "${local.name_prefix}-eks"
  namespace    = "socp-system"

  # A budget without a subscriber cannot warn anyone, so it is not a cost
  # guardrail. Normalise the empty string here because CI passes the value
  # through TF_VAR_budget_alert_email, and an unset GitHub variable expands to
  # the empty string rather than to null; without this the notification blocks
  # would subscribe an empty address and the apply would fail.
  budget_alert_email = (
    var.budget_alert_email == null || trimspace(var.budget_alert_email) == ""
    ? null
    : trimspace(var.budget_alert_email)
  )

  availability_zones = slice(data.aws_availability_zones.available.names, 0, 2)

  services = toset([
    "api-gateway",
    "search-config",
    "detect-web",
    "alert-web"
  ])

  github_oidc_provider_arn = var.create_github_oidc_provider ? (
    aws_iam_openid_connect_provider.github[0].arn
  ) : var.existing_github_oidc_provider_arn

  common_tags = {
    Project     = var.project_name
    Environment = var.environment
    ManagedBy   = "terraform"
    Purpose     = "aws-reference-environment"
  }
}
