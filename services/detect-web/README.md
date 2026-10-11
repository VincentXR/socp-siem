# Detection service boundaries

Detection keeps catalogue writes separate from continuous event processing:

- `DetectionRuleService` owns catalogue queries, mutations, revisions and the
  rule-change outbox transaction. `RuleController` calls this service directly.
  Local reloads happen only after a successful commit. The reload opens a
  `REQUIRES_NEW` transaction because the completed write can still have bound
  persistence resources when its after-commit callback runs.
- `DetectEngineService` owns HTTP/Kafka admission, tenant budgets, durable
  completion callbacks and the runtime API consumed by the Kafka processor,
  health checks and metrics. All ingestion entry points reject API-only roles.
- `DetectionEngineLifecycle` owns live tenant/shard engines, scoped readiness,
  cache eviction and scheduled rebuild retries. Ingestion shares its fair read
  lock; draining, swaps and eviction use the corresponding write lock. Reload,
  assignment rebuild and revocation transitions use the same lifecycle monitor.
- `DetectionStateRecovery` owns journal replay, coherent checkpoint generations,
  partition offset vectors and state-unit leases. Leases are keyed by input
  topic, Kafka partition and state shard. Revocation prevents reacquisition;
  durable writes and journal finalization retain their fencing checks.
- `DetectionEngineFactory` compiles live rule documents and accounts for
  per-engine rule isolation. An invalid rule is isolated without suppressing
  healthy rules belonging to the same tenant.

A hot reload compiles candidates before taking the lifecycle write lock, then
waits for accepted work to drain before reading durable history and swapping
engines. Failed tenant rebuilds keep existing engines serving and remain queued
for recovery. Full assignment rebuilds keep the process recovery gate closed
until the assigned history is ready. Snapshot writes remain an optimization:
missing or mixed generations fall back to the durable journal window, and a
failed checkpoint write does not acknowledge undurable work.

API and worker roles retain the same routes, configuration keys and persistence
contracts. This class separation does not change process placement or Kafka
ownership. It does not establish multi-instance or production capacity evidence.

## Focused verification

From the repository root:

```bash
bash build/mvnw.sh -pl services/detect-web -am test \
  -Dtest=DetectionRuleServiceTest,DetectEngineHotReloadPersistenceTest,DetectEngineServiceTest,DetectionStateRecoveryTest,DetectionEngineLifecycleTest,DetectionRuntimeWiringTest,RuleControllerTest,DetectionRecordProcessorTest,KafkaEventConsumerTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Run the complete owning-module suite for changes to these boundaries. See the
[testing guide](../../docs/testing.md) and
[validation matrix](../../docs/validation-matrix.md) for database, rebalance and
full-stack evidence requirements.
