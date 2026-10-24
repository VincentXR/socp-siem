# Supporting investigation workspaces

The sidebar service status describes observed health coverage, never an implicit
all-clear. Missing or partial observations remain unknown; unsuccessful refreshes
retain their last evidence with a stale label. A complete observation older than
90 seconds is stale. Overview separates High and Critical alarm counts; each
opens the corresponding exact severity in the same seven-UTC-calendar-day window.
The Situation feed coalesces burst refreshes to at most one start per five seconds
with one trailing refresh. Its regular 15-second snapshot polling continues when
the live stream is paused or reconnecting. The displayed observation timestamp
tracks successful feed fetches or frames, not reconnect attempts.

Asset filters (`type`, `criticality`, `owner`) and Endpoint Agent `status` filters
are server-side, tenant-scoped and applied before pagination. Endpoint ONLINE and
OFFLINE use the same five-minute heartbeat-expiry semantics as inventory counts.
A selected endpoint's exact event ID opens Search independently of the first page
of inventory. Enrollment guidance describes the supported collector workflow and
does not imply that registration proves active collection or host isolation.

ATT&CK techniques open bounded rule/alarm evidence, local notes and explicit
investigation links. Coverage, observed activity and missing evidence remain
distinct. Compliance control details expose mapped rule status, assessment owner,
expiry and stored evidence; configuration coverage is not a compliance attestation.
IOC details provide escaped event-search and exact alarm-entity links. Reference
sets are ingestion enrichment data, with a separate link to detection watchlists.

Reports save daily JSON snapshots rather than promising a configurable document
builder. Daily ClickHouse queries and archive day names use UTC. Archives support
date and file-name filters; existing capped-list warnings and independent source
retry states remain visible. Generated snapshot download links remain available
independently of the archive's capped first listing.
Signed download URLs request an attachment response with the archive's UTF-8
file name, so the browser saves JSON instead of opening it inline.

The gateway retires upstream connections after ten seconds idle, with a
five-second background eviction interval. Deployments with shorter upstream
keep-alive limits must set `SOCP_GATEWAY_HTTP_POOL_MAX_IDLE_TIME` below that
limit; `SOCP_GATEWAY_HTTP_POOL_EVICTION_INTERVAL` controls background cleanup.
For the archive listing GET only, a connection closed before upstream response
headers is retried once. The retry preserves the verified request identity and
does not replay archive creation, other methods, response bodies already in
flight, HTTP error responses, or unrelated transport failures. A second failure
remains visible to the browser. This bounds recovery from an idle-close race;
it does not prove every upstream disconnect has that cause.

Notification delivery receipts use real server pagination (default 20 rows),
exact status/alarm/channel filters and deterministic timestamp/ID ordering.
Page, page size and filters survive URL navigation. Alarm, delivery and detection
rule links open the corresponding investigation surfaces. A logged receipt is not
proof of external delivery.
Successful and skipped receipts do not prompt operator recovery; synthetic test
and recovery markers remain plain text because they are not detection rules.

Published response execution confirms the exact immutable version, investigation
origin and submitted inputs. Permission changes, a different input draft, or
navigation invalidate an open confirmation. Within the active editor, a submission
without a receipt retains its request ID and frozen payload: an identical retry
reuses that ID, while a distinct submission requires an explicit warning that the
prior run may already have changed external systems. The visible unresolved list
is bounded to ten requests. Check authoritative run history before leaving the
editor; this browser-memory list is not a durable execution journal and does not
survive a full reload. A received run ID is acceptance evidence, not completion.

Alarm actions discard results after drawer dismissal, reopening, identity changes
or permission changes. Notification recovery holds a single confirmation slot and
rechecks the administrator permission and alarm identity before submission.

The supporting component tests exercise filters, cancellation, stale responses,
pagination, health state and burst refresh behavior. Playwright fixtures cover the
mobile/recovery flows but require a working Chromium runtime; collecting a fixture
is not evidence that the browser flow passed.
