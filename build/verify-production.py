#!/usr/bin/env python3
"""Static guardrails for the production image and Kubernetes release baseline.

This is a contract check, not a claim that a cluster, registry, backup target,
or external connector has been operated successfully. Release CI still has to
resolve real image digests and run the configured scan and recovery gates.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

from runtime_topology import load_topology


ROOT = Path(__file__).resolve().parents[1]
DOCKERFILE = ROOT / "deploy" / "docker" / "Dockerfile.jvm"
DOCKER_README = ROOT / "deploy" / "docker" / "README.md"
K8S_DIR = ROOT / "deploy" / "k8s"
K8S_NAMESPACE = K8S_DIR / "namespace.yaml"
HELM_CHART = ROOT / "deploy" / "helm" / "socp-core"
HELM_VALUES = HELM_CHART / "values.yaml"
HELM_SCHEMA = HELM_CHART / "values.schema.json"
AWS_RELEASE_WORKFLOW = ROOT / ".github" / "workflows" / "aws-release.yml"
AWS_INFRASTRUCTURE_WORKFLOW = (
    ROOT / ".github" / "workflows" / "aws-infrastructure.yml"
)
COMPOSE_PROD = ROOT / "infra" / "docker-compose.prod.yml"
REQUIRED_HELM = {
    ".helmignore",
    "Chart.yaml",
    "values.yaml",
    "values.schema.json",
    "values-dev.yaml",
    "values-staging.yaml",
    "values-production.yaml",
    "ci/test-values.yaml",
    "templates/_helpers.tpl",
    "templates/configmap.yaml",
    "templates/deployments.yaml",
    "templates/horizontalpodautoscalers.yaml",
    "templates/networkpolicies.yaml",
    "templates/poddisruptionbudgets.yaml",
    "templates/prometheusrule.yaml",
    "templates/serviceaccount.yaml",
    "templates/services.yaml",
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


def check_action_pins(
    errors: list[str], workflow: str, workflow_label: str
) -> None:
    action_references = re.findall(r"^\s*uses:\s+([^\s#]+)", workflow, re.MULTILINE)
    for reference in action_references:
        if not re.search(r"@[0-9a-f]{40}$", reference):
            errors.append(
                f"{workflow_label} action is not commit-pinned: {reference}"
            )


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
    if DOCKER_README.is_file():
        docker_readme = DOCKER_README.read_text(encoding="utf-8")
        if re.search(r"(?m)^\s*-t\s+[^\s]+@sha256:", docker_readme):
            errors.append("Docker build documentation must not tag an image by digest")

    if not K8S_NAMESPACE.is_file():
        errors.append("missing deploy/k8s/namespace.yaml")
    else:
        namespace = K8S_NAMESPACE.read_text(encoding="utf-8")
        for mode in ("enforce", "audit", "warn"):
            marker = f"pod-security.kubernetes.io/{mode}: restricted"
            if marker not in namespace:
                errors.append(f"Kubernetes namespace must set {marker}")

    for path in sorted(K8S_DIR.rglob("*.yaml")):
        text = path.read_text(encoding="utf-8")
        if re.search(r"^kind:\s*Deployment\s*$", text, re.MULTILINE):
            errors.append(
                f"{path.relative_to(ROOT)} duplicates Helm-owned application Deployments"
            )

    if not HELM_CHART.is_dir():
        errors.append("missing deploy/helm/socp-core")
    else:
        actual = {
            path.relative_to(HELM_CHART).as_posix()
            for path in HELM_CHART.rglob("*")
            if path.is_file()
        }
        for name in sorted(REQUIRED_HELM - actual):
            errors.append(f"missing Helm release contract: {name}")

        templates = "\n".join(
            path.read_text(encoding="utf-8")
            for path in sorted((HELM_CHART / "templates").glob("*"))
            if path.is_file()
        )
        values = HELM_VALUES.read_text(encoding="utf-8") if HELM_VALUES.is_file() else ""
        schema = HELM_SCHEMA.read_text(encoding="utf-8") if HELM_SCHEMA.is_file() else ""
        template_checks = {
            "digest-only image rendering": 'printf "%s@%s" $repository $digest',
            "RollingUpdate strategy": "type: RollingUpdate",
            "readiness probe": "readinessProbe:",
            "liveness probe": "livenessProbe:",
            "startup probe": "startupProbe:",
            "non-root pod": "runAsNonRoot: true",
            "writable volume group": "fsGroup: 10001",
            "bounded volume ownership change": "fsGroupChangePolicy: OnRootMismatch",
            "read-only root filesystem": "readOnlyRootFilesystem: true",
            "dropped Linux capabilities": "drop: [ALL]",
            "disabled privilege escalation": "allowPrivilegeEscalation: false",
            "runtime-default seccomp": "type: RuntimeDefault",
            "disabled service-account token": "automountServiceAccountToken: false",
            "resource requests and limits": "toYaml $workload.resources",
            "runtime ConfigMap checksum": "checksum/runtime-config:",
        }
        for label, marker in template_checks.items():
            if marker not in templates:
                errors.append(f"Helm chart lacks {label}")
        if ":latest" in templates or ":latest" in values:
            errors.append("Helm chart must not use mutable latest image tags")
        if "^sha256:[0-9a-f]{64}$" not in schema:
            errors.append("Helm values schema must require a full sha256 image digest")

        workload_domains = {
            "api-gateway": runtime_domains.get("api-gateway"),
            "search-config-api": runtime_domains.get("search-config"),
            "search-config-worker": runtime_domains.get("search-config"),
            "detect-web-api": runtime_domains.get("detect-web"),
            "detect-web-worker": runtime_domains.get("detect-web"),
            "alert-web": runtime_domains.get("alert-web"),
        }
        workload_values = values.split("\nworkloads:\n", 1)[-1]
        for workload, domain in workload_domains.items():
            block = re.search(
                rf"(?ms)^  {re.escape(workload)}:\s*$.*?(?=^  [a-z0-9-]+:\s*$|\Z)",
                workload_values,
            )
            if block is None:
                errors.append(f"Helm values omit workload {workload}")
            elif domain is None or f"runtimeDomain: {domain}" not in block.group(0):
                errors.append(f"Helm workload {workload} runtime-domain drifted")

        required_values = {
            'SERVER_PORT: "8080"',
            "SPRING_DATA_REDIS_HOST: redis.socp-data.svc.cluster.local",
            'SPRING_DATA_REDIS_PORT: "6379"',
            "SOCP_SEARCH_RUNTIME_ROLE: api",
            "SOCP_SEARCH_RUNTIME_ROLE: worker",
            "SOCP_DETECT_RUNTIME_ROLE: api",
            "SOCP_DETECT_RUNTIME_ROLE: worker",
            "SOCP_DETECT_INSTANCE_ID: metadata.uid",
            "SOCP_HEALTH_REQUIRED_ENDPOINTS:",
            "SOCP_SECURITY_REQUIRE_GATEWAY: \"true\"",
            "SOCP_SSA_URI: http://alert-web:8080",
            "SOCP_GLS_URI: http://search-config-api:8080",
            "SOCP_GAS_WEB_URI: http://detect-web-api:8080",
            "SOCP_GAS_WORKER_URI: http://detect-web-worker:8080",
            "SOCP_DETECT_URL: http://detect-web-api:8080",
            "SOCP_ALERT_URL: http://alert-web:8080",
        }
        for marker in required_values:
            if marker not in values:
                errors.append(f"Helm values lack production marker: {marker}")
        if "localhost" in values or "127.0.0.1" in values:
            errors.append("Helm runtime config must not route dependencies to loopback")

    if not AWS_RELEASE_WORKFLOW.is_file():
        errors.append("missing AWS release workflow")
    else:
        release = AWS_RELEASE_WORKFLOW.read_text(encoding="utf-8")
        release_markers = {
            "OIDC authentication": "id-token: write",
            "AWS account guard": "allowed-account-ids:",
            "immutable source tag": 'image_tag="$repository:sha-$RELEASE_SHA"',
            "idempotent immutable-image retry": "ImageNotFoundException",
            "source revision verification": "Verify image source revision",
            "critical vulnerability gate": "Reject critical vulnerabilities",
            "CycloneDX SBOM": "format: cyclonedx",
            "registry digest resolution": "aws ecr describe-images",
            "production no-rebuild promotion": "Resolve existing immutable images",
            "production mainline guard": "git merge-base --is-ancestor",
            "atomic Helm rollout": "--atomic --wait",
            "protected production environment": "environment: production",
            "explicit kubectl toolchain": "azure/setup-kubectl@",
        }
        for label, marker in release_markers.items():
            if marker not in release:
                errors.append(f"AWS release workflow lacks {label}")
        check_action_pins(errors, release, "AWS release workflow")

    if not AWS_INFRASTRUCTURE_WORKFLOW.is_file():
        errors.append("missing AWS infrastructure workflow")
    else:
        infrastructure = AWS_INFRASTRUCTURE_WORKFLOW.read_text(encoding="utf-8")
        infrastructure_markers = {
            "same-repository pull-request guard": (
                "github.event.pull_request.head.repo.full_name == github.repository"
            ),
            "plan-only AWS role": "AWS_TERRAFORM_PLAN_ROLE_ARN",
            "separate apply AWS role": "AWS_TERRAFORM_APPLY_ROLE_ARN",
            "OIDC authentication": "id-token: write",
            "AWS account guard": "allowed-account-ids:",
            "protected infrastructure environment": "environment: infrastructure",
            "explicit destroy confirmation": "destroy-socp-dev",
            "saved Terraform plan": "dev.tfplan",
            "saved-plan application": 'apply\n          -auto-approve "$GITHUB_WORKSPACE/.cache/terraform/dev.tfplan"',
            "remote state lock timeout": "-lock-timeout=5m",
            "explicit kubectl toolchain": "azure/setup-kubectl@",
            "cluster prerequisite application": (
                "kubectl apply -f deploy/k8s/namespace.yaml"
            ),
        }
        for label, marker in infrastructure_markers.items():
            if marker not in infrastructure:
                errors.append(f"AWS infrastructure workflow lacks {label}")
        check_action_pins(errors, infrastructure, "AWS infrastructure workflow")

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
        "(Helm source, runtime domains, digest images, pod hardening, AWS workflows)"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
