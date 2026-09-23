"""Native Windows Maven wrapper behavior under redirected JVM warnings."""

import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
POWERSHELL = shutil.which("powershell")


@unittest.skipUnless(os.name == "nt" and POWERSHELL, "requires Windows PowerShell 5.1")
class MavenPowerShellTest(unittest.TestCase):
    def invoke(self, exit_code):
        with tempfile.TemporaryDirectory(prefix="socp-maven-wrapper-") as temporary:
            directory = Path(temporary)
            (directory / "mvn.cmd").write_text(
                "@echo off\necho JVM warning 1>&2\necho Maven finished\n"
                f"exit /b {exit_code}\n",
                encoding="ascii",
            )
            environment = {**os.environ, "PATH": str(directory) + os.pathsep + os.environ["PATH"]}
            wrapper = str(ROOT / "build/mvnw.ps1").replace("'", "''")
            report = str(directory / "maven.log").replace("'", "''")
            command = (
                "$ErrorActionPreference = 'Stop'; "
                f"& '{wrapper}' -version *> '{report}'"
            )
            result = subprocess.run(
                [POWERSHELL, "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", command],
                env=environment, capture_output=True, timeout=30, check=False,
            )
            log = (directory / "maven.log").read_text(encoding="utf-16")
            return result.returncode, log, result.stderr

    def test_redirected_warning_does_not_abort_a_successful_build(self):
        code, log, _ = self.invoke(0)
        self.assertEqual(code, 0)
        self.assertIn("JVM warning", log)
        self.assertIn("Maven finished", log)

    def test_native_failure_still_fails_the_wrapper(self):
        code, log, error = self.invoke(7)
        self.assertNotEqual(code, 0)
        self.assertIn("Maven finished", log)
        self.assertIn(b"Maven exited with code 7", error)


if __name__ == "__main__":
    unittest.main()
