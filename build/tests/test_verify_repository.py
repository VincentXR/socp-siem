import importlib.util
from pathlib import Path
import sys
import unittest


ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location(
    "verify_repository", ROOT / "build" / "verify-repository.py"
)
MODULE = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = MODULE
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


class VerifyRepositoryTest(unittest.TestCase):
    def test_manifest_contains_checks_that_previously_drifted(self):
        arguments = {argument for check in MODULE.CHECKS for argument in check.arguments}
        self.assertIn("build/verify-runtime-consolidation.py", arguments)
        self.assertIn("build/verify-prod-compose.py", arguments)
        self.assertIn("build/verify-helm.py", arguments)
        self.assertIn("build/verify-rls.py", arguments)
        self.assertIn("build/verify-backup-toolchain.py", arguments)
        self.assertIn("build/verify-observability-assets.py", arguments)
        self.assertIn("build/verify-frontend-conventions.py", arguments)
        self.assertIn("unittest", arguments)

    def test_helm_is_the_only_optionally_skipped_contract(self):
        all_checks = MODULE.selected_checks(skip_helm=False)
        without_helm = MODULE.selected_checks(skip_helm=True)
        self.assertEqual(len(all_checks) - len(without_helm), 1)
        self.assertEqual(
            [check.name for check in all_checks if check.requires_helm],
            ["Helm release contract"],
        )

    def test_evidence_output_is_added_only_to_detection_summary(self):
        evidence_dir = Path("evidence")
        commands = {
            check.name: MODULE.command_for(check, evidence_dir)
            for check in MODULE.CHECKS
        }
        summary_command = commands["detection README summary"]
        self.assertIn("--json", summary_command)
        self.assertEqual(Path(summary_command[-1]), evidence_dir / "detection-summary.json")
        self.assertNotIn("--json", commands["migration contracts"])


if __name__ == "__main__":
    unittest.main()
