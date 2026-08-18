#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Repeatable bulk and true-pipeline benchmark for SOCP.

The default ``bulk`` mode measures the detect HTTP boundary for backwards
compatibility.  ``--mode e2e`` sends NDJSON through search-config, then waits
for the resulting alerts.  It records p50/p95/p99 request latency, end-to-end
latency, accepted/rejected counts, Kafka offsets when kafka-python is present,
and optional OpenSearch/ClickHouse counters.

Examples:
  python build/benchmark-pipeline.py --count 100
  python build/benchmark-pipeline.py --mode e2e --count 100 --batch-size 25 \
      --output .cache/benchmark-e2e.json

This is a repeatable single-node baseline, not a production capacity claim.
Run the same command with a clean tenant and record the machine profile beside
the generated JSON report outside the repository.
"""

import argparse
import base64
import json
import os
import platform
import ssl
import statistics
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid
from datetime import datetime, timezone


def request(url, method="GET", body=None, headers=None, timeout=30):
    data = None if body is None else body.encode()
    req = urllib.request.Request(url, data=data, method=method,
                                 headers=headers or {})
    began = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as response:
            raw = response.read().decode()
            try:
                parsed = json.loads(raw) if raw else {}
            except json.JSONDecodeError:
                parsed = {"raw": raw}
            return response.status, parsed, (time.perf_counter() - began) * 1000
    except urllib.error.HTTPError as error:
        try:
            raw = error.read().decode()
            parsed = json.loads(raw) if raw else {}
        except Exception:
            parsed = {}
        return error.code, parsed, (time.perf_counter() - began) * 1000


def login(gateway):
    user = os.environ.get("BENCH_USER", "demo")
    password = os.environ.get("BENCH_PASS", "demo123")
    payload = json.dumps({"username": user, "password": password})
    status, body, _ = request(
        gateway + "/auth/login", "POST", payload,
        {"Content-Type": "application/json"}, timeout=15)
    if status != 200 or not body.get("token"):
        raise RuntimeError("login failed; check the local gateway and test credentials")
    return body["token"]


def unwrap(body):
    if isinstance(body, dict) and "data" in body:
        return body["data"]
    return body


def event_lines(run_id, start, end, mode):
    now = datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")
    rows = []
    for index in range(start, end):
        if mode == "e2e":
            # AUTH-PRIVESC is a single-event pattern. Unique hosts avoid the
            # five-minute suppressor while preserving a deterministic alert
            # expectation for the end-to-end wait.
            rows.append({
                "eventId": f"benchmark-{run_id}-{index}",
                "source": "auth",
                "host": f"benchmark-{run_id}-{index}",
                "message": "sudo: benchmark privilege escalation probe",
                "severity": "HIGH",
                "timestamp": now,
                "src_ip": f"198.51.100.{(index % 240) + 1}",
                "user": "benchmark-user",
            })
        else:
            rows.append({
                "eventId": f"benchmark-{run_id}-{index}",
                "source": "auth",
                "host": "benchmark-host",
                "msg": "Failed password for benchmark-user",
                "severity": "HIGH",
                "timestamp": now,
                "fields": {"src_ip": "198.51.100.10"},
            })
    return "\n".join(json.dumps(row, ensure_ascii=False) for row in rows) + "\n"


def percentile(values, p):
    if not values:
        return 0.0
    ordered = sorted(values)
    if len(ordered) == 1:
        return float(ordered[0])
    rank = (len(ordered) - 1) * p
    lower = int(rank)
    upper = min(lower + 1, len(ordered) - 1)
    return ordered[lower] + (ordered[upper] - ordered[lower]) * (rank - lower)


def kafka_snapshot():
    consumer = None
    try:
        from kafka import KafkaConsumer
        from kafka.structs import TopicPartition

        consumer = KafkaConsumer(
            bootstrap_servers=os.environ.get("BENCH_KAFKA", "127.0.0.1:9092"),
            group_id=os.environ.get("BENCH_GROUP", "socp-detect"),
            enable_auto_commit=False,
            request_timeout_ms=3000,
        )
        partitions = consumer.partitions_for_topic("socp-events") or set()
        tps = [TopicPartition("socp-events", p) for p in sorted(partitions)]
        if not tps:
            return None
        consumer.assign(tps)
        ends = consumer.end_offsets(tps)
        committed = {tp: consumer.committed(tp) for tp in tps}
        end_total = sum(ends.values())
        committed_total = sum((offset.offset if offset else 0) for offset in committed.values())
        return {
            "topic": "socp-events",
            "group": os.environ.get("BENCH_GROUP", "socp-detect"),
            "partitions": len(tps),
            "endOffset": end_total,
            "committedOffset": committed_total,
            "lag": max(0, end_total - committed_total),
        }
    except Exception:
        return None
    finally:
        if consumer is not None:
            consumer.close()


def optional_opensearch_count():
    url = os.environ.get("BENCH_OS_URL")
    if not url:
        return None
    auth = os.environ.get("BENCH_OS_AUTH", "admin:Socp!Sec2026xK")
    req = urllib.request.Request(url.rstrip("/") + "/socp-events-*/_count")
    req.add_header("Authorization", "Basic " + base64.b64encode(auth.encode()).decode())
    context = None
    if url.startswith("https"):
        context = ssl.create_default_context()
        context.check_hostname = False
        context.verify_mode = ssl.CERT_NONE
    try:
        with urllib.request.urlopen(req, timeout=10, context=context) as response:
            return int(json.loads(response.read().decode()).get("count", 0))
    except Exception:
        return None


def optional_clickhouse_count():
    url = os.environ.get("BENCH_CK_URL")
    if not url:
        return None
    auth = os.environ.get("BENCH_CK_AUTH", "default:socp")
    req = urllib.request.Request(
        url.rstrip("/") + "/", data=b"SELECT count(*) FROM alert_agg.alarm_detail",
        headers={"Authorization": "Basic " + base64.b64encode(auth.encode()).decode()})
    try:
        with urllib.request.urlopen(req, timeout=10) as response:
            return int(response.read().decode().strip() or "0")
    except Exception:
        return None


def git_commit():
    try:
        return subprocess.run(
            ["git", "rev-parse", "HEAD"], capture_output=True, text=True,
            timeout=3, check=False).stdout.strip() or None
    except Exception:
        return None


def machine_profile():
    profile = {
        "platform": platform.platform(),
        "python": sys.version.split()[0],
        "cpuCount": os.cpu_count(),
        "hostname": platform.node(),
        "gitCommit": git_commit(),
    }
    try:
        import psutil  # optional; the benchmark remains dependency-free without it

        profile["memoryBytes"] = psutil.virtual_memory().total
        profile["cpuPercent"] = psutil.cpu_percent(interval=0.1)
    except ImportError:
        profile["psutil"] = "not-installed"
    return profile


def detect_stats(gateway, token):
    status, body, _ = request(
        gateway + "/detect-web/api/v1/stats",
        headers={"Authorization": "Bearer " + token}, timeout=10)
    data = unwrap(body)
    return data if status == 200 and isinstance(data, dict) else None


def optional_prometheus_snapshot():
    """Read a small runtime-metric sample when a Prometheus endpoint is supplied."""
    url = os.environ.get("BENCH_PROMETHEUS_URL")
    if not url:
        return None
    wanted = {
        "process_cpu_usage", "system_cpu_usage", "jvm_memory_used_bytes",
        "jvm_memory_max_bytes", "jvm_gc_pause_seconds_count",
        "jvm_gc_pause_seconds_sum",
    }
    try:
        with urllib.request.urlopen(url, timeout=10) as response:
            text = response.read().decode("utf-8", errors="replace")
        values = {}
        for line in text.splitlines():
            if not line or line.startswith("#") or " " not in line:
                continue
            sample, raw_value = line.rsplit(" ", 1)
            name = sample.split("{", 1)[0]
            if name in wanted:
                try:
                    values[sample] = float(raw_value)
                except ValueError:
                    continue
        return values or None
    except Exception:
        return None


def alert_total(gateway, token):
    status, body, _ = request(
        gateway + "/alert-web/api/alarms?page=1&size=1",
        headers={"Authorization": "Bearer " + token})
    data = unwrap(body)
    if status != 200 or not isinstance(data, dict):
        return None
    try:
        return int(data.get("total", 0))
    except (TypeError, ValueError):
        return None


def wait_for_alerts(gateway, token, expected, baseline, timeout):
    deadline = time.monotonic() + timeout
    last = baseline
    while time.monotonic() < deadline:
        current = alert_total(gateway, token)
        if current is not None:
            last = current
            if current >= baseline + expected:
                return current
        time.sleep(1)
    return last


def choose_ingest_task(gateway, token):
    status, body, _ = request(
        gateway + "/search-config/api/v1/ingest/tasks",
        headers={"Authorization": "Bearer " + token})
    items = unwrap(body)
    if status != 200 or not isinstance(items, list) or not items:
        raise RuntimeError(f"no search-config ingest task available (HTTP {status})")
    enabled = [item for item in items if item.get("enabled", True)]
    return (enabled or items)[0]["id"]


def main():
    parser = argparse.ArgumentParser(description="SOCP single-node ingest baseline")
    parser.add_argument("--count", type=int, default=100)
    parser.add_argument("--batch-size", type=int, default=100)
    parser.add_argument("--mode", choices=("bulk", "e2e"), default="bulk")
    parser.add_argument("--gateway", default=os.environ.get("BENCH_GATEWAY", "http://127.0.0.1:18092"))
    parser.add_argument("--timeout", type=float, default=120.0,
                        help="seconds to wait for e2e alerts")
    parser.add_argument("--output", help="optional JSON report path")
    parser.add_argument("--label", default="baseline",
                        help="human-readable run label stored in the report")
    parser.add_argument("--instances", type=int, default=1,
                        help="number of Detection instances used for this run")
    parser.add_argument("--rules", type=int, default=None,
                        help="rule count configured for this run, if known")
    args = parser.parse_args()
    if args.count <= 0 or args.batch_size <= 0:
        parser.error("--count and --batch-size must be positive")

    gateway = args.gateway.rstrip("/")
    token = login(gateway)
    profile = machine_profile()
    stats_before = detect_stats(gateway, token)
    runtime_before = optional_prometheus_snapshot()
    task_id = choose_ingest_task(gateway, token) if args.mode == "e2e" else None
    baseline_alerts = alert_total(gateway, token) if args.mode == "e2e" else None
    kafka_before = kafka_snapshot() if args.mode == "e2e" else None
    os_before = optional_opensearch_count() if args.mode == "e2e" else None
    ck_before = optional_clickhouse_count() if args.mode == "e2e" else None

    run_id = uuid.uuid4().hex[:10]
    accepted = rejected = forwarded = 0
    latencies = []
    started = time.perf_counter()

    for start in range(0, args.count, args.batch_size):
        end = min(start + args.batch_size, args.count)
        if args.mode == "e2e":
            target = gateway + "/search-config/api/v1/ingest"
            content_type = "application/x-ndjson"
        else:
            target = gateway + "/detect-web/api/v1/ingest/bulk"
            content_type = "application/x-ndjson"
        status, body, latency = request(
            target, "POST", event_lines(run_id, start, end, args.mode),
            {"Authorization": "Bearer " + token, "Content-Type": content_type},
            timeout=60)
        latencies.append(latency)
        data = unwrap(body)
        if status != 200 or not isinstance(data, dict):
            raise RuntimeError(f"{args.mode} ingest failed with HTTP {status}: {body}")
        accepted += int(data.get("accepted", 0))
        rejected += int(data.get("rejected", data.get("skipped", 0)))
        forwarded += int(data.get("forwarded", 0))

    elapsed = time.perf_counter() - started
    alerts_after = None
    alert_wait = None
    if args.mode == "e2e":
        wait_started = time.perf_counter()
        alerts_after = wait_for_alerts(
            gateway, token, accepted, baseline_alerts or 0, args.timeout)
        alert_wait = time.perf_counter() - wait_started

    report = {
        "runId": run_id,
        "label": args.label,
        "recordedAt": datetime.now(timezone.utc).isoformat(),
        "machine": profile,
        "detectionInstances": args.instances,
        "configuredRules": args.rules,
        "mode": args.mode,
        "requested": args.count,
        "batchSize": args.batch_size,
        "batches": len(latencies),
        "accepted": accepted,
        "rejected": rejected,
        "forwarded": forwarded,
        "elapsedSeconds": round(elapsed, 3),
        "eventsPerSecond": round(args.count / elapsed if elapsed else 0, 2),
        "batchLatencyMs": {
            "avg": round(statistics.mean(latencies), 2) if latencies else 0,
            "p50": round(percentile(latencies, 0.50), 2),
            "p95": round(percentile(latencies, 0.95), 2),
            "p99": round(percentile(latencies, 0.99), 2),
            "max": round(max(latencies), 2) if latencies else 0,
        },
        "detectionStatsBefore": stats_before,
        "detectionStatsAfter": detect_stats(gateway, token),
        "runtimeMetricsBefore": runtime_before,
        "runtimeMetricsAfter": optional_prometheus_snapshot(),
    }
    if args.mode == "e2e":
        report["ingestTaskId"] = task_id
        report["baselineAlerts"] = baseline_alerts
        report["alertsAfter"] = alerts_after
        report["alertsObserved"] = ((alerts_after or 0) - (baseline_alerts or 0))
        report["alertWaitSeconds"] = round(alert_wait or 0, 3)
        report["endToEndSeconds"] = round(elapsed + (alert_wait or 0), 3)
        report["endToEndEventsPerSecond"] = round(
            accepted / (elapsed + (alert_wait or 0))
            if accepted and elapsed + (alert_wait or 0) else 0, 2)
        report["kafkaBefore"] = kafka_before
        report["kafkaAfter"] = kafka_snapshot()
        report["openSearchBefore"] = os_before
        report["openSearchAfter"] = optional_opensearch_count()
        report["clickHouseBefore"] = ck_before
        report["clickHouseAfter"] = optional_clickhouse_count()

    print("SOCP %s baseline" % ("end-to-end" if args.mode == "e2e" else "bulk ingest"))
    print(json.dumps(report, ensure_ascii=False, indent=2))
    if args.output:
        os.makedirs(os.path.dirname(os.path.abspath(args.output)), exist_ok=True)
        with open(args.output, "w", encoding="utf-8") as output:
            json.dump(report, output, ensure_ascii=False, indent=2)
            output.write("\n")
    if accepted + rejected != args.count:
        raise SystemExit("result count does not match requested event count")
    if args.mode == "e2e" and (alerts_after or 0) < (baseline_alerts or 0) + accepted:
        raise SystemExit("e2e timeout: alert count did not catch up to accepted events")


if __name__ == "__main__":
    main()
