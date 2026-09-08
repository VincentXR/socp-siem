#!/usr/bin/env python3
"""Live SOAR V2 integration and cross-instance admission verification.

The hermetic ``verify-soar.py`` gate proves source-level contracts.  This
probe exercises the deployed HTTP/API, PostgreSQL-backed projections and a
real Temporal worker.  It intentionally creates a short-lived, side-effect
free ``DELAY`` playbook so no endpoint/firewall/EDR connector is invoked.

Environment:
  SOCP_GATEWAY_URL       gateway base URL (default: http://127.0.0.1:18092)
  SOAR_PRIMARY_URL       gateway or service URL; ``/soar-web`` is appended
  SOAR_SECONDARY_URL     optional second SOAR instance for race checks
  SOAR_REQUIRE_SECONDARY  fail when the second instance is not configured
  SOAR_VERIFY_USERNAME/PASSWORD (default: admin/admin123)
  SOAR_VERIFY_TENANT     tenant header (default: default)
  SOAR_LIVE_EVIDENCE_PATH secret-free JSON result path (default: .cache/soar-v2-live.json)
"""

from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor
import datetime
import json
import os
from pathlib import Path
import sys
import time
import urllib.error
import urllib.request
import uuid
from typing import Any

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from auth_client import login_token  # noqa: E402


PASS: list[str] = []
FAIL: list[str] = []
WARN: list[str] = []


def evidence_path() -> Path:
    """Return the CI artifact path without ever including credentials in it."""
    value = os.environ.get("SOAR_LIVE_EVIDENCE_PATH", ".cache/soar-v2-live.json").strip()
    return Path(value or ".cache/soar-v2-live.json")


def write_evidence(primary: str, secondary: str, tenant: str) -> None:
    """Persist a small, secret-free result summary for CI retention."""
    target = evidence_path()
    report = {
        "schemaVersion": "soar.live-evidence/v1",
        "generatedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(),
        "primaryConfigured": bool(primary),
        "secondaryConfigured": bool(secondary),
        "tenant": tenant,
        "passed": PASS,
        "failed": FAIL,
        "warnings": WARN,
        "status": "PASS" if not FAIL else "FAIL",
    }
    try:
        target.parent.mkdir(parents=True, exist_ok=True)
        temporary = target.with_suffix(target.suffix + ".tmp")
        temporary.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        temporary.replace(target)
    except OSError as error:  # pragma: no cover - filesystem failure is deployment-specific
        print(f"[WARN] write live evidence -> {error}")


def check(name: str, condition: bool, detail: Any = "") -> None:
    (PASS if condition else FAIL).append(name)
    mark = "PASS" if condition else "FAIL"
    suffix = f" -> {detail}" if detail else ""
    print(f"[{mark}] {name}{suffix}")


def warn(name: str, detail: Any = "") -> None:
    WARN.append(name)
    suffix = f" -> {detail}" if detail else ""
    print(f"[WARN] {name}{suffix}")


def normalize_base(value: str, append_context: bool = True) -> str:
    base = (value or "").strip().rstrip("/")
    if not base:
        raise ValueError("SOAR URL is empty")
    if append_context and not base.endswith("/soar-web"):
        base += "/soar-web"
    return base


def unwrap(body: Any) -> Any:
    """Unwrap the platform's ``{code,message,timestamp,data}`` envelope."""
    if isinstance(body, dict) and "code" in body and "data" in body:
        return body["data"]
    return body


