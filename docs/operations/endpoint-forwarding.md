# Endpoint forwarding

HIPS collection writes the endpoint history, heartbeat update and
`t_endpoint_forwarding` publication intent in one database transaction. Search
ingestion is the next durable boundary; a receipt confirms Search acceptance,
not detection completion or alert creation.

## Producer request retries

Collectors may send `Idempotency-Key` on `POST /hips-web/api/v1/events`.
Use a stable key for one logical event and keep both the key and content on
transport retry. Keys contain 1–256 visible ASCII characters, without spaces;
invalid keys return 400. Omitting the header preserves distinct-event behavior,
even for identical bodies. A registered collector or verified signed service
identity is required for keyed requests; the legacy global ingest token has
no producer identity and cannot use this facility.

Deduplication is scoped to the authenticated tenant and producer. Collector
and service identities have separate namespaces. Credential rotation that
preserves the registered identity preserves the scope; renaming the producer
does not. Neither the body `agent` nor a caller-selected tenant supplies this
authority. The database stores SHA-256 hashes of producer/key and a versioned
fingerprint of the normalized collection input. JSON map key order is ignored;
list order and value changes remain significant. Server-generated event IDs,
receive times and tenant fields are not part of the HTTP input fingerprint.

Lookup and admission hold the database guard before history/heartbeat writes.
Concurrent identical requests return the original event ID and stored payload,
without adding history, refreshing heartbeat or consuming another queue slot.
A retry can therefore resolve an existing request even when the backlog is
full. Different content under the same scoped key returns 409, and a failed
transaction does not reserve the key. Corrupt stored receipts fail closed and
require repair instead of allocating a replacement event.

