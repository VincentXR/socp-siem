#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Collect commit-scoped, machine-readable validation evidence for CI."""

import argparse
import json
import os
import platform
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def command(*args):
    try:
        return subprocess.check_output(args, cwd=ROOT, text=True, stderr=subprocess.DEVNULL).strip()
    except (OSError, subprocess.CalledProcessError):
        return None


def manifest_summary():
    path = ROOT / "services/detect-web/src/main/resources/detection-content/manifest.json"
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
        return {"packId": data.get("packId"), "version": data.get("version"),
                "schemaVersion": data.get("schemaVersion"), "rules": len(data.get("rules", []))}
    except (OSError, ValueError):
        return {"rules": None}


def surefire_summary():
    tests = failures = errors = skipped = 0
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
        except OSError:
            continue
    for report in report_paths:
        try:
            import xml.etree.ElementTree as element_tree
            root = element_tree.parse(report).getroot()
            tests += int(root.attrib.get("tests", 0))
            failures += int(root.attrib.get("failures", 0))
            errors += int(root.attrib.get("errors", 0))
            skipped += int(root.attrib.get("skipped", 0))
        except Exception:
            continue
    return {"tests": tests, "failures": failures, "errors": errors, "skipped": skipped}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, default=ROOT / ".cache/evidence")
    parser.add_argument("--benchmark", type=Path, action="append", default=[])
    args = parser.parse_args()
    commit = command("git", "rev-parse", "HEAD") or os.environ.get("GITHUB_SHA", "unknown")
    evidence_dir = args.output / commit
    evidence_dir.mkdir(parents=True, exist_ok=True)
    now = datetime.now(timezone.utc).isoformat()
    manifest = manifest_summary()
    summary = {
        "commit": commit, "recordedAt": now,
        "workflow": os.environ.get("GITHUB_WORKFLOW"),
        "runId": os.environ.get("GITHUB_RUN_ID"),
        "runAttempt": os.environ.get("GITHUB_RUN_ATTEMPT"),
        "manifest": manifest,
        "instances": os.environ.get("DETECTION_INSTANCE_URLS", "http://127.0.0.1:18082").count(",") + 1,
        "kafkaPartitions": os.environ.get("SOCP_KAFKA_PARTITIONS"),
    }
    environment = {
        "platform": platform.platform(), "python": sys.version,
        "machine": platform.machine(), "processor": platform.processor(),
        "cpuCount": os.cpu_count(), "java": command("java", "-version"),
    }
    (evidence_dir / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (evidence_dir / "test-summary.json").write_text(json.dumps(surefire_summary(), indent=2) + "\n", encoding="utf-8")
    (evidence_dir / "environment.json").write_text(json.dumps(environment, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    reports = []
    for path in args.benchmark:
        try: reports.append(json.loads(path.read_text(encoding="utf-8")))
        except (OSError, ValueError): pass
    (evidence_dir / "benchmark-summary.json").write_text(json.dumps(reports, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(evidence_dir)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
