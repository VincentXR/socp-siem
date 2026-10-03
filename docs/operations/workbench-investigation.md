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

Notification delivery receipts use real server pagination (default 20 rows),
exact status/alarm/channel filters and deterministic timestamp/ID ordering.
Page, page size and filters survive URL navigation. Alarm, delivery and detection
rule links open the corresponding investigation surfaces. A logged receipt is not
proof of external delivery.

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
