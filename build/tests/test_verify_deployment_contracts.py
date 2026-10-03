"""Mutation regressions for deployment-only source contracts."""
import importlib.util
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

BUILD = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(BUILD))


def load(name):
    spec = importlib.util.spec_from_file_location(name.replace("-", "_"), BUILD / f"{name}.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


production = load("verify-production")
compose = load("verify-prod-compose")


class DeploymentContractsTest(unittest.TestCase):
    def test_credential_and_partition_contract_accepts_repository(self):
        errors = []
        production.check_deployment_credentials(errors)
        self.assertEqual([], errors)

    def test_missing_secret_and_overscaled_worker_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            chart = Path(directory)
            for filename in ("values.yaml", "values-product.yaml"):
                text = (production.HELM_CHART / filename).read_text()
                text = text.replace("      SOCP_VECTOR_TOKEN: SOCP_VECTOR_TOKEN\n", "")
                text = text.replace("  detectionPartitions: 6", "  detectionPartitions: 1")
                (chart / filename).write_text(text)
            with patch.object(production, "HELM_CHART", chart), patch.object(production, "HELM_VALUES", chart / "values.yaml"):
                errors = []
                production.check_deployment_credentials(errors)
            self.assertEqual(3, len(errors))
            self.assertTrue(any("HPA" in error for error in errors))

    def test_prometheus_merge_requires_explicit_file_and_production_target_config(self):
        base = compose.parse_compose(compose.BASE_COMPOSE.read_text())
        overlay = compose.parse_compose(compose.PROD_COMPOSE.read_text())
        errors = []
        compose.check_metrics(errors, base, overlay, compose.merge_compose(base, overlay))
        self.assertEqual([], errors)
        overlay.pop("secrets")
        overlay["services"].pop("prometheus")
        compose.check_metrics(errors, base, overlay, compose.merge_compose(base, overlay))
        self.assertEqual(2, len(errors))


if __name__ == "__main__":
    unittest.main()
