# Dependency audit operations

The `Dependency Audit` workflow scans the Java reactor and frontend dependencies.
It runs weekly, manually, and for PR changes to its own workflow, Java report
verifier/tests, or suppression file. The separate Change CI dependency review
continues to check dependency-manifest changes.

## Java data update and cache

Dependency-Check is pinned to 12.2.2. Before its `update-only` goal runs,
`build/nvd-feed-cache.py` downloads the official
[NVD JSON 2.0 feeds](https://nvd.nist.gov/vuln/data-feeds) from
`https://nvd.nist.gov/feeds/json/cve/2.0/nvdcve-2.0-{year}.json.gz` and the
corresponding `.meta` files. At most four annual feeds download concurrently,
starting with recent years so the largest feeds do not wait behind older years.
Each feed gets at most three attempts; a retry refreshes its metadata. Payloads
use fixed 1 MiB HTTP ranges with at most three attempts per range, requiring
HTTP 206 and the exact requested `Content-Range`. A failed range is truncated
back to its start before retrying, retaining earlier complete ranges. A feed-level
retry retains these ranges only when newly fetched official metadata has the same
fingerprint; changed metadata or a final integrity failure discards the partial
file. Chunk progress, retries and completion are flushed to the diagnostics. Before publication, the
snapshot requires the declared compressed byte count, gzip CRC/end-of-stream,
uncompressed byte count, and SHA-256 of the uncompressed JSON to match the
official metadata. A failed or partial snapshot never starts the database update.
The modified feed is fetched last, after all annual feeds. The updater reads
this complete snapshot through its supported `file://` feed URL, preserving
the original `.meta` bytes and literal `{0}` placeholder. Download logs and a
verification manifest are retained with the audit diagnostics.
This supported [data-feed mode](https://dependency-check.github.io/DependencyCheck/data/mirrornvd.html)
does not need an NVD API key or a third-party mirror. The initial update imports
all years from 2002 (including earlier CVEs) through the current year and the
modified feed. Subsequent updates use feed metadata and modified data, returning
to yearly feeds when required after a gap. Do not restrict the start year: old
CVEs still apply to current dependencies.

The download and non-recursive update share a separate 75-minute budget after
a measured cold download exceeded 45 minutes before reaching database import.
The job allows 125 minutes, with separate 20-minute build and scan budgets plus
setup, cache and artifact time. The update retains `failOnError=true`.
`build/update-dependency-check.py` retries transient feed download errors at most
three times, with a 15-second delay, a 30-second connect timeout, and a 180-second
read timeout. Each attempt starts a fresh JVM against an isolated copy of the
last successful cache. A truncated feed cannot replace that cache, and failed
attempts never supply the subsequent scan. Database/configuration failures are
not retried. Each attempt's log is flushed line by line and retained in
`target/dependency-audit/`, including when CI cancels a timed-out process.
Only an exit-zero update containing the H2 database and no logged import errors
is published. Dependency-Check can log individual CVE processing failures without
propagating them to the process exit code, so the helper rejects those failures
and all updater `ERROR` records. This is still not a freshness or vulnerability
verdict. The final report checks below remain
mandatory. Staging requires temporary disk space for the compressed feed snapshot
and one additional database. The dependency-check feed loader performs its normal
metadata selection and imports; the snapshot does not replace its CVE analysis.
`nvdValidForHours=0` requires a source check on every run. Both update and scan
use the same absolute data directory. Only a successful update is saved, using
the pinned engine/feed generation in the cache key. Saving happens before
building or scanning, so a CVSS failure, build failure, or report-validation
failure does not discard the completed database and cause another cold start.
Partial or failed updates are never saved. The `nvd-feed-v2` cache generation
requires a fresh initial import after adding import-error rejection; an older
database with a silently omitted CVE must not be accepted by a later incremental
update. Existing API-mode or `nvd-feed-v1` caches are not restored.

A separate `.cache/nvd-feed-payloads` cache retains only individually complete,
verified official gzip files. Those files can be saved even when a different
feed fails; they are never treated as a complete database or a passed scan.
Every reuse first fetches current official metadata, then checks its timestamp
where applicable and revalidates gzip integrity, sizes and the official SHA-256.
A mismatch requires a fresh download. Partial gzip files are not cached. This
avoids downloading already verified years again after a later feed fails while
preserving the complete-history and modified-feed requirements.

The feeds are maintained upstream and can be stale, unavailable, or rate-limited.
A recently downloaded database is not proof of fresh vulnerability data.
The JSON report verifier therefore requires both `NVD Cache Last Checked` and
`NVD Cache Last Modified` to be within 24 hours, rejects future timestamps beyond
five minutes of clock skew, and requires a recent report timestamp. If a feed is
stale or unavailable, the audit fails; do not extend the freshness allowance,
disable failures, or add vulnerability suppressions to obtain green CI. A
successful cache update is an optimization, not a vulnerability verdict.

## Runtime coverage and severity

The build records a JSON `maven-dependency-plugin:3.8.1:tree` with `scope=runtime`
for every module in the default reactor. This includes compile and runtime
dependencies, including transitives. The verifier requires every declared
module's graph, hashes the resolved external artifacts in the Maven repository,
and matches every SHA-256 against the aggregate report, including merged
`relatedDependencies`. Native classifiers are independently covered; matching
only dependency counts or package URLs is insufficient. Reactor-owned artifacts
are excluded from this external-dependency hash inventory by exact coordinates,
not by a broad group-prefix exclusion. The scan still processes the full reactor.
Changes adding profile-selected reactor modules or new runtime artifact types
must extend the verifier and its fixtures before changing the audit invocation.

Scanning uses `autoUpdate=false` only after the successful update step and retains
`skipTestScope=true`, `skipRuntimeScope=false`, `failOnError=true`, and
`failBuildOnCVSS=8`. No new suppression is introduced. HTML and JSON reports are
required, and missing/empty reports, analysis errors, missing runtime artifacts,
or CVSS scores of 8 or greater fail the job. The Maven scan's exit status remains
authoritative even if a report was written. Dependency-Check's default optional
Sonatype OSS Index analyzer disables itself without credentials in 12.2.2;
this NVD-backed audit does not claim an authenticated OSS Index scan. Change CI's
independent fail-closed OSS Index fallback remains unchanged.

## Frontend audit

The frozen pnpm install uses the official npm registry. `pnpm audit --json`
examines both runtime and development dependencies, preserving the high-severity
failure threshold and the complete JSON result as `frontend-dependency-audit-report`.
Moderate and low findings remain visible in that artifact even when the threshold
allows the job to pass. A pipeline failure cannot be hidden by `tee`, and a
missing report is an artifact-upload error.

## Evidence and troubleshooting

- `java-dependency-check-report` contains the HTML and JSON vulnerability reports.
- `java-dependency-check-diagnostics` contains update/build/scan logs and per-module
  runtime graphs. Logs are uploaded even when the scan cannot produce a report.
- Report upload fails if no report exists; successful log upload never makes a
  failed update, scan, or report gate green.
- A cache miss is supported and triggers a complete feed import. A failed import
  is reported explicitly; retry the workflow after source availability recovers.
- If database incompatibility or corruption is confirmed, increment the cache
  generation in the workflow and rerun. Do not reuse a partially written database.
- For a missing runtime hash, inspect the named coordinate, graph, and report.
  Fix the resolution/scan coverage issue rather than suppressing the requirement.

Run the verifier fixtures without Maven or network access:

```bash
python3 -m unittest build/tests/test_verify_dependency_check.py
python3 -m unittest build/tests/test_update_dependency_check.py
python3 -m unittest build/tests/test_nvd_feed_cache.py
```

For a completed local run using the workflow's update/build/scan commands:

```bash
python3 build/verify-dependency-check.py
# For a custom Maven local repository:
python3 build/verify-dependency-check.py --repository /path/to/maven/repository
```

The helper requires Python 3.11 or newer. A local verifier pass proves the report
metadata and runtime-artifact coverage; only a successful end-to-end workflow
also establishes the update, build, scanner, and artifact-upload results.
