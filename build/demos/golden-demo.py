#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""SOCP 黄金 Demo：sshd 暴力破解 -> 账户接管 -> 响应闭环。

默认走真实采集链：demo/sample.log -> Vector -> search-config -> Kafka。
如果只想快速验证 search-config 之后的链路，可使用 ``--transport ingest``。
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

BUILD = Path(__file__).resolve().parents[1]
REPO = BUILD.parent
sys.path.insert(0, str(BUILD))

from ports import GATEWAY_URL, base_url  # noqa: E402


USER = os.environ.get("DEMO_USER", "demo")
PASSWORD = os.environ.get("DEMO_PASS", "demo123")
VECTOR_TOKEN = os.environ.get("SOCP_VECTOR_TOKEN", "dev-vector-token")
COLLECTOR = "golden-demo"
DEMO_CHANNEL = "SOCP Golden Demo (logged)"
TIMEOUT = float(os.environ.get("GOLDEN_DEMO_TIMEOUT", "90"))

SERVICES = {
    "search": "search-config",
    "alert": "alert-web",
    "incident": "incident-web",
    "soar": "soar-web",
    "notify": "notify-web",
    "report": "report-web",
}


def unwrap(value):
    if isinstance(value, dict) and "data" in value:
        return value["data"]
    return value


def request(service, path, token=None, body=None, method=None, headers=None):
    service_name = SERVICES.get(service, service)
    base = GATEWAY_URL if service == "gateway" else base_url(service_name)
    context = "" if service == "gateway" else "/" + service_name
    url = base + context + path
    data = None if body is None else json.dumps(body, ensure_ascii=False).encode()
    req = urllib.request.Request(url, data=data, method=method or ("POST" if body is not None else "GET"))
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    for key, value in (headers or {}).items():
        req.add_header(key, value)
    try:
        with urllib.request.urlopen(req, timeout=15) as response:
            raw = response.read().decode("utf-8", errors="replace")
            try:
                parsed = json.loads(raw) if raw else {}
            except json.JSONDecodeError:
                parsed = raw
            return response.status, parsed, dict(response.headers)
    except urllib.error.HTTPError as error:
        raw = error.read().decode("utf-8", errors="replace")
        try:
            parsed = json.loads(raw) if raw else {}
        except json.JSONDecodeError:
            parsed = raw
        return error.code, parsed, dict(error.headers)
    except Exception as error:
        return 0, {"error": str(error)}, {}


def check(name, condition, detail=""):
    marker = "PASS" if condition else "FAIL"
    print(f"  [{marker}] {name}" + (f" -> {str(detail)[:220]}" if detail else ""))
    return condition


def wait_for(label, fn, timeout=TIMEOUT, interval=1.0):
    deadline = time.time() + timeout
    last = None
    while time.time() < deadline:
        last = fn()
        if last:
            return last
        time.sleep(interval)
    print(f"  [TIMEOUT] {label}: {last}")
    return None


def login():
    status, result, headers = request(
        "gateway", "/auth/login", body={"username": USER, "password": PASSWORD}
    )
    token = result.get("token") if isinstance(result, dict) else None
    if status != 200 or not token:
        raise RuntimeError(f"登录失败 status={status} body={result}")
    return token, headers.get("X-Trace-Id", "")


def vector_running():
    try:
        result = subprocess.run(
            ["docker", "inspect", "--format={{.State.Running}}", "socp-vector"],
            capture_output=True,
            text=True,
            timeout=8,
            check=False,
        )
        return result.returncode == 0 and result.stdout.strip().lower() == "true"
    except (OSError, subprocess.SubprocessError):
        return False


def event_lines(source_ip, host):
    timestamp = dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")
    failed = [
        f"{timestamp} {host} sshd[{4100 + i}]: Failed password for invalid user root from {source_ip} port {51234 + i} ssh2"
        for i in range(5)
    ]
    accepted = f"{timestamp} {host} sshd[4200]: Accepted password for root from {source_ip} port 51299 ssh2"
    return failed, accepted


def append_lines(lines, log_path):
    log_path.parent.mkdir(parents=True, exist_ok=True)
    with log_path.open("a", encoding="utf-8", newline="\n") as stream:
        for line in lines:
            stream.write(line + "\n")


def list_alerts(token):
    status, result, _ = request("alert", "/api/alarms?page=1&size=500", token=token)
    data = unwrap(result)
    if isinstance(data, dict):
        data = data.get("items", [])
    return status, data if isinstance(data, list) else []


def matching_alert(alerts, rule_id, source_ip):
    entity_pattern = re.compile(r"(?<!\d)" + re.escape(source_ip) + r"(?!\d)")
    return next(
        (
            item
            for item in alerts
            if item.get("ruleId") == rule_id
            and entity_pattern.search(str(item.get("entity", "")))
        ),
        None,
    )


