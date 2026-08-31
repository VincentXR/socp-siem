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
  python build/benchmark-pipeline.py --mode e2e --profile realistic --count 100 --batch-size 25 \
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
from pathlib import Path
import ssl
import statistics
import subprocess
import sys
import time
import traceback
import urllib.error
import urllib.request
import uuid
import math
import re
from datetime import datetime, timezone

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from auth_client import login_token  # noqa: E402


# The final correctness checks intentionally run after the workload has
# drained.  Keep the assembled report in memory so a late assertion failure
# can be written to the failure sidecar instead of losing the useful before /
# after snapshots.
LAST_BENCHMARK_CONTEXT = None
MANIFEST_PATH = Path(__file__).resolve().parents[1] / \
    "services/detect-web/src/main/resources/detection-content/manifest.json"


def manifest_rule_count(path=MANIFEST_PATH):
    """Read the executable rule count from the versioned content pack."""
    try:
        with open(path, "r", encoding="utf-8") as manifest:
            rules = json.load(manifest).get("rules", [])
        if not isinstance(rules, list) or not rules:
            raise ValueError("manifest rules must be a non-empty list")
        return len(rules)
    except (OSError, ValueError, json.JSONDecodeError) as failure:
        raise RuntimeError(f"unable to read detection manifest: {failure}") from failure


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
    return login_token(gateway, user, password)


def ingest_endpoint_and_headers(gateway, mode, user_token):
    """Select the authenticated control-plane or collector data-plane boundary."""
    if mode != "e2e":
        return gateway + "/detect-web/api/v1/ingest/bulk", {
            "Authorization": "Bearer " + user_token,
            "Content-Type": "application/x-ndjson",
        }
    collector_token = (
        os.environ.get("BENCH_COLLECTOR_TOKEN", "").strip()
        or os.environ.get("PIPELINE_COLLECTOR_TOKEN", "").strip()
        or os.environ.get("SOCP_INGEST_TOKEN", "").strip()
        or os.environ.get("SOCP_VECTOR_TOKEN", "").strip()
        or "dev-vector-token"
    )
    collector_id = (
        os.environ.get("BENCH_COLLECTOR_ID", "").strip()
        or os.environ.get("PIPELINE_COLLECTOR_ID", "").strip()
        or "benchmark-pipeline"
    )
    endpoint = os.environ.get(
        "BENCH_INGEST_URL",
        "http://127.0.0.1:18081/search-config/api/v1/ingest",
    ).strip()
    return endpoint, {
        "Authorization": "Bearer " + collector_token,
        "X-SOCP-Collector": collector_id,
        "Content-Type": "application/x-ndjson",
    }


def unwrap(body):
    if isinstance(body, dict) and "data" in body:
        return body["data"]
    return body


