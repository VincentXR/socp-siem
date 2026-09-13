import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
BUILD = ROOT / "build"
sys.path.insert(0, str(BUILD))
SPEC = importlib.util.spec_from_file_location(
    "verify_runtime_consolidation", BUILD / "verify-runtime-consolidation.py"
)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


class VerifyRuntimeConsolidationTest(unittest.TestCase):

    def test_missing_evidence_is_a_candidate_failure(self):
        errors = MODULE.evidence_errors(
            Path(".cache") / "does-not-exist-runtime-evidence.json",
            MODULE.load_topology(),
            "alert-incident",
        )
        self.assertTrue(any("missing consolidation evidence" in error for error in errors))

    def test_valid_manifest_covers_selected_candidate_and_checks(self):
        topology = MODULE.load_topology()
        selected = MODULE.candidate(topology, "soar-notify")
        manifest = {
            "schemaVersion": 1,
            "status": "passed",
            "commit": MODULE.current_commit(),
            "candidate": "soar-notify",
            "members": selected["members"],
            "checks": {
                check: True for check in topology["deploymentPolicy"]["requiredChecks"]
            },
        }
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "evidence.json"
            path.write_text(json.dumps(manifest), encoding="utf-8")
            self.assertEqual(
                [], MODULE.evidence_errors(path, topology, "soar-notify")
            )

    def test_incomplete_candidate_check_is_rejected(self):
        topology = MODULE.load_topology()
        selected = MODULE.candidate(topology, "alert-incident")
        manifest = {
            "schemaVersion": 1,
            "status": "passed",
            "commit": MODULE.current_commit(),
            "candidate": "alert-incident",
            "members": selected["members"],
            "checks": {
                check: True for check in topology["deploymentPolicy"]["requiredChecks"]
            },
        }
        manifest["checks"]["capacity"] = False
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "evidence.json"
            path.write_text(json.dumps(manifest), encoding="utf-8")
            errors = MODULE.evidence_errors(path, topology, "alert-incident")
            self.assertTrue(any("failed checks" in error for error in errors))

    def test_unknown_candidate_is_rejected(self):
        errors = MODULE.evidence_errors(
            Path("unused.json"), MODULE.load_topology(), "not-registered"
        )
        self.assertTrue(any("unknown consolidation candidate" in error for error in errors))


if __name__ == "__main__":
    unittest.main()
