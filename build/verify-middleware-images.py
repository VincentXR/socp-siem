#!/usr/bin/env python3
"""Verify that Compose, CI, probes, and Testcontainers use one image catalog."""

from __future__ import annotations

import json
import os
from pathlib import Path
import re
import sys

from middleware_images import (
    CATALOG,
    FIXTURE_IMAGE_KEYS,
    SERVICE_IMAGE_KEYS,
    load_catalog,
)

ROOT = Path(__file__).resolve().parents[1]
COMPOSE = ROOT / "infra/docker-compose.yml"
CI = ROOT / ".github/workflows/ci.yml"
FULL_STACK = ROOT / ".github/workflows/full-stack.yml"
MINIO_DOCKERFILE = ROOT / "deploy/docker/minio/Dockerfile"
VECTOR_RUNTIME_LOCK = ROOT / "agents/vector-runtime/runtime.lock.json"


def workflow_job(workflow: str, job: str) -> str:
    match = re.search(rf"(?ms)^  {re.escape(job)}:\n(?:(?!^  \S).)*", workflow)
    return match.group(0) if match else ""


def vector_workflow_errors(workflow: str, job: str, test_command: str) -> list[str]:
    """Keep source builds and failure evidence in the job that runs Vector."""
    errors: list[str] = []
    body = workflow_job(workflow, job)
    build_position = body.find("bash build/vector-runtime.sh image")
    verify_position = body.find("bash build/vector-runtime.sh verify-image")
    test_position = body.find(test_command)
    if not (0 <= build_position < verify_position < test_position):
        errors.append(f"{job}: build and verify the patched Vector image before its tests")
    steps = re.split(r"(?m)^      - ", body)
    build_step = next((step for step in steps if "bash build/vector-runtime.sh image" in step), "")
    if not re.search(r"(?m)^        timeout-minutes: [1-9][0-9]*\s*$", build_step):
        errors.append(f"{job}: Vector source compilation needs its own bounded step timeout")
    if "continue-on-error: true" in build_step:
        errors.append(f"{job}: Vector source build must fail closed")
    uploads = [step for step in steps if "uses: actions/upload-artifact@" in step]
    for evidence in ("target/vector-delivery-evidence/", "services/search-config/target/vector-delivery-evidence/"):
        if not any("if: always()" in step and re.search(rf"(?m)^\s+{re.escape(evidence)}\s*$", step)
                   for step in uploads):
            errors.append(f"{job}: always upload {evidence} including failed tests")
    return errors


def service_image(compose: str, service: str) -> str | None:
    match = re.search(
        rf"(?ms)^  {re.escape(service)}:\n(?:(?!^  \S).)*?^    image:\s+(.+?)\s*$",
        compose,
    )
    return match.group(1) if match else None


def java_test_files() -> list[Path]:
    files: list[Path] = []
    for source_root in (ROOT / "platform", ROOT / "services"):
        for directory, dirnames, filenames in os.walk(source_root):
            dirnames[:] = [
                name for name in dirnames if name not in {"target", "node_modules"}
            ]
            directory_path = Path(directory)
            if "src" not in directory_path.parts or "test" not in directory_path.parts:
                continue
            files.extend(directory_path / name for name in filenames if name.endswith(".java"))
    return sorted(files)


def image_repository(image: str) -> str:
    reference = image.split("@", 1)[0]
    return reference.rsplit(":", 1)[0]


