#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Run a small, repeatable failure matrix against a running SOCP stack.

This script is deliberately conservative: it only stops named SOCP services
through ``build/run-all.sh`` and uses unique event IDs for every run. It does
not claim exactly-once delivery; it checks the concrete invariants implemented
by the current pipeline (Kafka backlog recovery, multi-instance partition
ownership, durable Alert Web delivery, and source-alert idempotency).

Examples (Linux/macOS/WSL):
  python build/chaos-pipeline.py --scenario all --output .cache/chaos.json
  python build/chaos-pipeline.py --scenario duplicate_delivery
  python build/chaos-pipeline.py --scenario alert_web_restart
  DETECTION_INSTANCE_URLS=http://127.0.0.1:18082,http://127.0.0.1:28082 \
    python build/chaos-pipeline.py --scenario multi_instance
"""
from __future__ import annotations

import argparse
import hashlib
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
GROUP = os.environ.get("RECOVERY_GROUP", os.environ.get("SOCP_KAFKA_GROUP_ID", "socp-detect"))
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


def service_up(service, token=None):
    status, _ = request(health_url(service),
                        headers=auth_headers(token) if token else {}, timeout=4)
    return status == 200


def direct_instance_up(base_url, token=None):
    status, _ = request(base_url.rstrip("/") + "/detect-web/actuator/health",
                        headers=auth_headers(token) if token else {}, timeout=4)
    return status == 200


def direct_instance_stats(base_url, token=None):
    status, body = request(base_url.rstrip("/") + "/detect-web/api/v1/stats",
                           headers=auth_headers(token) if token else {}, timeout=5)
    data = unwrap(body)
    return data if status == 200 and isinstance(data, dict) else None


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


def java_name_uuid(value):
    """Match java.util.UUID.nameUUIDFromBytes used by Alert.stableId."""
    digest = bytearray(hashlib.md5(value.encode("utf-8")).digest())
    digest[6] = (digest[6] & 0x0F) | 0x30
    digest[8] = (digest[8] & 0x3F) | 0x80
    return str(uuid.UUID(bytes=bytes(digest)))


def expected_alert_id(rule_id, entity, event_ids):
    return java_name_uuid("|".join([rule_id, entity, *event_ids]))


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
    stopped = False
    try:
        control("stop-service", "detect-web")
        stopped = wait_for(lambda: not service_up("detect-web", token), timeout=30)
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
        stopped = False
        recovered = wait_for(lambda: kafka_snapshot() if service_up("detect-web", token) else None,
                             timeout=120, interval=3)
        if recovered is None:
            raise RuntimeError("detect-web did not recover")

        def drained_snapshot():
            snapshot = kafka_snapshot()
            return snapshot if snapshot and snapshot["lag"] == 0 else None

        recovered = wait_for(drained_snapshot, timeout=120, interval=3) or kafka_snapshot()
        if recovered is None:
            raise RuntimeError("Kafka snapshot unavailable while waiting for Detection recovery")
        stats = direct_instance_stats(GATEWAY_URL, token)
        return {
            "accepted": accepted,
            "acceptedCount": accepted_count,
            "baseline": baseline,
            "whileStopped": queued,
            "afterRecovery": recovered,
            "pendingEventsAfterRecovery": (stats or {}).get("pendingEvents"),
            "pass": queued["end"] >= baseline["end"] + accepted_count and recovered["lag"] == 0
                    and (stats or {}).get("pendingEvents") == 0,
        }
    finally:
        if stopped and not service_up("detect-web", token):
            control("start-service", "detect-web")


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


def scenario_alert_web_restart(token):
    """Verify Detection's durable outbox survives an Alert Web outage."""
    if not service_up("detect-web", token):
        raise RuntimeError("detect-web must be healthy before alert_web_restart")
    run_id = uuid.uuid4().hex[:10]
    host = f"chaos-alert-web-{run_id}"
    event = {
        "eventId": f"chaos-alert-web-{run_id}",
        "source": "auth",
        "host": host,
        "severity": "CRITICAL",
        "message": "sudo: alert-web outage delivery probe",
    }
    before = alert_total(token) or 0
    stopped = False
    try:
        control("stop-service", "alert-web")
        stopped = wait_for(lambda: not service_up("alert-web", token), timeout=30, interval=1)
        if not stopped:
            raise RuntimeError("alert-web did not stop")
        accepted = ingest(token, [event])
        time.sleep(5)
        while_down = alert_total(token)
        control("start-service", "alert-web")
        stopped = False

        def recovered_total():
            value = alert_total(token)
            return value if value is not None and value >= before + 1 else None

        recovered = wait_for(recovered_total, timeout=180, interval=2)
        matching = [item for item in list_alerts(token)
                    if item.get("entity") == host and item.get("ruleId") == "AUTH-PRIVESC"]
        return {
            "accepted": accepted,
            "before": before,
            "whileAlertWebDown": while_down,
            "afterRecovery": recovered,
            "matchingAlerts": len(matching),
            "sourceAlertIds": [item.get("sourceAlertId") for item in matching],
            "pass": recovered is not None and len(matching) == 1,
        }
    finally:
        if stopped and not service_up("alert-web", token):
            control("start-service", "alert-web")


