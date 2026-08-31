#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Demonstrate Kafka backlog and Detection recovery without losing events.

The script stops only ``detect-web`` through the repository startup helper,
injects a batch through the normal ingest boundary, and starts Detection
again. It asserts that the Kafka topic grows while the consumer is down and
that the committed offset catches up after recovery.

This is an operational demo, not a production throughput benchmark. It needs
the full backend, Kafka, and the Python ``kafka-python`` package.
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
from pathlib import Path

BUILD = Path(__file__).resolve().parents[1]
REPO = BUILD.parent
sys.path.insert(0, str(BUILD))

from ports import GATEWAY_URL, base_url, health_url  # noqa: E402
from auth_client import login_token  # noqa: E402


SERVICE = "detect-web"
TOPIC = os.environ.get("RECOVERY_TOPIC", "socp-events")
GROUP = os.environ.get("RECOVERY_GROUP", "socp-detect")
BOOTSTRAP = os.environ.get("PIPELINE_KAFKA", "127.0.0.1:9092")
USER = os.environ.get("DEMO_USER", "demo")
PASSWORD = os.environ.get("DEMO_PASS", "demo123")
VECTOR_TOKEN = os.environ.get("SOCP_VECTOR_TOKEN", "dev-vector-token")
COLLECTOR_ID = os.environ.get("RECOVERY_DEMO_COLLECTOR_ID", "").strip()
COLLECTOR_TOKEN = os.environ.get("RECOVERY_DEMO_COLLECTOR_TOKEN", "").strip()
if not COLLECTOR_TOKEN:
    COLLECTOR_TOKEN = os.environ.get("PIPELINE_COLLECTOR_TOKEN", "").strip()
    if COLLECTOR_TOKEN and not COLLECTOR_ID:
        COLLECTOR_ID = os.environ.get("PIPELINE_COLLECTOR_ID", "").strip()
COLLECTOR_ID = COLLECTOR_ID or "recovery-demo"
INGEST_TOKEN = COLLECTOR_TOKEN or os.environ.get("SOCP_INGEST_TOKEN", VECTOR_TOKEN).strip()
INGEST_URL = os.environ.get("PIPELINE_INGEST_URL") or (
    base_url("search-config") + "/search-config/api/v1/ingest"
)
TIMEOUT = float(os.environ.get("RECOVERY_TIMEOUT", "120"))
RUNNER = os.environ.get("SOCP_BASH", "bash")


def request(url, method="GET", body=None, headers=None, timeout=15):
    data = None if body is None else body
    req = urllib.request.Request(url, data=data, method=method,
                                 headers=headers or {})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as response:
            raw = response.read().decode("utf-8", errors="replace")
            return response.status, json.loads(raw) if raw else {}
    except urllib.error.HTTPError as error:
        raw = error.read().decode("utf-8", errors="replace")
        try:
            body = json.loads(raw) if raw else {}
        except json.JSONDecodeError:
            body = {"raw": raw}
        return error.code, body
    except Exception as error:
        return 0, {"error": str(error)}


def login():
    return login_token(GATEWAY_URL, USER, PASSWORD)


def check(name, condition, detail=""):
    marker = "PASS" if condition else "FAIL"
    suffix = f" -> {str(detail)[:180]}" if detail else ""
    print(f"  [{marker}] {name}{suffix}")
    return condition


def health_up():
    status, _ = request(health_url(SERVICE), timeout=4)
    return status == 200


def wait_for(label, predicate, timeout=TIMEOUT, interval=2):
    deadline = time.time() + timeout
    last = None
    while time.time() < deadline:
        try:
            last = predicate()
            if last:
                return last
        except Exception as error:
            last = error
        time.sleep(interval)
    print(f"  [TIMEOUT] {label}: {last}")
    return None


def control(action):
    command = [RUNNER, str(REPO / "build" / "run-all.sh"), action, SERVICE]
    result = subprocess.run(command, cwd=REPO, capture_output=True,
                            text=True, timeout=60, check=False)
    if result.returncode != 0:
        raise RuntimeError(f"startup helper {action} failed with exit {result.returncode}")


