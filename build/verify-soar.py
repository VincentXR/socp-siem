#!/usr/bin/env python3
"""Fast SOAR contract gate.

This verifier is intentionally hermetic: it checks the SOAR control-plane
surface, schema/migration inventory and the safety guards that can be proven
without a running Temporal/connector environment.  Set ``SOAR_VERIFY_URL`` to
also probe a deployed service's definition-schema endpoint; a skipped probe is
reported explicitly and is not presented as vendor certification.
"""

from __future__ import annotations

import json
import os
import re
import sys
import urllib.error
import urllib.request
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
PASS: list[str] = []
FAIL: list[str] = []
WARN: list[str] = []


def check(name: str, condition: bool, detail: str = "") -> None:
    (PASS if condition else FAIL).append(name)
    mark = "PASS" if condition else "FAIL"
    suffix = f" -> {detail}" if detail else ""
    print(f"[{mark}] {name}{suffix}")


def warn(name: str, detail: str = "") -> None:
    WARN.append(name)
    suffix = f" -> {detail}" if detail else ""
    print(f"[WARN] {name}{suffix}")


def read(relative: str) -> str:
    return (ROOT / relative).read_text(encoding="utf-8")


def main() -> int:
    print("SOAR contract gate")

    migration_dir = ROOT / "services/soar-web/src/main/resources/db/migration"
    migrations = sorted(migration_dir.glob("V*.sql"), key=lambda path: int(re.match(r"V(\d+)", path.name).group(1)))
    versions = [int(re.match(r"V(\d+)", path.name).group(1)) for path in migrations]
    check("SOAR migrations 6..22 are present", set(range(6, 23)).issubset(versions), str(versions))
    def migration(number: int) -> str:
        matches = tuple(migration_dir.glob(f"V{number}__*.sql"))
        return matches[0].read_text(encoding="utf-8") if len(matches) == 1 else ""

    v11 = migration(11)
    v12 = migration(12)
    check("artifact migration has bounded inline storage", "inline_json" in v11 and "ADD COLUMN IF NOT EXISTS inline_json" in v12)
    v13 = migration(13)
    check("approval migration binds gates by approval key",
          "approval_key" in v13 and "uq_soar_approval_key" in v13, "V13")
    v14 = migration(14)
    check("approval migration persists immutable voter decisions",
          "t_soar_approval_decision" in v14 and "uq_soar_approval_decision_actor" in v14, "V14")
    v15 = migration(15)
    check("signal migration isolates same-type gates",
          "signal_key" in v15 and "uq_soar_signal_outbox_business" in v15, "V15")
    v16 = migration(16)
    check("approval migration persists role/group policy", "policy_json" in v16 and "t_soar_approval" in v16, "V16")
    v17 = migration(17)
    check("attempt migration records remote operation time", "remote_time" in v17 and "t_soar_action_attempt" in v17, "V17")
    v18 = migration(18)
    check("node/attempt migration records connection revision", "connection_revision" in v18 and "t_soar_node_run" in v18, "V18")
    v19 = migration(19)
    check("retention migration adds operational indexes", "idx_soar_run_status_updated" in v19
          and "idx_soar_approval_status_expires" in v19, "V19")
    v20 = migration(20)
    check("row version migration protects inserts", "ALTER TABLE t_soar_run" in v20
          and "SET DEFAULT 0" in v20, "V20")
    v21 = migration(21)
    check("run budget migration persists the shared counter",
          "execution_node_count" in v21 and "t_soar_run" in v21, "V21")
    v22 = migration(22)
    check("tenant foreign-key migration protects the ownership graph",
          "fk_soar_run_version" in v22 and "fk_soar_attempt_node" in v22
          and "fk_soar_artifact_run" in v22, "V22")

    required_java = (
        "services/soar-web/src/main/java/com/socp/soar/web/definition/SoarDefinitionValidator.java",
        "services/soar-web/src/main/java/com/socp/soar/web/definition/SoarExpressionEngine.java",
        "services/soar-web/src/main/java/com/socp/soar/web/service/SoarService.java",
        "services/soar-web/src/main/java/com/socp/soar/web/service/SoarAutomationRuleService.java",
        "services/soar-web/src/main/java/com/socp/soar/web/service/SoarArtifactRetentionWorker.java",
        "services/soar-web/src/main/java/com/socp/soar/web/artifact/SoarArtifactStore.java",
        "services/soar-web/src/main/java/com/socp/soar/web/artifact/S3SoarArtifactStore.java",
        "services/soar-web/src/main/java/com/socp/soar/web/temporal/SoarWorkflowImpl.java",
        "services/soar-web/src/main/java/com/socp/soar/web/connector/SoarConnectorRegistry.java",
    )
    for relative in required_java:
        check(f"implementation file {Path(relative).name}", (ROOT / relative).is_file())

    controller = read("services/soar-web/src/main/java/com/socp/soar/web/api/controller/SoarController.java")
    soar_client = read("platform/socp-client/src/main/java/com/socp/platform/client/service/SoarClient.java")
    for route in ("/runs", "/dry-run", "/definition-schema", "/resolve-unknown", "/artifacts", "@PatchMapping(\"/playbooks/{id}\")"):
        check(f"SOAR route {route}", route in controller)
    check("Alert service client enters event evaluation",
          '"/api/events/evaluate"' in soar_client
          and "normalizeEventEnvelope" in controller)
    live_probe = ROOT / "build/verify-soar-live.py"
    full_stack = read(".github/workflows/full-stack.yml")
    live_source = live_probe.read_text(encoding="utf-8") if live_probe.is_file() else ""
    check("live SOAR integration verifier exists",
          live_probe.is_file() and "SOAR integration" in live_source
          and "temporalWorkflowId" in live_source
          and "SOAR_LIVE_EVIDENCE_PATH" in live_source)
    check("full-stack starts real Temporal and cross-instance SOAR probe",
          "Start Temporal for SOAR evidence" in full_stack
          and "SOAR_REQUIRE_SECONDARY: \"true\"" in full_stack
          and "SOCP_TEMPORAL_TARGET: localhost:7233" in full_stack
          and "SOAR_LIVE_EVIDENCE_PATH: .cache/soar-live.json" in full_stack
          and "verify-soar-live.py" in full_stack)
    request_dir = ROOT / "services/soar-web/src/main/java/com/socp/soar/web/api/request"
    for request_type in ("UpdatePlaybookRequest", "DryRunRequest", "ReasonRequest",
                         "RerunRequest", "UnknownResolutionRequest",
                         "ApprovalDecisionRequest", "PatchConnectionRequest"):
        check(f"typed SOAR request {request_type}", (request_dir / f"{request_type}.java").is_file())
    check("high-risk SOAR handlers use validated request DTOs",
          "@Valid @RequestBody(required = false)\n                                                         UpdatePlaybookRequest" in controller
          and "@Valid @RequestBody UnknownResolutionRequest" in controller
          and "@Valid @RequestBody ApprovalDecisionRequest" in controller
          and "@RequestBody PlaybookExecutionRequest" in controller)
    dynamic_body_count = len(re.findall(r"@RequestBody(?:\([^)]*\))?\s+Map<", controller))
    check("SOAR dynamic request maps stay at JSON extension boundaries",
          dynamic_body_count == 4, f"found={dynamic_body_count}; patch/evaluation routes are explicit extension boundaries")

    workflow = read("services/soar-web/src/main/java/com/socp/soar/web/temporal/SoarWorkflowImpl.java")
    activity = read("services/soar-web/src/main/java/com/socp/soar/web/temporal/SoarActivityImpl.java")
    activity_contract = read("services/soar-web/src/main/java/com/socp/soar/web/temporal/SoarActivity.java")
    recovery = read("services/soar-web/src/main/java/com/socp/soar/web/service/SoarRunRecoveryWorker.java")
    runtime = read("services/soar-web/src/main/java/com/socp/soar/web/config/SoarRuntimeProperties.java")
    application = read("services/soar-web/src/main/resources/application.yml")
    production = read("services/soar-web/src/main/resources/application-prod.yml")
    checks = {
        "bounded graph execution": "maxSteps()" in workflow and "EXECUTION_LIMIT_EXCEEDED" in workflow,
        "published sub-playbook graph gate": "validateSubPlaybookGraph" in read("services/soar-web/src/main/java/com/socp/soar/web/service/SoarService.java")
        and "SOAR_SUB_PLAYBOOK_CYCLE" in read("services/soar-web/src/main/java/com/socp/soar/web/service/SoarService.java")
        and "SOAR_SUB_PLAYBOOK_DEPTH_EXCEEDED" in read("services/soar-web/src/main/java/com/socp/soar/web/service/SoarService.java"),
        "run-wide child workflow budget": "reserveNodeExecution" in activity_contract
        and "execution_node_count" in read("services/soar-web/src/main/java/com/socp/soar/web/persistence/entity/SoarRunEntity.java")
        and "executionBudgetLimit" in workflow,
        "unknown action is durable": "Workflow.await(() -> cancelled || unknownResolution != null)" in workflow,
        "approval gate context is persisted": "markRunWaitingWithContext" in activity_contract
        and "setInputHash" in activity and "setTargetSnapshotJson" in activity
        and "setPolicyJson" in activity and "soar-approval-gate-context" in workflow,
        "approval role/group policy is enforced": "approvalPolicyAllows" in read("services/soar-web/src/main/java/com/socp/soar/web/service/SoarService.java")
        and "APPROVER_POLICY_FORBIDDEN" in read("services/soar-web/src/main/java/com/socp/soar/web/service/SoarService.java"),
        "output hard limit": "MAX_OUTPUT_BYTES = 10 * 1024 * 1024" in activity,
        "external artifact storage boundary": "SoarArtifactStore" in activity
        and "artifactStore.put" in activity
        and "artifactStore.read" in read("services/soar-web/src/main/java/com/socp/soar/web/service/SoarService.java")
        and "ConditionalOnProperty" in read("services/soar-web/src/main/java/com/socp/soar/web/artifact/S3SoarArtifactStore.java"),
        "production artifact backend is fail-closed": ("artifacts:" in production and "backend:" in production)
        and "must be s3 in production" in read("platform/socp-auth/src/main/java/com/socp/platform/auth/security/ProdGuard.java")
        and "large artifact requires the configured object-store adapter" in read(
            "services/soar-web/src/main/java/com/socp/soar/web/service/SoarService.java"),
        "rotatable secret provider boundary": "kubernetes-mount-path" in production
        and "KubernetesSecretResolver" in read(
            "services/soar-web/src/main/java/com/socp/soar/web/connector/KubernetesSecretResolver.java")
        and "VaultSecretResolver" in read(
            "services/soar-web/src/main/java/com/socp/soar/web/connector/VaultSecretResolver.java")
        and "allow-environment-fallback: ${SOCP_SOAR_SECRET_ALLOW_ENV_FALLBACK:false}" in production
        and "access-key-ref: ${SOCP_SOAR_ARTIFACT_S3_ACCESS_KEY_REF:k8s://" in production
        and "secret-key-ref: ${SOCP_SOAR_ARTIFACT_S3_SECRET_KEY_REF:k8s://" in production,
        "secret redaction": "[REDACTED]" in activity,
        "stale outbox recovery": "recoverStaleClaims" in read("services/soar-web/src/main/java/com/socp/soar/web/service/SoarDispatchWorker.java"),
        "cancellation is a one-way activity fence": '"CANCELLING".equals(run.getStatus())' in activity,
        "stale cancellation settles as cancelled": 'run.setStatus("CANCELLED")' in recovery
        and '"CANCELLING".equals(run.getStatus())' in recovery,
        "SOAR runtime switches are bound": "evaluationEnabled" in runtime
        and "controlPlaneEnabled" in runtime and "executionEnabled" in runtime
        and "executionTenantAllowlist" in runtime
        and "control-plane-enabled" in application
        and "evaluation-enabled" in production
        and "execution-enabled" in production,
        "paused workers retain durable backlog": "isExecutionEnabled" in read(
            "services/soar-web/src/main/java/com/socp/soar/web/service/SoarDispatchWorker.java")
        and "isExecutionEnabled" in read(
            "services/soar-web/src/main/java/com/socp/soar/web/service/SoarSignalWorker.java"),
        "typed action parameter schemas are enforced": "validateActionParameters" in read(
            "services/soar-web/src/main/java/com/socp/soar/web/definition/SoarDefinitionValidator.java")
        and "ACTION_PARAMETER_REQUIRED" in read(
            "services/soar-web/src/main/java/com/socp/soar/web/definition/SoarDefinitionValidator.java")
        and "ACTION_PARAMETER_TYPE_INVALID" in read(
            "services/soar-web/src/main/java/com/socp/soar/web/definition/SoarDefinitionValidator.java")
            and "ACTION_PARAMETER_UNKNOWN" in read(
            "services/soar-web/src/main/java/com/socp/soar/web/definition/SoarDefinitionValidator.java"),
        "workflow JSON corruption fails explicitly": "invalid workflow JSON" in workflow
        and 'return "{}"' not in workflow and "SoarWorkflowJsonException" in workflow,
        "SSE scheduler has an application lifecycle": "soarSseScheduler" in read(
            "services/soar-web/src/main/java/com/socp/soar/web/config/SoarSseConfiguration.java")
        and "getIfAvailable" in controller,
        "SSE bounds are configurable": "sse-poll-interval-ms" in application
        and "sse-timeout-ms" in application and "sse-scheduler-threads" in production,
    }
    for name, condition in checks.items():
        check(name, condition)

    frontend = read("frontend/apps/workbench/src/api/soar.ts")
    for fn in ("dryRunVersion", "resolveUnknown", "listArtifacts", "saveVersion"):
        check(f"Workbench API {fn}", f"{fn} =" in frontend)
    editor = read("frontend/apps/workbench/src/components/soar/SoarEditor.vue")
    node_registry = read("frontend/apps/workbench/src/components/soar/editor/nodeRegistry.ts")
    inspector = read("frontend/apps/workbench/src/components/soar/SoarRunInspector.vue")
    for node_type in ("START", "END", "ACTION", "CONDITION", "SWITCH", "PARALLEL", "JOIN",
                      "FOREACH", "DELAY", "APPROVAL", "MANUAL_TASK", "SUB_PLAYBOOK", "SET_VARIABLE"):
        # The editor delegates palette metadata/default schemas to the node
        # registry; checking only SoarEditor.vue would report false negatives
        # whenever a node is deliberately kept out of the shell component.
        check(f"Workbench editor supports {node_type}", node_type in editor or node_type in node_registry)
    check("Workbench graph editor is wired", "saveVersion" in editor and "publishVersion" in editor
          and "dryRunVersion" in editor)
    check("Workbench run inspector is wired", "listNodeAttempts" in inspector
          and "EventSource" in inspector and "resolveUnknown" in inspector)

    # Shipped golden response templates
    template_dir = ROOT / "services/soar-web/src/main/resources/soar/templates"
    golden_templates = (
        "credential-leak.json",
        "false-positive.json",
        "high-risk-ioc.json",
        "malicious-endpoint.json",
        "unknown-remote-result.json",
    )
    for tmpl in golden_templates:
        tmpl_file = template_dir / tmpl
        check(f"golden template {tmpl} exists", tmpl_file.is_file())
        if tmpl_file.is_file():
            data = json.loads(tmpl_file.read_text(encoding="utf-8"))
            definition = data.get("definition", {})
            check(f"golden template {tmpl} schemaVersion", definition.get("schemaVersion") == "soar.playbook")
            check(f"golden template {tmpl} entryNodeId", bool(definition.get("entryNodeId")))
            check(f"golden template {tmpl} has nodes", len(definition.get("nodes", [])) >= 2)

    # Workbench 5-tab workspace per Design §14
    soar_view = read("frontend/apps/workbench/src/views/SoarView.vue")
    for tab_name in ("playbooks", "rules", "runs", "approvals", "connections"):
        check(f"Workbench SoarView covers tab {tab_name}", f'name="{tab_name}"' in soar_view)
    check("Workbench approval modal with audited reason", "submitApprovalDecision" in soar_view and "decisionReason" in soar_view)
    check("Workbench golden template installation", "installTemplate" in soar_view and "installDraft" in soar_view)
    openapi = read("docs/soar-openapi.yaml")
    check("OpenAPI exposes durable run detail", "/api/runs/{runId}:" in openapi
          and "/api/node-runs/{nodeRunId}/attempts:" in openapi)
    check("OpenAPI exposes playbook lifecycle update", "/api/playbooks/{playbookId}:" in openapi
          and "UpdatePlaybook" in openapi)
    check("OpenAPI exposes automation and connections", "/api/automation-rules:" in openapi
          and "/api/connections:" in openapi)
    check("OpenAPI exposes approval policy", "approvalPolicy:" in openapi and "allowedRoles" in openapi
          and "allowedGroups" in openapi)
    check("OpenAPI exposes typed event envelope", "EventEnvelope:" in openapi
          and "soar.event" in openapi and "/api/events/evaluate:" in openapi)
    check("OpenAPI uses the real session cookie and ApiResult schema",
          "name: SOCP_SESSION" in openapi and "schemas/ApiResult" in openapi
          and "required: [code, message, timestamp]" in openapi
          and "VersionResult" in openapi and "'412'" in openapi and "ETag:" in openapi)
    registry = read("services/soar-web/src/main/java/com/socp/soar/web/connector/SoarConnectorRegistry.java")
    descriptor = read("services/soar-web/src/main/java/com/socp/soar/web/connector/ActionDescriptor.java")
    check("connector actions expose typed schemas and permissions",
          "inputProperties" in registry and "requiredPermissions" in descriptor)

    base_url = os.environ.get("SOAR_VERIFY_URL", "").strip().rstrip("/")
    if base_url:
        url = base_url + "/api/definition-schema"
        try:
            with urllib.request.urlopen(url, timeout=5) as response:
                body = json.loads(response.read().decode("utf-8"))
            check("deployed definition schema is JSON", isinstance(body, (dict, list)), url)
        except (OSError, ValueError, urllib.error.URLError) as exc:
            check("deployed definition schema is reachable", False, str(exc))
    else:
        warn("deployed SOAR probe skipped", "set SOAR_VERIFY_URL for an environment check")

    print(f"Summary: {len(PASS)} passed, {len(FAIL)} failed, {len(WARN)} warnings")
    return 1 if FAIL else 0


if __name__ == "__main__":
    sys.exit(main())
