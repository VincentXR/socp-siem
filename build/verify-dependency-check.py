#!/usr/bin/env python3
"""Fail closed on stale, failed, or incomplete OWASP Java dependency scans."""

from __future__ import annotations

import argparse
from datetime import datetime, timedelta, timezone
import hashlib
import json
import math
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
NS = {"m": "http://maven.apache.org/POM/4.0.0"}
SHA256 = re.compile(r"[0-9a-fA-F]{64}")
ENGINE_VERSION = "12.2.2"
MAX_AGE = timedelta(hours=24)
CLOCK_SKEW = timedelta(minutes=5)


class AuditGateError(RuntimeError):
    pass


def read_json(path: Path) -> dict:
    try:
        result = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as exc:
        raise AuditGateError(f"Missing or invalid JSON: {path}: {exc}") from exc
    if not isinstance(result, dict):
        raise AuditGateError(f"Expected a JSON object: {path}")
    return result


def fresh_timestamp(value: object, name: str, now: datetime) -> None:
    try:
        timestamp = datetime.fromisoformat(str(value).replace("Z", "+00:00"))
        if timestamp.tzinfo is None:
            raise ValueError("timezone is required")
        age = now - timestamp
    except (ValueError, TypeError) as exc:
        raise AuditGateError(f"Invalid {name} timestamp: {value!r}") from exc
    if age < -CLOCK_SKEW or age > MAX_AGE:
        raise AuditGateError(f"Stale or future {name}: {value}; maximum age is 24 hours")


def reactor_modules(root: Path) -> list[Path]:
    """Match the default, unprofiled reactor used by the audit workflow."""
    modules: list[Path] = []
    pending = [root.resolve()]
    while pending:
        module = pending.pop()
        if module in modules or not module.is_relative_to(root.resolve()):
            raise AuditGateError(f"Invalid/cyclic reactor module: {module}")
        try:
            pom = ET.parse(module / "pom.xml").getroot()
        except (OSError, ET.ParseError) as exc:
            raise AuditGateError(f"Cannot read reactor POM: {module}: {exc}") from exc
        modules.append(module)
        pending.extend((module / item.text.strip()).resolve()
                       for item in pom.findall("m:modules/m:module", NS) if item.text)
    return modules


def coordinate(node: dict) -> tuple[str, str, str]:
    values = tuple(node.get(key) for key in ("groupId", "artifactId", "version"))
    if any(not isinstance(value, str) or not value or "/" in value or "\\" in value
           or value in (".", "..") for value in values):
        raise AuditGateError(f"Invalid Maven coordinate: {values}")
    return values


def runtime_artifacts(root: Path, repository: Path) -> dict[str, str]:
    """Hash resolved external artifacts, including transitive/native classifiers.

    Hash comparison also handles Dependency-Check's relatedDependencies merging;
    a package count or package URL alone cannot prove every classifier was scanned.
    """
    trees = []
    for module in reactor_modules(root):
        tree = read_json(module / "target/dependency-audit-runtime.json")
        pom = ET.parse(module / "pom.xml").getroot()
        if tree.get("artifactId") != pom.findtext("m:artifactId", namespaces=NS):
            raise AuditGateError(f"Runtime graph does not belong to {module}")
        coordinate(tree)
        trees.append(tree)
    reactor = {coordinate(tree) for tree in trees}
    expected: dict[str, str] = {}
    hashed: set[Path] = set()
    pending = []
    for tree in trees:
        children = tree.get("children", [])
        if not isinstance(children, list):
            raise AuditGateError("Invalid runtime graph children")
        pending.extend(children)
    while pending:
        node = pending.pop()
        if not isinstance(node, dict):
            raise AuditGateError("Invalid runtime graph node")
        group, artifact, version = coordinate(node)
        children = node.get("children", [])
        if not isinstance(children, list):
            raise AuditGateError("Invalid runtime graph children")
        pending.extend(children)
        if node.get("scope") not in ("compile", "runtime"):
            raise AuditGateError(f"Unexpected scope in runtime graph: {node}")
        if (group, artifact, version) in reactor:
            continue
        kind = node.get("type", "jar")
        classifier = node.get("classifier", "")
        if kind == "test-jar":
            kind, classifier = "jar", classifier or "tests"
        if kind not in ("jar", "war", "zip") or not isinstance(classifier, str) or any(
            char in classifier for char in "/\\"
        ):
            raise AuditGateError(f"Unsupported runtime artifact type/classifier: {node}")
        filename = f"{artifact}-{version}{'-' + classifier if classifier else ''}.{kind}"
        path = repository / group.replace(".", "/") / artifact / version / filename
        if not path.resolve().is_relative_to(repository.resolve()):
            raise AuditGateError(f"Artifact escapes Maven repository: {path}")
        if path in hashed:
            continue
        try:
            with path.open("rb") as stream:
                digest = hashlib.file_digest(stream, "sha256").hexdigest()
        except OSError as exc:
            raise AuditGateError(f"Resolved runtime artifact missing: {path}") from exc
        expected[digest] = f"{group}:{artifact}:{version}:{classifier}"
        hashed.add(path)
    if not expected:
        raise AuditGateError("Runtime graph contains no resolved external artifacts")
    return expected


