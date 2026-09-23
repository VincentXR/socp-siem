import importlib.util
import io
import json
from pathlib import Path
import sys
import unittest
from unittest import mock

ROOT = Path(__file__).resolve().parents[2]


def script(name, path):
    spec = importlib.util.spec_from_file_location(name, ROOT / path)
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


DEMO = script("rule_demo_fixture", "build/demos/attack-scenarios.py")
CHAOS = script("rule_chaos_fixture", "build/chaos-pipeline.py")


class RuleOperatorClientTest(unittest.TestCase):
    def test_demo_uses_exact_rule_read_conditional_update_and_fresh_activation_token(self):
        original = {"id": "EXEC-SUSPICIOUS-SHELL", "status": "DISABLED", "enabled": False,
                    "revisionToken": "a" * 64, "match": []}
        updated = {**original, "revisionToken": "b" * 64}
        with mock.patch.object(DEMO, "api", side_effect=[(200, original), (200, updated), (200, {})]) as api:
            self.assertTrue(DEMO.ensure_exec_rule("fixture-token")[0])
        self.assertEqual(api.call_args_list[0].args[1], "/detect-web/api/v1/rules/EXEC-SUSPICIOUS-SHELL")
        edit, activate = api.call_args_list[1:]
        self.assertEqual(edit.args[3], "PUT")
        self.assertNotIn("status", edit.args[2])
        self.assertEqual(edit.kwargs["headers"]["If-Match"], '"' + "a" * 64 + '"')
        self.assertTrue(activate.args[1].endswith("/activate"))
        self.assertEqual(activate.kwargs["headers"]["If-Match"], '"' + "b" * 64 + '"')

    def test_demo_activation_uses_publisher_credentials_and_the_reviewed_revision(self):
        current = {"id": "WEB-SHELL", "status": "TESTING", "revisionToken": "d" * 64}
        with mock.patch.object(DEMO, "api", side_effect=[(200, current), (200, {})]) as api:
            self.assertTrue(DEMO.ensure_web_shell_rule("analyst", "publisher")[0])
        self.assertEqual(api.call_args_list[0].args[0], "analyst")
        self.assertEqual(api.call_args_list[1].args[0], "publisher")
        self.assertEqual(api.call_args_list[1].kwargs["headers"]["If-Match"], '"' + "d" * 64 + '"')

    def test_failed_read_or_stale_update_never_falls_back_to_upsert_or_activation(self):
        for method in (DEMO.ensure_exec_rule, DEMO.ensure_web_shell_rule):
            with mock.patch.object(DEMO, "api", return_value=(503, {})) as api:
                self.assertFalse(method("fixture")[0])
                self.assertEqual(api.call_count, 1)
        with mock.patch.object(DEMO, "api", side_effect=[(200, {"revisionToken": "a" * 64}), (412, {})]) as api:
            self.assertFalse(DEMO.ensure_exec_rule("fixture")[0])
            self.assertEqual(api.call_count, 2)

    def test_missing_rule_is_created_then_explicitly_activated(self):
        created = {"id": "WEB-SHELL", "status": "TESTING", "revisionToken": "c" * 64}
        with mock.patch.object(DEMO, "api", side_effect=[(404, {}), (200, created), (200, {})]) as api:
            self.assertTrue(DEMO.ensure_web_shell_rule("fixture")[0])
        self.assertEqual(api.call_args_list[1].args[1], "/detect-web/api/v1/rules")
        self.assertEqual(api.call_args_list[2].kwargs["headers"]["If-Match"], '"' + "c" * 64 + '"')
        with self.assertRaises(RuntimeError):
            DEMO.revision_headers({})

    def test_chaos_http_transport_encodes_dict_text_and_bytes_without_network(self):
        for body in ({"name": "中文"}, '{"name":"text"}', b'{"name":"bytes"}'):
            reply = io.BytesIO(b'{"code":0,"data":{}}')
            reply.status = 200
            with mock.patch.object(CHAOS.urllib.request, "urlopen", return_value=reply) as opener:
                self.assertEqual(CHAOS.request("http://127.0.0.1/fixture", method="PUT", body=body)[0], 200)
            sent = opener.call_args.args[0].data
            self.assertEqual(json.loads(sent), body if isinstance(body, dict) else json.loads(body))
            if isinstance(body, dict):
                self.assertEqual(opener.call_args.args[0].get_header("Content-type"), "application/json")


if __name__ == "__main__":
    unittest.main()
