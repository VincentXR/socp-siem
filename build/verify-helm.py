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
IMAGE_KEYS = {
    "apiGateway": "socp-api-gateway",
    "searchConfig": "socp-search-config",
    "detectWeb": "socp-detect-web",
    "alertWeb": "socp-alert-web",
}
# Workloads whose application.yml exposes the actuator metrics endpoint, and
# the one that deliberately does not. services/api-gateway/.../application.yml
# limits the application port to health and defers Prometheus samples to a
# dedicated internal management path/port, so the chart must not claim to
# scrape it.
METRICS_WORKLOADS = (
    "alert-web",
    "detect-web-api",
    "detect-web-worker",
    "search-config-api",
    "search-config-worker",
)
METRICS_EXCLUDED = ("api-gateway",)
# Locked per-profile monitoring intent. Change this together with the matching
# values-<profile>.yaml. This table exists because the previous contract
# asserted a rendered PrometheusRule for every profile while injecting its own
# values file that enabled the rule, so the assertion said nothing about what a
# real release renders.
MONITORING_ENABLED = {"dev": False, "staging": False, "production": False}
MONITORING_OVERRIDE = (
    "--set", "monitoring.prometheusRule.enabled=true",
    "--set", "monitoring.serviceMonitor.enabled=true",
)
RELEASE_WORKFLOW = ROOT / ".github/workflows/aws-release.yml"


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


def release_image_args() -> list[str]:
    """Supply images the way the release workflow does.

    `.github/workflows/aws-release.yml` injects `images.<key>.repository` and
    `images.<key>.digest` with `--set-string`; no values file carries them. The
    previous verifier instead relied on `ci/test-values.yaml`, which supplied
    the images and silently flipped `monitoring.prometheusRule.enabled` at the
    same time, so every profile appeared to render a PrometheusRule.
    """
    args: list[str] = []
    for key, artifact in IMAGE_KEYS.items():
        args += ["--set-string", f"images.{key}.repository=example.invalid/{artifact}"]
        args += ["--set-string", f"images.{key}.digest=sha256:{'0' * 64}"]
    return args


def values_workload_block(values: str, workload: str) -> str:
    """Return the slice of values.yaml belonging to one workload."""
    tail = values.split("\nworkloads:\n", 1)[-1]
    match = re.search(
        rf"(?ms)^  {re.escape(workload)}:\s*$.*?(?=^  [a-z0-9-]+:\s*$|\Z)",
        tail,
    )
    return match.group(0) if match else ""


