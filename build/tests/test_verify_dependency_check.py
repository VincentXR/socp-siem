#!/usr/bin/env python3

import copy
from datetime import datetime, timedelta, timezone
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "build/verify-dependency-check.py"
SPEC = importlib.util.spec_from_file_location("verify_dependency_check", SCRIPT)
GATE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(GATE)
NOW = datetime(2026, 10, 12, 12, tzinfo=timezone.utc)
HASH = hashlib.sha256(b"runtime jar").hexdigest()
OTHER_HASH = hashlib.sha256(b"native runtime jar").hexdigest()


def report():
    return {
        "scanInfo": {
            "engineVersion": "12.2.2",
            "dataSource": [
                {"name": "NVD Cache Last Checked", "timestamp": NOW.isoformat()},
                {"name": "NVD Cache Last Modified", "timestamp": NOW.isoformat()},
            ],
        },
        "projectInfo": {"reportDate": NOW.isoformat()},
        "dependencies": [{"sha256": HASH}],
    }


class DependencyCheckReportTest(unittest.TestCase):
    def verify(self, value):
        return GATE.verify_report(value, {HASH: "example:runtime:1:"}, NOW)

    def test_complete_fresh_scan_passes(self):
        self.assertEqual(1, self.verify(report()))

    def test_merged_related_dependency_covers_its_original_hash(self):
        value = report()
        value["dependencies"] = [{"sha256": OTHER_HASH,
                                   "relatedDependencies": [{"sha256": HASH}]}]
        self.assertEqual(1, self.verify(value))

    def test_same_package_count_does_not_hide_partial_report(self):
        value = report()
        value["dependencies"] = [{"sha256": OTHER_HASH}]
        with self.assertRaisesRegex(GATE.AuditGateError, "omitted 1"):
            self.verify(value)

    def test_empty_report_fails(self):
        value = report()
        value["dependencies"] = []
        with self.assertRaises(GATE.AuditGateError):
            self.verify(value)

    def test_scan_analysis_errors_fail(self):
        for message in ("HTTP 401", "HTTP 429", "SocketTimeoutException", "NVD update failed"):
            with self.subTest(message=message):
                value = report()
                value["scanInfo"]["analysisExceptions"] = [{"exception": {"message": message}}]
                with self.assertRaisesRegex(GATE.AuditGateError, "analysis exceptions"):
                    self.verify(value)

    def test_per_dependency_analysis_error_fails(self):
        value = report()
        value["dependencies"][0]["analysisExceptions"] = ["failed to analyze"]
        with self.assertRaises(GATE.AuditGateError):
            self.verify(value)

    def test_successful_check_cannot_freshen_old_feed(self):
        value = report()
        value["scanInfo"]["dataSource"][1]["timestamp"] = (NOW - timedelta(hours=25)).isoformat()
        with self.assertRaisesRegex(GATE.AuditGateError, "Stale.*Last Modified"):
            self.verify(value)

    def test_stale_check_fails_even_with_fresh_feed(self):
        value = report()
        value["scanInfo"]["dataSource"][0]["timestamp"] = (NOW - timedelta(hours=25)).isoformat()
        with self.assertRaisesRegex(GATE.AuditGateError, "Stale.*Last Checked"):
            self.verify(value)

    def test_future_and_malformed_timestamps_fail(self):
        for timestamp in ((NOW + timedelta(hours=1)).isoformat(), "2026-10-12T12:00:00", "invalid", None):
            with self.subTest(timestamp=timestamp):
                value = report()
                value["scanInfo"]["dataSource"][0]["timestamp"] = timestamp
                with self.assertRaises(GATE.AuditGateError):
                    self.verify(value)

    def test_missing_or_duplicate_source_metadata_fails(self):
        for sources in ([], report()["scanInfo"]["dataSource"] * 2):
            value = report()
            value["scanInfo"]["dataSource"] = sources
            with self.assertRaises(GATE.AuditGateError):
                self.verify(value)

    def test_stale_report_fails(self):
        value = report()
        value["projectInfo"]["reportDate"] = (NOW - timedelta(days=2)).isoformat()
        with self.assertRaises(GATE.AuditGateError):
            self.verify(value)

    def test_wrong_engine_fails(self):
        value = report()
        value["scanInfo"]["engineVersion"] = "11.0.0"
        with self.assertRaises(GATE.AuditGateError):
            self.verify(value)

    def test_cvss_eight_threshold_for_all_supported_versions(self):
        for version, field in (("cvssv2", "score"), ("cvssv3", "baseScore"), ("cvssv4", "baseScore")):
            for score in (7.9, 8, 9.8):
                with self.subTest(version=version, score=score):
                    value = report()
                    value["dependencies"][0]["vulnerabilities"] = [
                        {"name": "CVE-fixture", version: {field: score}}
                    ]
                    if score >= 8:
                        with self.assertRaisesRegex(GATE.AuditGateError, "CVSS >= 8"):
                            self.verify(value)
                    else:
                        self.assertEqual(1, self.verify(value))

    def test_invalid_score_fails(self):
        for score in (float("nan"), float("inf"), "8", True, -1, 11):
            value = report()
            value["dependencies"][0]["vulnerabilities"] = [{"cvssv3": {"baseScore": score}}]
            with self.assertRaises(GATE.AuditGateError):
                self.verify(value)

    def test_unscored_high_or_critical_matches_maven_failure(self):
        for severity in ("HIGH", "critical"):
            value = report()
            value["dependencies"][0]["vulnerabilities"] = [
                {"name": "CVE-fixture", "unscored": "true", "severity": severity}
            ]
            with self.assertRaisesRegex(GATE.AuditGateError, "Unscored high/critical"):
                self.verify(value)

    def test_existing_suppressed_findings_remain_suppressed(self):
        value = report()
        value["dependencies"][0]["suppressedVulnerabilities"] = [{"cvssv3": {"baseScore": 9.8}}]
        self.assertEqual(1, self.verify(value))


class DependencyCheckRuntimeGraphTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name) / "project"
        self.repo = Path(self.temp.name) / "repository"
        self.root.mkdir()
        (self.root / "pom.xml").write_text(
            '<project xmlns="http://maven.apache.org/POM/4.0.0"><artifactId>parent</artifactId>'
            '<modules><module>app</module></modules></project>', encoding="utf-8")
        (self.root / "app").mkdir()
        (self.root / "app/pom.xml").write_text(
            '<project xmlns="http://maven.apache.org/POM/4.0.0"><artifactId>app</artifactId></project>',
            encoding="utf-8")
        self.artifact = {"groupId": "example", "artifactId": "runtime", "version": "1",
                         "type": "jar", "classifier": "", "scope": "runtime"}
        self.write_graph(self.root, "parent", [])
        self.write_graph(self.root / "app", "app", [self.artifact])
        self.jar = self.repo / "example/runtime/1/runtime-1.jar"
        self.jar.parent.mkdir(parents=True)
        self.jar.write_bytes(b"runtime jar")

    def write_graph(self, path, name, dependencies):
        (path / "target").mkdir(exist_ok=True)
        (path / "target/dependency-audit-runtime.json").write_text(json.dumps({
            "groupId": "com.socp", "artifactId": name, "version": "1",
            "type": "pom" if name == "parent" else "jar", "children": dependencies,
        }), encoding="utf-8")

    def test_all_modules_and_runtime_hashes(self):
        self.assertEqual({HASH: "example:runtime:1:"}, GATE.runtime_artifacts(self.root, self.repo))

    def test_missing_module_graph_fails(self):
        (self.root / "app/target/dependency-audit-runtime.json").unlink()
        with self.assertRaisesRegex(GATE.AuditGateError, "Missing or invalid JSON"):
            GATE.runtime_artifacts(self.root, self.repo)

    def test_wrong_module_graph_fails(self):
        self.write_graph(self.root / "app", "parent", [self.artifact])
        with self.assertRaisesRegex(GATE.AuditGateError, "does not belong"):
            GATE.runtime_artifacts(self.root, self.repo)

    def test_missing_resolved_jar_fails(self):
        self.jar.unlink()
        with self.assertRaisesRegex(GATE.AuditGateError, "artifact missing"):
            GATE.runtime_artifacts(self.root, self.repo)

    def test_transitive_native_classifier_is_independently_required(self):
        native = dict(self.artifact, classifier="linux-x86_64")
        self.jar.with_name("runtime-1-linux-x86_64.jar").write_bytes(b"native runtime jar")
        parent = copy.deepcopy(self.artifact)
        parent["children"] = [native]
        self.write_graph(self.root / "app", "app", [parent])
        self.assertEqual({HASH, OTHER_HASH}, GATE.runtime_artifacts(self.root, self.repo).keys())

    def test_empty_runtime_graph_fails(self):
        self.write_graph(self.root / "app", "app", [])
        with self.assertRaisesRegex(GATE.AuditGateError, "no resolved external"):
            GATE.runtime_artifacts(self.root, self.repo)

    def test_no_broad_group_exclusion(self):
        artifact = dict(self.artifact, groupId="com.socp.external")
        path = self.repo / "com/socp/external/runtime/1/runtime-1.jar"
        path.parent.mkdir(parents=True)
        path.write_bytes(b"runtime jar")
        self.write_graph(self.root / "app", "app", [artifact])
        self.assertEqual(1, len(GATE.runtime_artifacts(self.root, self.repo)))

    def test_cli_requires_json_and_html(self):
        report_path = self.root / "target/dependency-check-report.json"
        report_path.write_text(json.dumps(report()), encoding="utf-8")
        result = subprocess.run([sys.executable, str(SCRIPT), "--root", str(self.root),
                                 "--repository", str(self.repo)], capture_output=True, text=True)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("HTML report", result.stderr)

    def test_workflow_preserves_failure_and_cache_boundaries(self):
        workflow = (ROOT / ".github/workflows/dependency-audit.yml").read_text(encoding="utf-8")
        java = workflow.split("  frontend-audit:")[0]
        for required in ("-DfailBuildOnCVSS=8", "-DfailOnError=true", "-DskipRuntimeScope=false",
                         "-DautoUpdate=false", "-DnvdValidForHours=0", "-Dformats=HTML,JSON",
                         "if-no-files-found: error", "set -euo pipefail"):
            self.assertIn(required, java)
        self.assertLess(java.index(":update-only"), java.index("actions/cache/save@"))
        self.assertLess(java.index("actions/cache/save@"), java.index("-DskipTests"))
        self.assertLess(java.index("actions/cache/save@"), java.index(":aggregate"))
        self.assertNotIn("continue-on-error", java)
        self.assertNotIn("if-no-files-found: ignore", java)
        self.assertNotIn("secrets.NVD_API_KEY", java)


if __name__ == "__main__":
    unittest.main()
