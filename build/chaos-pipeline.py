#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Run a small, repeatable failure matrix against a running SOCP stack.

This script is deliberately conservative: it only stops named SOCP services
through ``build/run-all.sh`` and uses unique event IDs for every run. It does
not claim exactly-once delivery; it checks the concrete invariants implemented
by the current pipeline (Kafka backlog recovery and source-alert idempotency).

Examples (Linux/macOS/WSL):
  python build/chaos-pipeline.py --scenario all --output .cache/chaos.json
  python build/chaos-pipeline.py --scenario duplicate_delivery
"""
from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path

BUILD = Path(__file__).resolve().parent
REPO = BUILD.parent
sys.path.insert(0, str(BUILD))

from ports import GATEWAY_URL, health_url  # noqa: E402


BOOTSTRAP = os.environ.get("PIPELINE_KAFKA", "127.0.0.1:9092")
TOPIC = os.environ.get("RECOVERY_TOPIC", "socp-events")
GROUP = os.environ.get("RECOVERY_GROUP", "socp-detect")
USER = os.environ.get("DEMO_USER", "demo")
PASSWORD = os.environ.get("DEMO_PASS", "demo123")
VECTOR_TOKEN = os.environ.get("SOCP_VECTOR_TOKEN", "dev-vector-token")
RUNNER = os.environ.get("SOCP_BASH", "bash")


def request(url, method="GET", body=None, headers=None, timeout=20):
    data = None if body is None else (body if isinstance(body, bytes) else body.encode())
    req = urllib.request.Request(url, data=data, method=method, headers=headers or {})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as response:
            raw = response.read().decode("utf-8", errors="replace")
            try:
                parsed = json.loads(raw) if raw else {}
            except json.JSONDecodeError:
                parsed = {"raw": raw}
            return response.status, parsed
    except urllib.error.HTTPError as error:
        raw = error.read().decode("utf-8", errors="replace")
        try:
            parsed = json.loads(raw) if raw else {}
        except json.JSONDecodeError:
            parsed = {"raw": raw}
        return error.code, parsed
    except Exception as error:
        return 0, {"error": str(error)}


def unwrap(body):
    return body.get("data") if isinstance(body, dict) and "data" in body else body


def login():
    status, body = request(
        GATEWAY_URL + "/auth/login", "POST",
        json.dumps({"username": USER, "password": PASSWORD}),
        {"Content-Type": "application/json"})
    token = body.get("token") if isinstance(body, dict) else None
    if status != 200 or not token:
        raise RuntimeError(f"gateway login failed HTTP {status}: {body}")
    return token


def auth_headers(token):
    return {"Authorization": "Bearer " + token}


def wait_for(predicate, timeout=120, interval=2):
    deadline = time.monotonic() + timeout
    last = None
    while time.monotonic() < deadline:
        last = predicate()
        if last:
            return last
        time.sleep(interval)
    return last


def service_up(service):
    status, _ = request(health_url(service), timeout=4)
    return status == 200


def control(action, service):
    result = subprocess.run(
        [RUNNER, str(REPO / "build" / "run-all.sh"), action, service],
        cwd=REPO, capture_output=True, text=True, timeout=60, check=False)
    if result.returncode != 0:
        raise RuntimeError(f"{action} {service} failed: {result.stderr[-500:]}")


def kafka_snapshot():
    try:
        from kafka import KafkaConsumer
        from kafka.structs import TopicPartition
    except ImportError as error:
        raise RuntimeError("kafka-python is required for chaos checks") from error

    consumer = KafkaConsumer(
        bootstrap_servers=BOOTSTRAP,
        group_id=GROUP,
        enable_auto_commit=False,
        request_timeout_ms=5000,
    )
    try:
        partitions = consumer.partitions_for_topic(TOPIC)
        if not partitions:
            raise RuntimeError(f"Kafka topic {TOPIC} has no partitions")
        tps = [TopicPartition(TOPIC, p) for p in sorted(partitions)]
        consumer.assign(tps)
        ends = consumer.end_offsets(tps)
        committed = {tp: consumer.committed(tp) for tp in tps}
        end_total = sum(ends.values())
        committed_total = sum((offset.offset if offset else 0) for offset in committed.values())
        return {"end": end_total, "committed": committed_total,
                "lag": max(0, end_total - committed_total),
                "partitions": len(tps)}
    finally:
        consumer.close()


def alert_total(token):
    status, body = request(
        GATEWAY_URL + "/alert-web/api/alarms?page=1&size=1",
        headers=auth_headers(token))
    data = unwrap(body)
    if status != 200 or not isinstance(data, dict):
        return None
    try:
        return int(data.get("total", 0))
    except (TypeError, ValueError):
        return None


def list_alerts(token):
    status, body = request(
        GATEWAY_URL + "/alert-web/api/alarms?page=1&size=500",
        headers=auth_headers(token))
    data = unwrap(body)
    if status != 200:
        return []
    if isinstance(data, dict):
        return data.get("items", [])
    return data if isinstance(data, list) else []


def ingest(token, events):
    payload = "\n".join(json.dumps(event) for event in events) + "\n"
    headers = {
        **auth_headers(token),
        "X-SOCP-Collector": "chaos-pipeline",
        "Content-Type": "application/x-ndjson",
    }
    status, body = request(
        GATEWAY_URL + "/search-config/api/v1/ingest", "POST", payload, headers, timeout=30)
    if status != 200:
        raise RuntimeError(f"ingest failed HTTP {status}: {body}")
    return body


def scenario_detection_restart(token, count):
    baseline = kafka_snapshot()
    if baseline is None:
        raise RuntimeError("Kafka snapshot unavailable; check kafka-python and the broker")
    if baseline["lag"] != 0:
        raise RuntimeError(f"detection restart scenario requires a drained baseline, got lag={baseline['lag']}")
    control("stop-service", "detect-web")
    stopped = wait_for(lambda: not service_up("detect-web"), timeout=30)
    if not stopped:
        raise RuntimeError("detect-web did not stop")
    run_id = uuid.uuid4().hex[:10]
    events = [{
        "eventId": f"chaos-restart-{run_id}-{i}",
        "source": "auth",
        "host": f"chaos-restart-{run_id}",
        "severity": "WARN",
        "message": f"Failed password for invalid user root from 198.51.100.77 port {52000 + i} ssh2",
        "src_ip": "198.51.100.77",
        "user": "root",
    } for i in range(count)]
    accepted = ingest(token, events)
    accepted_count = int(accepted.get("accepted", count)) if isinstance(accepted, dict) else count

    def queued_snapshot():
        snapshot = kafka_snapshot()
        return snapshot if snapshot and snapshot["end"] >= baseline["end"] + accepted_count else None

    queued = wait_for(queued_snapshot, timeout=30, interval=1) or kafka_snapshot()
    if queued is None:
        raise RuntimeError("Kafka backlog snapshot unavailable while detect-web was stopped")
    control("start-service", "detect-web")
    recovered = wait_for(lambda: kafka_snapshot() if service_up("detect-web") else None,
                         timeout=120, interval=3)
    if recovered is None:
        raise RuntimeError("detect-web did not recover")
    def drained_snapshot():
        snapshot = kafka_snapshot()
        return snapshot if snapshot and snapshot["lag"] == 0 else None

    recovered = wait_for(drained_snapshot, timeout=120, interval=3) or kafka_snapshot()
    if recovered is None:
        raise RuntimeError("Kafka snapshot unavailable while waiting for Detection recovery")
    return {
        "accepted": accepted,
        "acceptedCount": accepted_count,
        "baseline": baseline,
        "whileStopped": queued,
        "afterRecovery": recovered,
        "pass": queued["end"] >= baseline["end"] + accepted_count and recovered["lag"] == 0,
    }


def scenario_duplicate_delivery(token):
    run_id = uuid.uuid4().hex[:10]
    event_id = f"chaos-duplicate-{run_id}"
    event = {
        "eventId": event_id,
        "source": "auth",
        "host": f"chaos-duplicate-{run_id}",
        "severity": "HIGH",
        "message": "sudo: chaos duplicate-delivery probe",
    }
    before = alert_total(token) or 0
    accepted = ingest(token, [event, event])
    after = wait_for(lambda: (alert_total(token) or 0) >= before + 1, timeout=60, interval=1)
    # Pattern alert ids are derived from rule/entity/evidence, not event id;
    # sourceAlertId is therefore checked by counting the matching host/rule.
    all_alerts = list_alerts(token)
    matching = [a for a in all_alerts
                if a.get("ruleId") == "AUTH-PRIVESC" and a.get("entity") == event["host"]]
    return {
        "accepted": accepted,
        "alertCountBefore": before,
        "alertCountAfter": after,
        "matchingAlerts": len(matching),
        "pass": after is not None and len(matching) == 1,
        "eventId": event_id,
        "sourceAlertIds": [a.get("sourceAlertId") for a in matching],
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--scenario", choices=("all", "detect_restart", "duplicate_delivery"), default="all")
    parser.add_argument("--count", type=int, default=20,
                        help="events used by the Detection restart scenario")
    parser.add_argument("--output", help="optional JSON result path")
    args = parser.parse_args()
    if args.count < 5:
        parser.error("--count must be at least 5")

    token = login()
    results = {}
    if args.scenario in ("all", "detect_restart"):
        results["detect_restart"] = scenario_detection_restart(token, args.count)
    if args.scenario in ("all", "duplicate_delivery"):
        results["duplicate_delivery"] = scenario_duplicate_delivery(token)
    report = {"recordedAt": time.time(), "topic": TOPIC, "group": GROUP, "results": results,
              "pass": all(result.get("pass") for result in results.values())}
    print(json.dumps(report, ensure_ascii=False, indent=2))
    if args.output:
        Path(args.output).parent.mkdir(parents=True, exist_ok=True)
        Path(args.output).write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return 0 if report["pass"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
