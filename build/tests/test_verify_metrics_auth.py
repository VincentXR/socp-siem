import importlib.util
from pathlib import Path
import shutil
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("metrics_auth", ROOT / "build/verify-metrics-auth.py")
metrics = importlib.util.module_from_spec(spec)
spec.loader.exec_module(metrics)


class MetricsAuthTest(unittest.TestCase):
    def test_repository_contract(self):
        self.assertEqual([], metrics.check_configuration())

    def test_local_file_token_scrape_and_rejection(self):
        metrics.verify_file_scrape()

    def test_old_placeholder_and_missing_worker_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name in ("infra/init-sql/prometheus/prometheus.yml", "infra/init-sql/prometheus/prometheus-prod.yml",
                         "infra/docker-compose.yml", "infra/docker-compose.prod.yml", ".gitignore"):
                destination = root / name
                destination.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(ROOT / name, destination)
            path = root / "infra/init-sql/prometheus/prometheus-prod.yml"
            path.write_text(path.read_text().replace("credentials_file: /run/secrets/socp_metrics_token", "credentials: ${SOCP_SECURITY_METRICS_TOKEN}")
                            .replace('"search-config-worker:8080"', '"unrelated:8080"'))
            errors = metrics.check_configuration(root)
            self.assertTrue(any("placeholder" in error for error in errors))
            self.assertTrue(any("target set" in error for error in errors))


if __name__ == "__main__":
    unittest.main()
