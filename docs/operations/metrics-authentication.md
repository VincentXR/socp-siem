# Authenticated metrics collection

The base Compose Prometheus job scrapes the 13 servlet services through their
context paths. The gateway application port only exposes health and is omitted.
The production Compose overlay uses `prometheus-prod.yml` with all five
servlet container targets on port 8080, including both API/worker roles;
`SOCP_METRICS_TOKEN_FILE` is required there (no development-path fallback).
Prometheus does not expand `${ENV_VAR}` in YAML; authorization therefore reads
`/run/secrets/socp_metrics_token` using `credentials_file`.

Before starting Prometheus, create a private token file outside source control,
containing exactly the same value as `SOCP_SECURITY_METRICS_TOKEN` on the services.
Set `SOCP_METRICS_TOKEN_FILE` to its absolute path. The default development path
is `infra/secrets/metrics-token` (ignored). Keep the parent directory private
(mode 700) and grant read access only to the operator and the Prometheus
container UID/GID, for example with an appropriate group and mode 640. Compose
file-backed secrets preserve source-file ownership and permissions; a mode-600
file owned only by the host operator is unreadable by a different container UID.
Do not rely on secret `uid`/`gid`/`mode` to remap this bind-mounted file. Never
commit or log the token. Compose mounts it read-only; a missing or unreadable
file must fail startup/scraping rather than downgrade to unauthenticated access.

Changing a token requires coordinating the service value and mounted file;
restart/reload Prometheus after replacing the file. Keep metrics inside the
trusted network. This is independent of a browser login or operator JWT.

Validation: `python3 build/verify-metrics-auth.py` checks the shipped scrape
configuration, verifies a synthetic authenticated scrape against the same file
contract, and rejects the former literal environment-variable placeholder.
It does not contact production targets or claim a live cluster scrape.

For Helm, keep the configured metrics Secret referenced by ServiceMonitor.
The product report workload separately requires `SOCP_MINIO_ACCESS` and
`SOCP_MINIO_SECRET` in its own Secret, a reachable HTTPS `SOCP_MINIO_URL`, and the
configured bucket. Search API and worker require both `SOCP_INGEST_TOKEN` and
`SOCP_VECTOR_TOKEN`; the vector token must match a registered collector credential.
HIPS requires its explicit ingest token plus collector registry. These tokens
must not be put in runtime.config/extraConfig.

`runtime.extraConfig` overrides existing runtime.config keys. Worker HPA maximum
is bounded by `runtime.detectionPartitions` (default 6). That value describes an
existing routed topic; increasing it does not repartition Kafka. Coordinate
partition ownership, rollout and ordering guarantees before changing either.

The real `ProdGuardDeploymentTest` loads the chart values and actual service
YAML with Spring placeholder resolution, injects synthetic Secrets only for
keys declared by each workload, and calls the real ProdGuard. It covers all
16 product workloads and regressions for omitted Search, HIPS and report
credentials. Run `bash build/mvnw.sh -pl platform/socp-auth -am test -Dtest=ProdGuardDeploymentTest -Dsurefire.failIfNoSpecifiedTests=false`.
This is guard evidence only, not dependency connectivity or full boot evidence;
run `build/verify-prod-boot.py` separately in a disposable prepared environment.
