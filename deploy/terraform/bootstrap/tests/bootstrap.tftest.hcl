mock_provider "aws" {
  mock_data "aws_caller_identity" {
    defaults = {
      account_id = "123456789012"
      arn        = "arn:aws:iam::123456789012:user/terraform-test"
      id         = "123456789012"
      user_id    = "AIDATEST"
    }
  }
}

run "secure_state_baseline" {
  command = plan

  variables {
    project_name = "socp"
    aws_region   = "ap-southeast-1"
  }

  assert {
    condition     = aws_s3_bucket.terraform_state.bucket == "socp-123456789012-ap-southeast-1-tfstate"
    error_message = "The derived state bucket name must be stable per account and region."
  }

  assert {
    condition     = aws_s3_bucket_versioning.terraform_state.versioning_configuration[0].status == "Enabled"
    error_message = "Terraform state versioning must remain enabled."
  }

  assert {
    condition     = aws_kms_key.terraform_state.enable_key_rotation
    error_message = "The Terraform state KMS key must rotate automatically."
  }

  assert {
    condition     = aws_s3_bucket_public_access_block.terraform_state.restrict_public_buckets
    error_message = "The Terraform state bucket must reject public access."
  }
}
