import importlib.util
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


BUILD = Path(__file__).resolve().parents[1]
ROOT = BUILD.parent
sys.path.insert(0, str(BUILD))

from middleware_images import (  # noqa: E402
    CATALOG,
    FIXTURE_IMAGE_KEYS,
    SERVICE_IMAGE_KEYS,
    load_catalog,
)

SPEC = importlib.util.spec_from_file_location("verify_middleware_images", BUILD / "verify-middleware-images.py")
VERIFIER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(VERIFIER)


class MiddlewareImagesTest(unittest.TestCase):

    def test_catalog_contains_every_runtime_and_fixture_image(self):
        catalog = load_catalog()
        self.assertTrue(CATALOG.is_file())
        self.assertTrue(set(SERVICE_IMAGE_KEYS.values()).issubset(catalog))
        self.assertTrue(set(FIXTURE_IMAGE_KEYS.values()).issubset(catalog))

    def test_catalog_images_are_pinned(self):
        catalog = load_catalog()
        for key, image in catalog.items():
            with self.subTest(key=key):
                self.assertNotRegex(image, r":latest$")
                self.assertTrue("@sha256:" in image or ":" in image.rsplit("/", 1)[-1])

    def test_vector_catalog_matches_source_build_manifest(self):
        manifest = json.loads((ROOT / "agents/vector-runtime/runtime.lock.json").read_text())
        self.assertEqual(manifest["image"], load_catalog()["SOCP_VECTOR_IMAGE"])
        self.assertTrue(manifest["image"].startswith("socp/vector:"))


class VectorWorkflowContractTest(unittest.TestCase):

    def setUp(self):
        self.workflow = (ROOT / ".github/workflows/ci.yml").read_text()

    def errors(self, workflow):
        return VERIFIER.vector_workflow_errors(workflow, "build", "bash build/mvnw.sh verify")

    def test_both_vector_jobs_build_verify_and_preserve_failure_evidence(self):
        self.assertEqual([], self.errors(self.workflow))
        full_stack = (ROOT / ".github/workflows/full-stack.yml").read_text()
        self.assertEqual([], VERIFIER.vector_workflow_errors(
            full_stack, "full-stack-heavy", "-Dtest=VectorConfigRendererContainerTest"))

    def test_missing_build_fails(self):
        self.assertTrue(self.errors(self.workflow.replace("bash build/vector-runtime.sh image", "echo missing build")))

    def test_cached_images_keep_exact_inputs_and_unconditional_verification(self):
        for filename, job in (("ci.yml", "build"), ("full-stack.yml", "full-stack-heavy")):
            with self.subTest(workflow=filename):
                workflow = (ROOT / ".github/workflows" / filename).read_text()
                body = VERIFIER.workflow_job(workflow, job)
                self.assertIn("hashFiles('agents/vector-runtime/**', 'build/vector-runtime.sh')", body)
                self.assertNotIn("restore-keys:", body)
                self.assertIn("gzip -dc .cache/vector-image/vector.tar.gz | docker image load", body)
                self.assertIn("          fi\n          bash build/vector-runtime.sh verify-image", body)
                self.assertLess(body.index("bash build/vector-runtime.sh verify-image"),
                                body.index("uses: actions/cache/save@"))
                self.assertIn("path: .cache/vector-image/vector.tar.gz", body)
        full_stack = (ROOT / ".github/workflows/full-stack.yml").read_text()
        self.assertIn("!.cache/vector-image/**", full_stack)
        self.assertIn("!.cache/vector-runtime/**", full_stack)

    def test_image_cache_covers_every_local_docker_build_input(self):
        dockerfile = (ROOT / "agents/vector-runtime/Dockerfile").read_text()
        inputs = set(re.findall(r"(?m)^COPY (?!-)(\S+) ", dockerfile))
        self.assertEqual({"agents/vector-runtime", "build/vector-runtime.sh"}, inputs)

    def test_build_without_provenance_check_fails(self):
        self.assertTrue(self.errors(self.workflow.replace("bash build/vector-runtime.sh verify-image", "echo missing check")))

    def test_build_after_tests_fails(self):
        workflow = self.workflow.replace("bash build/vector-runtime.sh image", "bash build/mvnw.sh verify", 1)
        self.assertTrue(self.errors(workflow))

    def test_build_without_step_timeout_fails(self):
        self.assertTrue(self.errors(self.workflow.replace("        timeout-minutes: 60\n", "", 1)))

    def test_build_failure_cannot_be_ignored(self):
        workflow = self.workflow.replace("      - name: Build and verify patched Vector runtime\n",
                                         "      - name: Build and verify patched Vector runtime\n        continue-on-error: true\n")
        self.assertTrue(self.errors(workflow))

    def test_evidence_needs_always_upload(self):
        workflow = self.workflow.replace("      - name: Upload validation evidence\n        if: always()",
                                         "      - name: Upload validation evidence\n        if: success()")
        self.assertTrue(self.errors(workflow))

    def test_module_delivery_evidence_cannot_be_replaced_by_build_logs(self):
        workflow = self.workflow.replace("            services/search-config/target/vector-delivery-evidence/\n", "")
        self.assertTrue(self.errors(workflow))

    def test_build_in_other_job_does_not_satisfy_contract(self):
        workflow = self.workflow.replace("  build:\n", "  unrelated:\n", 1)
        self.assertTrue(self.errors(workflow))


