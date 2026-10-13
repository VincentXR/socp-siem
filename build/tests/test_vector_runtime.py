import copy
import difflib
import io
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import tarfile
import subprocess
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("vector_runtime_build", ROOT / "agents/vector-runtime/build.py")
BUILD = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(BUILD)


class VectorRuntimeKitTest(unittest.TestCase):
    def test_alpine_builder_checks_bindgen_library_before_compiling(self):
        dockerfile = (BUILD.KIT / "Dockerfile").read_text()
        builder, runtime = dockerfile.split("\nFROM ${RUNTIME_IMAGE}\n", 1)
        self.assertIn("clang20-libclang", builder)
        self.assertIn("ENV LIBCLANG_PATH=/usr/lib", builder)
        probe = "RUN python3 -c \"import ctypes; ctypes.CDLL('/usr/lib/libclang.so.20.1')\""
        self.assertIn(probe, builder)
        self.assertLess(builder.index(probe), builder.index("bash build/vector-runtime.sh test"))
        self.assertNotIn("clang20-libclang", runtime)

    def test_alpine_host_build_can_dynamically_load_bindgen(self):
        dockerfile = (BUILD.KIT / "Dockerfile").read_text()
        self.assertIn('ENV RUSTC_WRAPPER=/socp/agents/vector-runtime/rustc-host-wrapper.py', dockerfile)
        self.assertIn('export SOCP_VECTOR_TARGET=', dockerfile)
        self.assertNotIn('ENV RUSTFLAGS=', dockerfile)
        self.assertIn('check-libclang.rs', dockerfile)
        self.assertLess(dockerfile.index('/tmp/check-libclang /usr/lib/libclang.so.20.1'),
                        dockerfile.index('bash build/vector-runtime.sh test'))
        spec = importlib.util.spec_from_file_location("host_wrapper", BUILD.KIT / "rustc-host-wrapper.py")
        wrapper = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(wrapper)
        host = ["--crate-name", "build_script_build", "build.rs"]
        self.assertEqual(["-C", "target-feature=-crt-static", *host], wrapper.compiler_arguments(host))
        for target in (["--target", "x86_64-unknown-linux-musl"], ["--target=aarch64-unknown-linux-musl"]):
            args = ["--crate-name", "vector", *target, "-D", "warnings", "main.rs"]
            self.assertEqual(args, wrapper.compiler_arguments(args))

    def test_repository_patches_match_the_locked_digests(self):
        BUILD.verify()
        self.assertEqual("0.57.0-socp1", BUILD.LOCK["build_id"])
        self.assertEqual(2, len(BUILD.LOCK["patches"]))
        self.assertEqual("8832452f57afb536ea0de53a093f9fd1b669ccec", BUILD.LOCK["source"]["commit"])

    def test_corrupted_patch_is_rejected_before_building(self):
        changed = copy.deepcopy(BUILD.LOCK)
        changed["patches"][0]["sha256"] = "0" * 64
        with patch.object(BUILD, "LOCK", changed):
            with self.assertRaisesRegex(RuntimeError, "SHA-256"):
                BUILD.verify()

    def test_unpinned_images_and_unbounded_jobs_are_rejected(self):
        for key, value in (("builder_image", "rust:latest"), ("runtime_image", "vector:latest")):
            changed = copy.deepcopy(BUILD.LOCK)
            changed[key] = value
            with self.subTest(key=key), patch.object(BUILD, "LOCK", changed):
                with self.assertRaises(RuntimeError):
                    BUILD.verify()
        changed = copy.deepcopy(BUILD.LOCK)
        changed["build_profile"]["jobs"] = 3
        with patch.object(BUILD, "LOCK", changed):
            with self.assertRaises(RuntimeError):
                BUILD.verify()

    def test_prepared_source_is_checked_in_full_before_reuse(self):
        with tempfile.TemporaryDirectory() as temporary:
            cache = Path(temporary)
            source = cache / "source"
            source.mkdir()
            cargo = source / "Cargo.lock"
            cargo.write_text("version = 4\n")
            code = source / "writer.rs"
            code.write_text("original source\n")
            writer = source / "lib/vector-buffers/src/variants/disk_v2/writer.rs"
            invariants = source / "lib/vector-buffers/src/variants/disk_v2/tests/invariants.rs"
            writer.parent.mkdir(parents=True)
            invariants.parent.mkdir(parents=True)
            writer.write_text("\n".join(f"fn {name}() {{}}" for name in BUILD.LOCK["regression_tests"]))
            invariants.write_text("// unrelated original tests\n")
            archive = cache / "vector-source.tar.gz"
            archive.write_bytes(b"the source archive was verified before initial preparation")
            lock = copy.deepcopy(BUILD.LOCK)
            lock["source"]["sha256"] = hashlib.sha256(archive.read_bytes()).hexdigest()
            lock["source"]["cargo_lock_sha256"] = hashlib.sha256(cargo.read_bytes()).hexdigest()
            lock_path = cache / "runtime.lock.json"
            lock_path.write_text(json.dumps(lock))
            with patch.multiple(BUILD, CACHE=cache, SOURCE=source, ARCHIVE=archive,
                                LOCK=lock, LOCK_PATH=lock_path):
                stamp = {"lock_sha256": BUILD.sha(lock_path), "files": BUILD.source_manifest()}
                (cache / "source-manifest.json").write_text(json.dumps(stamp))
                BUILD.prepare()
                code.write_text("changed source\n")
                with self.assertRaisesRegex(RuntimeError, "Prepared source changed"):
                    BUILD.prepare()

    def test_patch_application_is_not_skipped_inside_an_enclosing_checkout(self):
        with tempfile.TemporaryDirectory() as temporary:
            parent = (Path(temporary) / "repo").resolve()
            parent.mkdir()
            subprocess.run(["git", "init", "--quiet", str(parent)], check=True)
            kit = parent / "kit"
            (kit / "patches").mkdir(parents=True)
            (kit / "rustc-host-wrapper.py").write_bytes((BUILD.KIT / "rustc-host-wrapper.py").read_bytes())
            cache = parent / ".cache/vector"
            cache.mkdir(parents=True)
            source = cache / "source"
            archive = cache / "vector-source.tar.gz"
            paths = ["lib/vector-buffers/src/variants/disk_v2/tests/invariants.rs",
                     "lib/vector-buffers/src/variants/disk_v2/writer.rs"]
            original = "fn previous_test() {}\n"
            cargo = "version = 4\n"
            with tarfile.open(archive, "w:gz") as tar:
                for name, text in [("Cargo.lock", cargo)] + [(path, original) for path in paths]:
                    data = text.encode()
                    info = tarfile.TarInfo("vector-base/" + name)
                    info.size = len(data)
                    tar.addfile(info, io.BytesIO(data))
            lock = copy.deepcopy(BUILD.LOCK)
            lock["source"]["sha256"] = BUILD.sha(archive)
            lock["source"]["cargo_lock_sha256"] = hashlib.sha256(cargo.encode()).hexdigest()
            for index, path in enumerate(paths):
                names = lock["regression_tests"][:1] if index == 0 else lock["regression_tests"][1:]
                updated = "\n".join(f"fn {name}() {{}}" for name in names) + "\n"
                patch_file = kit / lock["patches"][index]["file"]
                patch_file.write_text("".join(difflib.unified_diff(
                    original.splitlines(True), updated.splitlines(True),
                    fromfile="a/" + path, tofile="b/" + path)))
                lock["patches"][index]["sha256"] = BUILD.sha(patch_file)
            lock_path = kit / "runtime.lock.json"
            lock_path.write_text(json.dumps(lock))
            with patch.multiple(BUILD, CACHE=cache, SOURCE=source, ARCHIVE=archive,
                                LOCK=lock, LOCK_PATH=lock_path, KIT=kit):
                BUILD.prepare()
                BUILD.verify_patch_tests()
                # A second preparation verifies rather than reapplies the patches.
                BUILD.prepare()
            self.assertTrue((cache / "source-manifest.json").is_file())
            self.assertFalse((parent / "lib").exists())

    def test_full_disk_suite_is_serialized_without_skipping_assertions(self):
        with tempfile.TemporaryDirectory() as temporary:
            cache = Path(temporary)
            (cache / "source-manifest.json").write_text("{}\n")
            listing = "\n".join(f"disk_v2::{name}: test" for name in BUILD.LOCK["regression_tests"])
            with patch.multiple(BUILD, CACHE=cache, ARTIFACTS=cache / "artifacts"), \
                    patch.object(BUILD, "prepare"), patch.object(BUILD, "environment", return_value={}), \
                    patch.object(BUILD.subprocess, "check_output", return_value=listing), \
                    patch.object(BUILD, "run") as run:
                BUILD.test()
            command = list(run.call_args.args)
            self.assertEqual(["cargo", "test", "--locked", "--package", "vector-buffers", "--lib",
                              "variants::disk_v2", "--", "--nocapture", "--test-threads=1"], command)
            evidence = json.loads((cache / "artifacts/buffer-tests.passed.json").read_text())
            self.assertEqual(BUILD.LOCK["regression_tests"], evidence["required_regressions"])
            self.assertEqual(command, evidence["command"])
            self.assertEqual("passed", evidence["result"])

    def test_changed_lockfile_cannot_reuse_prepared_sources(self):
        with tempfile.TemporaryDirectory() as temporary:
            cache = Path(temporary)
            source = cache / "source"
            source.mkdir()
            archive = cache / "vector-source.tar.gz"
            archive.write_bytes(b"verified archive")
            lock = copy.deepcopy(BUILD.LOCK)
            lock["source"]["sha256"] = BUILD.sha(archive)
            lock_path = cache / "runtime.lock.json"
            lock_path.write_text(json.dumps(lock))
            (cache / "source-manifest.json").write_text(json.dumps({"lock_sha256": "0" * 64, "files": {}}))
            with patch.multiple(BUILD, CACHE=cache, SOURCE=source, ARCHIVE=archive,
                                LOCK=lock, LOCK_PATH=lock_path):
                with self.assertRaisesRegex(RuntimeError, "Prepared source changed"):
                    BUILD.prepare()


if __name__ == "__main__":
    unittest.main()
