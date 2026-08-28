# Service readiness matrix

Readiness is recorded by dimension. A green correctness result on a local or
CI stack is not a production HA, capacity, or external-integration claim.

| Service group | Correctness evidence | Security boundary | Operational readiness | External acceptance |
|---|---|---|---|---|
| Gateway/auth | Unit + browser flows | JWT/JWKS, CSRF-origin check, login/service-token rate limit, security headers | Redis-backed distributed limits; prod fail-fast | IdP, proxy trust, certificate and secret rotation remain deployment-owned |
| Ingest/search | Pipeline + duplicate/recovery chaos | Collector credential binds source and tenant; schema validation follows authentication | Durable ingest outbox, Kafka/OpenSearch recovery | Collector inventory, OpenSearch sizing/backups remain deployment-owned |
| Detection | Rule vectors, journal/outbox tests, multi-instance rebalance oracle | User mutations require role; internal side effects require signed service identity | Durable journal/outbox, partition ownership and lag evidence | Capacity/SLO and broker/database HA remain deployment-owned |
| Alert/incident | Idempotency, normalized timeline, downstream receipt tests | Tenant-scoped queries and negative authorization tests | Durable fan-out receipts, stale-claim recovery and replay | ClickHouse retention, notification vendors and case workflow acceptance remain deployment-owned |
| SOAR/notify | Action/dispatch tests; human approval contract | Service-only evaluation/notify boundaries; simulation rejected in prod | Durable execution/dispatch state | Real connector/vendor certification is required before production use |
| Assets/HIPS/threat/ATT&CK | CRUD/import/tenant tests | Role-gated mutation and ingest identity where applicable | PostgreSQL-backed state | CMDB, endpoint agents, feeds and content lifecycle remain operator-owned |
| Reports | Query/archive tests | Tenant-scoped report paths | ClickHouse/object-store adapters | Retention, object-lock, restore and report SLO are deployment-owned |
| AI assistant | Versioned dataset executes the real evidence composer; citations and human approval are evaluated | Evidence is treated as untrusted; no automatic containment | Deterministic fallback and bounded tool/timeout budgets | LLM model/vendor quality, privacy and safety acceptance remain deployment-owned |
| Compatibility collectors | Local/demo tests | Production guard rejects simulation | Not a production runtime | Replace with managed collectors/agents |

## Release interpretation

- `correctness evidence` means the repository has an executable oracle for the
  named invariant; it does not imply all workloads or failures were explored.
- `security boundary` means the owning service re-checks authorization and
  tenancy. Gateway policy is defense in depth, not the sole authority.
- `operational readiness` describes state, recovery and observability owned by
  this repository. Backup restore, multi-zone failover and SLO burn-rate
  operations require deployment-specific evidence.
- `external acceptance` must be closed by the target environment. Until then,
  the service is integration-ready or preview, not generally production-ready.

The authoritative pass/fail commands and cadence live in
[`validation-matrix.md`](validation-matrix.md). Evidence is commit-scoped: a
workflow result from an older commit must not be presented as proof for HEAD.
