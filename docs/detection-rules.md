# Detection rules

Detection rules consume normalized events produced by the ingest parser. A
condition reads a canonical field (`source`, `host`, `severity`, `raw`) or any
normalized/custom field in `event.fields` such as `src_ip`, `user`, `action`,
or `http_method`.

Stateful rule grouping is routed by its declared dimension. Use the same field
for `groupBy` and `routingField` (`keyField` is a compatibility alias). The
routed-v2 plan fans each source event out once per unique dimension required by
active rules. A rule whose grouping and routing fields disagree is invalid and
create/update returns HTTP 400.

## Rule catalogue queries

All paths below are under `/detect-web/api/v1` and use the authenticated tenant
and the normal `ApiResult` envelope. Catalogue queries read persisted rules;
they do not depend on a particular replica's live engine snapshot.

| Route | Contract |
|---|---|
| `GET /rules?page=1&size=20` | Full rule documents in `data.items`, with `total`, `page`, `size`, and `totalPages`. Pages start at 1, size is 1–500, ordered by logical rule ID. |
| `GET /rules?...&q=text&status=ACTIVE` | Filters before counting/paging. `q` is a case-insensitive literal substring of ID, name, or type (maximum 256 characters); `%` and `_` are literal. Status accepts DRAFT, TESTING, ACTIVE, DISABLED, or ARCHIVED. |
| `GET /rules?...&reference=name&referenceAlias=alternative` | Watchlist name match for `inlist`/`notinlist` in match, matchAny, steps, whitelist, or legacy allowlist. Names are trimmed and lowercased with `Locale.ROOT`, as in the engine. Either supplied name may match; substrings and extension metadata do not create dependencies. |
| `GET /rules/{id}` | One full rule from the current tenant, or HTTP 404. IDs are at most 128 characters. |
| `GET /rules/options?q=text&page=1&size=50` | Paged ID/name/type/status projection for selectors, size at most 100. No rule bodies. |
| `POST /rules/lookup` | Body `{"ids":["RULE-ID"]}` with 1–100 nonblank IDs, each at most 128 characters. Returns compact options for IDs present in the current tenant; missing IDs are omitted and duplicate IDs are collapsed. This is a read operation. |
| `GET /rules/active-techniques` | Sorted distinct ATT&CK IDs from ACTIVE rules' `mitre` and `mitreIds`. Draft, disabled, and archived rules do not contribute. More than 10,000 distinct stored technique groups or IDs returns an explicit error; results are never silently truncated. |

The no-page `/rules` compatibility response remains a bounded array (default
100, maximum 500). It is not the entire tenant catalogue. The workbench uses
server pagination and retains `q`, `status`, `page`, and `size` in the URL;
editor deep links fetch by ID. Alarm selectors search the compact endpoint,
watchlist drawers link to a server-filtered rule list, and compliance looks up only
the IDs declared by its frameworks in batches of at most 100. ATT&CK coverage
uses the ACTIVE technique projection. These are configuration coverage views,
not proof that a detection has executed successfully or that a control is verified.

### V25 catalogue metadata upgrade

V25 is a Java Flyway migration packaged below `db/migration`. It adds metadata
columns to `t_rule`, backfills existing rows in keyset batches of 250, and then
enforces non-null columns. It preserves `spec` byte for byte and freezes the
v2026.09.13 content-pack defaults needed to interpret legacy rules missing
status or ATT&CK metadata. Subsequent rule writes update spec and metadata in
the same row transaction. Do not edit this published migration or its frozen
defaults; future interpretation changes need a new migration and backfill.

Schedule a maintenance upgrade: stop all old Detection rule writers before
applying V25, then start only the upgraded binaries. Old binaries cannot fill
the new mandatory columns on insert and can leave stale projections on update.
Rolling mixed-version rule writes and rollback to an old writer are therefore
unsupported. Budget the database maintenance window for the complete backfill;
the 250-row batch limits memory, not transaction duration or table-lock time.
The PostgreSQL upgrade test covers more than one batch, unchanged original
specs, legacy content defaults, literal matching, and tenant isolation.

