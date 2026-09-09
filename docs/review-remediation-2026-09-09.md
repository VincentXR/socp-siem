# Review remediation — 2026-09-09

Baseline: `0a0b349`. This change fixes the reviewed delivery/editor/query defects.

- Suppression reserves keys in ordered lock stripes and records a suppression window only after all configured sinks succeed. Failure releases the reservation without consuming the window. This fixes retry loss in the current process; it does not claim distributed or crash-atomic suppression. Durable alert IDs and outboxes remain responsible for replay deduplication.
- Tenant hot reload builds replacement rules, drains accepted work, then reads recovery state. Snapshot ownership, consistent checkpoint cuts, and multi-instance recovery still require separate work.
- `POST /detect-web/api/v1/rules/test` accepts `{rules, events}` with 1–20 validated rule definitions and 1–100 validated events. It uses fresh instances of the real rule implementations, in input order, with the authenticated tenant and read-only watchlist lookup. It never invokes the live engine or output sinks. It returns per-rule sample counts and generated sample alerts. Production suppression is intentionally absent. Sample history must be sufficient for window/baseline rules.
- Workbench tests unsaved drafts through that endpoint. The advanced sample sequence replaces the single sample input when supplied.
- SOAR visual conditions explicitly support one comparison with text, integer, or boolean literals. Unsupported operators and compound expressions stay in advanced editing. Removing the condition writes `false`, preventing an accidental unconditional branch. Object/array action parameters stay in advanced JSON and are not coerced into text by the scalar form.
- Applying advanced rule JSON cannot change create/copy/update identity.
- Alarm export forwards the same status and sort parameters as the list query.
- SOAR configuration properties now reside in `config`, satisfying the existing package gate.
- The rule editor regression test also found and fixed an existing Vue i18n error in the literal template-placeholder hint.

Validation before push: 57 frontend tests, type checking/build/artifact smoke, frontend lint/format, 21 Python tests, 120 static SOAR checks, migration/contracts/package/architecture/style/i18n/production configuration gates. Live SOAR probe was not run. Java regression tests cover failed-sink retry, concurrent suppression reservations, hot reload ordering, dry-run state/tenant/watchlist semantics, and export filters; the local environment lacks Maven/JDK 21, so their execution must be checked in GitHub CI.

Visual verification note: component tests exercise the real rule-copy editor and locale rendering; production build passes. No screenshot or full browser/Temporal acceptance is claimed.
