# Workbench frontend guidelines

This document owns shared workbench interactions, internationalization, and operator-form
conventions. Feature-specific behavior remains with the feature code and API
contracts.

## Log investigation

The search editor keeps a draft separately from the applied query. Enter runs
the draft, Shift+Enter inserts a line, and IME confirmation does not submit.
Until a search is run, paging, exports, and result links retain the applied
query; a visible notice identifies unapplied edits. Field pivots preserve OR
grouping, quoted values, and pipeline order, then return focus to the editor.

Search links include the applied `q`, `range`, fixed UTC `to` boundary, and
non-default `size`. Browser back/forward and reload restore that time window;
running a new relative-time search advances it. Restoring a numbered page
replays at most 20 cursor pages. This is a fixed filter window, not a storage
snapshot: late arrivals and retention can change the matching events.
Table-header sorting applies to the loaded page only, as labeled in the UI;
use an SPL `sort` command for server-side ordering across pages.

Alarm evidence links carry their event query directly and use `range=all`,
so historical evidence is not hidden by the default recent-time window.
Search controls precede the field dictionary on narrow screens.

## Table and URL state checks

Each Element Plus table using `sortable="custom"` must bind its own
`sort-change` handler. `python build/verify-frontend-conventions.py` checks
that wiring per table, ignoring comments and script examples. It supports
shorthand and long-form event bindings; it does not execute handlers or infer
dynamic sortable expressions. Component/browser tests must verify the actual
sort order and whether it applies to the current page or the full result set.

URL-synced lists reject invalid page tokens, preserve unmanaged deep-link
keys, suppress navigation echo writes, and scope watchers to the owning
route. `useListQuery.test.ts` and `useAlarmQuery.component.test.ts` exercise
these behaviors through `pnpm test`; the Python gate does not infer them from
implementation tokens such as `nextTick` or `Number.isInteger`.

Reference-set entry writes retain the set ID captured when the operation
starts, including while a deletion confirmation is open. Batch entry writes
keep the drawer open and block switching sets until completion; rejected
entries remain selected for retry. Removing one set does not close a different
set opened while its confirmation was pending.

## Internationalization

The workbench uses Vue I18n 11 in Composition API mode. The global instance is
in `frontend/apps/workbench/src/i18n/index.ts`; `locale-manager.ts` resolves
preferences and synchronizes Vue I18n, Element Plus, `<html lang>`, the
document title, and local storage.

Feature code uses semantic keys and locale-aware formatting:

```ts
const { t, d, n } = useI18n()
t('common.actions')
d(event.timestamp, 'dateTime')
n(total, 'integer')
```

Existing compatibility-facade callers remain supported. New code uses Vue
I18n's global Composition API directly.

### Locale resolution

Preferences resolve in this order:

1. authenticated profile locale;
2. compatibility profile-locale keys;
3. `socp-locale` in local storage;
4. browser preference;
5. `zh-CN`.

Only `zh-CN` and `en-US` are accepted. Once authenticated, the gateway is the
source of truth. Local auth can map usernames through `SOCP_AUTH_LOCALES`; OIDC
uses the provider locale claim. The gateway validates the value, stores it in
the signed session, forwards trusted `X-Socp-Locale`, and exposes it from
`/auth/session`. Requests send `Accept-Language` unless explicitly overridden.

Use stable keys such as `threat.importSuccess` and `common.delete`. Keep enum
and protocol values stable and translate them only at presentation. Do not
translate user-authored names or descriptions. Server errors expose a code and
parameters; the client resolves `errors.*`.

Chinese and English packs must have identical structure. Run:

```bash
python build/verify-frontend-i18n.py
cd frontend/apps/workbench && pnpm test && pnpm verify
```

The gate checks key parity, direct locale branching, known mojibake markers,
legacy keys, and literal key references. Component tests cover preference
fallback, interpolation, date/number formats, metadata, and switching.

## Form conventions

### Choose the container by editable field count

Ignore read-only rows when counting.

| Fields | Container | Width |
| --- | --- | --- |
| 1–2 | dialog | `440px` |
| 3–4 | dialog | `520px` |
| 5–8 | dialog | `640px` |
| 9–14 | drawer | `min(720px, 96vw)` |
| 15+, or a condition builder | inline workspace | content width |

Bulk paste/import uses `640px`; result tables use `720px` or wider. Fixed
desktop widths remain safe because the global dialog rule caps small screens.

### Put input labels above controls

Editable forms use `label-position="top"`, avoiding locale-dependent fixed
label columns. Read-only descriptions may keep side labels because operators
scan rather than fill them.

### Use the shared form structure

- `FormSection.vue` groups roughly eight or more fields into operator-named
  sections; do not section a small dialog.