def scenario_multi_instance(token, count):
    """Prove stable entity routing, assignment ownership, rebalance, and recovery.

    The first URL is the instance controlled by run-all.sh. Additional URLs
    must be started manually with the same Kafka group and PostgreSQL profile,
    for example with SOCP_KAFKA_GROUP_ID=socp-detect and distinct ports.
    """
    raw_urls = os.environ.get("DETECTION_INSTANCE_URLS", "")
    urls = [item.strip().rstrip("/") for item in raw_urls.split(",") if item.strip()]
    if len(urls) < 2:
        raise RuntimeError("multi_instance requires DETECTION_INSTANCE_URLS with at least two URLs")

    baseline = kafka_snapshot()
    if not baseline or baseline["partitions"] < len(urls):
        raise RuntimeError(
            f"multi_instance requires at least {len(urls)} Kafka partitions; got {baseline}")
    if not all(wait_for(lambda url=url: direct_instance_up(url, token), timeout=30, interval=1)
               for url in urls):
        raise RuntimeError(f"not all Detection instances are healthy: {urls}")

    def assignments():
        values = [direct_instance_stats(url, token) for url in urls]
        if any(not isinstance(item, dict) for item in values):
            return None
        partitions = [set(item.get("assignedPartitions", [])) for item in values]
        if any(not item for item in partitions):
            return None
        if set().union(*partitions) != set(range(baseline["partitions"])):
            return None
        if sum(len(item) for item in partitions) != len(set().union(*partitions)):
            return None
        return {"instances": urls, "assignedPartitions": [sorted(item) for item in partitions]}

    initial = wait_for(assignments, timeout=90, interval=2)
    if initial is None:
        raise RuntimeError("Detection instances did not obtain disjoint full partition ownership")

    run_id = uuid.uuid4().hex[:10]
    group_count = max(1, count // 5)

    def threshold_batch(prefix, ip_start):
        events = []
        expected = []
        for group in range(group_count):
            src_ip = f"198.51.100.{ip_start + group}"
            ids = [f"chaos-multi-{prefix}-{run_id}-{group}-{i}" for i in range(5)]
            events.extend({
                "eventId": event_id,
                "source": "firewall",
                "host": f"multi-host-{run_id}-{prefix}-{group}-{i}",
                "severity": "HIGH",
                "message": "RDP connection to 3389",
                "src_ip": src_ip,
            } for i, event_id in enumerate(ids))
            expected.append(expected_alert_id("LATERAL-RDP", src_ip, ids))
        return events, expected

    events, expected_initial = threshold_batch("initial", 220)
    before = alert_total(token) or 0
    ingest_result = ingest(token, events)
    def observed_total(expected_count=1):
        value = alert_total(token)
        return value if value is not None and value >= before + expected_count else None

    observed = wait_for(lambda: observed_total(len(expected_initial)), timeout=120, interval=2)

    def matching_alerts(expected_ids):
        return [item for item in list_alerts(token)
                if item.get("sourceAlertId") in expected_ids]

    matching = wait_for(lambda: matching_alerts(set(expected_initial))
                        if len(matching_alerts(set(expected_initial))) == len(expected_initial) else None,
                        timeout=60, interval=2) or []

    # Stop the canonical instance and verify the remaining instance receives
    # the full assignment. This is the real rebalance boundary, not merely a
    # two-process startup check.
    stopped = False
    try:
        control("stop-service", "detect-web")
        stopped = wait_for(lambda: not direct_instance_up(urls[0], token), timeout=30, interval=1)
        if not stopped:
            raise RuntimeError("canonical Detection instance did not stop for rebalance")
        after_stop = wait_for(
            lambda: direct_instance_stats(urls[1], token)
            if direct_instance_up(urls[1], token) else None,
            timeout=90, interval=2)
        after_stop_partitions = (after_stop or {}).get("assignedPartitions", [])
        rebalance_ok = len(after_stop_partitions) == baseline["partitions"]
        post_events, expected_post = threshold_batch("post-rebalance", 230)
        post_ingest = ingest(token, post_events)
        expected_all = expected_initial + expected_post
        observed_post = wait_for(lambda: observed_total(len(expected_all)), timeout=120, interval=2)
        matching_all = wait_for(
            lambda: matching_alerts(set(expected_all))
            if len(matching_alerts(set(expected_all))) == len(expected_all) else None,
            timeout=60, interval=2) or []
        control("start-service", "detect-web")
        stopped = False
        recovered = wait_for(lambda: assignments(), timeout=120, interval=2)
        instance_stats = [direct_instance_stats(url, token) for url in urls]
        pending_values = [item.get("pendingEvents") for item in instance_stats if isinstance(item, dict)]
        expected_ids = set(expected_all)
        actual_ids = {item.get("sourceAlertId") for item in matching_all}
        return {
            "initialAssignments": initial,
            "afterStop": {"instance": urls[1], "assignedPartitions": after_stop_partitions},
            "afterRestart": recovered,
            "ingest": ingest_result,
            "postRebalanceIngest": post_ingest,
            "alertsBefore": before,
            "alertsAfter": observed_post,
            "expectedAlertIds": sorted(expected_ids),
            "actualAlertIds": sorted(actual_ids),
            "missingAlertIds": sorted(expected_ids - actual_ids),
            "unexpectedAlertIds": sorted(actual_ids - expected_ids),
            "pendingEventsAfterRecovery": pending_values,
            "pass": (observed_post is not None and actual_ids == expected_ids
                      and all(value == 0 for value in pending_values)
                      and rebalance_ok and recovered is not None),
        }
    finally:
        if stopped and not direct_instance_up(urls[0], token):
            control("start-service", "detect-web")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--scenario", choices=("all", "detect_restart", "duplicate_delivery", "alert_web_restart", "multi_instance"), default="all")
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
    if args.scenario in ("all", "alert_web_restart"):
        results["alert_web_restart"] = scenario_alert_web_restart(token)
    if args.scenario == "multi_instance" or (
            args.scenario == "all" and os.environ.get("DETECTION_INSTANCE_URLS")):
        results["multi_instance"] = scenario_multi_instance(token, args.count)
    report = {"recordedAt": time.time(), "topic": TOPIC, "group": GROUP, "results": results,
              "pass": all(result.get("pass") for result in results.values())}
    print(json.dumps(report, ensure_ascii=False, indent=2))
    if args.output:
        Path(args.output).parent.mkdir(parents=True, exist_ok=True)
        Path(args.output).write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return 0 if report["pass"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
