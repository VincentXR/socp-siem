#!/usr/bin/env python3
"""Verify deployment, gateway, frontend health, and port registry contracts."""

from __future__ import annotations

from pathlib import Path
import re
import sys

from runtime_topology import topology_report


ROOT = Path(__file__).resolve().parents[1]


def quoted_list(text: str, name: str) -> list[str]:
    match = re.search(rf'^{name}="([^"]+)"$', text, re.MULTILINE)
    if not match:
        raise ValueError(f"missing {name}")
    return match.group(1).split()


def main() -> int:
    errors: list[str] = []
    ports_text = (ROOT / "build/ports.env").read_text(encoding="utf-8")
    try:
        services = quoted_list(ports_text, "SOCP_SERVICE_NAMES")
        modules = quoted_list(ports_text, "SOCP_MODULE_NAMES")
    except ValueError as error:
        print(f"[FAIL] {error}", file=sys.stderr)
        return 1

    if len(services) != len(set(services)) or len(modules) != len(set(modules)):
        errors.append("service/module registry contains duplicate names")
    if not set(services).issubset(modules):
        errors.append("default services must be a subset of executable modules")
    expected_modules = {path.parent.name for path in ROOT.glob("services/*/pom.xml")}
    if set(modules) != expected_modules:
        errors.append(f"SOCP_MODULE_NAMES drift: missing={sorted(expected_modules - set(modules))} extra={sorted(set(modules) - expected_modules)}")

    port_entries = re.findall(r'^SOCP_PORT_([A-Z0-9_]+)="\$\{[^:]+:-(\d+)\}"$', ports_text, re.MULTILINE)
    backend_ports = {name: int(port) for name, port in port_entries if name != "FRONTEND_WORKBENCH"}
    if len(backend_ports.values()) != len(set(backend_ports.values())):
        errors.append("backend port registry contains duplicate ports")
    for module in modules:
        key = module.upper().replace("-", "_")
        if key not in backend_ports:
            errors.append(f"{module}: missing SOCP_PORT entry")

    gateway = (ROOT / "services/api-gateway/src/main/resources/application.yml").read_text(encoding="utf-8")
    route_ids = set(re.findall(r"^\s+- id: ([a-z0-9-]+)$", gateway, re.MULTILINE))
    downstream = set(services) - {"api-gateway"}
    if not downstream.issubset(route_ids):
        errors.append(f"gateway misses default routes: {sorted(downstream - route_ids)}")
    # The split detection deployment has one deliberate auxiliary route.  It
    # is not a legacy URL alias: the browser still addresses /detect-web/**,
    # while the runtime-only paths must reach the worker.  Keep that route in
    # the contract explicitly so this check does not confuse it with the two
    # historical collector aliases.
    auxiliary_route_ids = {"detect-web-runtime"}
    legacy_route_ids = {"asset-collect", "hips-collect"}
    unexpected_routes = route_ids - downstream - auxiliary_route_ids
    if unexpected_routes != legacy_route_ids:
        errors.append(f"unexpected non-default routes: {sorted(unexpected_routes)}")
    if "detect-web-runtime" not in route_ids:
        errors.append("gateway runtime route missing: detect-web-runtime")
    for legacy, owner in (("asset-collect", "asset-web"), ("hips-collect", "hips-web")):
        expected = f"RewritePath=/{legacy}/?(?<segment>.*), /{owner}/$\\{{segment}}"
        if expected not in gateway:
            errors.append(f"gateway compatibility rewrite missing: {legacy} -> {owner}")

    health_registry = (
        ROOT / "frontend/apps/workbench/src/api/health.ts"
    ).read_text(encoding="utf-8")
    health_names = set(re.findall(r"\{ name: '([a-z0-9-]+)' \}", health_registry))
    if health_names != set(services):
        errors.append(f"frontend health registry drift: missing={sorted(set(services) - health_names)} extra={sorted(health_names - set(services))}")

    runtime = topology_report()
    errors.extend(f"runtime topology: {error}" for error in runtime["errors"])

    k8s_runtime = (ROOT / "deploy/k8s/base/runtime-config.yaml").read_text(encoding="utf-8")
    if not re.search(r"^\s*SOCP_SECURITY_REQUIRE_GATEWAY:\s*[\"']?true[\"']?\s*$",
                     k8s_runtime, re.MULTILINE):
        errors.append("Kubernetes runtime config must require gateway trust")

    role_contracts = (
        ROOT / "infra/init-sql/pg/00_roles.sh",
        ROOT / "infra/init-sql/pg/02_runtime_grants.sh",
        ROOT / "build/apply-postgres-roles.sh",
    )
    for path in role_contracts:
        if not path.is_file():
            errors.append(f"missing PostgreSQL role contract: {path.relative_to(ROOT)}")

    flyway_role_profiles = (
        "services/ai-assistant/src/main/resources/application-pg.yml",
        "services/asset-web/src/main/resources/application-pg.yml",
        "services/attack-web/src/main/resources/application-pg.yml",
        "services/detect-model/src/main/resources/application-pg.yml",
        "services/detect-web/src/main/resources/application-pg.yml",
        "services/hips-web/src/main/resources/application-pg.yml",
        "services/notify-web/src/main/resources/application-pg.yml",
        "services/search-config/src/main/resources/application-pg.yml",
        "services/alert-web/src/main/resources/application-pg.yml",
        "services/incident-web/src/main/resources/application-pg.yml",
        "services/soc-base/src/main/resources/application-pg.yml",
        "services/threat-web/src/main/resources/application-pg.yml",
    )
    for relative in flyway_role_profiles:
        path = ROOT / relative
        if not path.is_file():
            errors.append(f"missing PostgreSQL Flyway profile: {relative}")
            continue
        profile = path.read_text(encoding="utf-8")
        for variable in ("SOCP_PG_MIGRATION_USER", "SOCP_PG_MIGRATION_PASSWORD"):
            if variable not in profile:
                errors.append(f"{relative} must configure Flyway with {variable}")

    outbox_adr = (ROOT / "docs/adr/005-outbox-lifecycle.md").read_text(encoding="utf-8")
    for metric in (
        "socp.alert.outbox.dead.count",
        "socp.alert.outbox.oldest.dead.age.seconds",
        "socp.detection.outbox.dead.count",
        "socp.detection.outbox.oldest.dead.age.seconds",
    ):
        if metric not in outbox_adr:
            errors.append(f"outbox metric contract missing canonical name: {metric}")
    for legacy in ("socp_ingestion_outbox_dead_count", "socp_alert_outbox_dead_count",
                   "socp_detection_outbox_dead_count", "oldest_dead_age_seconds"):
        if legacy in outbox_adr:
            errors.append(f"outbox metric contract still contains legacy name: {legacy}")

    detection_service = (
        ROOT / "services/detect-web/src/main/java/com/socp/detect/web/service/DetectEngineService.java"
    ).read_text(encoding="utf-8")
    detection_consumer = (
        ROOT / "services/detect-web/src/main/java/com/socp/detect/web/engine/KafkaEventConsumer.java"
    ).read_text(encoding="utf-8")
    if "socp.detect.state.retention" not in detection_service:
        errors.append("DetectEngineService must use the configured state replay retention")
    if "socp.detect.state.retention" not in detection_consumer:
        errors.append("KafkaEventConsumer must use the configured state replay retention")
    if "Duration.ofHours(24)" in detection_service or "Duration.ofHours(24)" in detection_consumer:
        errors.append("detection replay code must not hard-code a 24-hour window")

    bounded_response_clients = (
        ROOT / "platform/socp-client/src/main/java/com/socp/platform/client/http/SocpHttpClient.java",
        ROOT / "platform/socp-client/src/main/java/com/socp/platform/client/http/ServiceTokenProvider.java",
        ROOT / "services/ai-assistant/src/main/java/com/socp/ai/infrastructure/llm/HttpLlmChatClient.java",
        ROOT / "services/api-gateway/src/main/java/com/socp/gateway/api/controller/OidcAuthController.java",
        ROOT / "services/report-web/src/main/java/com/socp/report/web/service/ReportService.java",
        ROOT / "services/threat-web/src/main/java/com/socp/threat/web/service/TaxiiClient.java",
    )
    for path in bounded_response_clients:
        source = path.read_text(encoding="utf-8")
        if re.search(r"(?<!Bounded)BodyHandlers\\.ofString", source):
            errors.append(f"{path.relative_to(ROOT)} must use the bounded response body handler")

    clickhouse = (ROOT / "infra/init-sql/clickhouse/init.sql").read_text(encoding="utf-8")
    idempotency = (ROOT / "docs/idempotency-contract.md").read_text(encoding="utf-8")
    reporter = (ROOT / "services/alert-web/src/main/java/com/socp/alert/service/CkReporter.java").read_text(encoding="utf-8")
    if "row_version UInt64 DEFAULT 1" not in clickhouse or 'values.put("row_version", 1L)' not in reporter:
        errors.append("ClickHouse alarm detail row_version contract drifted")
    if "uniqExact(tenant_id, alarm_id)" not in idempotency:
        errors.append("ClickHouse logical dedup contract must require uniqExact")

    if errors:
        print("Contract gate failed:", file=sys.stderr)
        for error in errors:
            print(f"  - {error}", file=sys.stderr)
        return 1
    print(
        f"Contract gate passed: {len(modules)} modules, {len(services)} default processes, "
        f"{runtime['targetDeploymentUnits']} target units, {len(route_ids)} gateway routes"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
