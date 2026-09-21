"""Job-local credentials must agree across producers, services and probes."""
import contextlib
from datetime import datetime, timezone
import importlib.util
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import MagicMock, patch


BUILD = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("ci_credentials", BUILD / "prepare-ci-credentials.py")
ci = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ci)
spec = importlib.util.spec_from_file_location("ci_auth_client", BUILD / "auth_client.py")
auth = importlib.util.module_from_spec(spec)
spec.loader.exec_module(auth)


class CiCredentialsTest(unittest.TestCase):
    def test_independent_jobs_and_shared_credentials_within_each_job(self):
        first = ci.credentials("integration")
        second = ci.credentials("integration")
        for name in ("SOCP_JWT_SECRET", "SOCP_PG_RUNTIME_PASSWORD", "SOCP_PG_MIGRATION_PASSWORD",
                     "SOCP_PG_BOOTSTRAP_PASSWORD", "SOCP_CK_PASSWORD", "SOCP_MINIO_SECRET",
                     "SOCP_OPENSEARCH_PASSWORD", "PIPELINE_COLLECTOR_TOKEN"):
            self.assertGreaterEqual(len(first[name].encode()), 32)
            self.assertNotEqual(first[name], second[name])
        self.assertEqual(first["SOCP_JWT_SECRET"], first["SOCP_LOGIN_SECRET"])
        self.assertEqual(first["SOCP_PG_PASSWORD"], first["SOCP_PG_RUNTIME_PASSWORD"])
        self.assertNotEqual(first["SOCP_PG_RUNTIME_PASSWORD"], first["SOCP_PG_MIGRATION_PASSWORD"])
        self.assertEqual(first["PIPELINE_CK_AUTH"], "default:" + first["SOCP_CK_PASSWORD"])
        self.assertEqual(first["PIPELINE_OS_AUTH"], "admin:" + first["SOCP_OPENSEARCH_PASSWORD"])
        collector, tenant, token, expires = first["SOCP_COLLECTOR_CREDENTIALS"].split("|")
        self.assertEqual((collector, tenant), ("ci-chaos", "default"))
        for name in ("SOCP_INGEST_TOKEN", "SOCP_VECTOR_TOKEN", "PIPELINE_COLLECTOR_TOKEN"):
            self.assertEqual(token, first[name])
        remaining = (datetime.fromisoformat(expires) - datetime.now(timezone.utc)).total_seconds()
        self.assertGreater(remaining, 23 * 3600)
        self.assertLessEqual(remaining, 24 * 3600)
        users = json.loads(first["SOCP_AUTH_USERS"])
        self.assertEqual(users["demo"], first["DEMO_PASS"])
        self.assertEqual(users["demo"], first["PIPELINE_PASS"])
        self.assertEqual(users["admin"], first["SOAR_VERIFY_PASSWORD"])
        self.assertEqual(users["admin"], first["RULE_VERIFY_PASSWORD"])

    def test_runner_environment_round_trip_and_masking(self):
        values = ci.credentials("integration")
        with tempfile.TemporaryDirectory() as directory:
            destination = Path(directory) / "runner-env"
            output = io.StringIO()
            with contextlib.redirect_stdout(output):
                ci.write_environment(values, destination)
            actual = dict(line.split("=", 1) for line in destination.read_text().splitlines())
        self.assertEqual(values, actual)
        masks = output.getvalue().splitlines()
        for value in values.values():
            self.assertIn("::add-mask::" + value, masks)
        for password in json.loads(values["SOCP_AUTH_USERS"]).values():
            self.assertIn("::add-mask::" + password, masks)
        self.assertTrue(all(line.startswith("::add-mask::") for line in masks))

    def test_build_scope_does_not_override_application_test_users(self):
        self.assertEqual(set(ci.credentials("build")), {"SOCP_JWT_SECRET", "SOCP_LOGIN_SECRET"})

    def test_shared_login_uses_generated_user_and_preserves_explicit_password(self):
        values = ci.credentials("integration")
        response = MagicMock()
        response.status = 200
        response.read.return_value = b'{}'
        response.headers = {"Set-Cookie": "SOCP_SESSION=test-session; HttpOnly"}
        response.__enter__.return_value = response
        with patch.dict(os.environ, values), patch.object(auth.urllib.request, "urlopen", return_value=response) as send:
            self.assertEqual(auth.login_token("http://localhost"), "test-session")
            payload = json.loads(send.call_args.args[0].data)
            self.assertEqual(payload["password"], values["DEMO_PASS"])
            auth.login_token("http://localhost", "admin", "explicit-value")
            self.assertEqual(json.loads(send.call_args.args[0].data)["password"], "explicit-value")
