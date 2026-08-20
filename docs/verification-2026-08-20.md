# Verification Snapshot — 2026-08-20

This snapshot records reproducible engineering evidence for the feature-frozen
local stack. It contains no production-capacity or high-availability claim;
machine-specific raw JSON remains under `.cache` and is excluded from source
control.

## Runtime contract

- Kafka `socp-events`: 6 partitions.
- Detection: 3 PostgreSQL-backed instances, 256 MiB maximum heap each.
- Active runtime rules: 39, including 25 packaged Detection-as-Code rules.
- Detection delivery: at-least-once transport with deterministic alert
  identity and logically idempotent Alert Web creation.

## Correctness and recovery

| Probe | Observed invariant | Result |
|---|---|---|
| Three-instance rebalance, 3 cycles | Every partition owned once before/after; survivors owned all 6 during outage | PASS |
| Deterministic alert oracle | 12 expected IDs equaled 12 actual IDs; missing/unexpected sets empty | PASS |
| Journal terminal state | `pendingEvents` was `[0, 0, 0]` after recovery | PASS |
| PostgreSQL outage | Lag changed `0 -> 1 -> 0`; one expected alert; no pending rows | PASS |
| OpenSearch outage | Detection stayed available; post-recovery event became searchable | PASS |
| Detection Outbox replay | Rewound row returned to `PUBLISHED`; one logical Alert row | PASS |
| Golden Demo | Canonical event, four detection stages, entity risk, Incident, Notify, SOAR, report | PASS |

## Performance profiles

The host exposed eight logical CPUs. Both runs used three Detection instances,
six partitions, shared PostgreSQL state, and 39 rules.

| Profile | Events | Alerts expected/observed | Ingress EPS | Completed-path EPS | Detection P95 | Durable Alert P95 | Kafka lag |
|---|---:|---:|---:|---:|---:|---:|---:|
| realistic, one alert per 1,000 events | 10,000 | 10 / 10 | 811.10 | 138.32 | 60.10 s | 60.22 s | 0 |
| alert-heavy | 1,000 | 1,000 / 1,000 | 409.51 | 17.76 | 20.59 s | 52.64 s | 0 |

`Detection P95` is the Detection alert timestamp minus the trigger ingest
timestamp. `Durable Alert P95` is the Alert Web row creation timestamp minus
the trigger ingest timestamp. Both profiles accepted every event and had zero
alert shortfall.

## Reproduce

Use the commands in [Benchmark](benchmark/README.md), [Failure matrix](chaos/README.md),
and [Operational Demo](demo-checklist.md). The exact Kafka commit and crash
semantics are defined in [Detection State Semantics](detection-state-semantics.md).
