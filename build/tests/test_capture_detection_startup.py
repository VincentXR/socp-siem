import importlib.util
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch


SPEC = importlib.util.spec_from_file_location(
    "capture_detection_startup",
    Path(__file__).resolve().parents[1] / "capture-detection-startup.py",
)
CAPTURE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CAPTURE)


class CaptureDetectionStartupTest(unittest.TestCase):
    def test_unreachable_health_and_timed_out_attach_preserve_failure_evidence(self):
        with tempfile.TemporaryDirectory() as temporary, patch.object(
            CAPTURE.urllib.request, "urlopen", side_effect=TimeoutError
        ), patch.object(
            CAPTURE.subprocess, "run", side_effect=subprocess.TimeoutExpired("jcmd", 8)
        ) as run:
            result = CAPTURE.capture("jcmd", 18082, 42, Path(temporary))
            self.assertEqual("TimeoutError", result["health"]["errorType"])
            self.assertTrue(result["platform"]["timedOut"])
            self.assertTrue(result["virtual"]["timedOut"])
            self.assertEqual(2, run.call_count)
            for call in run.call_args_list:
                self.assertEqual(8, call.kwargs["timeout"])
                self.assertNotIn("JAVA_TOOL_OPTIONS", call.kwargs["env"])
                self.assertIn(call.args[0][2], ["Thread.print", "Thread.dump_to_file"])

    def test_missing_process_does_not_attach_to_another_jvm(self):
        with tempfile.TemporaryDirectory() as temporary, patch.object(
            CAPTURE.urllib.request, "urlopen", side_effect=ConnectionError
        ), patch.object(CAPTURE.subprocess, "run") as run:
            result = CAPTURE.capture("jcmd", 18082, None, Path(temporary))
            self.assertIn("unavailable", result["threads"])
            run.assert_not_called()


if __name__ == "__main__":
    unittest.main()