def event_lines(run_id, start, end, mode, profile, ingest_at, alert_every):
    expected_alerts = 0
    rows = []
    for index in range(start, end):
        if mode == "e2e":
            should_alert = profile == "alert-heavy" or (
                profile == "realistic" and index % alert_every == 0)
            if should_alert:
                expected_alerts += 1
            # AUTH-PRIVESC is a single-event pattern. Include the run id in
            # the synthetic entity so neither the five-minute suppressor nor
            # Alert Web idempotency can collide with an earlier benchmark.
            # It intentionally is not a routable IP: this is a correctness
            # oracle for generated events, not a network-address fixture.
            src_ip = f"benchmark-{run_id}-{index}"
            rows.append({
                "eventId": f"benchmark-{run_id}-{index}",
                "source": "auth" if should_alert else "system",
                "host": f"benchmark-{run_id}-{index}",
                "message": "sudo: benchmark privilege escalation probe" if should_alert
                           else "benchmark heartbeat",
                "severity": "HIGH" if should_alert else "INFO",
                "timestamp": ingest_at,
                "socp_bench_ingest_time": ingest_at,
                "src_ip": src_ip,
                "user": "benchmark-user",
            })
        else:
            rows.append({
                "eventId": f"benchmark-{run_id}-{index}",
                "source": "auth",
                "host": "benchmark-host",
                "msg": "Failed password for benchmark-user",
                "severity": "HIGH",
                "timestamp": ingest_at,
                "fields": {"src_ip": "198.51.100.10"},
            })
    return "\n".join(json.dumps(row, ensure_ascii=False) for row in rows) + "\n", expected_alerts


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
    admin = None
    try:
        from kafka import KafkaConsumer
        from kafka.admin import KafkaAdminClient
        from kafka.structs import TopicPartition

        group = os.environ.get("BENCH_GROUP",
                               os.environ.get("SOCP_KAFKA_GROUP_ID", "socp-detect"))
        topic = os.environ.get("BENCH_TOPIC",
                               os.environ.get("SOCP_KAFKA_TOPIC", "socp-events"))
        # Offset inspection must not join the live Detection group.  A
        # diagnostic member would trigger a rebalance and invalidate the very
        # benchmark being measured.
        consumer = KafkaConsumer(
            bootstrap_servers=os.environ.get("BENCH_KAFKA", "127.0.0.1:9092"),
            group_id=None,
            enable_auto_commit=False,
            request_timeout_ms=3000,
        )
        admin = KafkaAdminClient(
            bootstrap_servers=os.environ.get("BENCH_KAFKA", "127.0.0.1:9092"),
            client_id="socp-benchmark-offset-inspector",
        )
        partitions = consumer.partitions_for_topic(topic) or set()
        tps = [TopicPartition(topic, p) for p in sorted(partitions)]
        if not tps:
            return None
        consumer.assign(tps)
        ends = consumer.end_offsets(tps)
        committed = admin.list_group_offsets({group: tps}).get(group, {})
        end_total = sum(ends.values())
        committed_total = sum(
            offset if isinstance(offset, int) else (offset.offset if offset else 0)
            for offset in committed.values()
        )
        return {
            "topic": topic,
            "group": group,
            "partitions": len(tps),
            "endOffset": end_total,
            "committedOffset": committed_total,
            "lag": max(0, end_total - committed_total),
        }
    except Exception:
        return None
    finally:
        if admin is not None:
            admin.close()
        if consumer is not None:
            consumer.close()


def wait_for_kafka_drain(timeout=30.0):
    """Return an offset snapshot after the production group reaches lag 0.

    Alert publication can complete a fraction before the contiguous Kafka
    commit catches up.  Taking the report snapshot immediately would make a
    healthy run look incomplete, so the report waits briefly for the durable
    transport frontier as well.
    """
    deadline = time.monotonic() + timeout
    last = None
    while time.monotonic() < deadline:
        last = kafka_snapshot()
        if last is not None and last.get("lag", 1) == 0:
            return last, round(timeout - max(0.0, deadline - time.monotonic()), 3)
        time.sleep(0.5)
    return last, round(timeout, 3)


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


def wait_for_detection_drain(gateway, token, timeout=30.0):
    """Wait until the Detection journal and in-memory queue report drained."""
    deadline = time.monotonic() + timeout
    last = None
    while time.monotonic() < deadline:
        last = detect_stats(gateway, token)
        if last is not None:
            pending = int(last.get("pendingEvents", 0) or 0)
            queue_load = float(last.get("queueLoad", 0.0) or 0.0)
            if pending == 0 and queue_load <= 0.0:
                return last, round(timeout - max(0.0, deadline - time.monotonic()), 3)
        time.sleep(0.5)
    return last, round(timeout, 3)


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


