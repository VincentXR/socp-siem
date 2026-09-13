variable "project_name" {
  description = "Lowercase project prefix used in globally named resources."
  type        = string
  default     = "socp"

  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{2,20}$", var.project_name))
    error_message = "project_name must be 3-21 lowercase letters, digits, or hyphens."
  }
}

variable "aws_region" {
  description = "AWS region containing the state KMS key and bucket."
  type        = string
  default     = "ap-southeast-1"
}

variable "state_bucket_name" {
  description = "Optional globally unique bucket name; null derives one from account and region."
  type        = string
  default     = null
  nullable    = true
}

variable "noncurrent_version_retention_days" {
  description = "Days to retain noncurrent Terraform state object versions."
  type        = number
  default     = 365

  validation {
    condition     = var.noncurrent_version_retention_days >= 90
    error_message = "Retain noncurrent state versions for at least 90 days."
  }
}