def main() -> int:
    errors: list[str] = []
    try:
        catalog = load_catalog()
    except (OSError, ValueError) as error:
        print(f"[FAIL] Cannot load middleware image catalog {CATALOG}: {error}", file=sys.stderr)
        return 1

    compose = COMPOSE.read_text(encoding="utf-8")
    ci = CI.read_text(encoding="utf-8")
    full_stack = FULL_STACK.read_text(encoding="utf-8")
    minio_dockerfile = MINIO_DOCKERFILE.read_text(encoding="utf-8")
    try:
        vector_runtime = json.loads(VECTOR_RUNTIME_LOCK.read_text(encoding="utf-8"))
        if catalog["SOCP_VECTOR_IMAGE"] != vector_runtime["image"]:
            errors.append("SOCP_VECTOR_IMAGE must match the source-built image in the Vector runtime lock")
    except (OSError, ValueError, KeyError) as error:
        errors.append(f"Cannot read pinned Vector runtime image: {error}")

    errors.extend(vector_workflow_errors(ci, "build", "bash build/mvnw.sh verify"))
    errors.extend(vector_workflow_errors(full_stack, "full-stack-heavy", "-Dtest=VectorConfigRendererContainerTest"))

    for service, key in SERVICE_IMAGE_KEYS.items():
        expected = catalog[key]
        reference = service_image(compose, service)
        if reference is None:
            errors.append(f"Compose service {service!r} has no image")
            continue
        if re.fullmatch(rf"\$\{{{re.escape(key)}:\?.+\}}", reference) is None:
            errors.append(
                f"Compose service {service!r} must read {key} from the image catalog, found {reference}"
            )
        if expected.endswith(":latest"):
            errors.append(f"{key} must not use the floating latest tag")

    for workflow_name, workflow in (("CI", ci), ("full-stack", full_stack)):
        if "bash build/compose.sh" not in workflow:
            errors.append(f"{workflow_name} must start middleware through build/compose.sh")
        if re.search(r"(?m)^\s+services:\s*$", workflow):
            errors.append(f"{workflow_name} must not define a second GitHub service-container stack")
        for key, expected in catalog.items():
            if expected in workflow:
                errors.append(
                    f"{workflow_name} duplicates catalog value {key}={expected}; use build/compose.sh"
                )

    for path in java_test_files():
        relative = path.relative_to(ROOT)
        text = path.read_text(encoding="utf-8")
        for service, key in {**SERVICE_IMAGE_KEYS, **FIXTURE_IMAGE_KEYS}.items():
            repository = image_repository(catalog[key])
            direct_reference = re.search(
                rf"{re.escape(repository)}[:@][^\s\"')]+", text
            )
            if direct_reference:
                errors.append(
                    f"{relative}: test must resolve {service} through MiddlewareImages, "
                    f"not {direct_reference.group(0)}"
                )
        if "new PostgreSQLContainer" in text and "MiddlewareImages.postgres()" not in text:
            errors.append(f"{relative}: PostgreSQLContainer must use MiddlewareImages.postgres()")
        if "new KafkaContainer" in text and "MiddlewareImages.kafka()" not in text:
            errors.append(f"{relative}: KafkaContainer must use MiddlewareImages.kafka()")

    chaos = (ROOT / "build/chaos-pipeline.py").read_text(encoding="utf-8")
    for service in ("postgres", "opensearch"):
        marker = f'image("{service}")'
        if marker not in chaos:
            errors.append(f"build/chaos-pipeline.py: missing catalog lookup {marker}")

    vector = (ROOT / "build/run-vector.sh").read_text(encoding="utf-8")
    if "SOCP_VECTOR_IMAGE" not in vector or "middleware-images.env" not in vector:
        errors.append("build/run-vector.sh must read SOCP_VECTOR_IMAGE from the catalog")
    if 'bash "$ROOT/build/vector-runtime.sh" verify-image' not in vector:
        errors.append("build/run-vector.sh must verify source-built Vector provenance before startup")

    compose_wrapper = (ROOT / "build/compose.sh").read_text(encoding="utf-8")
    if "--env-file" not in compose_wrapper or "middleware-images.env" not in compose_wrapper:
        errors.append("build/compose.sh must pass the middleware image catalog to Compose")

    if "FROM scratch" not in minio_dockerfile:
        errors.append("MinIO source build must use its self-contained scratch runtime")
    if 'ENTRYPOINT ["/usr/bin/minio"]' not in minio_dockerfile:
        errors.append("MinIO scratch runtime must declare the server entrypoint")
    if "BASE_IMAGE" in minio_dockerfile or "SOCP_MINIO_BASE_IMAGE" in catalog:
        errors.append("MinIO source build must not depend on the archived distribution image")

    if errors:
        print("[FAIL] Middleware image catalog contract violated:", file=sys.stderr)
        for error in errors:
            print(f"  - {error}", file=sys.stderr)
        return 1

    print("[PASS] Compose, CI, probes, and Testcontainers use the middleware image catalog")
    return 0


if __name__ == "__main__":
    sys.exit(main())
