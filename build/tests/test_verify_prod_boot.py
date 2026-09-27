"""Production boot smoke must isolate asymmetric auth from CI dev secrets."""

import importlib.util
from pathlib import Path
import unittest


BUILD = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("verify_prod_boot", BUILD / "verify-prod-boot.py")
prod_boot = importlib.util.module_from_spec(spec)
spec.loader.exec_module(prod_boot)


class VerifyProdBootEnvironmentTest(unittest.TestCase):
    def test_production_children_drop_development_hmac_fallbacks(self):
        source = {
            "SOCP_JWT_SECRET": "j" * 32,
            "SOCP_LOGIN_SECRET": "l" * 32,
            "SOCP_SECURITY_ALLOW_PROD_HMAC": "true",
            "SOCP_AUTH_ISSUER": "https://issuer.example",
            "SOCP_PG_RUNTIME_USER": "runtime",
            "SOCP_PG_RUNTIME_PASSWORD": "runtime-password",
        }

        actual = prod_boot.production_environment(source)

        for name in prod_boot.DEVELOPMENT_AUTH_ENV:
            self.assertNotIn(name, actual)
        self.assertEqual(actual["SOCP_SECURITY_ISSUER_URI"], source["SOCP_AUTH_ISSUER"])
        self.assertEqual(actual["SOCP_PG_USER"], source["SOCP_PG_RUNTIME_USER"])
        self.assertEqual(actual["SOCP_PG_PASSWORD"], source["SOCP_PG_RUNTIME_PASSWORD"])
        self.assertEqual(actual["SOCP_SECURITY_AUDIENCE"], "socp-api")
        self.assertIn("SOCP_JWT_SECRET", source)

    def test_explicit_production_values_are_preserved(self):
        source = {
            "SOCP_AUTH_ISSUER": "https://issuer.example",
            "SOCP_PG_RUNTIME_USER": "runtime",
            "SOCP_PG_RUNTIME_PASSWORD": "runtime-password",
            "SOCP_SECURITY_AUDIENCE": "custom-audience",
            "SOCP_RATELIMIT_BACKEND": "redis-cluster",
        }

        actual = prod_boot.production_environment(source)

        self.assertEqual(actual["SOCP_SECURITY_AUDIENCE"], "custom-audience")
        self.assertEqual(actual["SOCP_RATELIMIT_BACKEND"], "redis-cluster")


if __name__ == "__main__":
    unittest.main()
