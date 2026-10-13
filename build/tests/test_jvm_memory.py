"""A successful response must not hide exhausted JVM memory in live CI services."""
import contextlib
import importlib.util
import io
from pathlib import Path
import tempfile
import unittest

import yaml


ROOT = Path(__file__).resolve().parents[2]


def gate():
    spec = importlib.util.spec_from_file_location("jvm_memory", ROOT / "build/verify-jvm-memory.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class JvmMemoryTest(unittest.TestCase):
    def test_incident_budget_is_service_local_and_gate_runs_after_failures(self):
        job = yaml.safe_load((ROOT / ".github/workflows/full-stack.yml").read_text())["jobs"]["full-stack-heavy"]
        self.assertIn("-XX:MaxMetaspaceSize=192m", job["env"].get("SOCP_INCIDENT_WEB_JVM_OPTS", ""))
        self.assertIn("-XX:MaxMetaspaceSize=128m", job["env"]["SOCP_JVM_OPTS"])
        steps = [step for step in job["steps"] if "build/verify-jvm-memory.py" in step.get("run", "")]
        self.assertEqual(1, len(steps))
        self.assertEqual("always()", steps[0]["if"])

    def test_regular_gc_and_configuration_flags_are_not_failures(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "incident-web.log").write_text(
                "JVM flags: -XX:MaxMetaspaceSize=192m -XX:+HeapDumpOnOutOfMemoryError\n"
                "Metaspace: 131072K used\nRedis connection restored\n")
            self.assertEqual(0, gate().main(["--root", directory]))

    def test_rejects_wrapped_and_direct_memory_failures_without_reprinting_secrets(self):
        for failure in ("rootCause=java.lang.OutOfMemoryError memoryArea=Metaspace",
                        "java.lang.OutOfMemoryError: Java heap space",
                        "io.netty.util.internal.OutOfDirectMemoryError: cannot reserve",
                        "There is insufficient memory for the Java Runtime Environment"):
            with self.subTest(failure=failure), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                (root / "incident-web.log").write_text("started\n" + failure + " private-credential\nrecovered\n")
                output = io.StringIO()
                with contextlib.redirect_stdout(output):
                    self.assertEqual(1, gate().main(["--root", directory]))
                self.assertIn("incident-web.log:2", output.getvalue())
                self.assertNotIn("private-credential", output.getvalue())

    def test_includes_restarted_instances_and_production_boot_logs(self):
        for relative in ("detection-cluster/detect-18082.log", "prod-boot/incident-web.log",
                         "chaos/rollback-example/before-primary/detect-18082.log"):
            with self.subTest(relative=relative), tempfile.TemporaryDirectory() as directory:
                log = Path(directory) / relative
                log.parent.mkdir(parents=True)
                log.write_text("java.lang.OutOfMemoryError: Metaspace\n")
                self.assertEqual(1, gate().main(["--root", directory]))

    def test_missing_runtime_evidence_is_not_success(self):
        with tempfile.TemporaryDirectory() as directory:
            self.assertEqual(1, gate().main(["--root", directory]))

    def test_does_not_scan_test_reports_or_secret_directories(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "incident-web.log").write_text("started\n")
            for name in ("secrets", "test-results", "vector-runtime"):
                (root / name).mkdir()
                (root / name / "unrelated.log").write_text("java.lang.OutOfMemoryError\n")
            self.assertEqual(0, gate().main(["--root", directory]))


if __name__ == "__main__":
    unittest.main()
