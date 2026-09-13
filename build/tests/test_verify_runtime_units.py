import copy
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
    "verify_runtime_units", BUILD / "verify-runtime-units.py"
)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


class VerifyRuntimeUnitsTest(unittest.TestCase):

    def test_missing_evidence_is_a_release_failure(self):
        errors = MODULE.evidence_errors(
            Path(".cache") / "does-not-exist-runtime-evidence.json",
            MODULE.load_topology(),
        )
        self.assertTrue(any("missing aggregate runtime-unit evidence" in error for error in errors))

    def test_valid_manifest_covers_every_target_unit_and_check(self):
        topology = copy.deepcopy(MODULE.load_topology())
        manifest = {
            "schemaVersion": 1,
            "status": "passed",
            "commit": MODULE.current_commit(),
            "units": {
                unit["name"]: {check: True for check in MODULE.REQUIRED_CHECKS}
                for unit in topology["units"]
            },
        }
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "evidence.json"
            path.write_text(json.dumps(manifest), encoding="utf-8")
            self.assertEqual([], MODULE.evidence_errors(path, topology))

    def test_incomplete_unit_check_is_rejected(self):
        topology = MODULE.load_topology()
        manifest = {
            "schemaVersion": 1,
            "status": "passed",
            "commit": MODULE.current_commit(),
            "units": {
                unit["name"]: {check: True for check in MODULE.REQUIRED_CHECKS}
                for unit in topology["units"]
            },
        }
        manifest["units"][topology["units"][0]["name"]]["capacity"] = False
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "evidence.json"
            path.write_text(json.dumps(manifest), encoding="utf-8")
            errors = MODULE.evidence_errors(path, topology)
            self.assertTrue(any("failed checks" in error for error in errors))


if __name__ == "__main__":
    unittest.main()
