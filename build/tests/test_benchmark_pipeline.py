import importlib.util
import os
from pathlib import Path
import unittest
from unittest.mock import patch


SCRIPT = Path(__file__).resolve().parents[1] / "benchmark-pipeline.py"
SPEC = importlib.util.spec_from_file_location("benchmark_pipeline", SCRIPT)
benchmark_pipeline = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(benchmark_pipeline)


class BenchmarkIdentityContractTest(unittest.TestCase):

    def test_e2e_uses_direct_registered_collector_boundary(self):
        environment = {
            "BENCH_INGEST_URL": "http://search/ingest",
            "BENCH_COLLECTOR_ID": "benchmark-a",
            "BENCH_COLLECTOR_TOKEN": "collector-secret",
        }
        with patch.dict(os.environ, environment, clear=True):
            endpoint, headers = benchmark_pipeline.ingest_endpoint_and_headers(
                "http://gateway", "e2e", "user-jwt")

        self.assertEqual("http://search/ingest", endpoint)
        self.assertEqual("Bearer collector-secret", headers["Authorization"])
        self.assertEqual("benchmark-a", headers["X-SOCP-Collector"])
        self.assertNotIn("user-jwt", headers["Authorization"])

    def test_bulk_keeps_user_authenticated_detection_boundary(self):
        endpoint, headers = benchmark_pipeline.ingest_endpoint_and_headers(
            "http://gateway", "bulk", "user-jwt")

        self.assertEqual("http://gateway/detect-web/api/v1/ingest/bulk", endpoint)
        self.assertEqual("Bearer user-jwt", headers["Authorization"])
        self.assertNotIn("X-SOCP-Collector", headers)


if __name__ == "__main__":
    unittest.main()
