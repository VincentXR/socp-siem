"""Acceptance-oracle regressions, not substitutes for the live Golden Demo."""
import contextlib
import importlib.util
import io
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import tomllib
import unittest
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("golden_demo", ROOT / "build/demos/golden-demo.py")
DEMO = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(DEMO)


class GoldenDemoTest(unittest.TestCase):
    def test_fixture_preserves_the_collector_protocol_and_disk_buffer(self):
        text = DEMO.fixture_config("http://127.0.0.1:18081/search-config/api/v1/ingest",
                                   'ci-collector', 'quote"slash\\token')
        config = tomllib.loads(text)
        self.assertEqual({"siem_files"}, set(config["sources"]))
        self.assertEqual(["siem_files"], config["transforms"]["gls_normalize"]["inputs"])
        self.assertEqual({"gls_ingest"}, set(config["sinks"]))
        self.assertEqual("beginning", config["sources"]["siem_files"]["read_from"])
        self.assertEqual(["/demo/sample.log"], config["sources"]["siem_files"]["include"])
        sink = config["sinks"]["gls_ingest"]
        self.assertEqual('Bearer quote"slash\\token', sink["request"]["headers"]["Authorization"])
        self.assertEqual("ci-collector", sink["request"]["headers"]["X-SOCP-Collector"])
        self.assertEqual({"type": "disk", "max_size": 268435488, "when_full": "block"}, sink["buffer"])
        self.assertNotIn("retry_attempts", sink["request"])
        self.assertIn('.parse_format = "auto"', config["transforms"]["gls_normalize"]["source"])

    @unittest.skipUnless(os.environ.get("SOCP_TESTCONTAINERS") == "true",
                         "requires the verified Vector Docker image")
    def test_private_file_fixture_passes_actual_vector_validation(self):
        with tempfile.TemporaryDirectory() as directory:
            configuration = Path(directory) / "vector.toml"
            configuration.write_text(DEMO.fixture_config(
                "http://127.0.0.1:18081/search-config/api/v1/ingest", "fixture", "not-a-live-credential"))
            result = subprocess.run([
                "docker", "run", "--rm", "--network", "none",
                "--mount", f"type=bind,source={configuration},target=/etc/vector/vector.toml,readonly",
                DEMO.middleware_image("vector"), "validate", "--no-environment", "/etc/vector/vector.toml",
            ], capture_output=True, text=True, timeout=30)
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def test_isolated_collector_rejects_remote_targets_and_missing_identity(self):
        for url in ("https://example.com/search-config/api/v1/ingest",
                    "http://127.0.0.1.evil.test/search-config/api/v1/ingest",
                    "http://user:password@localhost/search-config/api/v1/ingest",
                    "http://localhost/another-service", "http://localhost/search-config/api/v1/ingest?x=1"):
            with self.subTest(url=url), self.assertRaises(ValueError):
                DEMO.fixture_config(url, "collector", "credential")
        for collector, token in (("", "credential"), ("collector", "")):
            with self.assertRaises(ValueError):
                DEMO.fixture_config("http://localhost/search-config/api/v1/ingest", collector, token)

    def test_fixture_removes_only_its_container_and_never_uploads_credentials(self):
        for fail_scenario in (False, True):
            with self.subTest(fail_scenario=fail_scenario), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                reference = root / "agents/vector-pipeline/vector.toml"
                reference.parent.mkdir(parents=True)
                shutil.copy(ROOT / "agents/vector-pipeline/vector.toml", reference)
                calls, private_files = [], []
                container = "a" * 64
                token = "disposable-test-collector-secret"

                def run(command, **kwargs):
                    calls.append(command)
                    self.assertNotIn(token, " ".join(command))
                    if command[:2] == ["docker", "run"]:
                        mounts = [command[i + 1] for i, arg in enumerate(command) if arg == "--mount"]
                        config_file = Path(mounts[0].split("source=", 1)[1].split(",target=", 1)[0])
                        private_files.append(config_file)
                        self.assertEqual(0o600, config_file.stat().st_mode & 0o777)
                        self.assertIn(token, config_file.read_text())
                        return subprocess.CompletedProcess(command, 0, container + "\n", "")
                    return subprocess.CompletedProcess(command, 0, "collector log " + token, "")

                with patch.object(DEMO, "REPO", root), patch.object(DEMO.sys, "platform", "linux"), \
                        patch.object(DEMO, "INGEST_TOKEN", token), patch.object(DEMO.subprocess, "run", side_effect=run):
                    try:
                        with DEMO.isolated_vector() as (actual, log_file):
                            self.assertEqual(container, actual)
                            self.assertEqual("", log_file.read_text())
                            if fail_scenario:
                                raise RuntimeError("scenario failed")
                    except RuntimeError as error:
                        self.assertTrue(fail_scenario)
                        self.assertEqual("scenario failed", str(error))
                self.assertTrue(all(not path.exists() for path in private_files))
                self.assertEqual(["docker", "rm", "--force", "--volumes", container], calls[-1])
                evidence = list((root / ".cache/golden-demo").iterdir())
                self.assertEqual(1, len(evidence))
                self.assertEqual(".log", evidence[0].suffix)
                self.assertNotIn(token, evidence[0].read_text())
                self.assertIn("[REDACTED]", evidence[0].read_text())

    def test_full_stack_uses_the_isolated_real_collector(self):
        workflow = (ROOT / ".github/workflows/full-stack.yml").read_text()
        step = workflow.split("      - name: Run Golden Demo\n", 1)[1].split("\n      - name:", 1)[0]
        self.assertIn("python3 build/demos/golden-demo.py --transport vector --isolated-vector", step)
        self.assertNotIn("continue-on-error", step)

    def exercise(self, run_status="SUCCEEDED", report=None):
        host, ip = "golden-ssh-1791166800", "203.0.113.10"
        alarms = [
            {"id": "brute", "ruleId": "AUTH-BRUTE", "entity": ip},
            {"id": "takeover", "ruleId": "AUTH-BRUTE-SUCCESS", "entity": ip},
            {"id": "privilege", "ruleId": "AUTH-PRIVESC", "entity": host},
            {"id": "story", "ruleId": "CORR-ATTACK-SIGNALS", "entity": host},
        ]
        reads = 0

        def list_alerts(_token):
            nonlocal reads
            reads += 1
            return 200, [] if reads == 1 else alarms

        def request(service, path, **_kwargs):
            if service == "search":
                data = {"events": [{"eventId": "event", "source": "auth", "host": host,
                                    "ecs": {"source.ip": ip}}]}
            elif service == "detect":
                data = {"alerts": 2}
            elif service == "incident":
                data = {"id": "case", "alarmIds": ["takeover"]}
            elif service == "notify":
                data = [{"alarmId": "takeover", "channel": DEMO.DEMO_CHANNEL,
                         "type": "LOG", "status": "logged"}]
            elif service == "soar":
                data = [{"runId": "run", "playbookId": "book", "subject": {"id": "takeover"},
                         "status": run_status}]
            elif service == "report":
                data = report if report is not None else {"source": "clickhouse", "degraded": False, "total": 4}
            else:
                raise AssertionError((service, path))
            return 200, {"code": 0, "data": data}, {}

        output = io.StringIO()
        with patch.object(DEMO.sys, "argv", ["golden-demo.py", "--transport", "ingest"]), \
                patch.object(DEMO.time, "time", return_value=1791166800), \
                patch.object(DEMO, "login", return_value=("fixture", "")), \
                patch.object(DEMO, "login_token", return_value="fixture"), \
                patch.object(DEMO, "ensure_playbook", return_value={"id": "book"}), \
                patch.object(DEMO, "ensure_automation_rule", return_value={"id": "rule"}), \
                patch.object(DEMO, "ensure_channel", return_value={"id": "channel"}), \
                patch.object(DEMO, "detection_ready", return_value={"assignedPartitions": [0]}), \
                patch.object(DEMO, "ingest_lines", return_value=(200, {})), \
                patch.object(DEMO, "list_alerts", side_effect=list_alerts), \
                patch.object(DEMO, "request", side_effect=request), \
                patch.object(DEMO, "wait_for", side_effect=lambda _label, fn, **_kw: fn()), \
                patch.object(DEMO, "verify_case_follow_through", return_value=True, create=True), \
                patch.object(DEMO.atexit, "register"), contextlib.redirect_stdout(output):
            return DEMO.main(), output.getvalue()

    def test_completed_run_and_authoritative_report_pass(self):
        result, output = self.exercise()
        self.assertEqual(result, 0, output)

    def test_a_run_record_is_not_proof_of_successful_execution(self):
        for status in ("QUEUED", "RUNNING", "FAILED", "CANCELED", "ACTION_UNKNOWN", None):
            with self.subTest(status=status):
                result, output = self.exercise(run_status=status)
                self.assertEqual(result, 1, output)

    def test_http_200_cannot_hide_a_degraded_or_invalid_report(self):
        for report in (
            {"source": "alert-web", "degraded": True, "total": 4},
            {"source": "clickhouse", "degraded": True, "total": 4},
            {"source": "clickhouse", "total": 4},
            {"source": "clickhouse", "degraded": False, "total": None},
            {"source": "clickhouse", "degraded": False, "total": 0},
            {"code": 503, "data": None},
        ):
            with self.subTest(report=report):
                result, output = self.exercise(report=report)
                self.assertEqual(result, 1, output)

    def follow_through(self, broken=None):
        note = "Golden Demo probe: reviewed events event; SOAR run run. Simulated attack only; no external containment executed."
        closure = ("analyst: OPEN → CLOSED\nAssignee: analyst → analyst\nClassification: TRUE_POSITIVE"
                   "\nResult: Simulated attack detected; demo investigation completed"
                   "\nReason: Raw evidence and the successful no-action SOAR run were reviewed"
                   "\nEvidence: " + note + "\nRemaining actions: None for this disposable fixture")
        responses = [
            {"alarmId": "alarm", "items": [{"eventId": "event", "raw": "sshd: Accepted password"}]},
            {"id": "case", "rowVersion": 7},
            {"items": ["alarm"], "total": 1},
            {"case": {"id": "case", "rowVersion": 8, "assignee": "analyst"}},
            {"case": {"id": "case", "rowVersion": 9}, "duplicate": False},
            {"case": {"id": "case", "rowVersion": 9}, "duplicate": True},
            {"case": {"id": "case", "rowVersion": 10, "status": "CLOSED"}},
            {"id": "case", "rowVersion": 10, "status": "CLOSED", "assignee": "analyst"},
            {"items": [{"type": "NOTE", "idempotencyKey": "note:golden-note-probe", "message": "analyst: " + note},
                       {"type": "CLOSURE", "idempotencyKey": "change:golden-close-probe", "message": closure}], "total": 2},
            {"id": "case"},
        ]
        if broken == "evidence": responses[0]["items"] = []
        if broken == "association": responses[2]["items"] = ["other-alarm"]
        if broken == "duplicate-receipt": responses[5]["duplicate"] = False
        if broken == "status": responses[7]["status"] = "OPEN"
        if broken == "target": responses[7]["id"] = "other-case"
        if broken == "owner": responses[7]["assignee"] = "other"
        if broken == "duplicate-note": responses[8]["items"].append(responses[8]["items"][0])
        if broken == "note-content": responses[8]["items"][0]["message"] = "different evidence"
        if broken == "closure-material": responses[8]["items"][1]["message"] = note
        if broken == "closure": responses[8]["items"] = responses[8]["items"][:1]
        if broken == "link": responses[9]["id"] = "other-case"
        results = [(200, {"code": 0, "data": body}, {}) for body in responses]
        if broken == "conflict": results[6] = (409, {"code": 409, "message": "Case changed"}, {})
        with patch.object(DEMO, "request", side_effect=results) as request, \
                contextlib.redirect_stdout(io.StringIO()):
            result = DEMO.verify_case_follow_through("fixture", {"id": "alarm"}, {"id": "case"},
                                                    {"runId": "run"}, "probe")
        return result, request

    def test_case_follow_through_reads_back_both_relationships_and_durable_commands(self):
        result, request = self.follow_through()
        self.assertTrue(result)
        calls = request.call_args_list
        self.assertEqual(calls[3].kwargs["body"]["expectedVersion"], 7)
        self.assertEqual(calls[4].kwargs["body"], calls[5].kwargs["body"])
        self.assertEqual(calls[6].kwargs["body"]["expectedVersion"], 9)
        self.assertEqual(calls[6].kwargs["body"]["classification"], "TRUE_POSITIVE")
        self.assertTrue(calls[7].args[1].endswith("/case"))
        self.assertTrue(calls[8].args[1].endswith("/timeline?page=1&size=100"))
        self.assertTrue(calls[9].args[1].endswith("/by-alarm?alarmId=alarm"))

    def test_case_follow_through_does_not_accept_acknowledgement_without_durable_readback(self):
        for broken in ("evidence", "association", "duplicate-receipt", "status", "target", "owner",
                       "duplicate-note", "note-content", "closure-material", "closure", "link", "conflict"):
            with self.subTest(broken=broken):
                result, _ = self.follow_through(broken)
                self.assertFalse(result)


if __name__ == "__main__":
    unittest.main()
