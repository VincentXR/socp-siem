# ADR 008: use a disposable AWS environment for delivery evidence

Status: accepted; 2026-09-14

## Decision

SOCP's AWS reference environment covers one bounded delivery path without
claiming that the complete SIEM is production-ready on AWS. The executable
scope is the four-image core event path: API Gateway, Search API/Worker,
Detection API/Worker, and Alert. Other business services remain outside this
reference topology.

Terraform state infrastructure has a separate lifecycle. The bootstrap stack
creates a versioned, KMS-encrypted, non-public S3 bucket. The disposable dev
stack consumes that backend and cannot destroy it with the VPC and EKS
environment.

Infrastructure and application release use separate GitHub OIDC trust
boundaries:

- pull requests assume a plan role with resource-read and state-lock access;
- the protected `infrastructure` environment assumes the apply role;
- protected release environments assume a role limited to four ECR
  repositories and a namespace-scoped EKS Access Entry.

The cluster uses EKS API access entries instead of adding human or CI roles to
the deprecated `aws-auth` ConfigMap. Application pods receive no AWS IAM role
by default. Add-ons receive Pod Identity only when they actually call AWS
APIs.

Helm is the only application release definition. The chart maps four
digest-addressed images to six workloads, while raw Kubernetes assets retain
only the cluster-scoped namespace prerequisite. Staging builds, scans, and
publishes each image once; production promotion resolves the existing ECR
digests for an explicit source commit instead of rebuilding them.

The infrastructure workflow rejects cloud access from fork pull requests.
Protected mutation creates and applies one saved plan; destroy additionally
requires an explicit confirmation token. After an apply, the infrastructure
stage creates the cluster-scoped `socp-system` namespace before the
namespace-scoped release role is used. Project-filtered budget evidence also
requires the account's `Project` cost allocation tag to be activated. Enhanced
ECR scanning remains disabled by default because its configuration is
account-and-region scoped and must have a single owner.

## Explicit demo trade-offs

The dev environment spans two availability zones but uses one NAT gateway to
control cost. Its public EKS API endpoint is reachable from broad CIDRs so a
GitHub-hosted runner can deploy; authorization still requires a trusted IAM
principal. Neither choice is a production HA/security recommendation. A
production design needs per-AZ egress or approved VPC endpoints and a private
or restricted cluster endpoint reachable by a controlled runner.

Kafka, PostgreSQL, OpenSearch, ClickHouse, Redis, and the identity provider are
external dependencies. A disposable single-node dependency profile may be
used for validation only when it is visibly labelled `demo-only/no-HA`. The
production reference keeps those dependencies external and does not claim
they have been provisioned or accepted.

## Evidence boundary

`terraform fmt`, `validate`, and mock-provider tests establish configuration
correctness without creating cloud resources. Production or HA claims require
commit-scoped evidence from an actual apply, image release, event-path smoke
test, worker recovery, failed-release rollback, teardown, and cost review.
