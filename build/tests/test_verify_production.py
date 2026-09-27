"""Production profile checks must accept only explicit fail-closed overlays."""

import importlib.util
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch


BUILD = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(BUILD))
spec = importlib.util.spec_from_file_location("verify_production", BUILD / "verify-production.py")
production = importlib.util.module_from_spec(spec)
spec.loader.exec_module(production)


class VerifyProductionDefaultsTest(unittest.TestCase):
    def write_service(self, root: Path, production_tls: str | None) -> None:
        resources = root / "services/search-config/src/main/resources"
        resources.mkdir(parents=True)
        (resources / "application.yml").write_text(
            """socp:
  opensearch:
    tls:
      insecure-skip-verify: ${SOCP_OPENSEARCH_INSECURE_SKIP_VERIFY:true}
""",
            encoding="utf-8",
        )
        if production_tls is not None:
            (resources / "application-prod.yml").write_text(
                """socp:
  opensearch:
    tls:
      insecure-skip-verify: """ + production_tls + "\n",
                encoding="utf-8",
            )

    def test_explicit_fail_closed_production_overlay_is_accepted(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.write_service(root, "${SOCP_OPENSEARCH_INSECURE_SKIP_VERIFY:false}")
            errors = []
            with patch.object(production, "ROOT", root):
                production.check_service_defaults(errors)

        self.assertEqual([], errors)

    def test_missing_production_override_reports_real_service_name(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.write_service(root, None)
            errors = []
            with patch.object(production, "ROOT", root):
                production.check_service_defaults(errors)

        self.assertEqual(1, len(errors))
        self.assertTrue(errors[0].startswith("search-config:"), errors[0])


if __name__ == "__main__":
    unittest.main()