- `FormGrid.vue` lays out one to four columns and collapses responsively.
- `FormField.vue` owns the label, required marker, help, unit, and field error.

Pair short fields; give textareas and long values a full row. Attach validation
to the offending field:

```vue
<FormField :label="t('…')" required :error="errors.name">
```

Use form-level `ActionFeedback` only for whole-operation failures such as a
rejected save or network error.

`useFormDialog` protects an open dialog during pending writes even when its
fields have not changed: closing, route navigation/query changes and browser
unload are guarded until the operation settles. Close a successfully saved
dialog before navigating to its resulting resource. A failed operation keeps
the inputs available for correction.

Notification configuration and test delivery are separate operations. Retain
the acknowledged channel ID and saved baseline before sending a test. If that
test fails, state that the configuration was saved and keep the form open;
retry unchanged settings without creating or updating the channel again.
Changed settings update the same saved channel before another test. Transport
failures can leave remote test acceptance unknown, so the operator reviews the
result before retrying. Local logging must not be described as external delivery.
Discard obsolete catalogue reads so a pre-save refresh cannot replace the
acknowledged channel with its older response.

### Keep help text actionable

Help text should explain a downstream effect, unit/format, consequence, or
non-obvious constraint. Do not repeat the label as a placeholder. Describe a
validation pattern in words rather than showing its regular expression.

### Do not make operators edit raw JSON

Render objects as key/value rows and arrays as editable lists. A collapsed raw
JSON fallback is acceptable only for values the typed UI cannot represent; it
must not be the primary control.

## Asset investigation and import

Asset links use `/assets?assetId=<internal-id>` and fetch the asset directly,
independently of inventory filters/page. Related endpoints load only when a
detail is opened, through the paged exact-identity endpoint resource. Never infer
absence or a total from a tenant inventory prefix. Show association read failures
separately from an empty successful result, and retain a retry action. The alarm
and endpoint search pivots explicitly use the asset IP.

Capture an edit/delete target before awaiting a request or confirmation. Retain
failed edit input, freeze pending writes and establish the returned asset before
refreshing background reads. Selection/history changes must not discard an open
dirty editor, overwrite an acknowledged asset with an obsolete read, or reset a
restored list page through the delayed search watcher.

Asset CSV/JSON imports have a 2 MiB client file bound and 1–500 row bound matching
the service batch limit. Validate before opening the preview, show the reviewed
row count and first 20 rows, and submit only after the operator confirms. Keep
the reviewed rows and dialog during pending/failed submission; display returned
imported/skipped counts and row errors after acknowledgement. A failed transport
does not prove that no row was created: explain the uncertainty before a manual
resubmission, which can duplicate previously accepted rows. This UI does not
claim atomic or idempotent batch persistence.

## AI answer reset

Reset cancels the current answer request, clears the question/result/error and
immediately permits a new question. Late success, failure and completion
callbacks must check request ownership before changing visible state. Leaving
the view also cancels the request. Browser cancellation does not assert that
server-side model work has stopped.

The investigation URL (`alarmId`, with legacy `alertId` read compatibility)
owns the selected alert. Reusing the route loads the new context; editing the
input immediately invalidates old results and polling. Submitting a draft
updates the URL once, and the route watcher starts its investigation. A response
for a different alert is rejected. The context banner returns to the URL's
alert, independently of an unsubmitted draft.

Appending a summary is a durable write. Switching investigations does not
pretend to cancel it: the prior write remains pending, a status explains the
wait, and additional appends are disabled until it settles. Its result/error
can only update the investigation version that initiated it. Viewer/approver
navigation and alert drawers hide AI actions, matching the backend's
admin/analyst boundary.

Suggested question tags wrap within the available card width. On narrow screens,
the investigation heading and approval label stack, and context/actions wrap;
long prompts or identifiers must not disappear beyond a clipped card edge.

## Overview freshness

The overview sources refresh independently. Its freshness label uses the
oldest successful query timestamp across recent alerts, alert statistics,
case statistics and service health. Before every source has returned data it
shows a waiting label. Clicking refresh or receiving an error never creates a
success timestamp; failed refreshes retain previous data/time with an error.
This is client fetch freshness, not an event ingestion watermark or a shared
transactional snapshot. The refresh button reflects actual in-flight queries.

## Report sources and saved files

Daily summary, seven-day trend and archive listing load independently. A failed
source has its own retry and retains any previously successful data with an
error; it must not discard a successful sibling response. Daily and trend
fallback provenance are shown separately. Chart loading must not continue after
unmount, and the severity distribution includes INFO. Charts stack on narrow
screens.