def performance_metric_urls():
    raw_detect = os.environ.get("BENCH_DETECTION_URLS") or os.environ.get(
        "DETECTION_INSTANCE_URLS", "http://127.0.0.1:18082")
    detect = [url.strip().rstrip("/") + "/detect-web/actuator/prometheus"
              for url in raw_detect.split(",") if url.strip()]
    search = os.environ.get("BENCH_SEARCH_URL") or os.environ.get(
        "SOCP_SEARCH_URL", "http://127.0.0.1:18081")
    search_url = search.strip().rstrip("/") + "/search-config/actuator/prometheus"
    alert = os.environ.get("BENCH_ALERT_URL", "http://127.0.0.1:18080").rstrip("/")
    return detect + [search_url, alert + "/alert-web/actuator/prometheus"]


def parse_prometheus_labels(raw):
    if not raw:
        return {}
    return {match.group(1): match.group(2).replace(r'\"', '"')
            for match in re.finditer(r'(\w+)="((?:\\.|[^"])*)"', raw)}


def performance_snapshot():
    """Aggregate low-cardinality SOCP performance meters across instances."""
    samples = {}
    reachable = []
    for url in performance_metric_urls():
        try:
            with urllib.request.urlopen(url, timeout=5) as response:
                payload = response.read().decode("utf-8", errors="replace")
            reachable.append(url)
        except Exception:
            continue
        for line in payload.splitlines():
            if not line or line.startswith("#") or " " not in line:
                continue
            sample, raw_value = line.rsplit(" ", 1)
            name, _, raw_labels = sample.partition("{")
            if not (name.startswith("socp_detection_")
                    or name.startswith("socp_alert_")
                    or name.startswith("socp_opensearch_")):
                continue
            labels = parse_prometheus_labels(raw_labels[:-1] if raw_labels else "")
            key = (name, tuple(sorted(labels.items())))
            try:
                samples[key] = samples.get(key, 0.0) + float(raw_value)
            except ValueError:
                continue
    return {"reachable": reachable, "samples": samples}


def snapshot_delta(before, after):
    keys = set((before or {}).get("samples", {})) | set((after or {}).get("samples", {}))
    return {key: max(0.0, (after or {}).get("samples", {}).get(key, 0.0)
                           - (before or {}).get("samples", {}).get(key, 0.0))
            for key in keys}


def histogram_quantile(buckets, quantile):
    ordered = sorted((float(bound), count) for bound, count in buckets.items())
    if not ordered or ordered[-1][1] <= 0:
        return 0.0
    wanted = ordered[-1][1] * quantile
    previous_bound = 0.0
    previous_count = 0.0
    for bound, count in ordered:
        if count >= wanted:
            if math.isinf(bound) or count <= previous_count:
                return previous_bound
            fraction = (wanted - previous_count) / (count - previous_count)
            return previous_bound + (bound - previous_bound) * fraction
        previous_bound, previous_count = bound, count
    return previous_bound


