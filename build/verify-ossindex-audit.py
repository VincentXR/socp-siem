#!/usr/bin/env python3
"""Fail-closed validation for ossindex-maven-plugin audit output."""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

COUNT_RE = re.compile(r"Checking for vulnerabilities;\s*(\d+)\s+artifacts?", re.IGNORECASE)
REMOTE_FAILURE_PATTERNS = (
    re.compile(r"Failed to fetch component-reports?", re.IGNORECASE),
    re.compile(r"HTTP/\S+\s+(?:401|402|403|408|409|425|429|5\d\d)\b", re.IGNORECASE),
    re.compile(r"status(?: code)?\s*[:=]\s*(?:401|402|403|408|409|425|429|5\d\d)\b", re.IGNORECASE),
    re.compile(r"rate limit", re.IGNORECASE),
    re.compile(r"(?:connect|connection|read|request)\s+timed?\s*out", re.IGNORECASE),
    re.compile(r"SocketTimeoutException|ConnectTimeoutException|TransportException", re.IGNORECASE),
)


class AuditGateError(RuntimeError):
    pass


def _collection_size(value: object, field: str) -> int:
    if isinstance(value, dict) or isinstance(value, list):
        return len(value)
    raise AuditGateError(f"OSS Index report field {field!r} is not a collection")


def verify(log_path: Path, report_path: Path, scope: str) -> tuple[int, int]:
    if not log_path.is_file() or log_path.stat().st_size == 0:
        raise AuditGateError(f"OSS Index audit log missing or empty: {log_path}")
    log_text = log_path.read_text(encoding="utf-8", errors="replace")

    for pattern in REMOTE_FAILURE_PATTERNS:
        if pattern.search(log_text):
            raise AuditGateError(
                "OSS Index remote scan did not complete successfully "
                f"(matched {pattern.pattern!r})"
            )

    requested = [int(match) for match in COUNT_RE.findall(log_text)]
    if not requested or max(requested) <= 0:
        raise AuditGateError(
            "OSS Index did not report a positive dependency/component count; "
            "an empty or skipped scan is not evidence of no vulnerabilities"
        )
    expected_components = max(requested)

    if not report_path.is_file() or report_path.stat().st_size == 0:
        raise AuditGateError(f"OSS Index report missing or empty: {report_path}")
    try:
        report = json.loads(report_path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        raise AuditGateError(f"OSS Index report is not valid JSON: {exc}") from exc
    if not isinstance(report, dict):
        raise AuditGateError("OSS Index report root must be a JSON object")

    if "reports" not in report:
        raise AuditGateError("OSS Index report has no 'reports' result set")
    report_count = _collection_size(report["reports"], "reports")
    if report_count < expected_components:
        raise AuditGateError(
            "OSS Index returned fewer component reports than requested: "
            f"requested={expected_components} returned={report_count}"
        )

    if "vulnerable" not in report:
        raise AuditGateError("OSS Index report has no 'vulnerable' result set")
    vulnerable_count = _collection_size(report["vulnerable"], "vulnerable")
    print(
        "OSS Index fallback scan evidence: "
        f"scope={scope} requested_components={expected_components} "
        f"returned_components={report_count} vulnerable_components={vulnerable_count}"
    )
    if vulnerable_count:
        raise AuditGateError(
            f"OSS Index found {vulnerable_count} component(s) at/above the configured CVSS threshold"
        )
    return report_count, vulnerable_count


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--log", required=True, type=Path)
    parser.add_argument("--report", required=True, type=Path)
    parser.add_argument("--scope", default="runtime")
    args = parser.parse_args()
    try:
        verify(args.log, args.report, args.scope)
        return 0
    except AuditGateError as exc:
        print(f"dependency audit gate failed: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