Archive counts describe the returned list, not all stored files. Respect the
server's `truncated` flag as a possible incomplete listing and offer a date
filter. Derive dated prefixes from the tenant-owned prefix returned by the
server; client filtering is not a tenant authorization boundary. Generating a
report selects its returned day and retains a direct download for the returned
key, even when that date also exceeds the list cap. An in-flight generation remains a server operation after navigation,
but its eventual completion must not start hidden reads or show page-local
success/error messages.

Show each file's date beside its name so same-named daily files remain
distinguishable. Report tables use the available container width; wide columns
scroll inside the table so the fixed download column stays visible on mobile.
Fixed cells need an opaque surface to prevent underlying values bleeding through.

Downloads open a blank tab during the click gesture, detach its opener, and
then request the signed URL. Only a matching file key and HTTP(S) URL may
navigate that tab. Failed/cancelled requests close pending blank tabs; leaving
the report page does not close an already completed download tab.

After report generation, retain the returned `archiveKey` as a direct download
action for the current view. This action must survive a capped or failed archive
listing and a later failed generation, so the last successfully created snapshot
remains reachable independently of list refresh. Use the same popup, URL
validation and cancellation behavior as existing archive downloads.

## UEBA detail and score reads

Entity detail reads belong to the currently selected entity. Cancel and ignore
obsolete responses when selection changes, the drawer closes or the view
unmounts. Show loading and retryable read failures inside the drawer; a list
summary remains a preview while the detail read is pending or failed.

Score simulation answers belong to an immutable input snapshot. Clear the old
answer immediately when any input changes, including slider movement before
its final change event. Obsolete successes and failures cannot replace the
current result. Keep scoring errors and retry within the score panel, separate
from entity/list errors. Ranking and score columns stack on narrow screens.

## Endpoint investigation

Endpoint links use `/endpoints?endpointId=<internal-id>` with direct detail
lookup, independently of the current inventory page. Closing preserves list
filters and browser history restores selection. Matching assets and same-hostname
events use separate tabs and server-side pages, with independent loading, empty,
error and retry states. Do not infer absence from a global first 200/500 rows or
select only the first matching asset. Show the hostname reuse/identity limitation
beside history and allow navigation to each matching asset's direct detail.

Keep detail and association responses fenced to the current selection. Capture
and name the unregister target before confirmation; freeze duplicate mutations,
drawer closure, navigation and unload during the write. Acknowledged unregister
removes that row locally even if background refresh fails, and closes only the
matching selection. Keep the action and its failure feedback in the fixed drawer
footer so long history pages cannot scroll them out of view. Stats failure is an unavailable state, not zero endpoints.

## Case investigation

Case links use `/cases?caseId=<internal-id>` and load the detail resource directly,
independently of the current list page or filters. Row selection updates that URL;
closing the drawer preserves list filters, and browser history/reload restores
selection. Missing or failed details show a retry action rather than an empty
drawer. Detail and timeline requests cancel and ignore obsolete responses.

Keep status/assignee drafts while refreshing the list or paging timeline events.
Changing or closing the selected case requires unsaved-change confirmation;
pending writes freeze those fields and block navigation. Apply the acknowledged
case and baseline before refreshing background reads, so a read failure cannot
misrepresent a successful write. Timeline pages expose total and page controls
instead of presenting the first 100 events as complete. Export is restricted to
writers, describes its scope as all cases, and surfaces failures in the page.

## Rule catalogue completeness

Use `listRulePage` for server-filtered rule tables and watchlist dependencies, `getRule`
for an editor deep link, and the compact options/lookup endpoints for selectors
and known IDs. Do not derive a total, dependency list, or coverage result from
the first bounded rule page. Keep list filters/page/size in the route; retain
them when returning from the editor. A rule dry-run labelled "current page"
must not imply that it tests the entire catalogue. Coverage must use ACTIVE
rule metadata and distinguish mapping from operational verification.

Detection condition choices must load from the Detection watchlist API, which
owns `inlist/notinlist` membership. Search reference sets only enrich ingested
events; identical names do not make the two catalogues interchangeable.
Load watchlist summaries without members and page the bounded catalogue in the
workbench. Fetch members only for the selected drawer; discard obsolete detail
responses and keep a retry action on load failure. Apply the committed mutation
response to both counts and members, so a failed follow-up list refresh cannot
make a successful append appear to have failed. Preserve member input on write
failure and block changing the target while saving.

Restoring an authenticated session must retain any permitted editor path and
its query. Only an inaccessible menu may redirect to a fallback; clicking a
navigation item explicitly may return to its list.

Rule saves freeze the form and raw JSON until the write settles, reject duplicate
submissions, and block route/identity changes even when the draft was unchanged.
Pass the pending-write state to `useUnsavedChanges` so browser unload is guarded
as well. A discard confirmation cannot cancel an already-sent server write.
Release the guard after settlement, preserve the draft on failure, and establish
the returned saved spec as the baseline before navigating to a new edit route.
This navigation protection does not replace server-side concurrent-edit checks.

