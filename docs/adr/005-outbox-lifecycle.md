# ADR 005: Durable outbox lifecycle

- Status: accepted
- Date: 2026-08-29

## Decision

The publishers listed below use the shared `OutboxRetryPolicy` for attempt
normalization, bounded exponential backoff, error truncation, and the
`PENDING`/`DEAD` decision. Each bounded context keeps its own persistence
entity and publisher because the hand-off stages differ:

- Search publishes canonical events to Kafka;
- Detection has an Alert Web stage followed by the original-alarm stage;
- Alert has a Kafka outbox plus per-destination delivery receipts;
- Rule changes broadcast configuration updates.

Detection routing has a separate transport outbox with unlimited retry by
default, optional attempt exhaustion, and its own terminal retention. Its
source receipts and delivery identities are described in the
[Detection state contract](../detection-state-semantics.md).

Each claim atomically checks status, due time, retry limit, and the attempt
count read by the scanner. The five operator-managed contexts below assign a
fresh UUID `claim_token` to every successful claim. Completion, retry, and
exhaustion writes require both `PROCESSING` and that token; relinquishing a
claim clears it. A callback from an expired claim cannot overwrite a newer
claim, including after an operator requeue resets attempts to zero. Tokens
fence database state; they cannot cancel a remote side effect already in
flight. Delivery remains at least once and requires downstream idempotency.

Ingestion, Alert event, and Alert delivery publishers use
`OutboxDeliveryExecutor` to acquire capacity before invoking the claim
callback. Each process permits one drain per publisher, admits at most the
configured concurrency (1–32), and stops admitting work at the drain deadline.
Already admitted work is joined and may finish after that deadline within its
transport timeout; the deadline is not a hard database/I/O timeout. Rule-change
delivery is sequential and checks the deadline between rows. These local
limits control resources; database claims provide cross-replica ownership.

For those four publishers, each stale-recovery or attempt-exhaustion update
locks at most 100 eligible rows using `FOR UPDATE SKIP LOCKED`. Recovery runs
at most once per 30 seconds per active publisher, so a large stale backlog
drains over multiple recovery passes. Business code supplies the payload and
acknowledgement callback and binds the tenant stored in the outbox row.

HIPS endpoint forwarding uses a separate JDBC receipt store and retry policy.
It claims one due or expired row at a time (at most five sequential deliveries
per background pass), uses 120-second leases and a fresh token on every claim,
and counts expired claims against the attempt budget. Its shared admission row
bounds global and tenant backlog across replicas; DEAD receipts continue to
consume capacity until recovered. See [endpoint forwarding](../operations/endpoint-forwarding.md)
for this protocol, migration and operator recovery. These receipts do not use
the five-context operator API or shared retry-policy defaults described below.
HIPS V5 also retains optional producer-request keys in these receipts. Collection
locks the admission row before any history/heartbeat writes; identical scoped
keys reuse the existing receipt before quota checks, while content conflicts
return 409. Receipt pruning expires the key together with the delivered intent.
This lock-order change requires stopping older HIPS writers during rollout.

### Upgrade compatibility

Nullable token columns are introduced by Search V12, Alert V21 (both event
and delivery tables), Detection V24 (alert), and Detection V26 (rule change).
Stop and drain the affected old publishers before applying these migrations
and starting upgraded writers. Mixed old/new publishers are unsupported:
old writers do not check tokens. Existing `PROCESSING` rows have null tokens
and remain eligible for normal stale recovery; migration does not reset their
attempts, payload, or confirmed delivery history.

## Rationale

A single generic entity would erase meaningful stage semantics and encourage
unsafe cross-context joins. Sharing the retry policy removes the genuinely
duplicated correctness logic while keeping each outbox's state machine
auditable and testable.

## Verification

Publisher unit tests cover exact token propagation, tenant scope, overlapping
drains, and acknowledgement failures. Executor tests cover admission capacity,
deadline expiry, and failure cleanup. Repository contracts run on H2 and
PostgreSQL for competing claims, expired callbacks, requeue/discard, bounded
maintenance, and competing row locks; PostgreSQL tests also migrate populated
old schemas. Commands are in the [testing guide](../testing.md). Pipeline and
chaos probes cover replay and restart across service boundaries separately.

## DEAD operational closure

For the five operator-managed contexts below, `DEAD` rows are never removed by
retention cleanup and are never replayed automatically. The shipped Prometheus
rules alert on positive DEAD-count growth over 15 minutes sustained for five
minutes. That alert can resolve while unresolved DEAD rows remain: operators
must inspect the current count and close each row through the tenant-scoped,
admin-only API for the owning service:

| Context | Inspect | Requeue / discard |
|---|---|---|
| Search ingestion | `GET /api/admin/outbox/ingestion/dead` | `POST /api/admin/outbox/ingestion/{id}/requeue` or `/{id}/discard` |
| Detection alert | `GET /api/admin/outbox/detection-alerts/dead` | `POST /api/admin/outbox/detection-alerts/{id}/requeue` or `/{id}/discard` |
| Detection rule change | `GET /api/admin/outbox/rule-changes/dead` | `POST /api/admin/outbox/rule-changes/{id}/requeue` or `/{id}/discard` |
| Alert event | `GET /api/admin/outbox/alarm-events/dead` | `POST /api/admin/outbox/alarm-events/{id}/requeue` or `/{id}/discard` |
| Alert delivery | `GET /api/admin/outbox/alarm-deliveries/dead` | `POST /api/admin/outbox/alarm-deliveries/{id}/requeue` or `/{id}/discard` |

Discard requests require `{"reason":"..."}`. Both requeue and discard are
rate-limited and audit-logged. Inspection deliberately returns failure metadata
without the event payload. Explicitly discarded rows preserve the operator
reason and previous failure for 30 days before cleanup; published/successful
retention remains independent.

The primary Prometheus gauges are emitted with the service namespace and an
`outbox` tag. The canonical alert names are
`socp.alert.outbox.dead.count` and
`socp.alert.outbox.oldest.dead.age.seconds`; the canonical detection names are
`socp.detection.outbox.dead.count` and
`socp.detection.outbox.oldest.dead.age.seconds`. Search uses the same canonical
dotted naming convention for its ingestion outbox, including
`socp.ingestion.outbox.dead.count` and
`socp.ingestion.outbox.oldest.dead.age.seconds`. A requeue is complete only
after the current DEAD count falls and the relevant pending-age signal returns
below its SLO; an HTTP 200 from the admin endpoint alone is not delivery
evidence.

This retention rule does not apply to Detection routing transport evidence:
`DetectionRouteMaintenance` removes route/source `DEAD` rows after
`socp.detect.routing.dead-retention` (default 90 days) in bounded batches.
Those tables do not expose the requeue/discard APIs listed above. Do not use
their retention or replay behavior as a substitute for an alert hand-off receipt.
