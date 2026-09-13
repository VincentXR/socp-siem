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

from runtime_topology import load_topology


ROOT = Path(__file__).resolve().parents[1]
DOCKERFILE = ROOT / "deploy" / "docker" / "Dockerfile.jvm"
K8S_DIR = ROOT / "deploy" / "k8s" / "base"
COMPOSE_PROD = ROOT / "infra" / "docker-compose.prod.yml"
REQUIRED_K8S = {
    "namespace.yaml",
    "service-account.yaml",
    "runtime-config.yaml",
    "api-gateway.yaml",
    "search-config.yaml",
    "detect-web.yaml",
    "alert-web.yaml",
    "autoscaling.yaml",
    "kustomization.yaml",
}


def runtime_domain_membership() -> dict[str, str]:
    membership: dict[str, str] = {}
    for domain in load_topology().get("logicalDomains", []):
        if not isinstance(domain, dict):
            continue
        name = domain.get("name")
        for member in domain.get("members", []):
            if isinstance(name, str) and isinstance(member, str):
                membership[member] = name
    return membership


def deployment_document(manifest: str, name: str) -> str:
    """Return one Deployment document from a multi-workload manifest."""
    for document in re.split(r"(?m)^---\s*$", manifest):
        if (re.search(r"^kind:\s*Deployment\s*$", document, re.MULTILINE)
                and re.search(rf"^  name:\s*{re.escape(name)}\s*$", document, re.MULTILINE)):
            return document
    return ""