def kafka_snapshot():
    try:
        from kafka import KafkaConsumer
        from kafka.admin import KafkaAdminClient
        from kafka.structs import TopicPartition
    except ImportError as error:
        raise RuntimeError("kafka-python is required; install it before this demo") from error

    # Read offsets through the admin API instead of becoming a member of the
    # live Detection group and causing an unnecessary rebalance.
    consumer = KafkaConsumer(
        bootstrap_servers=BOOTSTRAP,
        group_id=None,
        enable_auto_commit=False,
        request_timeout_ms=5000,
    )
    admin = KafkaAdminClient(bootstrap_servers=BOOTSTRAP, client_id="socp-recovery-offset-inspector")
    try:
        partitions = consumer.partitions_for_topic(TOPIC)
        if not partitions:
            raise RuntimeError(f"Kafka topic {TOPIC} has no partitions")
        topic_partitions = [
            TopicPartition(TOPIC, partition)
            for partition in sorted(partitions)
        ]
        consumer.assign(topic_partitions)
        ends = consumer.end_offsets(topic_partitions)
        committed = admin.list_group_offsets({GROUP: topic_partitions}).get(GROUP, {})
        end_total = sum(ends.values())
        committed_total = sum(
            offset if isinstance(offset, int) else (offset.offset if offset is not None else 0)
            for offset in committed.values()
        )
        return {
            "end": end_total,
            "committed": committed_total,
            "lag": max(0, end_total - committed_total),
        }
    finally:
        admin.close()
        consumer.close()


def event_lines(run_id, count):
    host = f"recovery-demo-{run_id}"
    source_ip = f"198.51.100.{10 + int(run_id[-2:], 16) % 200}"
    lines = []
    for index in range(count):
        lines.append(json.dumps({
            "collector": COLLECTOR_ID,
            "host": host,
            "source": "auth",
            "severity": "HIGH",
            "message": (
                f"Failed password for invalid user root from {source_ip} "
                f"port {52000 + index} ssh2"
            ),
            "src_ip": source_ip,
            "user": "root",
        }))
    return "\n".join(lines) + "\n"


def ingest(lines):
    request_headers = {
        "Authorization": "Bearer " + INGEST_TOKEN,
        "X-SOCP-Collector": COLLECTOR_ID,
        "Content-Type": "application/x-ndjson",
    }
    status, body = request(
        INGEST_URL,
        "POST",
        lines.encode(),
        request_headers,
        timeout=30,
    )
    if status != 200:
        raise RuntimeError(f"ingest failed with HTTP {status}")
    accepted = body.get("accepted", 0) if isinstance(body, dict) else 0
    if accepted <= 0:
        raise RuntimeError("ingest accepted no events")
    return body


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--count", type=int, default=20,
                        help="events to inject while Detection is stopped")
    args = parser.parse_args()
    if args.count < 5:
        parser.error("--count must be at least 5")

    print("=== SOCP Detection recovery demo ===")
    login()  # Authentication proves the gateway is ready; ingest uses the collector token.
    if not check("detect-web is initially healthy", health_up()):
        return 1

    baseline = kafka_snapshot()
    print(f"  baseline: topic_end={baseline['end']} committed={baseline['committed']} lag={baseline['lag']}")
    stopped = False
    try:
        control("stop-service")
        stopped = True
        if not check("detect-web stopped", wait_for("detect-web to stop", lambda: not health_up())):
            return 1

        run_id = f"{time.time_ns():x}"[-8:]
        result = ingest(event_lines(run_id, args.count))
        expected = int(result.get("accepted", 0))
        print(f"  injected: accepted={expected}")

        def queued_snapshot():
            snapshot = kafka_snapshot()
            return snapshot if snapshot["end"] >= baseline["end"] + expected else None

        queued = wait_for(
            "Kafka topic to grow while Detection is down",
            queued_snapshot,
        )
        if not check("events remain in Kafka while Detection is down", bool(queued), queued):
            return 1
        if not check("consumer lag increases", queued["lag"] > baseline["lag"], queued):
            return 1

        control("start-service")
        stopped = False
        if not check("detect-web restarted", wait_for("detect-web to start", health_up)):
            return 1

        def recovered_snapshot():
            snapshot = kafka_snapshot()
            return snapshot if snapshot["committed"] >= queued["end"] else None

        recovered = wait_for(
            "consumer offset to catch up",
            recovered_snapshot,
        )
        if not check("consumer resumes from backlog", bool(recovered), recovered):
            return 1
        if not check("Kafka lag returns to zero", recovered["lag"] == 0, recovered):
            return 1
        print("\nResult: ingestion stayed available, Kafka retained the backlog, and Detection recovered with at-least-once processing.")
        return 0
    finally:
        if stopped:
            try:
                control("start-service")
            except Exception as error:
                print(f"  [WARN] recovery cleanup failed: {error}")


if __name__ == "__main__":
    raise SystemExit(main())
