"""Exercise non-admission/retry without weakening the attack outcome assertions."""
import contextlib
import importlib.util
import io
import json
from pathlib import Path
import unittest
from unittest.mock import patch
from urllib.error import HTTPError


spec = importlib.util.spec_from_file_location(
    "attack_scenarios", Path(__file__).resolve().parents[1] / "demos/attack-scenarios.py")
attack = importlib.util.module_from_spec(spec)
spec.loader.exec_module(attack)

REJECTED = {"code": 503, "message": "queue_full",
            "data": {"accepted": False, "queueLoad": 0.0, "error": "queue_full"}}
ACCEPTED = {"code": 0, "data": {"accepted": True}}


class AttackRetryTest(unittest.TestCase):
    def test_reload_rejection_retries_same_event_and_honors_retry_after(self):
        log = {"source": "edr", "msg": "powershell -enc sample"}
        responses = [(503, REJECTED, {"Retry-After": "2"}), (200, ACCEPTED, {})]
        with patch.object(attack, "api", side_effect=responses) as send, \
                patch.object(attack.time, "sleep") as sleep, contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(attack.ingest_event("token", log), (200, ACCEPTED))
        sleep.assert_called_once_with(2.0)
        self.assertEqual(send.call_args_list[0], send.call_args_list[1])
        sent = send.call_args.args[2]
        self.assertTrue(sent["eventId"])
        self.assertTrue(sent["timestamp"])
        self.assertEqual(send.call_args.kwargs["headers"]["Idempotency-Key"], sent["eventId"])
        self.assertNotIn("eventId", log)

    def test_permanent_non_admission_still_fails_at_deadline(self):
        with patch.object(attack, "api", return_value=(503, REJECTED, {"Retry-After": "2"})) as send, \
                patch.object(attack.time, "monotonic", side_effect=[0, 0, 2]), \
                patch.object(attack.time, "sleep"), contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(attack.ingest_event("token", {}, timeout=3), (503, REJECTED))
        self.assertEqual(send.call_count, 2)

    def test_no_retry_on_auth_failure_or_ambiguous_server_failure(self):
        for status, body in ((401, {}), (500, {}), (503, {}),
                             (503, {"data": {"accepted": True}})):
            with self.subTest(status=status, body=body), \
                    patch.object(attack, "api", return_value=(status, body, {})) as send:
                self.assertEqual(attack.ingest_event("token", {}), (status, body))
                send.assert_called_once()

    def test_http_error_retains_rejection_envelope_and_retry_header(self):
        error = HTTPError("http://localhost", 503, "unavailable", {"Retry-After": "2"},
                          io.BytesIO(json.dumps(REJECTED).encode()))
        with patch.object(attack.urllib.request, "urlopen", side_effect=error):
            self.assertEqual(attack.api("token", "/detect-web/api/v1/ingest", {},
                                        include_headers=True),
                             (503, REJECTED, {"Retry-After": "2"}))
        self.assertTrue(error.closed)

    def test_http_error_closes_malformed_response_without_retry_evidence(self):
        error = HTTPError("http://localhost", 503, "unavailable", {}, io.BytesIO(b"not JSON"))
        with patch.object(attack.urllib.request, "urlopen", side_effect=error):
            self.assertEqual(attack.api("token", "/detect-web/api/v1/ingest", {}), (503, {}))
        self.assertTrue(error.closed)
