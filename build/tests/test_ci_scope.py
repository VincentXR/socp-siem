import importlib.util
import pathlib
import unittest


SPEC = importlib.util.spec_from_file_location(
    "ci_scope", pathlib.Path(__file__).parents[1] / "ci-scope.py")
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader
SPEC.loader.exec_module(MODULE)


class CiScopeTest(unittest.TestCase):
    def test_documentation_only_change_can_legally_skip_heavy_job(self):
        self.assertFalse(MODULE.requires_full_stack([
            "README.md", "docs/architecture.md", "SECURITY.md",
        ]))

    def test_every_runtime_boundary_requires_heavy_evidence(self):
        for path in (
            "platform/socp-audit/pom.xml",
            "services/detect-web/src/Main.java",
            "frontend/apps/workbench/src/App.vue",
            "infra/init-sql/postgres/00.sql",
            "deploy/helm/socp-core/values.yaml",
            "agents/vector/vector.toml",
            "vector/remap.vrl",
            "build/verify-full.py",
            "config/dependency-check-suppressions.xml",
            ".github/workflows/ci.yml",
            ".mvn/wrapper/maven-wrapper.properties",
            "pom.xml",
            "mvnw",
            "docker-compose.prod.yml",
        ):
            with self.subTest(path=path):
                self.assertTrue(MODULE.requires_full_stack([path]))

    def test_one_runtime_file_makes_a_mixed_change_applicable(self):
        self.assertTrue(MODULE.requires_full_stack([
            "docs/operations.md", "agents/vector/config.yaml",
        ]))


if __name__ == "__main__":
    unittest.main()
