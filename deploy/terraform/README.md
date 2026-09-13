# AWS infrastructure

This directory provisions a disposable AWS reference environment for the SOCP
core event path. It does not claim that every SOCP service or data dependency
is production-ready on AWS.

## Lifecycle boundaries

`bootstrap` owns the state bucket and state KMS key. Apply it once with trusted
administrator credentials, then migrate its local state into the bucket. Its
bucket and key use `prevent_destroy`; normal demo teardown must not remove
them.

`environments/dev` owns the disposable VPC, one-NAT network, EKS cluster,
managed node group, four ECR repositories, GitHub OIDC roles, EKS access
entries, control-plane logging, and monthly budget. It intentionally does not
provision Kafka, PostgreSQL, OpenSearch, ClickHouse, Redis, or an identity
provider. The executable demo must select one separately documented dependency
profile instead of silently treating single-node middleware as production.

## 1. Bootstrap remote state

```bash
cd deploy/terraform/bootstrap
terraform init
terraform apply -var='project_name=socp' -var='aws_region=ap-southeast-1'
```

Record the `state_bucket_name` and `state_kms_key_arn` outputs. To migrate the
bootstrap state itself, copy `backend.s3.tf.example` to the ignored
`backend.s3.tf`, replace its placeholders, and run:

```bash
terraform init -migrate-state
```

Never commit `backend.s3.tf`, state, plan files, credentials, account IDs, or
generated evidence.

## 2. Initialize the disposable environment

Copy `environments/dev/backend.hcl.example` outside source control, replace
the placeholders, and initialize:

```bash
cd deploy/terraform/environments/dev
terraform init -backend-config=/secure/path/socp-dev-backend.hcl
terraform plan \
  -var='github_repository=VincentXR/socp-siem' \
  -var='state_bucket_name=<bootstrap-output>' \
  -var='state_kms_key_arn=<bootstrap-output>'
```

The first apply uses trusted administrator credentials because the GitHub OIDC
roles do not exist yet. Subsequent pull-request plans, infrastructure applies,
and application releases assume separate roles. The plan role has cloud read
permissions plus the S3/KMS writes required for the state lock; it cannot
mutate the VPC, EKS, ECR, or IAM resources. The release role can push only the
four SOCP repositories and deploy only through its namespace-scoped EKS access
entry.

Before handing deployment to the release role, the infrastructure apply stage
must create `socp-system` from `../k8s/base/namespace.yaml`. A namespace-scoped
EKS access policy intentionally cannot create that cluster-scoped resource.

The monthly budget filters costs by the `Project` tag. Activate that
user-defined cost allocation tag in the AWS Billing console before treating
the budget as an effective project guardrail; activation and cost visibility
are not immediate. The optional enhanced ECR scanning resource is
account-and-region scoped. Enable it only when this stack is the designated
owner and no other stack manages the registry scanning configuration.

The dev EKS API is public so GitHub-hosted runners can reach it. The default
CIDR is therefore broad, while authentication remains IAM-backed. This is a
documented demo trade-off, not a production recommendation. A production
environment must use restricted CIDRs or a runner with private VPC access.

## 3. Teardown

```bash
terraform destroy \
  -var='github_repository=VincentXR/socp-siem' \
  -var='state_bucket_name=<bootstrap-output>' \
  -var='state_kms_key_arn=<bootstrap-output>'
```

Confirm that the EKS cluster, NAT gateway, EIPs, load balancers, EBS volumes,
ECR repositories, and log groups are gone. The bootstrap state bucket and KMS
key remain by design.
