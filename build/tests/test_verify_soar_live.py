import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location(
    "verify_soar_live", ROOT / "build" / "verify-soar-live.py"
)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


class VerifySoarLiveTest(unittest.TestCase):
    def test_normalize_base_adds_service_context_once(self):
        self.assertEqual(
            MODULE.normalize_base("http://localhost:18092/", append_context=True),
            "http://localhost:18092/soar-web",
        )
        self.assertEqual(
            MODULE.normalize_base("http://localhost:18083/soar-web", append_context=True),
            "http://localhost:18083/soar-web",
        )

    def test_unwrap_accepts_platform_envelope_and_bare_payload(self):
        self.assertEqual(MODULE.unwrap({"code": 0, "data": {"ok": True}}), {"ok": True})
        self.assertEqual(MODULE.unwrap({"ok": True}), {"ok": True})

    def test_definition_is_side_effect_free_and_bounded(self):
        definition = MODULE.definition()
        self.assertEqual(definition["schemaVersion"], "soar.playbook/v2")
        self.assertEqual(definition["limits"]["maxNodeExecutions"], 20)
        self.assertEqual([node["type"] for node in definition["nodes"]], ["START", "DELAY", "END"])
        self.assertEqual(definition["nodes"][1]["config"]["durationSeconds"], 5)

    def test_event_carries_tenant_and_bounded_trace(self):
        event = MODULE.event("event-1", "alert.created", "tenant-a")
        self.assertEqual(event["tenantId"], "tenant-a")
        self.assertEqual(event["eventType"], "alert.created")
        self.assertEqual(event["trace"]["automationDepth"], 0)

    def test_evidence_report_is_secret_free_and_atomic(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "live.json"
            with patch.dict("os.environ", {"SOAR_LIVE_EVIDENCE_PATH": str(target)}):
                MODULE.write_evidence("http://primary", "http://secondary", "tenant-a")
            report = json.loads(target.read_text(encoding="utf-8"))
        self.assertEqual(report["schemaVersion"], "soar.live-evidence/v1")
        self.assertEqual(report["status"], "PASS")
        self.assertTrue(report["secondaryConfigured"])
        self.assertNotIn("token", json.dumps(report).lower())


if __name__ == "__main__":
    unittest.main()
