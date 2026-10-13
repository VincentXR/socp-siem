#!/usr/bin/env python3
"""Hash-pinned, local-only Vector build. Requires Python 3.11+ and a Rust toolchain."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile
import tomllib

KIT = Path(__file__).resolve().parent
ROOT = KIT.parents[1]
LOCK_PATH = KIT / "runtime.lock.json"
LOCK = json.loads(LOCK_PATH.read_text())
CACHE = Path(os.environ.get("SOCP_VECTOR_CACHE", ROOT / ".cache/vector-runtime")).resolve()
SOURCE = CACHE / "source"
ARTIFACTS = CACHE / "artifacts"
ARCHIVE = CACHE / "vector-source.tar.gz"
FEATURES = ",".join(LOCK["features"])
PATCH_IDS = ",".join(p["commit"] for p in LOCK["patches"])
LABELS = {
    "io.socp.vector.build": LOCK["build_id"],
    "io.socp.vector.source": LOCK["source"]["commit"],
    "io.socp.vector.patches": PATCH_IDS,
}


def run(*args: str, **kwargs):
    print("+ " + " ".join(map(str, args)), flush=True)
    return subprocess.run(list(map(str, args)), check=True, **kwargs)


def sha(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def check_hash(path: Path, expected: str):
    if not path.is_file() or sha(path) != expected:
        raise RuntimeError(f"SHA-256 mismatch or missing file: {path}")


def verify():
    def digest(value: str, size: int = 64):
        if not isinstance(value, str) or not re.fullmatch(r"[0-9a-f]{%d}" % size, value):
            raise RuntimeError(f"Invalid {size}-character digest: {value!r}")
    digest(LOCK["source"]["commit"], 40)
    digest(LOCK["source"]["sha256"])
    digest(LOCK["source"]["cargo_lock_sha256"])
    check_hash(KIT / "rustc-host-wrapper.py", LOCK["host_rustc_wrapper_sha256"])
    for patch in LOCK["patches"]:
        digest(patch["commit"], 40)
        digest(patch["sha256"])
        path = (KIT / patch["file"]).resolve()
        if not path.is_relative_to(KIT) or not path.is_relative_to(KIT / "patches"):
            raise RuntimeError("Patch path escapes the runtime kit patches directory")
        check_hash(path, patch["sha256"])
    if len(LOCK["patches"]) != 2 or len({p["commit"] for p in LOCK["patches"]}) != 2:
        raise RuntimeError("Expected exactly two distinct upstream patches")
    for image in (LOCK["builder_image"], LOCK["runtime_image"]):
        if not re.fullmatch(r"[^\s@]+@sha256:[0-9a-f]{64}", image):
            raise RuntimeError("Invalid or unpinned base image")
    required = {"api", "sources-file", "sources-socket", "sources-syslog", "sources-kafka",
                "sources-internal_metrics", "transforms-remap", "sinks-http", "sinks-prometheus",
                "sinks-console", "vrl/stdlib", "rdkafka/cmake_build"}
    features = LOCK["features"]
    if len(features) != len(set(features)) or not required.issubset(features):
        raise RuntimeError("Missing or duplicated supported SOCP features")
    profile = LOCK["build_profile"]
    if not (1 <= profile["jobs"] <= 2 and 1 <= profile["codegen_units"] <= 256 and
            profile["name"] == "release" and profile["opt_level"] in (1, 2, 3) and
            profile["lto"] is False and profile["debug"] == 0 and profile["incremental"] is False):
        raise RuntimeError("Invalid or unbounded build profile")
    if not isinstance(LOCK["source_date_epoch"], int) or LOCK["source_date_epoch"] < 0:
        raise RuntimeError("Invalid source timestamp")
    print(f"Verified {LOCK['build_id']} source, patch and image pins.", flush=True)


def verify_patch_tests():
    files = [SOURCE / "lib/vector-buffers/src/variants/disk_v2/writer.rs",
             SOURCE / "lib/vector-buffers/src/variants/disk_v2/tests/invariants.rs"]
    content = "\n".join(p.read_text() for p in files)
    for expected in LOCK["regression_tests"]:
        if f"fn {expected}(" not in content:
            raise RuntimeError(f"Patch not applied: missing regression {expected}")


def source_manifest() -> dict[str, str]:
    return {str(p.relative_to(SOURCE)): sha(p) for p in sorted(SOURCE.rglob("*"))
            if p.is_file() and ".git" not in p.relative_to(SOURCE).parts}


def prepare():
    verify()
    CACHE.mkdir(parents=True, exist_ok=True)
    if not ARCHIVE.is_file():
        partial = ARCHIVE.with_suffix(".download")
        run("curl", "--fail", "--location", "--retry", "3", "--connect-timeout", "30",
            "--output", partial, LOCK["source"]["url"])
        check_hash(partial, LOCK["source"]["sha256"])
        partial.replace(ARCHIVE)
    check_hash(ARCHIVE, LOCK["source"]["sha256"])
    stamp = CACHE / "source-manifest.json"
    if stamp.is_file():
        saved = json.loads(stamp.read_text())
        if saved["lock_sha256"] != sha(LOCK_PATH) or saved["files"] != source_manifest():
            raise RuntimeError(f"Prepared source changed; choose a fresh SOCP_VECTOR_CACHE (current: {CACHE})")
    else:
        if SOURCE.exists():
            raise RuntimeError(f"Unverified source directory exists: {SOURCE}; use a fresh SOCP_VECTOR_CACHE")
        SOURCE.mkdir()
        with tarfile.open(ARCHIVE) as archive:
            for member in archive.getmembers():
                components = Path(member.name).parts
                if len(components) < 2:
                    continue
                member.name = str(Path(*components[1:]))
                archive.extract(member, SOURCE, filter="data")
        check_hash(SOURCE / "Cargo.lock", LOCK["source"]["cargo_lock_sha256"])
        patch_env = {**os.environ, "GIT_CEILING_DIRECTORIES": str(CACHE)}
        for patch in LOCK["patches"]:
            run("git", "apply", "--check", KIT / patch["file"], cwd=SOURCE, env=patch_env)
            run("git", "apply", KIT / patch["file"], cwd=SOURCE, env=patch_env)
        # git apply can otherwise silently skip paths when nested in the SOCP
        # checkout. Validate the patched content, not only the process exit code.
        verify_patch_tests()
        stamp.write_text(json.dumps({"lock_sha256": sha(LOCK_PATH), "files": source_manifest()}, indent=2) + "\n")
    check_hash(SOURCE / "Cargo.lock", LOCK["source"]["cargo_lock_sha256"])
    verify_patch_tests()
    print(f"Verified patched source: {SOURCE}", flush=True)


def environment() -> dict[str, str]:
    env = os.environ.copy()
    toolchain = CACHE / "toolchain"
    paths = [toolchain / "cargo/bin", toolchain / "protoc/bin", toolchain / "python/bin"]
    env["PATH"] = os.pathsep.join(map(str, paths)) + os.pathsep + env["PATH"]
    if (toolchain / "rustup").is_dir():
        env["RUSTUP_HOME"] = str(toolchain / "rustup")
        env["CARGO_HOME"] = str(toolchain / "cargo")
    env.update({
        "RUSTUP_TOOLCHAIN": LOCK["rust_toolchain"],
        "CARGO_BUILD_JOBS": "2", "CARGO_INCREMENTAL": "0",
        "SOURCE_DATE_EPOCH": str(LOCK["source_date_epoch"]),
        "CARGO_PROFILE_DEV_DEBUG": "0", "CARGO_PROFILE_TEST_DEBUG": "0",
        "CARGO_TARGET_DIR": str(CACHE / "target"),
        "VECTOR_BUILD_DESC": f"{LOCK['build_id']};source={LOCK['source']['commit']};patches={PATCH_IDS}",
        # Do not accidentally report the enclosing SOCP checkout as Vector's source revision.
        "GIT_CEILING_DIRECTORIES": str(CACHE),
        "TMPDIR": str(CACHE / "tmp"),
    })
    for key, value in LOCK["build_profile"].items():
        if key not in ("name", "jobs"):
            env["CARGO_PROFILE_RELEASE_" + key.upper()] = str(value).lower()
    Path(env["TMPDIR"]).mkdir(parents=True, exist_ok=True)
    version = subprocess.check_output(["rustc", "--version"], env=env, text=True).split()[1]
    if version != LOCK["rust_toolchain"]:
        raise RuntimeError(f"Expected Rust {LOCK['rust_toolchain']}, got {version}")
    return env


def cargo_args(env: dict[str, str], command: str) -> list[str]:
    args = ["cargo", command, "--locked"]
    if env.get("SOCP_VECTOR_TARGET"):
        args += ["--target", env["SOCP_VECTOR_TARGET"]]
    return args


def test():
    prepare()
    env = environment()
    ARTIFACTS.mkdir(parents=True, exist_ok=True)
    # Run the whole disk-v2 module; explicitly check every new test is present so
    # a misspelled/removed filter cannot produce a false-green zero-test result.
    listing = subprocess.check_output(cargo_args(env, "test") + ["--package", "vector-buffers", "--lib", "--", "--list"],
                                      cwd=SOURCE, env=env, text=True)
    for expected in LOCK["regression_tests"]:
        if expected + ": test" not in listing:
            raise RuntimeError(f"Missing regression test: {expected}")
    (ARTIFACTS / "buffer-tests.list").write_text(listing)
    command = cargo_args(env, "test") + ["--package", "vector-buffers", "--lib", "variants::disk_v2", "--", "--nocapture", "--test-threads=1"]
    run(*command, cwd=SOURCE, env=env)
    (ARTIFACTS / "buffer-tests.passed.json").write_text(json.dumps({
        "build_id": LOCK["build_id"], "source_manifest_sha256": sha(CACHE / "source-manifest.json"),
        "command": command, "required_regressions": LOCK["regression_tests"], "result": "passed"}, indent=2) + "\n")


def artifact(env: dict[str, str]):
    ARTIFACTS.mkdir(parents=True, exist_ok=True)
    target = env.get("SOCP_VECTOR_TARGET")
    binary = CACHE / "target"
    if target:
        binary /= target
    binary /= "release/vector"
    shutil.copy2(binary, ARTIFACTS / "vector")
    version = subprocess.check_output([str(binary), "--version"], text=True, env=env).strip()
    if LOCK["build_id"] not in version:
        raise RuntimeError(f"Binary build marker missing: {version}")
    host = subprocess.check_output(["rustc", "-vV"], env=env, text=True)
    triple = target or next(line[6:] for line in host.splitlines() if line.startswith("host: "))
    metadata = json.loads(subprocess.check_output([
        "cargo", "metadata", "--locked", "--format-version", "1", "--filter-platform", triple,
        "--no-default-features", "--features", FEATURES], cwd=SOURCE, env=env, text=True))
    graph = {node["id"]: node for node in metadata["resolve"]["nodes"]}
    root = metadata["resolve"]["root"]
    needed, pending = set(), [root]
    while pending:
        current = pending.pop()
        if current in needed:
            continue
        needed.add(current)
        for dep in graph[current]["deps"]:
            # Exclude dev-only dependencies from the runtime BOM.
            if any(kind["kind"] != "dev" for kind in dep["dep_kinds"]):
                pending.append(dep["pkg"])
    cargo_lock = tomllib.loads((SOURCE / "Cargo.lock").read_text())
    hashes = {(p["name"], p["version"], p.get("source")): p.get("checksum") for p in cargo_lock["package"]}
    components = []
    for package in sorted(metadata["packages"], key=lambda p: p["id"]):
        if package["id"] not in needed:
            continue
        component = {"type": "library", "bom-ref": package["id"], "name": package["name"],
                     "version": package["version"], "purl": f"pkg:cargo/{package['name']}@{package['version']}"}
        if package.get("license"):
            component["licenses"] = [{"expression": package["license"]}]
        checksum = hashes.get((package["name"], package["version"], package.get("source")))
        if checksum:
            component["hashes"] = [{"alg": "SHA-256", "content": checksum}]
        if package.get("source"):
            component["properties"] = [{"name": "cargo:source", "value": package["source"]}]
        components.append(component)
    apk_db = env.get("SOCP_VECTOR_APK_DB")
    if apk_db:
        for record in Path(apk_db).read_text().strip().split("\n\n"):
            fields = dict(line.split(":", 1) for line in record.splitlines() if ":" in line)
            if "P" in fields and "V" in fields:
                components.append({"type": "library", "bom-ref": f"apk:{fields['P']}@{fields['V']}",
                    "name": fields["P"], "version": fields["V"],
                    "purl": f"pkg:apk/alpine/{fields['P']}@{fields['V']}",
                    "properties": [{"name": "alpine:declared-license", "value": fields.get("L", "unknown")} ]})
    sbom = {"bomFormat": "CycloneDX", "specVersion": "1.5", "version": 1,
            "metadata": {"component": {"type": "application", "name": "socp-vector", "version": LOCK["build_id"]},
                         "properties": [{"name": "socp:sbom-scope", "value": "resolved target Rust runtime/build dependency graph" + (" and inherited Alpine runtime packages" if apk_db else "; native host OS excluded")}]},
            "components": components,
            "dependencies": [{"ref": id_, "dependsOn": sorted(d["pkg"] for d in graph[id_]["deps"] if d["pkg"] in needed)} for id_ in sorted(needed)]}
    (ARTIFACTS / "sbom.cdx.json").write_text(json.dumps(sbom, indent=2) + "\n")
    build_tools = {}
    for tool in ("cargo", "cc", "c++", "cmake", "protoc", "git"):
        result = subprocess.run([tool, "--version"], env=env, text=True,
                                stdout=subprocess.PIPE, stderr=subprocess.STDOUT, check=True)
        build_tools[tool] = result.stdout.splitlines()[0]
    provenance = {"build_id": LOCK["build_id"], "source": LOCK["source"], "patches": LOCK["patches"],
                  "features": LOCK["features"], "build_profile": LOCK["build_profile"], "target": triple,
                  "source_date_epoch": LOCK["source_date_epoch"],
                  "rustc": host.strip(), "build_tools": build_tools, "vector_version": version, "binary_sha256": sha(binary),
                  "lock_sha256": sha(LOCK_PATH), "sbom_sha256": sha(ARTIFACTS / "sbom.cdx.json"),
                  "builder_image": LOCK["builder_image"] if target and "musl" in target else None,
                  "runtime_image": LOCK["runtime_image"] if apk_db else None}
    wrapper = env.get("RUSTC_WRAPPER")
    provenance["host_rustc_wrapper_sha256"] = sha(Path(wrapper)) if wrapper else None
    (ARTIFACTS / "provenance.json").write_text(json.dumps(provenance, indent=2) + "\n")
    shutil.copy2(SOURCE / "Cargo.lock", ARTIFACTS / "Cargo.lock")
    for name in ("LICENSE", "NOTICE", "LICENSE-3rdparty.csv"):
        shutil.copy2(SOURCE / name, ARTIFACTS / name)
    shutil.copytree(SOURCE / "licenses", ARTIFACTS / "licenses", dirs_exist_ok=True)
    # Corresponding source and exact backports travel with the binary, not just a URL.
    shutil.copy2(ARCHIVE, ARTIFACTS / "vector-source.tar.gz")
    kit_output = ARTIFACTS / "build-kit"
    shutil.copytree(KIT, kit_output / "agents/vector-runtime", dirs_exist_ok=True,
                    ignore=shutil.ignore_patterns("__pycache__"))
    (kit_output / "build").mkdir(parents=True, exist_ok=True)
    shutil.copy2(ROOT / "build/vector-runtime.sh", kit_output / "build/vector-runtime.sh")
    print(f"Built {version}\nArtifacts: {ARTIFACTS}", flush=True)


def native():
    prepare()
    env = environment()
    for tool in ("cc", "c++", "make", "cmake", "protoc", "perl", "pkg-config", "git"):
        if not shutil.which(tool, path=env["PATH"]):
            raise RuntimeError(f"Missing build dependency: {tool}; see agents/vector-runtime/README.md")
    run(*(cargo_args(env, "build") + ["--release", "--package", "vector", "--bin", "vector",
                                      "--no-default-features", "--features", FEATURES]), cwd=SOURCE, env=env)
    check_hash(SOURCE / "Cargo.lock", LOCK["source"]["cargo_lock_sha256"])
    artifact(env)


def image():
    verify()
    run("docker", "build", "--file", KIT / "Dockerfile", "--tag", LOCK["image"],
        "--build-arg", f"BUILDER_IMAGE={LOCK['builder_image']}",
        "--build-arg", f"RUNTIME_IMAGE={LOCK['runtime_image']}",
        "--build-arg", f"SOCP_LOCK_SHA256={sha(LOCK_PATH)}", ROOT)
    verify_image()


def verify_image():
    verify()
    try:
        metadata = json.loads(subprocess.check_output(["docker", "image", "inspect", LOCK["image"]], text=True))[0]
    except (subprocess.CalledProcessError, FileNotFoundError) as exc:
        raise RuntimeError("Patched Vector image is unavailable; run: bash build/vector-runtime.sh image") from exc
    labels = metadata.get("Config", {}).get("Labels", {}) or {}
    for key, expected in {**LABELS, "io.socp.vector.lock-sha256": sha(LOCK_PATH)}.items():
        if labels.get(key) != expected:
            raise RuntimeError(f"Patched Vector image provenance mismatch ({key}); rebuild: bash build/vector-runtime.sh image")
    invocation = ["docker", "run", "--rm", "--network", "none"]
    provenance = json.loads(subprocess.check_output(invocation + ["--entrypoint", "/bin/cat", LOCK["image"],
                            "/usr/share/vector/socp/provenance.json"], text=True))
    if provenance.get("lock_sha256") != sha(LOCK_PATH) or "-unknown-linux-musl" not in provenance.get("target", ""):
        raise RuntimeError("Packaged Vector provenance does not match the musl runtime lock")
    if provenance.get("host_rustc_wrapper_sha256") != LOCK["host_rustc_wrapper_sha256"]:
        raise RuntimeError("Packaged Vector host compiler wrapper does not match the runtime lock")
    binary_sha = subprocess.check_output(invocation + ["--entrypoint", "/bin/sh", LOCK["image"], "-ec",
                                        "sha256sum /usr/local/bin/vector"], text=True).split()[0]
    if binary_sha != provenance.get("binary_sha256"):
        raise RuntimeError("Packaged Vector binary hash does not match provenance")
    version = subprocess.check_output(invocation + [LOCK["image"], "--version"], text=True)
    if LOCK["build_id"] not in version:
        raise RuntimeError("Patched Vector image binary marker mismatch")
    # Reject accidental GNU binaries in Alpine and retain the health-probe tools.
    run("docker", "run", "--rm", "--network", "none", "--entrypoint", "/bin/sh", LOCK["image"], "-ec",
        "command -v wget; test -s /etc/ssl/certs/ca-certificates.crt; test -s /usr/share/vector/socp/provenance.json; test -s /usr/share/vector/socp/sbom.cdx.json")
    print(f"Verified local image {LOCK['image']}: {version.strip()}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("verify", "prepare", "test", "native", "image", "verify-image"))
    args = parser.parse_args()
    globals()[args.command.replace("-", "_")]()


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, subprocess.CalledProcessError, FileNotFoundError) as error:
        print(f"vector-runtime: {error}", file=sys.stderr)
        sys.exit(1)
