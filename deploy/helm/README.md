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

The deployment platform must create `socp-system` using
`deploy/k8s/namespace.yaml`. It must then create the external
`socp-runtime-secrets` Secret. The chart never creates secret values and
application service accounts receive no external cloud permissions by default.

Every Deployment imports that Secret with `envFrom`, which accepts any subset of
its keys. That is not good enough for the PostgreSQL role pair, because
`application-pg.yml` resolves Flyway's account as
`${SOCP_PG_MIGRATION_USER:${SOCP_PG_USER:socp}}`: a Secret without the migration
keys would run DDL as the restricted runtime role and only fail at the next
schema change. Each database workload therefore also names
`SOCP_PG_USER`, `SOCP_PG_PASSWORD`, `SOCP_PG_MIGRATION_USER`, and
`SOCP_PG_MIGRATION_PASSWORD` through `secretEnv`. A missing key stops the Pod
with a `CreateContainerConfigError` before the container starts instead of
changing which role performs migrations. The Compose overlay enforces the same
four values with `${VAR:?}` interpolation.

`runtime.config` points `SPRING_DATA_REDIS_HOST`/`PORT` at the environment-owned
Redis. That instance holds the service replay nonces, the revoked-session list,
and the shared limiter budget, so it must run with `maxmemory-policy noeviction`.
Eviction is invisible to the application: an evicted nonce makes `SETNX` succeed
again, so the replay is accepted, and an evicted revocation entry re-activates a
logged-out session until its token expires. A full instance under `noeviction`
fails writes instead, and every consumer turns that exception into an explicit
refusal. When the instance requires authentication, add
`SPRING_DATA_REDIS_PASSWORD` to `socp-runtime-secrets`; the chart does not pin it
because a private-network or mTLS-protected managed Redis is a legitimate shape.

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
    SOCP_KAFKA_BOOTSTRAP: kafka.example.internal:9092

networkPolicy:
  additionalEgress:
    - to:
        - ipBlock:
            cidr: 10.0.0.0/8
      ports:
        - protocol: TCP
          port: 9092
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
release path — profile values plus explicit `--set-string images.*`
repository/digest arguments, matching the chart's cloud-neutral release
interface. The second forces every optional capability on. Any object that
appears only in the second render must be in the verifier's expected set, so a
new flag-gated resource cannot quietly become something only CI produces.

Each profile must also declare its monitoring intent explicitly in
`values-<profile>.yaml`. Inheriting the chart default is rejected, because
silent inheritance is what let the verifier assert a rendered `PrometheusRule`
while no real release produced one.

`python build/verify-prod-compose.py` checks this chart against the fail-closed
prerequisites it shares with `infra/docker-compose.prod.yml`: the startup and
liveness probes must target a limited health group rather than the aggregated
endpoint, the readiness timeout must cover the pooled-connection wait the chart
pins, every database workload must name the four PostgreSQL role keys, and the
gateway must declare the Redis revocation and OIDC state backends. That gate
reads the same invariants out of the Compose overlay, so the rehearsal cannot
drift away from the release baseline in the direction of "works only in the
chart".

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

Actuator authorization is same-port by design for the workloads this chart
scrapes: the platform guards every non-public actuator path with a servlet
filter that runs ahead of the request interceptors, and leaves
`/actuator/health` and its groups public. That keeps one port carrying several
trust domains coherently, so the chart moves nothing to a `management.server.port`.
Doing so would put scrape traffic outside the policy that already admits it and
force a new Service port, a new `NetworkPolicy` port, and a rewritten
`ServiceMonitor` for no additional boundary; `socp-metrics-ingress` and the
`ServiceMonitor` endpoint therefore stay on the application port with the
metrics token. The gateway is a separate case, described next: it exposes no
metrics endpoint on its application port at all.

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

## Probes and failure domains

Each container carries three probes, and the two health groups they use are
deliberately different:

| Probe | Path | Meaning of a failure |
| --- | --- | --- |
| `startupProbe` | `<basePath>/liveness` | The process never finished booting, so kubelet restarts it |
| `readinessProbe` | `<basePath>/readiness` | This replica stops receiving traffic; nothing restarts |
| `livenessProbe` | `<basePath>/liveness` | The process is wedged, so kubelet restarts it |

`<basePath>/liveness` is the process-local group. The startup probe must not use
the unqualified `<basePath>`, because Spring's aggregated endpoint folds in the
`db` contributor and `SOCP_HEALTH_REQUIRED_ENDPOINTS`, so a Kafka, OpenSearch,
ClickHouse, or downstream outage would keep every *new* container — a rollout
surge, an HPA scale-up, a rescheduled node — from ever passing startup. Under
`maxUnavailable: 0` those restart loops repeatedly join and leave the shared
consumer group and perturb the replicas that are still serving. Waiting for
dependencies stays a readiness concern, where the cost is one replica out of
rotation. The endpoint is public by design: the platform's actuator filter keeps
`/actuator/health` and its groups unauthenticated precisely so kubelet needs no
credential.

The readiness group also contains the Spring `db` contributor, which borrows a
pooled connection. `runtime.config` pins
`SPRING_DATASOURCE_HIKARI_CONNECTION_TIMEOUT` at 2000ms, below the 5s
`readinessProbe` timeout, so a saturated pool surfaces as a DOWN readiness
signal rather than a probe timeout. Raising the probe timeout without lowering
the pool wait leaves the same ambiguity, and the dependency indicator itself
costs 500ms per configured endpoint, serially.

`SOCP_HEALTH_REQUIRED_ENDPOINTS` lists only what the workload calls
synchronously. Detection's API role, for example, lists Kafka but not
`alert-web`: its alert client lives on the worker role, and a transitive hop
would let one ClickHouse outage pull the northernmost ingest entry out of
rotation once every tier lost readiness at the same time.

## Release behavior

A deployment pipeline should build the four JARs once, build and scan each
image, emit an SBOM, publish immutable images to the target registry, resolve
their digests, and pass those exact repository/digest pairs to Helm.

Production promotion should reuse an already-published set of image digests
for an explicit source revision instead of rebuilding artifacts. Registry,
cluster, identity, ingress, secret, and approval configuration are owned by
the target environment rather than by this chart.

Use `helm upgrade --install --atomic --wait` for rollout. `--atomic` rolls back
a failed upgrade automatically. Operators can inspect or manually select an
earlier revision with `helm history` and `helm rollback`; production rollback
must follow the target environment's normal approval boundary.