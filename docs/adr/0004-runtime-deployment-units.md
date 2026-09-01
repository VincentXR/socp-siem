# ADR 0004: separate code modules from runtime deployment units

Status: accepted for incremental migration

SOCP keeps Maven modules aligned with domain ownership, but a local deployment
of every thin CRUD module creates unnecessary JVM and connection-pool cost. The
target runtime shape is six independently scalable units: gateway/frontend,
ingest-search, detection, alert-incident, response-integration and report-ai.

`build/runtime-topology.json` is the machine-readable source of truth for that
target. `build/verify-contracts.py` rejects duplicate assignments, missing
default services, unknown modules, or compatibility launchers placed into a
target unit. The former `asset-collect` and `hips-collect` launchers have been
retired because their ingress endpoints are owned by `asset-web` and
`hips-web`. Gateway rewrites preserve both legacy URL prefixes.

The migration is deliberately additive. Each aggregate application must keep
the existing context paths and platform contracts, and may call a remote
adapter when a module is still deployed separately. Database schemas remain
separate until a measured latency/startup/memory comparison justifies a merge.
Detection and ingest remain independently scalable because Kafka partition
ownership and backpressure are correctness boundaries.

Until an aggregate has passed contract and failure tests, the current
single-purpose launchers remain the supported topology. This avoids claiming
that a process count reduction exists before it is measured.

An aggregate is eligible to replace its launchers only after it proves all of
the following with executable evidence:

1. every legacy context path and authentication/tenant boundary is preserved;
2. each owning schema retains an independent Flyway history and transaction
   boundary;
3. a dependency outage cannot make an unrelated member fail liveness;
4. the grouped unit uses fewer JVM/connection-pool resources under the same
   workload without regressing latency or recovery semantics.
