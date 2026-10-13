import importlib.util
import json
from pathlib import Path
import unittest
from unittest.mock import Mock, patch
import urllib.error
import urllib.request


ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("vector_delivery_probe", ROOT / "build/vector-delivery-probe.py")
PROBE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PROBE)


def body(event_id="one"):
    return json.dumps({"eventId": "stable-envelope-id", "parse_format": "auto",
                       "message": PROBE.input_event(event_id).strip()}) + "\n"


class VectorDeliveryProbeTest(unittest.TestCase):
    def test_exact_envelope_and_original_fields_are_required(self):
        self.assertEqual("one", PROBE.inspect_body(body()))
        for bad in ("{}", body() + body(), body().replace("203.0.113.9", "203.0.113.10"),
                    body().replace("stable-envelope-id", ""), body().replace("auth-host", "changed")):
            with self.subTest(body=bad[:60]):
                with self.assertRaises((ValueError, KeyError)):
                    PROBE.inspect_body(bad)

    def test_nine_attempts_one_body_per_expected_identity(self):
        good = {"attempts": {"one": 9}, "distinct_bodies": {"one": 1},
                "accepted": ["one"], "errors": []}
        PROBE.validate_delivery(good, ["one"])
        for key, value in (("attempts", {"one": 8}), ("attempts", {"one": 10}),
                           ("distinct_bodies", {"one": 2}), ("accepted", []),
                           ("accepted", ["one", "phantom"]), ("errors", ["bad JSON"])):
            with self.subTest(key=key, value=value):
                with self.assertRaises(AssertionError):
                    PROBE.validate_delivery({**good, key: value}, ["one"])

    def test_fixture_returns_eight_503_then_200_per_event(self):
        receiver = PROBE.Receiver()
        try:
            for event_id in ("first", "second"):
                statuses = []
                for _ in range(9):
                    request = urllib.request.Request(
                        f"http://127.0.0.1:{receiver.server.server_port}/ingest",
                        data=body(event_id).encode(), method="POST")
                    try:
                        with urllib.request.urlopen(request, timeout=2) as response:
                            statuses.append(response.status)
                    except urllib.error.HTTPError as response:
                        statuses.append(response.code)
                        response.close()
                self.assertEqual([503] * 8 + [200], statuses)
            # Joining the request thread is unnecessary: status has been sent
            # before bookkeeping, so wait for that explicit condition briefly.
            import time
            deadline = time.monotonic() + 2
            while len(receiver.snapshot()["accepted"]) < 2 and time.monotonic() < deadline:
                time.sleep(0.01)
            PROBE.validate_delivery(receiver.snapshot(), ["first", "second"])
            receiver.reset_for_recovery()
            self.assertEqual({}, receiver.snapshot()["attempts"])
            self.assertEqual([], receiver.snapshot()["accepted"])
        finally:
            receiver.close()

    def test_late_tenth_attempt_fails_the_final_quiet_window(self):
        good = {"attempts": {"one": 9}, "distinct_bodies": {"one": 1},
                "accepted": ["one"], "errors": []}
        receiver = Mock()
        receiver.snapshot.side_effect = [good, {**good, "attempts": {"one": 10}}]
        process = Mock()
        process.poll.return_value = None
        with patch.object(PROBE.time, "monotonic", side_effect=[0, 0.1, 0.2]), \
                patch.object(PROBE.time, "sleep"):
            with self.assertRaisesRegex(AssertionError, "eight 503"):
                PROBE.assert_final_quiet(process, receiver, ["one"])

    def test_config_keeps_disk_capacity_and_retry_budget(self):
        text = PROBE.config_text(Path("/tmp/fixture"), 12345, "disk")
        self.assertIn('buffer.type = "disk"', text)
        self.assertIn("buffer.max_size = 268435488", text)
        self.assertIn('buffer.when_full = "block"', text)
        self.assertNotIn("retry_attempts", text)
        self.assertIn("batch.timeout_secs = 2", text)
        self.assertIn('.eventId = uuid_v4()', text)
        memory = PROBE.config_text(Path("/tmp/fixture"), 12345, "memory")
        self.assertNotIn("buffer.max_size", memory)
        self.assertIn("buffer.max_events = 500", memory)


if __name__ == "__main__":
    unittest.main()
