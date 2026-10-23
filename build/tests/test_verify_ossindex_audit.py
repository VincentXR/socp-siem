#!/usr/bin/env python3

import json
import os
import subprocess
import sys
import tempfile
import textwrap
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
VERIFIER = ROOT / "build" / "verify-ossindex-audit.py"


class VerifyOssIndexAuditTest(unittest.TestCase):
    def run_gate(self, log: str, report: object | None):
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            log_path = tmp / "audit.log"
            report_path = tmp / "report.json"
            log_path.write_text(log, encoding="utf-8")
            if report is not None:
                report_path.write_text(json.dumps(report), encoding="utf-8")
            return subprocess.run(
                [
                    sys.executable,
                    str(VERIFIER),
                    "--log",
                    str(log_path),
                    "--report",
                    str(report_path),
                    "--scope",
                    "runtime",
                ],
                text=True,
                capture_output=True,
                check=False,
            )

    @staticmethod
    def clean_report(count: int, vulnerable: dict | None = None):
        return {
            "reports": {f"pkg:maven/example/lib-{index}@1.0": {} for index in range(count)},
            "vulnerable": vulnerable or {},
            "excludedCoordinates": [],
            "excludedVulnerabilities": [],
        }

    def test_complete_scan_passes(self):
        result = self.run_gate(
            "[INFO] Checking for vulnerabilities; 3 artifacts\n",
            self.clean_report(3),
        )
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("requested_components=3", result.stdout)

    def test_high_vulnerability_fails(self):
        result = self.run_gate(
            "[INFO] Checking for vulnerabilities; 2 artifacts\n",
            self.clean_report(2, {"pkg:maven/example/bad@1.0": {"vulnerabilities": [{}]}}),
        )
        self.assertNotEqual(0, result.returncode)

    def test_401_fails_even_with_empty_report(self):
        result = self.run_gate(
            "[INFO] Checking for vulnerabilities; 2 artifacts\n"
            "[WARNING] Failed to fetch component-reports\n"
            "Unexpected response; status: HTTP/1.1 401 Unauthorized\n",
            self.clean_report(0),
        )
        self.assertNotEqual(0, result.returncode)

    def test_429_fails(self):
        result = self.run_gate(
            "[INFO] Checking for vulnerabilities; 2 artifacts\n"
            "Unexpected response; status: HTTP/1.1 429 Too many requests\n",
            self.clean_report(0),
        )
        self.assertNotEqual(0, result.returncode)

    def test_timeout_fails(self):
        result = self.run_gate(
            "[INFO] Checking for vulnerabilities; 2 artifacts\n"
            "java.net.SocketTimeoutException: Read timed out\n",
            self.clean_report(0),
        )
        self.assertNotEqual(0, result.returncode)

    def test_missing_report_fails(self):
        result = self.run_gate(
            "[INFO] Checking for vulnerabilities; 2 artifacts\n",
            None,
        )
        self.assertNotEqual(0, result.returncode)

    def test_partial_component_report_fails(self):
        result = self.run_gate(
            "[INFO] Checking for vulnerabilities; 4 artifacts\n",
            self.clean_report(3),
        )
        self.assertNotEqual(0, result.returncode)


class DependencyReviewFallbackTest(unittest.TestCase):
    def run_decision(self, has_findings):
        workflow = (ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8")
        step = workflow.split("      - name: Decide whether fallback audit is required\n")[1]
        step = step.split("\n      - name:")[0]
        self.assertNotIn("          VULNERABLE_CHANGES:", step)
        self.assertIn("HAS_VULNERABLE_CHANGES: ${{", step)
        self.assertIn("outputs['vulnerable-changes'] != '[]'", step)
        self.assertIn("outputs['vulnerable-changes'] != '{}'", step)
        script = textwrap.dedent(step.split("        run: |\n")[1])
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "outputs"
            result = subprocess.run(
                ["bash", "-e", "-c", script], capture_output=True, text=True, check=False,
                env={**os.environ, "HAS_VULNERABLE_CHANGES": has_findings,
                     "GITHUB_OUTPUT": str(output)},
            )
            return result, output.read_text() if output.exists() else ""

    def test_large_findings_fail_without_passing_json_to_process_environment(self):
        findings = json.dumps([{"advisory": "x" * 400_000}])
        # GitHub evaluates the nonempty-output expression before spawning bash.
        result, output = self.run_decision(str(findings not in ("", "[]", "{}")).lower())
        self.assertEqual(1, result.returncode)
        self.assertIn("reported vulnerable changes", result.stdout)
        self.assertNotIn("required=true", output)

    def test_empty_findings_require_fallback_audit(self):
        result, output = self.run_decision("false")
        self.assertEqual(0, result.returncode)
        self.assertEqual("required=true\n", output)

    def test_unexpected_boolean_fails_closed(self):
        result, output = self.run_decision("")
        self.assertNotEqual(0, result.returncode)
        self.assertEqual("", output)


if __name__ == "__main__":
    unittest.main()
