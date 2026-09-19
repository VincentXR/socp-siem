# Flyway checksum drift and `repair` runbook

Published Flyway migrations in this repository are **append-only**: once a
version has shipped, its file text is immutable. `build/verify-migrations.py`
(`check_published_migrations`) enforces this against git history and rejects any
committed or uncommitted rewrite of a version that already shipped, allowing only
a byte-for-byte restore to one of the file's own published blobs. This runbook
explains why that rule exists, the real incidents that triggered it, and how to
recover an environment whose `flyway_schema_history` checksum has already drifted.

## The failure mode in one sentence

Editing an applied migration changes its checksum, so every database that already
recorded that version fails `flyway.validate-on-migrate` (default **true**, and no
service overrides it) on the next boot — a loud, fail-closed
`FlywayValidateException` — **and** the edited statements never re-run on those
databases, so the schema silently diverges from what git claims.

That second half is the dangerous part: an index or column you "added" inside a
published `V1` looks present in the repository but does **not** exist in any
already-migrated production database, because `V1` will never run there again.

## The three historical incidents

| Module | File | What happened | How it was remediated (revert + replay) |
| --- | --- | --- | --- |
| alert-web | `db/migration/V1__init.sql` | `source_alert_id` column + `uq_alarm_tenant_source_alert` index appended in place (commit `50ba514a`) | V1 restored to its published text; the idempotency column/index delivered by `V4__alert_source_idempotency.sql` and the `NOT NULL` tightening by `V20__alarm_source_alert_id_not_null.sql` |
| threat-web | `db/migration/V1__init.sql` | hot-path index appended in place (commit `e351e355`) — and because V1 never re-ran, the index was effectively absent on existing DBs | V1 restored; index delivered by a new `V4__ioc_tenant_value_index.sql` |
| threat-web | `db/migration/V2__stix_indicator_metadata.sql` | `confidence` rewritten from `DOUBLE` to `DOUBLE PRECISION` (commit `afc86f48`) — a real DDL-text change | V2 restored to its published blob; the type change delivered by a new `V5__stix_confidence_double_precision.sql` |

The same class of drift was caught in detect-web `V2__detection_state.sql` and in
comment-only edits to soar-web `V6/V10/V20/V22` (Flyway strips comments from the
checksum only for lines it treats as comments, so even comment churn can move the
stored checksum — the gate allows a restore, not a rewrite).

## Remediation is always two halves

1. **Restore the file.** Return the edited migration to its published text
   (`git checkout HEAD -- <path>` when the pending edit is the rewrite, or the
   pre-rewrite published blob when the rewrite was already committed). This is
   what makes the gate report it as *restored* rather than *failed*.
2. **Replay in a new version.** Deliver the intended schema change as a higher
   `V(n+1)` so it actually applies to every fleet, new and existing.

Do **not** fix a missing object by editing the old version, and do **not** treat
`repair` as a way to run the missing DDL — it is not.

## When `flyway repair` is the right tool

Run `repair` only to realign the metadata of databases that already applied the
**drifted** blob (the one that was committed before you reverted it), so their
stored checksum matches the now-corrected file and validate stops failing. After
a revert, existing databases still carry the drifted checksum in
`flyway_schema_history`; `repair` overwrites the stored checksum to match the
current file and removes any failed-migration rows. It does **not** re-execute
applied migrations and does **not** apply a new version — you deploy that normally.

Typical trigger: after shipping the revert + new version, an environment throws

```
FlywayValidateException: ... Migration V1__init.sql checksum mismatch
  (applied: -1234567890, resolved: 987654321)
```

on startup. That is a metadata mismatch on a version whose schema is already
correct — exactly the case `repair` resolves.

```bash
# Per database, using the migration role (never the restricted runtime role).
# alert-web -> database "alert", threat-web -> database "threat".
flyway \
  -url="jdbc:postgresql://${SOCP_PG_HOST:?host}:${SOCP_PG_PORT:-5432}/alert" \
  -user="${SOCP_PG_MIGRATION_USER:?set the migration user}" \
  -password="${SOCP_PG_MIGRATION_PASSWORD:?set the migration password}" \
  -locations=filesystem:services/alert-web/src/main/resources/db/migration \
  -table=flyway_schema_history \
  repair
# Then start the service; migrate applies the new V(n+1) and validate now passes.
```

`repair` is safe and idempotent for the checksum/failed-row concerns, but read
what it prints: it also *removes* history rows for migrations it considers
failed, so run it deliberately per database, in the maintenance window, before a
rolling start — not as a reflex.

## When NOT to repair

- **A schema object is genuinely missing on existing DBs** (the threat-web
  hot-path-index case): the fix is a **new version** that creates the object.
  Repairing the checksum of the reverted file will make validate pass but leaves
  the object absent. Repair aligns metadata; only a new migration changes schema.
- **A failed (partially applied) migration on Postgres DDL** that already
  committed or rolled back cleanly: prefer understanding the half-applied state
  first; `repair` clears the failed row but does not roll back applied DDL.
- **Anything you have not reverted yet.** Repairing against a still-edited file
  just records the wrong checksum as canonical and re-breaks the gate. Restore
  the published text first, then repair.

## Decision rule

| Situation | Action |
| --- | --- |
| Committed edit to an applied version, caught by the gate | Restore published text + deliver a new `V(n+1)`; deploy; run `repair` on drifted DBs |
| Boot fails with `checksum mismatch` on a reverted file whose schema is correct | `repair` (metadata only) |
| Column/index/type change that never reached existing DBs | New `V(n+1)` only; `repair` is not a substitute |
| Net-new schema work (nothing was ever edited) | Just add the next version; no repair |

The durable invariant: **the migration sequence only grows; `repair` fixes
metadata, migrations fix schema.** Keep both in that order and the two fleets
(reverted file + new DBs) stay convergent.