The key lives in the forwarding receipt: unresolved PENDING/PROCESSING/DEAD
work retains it, and DELIVERED work remains eligible for lookup until the
receipt is pruned (14 days after delivery, with bounded background cleanup).
Use a producer retry window shorter than that period. After pruning, the same
key can create a new event even if old endpoint history still exists; history
has its own [retention policy](#history-retention). This is a retained-receipt
deduplication guarantee, not an unlimited or exactly-once guarantee.

A keyed retry does not reset DEAD state or its attempt budget. Inspect and
explicitly requeue DEAD work as described below. `total`, `forwarded` and
`deliveryStatus` reflect current state rather than a cached original HTTP
response; the existing receipt can advance between reads.

## Delivery and limits

The request handler attempts forwarding after commit. A scheduler also picks
due work, at most five sequential deliveries per pass with a default 1-second
delay between completed passes. Each database claim locks one eligible row
using `FOR UPDATE SKIP LOCKED`, generates a new token, increments attempts and
leases it for 120 seconds. Another replica can recover an expired claim.
Completion/failure writes require the matching token. Tokens fence receipt
state; they cannot prevent a remote request already in flight.

The stored tenant supplies service authentication context; payload bytes and
`Idempotency-Key: hips:<event-id>` stay unchanged on retry. Search checks its
normal tenant/event identity and content fingerprint. HTTP success alone is
insufficient: one event must be acknowledged with zero skipped events.
Delivery is at least once within the configured retry budget and Search's
deduplication retention; there is no exactly-once claim.

Before JSON parsing, `socp.hips.ingest.max-body-bytes` separately limits the
raw HTTP request to 262,144 bytes by default (supported range 1–67,108,864).
Oversized requests return 413 before history, heartbeat or outbox writes,
including requests without Content-Length. The final envelope limit below
still applies after server metadata and JSON escaping are added.

Configuration under `socp.hips.forwarding`:

| Property | Default | Meaning |
|---|---:|---|
| `enabled` | `true` | Background publisher switch; synchronous attempts still run. Pausing may fill the backlog. |
| `delay-ms` | `1000` | Delay after each background pass. |
| `max-pending` | `100000` | Global outstanding receipt limit, including DEAD. |
| `max-tenant-pending` | `10000` | Per-tenant outstanding receipt limit, including DEAD. |
| `max-attempts` | `20` | Claim budget, including claims lost to process failure; supported range 1–1000. |
| `max-event-bytes` | `262144` | UTF-8 size of the final serialized envelope, including server metadata and JSON escaping; supported range 1–16777216. Keep at or below Search's event/body limits. |

Admission serializes through one database guard row so concurrent replicas
cannot overrun quotas. A full backlog returns 503 and rolls back the history
and heartbeat write. Failure retries use exponential delay from 2 seconds up
to 300 seconds. Attempt exhaustion becomes DEAD, including an expired final
claim. No unbounded in-memory work queue is used. Each HTTP attempt has a
5-second request timeout; shared client retry/backoff settings can extend a
delivery beyond one attempt and beyond a lease. A late callback is fenced,
and its remote request may still require Search deduplication.

With PostgreSQL RLS enabled, only global admission counting and background
discovery/retention use explicit system scope. Each delivery and its receipt
write execute in the stored tenant scope. The admission table is an explicit
RLS exemption because its sole row contains only `id=1`; receipt payloads and
tenant IDs stay in the RLS-covered forwarding table.

DELIVERED receipts older than 14 days are removed in batches of at most 100
after each background pass. This is best-effort maintenance, not an exact
deletion deadline. PENDING, PROCESSING and DEAD receipts are never discarded
by retention. Endpoint history retention is separate and cannot remove history
while any forwarding receipt still references the event.

## History retention

HIPS history is a retained investigation copy. The default policy makes rows
older than 30 days eligible based on server `received_at`, not agent event time.
Only history with **no forwarding receipt in any state** can be removed. Thus
PENDING/PROCESSING/DEAD work and retained DELIVERED request keys protect their
history. This does not remove the endpoint registration or its heartbeat.
The history list/count and hostname investigation APIs report retained rows,
not lifetime ingestion totals. Search/OpenSearch retention is independent.

The worker runs independently of the forwarding enabled flag. HIPS configures
two scheduler threads so a forwarding call does not occupy the sole scheduler
thread. Each history batch acquires the admission guard before history locks;
it skips a busy guard or locked history rows. The guard also prevents a new
receipt from being inserted between eligibility selection and deletion. Each
batch is its own transaction and requests a five-second SQL transaction timeout.
The worker never materializes more than the configured batch of event IDs.

Configuration under `socp.hips.history-retention` has corresponding
`SOCP_HIPS_HISTORY_RETENTION_` environment variables (hyphens become underscores):

| Property | Default | Meaning |
|---|---:|---|
| `enabled` | `true` | Automatic history deletion switch; does not pause forwarding or DELIVERED receipt pruning. |
| `days` | `30` | Minimum history age; supported range 1–3650 days. Referenced history can remain longer. |
| `batch-size` | `1000` | Maximum deleted IDs per transaction; range 1–1000. |
| `max-batches` | `10` | Maximum transactions started per pass; range 1–100. |
| `max-run-ms` | `10000` | Budget for starting further batches; range 100–60000 ms. |
| `delay-ms` | `60000` | Delay after a cleanup pass completes. |
| `initial-delay-ms` | `60000` | Delay before the first cleanup pass. |

The run budget does not interrupt an in-flight transaction or include a hard
deadline for connection acquisition and the final lag query. Monitor actual
duration and database timeouts. The default allows at most 10,000 deletions per
pass, not a guaranteed deletion rate or maximum disk size. Backlog can grow when
ingestion outpaces cleanup or unresolved work remains protected. Disabling the
forwarding publisher also pauses its DELIVERED receipt pruning, which can keep
referenced history beyond the configured age. Retention does not override that
protection to meet a deletion target.

Actuator metrics include `socp.hips.history.retention.lag.seconds` (oldest eligible
history beyond its deadline; -1 before a successful pass),
`socp.hips.history.retention.last.success.epoch.seconds`,
`socp.hips.history.retention.last.deleted` and
`socp.hips.history.retention.duration.ms`. Watch lag growth and a stale success
timestamp together; an old zero-lag sample does not prove cleanup is healthy.
Metrics are per process and have no tenant-controlled labels.

## Inspect and recover

Use an authenticated admin in the owning tenant:

1. Read `GET /hips-web/api/v1/endpoints/forwarding?status=DEAD&limit=100`.
   The limit is 1–500. Lists order by creation time and event ID and return
   bounded prefixes without payloads. Query PENDING/PROCESSING to inspect work
   that has not exhausted its budget.
2. Resolve the Search/dependency or payload problem before retrying. Error
   summaries intentionally omit remote bodies and arbitrary exception text.
3. Call `POST /hips-web/api/v1/endpoints/forwarding/{id}/requeue` for the chosen
   DEAD receipt. It is audited and rate-limited to five requests per minute.
   The operation preserves payload/identity, clears claim ownership and resets
   attempts. Missing, foreign or non-DEAD receipts return 409.
4. Read receipts again and verify DELIVERED or inspect subsequent failure.
   Requeue is not a success acknowledgement. Do not resubmit collection as a
   recovery substitute: keyless collection allocates a new history/event ID,
   and keyed replay does not reset DEAD work.

Do not silently delete DEAD work to free admission capacity. Requeue after
repair lets successful delivery release outstanding capacity. The receipt API
does not provide a discard operation.

## Migration and compatibility

HIPS Flyway V4 adds the outbox and admission guard. Run migrations before new
writers and reapply [tenant RLS policies](../../infra/postgres/tenant-rls.sql)
after migration so the new tenant-owned table is covered. Stop/drain old HIPS instances during rollout: they do not record
publication intent, so mixed old/new ingestion cannot guarantee this protocol.
Existing V3 history is preserved without automatic replay because its prior
forwarding outcome is unknown. Operator-created history via the separate
`/endpoints/events` route retains its local-only semantics.

V5 adds optional producer/key hashes, request fingerprints and a unique
tenant/producer/key index to the same RLS-covered table. Existing V4 receipts
remain keyless; their producer/request identity cannot be reconstructed from
payloads. Stop all HIPS writers before this rollout and restart only after V5
is applied: V4 writers ignore producer keys and acquire the admission guard
after heartbeat writes, whereas V5 collection acquires it first. Mixed writers
cannot provide keyed deduplication and can invert lock order. Keep V4 and V5
migration files immutable once deployed.

V6 adds the `(received_at,event_id)` history cleanup index. Deploy it before the
new worker. Its default policy applies to existing V3/V4 history as well as new
events: archive any required old history before rollout, or set
`SOCP_HIPS_HISTORY_RETENTION_ENABLED=false` until the desired policy is configured.
No legacy event is automatically replayed as part of cleanup. Existing receipt
references continue to protect history regardless of age.

Collection keeps `accepted`, `eventId`, `total` and `forwarded`, adding
`deliveryStatus`. Background progress may change the receipt between reads.
Producer retry safety requires the original caller key within the retained
receipt window above; the internal forwarding key alone does not deduplicate
keyless producer requests.

`EndpointForwardingPersistenceTest` and its PostgreSQL subclass exercise
atomic rollback/admission, competing claims, expired ownership, retry budgets,
requeue, transport recovery, bounded retention and populated V3/V4 upgrades.
They also exercise concurrent keyed admission across independent service
objects, content conflicts, rollback, full-queue replay and key expiry.
The PostgreSQL subclass also uses a NOSUPERUSER/NOBYPASSRLS role to verify
global admission, fail-closed unscoped reads and cross-tenant background work.
`EndpointForwardingRuntimeTest` boots HIPS and observes automatic timer-driven
retry after an unacknowledged HTTP response with a controlled transport fixture.
These are correctness fixtures, not throughput or high-availability benchmarks.