def summarize_performance_metrics(before, after, event_count, alert_count,
                                  committed_source_offsets=None):
    delta = snapshot_delta(before, after)
    histograms = {}
    transactions = {}
    indexer_records = {}
    for (name, labels_tuple), value in delta.items():
        labels = dict(labels_tuple)
        if name.endswith("_bucket") and "stage" in labels and "le" in labels:
            base = name[:-7]
            bound = float("inf") if labels["le"] == "+Inf" else float(labels["le"])
            histograms.setdefault((base, labels["stage"]), {})[bound] = value
        if name.endswith("_total") and ("db_transactions" in name):
            scope = labels.get("scope", "unknown")
            operation = labels.get("operation", "unknown")
            owner = "detection" if name.startswith("socp_detection_") else "alertWeb"
            transactions[f"{owner}.{scope}.{operation}"] = round(value, 3)
        if name == "socp_opensearch_indexer_records_total":
            stage = labels.get("stage", "unknown")
            indexer_records[stage] = round(value, 3)

    stages = {}
    for (base, stage), buckets in histograms.items():
        key = ("event." if "detection_event" in base else
               "detectionAlert." if "detection_alert" in base else "alert.") + stage
        stages[key] = {
            "count": round(max(buckets.values(), default=0.0), 3),
            "p50Ms": round(histogram_quantile(buckets, 0.50) * 1000, 3),
            "p95Ms": round(histogram_quantile(buckets, 0.95) * 1000, 3),
            "p99Ms": round(histogram_quantile(buckets, 0.99) * 1000, 3),
        }
    detection_tx = sum(value for key, value in transactions.items()
                       if key.startswith("detection.event."))
    outbox_tx = sum(value for key, value in transactions.items()
                    if key.startswith("detection.alert."))
    alert_web_tx = transactions.get("alertWeb.alert.create", 0.0)
    consume = indexer_records.get("consume", 0.0)
    write = indexer_records.get("write", 0.0)
    failed = indexer_records.get("fail", 0.0)
    dropped = indexer_records.get("drop", 0.0)
    dlq = indexer_records.get("dlq", 0.0)
    dlq_failed = indexer_records.get("dlq_failed", 0.0)
    committed = indexer_records.get("commit", 0.0)
    commit_failed = indexer_records.get("commit_failed", 0.0)
    return {
        "metricEndpoints": (after or {}).get("reachable", []),
        "stages": stages,
        "transactions": transactions,
        "openSearchIndexer": {
            "records": indexer_records,
            "reconciliation": {
                # These counters describe processing attempts. Retries can
                # legitimately make them larger than the run's unique input.
                "attemptCounters": {
                    "consumed": consume,
                    "writeAcknowledged": write,
                    "writeFailed": failed,
                    "dropped": dropped,
                    "dlqAcknowledged": dlq,
                    "dlqFailed": dlq_failed,
                    "commitFailed": commit_failed,
                },
                # The Kafka group frontier is the authoritative unique source
                # offset count for an isolated benchmark interval. The commit
                # metric remains useful evidence but is not used to derive a
                # loss gap from retry-inflated attempt counters.
                "uniqueSourceOffsets": {
                    "accepted": event_count,
                    "committed": committed_source_offsets,
                    "gap": (event_count - committed_source_offsets
                            if committed_source_offsets is not None else None),
                },
                "commitAcknowledgedRecords": committed,
            },
        },
        "ratios": {
            "detectionDbTransactionsPerUniqueEvent": round(detection_tx / event_count, 3)
            if event_count else 0,
            "detectionOutboxDbTransactionsPerAlert": round(outbox_tx / alert_count, 3)
            if alert_count else 0,
            "alertWebDbTransactionsPerAlert": round(alert_web_tx / alert_count, 3)
            if alert_count else 0,
        },
    }
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


def wait_for_alert_stability(gateway, token, timeout=30.0, stable_polls=3):
    """Wait until the durable Alert row count stops changing after Detection drains."""
    deadline = time.monotonic() + timeout
    last = None
    stable = 0
    while time.monotonic() < deadline:
        current = alert_total(gateway, token)
        if current is not None and current == last:
            stable += 1
            if stable >= stable_polls:
                return current, round(timeout - max(0.0, deadline - time.monotonic()), 3)
        else:
            stable = 0
            last = current
        time.sleep(1)
    return last, round(timeout, 3)


def run_alerts(gateway, token, run_id, expected, max_pages=10):
    """Return only alerts whose trigger event belongs to this benchmark run.

    A delayed alert from an older stateful evaluation can become durable while
    a new benchmark is running. Global row-count deltas still help detect a
    drain, but they are not a valid run-level latency or correctness oracle.
    """
    prefix = f"benchmark-{run_id}-"
    matched = []
    size = 500
    # Alert-heavy runs can legitimately produce more than the historical ten
    # pages. Keep the run-scoped oracle complete instead of turning pagination
    # into a false alert shortfall.
    max_pages = max(max_pages, math.ceil(max(1, expected) / size) + 1)
    for page in range(1, max_pages + 1):
        status, body, _ = request(
            gateway + ("/alert-web/api/alarms?page=%d&size=%d"
                       "&sort=alertCreatedAt&order=descending" % (page, size)),
            headers={"Authorization": "Bearer " + token}, timeout=30)
        data = unwrap(body)
        if status != 200 or not isinstance(data, dict):
            break
        items = data.get("items", [])
        if not isinstance(items, list) or not items:
            break
        matched.extend(
            alarm for alarm in items
            if isinstance(alarm, dict)
            and str(alarm.get("triggerEventId", "")).startswith(prefix)
        )
        if len(matched) >= expected or len(items) < size:
            break
    return matched