### V29 catalogue installation and mutation coordination

Detection V29 adds `t_rule_catalog`, one database row per tenant. Every catalogue
installation and rule mutation takes this row's lock. Initial installation,
content-pack upgrades, and their completed marker commit together; another
replica cannot read a partially installed catalogue or treat an in-progress
installation as complete. There is no process-local initialized-tenant registry.
The lock remains held in an owning service transaction, so rule bodies, user
revision rows, and rule-change outbox writes still commit or roll back together.
Revision numbers are allocated while holding this lock, including delete/restore.

The routing topology lock is acquired before the catalogue lock, matching the
first-pin path that reads the catalogue. Topology compatibility and revision
preconditions are checked in the same mutation transaction. A topology rejection
preserves the current revision token; a stale authoring token still returns 412.

Package ownership is assigned only by the installer or preserved from persisted
state. Display defaults do not label user rules as packaged content, including
user rules with IDs matching a built-in. Upgrade checks run under the same lock
as analyst edits and preserve committed customization. A DELETE revision keeps a
built-in absent across replicas and future pack refreshes until it is explicitly
restored or recreated. Removing an uncustomized packaged rule from a future
manifest does not itself delete that tenant's existing row.

The marker records pack ID, version, and a SHA-256 fingerprint of the canonical
manifest. Versions use increasing dot-separated numeric components (currently
release dates such as `2026.09.13`). Within the same pack ID, an older runtime
version, or changed content without a version increment, fails explicitly.
Keep the complete Detection database in consistency backups, including this
marker, revision history, rules, conflicts, and change outbox.

Upgrade the Detection fleet as a maintenance cohort: stop old API/worker rule
writers before V29 and resume with upgraded binaries. Writers that ignore the
namespace lock cannot coexist safely. Old binaries cannot be used to roll back
after newer content has been installed; use a compatible application release
or an explicitly chosen consistent database restore. Existing rules and
revisions are not rewritten by V29; the first access installs or reconciles the
pack in a transaction. The coordinator uses five-second statement deadlines
and a five-second transaction timeout when it owns the transaction; an outer
service transaction retains its own deadline. Lock/installation failures leave
no success marker and remain visible to the caller.

### Rule history and content conflicts

History and pending package conflicts are tenant-scoped and require the admin
or analyst role. Use these routes under `/detect-web/api/v1`:

| Route | Response inside `ApiResult.data` |
|---|---|
| `GET /rules/{id}/revisions?page=1&size=20` | `PageResponse` of revision metadata, newest first, with `revision`, `ruleId`, `status`, `source`, `changedBy`, and `changedAt`. The database projection excludes rule bodies. |
| `GET /rules/{id}/revisions/{revision}` | One immutable revision, including `spec`; a missing or other-tenant revision returns 404. |
| `GET /rules/content-conflicts?page=1&size=20` | `PageResponse` of pending conflicts, oldest first with a stable ID tie-breaker. |

Paged requests require `page >= 1`; `size` defaults to 20 and must be 1–100.
Supplying `size` without `page` is rejected. Omitting both parameters preserves
the legacy array format, including full rule specs in ascending revision order,
only while the result fits 100 records. Larger legacy requests return 400 with
instructions to use pagination; they never report a truncated complete history.
Empty histories and pages return empty results. Histories remain readable after
rule deletion, and restore continues to append a new revision.
`POST /rules/{id}/revisions/{revision}/restore` requires admin and
`rule:activate`: it restores the historical lifecycle state, including ACTIVE,
and can recreate a deleted rule. It does not force a separate activation step.

