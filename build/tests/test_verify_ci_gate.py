import importlib.util
import pathlib
import unittest


SPEC = importlib.util.spec_from_file_location(
    "verify_ci_gate", pathlib.Path(__file__).parents[1] / "verify-ci-gate.py")
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader
SPEC.loader.exec_module(MODULE)


class VerifyCiGateTest(unittest.TestCase):
    def test_all_required_success(self):
        self.assertEqual([], MODULE.evaluate(["build=success", "e2e=success"], []))

    def test_failure_cancel_and_unexpected_skip_fail_closed(self):
        for conclusion in ("failure", "cancelled", "skipped"):
            with self.subTest(conclusion=conclusion):
                self.assertTrue(MODULE.evaluate([f"build={conclusion}"], []))

    def test_explicitly_optional_skip_is_legal_but_failure_is_not(self):
        self.assertEqual([], MODULE.evaluate([], ["heavy=skipped"]))
        self.assertTrue(MODULE.evaluate([], ["heavy=failure"]))


if __name__ == "__main__":
    unittest.main()