def verify_report(report: dict, expected: dict[str, str], now: datetime) -> int:
    info = report.get("scanInfo")
    if not isinstance(info, dict) or info.get("engineVersion") != ENGINE_VERSION:
        raise AuditGateError(f"Expected Dependency-Check {ENGINE_VERSION} scan metadata")
    if info.get("analysisExceptions"):
        raise AuditGateError("Dependency-Check reported analysis exceptions")
    sources = info.get("dataSource")
    if not isinstance(sources, list) or any(not isinstance(item, dict) for item in sources):
        raise AuditGateError("Missing NVD source metadata")
    for name in ("NVD Cache Last Checked", "NVD Cache Last Modified"):
        values = [item.get("timestamp") for item in sources if item.get("name") == name]
        if len(values) != 1:
            raise AuditGateError(f"Missing or duplicate {name}")
        fresh_timestamp(values[0], name, now)
    project = report.get("projectInfo")
    if not isinstance(project, dict):
        raise AuditGateError("Missing report date")
    fresh_timestamp(project.get("reportDate"), "report", now)
    dependencies = report.get("dependencies")
    if not isinstance(dependencies, list) or not dependencies:
        raise AuditGateError("Dependency-Check report contains no dependencies")
    seen: set[str] = set()
    pending = list(dependencies)
    while pending:
        dependency = pending.pop()
        if not isinstance(dependency, dict):
            raise AuditGateError("Invalid dependency report entry")
        if dependency.get("analysisExceptions"):
            raise AuditGateError("Dependency analysis failed")
        related = dependency.get("relatedDependencies", [])
        if not isinstance(related, list):
            raise AuditGateError("Invalid related dependency report entries")
        pending.extend(related)
        digest = dependency.get("sha256", "")
        if isinstance(digest, str) and SHA256.fullmatch(digest):
            seen.add(digest.lower())
        vulnerabilities = dependency.get("vulnerabilities", [])
        if not isinstance(vulnerabilities, list):
            raise AuditGateError("Invalid vulnerability list")
        for vulnerability in vulnerabilities:
            if not isinstance(vulnerability, dict):
                raise AuditGateError("Invalid vulnerability entry")
            scored = False
            for version, field in (("cvssv2", "score"), ("cvssv3", "baseScore"),
                                   ("cvssv4", "baseScore")):
                rating = vulnerability.get(version, {})
                if not isinstance(rating, dict):
                    raise AuditGateError("Invalid vulnerability rating")
                score = rating.get(field)
                if score is None:
                    continue
                scored = True
                if isinstance(score, bool) or not isinstance(score, (int, float)) or not (
                    math.isfinite(score) and 0 <= score <= 10
                ):
                    raise AuditGateError("Invalid CVSS score")
                if score >= 8:
                    raise AuditGateError(f"CVSS >= 8: {vulnerability.get('name')} ({score})")
            # Match 12.2.2's failBuildOnCVSS fallback (SeverityUtil.estimateCvssV2).
            if not scored and str(vulnerability.get("severity", "")).lower() in ("high", "critical"):
                raise AuditGateError(f"Unscored high/critical vulnerability: {vulnerability.get('name')}")
    missing = expected.keys() - seen
    if missing:
        names = ", ".join(sorted(expected[digest] for digest in missing)[:10])
        raise AuditGateError(f"Report omitted {len(missing)} runtime artifact(s): {names}")
    return len(expected)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--repository", type=Path, default=Path.home() / ".m2/repository")
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    report_path = args.report or args.root / "target/dependency-check-report.json"
    try:
        html = report_path.with_suffix(".html")
        if not html.is_file() or html.stat().st_size == 0:
            raise AuditGateError(f"Missing or empty HTML report: {html}")
        count = verify_report(read_json(report_path), runtime_artifacts(args.root, args.repository),
                              datetime.now(timezone.utc))
    except AuditGateError as exc:
        print(f"Java dependency audit gate failed: {exc}", file=sys.stderr)
        return 1
    print(f"Java dependency audit passed: {count} runtime artifact hashes covered; NVD age <= 24h; CVSS < 8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