def request(base: str, path: str, token: str, tenant: str,
            method: str = "GET", body: Any = None, timeout: float = 15) -> tuple[int, Any]:
    payload = None if body is None else json.dumps(body).encode("utf-8")
    headers = {
        "Authorization": "Bearer " + token,
        "X-Tenant-Id": tenant,
        "Accept": "application/json",
    }
    if payload is not None:
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(base.rstrip("/") + path, data=payload,
                                 method=method, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as response:
            return response.status, parse_response(response.read())
    except urllib.error.HTTPError as error:
        return error.code, parse_response(error.read())
    except (OSError, urllib.error.URLError) as error:
        return -1, {"error": str(error)[:300]}


def stream_probe(base: str, token: str, tenant: str, run_id: str,
                 last_event_id: int = 0, timeout: float = 8) -> tuple[bool, Any]:
    """Read one complete ``run-event`` frame from the durable SSE endpoint.

    ``urllib`` exposes the response as a line stream, so the probe only keeps
    bounded protocol state and closes the socket as soon as one event has been
    reconstructed.  No response body or bearer token is written to evidence.
    """
    headers = {
        "Authorization": "Bearer " + token,
        "X-Tenant-Id": tenant,
        "Accept": "text/event-stream",
        "Last-Event-ID": str(max(0, last_event_id)),
        "Cache-Control": "no-cache",
    }
    url = base.rstrip("/") + f"/api/v2/runs/{run_id}/stream"
    req = urllib.request.Request(url, method="GET", headers=headers)
    event_name = False
    event_id = False
    event_data = False
    try:
        with urllib.request.urlopen(req, timeout=timeout) as response:
            if response.status != 200:
                return False, {"status": response.status}
            deadline = time.monotonic() + timeout
            while time.monotonic() < deadline:
                line = response.readline()
                if not line:
                    break
                value = line.decode("utf-8", "replace").strip()
                if value.startswith("event:") and value[len("event:"):].strip() == "run-event":
                    event_name = True
                elif value.startswith("id:") and value[len("id:"):].strip().isdigit():
                    event_id = True
                elif value.startswith("data:") and value[len("data:"):].strip():
                    event_data = True
                if event_name and event_id and event_data:
                    return True, {"status": response.status, "lastEventId": last_event_id}
    except (OSError, TimeoutError, urllib.error.URLError) as error:
        return False, {"error": str(error)[:300]}
    return False, {"status": 200, "event": "incomplete"}


def parse_response(raw: bytes) -> Any:
    if not raw:
        return {}
    try:
        return json.loads(raw.decode("utf-8", "replace"))
    except ValueError:
        return {"raw": raw.decode("utf-8", "replace")[:300]}


def definition(delay_seconds: int = 5) -> dict[str, Any]:
    return {
        "schemaVersion": "soar.playbook/v2",
        "entryNodeId": "start",
        "limits": {"maxNodeExecutions": 20, "maxParallelism": 2},
        "nodes": [
            {"id": "start", "type": "START", "name": "Start"},
            {"id": "delay", "type": "DELAY", "name": "CI hold",
             "config": {"durationSeconds": delay_seconds}},
            {"id": "end", "type": "END", "name": "End", "outcome": "SUCCEEDED"},
        ],
        "edges": [
            {"from": "start", "to": "delay"},
            {"from": "delay", "to": "end"},
        ],
    }


def event(event_id: str, event_type: str, tenant: str) -> dict[str, Any]:
    now = datetime.datetime.now(datetime.timezone.utc).isoformat()
    return {
        "schemaVersion": "soar.event/v1",
        "eventId": event_id,
        "eventType": event_type,
        "tenantId": tenant,
        "occurredAt": now,
        "producer": "soar-live-verifier",
        "subject": {"type": "alert", "id": event_id},
        "data": {"source": "soar-live-verifier", "severity": "LOW"},
        "trace": {"correlationId": event_id, "causationId": event_id,
                  "automationDepth": 0},
    }


def run_status(base: str, token: str, tenant: str, run_id: str) -> tuple[int, dict[str, Any]]:
    status, body = request(base, f"/api/v2/runs/{run_id}", token, tenant)
    value = unwrap(body)
    return status, value if isinstance(value, dict) else {}


def wait_for_health(base: str, token: str, tenant: str,
                    timeout: float = 45) -> dict[str, Any]:
    """Wait for both the service and its Temporal dependency to report UP."""
    deadline = time.monotonic() + timeout
    latest: dict[str, Any] = {}
    while time.monotonic() < deadline:
        status, body = request(base, "/health", token, tenant)
        value = unwrap(body)
        latest = value if isinstance(value, dict) else {}
        temporal = latest.get("temporal")
        if status == 200 and latest.get("status") == "UP" \
                and isinstance(temporal, dict) and temporal.get("status") == "UP":
            return latest
        time.sleep(0.5)
    return latest


def wait_for_run(base: str, token: str, tenant: str, run_id: str,
                 timeout: float = 45) -> dict[str, Any]:
    deadline = time.monotonic() + timeout
    latest: dict[str, Any] = {}
    while time.monotonic() < deadline:
        status, latest = run_status(base, token, tenant, run_id)
        if status == 200 and latest.get("status") in {
                "SUCCEEDED", "FAILED", "CANCELLED", "UNKNOWN"}:
            return latest
        time.sleep(0.5)
    return latest


def evaluate(base: str, token: str, tenant: str, payload: dict[str, Any]) -> tuple[int, dict[str, Any]]:
    status, body = request(base, "/api/v2/automation-rules/evaluate", token, tenant,
                           method="POST", body=payload)
    value = unwrap(body)
    return status, value if isinstance(value, dict) else {}


def accepted_run_ids(result: dict[str, Any]) -> list[str]:
    runs = result.get("runs")
    if not isinstance(runs, list):
        return []
    return [str(item.get("runId")) for item in runs
            if isinstance(item, dict) and item.get("runId")]


def receipt_statuses(result: dict[str, Any]) -> list[dict[str, Any]]:
    receipts = result.get("receipts")
    return [item for item in receipts if isinstance(item, dict)] if isinstance(receipts, list) else []


def main() -> int:
    PASS.clear()
    FAIL.clear()
    WARN.clear()
    gateway = os.environ.get("SOCP_GATEWAY_URL", "http://127.0.0.1:18092").rstrip("/")
    primary_raw = os.environ.get("SOAR_PRIMARY_URL", gateway + "/soar-web")
    primary = normalize_base(primary_raw, append_context=True)
    secondary_raw = os.environ.get("SOAR_SECONDARY_URL", "").strip()
    secondary = normalize_base(secondary_raw, append_context=True) if secondary_raw else ""
    require_secondary = os.environ.get("SOAR_REQUIRE_SECONDARY", "false").lower() == "true"
    tenant = os.environ.get("SOAR_VERIFY_TENANT", "default")
    username = os.environ.get("SOAR_VERIFY_USERNAME", "admin")
    password = os.environ.get("SOAR_VERIFY_PASSWORD", "admin123")

    if require_secondary and not secondary:
        check("secondary SOAR instance is configured", False,
              "set SOAR_SECONDARY_URL when SOAR_REQUIRE_SECONDARY=true")
        write_evidence(primary, secondary, tenant)
        return 1
    if not secondary:
        warn("cross-instance race uses one endpoint", "set SOAR_SECONDARY_URL for two-process evidence")

    try:
        token = login_token(gateway, username=username, password=password, timeout=15)
    except Exception as error:  # pragma: no cover - exercised only by a live deployment
        check("gateway login for SOAR live probe", False, str(error))
        write_evidence(primary, secondary, tenant)
        return 1
    check("gateway login for SOAR live probe", True, username)

    for label, base in (("primary", primary), ("secondary", secondary)):
        if not base:
            continue
        details = wait_for_health(base, token, tenant)
        temporal = details.get("temporal", {}) if isinstance(details, dict) else {}
        check(f"{label} SOAR health is UP", details.get("status") == "UP",
              details.get("status") if isinstance(details, dict) else details)
        check(f"{label} Temporal health is UP", isinstance(temporal, dict)
              and temporal.get("status") == "UP",
              temporal.get("status") if isinstance(temporal, dict) else temporal)

    suffix = uuid.uuid4().hex[:12]
    playbook_id = version_id = rule_id = capacity_rule_id = None
    accepted_ids: list[str] = []
    try:
        status, body = request(primary, "/api/v2/playbooks/import", token, tenant,
                               method="POST", body={
                                   "name": f"CI SOAR V2 live {suffix}",
                                   "description": "automated live integration evidence",
                                   "tags": ["ci", "e2e"],
                                   "definition": definition(),
                                   "layout": {},
                               })
        created = unwrap(body)
        playbook_id = created.get("playbookId") if isinstance(created, dict) else None
        version_id = created.get("id") if isinstance(created, dict) else None
        version_no = int(created.get("version", 1)) if isinstance(created, dict) else 1
        check("create isolated V2 draft", status in (200, 201)
              and bool(playbook_id) and bool(version_id), body)
        if not playbook_id or not version_id:
            raise RuntimeError("draft response did not contain playbookId/id")

        status, body = request(primary,
                               f"/api/v2/playbooks/{playbook_id}/versions/{version_no}/validate",
                               token, tenant, method="POST")
        validation = unwrap(body)
        check("validate isolated V2 draft", status == 200 and validation.get("valid") is True,
              validation)

        status, body = request(primary,
                               f"/api/v2/playbooks/{playbook_id}/versions/{version_no}/publish",
                               token, tenant, method="POST")
        published = unwrap(body)
        check("publish isolated V2 version", status == 200 and published.get("status") == "PUBLISHED",
              published)

        action = [{"playbookVersionId": version_id}]
        for label, trigger_type, suppression in (
                ("alert", "alert.created", {}),
                ("capacity", "soar.ci.capacity", {
                    "maxConcurrentRuns": 1, "conflictStrategy": "SUPPRESS"
                })):
            status, body = request(primary, "/api/v2/automation-rules", token, tenant,
                                   method="POST", body={
                                       "name": f"CI SOAR V2 {label} {suffix}",
                                       "triggerType": trigger_type,
                                       "priority": 1 if label == "alert" else 2,
                                       "enabled": True,
                                       "conditions": {},
                                       "actions": action,
                                       "suppression": suppression,
                                   })
            created_rule = unwrap(body)
            check(f"create {label} automation rule", status in (200, 201)
                  and bool(created_rule.get("id")), body)
            if label == "alert":
                rule_id = created_rule.get("id")
            else:
                capacity_rule_id = created_rule.get("id")
        if not rule_id or not capacity_rule_id:
            raise RuntimeError("automation rule creation failed")

        # This is a user-authenticated V2 event evaluation.  The full-stack
        # verifier separately proves Alert Web's service-signed event route.
        alert_event_id = f"soar-live-alert-{suffix}"
        status, result = evaluate(primary, token, tenant, event(alert_event_id, "alert.created", tenant))
        alert_runs = accepted_run_ids(result)
        check("alert.created reaches a durable V2 Run", status == 200 and len(alert_runs) == 1,
              {"status": status, "matchedRuns": result.get("matchedRuns")})
        if alert_runs:
            completed = wait_for_run(primary, token, tenant, alert_runs[0])
            check("alert-created V2 Run has a Temporal workflow identity",
                  bool(completed.get("temporalWorkflowId")),
                  completed.get("temporalWorkflowId"))
            check("alert-created V2 Run completes through Temporal",
                  completed.get("status") == "SUCCEEDED", completed)
            sse_ok, sse_detail = stream_probe(primary, token, tenant, alert_runs[0])
            check("run-event SSE resumes from Last-Event-ID", sse_ok, sse_detail)

        # Same event id concurrently at two service processes must yield one
        # durable receipt/run.  The losing request should return the existing
        # receipt, not a unique-key transaction error.
        duplicate_id = f"soar-live-duplicate-{suffix}"
        endpoints = [primary, secondary or primary]
        with ThreadPoolExecutor(max_workers=2) as pool:
            futures = [pool.submit(evaluate, endpoint, token, tenant,
                                    event(duplicate_id, "soar.ci.capacity", tenant))
                       for endpoint in endpoints]
            duplicate_results = [future.result() for future in futures]
        duplicate_runs = [run_id for _, result in duplicate_results for run_id in accepted_run_ids(result)]
        duplicate_receipts = [receipt for _, result in duplicate_results for receipt in receipt_statuses(result)]
        check("same-event cross-instance evaluation succeeds", all(status == 200 for status, _ in duplicate_results),
              [status for status, _ in duplicate_results])
        check("same-event evaluation creates exactly one Run", len(set(duplicate_runs)) == 1,
              duplicate_runs)
        check("same-event evaluation returns durable receipts", len(duplicate_receipts) == 2,
              duplicate_receipts)
        if duplicate_runs:
            accepted_ids.append(duplicate_runs[0])
            duplicate_completed = wait_for_run(primary, token, tenant, duplicate_runs[0])
            check("same-event admitted Run has a Temporal workflow identity",
                  bool(duplicate_completed.get("temporalWorkflowId")),
                  duplicate_completed.get("temporalWorkflowId"))
            check("same-event admitted Run completes before capacity test",
                  duplicate_completed.get("status") == "SUCCEEDED", duplicate_completed)

        # Distinct events exercise the capacity fence.  DELAY keeps the first
        # run active while the second transaction acquires the rule lock.
        capacity_ids = [f"soar-live-capacity-{suffix}-a", f"soar-live-capacity-{suffix}-b"]
        with ThreadPoolExecutor(max_workers=2) as pool:
            futures = [pool.submit(evaluate, endpoint, token, tenant,
                                    event(event_id, "soar.ci.capacity", tenant))
                       for endpoint, event_id in zip(endpoints, capacity_ids)]
            capacity_results = [future.result() for future in futures]
        capacity_receipts = [receipt for _, result in capacity_results for receipt in receipt_statuses(result)]
        capacity_statuses = [str(receipt.get("status")) for receipt in capacity_receipts]
        accepted_capacity = [receipt for receipt in capacity_receipts if receipt.get("status") == "ACCEPTED"]
        suppressed_capacity = [receipt for receipt in capacity_receipts
                               if receipt.get("status") == "SUPPRESSED"
                               and receipt.get("reason") == "CAPACITY"]
        check("distinct cross-instance evaluations return successfully",
              all(status == 200 for status, _ in capacity_results),
              [status for status, _ in capacity_results])
        check("maxConcurrentRuns admits one whole action set", len(accepted_capacity) == 1
              and len(suppressed_capacity) == 1, capacity_statuses)
        accepted_ids.extend(str(item.get("runId")) for item in accepted_capacity if item.get("runId"))
        for run_id in list(dict.fromkeys(accepted_ids)):
            completed = wait_for_run(primary, token, tenant, run_id)
            check(f"admitted Run {run_id[:8]} has a Temporal workflow identity",
                  bool(completed.get("temporalWorkflowId")),
                  completed.get("temporalWorkflowId"))
            check(f"admitted Run {run_id[:8]} completes through Temporal",
                  completed.get("status") == "SUCCEEDED", completed)
    except Exception as error:
        check("SOAR live probe completed", False, str(error))
    finally:
        # Rules are tombstoned by DELETE and the playbook is archived; this
        # keeps receipt/run evidence while preventing future event matches.
        for rule in (capacity_rule_id, rule_id):
            if rule:
                status, body = request(primary, f"/api/v2/automation-rules/{rule}", token, tenant,
                                       method="DELETE")
                if status not in (200, 204):
                    warn("cleanup automation rule", {"status": status, "body": body})
        if playbook_id:
            status, body = request(primary, f"/api/v2/playbooks/{playbook_id}", token, tenant,
                                   method="PATCH", body={"status": "ARCHIVED"})
            if status != 200:
                warn("archive live verifier playbook", {"status": status, "body": body})

    print(f"Summary: {len(PASS)} passed, {len(FAIL)} failed, {len(WARN)} warnings")
    write_evidence(primary, secondary, tenant)
    return 1 if FAIL else 0


if __name__ == "__main__":
    raise SystemExit(main())
