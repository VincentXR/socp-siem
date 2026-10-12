# Dependency audit operations

The `Dependency Audit` workflow scans the Java reactor and frontend dependencies.
It runs weekly, manually, and for PR changes to its own workflow, Java report
verifier/tests, or suppression file. The separate Change CI dependency review
continues to check dependency-manifest changes.

## Java data update and cache

Dependency-Check is pinned to 12.2.2. Its `update-only` goal reads the official
[NVD JSON 2.0 feeds](https://nvd.nist.gov/vuln/data-feeds) through
`nvdDatafeedUrl=https://nvd.nist.gov/feeds/json/cve/2.0/nvdcve-2.0-{0}.json.gz`.
This supported [data-feed mode](https://dependency-check.github.io/DependencyCheck/data/mirrornvd.html)
does not need an NVD API key or a third-party mirror. The initial update imports
all years from 2002 (including earlier CVEs) through the current year and the
modified feed. Subsequent updates use feed metadata and modified data, returning
to yearly feeds when required after a gap. Do not restrict the start year: old
CVEs still apply to current dependencies.

The non-recursive update has a separate 45-minute budget and `failOnError=true`.
`nvdValidForHours=0` requires a source check on every run. Both update and scan
use the same absolute data directory. Only a successful update is saved, using
the pinned engine/feed generation in the cache key. Saving happens before
building or scanning, so a CVSS failure, build failure, or report-validation
failure does not discard the completed database and cause another cold start.
Partial or failed updates are never saved. Existing API-mode caches are not
restored into this feed-mode cache generation.

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