def measured_latency_ms(alarms):
    values = []
    for alarm in alarms:
        raw = alarm.get("processingLatencyMs") if isinstance(alarm, dict) else None
        if isinstance(raw, (int, float)) and raw >= 0:
            values.append(float(raw))
    return values


def durable_alert_latency_ms(alarms):
    """Measure ingress until the Alert Web row became durable.

    ``processingLatencyMs`` is produced by Detection before its alert outbox
    hand-off.  Alert Web's inherited ``createdAt`` timestamp includes that
    durable delivery queue and is therefore the end-to-end pipeline metric.
    """
    values = []
    for alarm in alarms:
        if not isinstance(alarm, dict):
            continue
        started = alarm.get("triggerIngestedAt")
        completed = alarm.get("createdAt")
        if not started or not completed:
            continue
        try:
            start_time = datetime.fromisoformat(str(started).replace("Z", "+00:00"))
            completed_time = datetime.fromisoformat(str(completed).replace("Z", "+00:00"))
            elapsed = (completed_time - start_time).total_seconds() * 1000
            if elapsed >= 0:
                values.append(elapsed)
        except (TypeError, ValueError):
            continue
    return values


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
    global LAST_BENCHMARK_CONTEXT
    LAST_BENCHMARK_CONTEXT = None
    parser = argparse.ArgumentParser(description="SOCP single-node ingest baseline")
    parser.add_argument("--count", type=int, default=100)
    parser.add_argument("--batch-size", type=int, default=100)
    parser.add_argument("--mode", choices=("bulk", "e2e"), default="bulk")
    parser.add_argument("--profile", choices=("realistic", "alert-heavy"), default="realistic",
                        help="e2e workload profile: low hit-rate realistic or alert-heavy")
    parser.add_argument("--alert-every", type=int, default=10,
                        help="realistic profile emits one pattern alert every N events")
    parser.add_argument("--gateway", default=os.environ.get("BENCH_GATEWAY", "http://127.0.0.1:18092"))
    parser.add_argument("--timeout", type=float, default=120.0,
                        help="seconds to wait for e2e alerts")
    parser.add_argument("--output", help="optional JSON report path")
    parser.add_argument("--label", default="baseline",
                        help="human-readable run label stored in the report")
    parser.add_argument("--instances", type=int, default=1,
                        help="number of Detection instances used for this run")
    parser.add_argument("--rules", type=int, default=None,
                        help="assertion for the manifest rule count (auto-read by default)")
    parser.add_argument("--offered-eps", type=float, default=None,
                        help="pace e2e ingestion at this offered load instead of burst mode")
    parser.add_argument("--duration", type=float, default=180.0,
                        help="steady-state offered-load duration in seconds")
    args = parser.parse_args()
    manifest_rules = manifest_rule_count()
    if args.rules is not None and args.rules != manifest_rules:
        parser.error(f"--rules={args.rules} does not match manifest rule count {manifest_rules}")
    if args.offered_eps is not None:
        if args.mode != "e2e" or args.offered_eps <= 0 or args.duration <= 0:
            parser.error("--offered-eps requires e2e mode and positive rate/duration")
        args.count = max(1, math.ceil(args.offered_eps * args.duration))
    if args.count <= 0 or args.batch_size <= 0 or args.alert_every <= 0:
        parser.error("--count, --batch-size, and --alert-every must be positive")

    gateway = args.gateway.rstrip("/")
    token = login(gateway)
    ingest_target, ingest_headers = ingest_endpoint_and_headers(
        gateway, args.mode, token)
    profile = machine_profile()
    stats_before = detect_stats(gateway, token)
    runtime_before = optional_prometheus_snapshot()
    performance_before = performance_snapshot()
    task_id = choose_ingest_task(gateway, token) if args.mode == "e2e" else None
    baseline_alerts = alert_total(gateway, token) if args.mode == "e2e" else None
    kafka_before = kafka_snapshot() if args.mode == "e2e" else None
    os_before = optional_opensearch_count() if args.mode == "e2e" else None
    ck_before = optional_clickhouse_count() if args.mode == "e2e" else None

    run_id = uuid.uuid4().hex[:10]
    accepted = rejected = forwarded = 0
    expected_alerts = 0
    latencies = []
    lag_during_load = []
    next_lag_sample = 0.0
    started = time.perf_counter()

    for start in range(0, args.count, args.batch_size):
        if args.offered_eps:
            target_elapsed = start / args.offered_eps
            remaining = target_elapsed - (time.perf_counter() - started)
            if remaining > 0:
                time.sleep(remaining)
        end = min(start + args.batch_size, args.count)
        batch_ingest_at = datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")
        body, batch_expected_alerts = event_lines(
            run_id, start, end, args.mode, args.profile, batch_ingest_at, args.alert_every)
        status, body, latency = request(
            ingest_target, "POST", body, ingest_headers,
            timeout=60)
        latencies.append(latency)
        data = unwrap(body)
        if status != 200 or not isinstance(data, dict):
            raise RuntimeError(f"{args.mode} ingest failed with HTTP {status}: {body}")
        accepted += int(data.get("accepted", 0))
        rejected += int(data.get("rejected", data.get("skipped", 0)))
        forwarded += int(data.get("forwarded", 0))
        expected_alerts += batch_expected_alerts
        elapsed_load = time.perf_counter() - started
        if args.offered_eps and elapsed_load >= next_lag_sample:
            snapshot = kafka_snapshot()
            if snapshot is not None:
                lag_during_load.append({"elapsedSeconds": round(elapsed_load, 3),
                                        "lag": snapshot.get("lag", 0)})
            next_lag_sample = elapsed_load + 1.0

    elapsed = time.perf_counter() - started
    alerts_after = None
    alert_wait = None
    if args.mode == "e2e":
        wait_started = time.perf_counter()
        alerts_after = wait_for_alerts(
            gateway, token, expected_alerts, baseline_alerts or 0, args.timeout)
        alert_wait = time.perf_counter() - wait_started

    detection_after, detection_drain_wait = wait_for_detection_drain(
        gateway, token, timeout=args.timeout)
    report = {
        "status": "passed",
        "runId": run_id,
        "label": args.label,
        "recordedAt": datetime.now(timezone.utc).isoformat(),
        "machine": profile,
        "detectionInstances": args.instances,
        "configuredRules": manifest_rules,
        "rulesAssertion": args.rules if args.rules is not None else manifest_rules,
        "mode": args.mode,
        "requested": args.count,
        "batchSize": args.batch_size,
        "batches": len(latencies),
        "accepted": accepted,
        "rejected": rejected,
        "forwarded": forwarded,
        "elapsedSeconds": round(elapsed, 3),
        "eventsPerSecond": round(args.count / elapsed if elapsed else 0, 2),
        "profile": args.profile if args.mode == "e2e" else None,
        "alertEvery": args.alert_every if args.mode == "e2e" else None,
        "batchLatencyMs": {
            "avg": round(statistics.mean(latencies), 2) if latencies else 0,
            "p50": round(percentile(latencies, 0.50), 2),
            "p95": round(percentile(latencies, 0.95), 2),
            "p99": round(percentile(latencies, 0.99), 2),
            "max": round(max(latencies), 2) if latencies else 0,
        },
        "detectionStatsBefore": stats_before,
        "detectionStatsAfter": detection_after,
        "detectionDrainWaitSeconds": detection_drain_wait,
        "runtimeMetricsBefore": runtime_before,
        "runtimeMetricsAfter": optional_prometheus_snapshot(),
        "loadShape": "steady-state" if args.offered_eps else "burst-drain",
        "offeredEventsPerSecond": args.offered_eps,
        "offeredDurationSeconds": args.duration if args.offered_eps else None,
    }
    if args.mode == "e2e":
        report["ingestTaskId"] = task_id
        report["baselineAlerts"] = baseline_alerts
        report["expectedAlerts"] = expected_alerts
        report["alertsAfter"] = alerts_after
        report["alertsObserved"] = ((alerts_after or 0) - (baseline_alerts or 0))
        report["alertShortfall"] = max(0, expected_alerts - report["alertsObserved"])
        report["alertWaitSeconds"] = round(alert_wait or 0, 3)
        report["endToEndSeconds"] = round(elapsed + (alert_wait or 0), 3)
        report["endToEndEventsPerSecond"] = round(
            accepted / (elapsed + (alert_wait or 0))
            if accepted and elapsed + (alert_wait or 0) else 0, 2)
        report["kafkaBefore"] = kafka_before
        kafka_after, kafka_drain_wait = wait_for_kafka_drain(timeout=args.timeout)
        report["kafkaAfter"] = kafka_after
        report["kafkaDrainWaitSeconds"] = kafka_drain_wait
        final_alerts, alert_stability_wait = wait_for_alert_stability(
            gateway, token, timeout=args.timeout)
        report["alertStabilityWaitSeconds"] = alert_stability_wait
        report["alertsAfterFinalDrain"] = final_alerts
        report["alertsObservedFinal"] = ((final_alerts or 0) - (baseline_alerts or 0))
        report["openSearchBefore"] = os_before
        report["openSearchAfter"] = optional_opensearch_count()
        report["clickHouseBefore"] = ck_before
        report["clickHouseAfter"] = optional_clickhouse_count()
        sampled = run_alerts(gateway, token, run_id, max(expected_alerts, 1))
        report["runAlertsObserved"] = len(sampled)
        report["nonRunAlertsObserved"] = max(
            0, report["alertsObservedFinal"] - report["runAlertsObserved"])
        report["alertShortfall"] = max(0, expected_alerts - report["runAlertsObserved"])
        detection_latency = measured_latency_ms(sampled)
        durable_latency = durable_alert_latency_ms(sampled)
        report["detectionLatencyDefinition"] = "alertCreatedAt - triggerIngestedAt"
        report["detectionLatencySampleCount"] = len(detection_latency)
        report["detectionLatencyMs"] = {
            "p50": round(percentile(detection_latency, 0.50), 2),
            "p95": round(percentile(detection_latency, 0.95), 2),
            "p99": round(percentile(detection_latency, 0.99), 2),
            "max": round(max(detection_latency), 2) if detection_latency else 0,
        }
        report["durableAlertLatencyDefinition"] = "Alert Web createdAt - triggerIngestedAt"
        report["durableAlertLatencySampleCount"] = len(durable_latency)
        report["durableAlertLatencyMs"] = {
            "p50": round(percentile(durable_latency, 0.50), 2),
            "p95": round(percentile(durable_latency, 0.95), 2),
            "p99": round(percentile(durable_latency, 0.99), 2),
            "max": round(max(durable_latency), 2) if durable_latency else 0,
        }
        performance_after = performance_snapshot()
        committed_source_offsets = None
        if kafka_before is not None and kafka_after is not None \
                and kafka_before.get("topic") == kafka_after.get("topic") \
                and kafka_before.get("group") == kafka_after.get("group") \
                and kafka_before.get("partitions") == kafka_after.get("partitions"):
            committed_source_offsets = max(
                0, kafka_after.get("committedOffset", 0)
                - kafka_before.get("committedOffset", 0))
        report["performanceMetrics"] = summarize_performance_metrics(
            performance_before, performance_after, accepted,
            report["runAlertsObserved"], committed_source_offsets)
        if args.offered_eps:
            lags = [sample["lag"] for sample in lag_during_load]
            lag_growth = (lags[-1] - lags[0]) if len(lags) >= 2 else 0
            report["steadyState"] = {
                "lagSamples": lag_during_load,
                "peakLag": max(lags, default=0),
                "lagGrowthDuringLoad": lag_growth,
                "lagStable": lag_growth <= max(10, args.offered_eps * 0.1),
            }

    LAST_BENCHMARK_CONTEXT = report

    if accepted + rejected != args.count:
        raise SystemExit("result count does not match requested event count")
    if args.mode == "e2e" and (alerts_after or 0) < (baseline_alerts or 0) + expected_alerts:
        raise SystemExit("e2e timeout: expected alert count did not catch up")
    if args.mode == "e2e" and report.get("alertShortfall", 0) > 0:
        raise SystemExit(
            "e2e incomplete: run-scoped durable alerts short by "
            f"{report['alertShortfall']}")
    if args.mode == "e2e" and report.get("kafkaAfter") is not None \
            and report["kafkaAfter"].get("lag", 0) != 0:
        raise SystemExit("e2e timeout: Kafka consumer lag did not drain to zero")
    completed = (report.get("performanceMetrics", {}).get("stages", {})
                 .get("event.consumer_to_durable", {}).get("count", 0))
    if args.mode == "e2e" and completed < accepted:
        raise SystemExit(f"e2e incomplete: durable event metrics {completed} < accepted {accepted}")

    # Write a passing report only after all correctness assertions have
    # succeeded.  If one of the checks above raises, the outer handler writes
    # the in-memory report to the separate .failed.json sidecar.
    print("SOCP %s baseline" % ("end-to-end" if args.mode == "e2e" else "bulk ingest"))
    print(json.dumps(report, ensure_ascii=False, indent=2))
    if args.output:
        os.makedirs(os.path.dirname(os.path.abspath(args.output)), exist_ok=True)
        with open(args.output, "w", encoding="utf-8") as output:
            json.dump(report, output, ensure_ascii=False, indent=2)
            output.write("\n")


