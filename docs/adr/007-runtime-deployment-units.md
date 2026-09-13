# ADR 007: separate code modules from runtime deployment units

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

The first Detection consolidation is implemented: `detect-model` is no longer
an executable module or default process, and its secondary analyzer now runs
only in the `detect-web` Worker role. The legacy `/detect-model/**` gateway
route is rewritten to the Worker's internal `/detect-web/model/**` endpoint.
The existing `detect_model` database and four migration files are reused with
their original Flyway history; a second persistence unit and transaction
manager prevent cross-schema transactions. The `socp-detect-model` Kafka group
is also unchanged, so committed offsets survive the process cutover. This
reduces the default full topology from 15 to 14 JVMs without a data migration.

For the remaining target units, single-purpose launchers stay supported until
their aggregate has passed contract and failure tests. This avoids claiming a
process-count reduction before it is measured.

The Detection consolidation has context and compatibility coverage, but it
does not by itself validate the complete six-unit target. The topology status
therefore remains `contract-only-until-aggregate-apps-pass-integration-tests`;
the remaining aggregates still require failure-isolation and capacity
evidence.

`python build/verify-runtime-units.py` checks this assignment in normal CI.
Release automation must additionally run it with `--require-evidence` after
the topology status is changed to `validated`; the commit-matched manifest
must contain passing context, transaction, failure-isolation, and capacity
checks for all six units. A static topology check, a successful Maven build,
or a single-node Compose run is not a substitute for that manifest.

An aggregate is eligible to replace its launchers only after it proves all of
the following with executable evidence:

1. every legacy context path and authentication/tenant boundary is preserved;
2. each owning schema retains an independent Flyway history and transaction
   boundary;
3. a dependency outage cannot make an unrelated member fail liveness;
4. the grouped unit uses fewer JVM/connection-pool resources under the same
   workload without regressing latency or recovery semantics.