The workbench exposes paged **Revision history** from the rule table and editor.
It fetches one full revision only when selected, shows it beside the current
rule, and retains history when restoring. Analysts can compare; restoration
requires the server's admin role and `rule:activate` permission. The confirmation
identifies ACTIVE restoration and replacement of an unsaved editor draft.

V30 adds the tenant/status/time/ID index used by conflict paging. The migration
does not rewrite history or conflict records. It creates a regular transactional
index, so budget migration time and locking for the actual table size; the local
fixtures establish correctness, not a large-installation migration-time bound.

### Conditional rule authoring

Rules expose a server-managed `revisionToken` in current full-spec responses.
`GET /rules/{id}` and successful create/update/activate/restore return the same
token as a quoted strong `ETag`. Use the token of the **current rule you reviewed**,
not a historical revision token or the editable business `version` field:

| Mutation | Required precondition / behavior |
|---|---|
| `POST /rules` and Sigma import | Create only; an existing ID returns 409. They never overwrite through upsert. |
| `PUT /rules/{id}` | `If-Match: "<revisionToken>"`; an omitted lifecycle status is resolved from the locked current head. `enabled=true` cannot activate a disabled rule. |
| `POST /rules/{id}/activate` | The same `If-Match`, plus admin and `rule:activate`; archived rules return 409. |
| `DELETE /rules/{id}` | The same `If-Match`; an ACTIVE rule must first be disabled using a conditional update. |
| `POST /rules/{id}/revisions/{revision}/restore` | `If-Match` for the existing head, or `If-None-Match: *` only after observing that the current rule is absent. Concurrent recreation fails the absence check. |