def _failure_output_path(argv):
    output = None
    for index, value in enumerate(argv):
        if value == "--output" and index + 1 < len(argv):
            output = argv[index + 1]
        elif value.startswith("--output="):
            output = value.split("=", 1)[1]
    if output:
        target = Path(output)
        return target.with_name(target.stem + ".failed" + target.suffix)
    return Path(".cache") / "benchmark" / (
        "failure-" + datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + ".json")


def write_failure_evidence(failure):
    """Persist a failure report even when the workload aborts mid-run.

    A successful benchmark already writes its full before/after snapshot.  A
    transport timeout or alert shortfall used to lose all evidence because the
    exception escaped before that final write.  The failed sidecar is kept
    separate from the requested output so a later rerun cannot mistake it for
    a successful baseline.
    """
    path = _failure_output_path(sys.argv[1:])
    path.parent.mkdir(parents=True, exist_ok=True)
    report = {
        "status": "failed",
        "recordedAt": datetime.now(timezone.utc).isoformat(),
        "argv": sys.argv[1:],
        "machine": machine_profile(),
        "failureType": failure.__class__.__name__,
        "failure": str(failure),
        "traceback": traceback.format_exc(),
    }
    if LAST_BENCHMARK_CONTEXT is not None:
        report["partialReport"] = LAST_BENCHMARK_CONTEXT
    path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print("benchmark failure evidence: %s" % path)


if __name__ == "__main__":
    try:
        main()
    except BaseException as failure:
        try:
            write_failure_evidence(failure)
        except Exception as evidence_failure:
            print("unable to write benchmark failure evidence: %s" % evidence_failure,
                  file=sys.stderr)
        raise
