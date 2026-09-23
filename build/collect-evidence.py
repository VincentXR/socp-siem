#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Collect available validation evidence, recording missing/corrupt inputs explicitly.

The directory's commit is provenance, not proof that local reports were produced
from that commit. Exit status describes collection integrity, not test success.
"""

import argparse
import hashlib
import json
import os
import platform
import subprocess
import sys
import xml.etree.ElementTree as element_tree
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MAX_ARTIFACT_BYTES = 32 * 1024 * 1024


def command(*args, include_stderr=False):
    try:
        return subprocess.check_output(args, cwd=ROOT, text=True, timeout=15,
                                       stderr=subprocess.STDOUT if include_stderr else subprocess.DEVNULL).strip()
    except (OSError, subprocess.SubprocessError):
        return None


def manifest_summary():
    path = ROOT / "services/detect-web/src/main/resources/detection-content/manifest.json"
    try:
        data = json.loads(read_artifact(path))
        if not isinstance(data, dict) or not all(data.get(key) for key in ("packId", "version", "schemaVersion")) \
                or not isinstance(data.get("rules"), list) or not data["rules"]:
            raise ValueError("incomplete detection manifest")
        return {"packId": data.get("packId"), "version": data.get("version"),
                "schemaVersion": data.get("schemaVersion"), "rules": len(data.get("rules", []))}
    except (OSError, ValueError, TypeError, AttributeError) as error:
        return {"rules": None, "error": type(error).__name__}


def read_artifact(path):
    with path.open("rb") as stream:
        raw = stream.read(MAX_ARTIFACT_BYTES + 1)
    if len(raw) > MAX_ARTIFACT_BYTES:
        raise ValueError("artifact exceeds 32 MiB")
    return raw


def artifact_identity(path, raw):
    return {"path": path.relative_to(ROOT).as_posix() if path.is_relative_to(ROOT) else str(path),
            "sha256": hashlib.sha256(raw).hexdigest(), "bytes": len(raw),
            "modifiedAt": datetime.fromtimestamp(path.stat().st_mtime, timezone.utc).isoformat()}


def surefire_summary():
    counts = dict(tests=0, failures=0, errors=0, skipped=0)
    reports, invalid = [], []
    # Do not recursively walk the entire repository.  A frontend install can
    # contain broken Windows junctions under node_modules (or a very large
    # dependency tree), neither of which can contain Maven reports.  Restrict
    # discovery to the reactor's known module roots and tolerate a module being
    # absent when a focused build was run.
    report_paths = []
    for root in (ROOT, ROOT / "platform", ROOT / "services"):
        try:
            if root == ROOT:
                report_paths.extend(root.glob("target/surefire-reports/TEST-*.xml"))
            else:
                for module in root.iterdir():
                    if module.is_dir():
                        report_paths.extend(module.glob("target/surefire-reports/TEST-*.xml"))
        except OSError as error:
            invalid.append({"path": str(root), "error": type(error).__name__})
    for report in sorted(set(report_paths)):
        try:
            raw = read_artifact(report)
            suite = element_tree.fromstring(raw)
            if suite.tag != "testsuite":
                raise ValueError("expected a Surefire testsuite root")
            found = {field: int(suite.attrib[field]) for field in counts}
            cases = suite.findall("testcase")
            observed = {"tests": len(cases), "failures": sum(case.find("failure") is not None for case in cases),
                        "errors": sum(case.find("error") is not None for case in cases),
                        "skipped": sum(case.find("skipped") is not None for case in cases)}
            if found != observed or any(value < 0 for value in found.values()) \
                    or found["failures"] + found["errors"] + found["skipped"] > found["tests"]:
                raise ValueError("suite counters disagree with testcase outcomes")
            reports.append({**artifact_identity(report, raw), **found})
            for field in counts:
                counts[field] += found[field]
        except (OSError, ValueError, KeyError, element_tree.ParseError) as error:
            invalid.append({"path": str(report), "error": str(error)})
    status = ("incomplete" if invalid else "not-run" if not reports else
              "failed" if counts["failures"] or counts["errors"] else
              "skipped" if counts["tests"] == counts["skipped"] else "passed")
    return {**counts, "executed": counts["tests"] - counts["skipped"], "status": status,
            "scope": "available-local-surefire-reports", "reports": reports, "invalidReports": invalid,
            "currentRunVerified": False}


def main(argv=None):
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, default=ROOT / ".cache/evidence")
    parser.add_argument("--benchmark", type=Path, action="append", default=[])
    parser.add_argument("--require-tests", action="store_true", help="fail collection if no tests executed")
    args = parser.parse_args(argv)
    commit = command("git", "rev-parse", "HEAD") or os.environ.get("GITHUB_SHA", "unknown")
    if len(commit) not in (40, 64) or any(character not in "0123456789abcdef" for character in commit):
        commit = "unknown"
    evidence_dir = args.output / commit
    evidence_dir.mkdir(parents=True, exist_ok=True)
    now = datetime.now(timezone.utc).isoformat()
    manifest = manifest_summary()
    tests = surefire_summary()
    problems = []
    if manifest.get("error"):
        problems.append("detection manifest could not be read")
    if tests["invalidReports"]:
        problems.append("some Surefire reports could not be collected")
    if args.require_tests and tests["executed"] == 0:
        problems.append("required executed-test evidence is missing")
    reports, benchmark_inputs = [], []
    for supplied in args.benchmark:
        path = supplied.resolve()
        try:
            raw = read_artifact(path)
            report = json.loads(raw)
            if not isinstance(report, dict) or not report:
                raise ValueError("benchmark report must be a nonempty JSON object")
            reported_commit = report.get("commitSha")
            if reported_commit not in (None, "unknown", commit):
                raise ValueError("benchmark commitSha differs from the collected checkout")
            reports.append(report)
            benchmark_inputs.append({**artifact_identity(path, raw), "status": "collected"})
        except (OSError, ValueError) as error:
            benchmark_inputs.append({"path": str(supplied), "status": "unavailable", "error": str(error)})
            problems.append("benchmark unavailable: " + str(supplied))
    source_status = command("git", "status", "--porcelain", "--untracked-files=normal")
    configured_instances = {value.strip() for value in os.environ.get("DETECTION_INSTANCE_URLS", "").split(",") if value.strip()}
    summary = {
        "commit": commit, "recordedAt": now,
        "workflow": os.environ.get("GITHUB_WORKFLOW"),
        "runId": os.environ.get("GITHUB_RUN_ID"),
        "runAttempt": os.environ.get("GITHUB_RUN_ATTEMPT"),
        "manifest": manifest,
        "worktreeDirty": bool(source_status) if source_status is not None else None,
        "collectionStatus": "incomplete" if problems else "complete",
        "collectionErrors": problems, "benchmarkInputs": benchmark_inputs,
        "testScope": tests["scope"], "currentRunVerified": False,
        "instances": len(configured_instances) if configured_instances else None,
        "instanceScope": "configured URLs; not observed runtime membership",
        "kafkaPartitions": os.environ.get("SOCP_KAFKA_PARTITIONS"),
    }
    environment = {
        "platform": platform.platform(), "python": sys.version,
        "machine": platform.machine(), "processor": platform.processor(),
        "cpuCount": os.cpu_count(), "java": command("java", "-version", include_stderr=True),
    }
    (evidence_dir / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (evidence_dir / "test-summary.json").write_text(json.dumps(tests, indent=2) + "\n", encoding="utf-8")
    (evidence_dir / "environment.json").write_text(json.dumps(environment, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (evidence_dir / "benchmark-summary.json").write_text(json.dumps(reports, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(evidence_dir)
    for problem in problems:
        print("[INCOMPLETE] " + problem, file=sys.stderr)
    return 1 if problems else 0


if __name__ == "__main__":
    raise SystemExit(main())
