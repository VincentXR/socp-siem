mock_provider "aws" {
  mock_data "aws_iam_policy_document" {
    defaults = {
      id            = "mock-policy"
      json          = "{\"Version\":\"2012-10-17\",\"Statement\":[]}"
      minified_json = "{\"Version\":\"2012-10-17\",\"Statement\":[]}"
    }
  }

  mock_data "aws_caller_identity" {
    defaults = {
      account_id = "123456789012"
      arn        = "arn:aws:iam::123456789012:user/terraform-test"
      id         = "123456789012"
      user_id    = "AIDATEST"
    }
  }

  mock_data "aws_partition" {
    defaults = {
      dns_suffix         = "amazonaws.com"
      id                 = "aws"
      partition          = "aws"
      reverse_dns_prefix = "com.amazonaws"
    }
  }

  mock_data "aws_availability_zones" {
    defaults = {
      names    = ["ap-southeast-1a", "ap-southeast-1b"]
      state    = "available"
      zone_ids = ["apse1-az1", "apse1-az2"]
    }
  }
}

variables {
  github_repository = "VincentXR/socp-siem"
  state_bucket_name = "socp-123456789012-ap-southeast-1-tfstate"
  state_kms_key_arn = "arn:aws:kms:ap-southeast-1:123456789012:key/test-state-key"
}

run "least_privilege_reference_shape" {
  command = plan

  assert {
    condition     = length(aws_ecr_repository.service) == 4
    error_message = "The cloud demo must build only the four core service images."
  }

  assert {
    condition     = aws_eks_cluster.this.access_config[0].authentication_mode == "API"
    error_message = "EKS must use API-backed access entries instead of aws-auth mappings."
  }

  assert {
    condition     = !aws_eks_cluster.this.access_config[0].bootstrap_cluster_creator_admin_permissions
    error_message = "The cluster creator must not retain implicit system:masters access."
  }

  assert {
    condition     = aws_eks_access_policy_association.release.access_scope[0].type == "namespace"
    error_message = "The release role must remain namespace-scoped."
  }

  assert {
    condition     = aws_ecr_repository.service["detect-web"].image_tag_mutability == "IMMUTABLE"
    error_message = "Release repositories must reject mutable image tags."
  }

  assert {
    condition     = length(aws_eks_cluster.this.enabled_cluster_log_types) == 5
    error_message = "All five EKS control-plane log types must remain enabled."
  }

  assert {
    condition     = aws_eks_addon.metrics_server.addon_name == "metrics-server"
    error_message = "The EKS environment must provide resource metrics for HPA."
  }
}

run "production_rejects_world_open_api" {
  command = plan

  variables {
    environment                 = "prod"
    cluster_public_access_cidrs = ["0.0.0.0/0"]
  }

  expect_failures = [var.cluster_public_access_cidrs]
}

run "invalid_node_sizing_is_rejected" {
  command = plan

  variables {
    node_min_size     = 3
    node_desired_size = 2
    node_max_size     = 1
  }

  expect_failures = [aws_eks_node_group.core]
}

run "budget_without_a_subscriber_cannot_warn" {
  command = plan

  assert {
    condition     = length(aws_budgets_budget.monthly.notification) == 0
    error_message = "An unset budget subscriber renders no notification, which is why the workflow must pass one."
  }
}

run "blank_budget_subscriber_is_treated_as_unset" {
  command = plan

  # CI passes the subscriber through TF_VAR_budget_alert_email, and an unset
  # GitHub variable expands to the empty string rather than to null.
  variables {
    budget_alert_email = "   "
  }

  assert {
    condition     = length(aws_budgets_budget.monthly.notification) == 0
    error_message = "A blank subscriber must render no notification instead of subscribing an empty address."
  }
}

run "configured_budget_subscriber_creates_forecast_and_actual_alerts" {
  command = plan

  variables {
    budget_alert_email = "security@example.com"
  }

  assert {
    condition     = length(aws_budgets_budget.monthly.notification) == 2
    error_message = "A configured subscriber must create both the forecasted and the actual budget alert."
  }

  assert {
    condition = alltrue([
      for notification in aws_budgets_budget.monthly.notification :
      contains(notification.subscriber_email_addresses, "security@example.com")
    ])
    error_message = "Both budget alerts must subscribe the configured address."
  }
}

run "malformed_budget_subscriber_is_rejected" {
  command = plan

  variables {
    budget_alert_email = "not-an-email"
  }

  expect_failures = [var.budget_alert_email]
}
