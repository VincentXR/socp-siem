#!/usr/bin/env python3
"""Render the canonical Helm release and verify its production invariants."""

from __future__ import annotations

import os
import re
import shutil
import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
CHART = ROOT / "deploy/helm/socp-core"
TEST_VALUES = CHART / "ci/test-values.yaml"
NAMESPACE = "socp-system"
PROFILES = ("dev", "staging", "production")
WORKLOADS = {
    "api-gateway": ("gateway-ui", "socp-api-gateway"),
    "search-config-api": ("ingest-search", "socp-search-config"),
    "search-config-worker": ("ingest-search", "socp-search-config"),
    "detect-web-api": ("detection", "socp-detect-web"),
    "detect-web-worker": ("detection", "socp-detect-web"),
    "alert-web": ("alert-incident", "socp-alert-web"),
}


def helm_binary() -> str | None:
    configured = os.environ.get("SOCP_HELM_BIN")
    if configured:
        path = Path(configured)
        return str(path) if path.is_file() else None
    return shutil.which("helm")


def run(command: list[str], expect_success: bool = True) -> subprocess.CompletedProcess[str]:
    result = subprocess.run(
        command,
        cwd=ROOT,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    if expect_success and result.returncode != 0:
        detail = (result.stdout + result.stderr).strip()
        raise RuntimeError(f"command failed ({result.returncode}): {' '.join(command)}\n{detail}")
    return result


def manifest_documents(rendered: str) -> dict[tuple[str, str], str]:
    documents: dict[tuple[str, str], str] = {}
    for document in re.split(r"(?m)^---\s*$", rendered):
        kind = re.search(r"(?m)^kind:\s*([^\s]+)\s*$", document)
        name = re.search(r"(?m)^  name:\s*([^\s]+)\s*$", document)
        if not kind or not name:
            continue
        key = (kind.group(1), name.group(1))
        if key in documents:
            raise RuntimeError(f"duplicate rendered object: {key[0]}/{key[1]}")
        documents[key] = document
    return documents


def require(errors: list[str], condition: bool, message: str) -> None:
    if not condition:
        errors.append(message)


def verify_profile(helm: str, profile: str, errors: list[str]) -> None:
    profile_values = CHART / f"values-{profile}.yaml"
    values = ["--values", str(TEST_VALUES), "--values", str(profile_values)]
    run([helm, "lint", str(CHART), *values])
    rendered = run([
        helm,
        "template",
        "socp-core",
        str(CHART),
        "--namespace",
        NAMESPACE,
        *values,
    ]).stdout
    documents = manifest_documents(rendered)

    require(errors, not any(kind == "Namespace" for kind, _ in documents),
            f"{profile}: chart must not create cluster-scoped Namespace resources")
    require(errors, len([key for key in documents if key[0] == "Deployment"]) == 6,
            f"{profile}: expected six Deployments")
    require(errors, len([key for key in documents if key[0] == "Service"]) == 6,
            f"{profile}: expected six Services")
    require(errors, len([key for key in documents if key[0] == "NetworkPolicy"]) == 5,
            f"{profile}: expected five NetworkPolicies")
    require(errors, ("ServiceAccount", "socp-service") in documents,
            f"{profile}: missing non-cloud ServiceAccount")
    require(errors, ("ConfigMap", "socp-runtime") in documents,
            f"{profile}: missing runtime ConfigMap")
    require(errors, ("PrometheusRule", "socp-slo-alerts") in documents,
            f"{profile}: missing enabled PrometheusRule render")

    expected_scalers = 0 if profile == "dev" else 6
    require(errors,
            len([key for key in documents if key[0] == "HorizontalPodAutoscaler"])
            == expected_scalers,
            f"{profile}: expected {expected_scalers} HorizontalPodAutoscalers")
    require(errors,
            len([key for key in documents if key[0] == "PodDisruptionBudget"])
            == expected_scalers,
            f"{profile}: expected {expected_scalers} PodDisruptionBudgets")

    for workload, (runtime_domain, image_name) in WORKLOADS.items():
        document = documents.get(("Deployment", workload), "")
        prefix = f"{profile}: Deployment/{workload}"
        require(errors, bool(document), f"{prefix} is missing")
        if not document:
            continue
        checks = {
            "RollingUpdate strategy": r"(?m)^    type:\s*RollingUpdate\s*$",
            "readiness probe": r"readinessProbe:",
            "liveness probe": r"livenessProbe:",
            "startup probe": r"startupProbe:",
            "non-root pod": r"runAsNonRoot:\s*true",
            "writable volume group": r"fsGroup:\s*10001",
            "bounded volume ownership change": r"fsGroupChangePolicy:\s*OnRootMismatch",
            "read-only root filesystem": r"readOnlyRootFilesystem:\s*true",
            "disabled privilege escalation": r"allowPrivilegeEscalation:\s*false",
            "dropped Linux capabilities": r"drop:\s*\[ALL\]",
            "runtime-default seccomp": r"(?ms)seccompProfile:\s*\n\s+type:\s*RuntimeDefault",
            "disabled service links": r"enableServiceLinks:\s*false",
            "disabled service-account token": r"automountServiceAccountToken:\s*false",
            "resource requests": r"(?ms)resources:\s*\n\s+limits:.*?\n\s+requests:",
            "runtime ConfigMap checksum": r"checksum/runtime-config:",
        }
        for label, pattern in checks.items():
            require(errors, re.search(pattern, document) is not None,
                    f"{prefix} lacks {label}")
        require(errors,
                re.search(rf"image:\s+example\.invalid/{re.escape(image_name)}@sha256:0{{64}}\s*$",
                          document, re.MULTILINE) is not None,
                f"{prefix} is not digest-addressed to the expected artifact")
        actual_domains = re.findall(
            r"(?m)^\s+socp\.io/runtime-domain:\s*([a-z0-9-]+)\s*$", document
        )
        require(errors, actual_domains == [runtime_domain, runtime_domain],
                f"{prefix} runtime-domain labels drifted")
        selector = re.search(r"(?ms)^  selector:\s*$.*?^  template:\s*$", document)
        require(errors, selector is not None and "socp.io/runtime-domain" not in selector.group(0),
                f"{prefix} runtime-domain must not enter the immutable selector")
        if profile == "dev":
            require(errors, re.search(r"(?m)^  replicas:\s*1\s*$", document) is not None,
                    f"{prefix} must render one fixed replica")

    for workload, role in (
        ("search-config-api", "api"),
        ("search-config-worker", "worker"),
        ("detect-web-api", "api"),
        ("detect-web-worker", "worker"),
    ):
        document = documents.get(("Deployment", workload), "")
        variable = "SOCP_SEARCH_RUNTIME_ROLE" if workload.startswith("search") else "SOCP_DETECT_RUNTIME_ROLE"
        require(errors,
                re.search(rf'(?ms)- name:\s*{variable}\s*$.*?value:\s*["\']?{role}["\']?\s*$',
                          document, re.MULTILINE) is not None,
                f"{profile}: Deployment/{workload} must set {variable}={role}")

    for workload in ("detect-web-api", "detect-web-worker"):
        document = documents.get(("Deployment", workload), "")
        require(errors,
                re.search(
                    r"(?ms)- name:\s*SOCP_DETECT_INSTANCE_ID\s*$.*?fieldPath:\s*metadata\.uid\s*$",
                    document,
                    re.MULTILINE,
                ) is not None,
                f"{profile}: Deployment/{workload} must derive a unique instance ID from pod UID")

    runtime = documents.get(("ConfigMap", "socp-runtime"), "")
    required_routes = {
        "SERVER_PORT": "8080",
        "SPRING_DATA_REDIS_HOST": "redis.socp-data.svc.cluster.local",
        "SPRING_DATA_REDIS_PORT": "6379",
        "SOCP_SSA_URI": "http://alert-web:8080",
        "SOCP_GLS_URI": "http://search-config-api:8080",
        "SOCP_GAS_WEB_URI": "http://detect-web-api:8080",
        "SOCP_GAS_WORKER_URI": "http://detect-web-worker:8080",
        "SOCP_DETECT_URL": "http://detect-web-api:8080",
        "SOCP_ALERT_URL": "http://alert-web:8080",
    }
    for name, endpoint in required_routes.items():
        require(errors, f'{name}: "{endpoint}"' in runtime,
                f"{profile}: runtime config must set {name}={endpoint}")
    require(errors, "localhost" not in runtime and "127.0.0.1" not in runtime,
            f"{profile}: runtime config must not route dependencies to loopback")


def main() -> int:
    errors: list[str] = []
    helm = helm_binary()
    if helm is None:
        print("[FAIL] Helm not found; set SOCP_HELM_BIN or install Helm", file=sys.stderr)
        return 1
    if not CHART.is_dir() or not TEST_VALUES.is_file():
        print("[FAIL] canonical Helm chart or CI values are missing", file=sys.stderr)
        return 1

    insecure_defaults = run(
        [helm, "lint", str(CHART), "--values", str(CHART / "values-production.yaml")],
        expect_success=False,
    )
    if insecure_defaults.returncode == 0:
        errors.append("chart lint must reject missing image repositories and digests")

    try:
        for profile in PROFILES:
            verify_profile(helm, profile, errors)
    except RuntimeError as exc:
        errors.append(str(exc))

    if errors:
        print("Helm release contract failed:", file=sys.stderr)
        for error in errors:
            print(f"  - {error}", file=sys.stderr)
        return 1
    print("Helm release contract passed: 3 profiles, 4 images, 6 workloads")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
