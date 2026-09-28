import importlib.util
from pathlib import Path
import unittest


SPEC = importlib.util.spec_from_file_location(
    "chaos_alert_oracle", Path(__file__).resolve().parents[1] / "chaos-pipeline.py")
CHAOS = importlib.util.module_from_spec(SPEC)
assert SPEC.loader
SPEC.loader.exec_module(CHAOS)


class ChaosAlertOracleTest(unittest.TestCase):
    def test_ignores_an_earlier_run_that_reused_the_same_entity(self):
        alerts = [
            {
                "sourceAlertId": "old-alert",
                "ruleId": "LATERAL-RDP",
                "entity": "10.240.251.21",
                "triggerEventId": "old-run-event",
            },
            {
                "sourceAlertId": "current-alert",
                "ruleId": "LATERAL-RDP",
                "entity": "10.240.251.21",
                "triggerEventId": "current-run-event",
            },
        ]

        scoped = CHAOS.alerts_triggered_by(
            alerts, {"current-run-event"}, {"LATERAL-RDP", "CORR-FAIL-SUDO"})

        self.assertEqual(["current-alert"], [item["sourceAlertId"] for item in scoped])

    def test_keeps_every_supported_alert_triggered_by_the_current_run(self):
        alerts = [
            {"sourceAlertId": "threshold", "ruleId": "LATERAL-RDP",
             "triggerEventId": "event-a"},
            {"sourceAlertId": "correlation", "ruleId": "CORR-FAIL-SUDO",
             "triggerEventId": "event-b"},
            {"sourceAlertId": "unrelated-rule", "ruleId": "OTHER",
             "triggerEventId": "event-a"},
            {"sourceAlertId": "missing-trigger", "ruleId": "LATERAL-RDP"},
        ]

        scoped = CHAOS.alerts_triggered_by(
            alerts, {"event-a", "event-b"}, {"LATERAL-RDP", "CORR-FAIL-SUDO"})

        self.assertEqual(
            {"threshold", "correlation"},
            {item["sourceAlertId"] for item in scoped})


if __name__ == "__main__":
    unittest.main()
