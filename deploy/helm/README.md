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

Changing `runtime.extraConfig` without adding those rules is the failure mode
to watch for: the readiness probes keep checking the new endpoints, fail, and
`--atomic` reverts the whole release after the ten-minute timeout. The
in-cluster default needs no rules because `socp-data` is already allowed. A
managed endpoint outside the cluster needs both the address and the egress
rule, for example:

```yaml
monitoring:
  prometheusRule:
    enabled: true
  serviceMonitor:
    enabled: true

runtime:
  extraConfig:
    SOCP_KAFKA_BOOTSTRAP: b-1.example.kafka.ap-southeast-1.amazonaws.com:9096

networkPolicy:
  additionalEgress:
    - to:
        - ipBlock:
            cidr: 10.0.0.0/8
      ports:
        - protocol: TCP
          port: 9096
```

## Render locally

Repository verification uses non-routable image names:

```bash
helm lint deploy/helm/socp-core \
  -f deploy/helm/socp-core/ci/test-values.yaml \
  -f deploy/helm/socp-core/values-production.yaml
python build/verify-helm.py
```

The `ci/test-values.yaml` file is render-only and must never be supplied to a
cluster rollout. It supplies renderable images and nothing else: a capability
override hidden in a file shared by every profile would otherwise let the
verifier report a resource that no real profile renders.

`build/verify-helm.py` renders each profile twice. The first render is the
release path — the profile values plus the same `--set-string images.*`
arguments `.github/workflows/aws-release.yml` uses. The second forces every
optional capability on. Any object that appears only in the second render must
be in the verifier's expected set, so a new flag-gated resource cannot quietly
become something only CI produces.

Each profile must also declare its monitoring intent explicitly in
`values-<profile>.yaml`. Inheriting the chart default is rejected, because
silent inheritance is what let the verifier assert a rendered `PrometheusRule`
while no real release produced one.

## Monitoring

The chart ships two optional objects and keeps both off by default:

| Object | Flag | Purpose |
| --- | --- | --- |
| `PrometheusRule` | `monitoring.prometheusRule.enabled` | The eight SLO alerts for the event path |
| `ServiceMonitor` | `monitoring.serviceMonitor.enabled` | Metrics discovery for the five workloads that expose Prometheus |

Both require the Prometheus Operator CRDs. Enabling them on a cluster that does
not run the operator makes `helm upgrade --install --atomic` fail and roll the
release back, which is why the chart default is off and each environment opts
in through its own values file.

The two flags are not independent: a rule with no scrape target never
evaluates, so `build/verify-helm.py` rejects any profile that enables the rule
without the ServiceMonitor. Turn both on together once the operator is present:

```bash
helm upgrade --install socp-core deploy/helm/socp-core \
  --set monitoring.prometheusRule.enabled=true \
  --set monitoring.serviceMonitor.enabled=true
```

Prometheus authenticates with the metrics token from `socp-runtime-secrets`
(`SOCP_SECURITY_METRICS_TOKEN`). The platform accepts it as
`Authorization: Bearer <token>` and restricts that credential to the actuator
metrics endpoints, so Prometheus needs neither a user JWT nor a gateway
signature. A production profile must set a non-default token; the platform
refuses to start with the development default.

The token is one accepted credential on the metrics path, not an exclusive
access control. The interceptor treats it as one branch of the authentication
chain, and the actuator endpoints carry no role annotation, so a valid user
JWT also passes it. In a `prod` profile the gateway-signature requirement is
what closes that path, because Prometheus cannot produce a gateway signature
and a bare JWT is rejected. Do not describe the metrics token as the only way
to read the endpoint.

`api-gateway` is deliberately not scraped. Its application port exposes health
only and defers Prometheus samples to a dedicated internal management
path/port, so the chart declares no `health.metricsPath` for it. The verifier
rejects a `ServiceMonitor` for that workload and rejects any `metricsPath` that
does not match the workload's health path.

The `ServiceMonitor` resolves its bearer token from `socp-runtime-secrets` in
the release namespace, so the Prometheus instance must be able to read Secrets
there. A Prometheus that runs elsewhere or lacks that permission loses the
credential silently and every scrape returns 401. Confirm credential
reachability before enabling the flag.

The default network policy already admits metrics traffic to port 8080 from
the namespace labelled `kubernetes.io/metadata.name: monitoring`. A Prometheus
in a differently named namespace needs `networkPolicy.metricsIngressNamespaceSelector`
updated in the environment values file, otherwise the scrape is dropped.

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