class RunVectorSafetyTest(unittest.TestCase):

    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)
        for name in ("build", "infra", "agents/vector-pipeline", "bin", "demo"):
            (self.root / name).mkdir(parents=True)
        shutil.copy(BUILD / "run-vector.sh", self.root / "build/run-vector.sh")
        shutil.copy(CATALOG, self.root / "infra/middleware-images.env")
        (self.root / "agents/vector-pipeline/vector.toml").write_text('token = "__VECTOR_TOKEN__"\n')
        verifier = self.root / "build/vector-runtime.sh"
        verifier.write_text('''#!/usr/bin/env bash
printf '%s\\n' "$*" >> "$RUNTIME_CALL_LOG"
if [[ "${FAKE_PROVENANCE_STATUS:-0}" != 0 ]]; then
  echo "Missing or unverified image; run bash build/vector-runtime.sh image" >&2
  exit "$FAKE_PROVENANCE_STATUS"
fi
''')
        docker = self.root / "bin/docker"
        docker.write_text('''#!/usr/bin/env python3
import json
import os
import sys
with open(os.environ["DOCKER_CALL_LOG"], "a") as log:
    log.write(json.dumps(sys.argv[1:]) + "\\n")
if sys.argv[1] == "ps" and os.environ.get("FAKE_CONTAINER_EXISTS") == "1":
    print("socp-vector")
if sys.argv[1] == "run":
    print("disposable-container-id")
''')
        docker.chmod(0o755)
        self.env = os.environ | {
            "PATH": str(self.root / "bin") + os.pathsep + os.environ["PATH"],
            "DOCKER_CALL_LOG": str(self.root / "docker-calls.jsonl"),
            "RUNTIME_CALL_LOG": str(self.root / "runtime-calls.txt"),
            "SOCP_VECTOR_TOKEN": "test-collector-credential",
        }

    def run_helper(self, command):
        return subprocess.run(["bash", str(self.root / "build/run-vector.sh"), command],
                              env=self.env, text=True, capture_output=True, timeout=30)

    def docker_calls(self):
        path = self.root / "docker-calls.jsonl"
        return [json.loads(line) for line in path.read_text().splitlines()] if path.exists() else []

    def test_existing_collector_is_not_replaced_or_restarted(self):
        self.env["FAKE_CONTAINER_EXISTS"] = "1"
        result = self.run_helper("start")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("docs/operations/vector-runtime.md", result.stderr)
        self.assertEqual(["ps"], [call[0] for call in self.docker_calls()])
        self.assertFalse((self.root / "runtime-calls.txt").exists())

    def test_missing_or_wrong_provenance_blocks_start(self):
        self.env["FAKE_PROVENANCE_STATUS"] = "7"
        result = self.run_helper("start")
        self.assertEqual(7, result.returncode)
        self.assertIn("bash build/vector-runtime.sh image", result.stderr)
        self.assertEqual("verify-image\n", (self.root / "runtime-calls.txt").read_text())
        self.assertEqual(["ps"], [call[0] for call in self.docker_calls()])

    def test_new_collector_uses_verified_catalog_image_without_leaking_token(self):
        result = self.run_helper("start")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("verify-image\n", (self.root / "runtime-calls.txt").read_text())
        calls = self.docker_calls()
        self.assertEqual(["ps", "run"], [call[0] for call in calls])
        self.assertIn(load_catalog()["SOCP_VECTOR_IMAGE"], calls[-1])
        self.assertNotIn(self.env["SOCP_VECTOR_TOKEN"], result.stdout + result.stderr)

    def test_stop_preserves_container_and_state(self):
        result = self.run_helper("stop")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual([["stop", "--time", "60", "socp-vector"]], self.docker_calls())


if __name__ == "__main__":
    unittest.main()
