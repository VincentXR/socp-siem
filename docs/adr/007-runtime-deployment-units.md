# ADR 007: separate code modules, logical domains, and runtime workloads

Status: accepted; revised 2026-09-14

## Decision

SOCP does not have a fixed target process count. In particular, the former
goal of collapsing the repository into six JVMs is retired. A Maven module,
logical product domain, deployable artifact, runtime role, and independently
scaled workload are different boundaries and must not be forced into a single
number.

`build/runtime-topology.json` records the current executable registry, logical
domain ownership, completed consolidations, and a small list of candidates
that may be evaluated. Logical domains are labels for ownership and
observability; they are not instructions to colocate every member in one JVM.
The policy deliberately sets `fixedTargetProcessCount` to `null`.

The default local topology currently starts 14 backend processes. Production
may use more workloads because Search and Detection have independently scaled
API and Worker roles. That is intentional: partition ownership, backpressure,
long-running workers, and request latency are operational boundaries even when
the roles share one artifact.

## Completed consolidation

The former `detect-model` process was retired after its secondary-analysis
capability moved into the `detect-web` Worker role. The legacy
`/detect-model/**` gateway route, `detect_model` database, four-file Flyway
history, independent transaction manager, and `socp-detect-model` Kafka group
remain compatible. This reduced the default local topology from 15 to 14 JVMs
without a data migration.

## Candidates, not roadmap commitments

Only two combinations are registered for possible measurement:

- `alert-incident`: `alert-web` with `incident-web`;
- `soar-notify`: `soar-web` with `notify-web`.

Registration does not authorize implementation and does not imply that either
combination is beneficial. The existing processes remain the supported
topology until one candidate has its own passing evidence.

The former broad `response-integration` aggregate is not a deployment target.
SOAR/Temporal work, notification I/O, endpoint ingestion, threat-feed sync,
asset management, and ATT&CK content have different load and failure profiles.
Likewise, `report-web`, `ai-assistant`, and `soc-base` are not planned as one
JVM: batch analytics, external-model latency, and governance queries require
different scaling and security controls.

## Evidence gate

A candidate may replace its launchers only after commit-scoped evidence proves:

1. every public context path and authentication/tenant boundary is preserved;
2. each owning schema retains its Flyway history and transaction boundary;
3. a member or dependency outage does not make an unrelated capability fail
   liveness;
4. measured JVM, connection-pool, and startup savings justify the larger
   failure and release boundary without regressing latency or recovery.

`python build/verify-runtime-consolidation.py` validates the standing policy.
Evidence for one registered candidate can be checked explicitly with:

```bash
python build/verify-runtime-consolidation.py \
  --candidate alert-incident \
  --require-evidence \
  --evidence .cache/runtime-consolidation/alert-incident/evidence.json
```

There is no repository-wide aggregate promotion and no process-count success
metric. Capacity work should prioritize event throughput, lag, latency,
recovery, and resource limits over reducing the number of service names.
