import contextlib
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("collect_evidence", ROOT / "build/collect-evidence.py")
EVIDENCE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(EVIDENCE)
COMMIT = "a" * 40


class EvidenceCollectionTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        (self.root / "platform").mkdir()
        (self.root / "services").mkdir()
        manifest = self.root / "services/detect-web/src/main/resources/detection-content/manifest.json"
        manifest.parent.mkdir(parents=True)
        manifest.write_text(json.dumps({"packId": "fixture", "version": "1", "schemaVersion": "1", "rules": [{}]}), encoding="utf-8")
        self.output = self.root / "evidence"
        self.log = io.StringIO()

    def report(self, body, name="Example"):
        path = self.root / ("services/fixture/target/surefire-reports/TEST-" + name + ".xml")
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(body, encoding="utf-8")
        return path

    def benchmark(self, value, name="benchmark.json"):
        path = self.root / name
        path.write_text(json.dumps(value), encoding="utf-8")
        return path

    def collect(self, *args, dirty="", instances=""):
        def command(*arguments, **kwargs):
            if arguments == ("git", "rev-parse", "HEAD"):
                return COMMIT
            if arguments[0:2] == ("git", "status"):
                return dirty
            return "openjdk version fixture"
        with patch.object(EVIDENCE, "ROOT", self.root), patch.object(EVIDENCE, "command", side_effect=command), \
                patch.dict(EVIDENCE.os.environ, {"DETECTION_INSTANCE_URLS": instances}), \
                contextlib.redirect_stdout(self.log), contextlib.redirect_stderr(self.log):
            return EVIDENCE.main(["--output", str(self.output), *map(str, args)])

    def read(self, filename):
        return json.loads((self.output / COMMIT / filename).read_text(encoding="utf-8"))

    def test_valid_failed_test_report_is_preserved_without_claiming_collection_means_pass(self):
        path = self.report('<testsuite tests="4" failures="1" errors="1" skipped="1">'
                           '<testcase name="pass"/><testcase name="fail"><failure/></testcase>'
                           '<testcase name="error"><error/></testcase><testcase name="skip"><skipped/></testcase></testsuite>')
        self.assertEqual(self.collect("--require-tests"), 0)
        tests = self.read("test-summary.json")
        self.assertEqual([tests[key] for key in ("tests", "executed", "failures", "errors", "skipped")], [4, 3, 1, 1, 1])
        self.assertEqual(tests["status"], "failed")
        self.assertFalse(tests["currentRunVerified"])
        self.assertEqual(tests["reports"][0]["sha256"], hashlib.sha256(path.read_bytes()).hexdigest())
        self.assertIn("modifiedAt", tests["reports"][0])
        self.assertEqual(self.read("summary.json")["collectionStatus"], "complete")

    def test_corrupt_and_inconsistent_counters_are_visible_and_fail_collection(self):
        self.report('<testsuite tests="1" failures="0" errors="0" skipped="0"><testcase/></testsuite>')
        for body in ("<invalid", '<testsuite tests="-1" failures="0" errors="0" skipped="0"/>',
                     '<testsuite tests="1" failures="0" errors="0" skipped="0"><testcase><failure/></testcase></testsuite>',
                     '<testsuite tests="10" failures="0" errors="0" skipped="0"/>', '<testsuite/>'):
            with self.subTest(body=body):
                self.report(body, "Broken")
                self.assertEqual(self.collect(), 1)
                tests = self.read("test-summary.json")
                self.assertEqual(tests["status"], "incomplete")
                self.assertEqual(tests["tests"], 1, "valid counts remain explicitly partial")
                self.assertEqual(len(tests["invalidReports"]), 1)

    def test_missing_and_all_skipped_reports_never_mean_executed_tests(self):
        self.assertEqual(self.collect(), 0)
        self.assertEqual(self.read("test-summary.json")["status"], "not-run")
        self.assertEqual(self.collect("--require-tests"), 1)
        self.report('<testsuite tests="1" failures="0" errors="0" skipped="1"><testcase><skipped/></testcase></testsuite>')
        self.assertEqual(self.collect("--require-tests"), 1)
        self.assertEqual(self.read("test-summary.json")["status"], "skipped")
        self.assertEqual(self.read("test-summary.json")["executed"], 0)

    def test_missing_invalid_and_wrong_commit_benchmarks_are_not_silently_dropped(self):
        good = self.benchmark({"pass": False, "commitSha": COMMIT, "results": {"fixture": {"pass": False}}})
        bad = self.root / "missing.json"
        for content in (None, "not JSON", "[]", "{}", json.dumps({"commitSha": "b" * 40, "pass": True})):
            with self.subTest(content=content):
                if content is not None:
                    bad.write_text(content, encoding="utf-8")
                self.assertEqual(self.collect("--benchmark", good, "--benchmark", bad), 1)
                summary = self.read("summary.json")
                self.assertEqual(summary["collectionStatus"], "incomplete")
                self.assertEqual([item["status"] for item in summary["benchmarkInputs"]], ["collected", "unavailable"])
                self.assertFalse(self.read("benchmark-summary.json")[0]["pass"])

    def test_provenance_does_not_misrepresent_dirty_checkout_or_assume_live_instances(self):
        self.assertEqual(self.collect(dirty=" M source.java\n?? fixture.txt"), 0)
        summary = self.read("summary.json")
        self.assertTrue(summary["worktreeDirty"])
        self.assertIsNone(summary["instances"])
        self.assertFalse(summary["currentRunVerified"])
        self.assertEqual(self.collect(instances=" http://127.0.0.1:1, ,http://127.0.0.1:1,"), 0)
        self.assertEqual(self.read("summary.json")["instances"], 1)

    def test_missing_manifest_is_reported_even_when_other_artifacts_are_available(self):
        (self.root / "services/detect-web/src/main/resources/detection-content/manifest.json").unlink()
        self.assertEqual(self.collect(), 1)
        self.assertIn("detection manifest", self.read("summary.json")["collectionErrors"][0])

    def test_large_artifacts_and_subprocess_timeouts_are_bounded(self):
        path = self.root / "large.json"
        path.write_bytes(b"123456789")
        with patch.object(EVIDENCE, "MAX_ARTIFACT_BYTES", 8), self.assertRaisesRegex(ValueError, "exceeds"):
            EVIDENCE.read_artifact(path)
        with patch.object(EVIDENCE.subprocess, "check_output", side_effect=subprocess.TimeoutExpired("java", 15)):
            self.assertIsNone(EVIDENCE.command("java", "-version", include_stderr=True))
        with patch.object(EVIDENCE.subprocess, "check_output", return_value="java version fixture\n") as process:
            self.assertEqual(EVIDENCE.command("java", "-version", include_stderr=True), "java version fixture")
            self.assertEqual(process.call_args.kwargs["stderr"], subprocess.STDOUT)
            self.assertEqual(process.call_args.kwargs["timeout"], 15)


if __name__ == "__main__":
    unittest.main()
