# Kubernetes release contract

`deploy/k8s/base` is the minimal event-plane baseline. `overlays/dev`,
`overlays/staging` and `overlays/prod` choose replica topology without changing
API contracts. Images remain digest-addressed; a release process must replace
the `REPLACE_WITH_RELEASE_DIGEST` placeholders and apply signed manifests.

The two continuously active application paths have separate Kubernetes
workloads: `search-config-api` serves management, ingest and query traffic,
while `search-config-worker` drains the ingestion Outbox and indexes Kafka
events into OpenSearch. The gateway routes to `search-config-api`; it never
uses the worker Service. Detection follows the same API/Worker split.

Every core Deployment carries `socp.io/runtime-unit`, validated against
`build/runtime-topology.json`. The labels describe ownership in the six-unit
target; they do not imply that the other members of `alert-incident` or the
remaining target units are already present in this minimal event-plane base.

The base includes default-deny network policy, non-root/read-only pods,
dependency-aware probes, preferred zone anti-affinity, CPU-based HPA and
Prometheus SLO rules. Middleware (Kafka,
PostgreSQL, OpenSearch, ClickHouse, Redis and Temporal) remains an explicit
platform dependency and is not silently represented as an in-cluster demo.