def ensure_playbook(token):
    status, result, _ = request("soar", "/api/v1/playbooks", token=token)
    items = result if isinstance(result, list) else unwrap(result)
    items = items if isinstance(items, list) else []
    name = "SOCP Golden Demo Response"
    existing = next((item for item in items if item.get("name") == name), None)
    if existing:
        if not existing.get("enabled", True):
            request("soar", f"/api/v1/playbooks/{existing.get('id')}/toggle", token=token)
        return existing
    status, result, _ = request(
        "soar",
        "/api/v1/playbooks",
        token=token,
        body={
            "name": name,
            "trigger": "AUTH-BRUTE-SUCCESS",
            "actions": ["notify"],
            "enabled": True,
        },
    )
    if status not in (200, 201):
        raise RuntimeError(f"创建 SOAR Demo 剧本失败 status={status} body={result}")
    return result


def ensure_channel(token):
    status, result, _ = request("notify", "/api/v1/channels", token=token)
    items = result if isinstance(result, list) else unwrap(result)
    items = items if isinstance(items, list) else []
    name = DEMO_CHANNEL
    existing = next((item for item in items if item.get("name") == name), None)
    if existing:
        if not existing.get("enabled", True):
            request("notify", f"/api/v1/channels/{existing.get('id')}/toggle", token=token)
        return existing
    status, result, _ = request(
        "notify",
        "/api/v1/channels",
        token=token,
        body={
            "name": name,
            "type": "EMAIL",
            "target": "golden-demo@example.invalid",
            "enabled": True,
            "description": "Local demo channel; records dispatch without sending mail.",
        },
    )
    if status not in (200, 201):
        raise RuntimeError(f"创建通知 Demo 渠道失败 status={status} body={result}")
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--transport",
        choices=("vector", "ingest"),
        default="vector",
        help="vector 走真实采集链；ingest 直接调用 search-config，便于排查",
    )
    parser.add_argument("--log-file", default=str(REPO / "demo" / "sample.log"))
    args = parser.parse_args()

    print("=== SOCP Golden Demo: SSH brute force -> account compromise ===")
    print(f"transport={args.transport} gateway={GATEWAY_URL}")
    try:
        token, login_trace = login()
    except RuntimeError as error:
        print(f"[FAIL] {error}")
        return 1
    check("gateway login", bool(token), f"trace={login_trace or 'n/a'}")

    if args.transport == "vector":
        if not check("Vector container is running", vector_running(), "start it with bash build/run-vector.sh start"):
            print("\nPrerequisites:")
            print("  docker compose -f infra/docker-compose.yml up -d")
            print("  bash build/run-all.sh backend")
            print("  bash build/run-vector.sh start")
            return 1

    try:
        playbook = ensure_playbook(token)
        channel = ensure_channel(token)
    except RuntimeError as error:
        print(f"[FAIL] Demo prerequisites: {error}")
        return 1
    check("SOAR demo playbook ready", bool(playbook.get("id")), playbook.get("name"))
    check("local notification channel ready", bool(channel.get("id")), channel.get("name"))

    epoch = int(time.time())
    run_id = str(epoch)
    source_ip = f"203.0.113.{10 + epoch % 200}"
    host = f"golden-ssh-{run_id}"
    failed, accepted = event_lines(source_ip, host)
    print(f"\nScenario: {len(failed)} failed SSH logins from {source_ip}, then one accepted login")

    if args.transport == "vector":
        append_lines(failed, Path(args.log_file))
        print(f"  appended {len(failed)} raw sshd lines to {args.log_file}")
    else:
        # request() intentionally serializes JSON bodies, so raw NDJSON uses urllib directly.
        url = base_url("search-config") + "/api/v1/ingest"
        req = urllib.request.Request(
            url,
            data=("\n".join(failed) + "\n").encode(),
            method="POST",
            headers={
                "Authorization": "Bearer " + VECTOR_TOKEN,
                "X-SOCP-Collector": COLLECTOR,
                "Content-Type": "application/x-ndjson",
            },
        )
        try:
            with urllib.request.urlopen(req, timeout=20) as response:
                status = response.status
                result = json.loads(response.read().decode())
        except urllib.error.HTTPError as error:
            status, result = error.code, error.read().decode(errors="replace")
        check("search-config accepted failed-log batch", status == 200, result)
        if status != 200:
            return 1

    # The search API currently tokenizes numeric IP values, so querying only by
    # src_ip can return a prefix/substring match from an earlier demo run.  The
    # per-run host is unique; use it as the server-side narrowing condition and
    # retain exact field checks below as the final assertion.
    query = urllib.parse.quote(f"source=auth host={host}", safe="")

    def canonical_event():
        status, result, headers = request("search", f"/api/v1/search?q={query}", token=token)
        data = result if isinstance(result, dict) else {}
        events = data.get("events", [])
        for event in events if isinstance(events, list) else []:
            ecs = event.get("ecs", {}) or {}
            if (
                event.get("source") == "auth"
                and event.get("host") == host
                and (ecs.get("source.ip") == source_ip or event.get("fields", {}).get("src_ip") == source_ip)
            ):
                return event, headers.get("X-Trace-Id", "")
        return None

    event_result = wait_for("canonical event", canonical_event)
    if not check("sshd parsed into canonical auth event", bool(event_result), event_result):
        return 1
    event, search_trace = event_result
    print(
        "  canonical: "
        + json.dumps(
            {
                "source": event.get("source"),
                "event.action": (event.get("ecs", {}) or {}).get("event.action"),
                "source.ip": (event.get("ecs", {}) or {}).get("source.ip"),
                "user.name": (event.get("ecs", {}) or {}).get("user.name"),
            },
            ensure_ascii=False,
        )
    )

    def brute_alert():
        _, alerts = list_alerts(token)
        return matching_alert(alerts, "AUTH-BRUTE", source_ip)

    brute = wait_for("AUTH-BRUTE alert", brute_alert)
    if not check("threshold produced AUTH-BRUTE", bool(brute), brute):
        return 1
    print(f"  alert: id={brute.get('id')} severity={brute.get('severity')} mitre={brute.get('mitre')}")

    if args.transport == "vector":
        append_lines([accepted], Path(args.log_file))
        print("  appended one accepted sshd login to trigger correlation")
    else:
        url = base_url("search-config") + "/api/v1/ingest"
        req = urllib.request.Request(
            url,
            data=(accepted + "\n").encode(),
            method="POST",
            headers={
                "Authorization": "Bearer " + VECTOR_TOKEN,
                "X-SOCP-Collector": COLLECTOR,
                "Content-Type": "application/x-ndjson",
            },
        )
        with urllib.request.urlopen(req, timeout=20) as response:
            check("search-config accepted success-log", response.status == 200)

    def compromise_alert():
        _, alerts = list_alerts(token)
        return matching_alert(alerts, "AUTH-BRUTE-SUCCESS", source_ip)

    compromise = wait_for("AUTH-BRUTE-SUCCESS alert", compromise_alert)
    if not check("correlation produced AUTH-BRUTE-SUCCESS", bool(compromise), compromise):
        return 1
    print(f"  alert: id={compromise.get('id')} severity={compromise.get('severity')} mitre={compromise.get('mitre')}")

    def incident_for_alert():
        status, result, _ = request("incident", "/api/v1/incidents", token=token)
        incidents = result if isinstance(result, list) else unwrap(result)
        incidents = incidents if isinstance(incidents, list) else []
        return next(
            (item for item in incidents if compromise.get("id") in str(item.get("alarmIds", []))), None
        )

    incident = wait_for("incident fan-out", incident_for_alert)
    if not check("Outbox fan-out created incident", bool(incident), incident):
        return 1

    def notification_for_alert():
        status, result, _ = request("notify", "/api/v1/dispatch-log", token=token)
        logs = result if isinstance(result, list) else unwrap(result)
        logs = logs if isinstance(logs, list) else []
        return next(
            (
                item
                for item in logs
                if item.get("alarmId") == compromise.get("id")
                and item.get("channel") == DEMO_CHANNEL
                and item.get("type") == "EMAIL"
                and item.get("status") == "logged"
            ),
            None,
        )

    notification = wait_for("notification fan-out", notification_for_alert)
    if not check("Outbox fan-out recorded notification", bool(notification), notification):
        return 1

    def soar_execution():
        status, result, _ = request("soar", "/api/v1/playbooks/executions", token=token)
        executions = result if isinstance(result, list) else unwrap(result)
        executions = executions if isinstance(executions, list) else []
        return next(
            (
                item
                for item in reversed(executions)
                if item.get("trigger") == "AUTH-BRUTE-SUCCESS"
                and item.get("playbook") == "SOCP Golden Demo Response"
            ),
            None,
        )

    execution = wait_for("SOAR execution", soar_execution)
    if not check("Outbox fan-out executed SOAR playbook", bool(execution), execution):
        return 1

    status, report, report_headers = request("report", "/api/v1/reports/daily", token=token)
    if not check("report-web exposes downstream analytics", status == 200, report):
        return 1
    print(f"\nTrace IDs: login={login_trace or 'n/a'} search={search_trace or 'n/a'} report={report_headers.get('X-Trace-Id', 'n/a')}")
    print("\nNow show in Workbench: canonical event -> AUTH-BRUTE -> AUTH-BRUTE-SUCCESS -> incident timeline -> SOAR execution -> audit/trace.")
    print("This demo intentionally claims at-least-once + idempotent consumers, not exactly-once delivery.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
