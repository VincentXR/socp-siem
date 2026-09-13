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
    legacy_route_ids = {"asset-collect", "hips-collect", "detect-model"}
    unexpected_routes = route_ids - downstream - auxiliary_route_ids
    if unexpected_routes != legacy_route_ids:
        errors.append(f"unexpected non-default routes: {sorted(unexpected_routes)}")
    if "detect-web-runtime" not in route_ids:
        errors.append("gateway runtime route missing: detect-web-runtime")
    for legacy, owner in (("asset-collect", "asset-web"), ("hips-collect", "hips-web")):
        expected = f"RewritePath=/{legacy}/?(?<segment>.*), /{owner}/$\\{{segment}}"
        if expected not in gateway:
            errors.append(f"gateway compatibility rewrite missing: {legacy} -> {owner}")
    model_rewrite = "RewritePath=/detect-model/?(?<segment>.*), /detect-web/model/$\\{segment}"
    if model_rewrite not in gateway or "uri: ${SOCP_GAS_MODEL_URI:${SOCP_GAS_WORKER_URI:" not in gateway:
        errors.append("gateway compatibility rewrite missing: detect-model -> detect-web worker")

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

    role_bootstrap = (ROOT / "infra/init-sql/pg/00_roles.sh").read_text(encoding="utf-8")
    role_grants = (ROOT / "infra/init-sql/pg/02_runtime_grants.sh").read_text(encoding="utf-8")
    if "NOSUPERUSER" not in role_bootstrap or "NOBYPASSRLS" not in role_bootstrap:
        errors.append("PostgreSQL bootstrap must create both runtime and migration roles without RLS bypass")
    if "REVOKE CREATE ON SCHEMA public FROM PUBLIC" not in role_grants:
        errors.append("PostgreSQL runtime grant contract must revoke public schema CREATE")
    # The grants script embeds SQL in a quoted heredoc. A shell-style comment
    # there is sent to psql verbatim and aborts initialization, so keep the
    # embedded SQL comments portable and executable.
    embedded_sql = role_grants.split("<<'SQL'", 1)[-1].split("\nSQL", 1)[0]
    if re.search(r"^\s*#", embedded_sql, re.MULTILINE):
        errors.append("PostgreSQL grants heredoc contains a shell comment instead of SQL comment")

    api_result = (ROOT / "platform/socp-error/src/main/java/com/socp/platform/error/api/ApiResult.java").read_text(encoding="utf-8")
    page_response = (ROOT / "platform/socp-error/src/main/java/com/socp/platform/error/api/PageResponse.java").read_text(encoding="utf-8")
    if "static <T> ApiResult<T> ok(T data)" not in api_result or "static <T> ApiResult<T> of(" not in api_result:
        errors.append("ApiResult success and business-error factories drifted")
    if not all(field in page_response for field in ("items", "total", "page", "size", "totalPages")):
        errors.append("PageResponse must expose the canonical items/total/page/size/totalPages fields")

    alarm_controller = (ROOT / "services/alert-web/src/main/java/com/socp/alert/api/controller/AlarmController.java").read_text(encoding="utf-8")
    export_role = re.search(r'@RequireRole\(\{"admin", "analyst"\}\)\s*@GetMapping\("/export"\)',
                            alarm_controller)
    if ("EXPORT_BATCH_SIZE" not in alarm_controller or "EXPORT_DEFAULT_LIMIT" not in alarm_controller
            or "EXPORT_MAX_LIMIT" not in alarm_controller or "service.count(" not in alarm_controller
            or "service.page(" not in alarm_controller or export_role is None):
        errors.append("alarm export must use a hard row limit and bounded database-side batches")
    if "PageResponse.of" not in alarm_controller:
        errors.append("alarm paged list must use the shared PageResponse contract")

    incident_controller = (
        ROOT / "services/incident-web/src/main/java/com/socp/incident/web/api/controller/CaseController.java"
    ).read_text(encoding="utf-8")
    incident_export_role = re.search(
        r'@RequireRole\(\{"admin", "analyst"\}\)\s*@GetMapping\("/incidents/export"\)',
        incident_controller,
    )
    if ("EXPORT_BATCH_SIZE" not in incident_controller
            or "EXPORT_DEFAULT_LIMIT" not in incident_controller
            or "EXPORT_MAX_LIMIT" not in incident_controller
            or "service.count()" not in incident_controller
            or "service.page(" not in incident_controller
            or "HttpServletResponse" not in incident_controller
            or incident_export_role is None):
        errors.append("incident export must be role-protected, bounded, and streamed in database-side pages")
    if "PageResponse.of" not in incident_controller:
        errors.append("incident paged list must use the shared PageResponse contract")

    ueba_controller = (
        ROOT / "services/detect-web/src/main/java/com/socp/detect/web/api/controller/UebaController.java"
    ).read_text(encoding="utf-8")
    risk_store = (
        ROOT / "services/detect-web/src/main/java/com/socp/detect/web/service/EntityRiskStore.java"
    ).read_text(encoding="utf-8")
    if "MAX_ENTITY_LIMIT" not in ueba_controller or "MAX_TOP_CANDIDATES" not in risk_store:
        errors.append("UEBA risk ranking must enforce a bounded tenant candidate set")

    rule_controller = (
        ROOT / "services/detect-web/src/main/java/com/socp/detect/web/api/controller/RuleController.java"
    ).read_text(encoding="utf-8")
    rule_store = (
        ROOT / "services/detect-web/src/main/java/com/socp/detect/web/persistence/store/RuleSpecStore.java"
    ).read_text(encoding="utf-8")
    if "PageResponse.of" not in rule_controller or "public Page<Map<String, Object>> page" not in rule_store:
        errors.append("detection rule catalogue must expose a bounded PageResponse read")

    search_controller = (
        ROOT / "services/search-config/src/main/java/com/socp/search/config/api/controller/SearchController.java"
    ).read_text(encoding="utf-8")
    if ("EXPORT_LIMIT = 5_000" not in search_controller
            or "bounded(limit, EXPORT_LIMIT, EXPORT_LIMIT)" not in search_controller
            or not re.search(r'@RequireRole\(\{"admin", "analyst"\}\)\s*@GetMapping\("/export"\)',
                             search_controller)):
        errors.append("search export must be role-protected and hard-limited")

    report_controller = (
        ROOT / "services/report-web/src/main/java/com/socp/report/web/api/controller/ReportController.java"
    ).read_text(encoding="utf-8")
    report_store = (
        ROOT / "services/report-web/src/main/java/com/socp/report/web/persistence/store/ReportObjectStore.java"
    ).read_text(encoding="utf-8")
    if ("ARCHIVE_MAX_LIMIT" not in report_controller
            or "objectStore.list(ownedPrefix, limit)" not in report_controller
            or "boundedLimit" not in report_store):
        errors.append("report archive listing must enforce a bounded object limit")

    source_controller = (
        ROOT / "services/search-config/src/main/java/com/socp/search/config/api/controller/LogSourceController.java"
    ).read_text(encoding="utf-8")
    source_store = (
        ROOT / "services/search-config/src/main/java/com/socp/search/config/persistence/store/LogSourceStore.java"
    ).read_text(encoding="utf-8")
    source_repository = (
        ROOT / "services/search-config/src/main/java/com/socp/search/config/persistence/repository/LogSourceRepository.java"
    ).read_text(encoding="utf-8")
    source_frontend = (
        ROOT / "frontend/apps/workbench/src/api/search.ts"
    ).read_text(encoding="utf-8")
    if ("PageResponse.of" not in source_controller
            or "Page<LogSource> page" not in source_store
            or "Page<LogSourceEntity> findByTenantId" not in source_repository
            or "page: 1, size: 500" not in source_frontend):
        errors.append("log source catalogue must use a bounded database page end-to-end")

    # Asset and endpoint inventories are tenant-controlled cardinalities. Keep
    # their existing one-based API contract, but make the database page and
    # owning-service role guard part of the executable boundary checks so a
    # future compatibility edit cannot silently reintroduce full-table reads.
    asset_controller = (
        ROOT / "services/asset-web/src/main/java/com/socp/asset/web/api/controller/AssetController.java"
    ).read_text(encoding="utf-8")
    asset_store = (
        ROOT / "services/asset-web/src/main/java/com/socp/asset/web/persistence/store/AssetStore.java"
    ).read_text(encoding="utf-8")
    endpoint_controller = (
        ROOT / "services/hips-web/src/main/java/com/socp/hips/web/api/controller/EndpointController.java"
    ).read_text(encoding="utf-8")
    endpoint_store = (
        ROOT / "services/hips-web/src/main/java/com/socp/hips/web/persistence/store/EndpointStore.java"
    ).read_text(encoding="utf-8")
    if (not re.search(
                r'@RequireRole\(\{"admin", "analyst"\}\)\s*@GetMapping(?:\([^)]*\))?\s*'
                r'public\s+ApiResult<PageResponse<Asset>>\s+list', asset_controller)
            or "Page<Asset> page" not in asset_store
            or "searchByTenantId" not in asset_store):
        errors.append("asset inventory must use a role-protected bounded database page")
    if (not re.search(
                r'@RequireRole\(\{"admin", "analyst"\}\)\s*@GetMapping(?:\([^)]*\))?\s*'
                r'public\s+ApiResult<PageResponse<Endpoint>>\s+list', endpoint_controller)
            or "Page<Endpoint> page" not in endpoint_store
            or "searchByTenantId" not in endpoint_store):
        errors.append("endpoint inventory must use a role-protected bounded database page")

    frontend_response = (ROOT / "frontend/apps/workbench/src/lib/api-response.ts").read_text(encoding="utf-8")
    frontend_tests = (ROOT / "frontend/apps/workbench/scripts/api-response.test.ts").read_text(encoding="utf-8")
    if "class ApiBusinessError" not in frontend_response or "SUCCESS_CODES" not in frontend_response:
        errors.append("frontend must surface non-zero business codes from HTTP 200 envelopes")
    if "failed API envelopes" not in frontend_tests or "serialized without a data key" not in frontend_tests:
        errors.append("frontend ApiResult error handling must have HTTP-200 envelope coverage")

    gateway_config = (ROOT / "services/api-gateway/src/main/resources/application.yml").read_text(encoding="utf-8")
    revocation_store = (ROOT / "services/api-gateway/src/main/java/com/socp/gateway/security/RedisTokenRevocationStore.java").read_text(encoding="utf-8")
    revocation_filter = (ROOT / "services/api-gateway/src/main/java/com/socp/gateway/filter/RevokedTokenWebFilter.java").read_text(encoding="utf-8")
    auth_controller = (ROOT / "services/api-gateway/src/main/java/com/socp/gateway/api/controller/AuthController.java").read_text(encoding="utf-8")
    if "backend: ${SOCP_AUTH_REVOCATION_BACKEND:redis}" not in gateway_config:
        errors.append("gateway production revocation backend must default to shared Redis")
    if "ConditionalOnProperty(name = \"socp.auth.revocation.backend\", havingValue = \"redis\", matchIfMissing = true)" not in revocation_store:
        errors.append("Redis token revocation store must be the default gateway backend")
    if "revocations.isRevoked" not in revocation_filter or "revocations.revoke" not in auth_controller:
        errors.append("gateway logout and request filtering must use the distributed revocation store")
    if not re.search(r"management:\s+endpoints:\s+web:\s+.*?include: health\s*$", gateway_config, re.MULTILINE | re.DOTALL):
        errors.append("gateway public actuator exposure must be limited to health")
    auth_config = (
        ROOT / "platform/socp-auth/src/main/java/com/socp/platform/auth/config/SocpAuthConfig.java"
    ).read_text(encoding="utf-8")
    gateway_session_filter = (
        ROOT / "services/api-gateway/src/main/java/com/socp/gateway/filter/AuthSessionWebFilter.java"
    ).read_text(encoding="utf-8")
    if "/actuator/info/**" in auth_config:
        errors.append("servlet actuator info must not bypass authentication")
    if "requiresActuatorAuthentication" not in gateway_session_filter:
        errors.append("gateway actuator paths must have an explicit authentication guard")

    ci = (ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8")
    full_stack = (ROOT / ".github/workflows/full-stack.yml").read_text(encoding="utf-8")
    for marker in ("SOCP_TESTCONTAINERS", "verify-prod-boot.py", "duplicate_delivery", "detection_outbox_replay",
                   "RedisTokenRevocationStoreContainerTest", "ProductionDatabaseRoleGuardPostgresTest",
                   "TenantRlsPostgresTest"):
        if marker not in ci:
            errors.append(f"CI production verification contract missing: {marker}")
    if not (ROOT / "build/verify-actuator-auth.py").is_file():
        errors.append("missing deployment-backed Actuator authentication verifier")
    if "verify-actuator-auth.py" not in full_stack:
        errors.append("full-stack production evidence contract missing: verify-actuator-auth.py")
    if not (ROOT / "build/verify-runtime-consolidation.py").is_file():
        errors.append("missing evidence-gated runtime consolidation verifier")
    if "verify-runtime-consolidation.py" not in ci:
        errors.append("CI deployment contract missing: verify-runtime-consolidation.py")
    for marker in ("chaos-pipeline.py --scenario all", "--scenario multi_instance", "verify-soar-live.py"):
        if marker not in full_stack:
            errors.append(f"full-stack production evidence contract missing: {marker}")

    flyway_role_profiles = (
        "services/ai-assistant/src/main/resources/application-pg.yml",
        "services/asset-web/src/main/resources/application-pg.yml",
        "services/attack-web/src/main/resources/application-pg.yml",
        "services/detect-web/src/main/resources/application-pg.yml",
        "services/hips-web/src/main/resources/application-pg.yml",
        "services/notify-web/src/main/resources/application-pg.yml",
        "services/search-config/src/main/resources/application-pg.yml",
        "services/alert-web/src/main/resources/application-pg.yml",
        "services/incident-web/src/main/resources/application-pg.yml",
        "services/soc-base/src/main/resources/application-pg.yml",
        "services/threat-web/src/main/resources/application-pg.yml",
        "services/soar-web/src/main/resources/application-pg.yml",
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

    observability = (ROOT / "docs/observability-stage-metrics.md").read_text(encoding="utf-8")
    ingestion_publisher = (
        ROOT / "services/search-config/src/main/java/com/socp/search/config/infrastructure/kafka/"
        "IngestionOutboxPublisher.java"
    ).read_text(encoding="utf-8")
    for metric in (
        "socp.ingestion.outbox.pending.count",
        "socp.ingestion.outbox.oldest.pending.age.seconds",
        "socp.ingestion.outbox.dead.count",
        "socp.ingestion.outbox.oldest.dead.age.seconds",
    ):
        if metric not in observability or metric not in ingestion_publisher:
            errors.append(f"ingestion observability metric drifted: {metric}")
    if "socp.ingestion.outbox.queue_age" in observability:
        errors.append("observability documentation still advertises the removed queue_age metric")

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
        f"no fixed process target, {runtime['logicalDomainCount']} logical domains, "
        f"{len(runtime['consolidationCandidates'])} consolidation candidates, "
        f"{len(route_ids)} gateway routes"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
