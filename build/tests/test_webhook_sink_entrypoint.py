"""Keep the webhook CLI independent of another test's import-path mutations."""

import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
ENTRYPOINT = ROOT / "build" / "webhook-sink.py"


class WebhookSinkEntrypointTest(unittest.TestCase):
    def run_python(self, *arguments, cwd):
        environment = os.environ.copy()
        environment.pop("PYTHONPATH", None)
        environment["PYTHONDONTWRITEBYTECODE"] = "1"
        result = subprocess.run(
            [sys.executable, "-B", *arguments],
            cwd=cwd,
            env=environment,
            capture_output=True,
            text=True,
            timeout=30,
            check=False,
        )
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        return result

    def test_exact_ci_subset_passes_in_a_clean_process(self):
        # Do not include this module in the child invocation: it would recurse.
        result = self.run_python(
            "-m", "unittest",
            "build/tests/test_verify_ci_gate.py",
            "build/tests/test_ci_scope.py",
            "build/tests/test_webhook_sink.py",
            cwd=ROOT,
        )
        self.assertIn("Ran 10 tests", result.stderr)

    def test_import_by_path_outside_repository_preserves_search_path(self):
        script = """
import importlib.util
import sys
before = list(sys.path)
spec = importlib.util.spec_from_file_location("webhook_sink", sys.argv[1])
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
assert sys.path == before, (before, sys.path)
assert module.WebhookSink.protocol_version == "HTTP/1.1"
assert module.WebhookSinkServer.request_queue_size == 128
"""
        with tempfile.TemporaryDirectory() as directory:
            self.run_python("-I", "-c", script, str(ENTRYPOINT), cwd=directory)

    def test_cli_runs_from_outside_repository(self):
        with tempfile.TemporaryDirectory() as directory:
            result = self.run_python(str(ENTRYPOINT), "--help", cwd=directory)
        self.assertIn("--host", result.stdout)
        self.assertIn("--port", result.stdout)


if __name__ == "__main__":
    unittest.main()
