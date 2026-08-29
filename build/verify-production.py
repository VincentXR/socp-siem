#!/usr/bin/env python3
"""Static guardrails for the production image and Kubernetes release baseline.

This is a contract check, not a claim that a cluster, registry, backup target,
or external connector has been operated successfully. Release CI still has to
substitute real image digests and run scanner, signature, and restore drills.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
DOCKERFILE = ROOT / "deploy" / "docker" / "Dockerfile.jvm"
K8S_DIR = ROOT / "deploy" / "k8s" / "base"
COMPOSE_PROD = ROOT / "infra" / "docker-compose.prod.yml"
REQUIRED_K8S = {
    "namespace.yaml",
    "service-account.yaml",
    "runtime-config.yaml",
    "api-gateway.yaml",
    "detect-web.yaml",
    "alert-web.yaml",
    "kustomization.yaml",
}


def main() -> int:
    errors: list[str] = []
    if not DOCKERFILE.is_file():
        errors.append("missing deploy/docker/Dockerfile.jvm")
    else:
        docker = DOCKERFILE.read_text(encoding="utf-8")
        if not re.search(r"^ARG RUNTIME_IMAGE\s*$", docker, re.MULTILINE):
            errors.append("Dockerfile must require ARG RUNTIME_IMAGE")
        if not re.search(r"^FROM \$\{RUNTIME_IMAGE\}", docker, re.MULTILINE):
            errors.append("Dockerfile must consume the immutable runtime image argument")
        if "USER 10001:10001" not in docker:
            errors.append("Dockerfile must run as UID/GID 10001")
        if re.search(r":latest(?:\s|$)", docker):
            errors.append("Dockerfile must not use a latest image tag")

    if not K8S_DIR.is_dir():
        errors.append("missing deploy/k8s/base")
    else:
        actual = {path.name for path in K8S_DIR.glob("*.yaml")}
        for name in sorted(REQUIRED_K8S - actual):
            errors.append(f"missing Kubernetes release contract: {name}")
        for path in sorted(K8S_DIR.glob("*.yaml")):
            text = path.read_text(encoding="utf-8")
            if re.search(r"image:\s+[^\n]*:latest\b", text):
                errors.append(f"{path.relative_to(ROOT)} uses mutable :latest")
            if "kind: Deployment" not in text:
                continue
            checks = {
                "RollingUpdate strategy": r"strategy:\s*[\s\S]*?type:\s*RollingUpdate",
                "readiness probe": r"readinessProbe:",
                "liveness probe": r"livenessProbe:",
                "non-root pod": r"runAsNonRoot:\s*true",
                "read-only root filesystem": r"readOnlyRootFilesystem:\s*true",
                "dropped Linux capabilities": r"drop:\s*\[ALL\]",
                "resource requests": r"requests:\s*[\s\S]*?memory:",
                "resource limits": r"limits:\s*[\s\S]*?memory:",
            }
            for label, pattern in checks.items():
                if not re.search(pattern, text):
                    errors.append(f"{path.relative_to(ROOT)} lacks {label}")
            images = re.findall(r"^\s*image:\s*([^\s]+)", text, re.MULTILINE)
            for image in images:
                if "@sha256:" not in image:
                    errors.append(f"{path.relative_to(ROOT)} image is not digest-addressed: {image}")
                elif not re.search(r"@sha256:(?:[0-9a-f]{64}|REPLACE_WITH_RELEASE_DIGEST)$", image):
                    errors.append(f"{path.relative_to(ROOT)} has malformed image digest: {image}")

    if not COMPOSE_PROD.is_file():
        errors.append("missing infra/docker-compose.prod.yml")
    else:
        compose = COMPOSE_PROD.read_text(encoding="utf-8")
        for name in ("SOCP_API_GATEWAY_IMAGE", "SOCP_SEARCH_CONFIG_IMAGE",
                     "SOCP_DETECT_WEB_IMAGE", "SOCP_ALERT_WEB_IMAGE"):
            if not re.search(rf"\$\{{{name}:\?", compose):
                errors.append(f"production Compose must require {name}")
        for bad in ("PASSWORD: socp", "PASSWORD: admin", "PASSWORD: Socp@",
                    "TOKEN: dev-", ":latest"):
            if bad in compose:
                errors.append(f"production Compose contains a development fallback: {bad}")

    if errors:
        for error in errors:
            print(f"[FAIL] {error}", file=sys.stderr)
        return 1
    print("Production deployment contract passed (digest images, non-root pods, probes, resources)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