def verify_monitoring(
    profile: str,
    documents: dict[tuple[str, str], str],
    structural: dict[tuple[str, str], str],
    values: str,
    errors: list[str],
) -> None:
    prefix = f"{profile}: monitoring"
    enabled = MONITORING_ENABLED[profile]

    # Intent must be declared by the profile, never inherited from chart
    # defaults: silent inheritance is what hid the original drift.
    profile_values = CHART / f"values-{profile}.yaml"
    require(errors,
            re.search(r"(?m)^monitoring:\s*$", profile_values.read_text(encoding="utf-8")) is not None,
            f"{prefix}: values-{profile}.yaml must declare monitoring explicitly")

    rendered_rule = ("PrometheusRule", "socp-slo-alerts") in documents
    scrapes = sorted(name for kind, name in documents if kind == "ServiceMonitor")
    expected_scrapes = sorted(METRICS_WORKLOADS) if enabled else []

    require(errors, rendered_rule == enabled,
            f"{prefix}: release render has PrometheusRule={rendered_rule}, locked intent={enabled}")
    require(errors, scrapes == expected_scrapes,
            f"{prefix}: release render scrapes {scrapes}, expected {expected_scrapes}")
    require(errors, not rendered_rule or bool(scrapes),
            f"{prefix}: PrometheusRule without ServiceMonitor evaluates against no data")
    for excluded in METRICS_EXCLUDED:
        require(errors, excluded not in scrapes,
                f"{prefix}: {excluded} must not be scraped (its application port exposes health only)")

    # Drift detector: anything that the flag override alone changes must be
    # exactly the monitoring objects. This catches the next flag-gated resource
    # that CI enables for itself but no real profile renders.
    expected_added: set[tuple[str, str]] = set()
    if not enabled:
        expected_added = {("PrometheusRule", "socp-slo-alerts")}
        expected_added |= {("ServiceMonitor", name) for name in METRICS_WORKLOADS}
    added = {key for key in structural if key not in documents}
    removed = {key for key in documents if key not in structural}
    require(errors, added == expected_added,
            f"{prefix}: flag override changed unexpected objects; "
            f"enabled only by overrides={sorted(added - expected_added)}, "
            f"never rendered={sorted(expected_added - added)}")
    require(errors, not removed,
            f"{prefix}: release render has objects the structural render lacks: {sorted(removed)}")

    # A scrape path that drifts from the probe path silently scrapes nothing.
    for workload in METRICS_WORKLOADS:
        block = values_workload_block(values, workload)
        base = re.search(r"(?m)^      basePath:\s*(\S+)\s*$", block)
        metrics = re.search(r"(?m)^      metricsPath:\s*(\S+)\s*$", block)
        if base is None or metrics is None:
            errors.append(f"{prefix}: workload {workload} must declare health.basePath and health.metricsPath")
            continue
        expected_path = base.group(1).removesuffix("/health") + "/prometheus"
        require(errors, metrics.group(1) == expected_path,
                f"{prefix}: workload {workload} metricsPath {metrics.group(1)} must match {expected_path}")
    for excluded in METRICS_EXCLUDED:
        block = values_workload_block(values, excluded)
        require(errors, re.search(r"(?m)^      metricsPath:", block) is None,
                f"{prefix}: workload {excluded} must not declare health.metricsPath")


def verify_profile(helm: str, profile: str, errors: list[str]) -> None:
    profile_values = CHART / f"values-{profile}.yaml"
    values = [
        "--values", str(TEST_VALUES),
        "--values", str(profile_values),
        *release_image_args(),
    ]
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

    # Second render with every optional capability forced on. Comparing the two
    # is what proves the release render reflects profile behaviour rather than
    # an override that only the verifier supplies.
    structural = manifest_documents(run([
        helm,
        "template",
        "socp-core",
        str(CHART),
        "--namespace",
        NAMESPACE,
        *values,
        *MONITORING_OVERRIDE,
    ]).stdout)

    verify_monitoring(
        profile,
        documents,
        structural,
        (CHART / "values.yaml").read_text(encoding="utf-8"),
        errors,
    )

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


def verify_test_values_scope(errors: list[str]) -> None:
    """The shared CI values fixture must supply images and nothing else.

    A behaviour override hidden in a file shared by every profile is how the
    original drift hid: the fixture enabled the rule, so the verifier reported
    a rendered PrometheusRule that no real profile produced. Declaring the keys
    explicitly keeps that class of override visible even when a profile happens
    to neutralise it.
    """
    text = TEST_VALUES.read_text(encoding="utf-8")
    keys = re.findall(r"(?m)^([A-Za-z][A-Za-z0-9_-]*):", text)
    unexpected = sorted(set(keys) - {"images"})
    require(errors, not unexpected,
            f"ci/test-values.yaml must supply images only; it also sets {unexpected}")


def verify_release_image_mechanism(errors: list[str]) -> None:
    """The chart image keys must be the ones the release workflow injects.

    The verifier renders images through `--set-string`, so if the workflow used
    different keys the chart would keep rendering here and fail only on a real
    rollout.
    """
    if not RELEASE_WORKFLOW.is_file():
        errors.append("missing AWS release workflow; cannot confirm the image injection contract")
        return
    release = RELEASE_WORKFLOW.read_text(encoding="utf-8")
    for field in ("repository", "digest"):
        if f"images.$image_key.{field}" not in release:
            errors.append(
                f"release workflow must inject images.$image_key.{field} to match the chart's image keys"
            )
    for key in IMAGE_KEYS:
        if f"image_key={key}" not in release:
            errors.append(f"release workflow does not map any service to the chart image key {key}")


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

    verify_test_values_scope(errors)
    verify_release_image_mechanism(errors)

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
