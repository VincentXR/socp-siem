#!/usr/bin/env python3

import json
import subprocess
import sys
import tempfile
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


if __name__ == "__main__":
    unittest.main()
