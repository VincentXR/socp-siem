import argparse
import importlib.util
from pathlib import Path
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "benchmark-series.py"
SPEC = importlib.util.spec_from_file_location("benchmark_series", SCRIPT)
benchmark_series = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(benchmark_series)


def args():
    return argparse.Namespace(
        mode="e2e",
        profile="realistic",
        count=50_000,
        batch_size=500,
        rounds=5,
        instances=1,
        rules=39,
        tolerance=0.15,
    )


def report(eps, commit="abc"):
    return {
        "status": "passed",
        "endToEndEventsPerSecond": eps,
        "machine": {"gitCommit": commit},
    }


class BenchmarkSeriesContractTest(unittest.TestCase):

    def test_accepts_stable_same_commit_rounds(self):
        result = benchmark_series.build_series_report(
            args(), [report(value) for value in (100, 98, 101, 97, 99)])

        self.assertEqual("passed", result["status"])
        self.assertTrue(result["commitConsistent"])
        self.assertFalse(result["stableBaseline"]["sustainedMonotonicDecline"])
        self.assertTrue(result["stableBaseline"]["throughputStable"])

    def test_rejects_sustained_monotonic_decline_within_tolerance(self):
        result = benchmark_series.build_series_report(
            args(), [report(value) for value in (100, 99, 98, 97, 96)])

        self.assertEqual("failed", result["status"])
        self.assertTrue(result["stableBaseline"]["sustainedMonotonicDecline"])
        self.assertFalse(result["stableBaseline"]["throughputStable"])

    def test_rejects_mixed_commit_rounds(self):
        rounds = [report(value) for value in (100, 98, 101, 97)]
        rounds.append(report(99, commit="def"))

        result = benchmark_series.build_series_report(args(), rounds)

        self.assertEqual("failed", result["status"])
        self.assertFalse(result["commitConsistent"])
        self.assertFalse(result["stableBaseline"]["throughputStable"])


if __name__ == "__main__":
    unittest.main()
