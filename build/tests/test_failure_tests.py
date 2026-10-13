"""Fault-probe orchestration contracts; real recovery runs in Full Stack CI."""
import contextlib
import importlib.util
import io
from pathlib import Path
import unittest
from unittest.mock import patch


SPEC = importlib.util.spec_from_file_location(
    "failure_tests", Path(__file__).resolve().parents[1] / "failure-tests.py")
PROBE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PROBE)


class TemporalOutageProbeTest(unittest.TestCase):
    def exercise(self, fault=None):
        calls, transitions = [], []
        down = False

        def docker(action, container):
            nonlocal down
            self.assertEqual("socp-temporal", container)
            down = action == "stop"
            transitions.append(action)

        def api(path, method="GET", body=None, **kwargs):
            calls.append((path, method, body, kwargs, down))
            if path.endswith("/playbooks/import"):
                self.assertFalse(down)
                self.assertEqual("publisher", kwargs["bearer"])
                self.assertEqual({"START", "END"}, {n["type"] for n in body["definition"]["nodes"]})
                value = {"playbookId": "owned-book", "id": "owned-version", "version": 7}
            elif path.endswith("/owned-book/versions/7/validate"):
                value = {"valid": fault != "invalid-definition"}
            elif path.endswith("/owned-book/versions/7/publish"):
                value = {"status": "PUBLISHED", "id": "owned-version"}
            elif path == "/soar-web/api/runs":
                self.assertTrue(down)
                self.assertEqual("owned-version", body["playbookVersionId"])
                self.assertNotIn("bearer", kwargs)  # ordinary analyst, not publisher authority
                if fault == "admission-error":
                    raise RuntimeError("admission failed")
                value = {"runId": "owned-run", "status": "QUEUED"}
                if fault == "executed-offline":
                    value["status"] = "SUCCEEDED"
            elif path == "/soar-web/api/runs/owned-run":
                value = {"runId": "owned-run", "status": "QUEUED" if down else "SUCCEEDED"}
                if fault == "lost-queue" and down:
                    return 404, {"code": 404, "data": None}
                if fault == "recovery-failed" and not down:
                    value["status"] = "FAILED"
                if fault == "never-recovers" and not down:
                    value["status"] = "QUEUED"
                if fault == "different-run":
                    value["runId"] = "another-run"
            elif path == "/soar-web/api/playbooks/owned-book" and method == "PATCH":
                self.assertFalse(down)
                self.assertEqual({"status": "ARCHIVED"}, body)
                value = {}
            else:
                self.fail(f"probe must not list or run arbitrary playbooks: {path}")
            return 200, {"code": 0, "data": value}

        with patch.object(PROBE, "api", side_effect=api), \
                patch.object(PROBE, "docker", side_effect=docker), \
                patch.object(PROBE, "login_token", return_value="publisher"), \
                patch.object(PROBE.time, "sleep"), patch.object(PROBE, "PASS", 0), \
                patch.object(PROBE.time, "monotonic", side_effect=[0, 1, 91]), \
                patch.object(PROBE, "FAIL", 0), contextlib.redirect_stdout(io.StringIO()):
            PROBE.temporal_outage()
            failures = PROBE.FAIL
        return failures, calls, transitions

    def test_owns_a_no_action_fixture_and_reads_the_same_run_before_and_after_recovery(self):
        failures, calls, transitions = self.exercise()
        self.assertEqual(0, failures)
        self.assertEqual(["stop", "start"], transitions)
        reads = [call for call in calls if call[0] == "/soar-web/api/runs/owned-run"]
        self.assertEqual([True, False], [call[4] for call in reads])

    def test_invalid_fixture_does_not_stop_the_dependency(self):
        failures, _, transitions = self.exercise("invalid-definition")
        self.assertGreater(failures, 0)
        self.assertEqual([], transitions)

    def test_bad_admission_persistence_or_recovery_cannot_pass_and_temporal_is_restored(self):
        for fault in ("admission-error", "executed-offline", "lost-queue", "recovery-failed",
                      "never-recovers", "different-run"):
            with self.subTest(fault=fault):
                failures, _, transitions = self.exercise(fault)
                self.assertGreater(failures, 0)
                self.assertEqual(["stop", "start"], transitions)


if __name__ == "__main__":
    unittest.main()
