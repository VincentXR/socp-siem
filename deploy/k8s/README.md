# Kubernetes prerequisites

Helm is the only application release entry point. The raw Kubernetes assets in
this directory contain cluster-scoped prerequisites that the namespace-scoped
release role cannot create.

Create the namespace with the infrastructure apply role before the first Helm
release:

```bash
kubectl apply -f deploy/k8s/namespace.yaml
```

The namespace enforces the Kubernetes Restricted Pod Security Standard. The
Helm chart owns the ServiceAccount, ConfigMap, six Deployments and Services,
PodDisruptionBudgets, HorizontalPodAutoscalers, NetworkPolicies, and optional
PrometheusRule. Do not maintain parallel application Deployment manifests
under this directory.

Before rollout, the deployment platform must create the external
`socp-runtime-secrets` Secret in this namespace. Real secret values must not be
stored in Git. Kafka, PostgreSQL, OpenSearch, ClickHouse, Redis, and the
identity provider are external dependencies and must be reachable from the
namespace.
