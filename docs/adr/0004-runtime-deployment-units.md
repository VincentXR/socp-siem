# ADR 0004: separate code modules from runtime deployment units

Status: accepted for incremental migration

SOCP keeps Maven modules aligned with domain ownership, but a local deployment
of every thin CRUD module creates unnecessary JVM and connection-pool cost. The
target runtime shape is six independently scalable units: gateway/frontend,
ingest-search, detection, alert-incident, response-integration and report-ai.

The migration is deliberately additive. Each aggregate application must keep
the existing context paths and platform contracts, and may call a remote
adapter when a module is still deployed separately. Database schemas remain
separate until a measured latency/startup/memory comparison justifies a merge.
Detection and ingest remain independently scalable because Kafka partition
ownership and backpressure are correctness boundaries.

Until an aggregate has passed contract and failure tests, the current
single-purpose launchers remain the supported topology. This avoids claiming
that a process count reduction exists before it is measured.
