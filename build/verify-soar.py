#!/usr/bin/env python3
"""Fast SOAR 2.0 contract gate.

This verifier is intentionally hermetic: it checks the versioned control-plane
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
    print("SOAR 2.0 contract gate")

    design = read("docs/soar-2.0-design.md")
    check("design document exists", len(design) > 20_000)
    for heading in ("20.", "P0", "Temporal", "Connector", "17.", "23."):
        check(f"design covers {heading}", heading in design)

    migration_dir = ROOT / "services/soar-web/src/main/resources/db/migration"
    migrations = sorted(migration_dir.glob("V*.sql"), key=lambda path: int(re.match(r"V(\d+)", path.name).group(1)))
    versions = [int(re.match(r"V(\d+)", path.name).group(1)) for path in migrations]
    check("SOAR migrations V6..V22 are present", set(range(6, 23)).issubset(versions), str(versions))
    v11 = read("services/soar-web/src/main/resources/db/migration/V11__soar_v2_artifacts_retention.sql")
    v12 = read("services/soar-web/src/main/resources/db/migration/V12__soar_v2_artifact_inline_storage.sql")
    check("artifact migration has bounded inline storage", "inline_json" in v11 and "ADD COLUMN IF NOT EXISTS inline_json" in v12)
    v13 = read("services/soar-web/src/main/resources/db/migration/V13__soar_v2_approval_keys.sql")
    check("approval migration binds gates by approval key",
          "approval_key" in v13 and "uq_soar_approval_key" in v13, "V13")
    v14 = read("services/soar-web/src/main/resources/db/migration/V14__soar_v2_approval_decisions.sql")
    check("approval migration persists immutable voter decisions",
          "t_soar_approval_decision" in v14 and "uq_soar_approval_decision_actor" in v14, "V14")
    v15 = read("services/soar-web/src/main/resources/db/migration/V15__soar_v2_signal_keys.sql")
    check("signal migration isolates same-type gates",
          "signal_key" in v15 and "uq_soar_signal_outbox_business" in v15, "V15")
    v16 = read("services/soar-web/src/main/resources/db/migration/V16__soar_v2_approval_policy.sql")
    check("approval migration persists role/group policy", "policy_json" in v16 and "t_soar_approval" in v16, "V16")
    v17 = read("services/soar-web/src/main/resources/db/migration/V17__soar_v2_attempt_remote_time.sql")
    check("attempt migration records remote operation time", "remote_time" in v17 and "t_soar_action_attempt" in v17, "V17")
    v18 = read("services/soar-web/src/main/resources/db/migration/V18__soar_v2_connection_revision.sql")
    check("node/attempt migration records connection revision", "connection_revision" in v18 and "t_soar_node_run" in v18, "V18")
    v19 = read("services/soar-web/src/main/resources/db/migration/V19__soar_v2_retention_indexes.sql")
    check("retention migration adds operational indexes", "idx_soar_run_status_updated" in v19
          and "idx_soar_approval_status_expires" in v19, "V19")
    v20 = read("services/soar-web/src/main/resources/db/migration/V20__soar_v2_row_version_defaults.sql")
    check("row version migration protects inserts", "ALTER TABLE t_soar_run" in v20
          and "SET DEFAULT 0" in v20, "V20")
    v21 = read("services/soar-web/src/main/resources/db/migration/V21__soar_v2_run_execution_budget.sql")
    check("run budget migration persists the shared counter",
          "execution_node_count" in v21 and "t_soar_run" in v21, "V21")
    v22 = read("services/soar-web/src/main/resources/db/migration/V22__soar_v2_tenant_foreign_keys.sql")
    check("tenant foreign-key migration protects the V2 ownership graph",
          "fk_soar_run_version" in v22 and "fk_soar_attempt_node" in v22
          and "fk_soar_artifact_run" in v22, "V22")

    required_java = (
        "services/soar-web/src/main/java/com/socp/soar/web/definition/SoarDefinitionValidator.java",
        "services/soar-web/src/main/java/com/socp/soar/web/definition/SoarExpressionEngine.java",
        "services/soar-web/src/main/java/com/socp/soar/web/service/SoarV2Service.java",
        "services/soar-web/src/main/java/com/socp/soar/web/service/SoarV2AutomationRuleService.java",
        "services/soar-web/src/main/java/com/socp/soar/web/service/SoarArtifactRetentionWorker.java",
        "services/soar-web/src/main/java/com/socp/soar/web/temporal/v2/SoarV2WorkflowImpl.java",
        "services/soar-web/src/main/java/com/socp/soar/web/connector/SoarConnectorRegistry.java",
    )
    for relative in required_java:
        check(f"implementation file {Path(relative).name}", (ROOT / relative).is_file())

    controller = read("services/soar-web/src/main/java/com/socp/soar/web/api/controller/SoarV2Controller.java")
    soar_client = read("platform/socp-client/src/main/java/com/socp/platform/client/service/SoarClient.java")
    for route in ("/runs", "/dry-run", "/definition-schema", "/resolve-unknown", "/artifacts", "@PatchMapping(\"/playbooks/{id}\")"):
        check(f"V2 route {route}", route in controller)
    check("Alert service client enters V2 event evaluation",
          '"/api/v2/events/evaluate"' in soar_client
          and "legacyAlarmEnvelope" in controller)

    workflow = read("services/soar-web/src/main/java/com/socp/soar/web/temporal/v2/SoarV2WorkflowImpl.java")
    activity = read("services/soar-web/src/main/java/com/socp/soar/web/temporal/v2/SoarV2ActivityImpl.java")
    activity_contract = read("services/soar-web/src/main/java/com/socp/soar/web/temporal/v2/SoarV2Activity.java")
    recovery = read("services/soar-web/src/main/java/com/socp/soar/web/service/SoarV2RunRecoveryWorker.java")
    runtime = read("services/soar-web/src/main/java/com/socp/soar/web/config/SoarRuntimeProperties.java")
    application = read("services/soar-web/src/main/resources/application.yml")
    production = read("services/soar-web/src/main/resources/application-prod.yml")
    checks = {
        "bounded graph execution": "maxSteps()" in workflow and "EXECUTION_LIMIT_EXCEEDED" in workflow,
        "published sub-playbook graph gate": "validateSubPlaybookGraph" in read("services/soar-web/src/main/java/com/socp/soar/web/service/SoarV2Service.java")
        and "SOAR_SUB_PLAYBOOK_CYCLE" in read("services/soar-web/src/main/java/com/socp/soar/web/service/SoarV2Service.java")
        and "SOAR_SUB_PLAYBOOK_DEPTH_EXCEEDED" in read("services/soar-web/src/main/java/com/socp/soar/web/service/SoarV2Service.java"),
        "run-wide child workflow budget": "reserveNodeExecution" in activity_contract
        and "execution_node_count" in read("services/soar-web/src/main/java/com/socp/soar/web/persistence/entity/SoarRunEntity.java")
        and "executionBudgetLimit" in workflow,
        "unknown action is durable": "Workflow.await(() -> cancelled || unknownResolution != null)" in workflow,
        "approval gate context is persisted": "markRunWaitingWithPolicyV2" in activity_contract
        and "setInputHash" in activity and "setTargetSnapshotJson" in activity
        and "setPolicyJson" in activity and "soar-approval-gate-context" in workflow,
        "approval role/group policy is enforced": "approvalPolicyAllows" in read("services/soar-web/src/main/java/com/socp/soar/web/service/SoarV2Service.java")
        and "APPROVER_POLICY_FORBIDDEN" in read("services/soar-web/src/main/java/com/socp/soar/web/service/SoarV2Service.java"),
        "output hard limit": "MAX_OUTPUT_BYTES = 10 * 1024 * 1024" in activity,
        "secret redaction": "[REDACTED]" in activity,
        "stale outbox recovery": "recoverStaleClaims" in read("services/soar-web/src/main/java/com/socp/soar/web/service/SoarV2DispatchWorker.java"),
        "cancellation is a one-way activity fence": '"CANCELLING".equals(run.getStatus())' in activity,
        "stale cancellation settles as cancelled": 'run.setStatus("CANCELLED")' in recovery
        and '"CANCELLING".equals(run.getStatus())' in recovery,
        "rollout switches are bound": "v2ControlPlaneEnabled" in runtime
        and "v2ExecutionEnabled" in runtime and "executionTenantAllowlist" in runtime
        and "legacyExecutionEnabled" in runtime
        and "v2-control-plane-enabled" in application
        and "legacy-execution-enabled" in production,
        "legacy execution cannot bypass the migration gate": "requireLegacyExecution" in read(
            "services/soar-web/src/main/java/com/socp/soar/web/api/controller/PlaybookController.java")
        and "isLegacyExecutionEnabled" in read(
            "services/soar-web/src/main/java/com/socp/soar/web/service/AlarmEvaluationService.java"),
        "paused V2 workers retain durable backlog": "isV2ExecutionEnabled" in read(
            "services/soar-web/src/main/java/com/socp/soar/web/service/SoarV2DispatchWorker.java")
        and "isV2ExecutionEnabled" in read(
            "services/soar-web/src/main/java/com/socp/soar/web/service/SoarV2SignalWorker.java"),
        "typed action parameter schemas are enforced": "validateActionParameters" in read(
            "services/soar-web/src/main/java/com/socp/soar/web/definition/SoarDefinitionValidator.java")
        and "ACTION_PARAMETER_REQUIRED" in read(
            "services/soar-web/src/main/java/com/socp/soar/web/definition/SoarDefinitionValidator.java")
        and "ACTION_PARAMETER_TYPE_INVALID" in read(
            "services/soar-web/src/main/java/com/socp/soar/web/definition/SoarDefinitionValidator.java")
        and "ACTION_PARAMETER_UNKNOWN" in read(
            "services/soar-web/src/main/java/com/socp/soar/web/definition/SoarDefinitionValidator.java"),
    }
    for name, condition in checks.items():
        check(name, condition)

    frontend = read("frontend/apps/workbench/src/api/soar.ts")
    for fn in ("dryRunV2Version", "resolveV2Unknown", "listV2Artifacts", "saveV2Version"):
        check(f"Workbench API {fn}", f"{fn} =" in frontend)
    editor = read("frontend/apps/workbench/src/components/soar/SoarV2Editor.vue")
    node_registry = read("frontend/apps/workbench/src/components/soar/editor/nodeRegistry.ts")
    inspector = read("frontend/apps/workbench/src/components/soar/SoarV2RunInspector.vue")
    for node_type in ("START", "END", "ACTION", "CONDITION", "SWITCH", "PARALLEL", "JOIN",
                      "FOREACH", "DELAY", "APPROVAL", "MANUAL_TASK", "SUB_PLAYBOOK", "SET_VARIABLE"):
        # The editor delegates palette metadata/default schemas to the node
        # registry; checking only SoarV2Editor.vue would report false negatives
        # whenever a node is deliberately kept out of the shell component.
        check(f"Workbench editor supports {node_type}", node_type in editor or node_type in node_registry)
    check("Workbench graph editor is wired", "saveV2Version" in editor and "publishV2Version" in editor
          and "dryRunV2Version" in editor)
    check("Workbench run inspector is wired", "listV2NodeAttempts" in inspector
          and "EventSource" in inspector and "resolveV2Unknown" in inspector)

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
            check(f"golden template {tmpl} schemaVersion", definition.get("schemaVersion") == "soar.playbook/v2")
            check(f"golden template {tmpl} entryNodeId", bool(definition.get("entryNodeId")))
            check(f"golden template {tmpl} has nodes", len(definition.get("nodes", [])) >= 2)

    # Workbench 5-tab workspace per Design §14
    soar_view = read("frontend/apps/workbench/src/views/SoarView.vue")
    for tab_name in ("playbooks", "rules", "runs", "approvals", "connections"):
        check(f"Workbench SoarView covers tab {tab_name}", f'name="{tab_name}"' in soar_view)
    check("Workbench approval modal with audited reason", "submitApprovalDecision" in soar_view and "decisionReason" in soar_view)
    check("Workbench golden template installation", "installTemplate" in soar_view and "installDraft" in soar_view)
    openapi = read("docs/soar-2.0-openapi.yaml")
    check("OpenAPI exposes durable run detail", "/api/v2/runs/{runId}:" in openapi
          and "/api/v2/node-runs/{nodeRunId}/attempts:" in openapi)
    check("OpenAPI exposes playbook lifecycle update", "/api/v2/playbooks/{playbookId}:" in openapi
          and "UpdatePlaybook" in openapi)
    check("OpenAPI exposes automation and connections", "/api/v2/automation-rules:" in openapi
          and "/api/v2/connections:" in openapi)
    check("OpenAPI exposes approval policy", "approvalPolicy:" in openapi and "allowedRoles" in openapi
          and "allowedGroups" in openapi)
    check("OpenAPI exposes typed event envelope", "EventEnvelope:" in openapi
          and "soar.event/v1" in openapi and "/api/v2/events/evaluate:" in openapi)
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
        url = base_url + "/api/v2/definition-schema"
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