Keep a rule's loaded `revisionToken` outside editable JSON and send it with each
update, activation, deletion or restore. On a conflict retain the draft, offer a
download and an explicit reload with unsaved-change confirmation; never adopt a
new token and silently resend old content. Revision history pages metadata and
loads only the selected full spec. Compare it with the current head before
restoring, identify ACTIVE and draft-replacement effects, and keep restoration
subject to the server's permission and version checks.

Threat indicator edits, lifecycle changes, deletion and imports share one pending
operation guard. Keep errors beside the active dialog or detail and preserve
input after failure. Apply a mutation response only to the same selected ID;
refreshing a catalogue must not replace a newer selection. A failed statistics
request must not show a successful zero count.

Asset and indicator imports accept 1–500 CSV/JSON rows within 2 MiB. Parse quoted
CSV newlines and escaped quotes, reject duplicate headers and inconsistent row
widths, and validate every indicator before showing a preview of at most 20 rows.
Do not silently discard missing values or replace invalid severities with a
default. Show partial-import errors and retain an unconfirmed preview with a
check-before-resubmission message after transport failure. Pending import writes
block closing or navigating away; client validation does not replace the owning
service's validation and tenant authorization.

## Compliance mapping refresh

Publish framework definitions, resolved rule states and computed coverage as one
result. A failed refresh must preserve the last successful result and show that
it is stale; before any successful read, counts are unavailable rather than zero.
Recalculation uses the last successful framework catalogue and stays disabled
until that catalogue and its result exist. Cancel the request chain on unmount,
and stop before subsequent rule batches or coverage calls even if a transport
ignores cancellation. Keep the distinction between mapping, enabled rules and
verified assessment visible; these counters do not establish compliance.

## ATT&CK reads and local notes

Tactic filtering owns its technique-list request and clears an obsolete list
while loading the new selection. Keep global coverage refresh independent of
catalogue filtering; retain the last successful coverage with an error when
refresh fails. A technique is covered only if present in the successful ACTIVE
rule projection, not merely absent from a list of uncovered catalogue IDs.

Local note reads may be cancelled by closing the dialog. Late responses must not
replace another technique's note. A failed read offers retry; a failed save keeps
the draft editable. Pending writes freeze the target and draft and keep the
navigation guard active. A note-only save does not reload the standard catalogue.

ATT&CK activity must come from the tenant-owned technique-count aggregation,
independent of whether Overview has been visited. Display the seven-day
occurrence-time scope and calculation time. Own activity requests separately
from rule coverage, retain successful counts with an error after failed refresh,
and invalidate them on tactic changes. Never interpret the bounded Overview
alarm sample as a technique's total count.

Reference-set list refreshes publish only the newest response. A refresh after
an entry mutation supersedes any older read, so deleted entries cannot reappear
from a late response. Failed refreshes retain the last successful list with an
error; leaving the view cancels reads and suppresses post-mutation reloads.

Metadata registry reads have independent data, loading and error states. A
post-mutation refresh supersedes older reads instead of being skipped while a
read is pending. Partial failure retains only the affected registry's last
successful data; leaving the view cancels all three reads.

Situation metrics refresh every 15 seconds independently of the live alert
stream pause button.
When one source fails, retain that source's last successful value and label the
snapshot stale; an initial failure is unavailable, never a measured zero. Do
not add a repeated throughput sample when its refresh failed. Ignore aborted
snapshots before they can replace current metrics.
An alert-stream disconnect can check the session, but only an explicit HTTP
401 means the session expired. A temporary network or service error should
reconnect without logging the operator out.

The ingest task page refreshes sources, outputs, parse rules, tasks, summary
and categories independently. A newer read owns each list, and leaving the page
cancels pending reads. Keep successful siblings when one fails and show the
failing source's error. If the task summary fails, retain its last successful
metrics with a stale label; before any success, show unavailable values rather
than zeros.
Parse previews belong to the selected task and sample. Switching tasks,
closing the dialog or leaving the page cancels older preview requests; late
responses cannot populate another task's dialog.
Preview at most four bound rules at once, preserving rule order in the result.

Source management uses server pages with a visible total and name search.
Changing page or search supersedes the old request; deleting the final row on
a page reloads the last valid page. The parse-rule editor uses bounded remote
source search and preserves the label of its selected source even when that
source is outside the currently shown options. When a search has more than 50
matches, prompt for a narrower term instead of implying the first 50 are all.
Parse-rule management likewise pages and searches by name. Direct rule entry
loads the requested ID rather than a list. Source binding uses remote rule
search with a visible first-50 overflow hint; it resolves already selected
rule IDs separately so changing the search never turns a known name into an
opaque ID. A failed selected-rule lookup leaves the IDs visible with an error.
