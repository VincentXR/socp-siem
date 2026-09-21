"""Polling deadlines must not masquerade as a drained migration baseline."""
import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch


spec = importlib.util.spec_from_file_location(
    "chaos_wait_test", Path(__file__).resolve().parents[1] / "chaos-pipeline.py")
chaos = importlib.util.module_from_spec(spec)
spec.loader.exec_module(chaos)


class ChaosWaitTest(unittest.TestCase):
    def test_false_or_empty_results_at_deadline_are_explicit_timeouts(self):
        for value in (False, None, {}, []):
            with self.subTest(value=value), \
                    patch.object(chaos.time, "monotonic", side_effect=[0, 0, 2]), \
                    patch.object(chaos.time, "sleep"):
                self.assertIsNone(chaos.wait_for(lambda: value, timeout=1))

    def test_recovery_returns_the_successful_evidence(self):
        evidence = {"lag": 0, "partitions": 6}
        values = iter([False, evidence])
        with patch.object(chaos.time, "monotonic", side_effect=[0, 0, 0.5]), \
                patch.object(chaos.time, "sleep"):
            self.assertIs(evidence, chaos.wait_for(lambda: next(values), timeout=1))