Missing/blank `If-Match` or wildcard-only `If-Match: *` returns 428: checking
existence alone does not meet this API's authoring policy. Malformed headers,
headers over 2,048 characters, more than eight tags, or conflicting restore
preconditions return 400. A stale/deleted head returns 412, including a weak tag
that cannot match the strong validator. At least one tag in a bounded list must
match. These errors retain the `ApiResult` envelope; rule responses and
precondition failures use `Cache-Control: no-store`.
See [HTTP If-Match](https://www.rfc-editor.org/rfc/rfc9110.html#name-if-match) and
[428 Precondition Required](https://www.rfc-editor.org/rfc/rfc6585.html#section-3).

The tenant catalogue database lock covers the head read, precondition, lifecycle
decision, mutation and revision append. The owning service transaction also
contains the change outbox. A losing writer does not append a revision or a
change event. Deleted rules cannot be resurrected by an old update. Restoring or
recreating identical content produces a new token; a caller-supplied token in
the body is overwritten. Reads of legacy specs derive stable tenant-bound tokens
without rewriting them. New writes persist a nonce in spec metadata, which is
independent of the rule's execution-state fingerprint and routing topology.

On 412/428, the editor preserves the draft and offers download and explicit
reload; editable JSON never supplies the write precondition. It does not fetch a
new token and automatically retry an old draft. History restoration also keeps
the comparison on conflict and requires a refresh/review before another attempt.
Transport timeouts can leave a write outcome unknown: fetch the current rule and
history to reconcile before retrying. A broker change notification remains
asynchronous per replica; a successful authoring response is a durable write,
not proof that every worker has reloaded.

This is an intentional API compatibility change. Upgrade scripts/clients to
retain the returned token and send the precondition, and drain old API writers
before relying on conflict protection. Old instances can still accept blind
writes; request headers alone cannot fence them. No schema migration or topic
change is needed. Existing legacy history specs remain readable, and their
business fields are not rewritten to add tokens. The demo and routed migration
probe use conditional operations and disable an active fixture before deletion.

## Evaluation failures and dependency recovery

Failures reading required watchlist state leave the event retryable, including
transaction failures and unreadable persisted values. Repeated failures do not
open the bad-rule circuit or allow the event's durable completion. After the
dependency is recovered or repaired, evaluation resumes from the rolled-back
rule state; candidates from failed attempts are discarded. Detection reports
these failures with the `dependency` category. Custom rule adapters that read
external state must raise `RuleDependencyException` for the same guarantee.

The bad-rule circuit still isolates invalid rule arguments immediately and
repeated unclassified rule failures at the configured threshold. Dependency
failures contribute to `ruleFailures`/`ruleLastFailure`, but not to the rule-bug
streak (`ruleConsecutiveFailures`). A broken timing/metrics observer is logged
without changing detection results or opening that circuit. These semantics
do not claim exactly-once downstream delivery.

## Alert templates

`message` remains supported for compatibility. New rules should use an
`alert` object so the alert title and content are independently configurable:

```json
{
  "id": "AUTH-BRUTE-CUSTOM",
  "name": "SSH brute force",
  "type": "threshold",
  "severity": "HIGH",
  "keyField": "src_ip",
  "threshold": 5,
  "window": "60s",
  "alert": {
    "title": "Repeated login failures from {{event.src_ip}}",
    "description": "{{count}} failures for {{key}} within {{window}} on {{event.host}}"
  },
  "match": [
    {"field": "source", "op": "eq", "value": "auth"},
    {"field": "msg", "op": "contains", "value": "Failed password"}
  ]
}
```

Templates are deliberately logic-free. They support event values with
`{{event.field}}` (or `{{fields.field}}`) and rule evaluation values such as
`{{key}}`, `{{count}}`, and `{{window}}`. The legacy `{host}` and `{count}`
forms continue to work.

## Rule whitelist

`whitelist` is a rule-level exclusion list. Conditions in this array use the
same DSL as `match`; if any whitelist condition matches, the event is excluded
from that rule before it can trigger an alert. This is OR semantics across
whitelist rows, while each row is one condition.

Use `inlist` when the value is the name of a tenant-scoped dynamic watchlist:

```json
"whitelist": [
  {"field": "src_ip", "op": "inlist", "value": "trusted_ips"},
  {"field": "user", "op": "inlist", "value": "service_accounts"}
]
```

The existing watchlist API manages those values at
`/detect-web/api/v1/watchlists`. Literal exclusions can use operators such as
`eq`, `contains`, or `regex`. A whitelist does not disable the rule globally;
it only suppresses events matching its configured exclusions.

The rule editor loads its list choices from that same Detection watchlist API.
Search reference sets (`/search-config/api/v1/reference-sets`) are a separate
ingestion-enrichment catalogue: creating a same-named reference set does not
populate a Detection watchlist. The reference-set UI therefore does not infer
rule dependencies from matching names. UEBA watchlist drawers link to all
persisted rules using that watchlist, with server pagination.
Watchlist names and members use locale-independent lowercase. V25 does not
rename existing watchlist overlays or rewrite their stored member values.

### Watchlist mutations and catalogue limits

`GET /watchlists?includeValues=false` returns names and persisted member counts;
the workbench uses it for list pages and rule selectors. `GET /watchlists/{name}`
loads one list from the database. The compatibility `GET /watchlists` response
still includes values, but rejects a catalogue exceeding 10,000 total members
with HTTP 413; it never silently truncates. Member ordering is deterministic.

`POST /watchlists` accepts `{"name":"accounts","values":["alice"]}` and creates
only an absent effective list. New names accept Unicode letters/digits and
`_`, `-`, `.`; the names `.` and `..` are rejected. This keeps body-created IDs
addressable by the existing member/delete path endpoints. The existence check
runs in the same database
transaction as the write; an existing tenant overlay or inherited template
returns HTTP 409 without changing members. Names are normalized before checking,
and deleted lists may be recreated. The workbench uses this endpoint and retains
the form on conflict, so concurrent analysts cannot overwrite each other's new
list. Create, replace, append, and delete require analyst or admin permission.

`PUT /watchlists/{name}` replaces a list and `POST /watchlists/{name}` appends
distinct normalized members. The existing PUT replacement contract is unchanged;
callers intending creation should use the create endpoint. All writes return
the committed result. Appends read current durable values inside a transaction,
so stale per-node caches cannot lose another instance's additions. Database
namespace locks serialize mutations and quota checks for each tenant. Built-in
lists remain inherited until replaced or deleted; a deleted built-in retains a
tombstone. Deleting a custom list removes its row and frees its namespace slot.

Mutations allow at most 500 tenant-owned names (including template overrides
and tombstones), 10,000 members per list, 256 characters per input member, and
255 characters per normalized name. Inherited templates do not consume slots.
An append that would exceed the final member limit fails without partial writes.
Rule evaluation reads immutable sets through a per-node cache: the default
refresh is 1 second, bounded by both 10,000 entries and 64 MiB of estimated
weight. Oversized entries are read without caching. These weights are admission
estimates, not a heap-size or throughput guarantee. Operator detail reads bypass
the cache; other rule workers observe committed changes on cache refresh.

Detection V27 creates the namespace lock table; V28 backfills member counts in
batches while preserving payloads and tombstones. Stop old watchlist writers
before this upgrade; writers that ignore locks/counts cannot safely coexist.
Legacy rows are not deleted or truncated by migration. Before resuming writes,
inspect tenants with more than 500 rows or lists with `value_count > 10000` and
reduce them through explicitly chosen named replacements/deletions. An oversized
legacy namespace fails catalogue reads explicitly; named mutation APIs remain
available.

<!-- content-pack:start -->
## Content pack `socp-core-detections` (v2026.09.13)

The pack ships 39 versioned detections. Every rule declares its
data sources, MITRE ATT&CK mapping, an investigation guide and known false
positives; every rule also carries positive and negative test vectors that are
executed against the real rule engine by
`DetectionContentExecutionTest` and contract-checked by
`DetectionContentCatalogTest`.

| Rule id | Name | Severity | ATT&CK | Data sources | Version |
| --- | --- | --- | --- | --- | --- |
| AUTH-BRUTE | SSH 暴力破解 | HIGH | T1110 | auth, sshd | 1.1.0 |
| AUTH-BRUTE-SUCCESS | 暴力破解得手（关联） | CRITICAL | T1110, T1078 | auth, sshd | 1.0.0 |
| AUTH-PRIVESC | 权限提升 | CRITICAL | T1548 | auth, auditd, linux | 1.0.0 |
| CRED-DUMP | 凭据转储 | CRITICAL | T1003 | edr, auditd, sysmon | 1.0.0 |
| AUTH-NEW-ADMIN | New privileged account | HIGH | T1098 | linux, auditd | 1.0.0 |
| AUTH-ROOT-LOGIN | Direct root login | HIGH | T1078 | auth, sshd | 1.0.0 |
| LATERAL-RDP | RDP lateral movement | HIGH | T1021.001 | firewall, windows | 1.0.0 |
| LATERAL-SMB | SMB lateral movement | HIGH | T1021.002 | firewall, windows, edr | 1.0.0 |
| EXEC-POWERSHELL | Suspicious PowerShell | HIGH | T1059.001 | sysmon, edr | 1.0.0 |
| EXEC-SHELL | Suspicious shell execution | HIGH | T1059 | auditd, edr, falco | 1.0.0 |
| PERSIST-CRON | Cron persistence | HIGH | T1053.003 | auditd, linux, edr | 1.0.0 |
| PERSIST-TASK | Scheduled task persistence | HIGH | T1053.005 | sysmon, windows, edr | 1.0.0 |
| EVADE-LOGCLEAR | Log clearing | CRITICAL | T1070.001 | windows, auditd, edr | 1.0.0 |
| IOC-BLOCKED-IP | Blocked IP activity | CRITICAL | T1071 | firewall, proxy, auth | 1.0.0 |
| WEB-SQLI | SQL injection | HIGH | T1190 | nginx, waf, web | 1.0.0 |
| WEB-TRAVERSAL | Path traversal | HIGH | T1006 | nginx, waf, web | 1.0.0 |
| C2-BEACON | C2 beacon | HIGH | T1071.001 | proxy, edr, dns | 1.0.0 |
| EXFIL-LARGE | Large data exfiltration | HIGH | T1041 | proxy, netflow, dlp | 1.0.0 |
| DOS-FLOOD | Network flood | HIGH | T1498 | firewall, netflow, waf | 1.0.0 |
| CORR-FAIL-SUDO | Failed login followed by sudo | CRITICAL | T1110, T1548 | auth, auditd, linux | 1.0.0 |
| RISK-ENTITY-SPIKE | Entity risk accumulation | CRITICAL | T1078 | alert, risk | 1.0.0 |
| RARE-PROCESS | Rare process on host | HIGH | T1059 | sysmon, auditd, edr | 1.0.0 |
| RARE-DOMAIN | Rare destination domain | MEDIUM | T1071.004 | dns, proxy, edr | 1.0.0 |
| BASELINE-AUTH-VOLUME | Authentication volume baseline deviation | HIGH | T1078 | auth, sshd, windows | 1.0.0 |
| CORR-ATTACK-SIGNALS | Multi-stage host attack signals | CRITICAL | T1078, T1548, T1059 | auth, auditd, edr | 1.0.0 |
| EXEC-SUSPICIOUS-SHELL | Suspicious shell execution | HIGH | T1059 | edr, sysmon, auditd | 1.0.0 |
| FW-SCAN | Firewall scan | MEDIUM | T1046 | firewall | 1.0.0 |
| MAL-C2 | Suspected C2 traffic | HIGH | T1071 | proxy | 1.0.0 |
| PHISH-MAIL | Phishing mail delivery | MEDIUM | T1566 | mail | 1.0.0 |
| RANSOM-ENCRYPT | Ransomware encryption | CRITICAL | T1486 | edr | 1.0.0 |
| UEBA-AUTH-SPIKE | Authentication volume spike | HIGH | T1110 | auth | 1.0.0 |
| UEBA-NEW-DEST | First-seen destination | MEDIUM | T1071 | proxy | 1.0.0 |
| UEBA-NEW-GEO | First-seen login geography | HIGH | T1078 | auth | 1.0.0 |
| UEBA-NEW-PROCESS | First-seen process | MEDIUM | T1059 | edr | 1.0.0 |
| UEBA-USER-VOLUME | Account activity spike | MEDIUM | T1078 | audit, application, auth | 1.0.0 |
| WATCH-BLOCKED-IP | Blocked IP activity | CRITICAL | T1071 | firewall, proxy, auth | 1.0.0 |
| WATCH-CROWN-JEWEL | Crown-jewel access | HIGH | T1021 | firewall, netflow, application | 1.0.0 |
| WATCH-PRIV-ACCOUNT | Privileged account operation | HIGH | T1078 | audit, database, linux | 1.0.0 |
| WEB-ATTACK | Web attack indicator | HIGH | T1190 | web, waf | 1.0.0 |

### Authoring a new rule

1. Append an entry to `rules[]` in
   `services/detect-web/src/main/resources/detection-content/manifest.json`
   with `id`, `version`, `status`, `owner`, `description`, `dataSources`,
   `mitre`, `references`, `investigationGuide`, `falsePositives`, the
   executable `spec` (the typed DSL documented in this file) and at least one
   positive and one negative vector in `tests`.
2. Bump the manifest `version`. The pack version lands in every persisted
   rule document through `DetectionContentCatalog.enrich` as `contentVersion`.
3. Run `mvnw -pl services/detect-web test`: the catalog contract test fails
   on missing metadata and the execution test fails on vectors that do not
   reproduce the expected alert behaviour.

<!-- content-pack:end -->
