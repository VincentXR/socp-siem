"""Consumer completion cannot substitute for a durable producer receipt."""
import importlib.util
import json
from pathlib import Path
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch


SPEC = importlib.util.spec_from_file_location(
    "chaos_route_publication", Path(__file__).resolve().parents[1] / "chaos-pipeline.py")
CHAOS = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CHAOS)


class RoutePublicationWaitTest(unittest.TestCase):
    def exercise(self, snapshots, clock):
        ctx = SimpleNamespace(
            psql_scalar=Mock(side_effect=[json.dumps(value) for value in snapshots]),
            wait_for=CHAOS.wait_for)
        with patch.object(CHAOS.time, "monotonic", side_effect=clock), \
                patch.object(CHAOS.time, "sleep"):
            result = CHAOS.multi_instance.wait_for_route_publication(ctx, {"event-a", "event-b"})
        return result, ctx.psql_scalar.call_count

    def test_acknowledged_kafka_send_waits_for_expired_claim_recovery(self):
        result, reads = self.exercise([
            {"sourceEvents": 2, "unfinished": 1},
            {"sourceEvents": 2, "unfinished": 1},
            {"sourceEvents": 2, "unfinished": 0},
        ], [0, 0, 60, 123])
        self.assertEqual({"sourceEvents": 2, "unfinished": 0, "completed": True}, result)
        self.assertEqual(3, reads)

    def test_permanently_unfinished_or_missing_sources_fail_at_the_deadline(self):
        for snapshot in ({"sourceEvents": 2, "unfinished": 1},
                         {"sourceEvents": 1, "unfinished": 0}):
            with self.subTest(snapshot=snapshot):
                result, reads = self.exercise([snapshot], [0, 0, 241])
                self.assertEqual({**snapshot, "completed": False}, result)
                self.assertEqual(1, reads)


if __name__ == "__main__":
    unittest.main()