def main() -> int:
    errors: list[str] = []
    runtime_domains = runtime_domain_membership()
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
            # HPA manifests refer to a Deployment in scaleTargetRef; only a
            # top-level workload document should receive pod hardening checks.
            if not re.search(r"^kind:\s*Deployment\s*$", text, re.MULTILINE):
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
            workload = path.stem
            expected_domain = runtime_domains.get(workload)
            actual_domains = re.findall(
                r"^\s*socp\.io/runtime-domain:\s*([a-z0-9-]+)\s*$",
                text,
                re.MULTILINE,
            )
            if expected_domain is None:
                errors.append(f"{path.relative_to(ROOT)} is not assigned to a logical runtime domain")
            elif (not actual_domains or set(actual_domains) != {expected_domain}
                  or len(actual_domains) % 2 != 0):
                errors.append(
                    f"{path.relative_to(ROOT)} must declare socp.io/runtime-domain="
                    f"{expected_domain} on Deployment and Pod metadata"
                )
            selector = re.search(r"^  selector:\s*$([\s\S]*?)^  template:\s*$",
                                 text, re.MULTILINE)
            if selector and "socp.io/runtime-domain" in selector.group(1):
                errors.append(
                    f"{path.relative_to(ROOT)} runtime-domain must not change the immutable selector"
                )
            if path.name in {"search-config.yaml", "detect-web.yaml", "alert-web.yaml"}:
                if "SOCP_HEALTH_REQUIRED_ENDPOINTS" not in text:
                    errors.append(f"{path.relative_to(ROOT)} lacks dependency-aware readiness configuration")
            images = re.findall(r"^\s*image:\s*([^\s]+)", text, re.MULTILINE)
            for image in images:
                if "@sha256:" not in image:
                    errors.append(f"{path.relative_to(ROOT)} image is not digest-addressed: {image}")
                elif not re.search(r"@sha256:(?:[0-9a-f]{64}|REPLACE_WITH_RELEASE_DIGEST)$", image):
                    errors.append(f"{path.relative_to(ROOT)} has malformed image digest: {image}")

        search_manifest = (K8S_DIR / "search-config.yaml").read_text(encoding="utf-8")
        for workload, role in (("search-config-api", "api"),
                               ("search-config-worker", "worker")):
            deployment = deployment_document(search_manifest, workload)
            if not deployment:
                errors.append(f"search-config.yaml lacks Deployment {workload}")
            elif not re.search(
                    rf"(?ms)^[ \t]+- name:\s*SOCP_SEARCH_RUNTIME_ROLE\s*$.*?"
                    rf"^[ \t]+value:\s*{re.escape(role)}\s*$",
                    deployment):
                errors.append(
                    f"search-config.yaml must set SOCP_SEARCH_RUNTIME_ROLE={role} "
                    f"for {workload}"
                )

        kustomization = (K8S_DIR / "kustomization.yaml").read_text(encoding="utf-8")
        for workload in ("api-gateway.yaml", "search-config.yaml", "detect-web.yaml", "alert-web.yaml"):
            if not re.search(rf"^\s*-\s+{re.escape(workload)}\s*$", kustomization, re.MULTILINE):
                errors.append(f"Kubernetes kustomization omits {workload}")
        if not re.search(r"^\s*-\s+autoscaling\.yaml\s*$", kustomization, re.MULTILINE):
            errors.append("Kubernetes kustomization omits autoscaling.yaml")

        autoscaling = (K8S_DIR / "autoscaling.yaml").read_text(encoding="utf-8")
        for workload in ("api-gateway", "search-config-api", "search-config-worker",
                         "detect-web-api", "detect-web-worker", "alert-web"):
            if not re.search(rf"kind:\s+HorizontalPodAutoscaler[\s\S]*?name:\s+{re.escape(workload)}\b", autoscaling):
                errors.append(f"autoscaling.yaml lacks HPA for {workload}")

        runtime = (K8S_DIR / "runtime-config.yaml").read_text(encoding="utf-8")
        required_routes = {
            "SOCP_SSA_URI": "http://alert-web:8080",
            "SOCP_GLS_URI": "http://search-config-api:8080",
            "SOCP_GAS_WEB_URI": "http://detect-web-api:8080",
            "SOCP_GAS_WORKER_URI": "http://detect-web-worker:8080",
            "SOCP_DETECT_URL": "http://detect-web-api:8080",
            "SOCP_ALERT_URL": "http://alert-web:8080",
        }
        for name, endpoint in required_routes.items():
            if not re.search(rf"^\s*{name}:\s*{re.escape(endpoint)}\s*$", runtime, re.MULTILINE):
                errors.append(f"Kubernetes runtime config must set {name}={endpoint}")
        if "localhost" in runtime or "127.0.0.1" in runtime:
            errors.append("Kubernetes runtime config must not route dependencies to loopback")

    if not COMPOSE_PROD.is_file():
        errors.append("missing infra/docker-compose.prod.yml")
    else:
        compose = COMPOSE_PROD.read_text(encoding="utf-8")
        for name in ("SOCP_PG_RUNTIME_USER", "SOCP_PG_RUNTIME_PASSWORD",
                     "SOCP_PG_MIGRATION_USER", "SOCP_PG_MIGRATION_PASSWORD"):
            if not re.search(rf"\$\{{{name}:\?", compose):
                errors.append(f"production Compose must require {name}")
        for path in (ROOT / "infra/init-sql/pg/00_roles.sh",
                     ROOT / "infra/init-sql/pg/02_runtime_grants.sh",
                     ROOT / "build/apply-postgres-roles.sh"):
            if not path.is_file():
                errors.append(f"missing PostgreSQL role contract: {path.relative_to(ROOT)}")
        for service in ("search-config-api", "search-config-worker",
                        "detect-web-api", "detect-web-worker", "alert-web"):
            block = re.search(
                rf"(?ms)^  {re.escape(service)}:\s*\n.*?(?=^  \S|\Z)",
                compose,
            )
            if block is None or not re.search(
                r"^\s+SOCP_PG_USER:\s*\$\{SOCP_PG_RUNTIME_USER:\?",
                block.group(0),
                re.MULTILINE,
            ):
                errors.append(f"production Compose must map {service} to SOCP_PG_RUNTIME_USER")
            if block is None or not re.search(
                r"^\s+SOCP_PG_PASSWORD:\s*\$\{SOCP_PG_RUNTIME_PASSWORD:\?",
                block.group(0),
                re.MULTILINE,
            ):
                errors.append(f"production Compose must map {service} to SOCP_PG_RUNTIME_PASSWORD")
            for variable in ("SOCP_PG_MIGRATION_USER", "SOCP_PG_MIGRATION_PASSWORD"):
                if block is None or not re.search(
                    rf"^\s+{variable}:\s*\$\{{{variable}:\?",
                    block.group(0),
                    re.MULTILINE,
                ):
                    errors.append(f"production Compose must pass {variable} to {service}")
            if block is None or not re.search(
                r"^\s+SOCP_SECURITY_REQUIRE_GATEWAY:\s*['\"]?true['\"]?\s*$",
                block.group(0),
                re.MULTILINE,
            ):
                errors.append(f"production Compose must require gateway trust for {service}")
        gateway_block = re.search(r"(?ms)^  api-gateway:\s*\n.*?(?=^  \S|\Z)", compose)
        if gateway_block is None or "SOCP_SECURITY_SERVICE_SECRET" not in gateway_block.group(0):
            errors.append("production Compose gateway must have the shared signing secret")
        for name in ("SOCP_API_GATEWAY_IMAGE", "SOCP_SEARCH_CONFIG_IMAGE",
                     "SOCP_DETECT_WEB_IMAGE", "SOCP_ALERT_WEB_IMAGE"):
            if not re.search(rf"\$\{{{name}:\?", compose):
                errors.append(f"production Compose must require {name}")
        for service, variable, role in (
            ("search-config-api", "SOCP_SEARCH_RUNTIME_ROLE", "api"),
            ("search-config-worker", "SOCP_SEARCH_RUNTIME_ROLE", "worker"),
            ("detect-web-api", "SOCP_DETECT_RUNTIME_ROLE", "api"),
            ("detect-web-worker", "SOCP_DETECT_RUNTIME_ROLE", "worker"),
        ):
            block = re.search(
                rf"(?ms)^  {re.escape(service)}:\s*\n.*?(?=^  \S|\Z)",
                compose,
            )
            if block is None or not re.search(
                rf"^\s+{re.escape(variable)}:\s*{re.escape(role)}\s*$",
                block.group(0),
                re.MULTILINE,
            ):
                errors.append(
                    f"production Compose must set {variable}={role} for {service}"
                )
        for bad in ("PASSWORD: socp", "PASSWORD: admin", "PASSWORD: Socp@",
                    "TOKEN: dev-", ":latest"):
            if bad in compose:
                errors.append(f"production Compose contains a development fallback: {bad}")

    if errors:
        for error in errors:
            print(f"[FAIL] {error}", file=sys.stderr)
        return 1
    print(
        "Production deployment contract passed "
        "(runtime domains, digest images, non-root pods, probes, resources)"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
