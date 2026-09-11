import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


BUILD = Path(__file__).resolve().parents[1]
CORE = "alert-web search-config detect-web incident-web soar-web notify-web report-web api-gateway".split()


class RunAllDoctorTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / "build").mkdir()
        for name in ("run-all.sh", "ports.env", "toolchain.sh"):
            shutil.copyfile(BUILD / name, self.root / "build" / name)
        (self.root / "pom.xml").write_text("<project/>")
        self.java_home = self.root / "jdk"
        (self.java_home / "bin").mkdir(parents=True)

    def java(self, major):
        executable = self.java_home / "bin" / "java"
        executable.write_text(f'#!/bin/sh\necho \'openjdk version "{major}.0.1"\' >&2\n')
        executable.chmod(0o755)

    def jars(self):
        for service in CORE:
            target = self.root / "services" / service / "target"
            target.mkdir(parents=True)
            (target / f"{service}-1.0.0-SNAPSHOT.jar").write_bytes(b"test artifact")

    def doctor(self, profile="core"):
        return subprocess.run(
            ["bash", str(self.root / "build/run-all.sh"), "doctor", profile],
            env={**os.environ, "JAVA_HOME": str(self.java_home)},
            text=True, capture_output=True, timeout=20,
        )

    def test_java_17_cannot_pass_even_when_artifacts_exist(self):
        self.java(17)
        self.jars()
        result = self.doctor()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Java 21+", result.stdout)

    def test_missing_selected_service_is_a_failure(self):
        self.java(21)
        result = self.doctor()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("detect-web", result.stdout)

    def test_core_ignores_unselected_services_and_collectors(self):
        self.java(21)
        self.jars()
        result = self.doctor()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertNotIn("缺少可用 jar: asset", result.stdout)
        self.assertNotIn("缺少可用 jar: hips", result.stdout)
        self.assertNotEqual(0, self.doctor("full").returncode)
        self.assertNotEqual(0, self.doctor("unknown").returncode)


if __name__ == "__main__":
    unittest.main()
