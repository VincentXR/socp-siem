#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Measure the detect bulk-ingest boundary without making a production claim.

Examples:
  python build/benchmark-pipeline.py --count 100
  python build/benchmark-pipeline.py --count 10000 --batch-size 200

The script prints only generic counters and timings. It does not write a
report file or expose local machine details. The result measures the HTTP bulk
ingest boundary; use verify-pipeline.py for end-to-end persistence evidence.
"""
import argparse
import json
import os
import time
import urllib.error
import urllib.request
import uuid


def request(url, method="GET", body=None, headers=None, timeout=30):
    data = None if body is None else body.encode()
    req = urllib.request.Request(url, data=data, method=method,
                                 headers=headers or {})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as response:
            raw = response.read().decode()
            return response.status, json.loads(raw) if raw else {}
    except urllib.error.HTTPError as error:
        try:
            return error.code, json.loads(error.read().decode())
        except Exception:
            return error.code, {}


def login(gateway):
    user = os.environ.get("BENCH_USER", "demo")
    password = os.environ.get("BENCH_PASS", "demo123")
    payload = json.dumps({"username": user, "password": password})
    status, body = request(
        gateway + "/auth/login", "POST", payload,
        {"Content-Type": "application/json"}, timeout=15)
    if status != 200 or not body.get("token"):
        raise RuntimeError("login failed; check the local gateway and test credentials")
    return body["token"]


def batch_lines(run_id, start, end):
    return "\n".join(json.dumps({
        "eventId": f"benchmark-{run_id}-{index}",
        "source": "auth",
        "host": "benchmark-host",
        "msg": "Failed password for benchmark-user",
        "severity": "HIGH",
        "timestamp": "2026-01-01T00:00:00Z",
    }) for index in range(start, end)) + "\n"


def main():
    parser = argparse.ArgumentParser(description="SOCP bulk ingest baseline")
    parser.add_argument("--count", type=int, default=100)
    parser.add_argument("--batch-size", type=int, default=100)
    parser.add_argument("--gateway", default=os.environ.get(
        "BENCH_GATEWAY", "http://127.0.0.1:18092"))
    args = parser.parse_args()
    if args.count <= 0 or args.batch_size <= 0:
        parser.error("--count and --batch-size must be positive")

    gateway = args.gateway.rstrip("/")
    token = login(gateway)
    run_id = uuid.uuid4().hex[:10]
    accepted = rejected = 0
    latencies = []
    started = time.perf_counter()

    for start in range(0, args.count, args.batch_size):
        end = min(start + args.batch_size, args.count)
        began = time.perf_counter()
        status, body = request(
            gateway + "/detect-web/api/v1/ingest/bulk", "POST",
            batch_lines(run_id, start, end),
            {"Authorization": "Bearer " + token,
             "Content-Type": "application/x-ndjson"})
        latencies.append((time.perf_counter() - began) * 1000)
        data = body.get("data", body) if isinstance(body, dict) else {}
        if status != 200:
            raise RuntimeError(f"bulk ingest failed with HTTP {status}")
        accepted += int(data.get("accepted", 0))
        rejected += int(data.get("rejected", 0))

    elapsed = time.perf_counter() - started
    rate = args.count / elapsed if elapsed else 0
    average = sum(latencies) / len(latencies) if latencies else 0
    maximum = max(latencies) if latencies else 0
    print("SOCP bulk ingest baseline")
    print(f"events={args.count} batches={len(latencies)} accepted={accepted} rejected={rejected}")
    print(f"elapsed_seconds={elapsed:.3f} events_per_second={rate:.2f}")
    print(f"batch_latency_ms_avg={average:.2f} batch_latency_ms_max={maximum:.2f}")
    if accepted + rejected != args.count:
        raise SystemExit("result count does not match requested event count")


if __name__ == "__main__":
    main()
