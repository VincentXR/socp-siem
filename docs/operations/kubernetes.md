# Kubernetes release contract

`deploy/helm/socp-core` is the canonical application release for the core
event path. Raw application Deployments and environment overlays are not kept
under `deploy/k8s`, preventing Kustomize and Helm from drifting into two
independent release definitions.

The infrastructure role first applies `deploy/k8s/namespace.yaml`. The
namespace uses the Kubernetes Restricted Pod Security Standard. The
namespace-scoped release role then installs the Helm chart; it cannot create
or modify cluster-scoped resources.

## Runtime topology

Four immutable image artifacts produce six workloads:

- `api-gateway`;
- `search-config-api` and `search-config-worker` from `search-config`;
- `detect-web-api` and `detect-web-worker` from `detect-web`;
- `alert-web`.

The gateway routes to the API workloads. Search and Detection workers retain
independent scaling and failure boundaries while sharing their service image.
Detection instance identity comes from the Kubernetes pod UID, so replicas do
not share a fixed ownership identifier.

Every Deployment carries `socp.io/runtime-domain`, validated against
`build/runtime-topology.json`. The label describes ownership and
observability grouping only; it is not part of the immutable Deployment
selector.

## Values and secrets

The chart accepts image repository and digest as separate values and renders
only `repository@sha256:...`. Its JSON schema rejects missing or malformed
digests. Environment files control replica/HPA/PDB policy without duplicating
the workload manifests.

`socp-runtime-secrets` is external to Helm. The deployment platform must
create it before release and rotate it independently. Non-secret dependency
endpoints can be supplied through an environment-owned values file under
`runtime.extraConfig`. Neither generated secrets nor environment values are
committed by the release workflow.

Default egress is limited to cluster DNS, same-namespace service HTTP, and the
declared data-service namespace. Gateway ingress defaults to the
`ingress-nginx` namespace, while metrics ingress defaults to `monitoring`.
Managed dependencies, external identity providers, or different ingress and
monitoring namespaces require explicit environment-owned network policy
values; the chart does not silently permit all external egress.

## Availability and security

The chart includes rolling updates, dependency-aware readiness probes,
process-local liveness probes, startup probes, resource requests/limits,
read-only root filesystems, non-root UID/GID, an explicit writable volume
group, RuntimeDefault seccomp, dropped Linux capabilities, disabled
service-account token mounts, preferred
zone-aware pod anti-affinity, HPA, PDB, and default-deny network policy.

The PrometheusRule is optional because the CRD belongs to the observability
platform. Enable it only after the Prometheus Operator CRDs are installed.
External data services and ingress controllers remain platform dependencies.

## Rollout and rollback

The AWS release workflow uses `helm upgrade --install --atomic --wait` and
waits for all six Deployments. A failed upgrade rolls back automatically.
Operators can inspect and restore a prior successful revision with:

```bash
helm -n socp-system history socp-core
helm -n socp-system rollback socp-core <revision> --wait
```

Run `python build/verify-helm.py` to lint and render all three capacity
profiles with non-routable CI images and verify the resulting security and
topology contracts.
