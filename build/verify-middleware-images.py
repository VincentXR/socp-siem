#!/usr/bin/env python3
"""Keep middleware integration images aligned with the local Compose stack."""

from __future__ import annotations

import os
from pathlib import Path
import re
import sys


ROOT = Path(__file__).resolve().parents[1]
COMPOSE = ROOT / "infra/docker-compose.yml"
CI = ROOT / ".github/workflows/ci.yml"

TEST_IMAGE_SERVICES = {
    "postgres": "postgres",
    "opensearch": "opensearchproject/opensearch",
    "redis": "redis",
    "clickhouse": "clickhouse/clickhouse-server",
}


def service_image(compose: str, service: str) -> str | None:
    """Return the image from a top-level Compose service block."""

    match = re.search(
        rf"(?ms)^  {re.escape(service)}:\n(?:(?!^  \S).)*?^    image:\s+(\S+)\s*$",
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


def main() -> int:
    errors: list[str] = []
    compose = COMPOSE.read_text(encoding="utf-8")
    ci = CI.read_text(encoding="utf-8")

    runtime_images: dict[str, str] = {}
    for service in ("postgres", "kafka", "opensearch", "redis", "clickhouse"):
        image = service_image(compose, service)
        if image is None:
            errors.append(f"Compose service {service!r} has no image")
        else:
            runtime_images[service] = image
            if image not in ci:
                errors.append(f"CI does not use the Compose {service} image {image}")

    for path in java_test_files():
        relative = path.relative_to(ROOT)
        text = path.read_text(encoding="utf-8")

        for service, repository in TEST_IMAGE_SERVICES.items():
            image_pattern = rf"{re.escape(repository)}:[^\s\"')]+"
            images = set(re.findall(image_pattern, text))
            expected = runtime_images.get(service)
            if expected is not None:
                for image in sorted(images):
                    if image != expected:
                        errors.append(f"{relative}: {service} test image {image} != {expected}")

        kafka_images = set(re.findall(r"(?:apache/kafka|confluentinc/cp-kafka):[^\s\"')]+", text))
        expected_kafka = runtime_images.get("kafka")
        for image in sorted(kafka_images):
            if expected_kafka is not None and image != expected_kafka:
                errors.append(f"{relative}: Kafka test image {image} != {expected_kafka}")
        if "new KafkaContainer" in text:
            if expected_kafka is not None and expected_kafka not in text:
                errors.append(f"{relative}: KafkaContainer does not use {expected_kafka}")
            if "import org.testcontainers.kafka.KafkaContainer;" not in text:
                errors.append(f"{relative}: KafkaContainer must use the Apache-compatible Testcontainers adapter")

    chaos = (ROOT / "build/chaos-pipeline.py").read_text(encoding="utf-8")
    for env_name, service in (
        ("SOCP_POSTGRES_IMAGE", "postgres"),
        ("SOCP_OPENSEARCH_IMAGE", "opensearch"),
    ):
        expected = runtime_images.get(service)
        default_pattern = (
            rf'os\.environ\.get\(\s*"{re.escape(env_name)}"\s*,\s*'
            rf'"{re.escape(expected or "")}"\s*\)'
        )
        if expected is not None and re.search(default_pattern, chaos) is None:
            errors.append(f"build/chaos-pipeline.py: {env_name} default is not {expected}")

    if errors:
        print("[FAIL] Middleware image contract drift detected:", file=sys.stderr)
        for error in errors:
            print(f"  - {error}", file=sys.stderr)
        return 1

    print("[PASS] Middleware test and CI images match the Compose runtime versions")
    return 0


if __name__ == "__main__":
    sys.exit(main())
