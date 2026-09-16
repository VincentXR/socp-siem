# Helm release

`socp-core` is the canonical application release for the core event path. It
deploys four image artifacts as six independently scalable workloads:

| Image | Workloads |
| --- | --- |
| `api-gateway` | `api-gateway` |
| `search-config` | `search-config-api`, `search-config-worker` |
| `detect-web` | `detect-web-api`, `detect-web-worker` |
| `alert-web` | `alert-web` |

The chart requires every image as `repository@sha256:digest`; tags are not
accepted as rollout inputs. `values-dev.yaml`, `values-staging.yaml`, and
`values-production.yaml` change capacity policy without duplicating workload
manifests.

## Prerequisites

The infrastructure role must create `socp-system` using
`deploy/k8s/namespace.yaml`. The deployment platform must then create the
external `socp-runtime-secrets` Secret. The chart never creates secret values
and application service accounts receive no AWS permissions by default.

Dependency endpoints default to the `socp-data` namespace. Override
`runtime.extraConfig` through an environment-owned values file when using
managed or external Kafka, PostgreSQL, OpenSearch, ClickHouse, Redis, or
identity services. The default network policy permits DNS, same-namespace HTTP,
the declared data-service namespace, gateway traffic from `ingress-nginx`, and
metrics collection from `monitoring`. External dependencies require explicit
`networkPolicy.additionalEgress` rules in that same environment-owned file.

## Render locally

Repository verification uses non-routable image names:

```bash
helm lint deploy/helm/socp-core \
  -f deploy/helm/socp-core/ci/test-values.yaml \
  -f deploy/helm/socp-core/values-production.yaml
python build/verify-helm.py
```

The `ci/test-values.yaml` file is render-only and must never be supplied to a
cluster rollout.

## Release behavior

`.github/workflows/aws-release.yml` builds the four JARs once, builds and
scans each image, emits a CycloneDX SBOM, pushes an immutable source-SHA tag to
ECR, resolves the registry digest, and deploys those exact digests with
`helm upgrade --install --atomic`.

A production dispatch requires the full commit SHA of an existing staging
release. It resolves the already-published ECR images and does not rebuild
them. GitHub Environment protection is the approval boundary. The protected
`staging` and `production` environments provide:

- `AWS_ACCOUNT_ID`
- `AWS_REGION`
- `AWS_RELEASE_ROLE_ARN`
- `ECR_REPOSITORY_PREFIX`
- `EKS_CLUSTER_NAME`
- staging-only `SOCP_RUNTIME_IMAGE`, pinned as `repository@sha256:digest`
- optional `SOCP_NAMESPACE` (must remain `socp-system` for the Terraform role)
- optional `SOCP_RUNTIME_SECRET_NAME`
- optional secret `SOCP_HELM_VALUES_B64` containing a base64-encoded,
  non-secret environment values file

The repository variable `AWS_DELIVERY_ENABLED=true` enables automatic staging
delivery after relevant changes reach `main`. Without it, the workflow remains
available for explicit dispatch and does not contact AWS.

Production must resolve the same immutable ECR repositories populated by the
staging build. If clusters use separate AWS accounts, replicate the image
manifests without rebuilding them and grant the production node role pull
access; the resolved digest must remain unchanged.

`--atomic` rolls back a failed upgrade automatically. Operators can inspect or
manually select an earlier revision with `helm history` and `helm rollback`;
production rollback must use the same protected environment.
