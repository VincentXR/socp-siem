output "state_bucket_name" {
  description = "S3 bucket used by environment state backends."
  value       = aws_s3_bucket.terraform_state.id
}

output "state_kms_key_arn" {
  description = "KMS key used to encrypt Terraform state and lock files."
  value       = aws_kms_key.terraform_state.arn
}

output "bootstrap_backend_key" {
  description = "Suggested key used after migrating the bootstrap state."
  value       = "bootstrap/terraform.tfstate"
}
