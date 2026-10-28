#!/usr/bin/env python3
"""Bounded sustained ingestion/search verification on a disposable loopback stack.

Requires --allow-local-fixture and existing real middleware/collector credentials.
Never changes durability settings, retries failed requests, or hides failures.
Local HTTPS OpenSearch uses the self-signed fixture convention of verify-pipeline.
Artifacts contain generated event identities and metrics, never auth headers.
"""
import ssl
import argparse
import base64
import collections
import concurrent.futures
import datetime
import hashlib
import json
import math
import os
import pathlib
import shutil
import sqlite3
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "build"))
from auth_client import login_token
from ports import SERVICES, health_url, base_url

P = ROOT / ".cache/load-soak"
GW = OS = INGEST = COLLECTOR = CREDENTIAL = METRICS = ""
TOKEN = ""
token_at = 0
lock = threading.Lock()
stop = threading.Event()
guard = threading.Event()
guard_reason = []
kafka_seen = collections.Counter()
kafka_bad = []
records = []
phase = "setup"
queries = []
samples = []
saturation = threading.Event()
saturation_reason = []
warm_id = None
query_ids = []
query_serial = 0
started_at = 0
ip_base = 0
RUN = ""
OUT = None
db = None
memory_limit = 0
working_set_limit = 0


def require_loopback(url):
    parsed = urllib.parse.urlsplit(url)
    if (
        parsed.scheme not in ("http", "https", "tcp")
        or parsed.hostname not in ("localhost", "127.0.0.1", "::1")
        or parsed.username
        or parsed.password
    ):
        raise ValueError(
            "load verification requires loopback endpoints without URL credentials"
        )


class LoopbackRedirectHandler(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, new_url):
        require_loopback(new_url)
        return super().redirect_request(request, fp, code, message, headers, new_url)


def memory_snapshot(cgroup=pathlib.Path("/sys/fs/cgroup"), proc=pathlib.Path("/proc")):
    """Use delegated cgroup counters, or whole-VM counters on a hosted runner."""
    info = {
        line.split(":", 1)[0]: int(line.split()[1]) * 1024
        for line in (proc / "meminfo").read_text().splitlines()
        if line.split()[-1] == "kB"
    }
    maximum = (
        (cgroup / "memory.max").read_text().strip()
        if (cgroup / "memory.max").exists()
        else "max"
    )
    limit = (
        min(int(maximum), info["MemTotal"]) if maximum != "max" else info["MemTotal"]
    )
    if (cgroup / "memory.current").exists():
        current = int((cgroup / "memory.current").read_text())
        stat = {
            k: int(v)
            for k, v in (
                line.split()
                for line in (cgroup / "memory.stat").read_text().splitlines()
            )
        }
        events = dict(
            line.split() for line in (cgroup / "memory.events").read_text().splitlines()
        )
        return {
            "memorySource": "delegated-cgroup",
            "memoryLimitBytes": limit,
            "cgroupMemoryBytes": current,
            "workingSetBytes": current - stat["inactive_file"],
            "memoryEvents": events,
            "memoryStat": {
                k: v
                for k, v in stat.items()
                if k
                in ("anon", "file", "inactive_file", "active_file", "shmem", "kernel")
            },
        }
    # Root cgroups on VMs need not expose memory.current/max/events. MemAvailable
    # accounts for reclaimable memory; vmstat retains the kernel's OOM counter.
    vmstat = dict(line.split() for line in (proc / "vmstat").read_text().splitlines())
    return {
        "memorySource": "whole-vm-proc",
        "memoryLimitBytes": limit,
        "cgroupMemoryBytes": None,
        "workingSetBytes": info["MemTotal"] - info["MemAvailable"],
        "memoryStat": {},
        "memoryEvents": {"oom_kill": vmstat["oom_kill"]},
    }


def cpu_counters():
    path = pathlib.Path("/sys/fs/cgroup/cpu.stat")
    values = (
        dict(line.split() for line in path.read_text().splitlines())
        if path.exists()
        else {}
    )
    if "usage_usec" in values:
        return "delegated-cgroup", values
    ticks = list(
        map(int, pathlib.Path("/proc/stat").read_text().splitlines()[0].split()[1:])
    )
    active = sum(ticks[i] for i in (0, 1, 2, 5, 6))
    return "whole-vm-proc", {
        "usage_usec": str(active * 1_000_000 // os.sysconf("SC_CLK_TCK"))
    }


def initialize(args):
    global P, GW, OS, INGEST, COLLECTOR, CREDENTIAL, METRICS, started_at, ip_base, RUN, OUT, db, memory_limit, working_set_limit
    if not args.allow_local_fixture:
        raise ValueError("explicit --allow-local-fixture is required")
    if not 60 <= args.seconds <= 3600 or not 1 <= args.eps <= 25:
        raise ValueError("duration must be 60..3600 seconds and EPS 1..25")
    GW = os.environ.get("PIPELINE_GATEWAY", "http://127.0.0.1:18092").rstrip("/")
    OS = os.environ.get("PIPELINE_OS", "https://localhost:9200").rstrip("/")
    INGEST = os.environ.get(
        "PIPELINE_INGEST_URL", "http://127.0.0.1:18081/search-config/api/v1/ingest"
    )
    for url in [
        GW,
        OS,
        INGEST,
        os.environ.get("PIPELINE_CK", "http://localhost:8123"),
        *[health_url(name) for name in SERVICES],
    ]:
        require_loopback(url)
    for address in os.environ.get("PIPELINE_KAFKA", "localhost:9092").split(","):
        require_loopback("tcp://" + address)
    COLLECTOR = os.environ["PIPELINE_COLLECTOR_ID"]
    CREDENTIAL = os.environ["PIPELINE_COLLECTOR_TOKEN"]
    METRICS = os.environ["SOCP_SECURITY_METRICS_TOKEN"]
    if (
        not COLLECTOR
        or not CREDENTIAL
        or (not METRICS)
        or (not os.environ.get("PIPELINE_CK_AUTH"))
    ):
        raise ValueError("explicit fixture credentials are required")
    if OS.startswith("https://") and (not os.environ.get("PIPELINE_OS_AUTH")):
        raise ValueError("explicit OpenSearch fixture authentication is required")
    P = args.report_dir.resolve()
    if not P.is_relative_to(ROOT):
        raise ValueError("report directory must remain inside the repository")
    P.mkdir(parents=True, exist_ok=True)
    started_at = time.time()
    ip_base = int(uuid.uuid4().hex[:8], 16) % 60 * 1000
    used = {
        json.loads(p.read_text()).get("thresholdIpBase")
        for p in P.glob("load-*/config.json")
    }
    available = [
        (ip_base + n * 1000) % 60000
        for n in range(60)
        if (ip_base + n * 1000) % 60000 not in used
    ]
    if not available:
        raise ValueError(
            "all threshold fixture address blocks were used in this report directory"
        )
    ip_base = available[0]
    RUN = (
        "load-"
        + datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%d%H%M%S")
        + "-"
        + uuid.uuid4().hex[:6]
    )
    OUT = P / RUN
    OUT.mkdir()
    db = sqlite3.connect(OUT / "ledger.sqlite")
    db.execute(
        "create table events(id text primary key, phase text, n integer, kind text, host text, msg text, src_ip text, ack integer default 0, submitted real, received real)"
    )
    memory_limit = memory_snapshot()["memoryLimitBytes"]
    working_set_limit = int(memory_limit * 0.875)


def record_acknowledgement(connection, result):
    """A replay acknowledgement survives a failed primary response in either order."""
    if result["ok"]:
        connection.executemany(
            "update events set ack=1,received=case when ack=0 then ? else received end where id=?",
            [(result["received"], ident) for ident in result["ids"]],
        )


def phase_passed(result, expected_unique, guard_active):
    return (
        not result["requestErrors"]
        and (not result["queries"]["errors"])
        and (not guard_active)
        and result["reconciliation"]["pass"]
        and (result["acceptedUnique"] >= expected_unique * 0.98)
        and (result["elapsedSeconds"] >= result["requestedSeconds"])
        and (result["name"] == "warmup" or result["capacityStable"])
    )


def http(url, body=None, headers=None, method=None, timeout=15):
    raw = (
        None
        if body is None
        else body if isinstance(body, bytes) else json.dumps(body).encode()
    )
    request_headers = {"Content-Type": "application/json"} | (headers or {})
    context = None
    if url.startswith(OS + "/"):
        if os.environ.get("PIPELINE_OS_AUTH"):
            request_headers["Authorization"] = (
                "Basic "
                + base64.b64encode(os.environ["PIPELINE_OS_AUTH"].encode()).decode()
            )
        if OS.startswith("https://"):
            # Explicitly opted-in loopback fixture with a self-signed certificate.
            context = ssl._create_unverified_context()
    req = urllib.request.Request(
        url,
        data=raw,
        method=method or ("POST" if raw is not None else "GET"),
        headers=request_headers,
    )
    try:
        opener = urllib.request.build_opener(
            LoopbackRedirectHandler(), urllib.request.HTTPSHandler(context=context)
        )
        with opener.open(req, timeout=timeout) as r:
            data = r.read(32 * 1024 * 1024 + 1)
            if len(data) > 32 * 1024 * 1024:
                raise RuntimeError("HTTP response exceeded 32 MiB")
            try:
                return (r.status, json.loads(data))
            except ValueError:
                return (r.status, data.decode(errors="replace"))
    except urllib.error.HTTPError as e:
        with e:
            return (e.code, {"error": "HTTP " + str(e.code)})


def api(path):
    status, data = http(GW + path, headers={"Authorization": "Bearer " + TOKEN})
    if status != 200 or not isinstance(data, dict) or data.get("code") != 0:
        raise RuntimeError("API HTTP " + str(status) + " " + path.split("?")[0])
    return data["data"]


def sql(database, query):
    r = subprocess.run(
        [
            "docker",
            "exec",
            "socp-postgres",
            "psql",
            "-U",
            "socp",
            "-d",
            database,
            "-tAc",
            query,
        ],
        capture_output=True,
        text=True,
        timeout=30,
    )
    if r.returncode:
        raise RuntimeError("PostgreSQL read failed " + r.stderr[-350:])
    return r.stdout.strip()


def percentile(values, p):
    return sorted(values)[max(0, math.ceil(len(values) * p) - 1)] if values else None


def id_for(n):
    return f"{RUN}-{n:08d}"


def event(n):
    ident = id_for(n)
    kind = "pattern" if n % 100 == 0 else "threshold" if n % 100 >= 95 else "benign"
    host = f"{RUN}-b-{n % 32:02d}" if kind == "benign" else f"{RUN}-a-{n:08d}"
    source = {"pattern": "auth", "threshold": "firewall", "benign": "loadtest"}[kind]
    msg = {
        "pattern": f"sudo: load-validation {ident} USER=root COMMAND=/bin/true",
        "threshold": f"RDP connection to 3389 {ident}",
        "benign": f"bounded telemetry heartbeat {ident}",
    }[kind]
    ip = (
        f"198.18.{(ip_base + n // 100) // 256}.{(ip_base + n // 100) % 256}"
        if kind == "threshold"
        else None
    )
    padding = "".join(
        (hashlib.sha256(f"{ident}:{j}".encode()).hexdigest() for j in range(11))
    )
    e = {
        "eventId": ident,
        "source": source,
        "host": host,
        "severity": "INFO" if kind == "benign" else "HIGH",
        "message": msg,
        "payload": padding,
    }
    if ip:
        e["src_ip"] = ip
    return (e, kind)


def alert_id(rule, entity, ids):
    return str(
        uuid.UUID(
            bytes=hashlib.md5(
                "|".join(["default", rule, entity, *sorted(ids)]).encode()
            ).digest(),
            version=3,
        )
    )


def send(batch, duplicate=False):
    start = time.monotonic()
    body = (
        "\n".join((json.dumps(e, separators=(",", ":")) for e in batch)) + "\n"
    ).encode()
    try:
        code, result = http(
            INGEST,
            body,
            {
                "Authorization": "Bearer " + CREDENTIAL,
                "X-SOCP-Collector": COLLECTOR,
                "Content-Type": "application/x-ndjson",
            },
            timeout=30,
        )
        data = result.get("data", {}) if isinstance(result, dict) else {}
        ok = (
            code == 200
            and result.get("code") == 0
            and (data.get("acknowledged") == len(batch))
            and (not data.get("parseFailed"))
            and (not data.get("quarantined"))
        )
        return {
            "ids": [e["eventId"] for e in batch],
            "ok": ok,
            "http": code,
            "seconds": time.monotonic() - start,
            "received": time.time(),
            "duplicate": duplicate,
            "bytes": len(body),
            "counts": {
                k: data.get(k)
                for k in [
                    "accepted",
                    "created",
                    "duplicates",
                    "persisted",
                    "acknowledged",
                    "parseFailed",
                    "quarantined",
                ]
            },
        }
    except Exception as ex:
        return {
            "ids": [e["eventId"] for e in batch],
            "ok": False,
            "http": 0,
            "seconds": time.monotonic() - start,
            "received": time.time(),
            "duplicate": duplicate,
            "errorType": type(ex).__name__,
            "bytes": len(body),
        }


def consume():
    try:
        while not stop.is_set():
            for entries in consumer.poll(timeout_ms=500).values():
                for rec in entries:
                    item = json.loads(rec.value)
                    ident = item.get("eventId", "")
                    if ident.startswith(RUN + "-"):
                        with lock:
                            kafka_seen[ident] += 1
                            if item.get(
                                "tenantId"
                            ) != "default" or ident not in item.get("msg", ""):
                                kafka_bad.append(ident)
    except Exception as ex:
        guard_reason.append("Kafka evidence consumer " + type(ex).__name__)
        guard.set()
    finally:
        consumer.close()


def sample_query():
    global query_serial
    start = time.monotonic()
    current = phase
    try:
        if not query_ids:
            return
        with lock:
            ident = query_ids[query_serial % len(query_ids)]
            query_serial += 1
        value = api(
            "/search-config/api/v1/search?"
            + urllib.parse.urlencode({"q": f'eventId="{ident}"', "limit": 10})
        )
        ok = (
            value.get("source") == "opensearch"
            and value.get("degraded") is False
            and (value.get("total") == 1)
            and (value.get("events", [{}])[0].get("eventId") == ident)
        )
        row = {"phase": current, "seconds": time.monotonic() - start, "ok": ok}
    except Exception as ex:
        row = {
            "phase": current,
            "seconds": time.monotonic() - start,
            "ok": False,
            "errorType": type(ex).__name__,
        }
    with lock:
        queries.append(row)


def monitor():
    global TOKEN, token_at, query_ids
    while not stop.is_set():
        start = time.monotonic()
        sample = {"time": time.time(), "phase": phase}
        try:
            if time.monotonic() - token_at > 300:
                TOKEN = login_token(GW)
                token_at = time.monotonic()
            disk = shutil.disk_usage(ROOT)
            sample["diskFree"] = disk.free
            sample.update(memory_snapshot())
            sample["cpuCounterScope"], sample["cpu"] = cpu_counters()
            sample["hostCpu"] = pathlib.Path("/proc/stat").read_text().splitlines()[0]
            status, node = http(OS + "/_nodes/stats/fs,jvm,indices")
            if status != 200:
                raise RuntimeError("OpenSearch stats HTTP " + str(status))
            nodes = list(node["nodes"].values())
            sample["opensearch"] = [
                {
                    "free": n["fs"]["total"]["available_in_bytes"],
                    "total": n["fs"]["total"]["total_in_bytes"],
                    "heap": n["jvm"]["mem"]["heap_used_in_bytes"],
                    "docs": n["indices"]["docs"]["count"],
                    "store": n["indices"]["store"]["size_in_bytes"],
                }
                for n in nodes
            ]
            if disk.free < 2684354560:
                guard_reason.append("Persistent disk free below 2.5 GiB")
                guard.set()
            if sample["workingSetBytes"] > working_set_limit:
                guard_reason.append(
                    "Working set exceeds 87.5 percent of host/cgroup budget"
                )
                guard.set()
            if any((n["free"] / n["total"] < 0.15 for n in sample["opensearch"])):
                guard_reason.append("OpenSearch low watermark headroom exhausted")
                guard.set()
            if int(sample["memoryEvents"].get("oom_kill", 0)) > initial_oom:
                guard_reason.append("cgroup OOM kill")
                guard.set()
            sample["jvm"] = {}
            for svc in ["search-config", "detect-web", "alert-web"]:
                url = (
                    base_url(svc)
                    + ("" if svc == "api-gateway" else "/" + svc)
                    + "/actuator/prometheus"
                )
                code, text = http(
                    url,
                    headers={
                        "Authorization": "Bearer "
                        + (TOKEN if svc == "api-gateway" else METRICS)
                    },
                )
                if code != 200:
                    raise RuntimeError("Metrics HTTP " + str(code) + " " + svc)
                vals = {}
                for line in text.splitlines():
                    if line.startswith(
                        (
                            "jvm_memory_used_bytes{",
                            "jvm_gc_pause_seconds_count{",
                            "jvm_gc_pause_seconds_sum{",
                            "process_cpu_usage ",
                            "socp_ingestion_outbox_oldest_pending_age_seconds ",
                            "socp_detection_event_completed_total{",
                            "socp_ingestion_outbox_",
                            "socp_kafka_consumer_lag",
                            "socp_detection_alert_outbox_",
                        )
                    ):
                        vals[line.rsplit(" ", 1)[0]] = float(line.rsplit(" ", 1)[1])
                sample["jvm"][svc] = vals
            sample["detectKafkaLag"] = sum(
                (
                    v
                    for k, v in sample["jvm"]["detect-web"].items()
                    if k.startswith("socp_kafka_consumer_lag")
                )
            )
            if phase == "soak" and sample["detectKafkaLag"] > 1000:
                saturation_reason.append("detection Kafka lag exceeded 1000 records")
                saturation.set()
            if len(samples) % 4 == 0:
                status, recent = http(
                    OS + "/socp-events-*/_search",
                    {
                        "size": 500,
                        "_source": ["eventId"],
                        "query": {"prefix": {"eventId": RUN + "-"}},
                        "sort": [{"eventId": "desc"}],
                    },
                )
                if status != 200:
                    raise RuntimeError("query sample refresh HTTP " + str(status))
                indexed_ids = [v["_source"]["eventId"] for v in recent["hits"]["hits"]]
                if indexed_ids:
                    with lock:
                        query_ids = indexed_ids
                sample["queryCandidateCount"] = len(indexed_ids)

                def probe(name):
                    try:
                        code, value = http(health_url(name), timeout=4)
                        return (
                            name,
                            {
                                "http": code,
                                "status": (
                                    value.get("status")
                                    if isinstance(value, dict)
                                    else None
                                ),
                            },
                        )
                    except Exception as ex:
                        return (name, {"errorType": type(ex).__name__})

                with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
                    sample["serviceHealth"] = dict(pool.map(probe, SERVICES))
                sample["healthFailures"] = sum(
                    (
                        v.get("http") != 200 or v.get("status") != "UP"
                        for v in sample["serviceHealth"].values()
                    )
                )
                sample["javaProcesses"] = {}
                for proc in pathlib.Path("/proc").iterdir():
                    if not proc.name.isdigit():
                        continue
                    try:
                        args = (proc / "cmdline").read_bytes().split(b"\x00")
                        jars = [
                            a.decode()
                            for a in args
                            if a.endswith(b".jar")
                            and str(ROOT / "services").encode() + b"/" in a
                        ]
                        if not jars:
                            continue
                        name = pathlib.Path(jars[0]).name.split("-1.0.0-")[0]
                        fields = {
                            a.split(":", 1)[0]: a.split(":", 1)[1].strip()
                            for a in (proc / "status").read_text().splitlines()
                            if ":" in a
                        }
                        sample["javaProcesses"][name] = {
                            "pid": int(proc.name),
                            "rssBytes": int(fields.get("VmRSS", "0").split()[0]) * 1024,
                            "threads": int(fields.get("Threads", 0)),
                        }
                    except (OSError, ValueError):
                        continue
        except Exception as ex:
            sample["errorType"] = type(ex).__name__
            sample["error"] = str(ex)[:200]
        with lock:
            samples.append(sample)
        with (OUT / "resources.jsonl").open("a") as f:
            f.write(json.dumps(sample) + "\n")
        if stop.wait(max(0, 15 - (time.monotonic() - start))):
            break


def queries_loop():
    with concurrent.futures.ThreadPoolExecutor(max_workers=10) as pool:
        while not stop.is_set():
            futures = [pool.submit(sample_query) for _ in range(10)]
            for f in futures:
                f.result()
            if stop.wait(10):
                break


def counts():
    where = f"source_event_id like '{RUN}-%'"
    journal = json.loads(
        sql(
            "detect",
            f"select json_build_object('total',count(*),'completed',count(*) filter(where status='COMPLETED'),'dead',count(*) filter(where status='DEAD_LETTERED')) from t_detection_event where {where}",
        )
    )
    code, data = http(
        OS + "/socp-events-*/_count", {"query": {"prefix": {"eventId": RUN + "-"}}}
    )
    if code != 200:
        raise RuntimeError("index count HTTP " + str(code))
    outbox = json.loads(
        sql(
            "search",
            f"select json_build_object('total',count(*),'unfinished',count(*) filter(where status <> 'PUBLISHED'),'dead',count(*) filter(where status='DEAD')) from t_ingestion_outbox where event_id like '{RUN}-%'",
        )
    )
    return {
        "journal": journal,
        "index": data["count"],
        "outbox": outbox,
        "kafkaUnique": len(kafka_seen),
    }


def drain(expected, timeout=600):
    deadline = time.monotonic() + timeout
    last = {}
    while time.monotonic() < deadline:
        last = counts()
        if (
            last["journal"]["completed"] == expected
            and last["index"] == expected
            and (last["outbox"]["unfinished"] == 0)
            and (last["kafkaUnique"] == expected)
        ):
            return last
        if last["journal"]["dead"] or last["outbox"]["dead"]:
            break
        print(json.dumps({"draining": last, "expected": expected}), flush=True)
        time.sleep(5)
    raise RuntimeError("pipeline did not drain: " + json.dumps(last))


def reconcile():
    rows = db.execute(
        "select id,n,kind,host,msg,src_ip from events where ack=1"
    ).fetchall()
    expected = {r[0] for r in rows}
    journal = sql(
        "detect",
        f"select source_event_id||E'\t'||status from t_detection_event where source_event_id like '{RUN}-%'",
    ).splitlines()
    actual_j = [s.split("\t")[0] for s in journal]
    bad_j = [s for s in journal if not s.endswith("\tCOMPLETED")]
    search = sql(
        "search", f"select event_id from t_search_event where event_id like '{RUN}-%'"
    ).splitlines()
    actual_os = []
    bad_os = []
    spec = {r[0]: r for r in rows}
    code, result = http(
        OS + "/socp-events-*/_search?scroll=1m",
        {"size": 500, "query": {"prefix": {"eventId": RUN + "-"}}, "sort": ["_doc"]},
    )
    scroll = None
    try:
        while True:
            if code != 200:
                raise RuntimeError("OpenSearch reconciliation HTTP " + str(code))
            scroll = result.get("_scroll_id", scroll)
            hits = result["hits"]["hits"]
            if not hits:
                break
            for hit in hits:
                value = hit["_source"]
                ident = value["eventId"]
                actual_os.append(ident)
                wanted = spec.get(ident)
                if (
                    not wanted
                    or value.get("tenantId") != "default"
                    or value.get("host") != wanted[3]
                    or (value.get("msg") != wanted[4])
                ):
                    bad_os.append(ident)
            code, result = http(
                OS + "/_search/scroll", {"scroll": "1m", "scroll_id": scroll}
            )
    finally:
        if scroll:
            http(OS + "/_search/scroll", {"scroll_id": [scroll]}, method="DELETE")
    expected_alerts = {}
    groups = collections.defaultdict(list)
    for ident, n, kind, host, msg, ip in rows:
        if kind == "pattern":
            expected_alerts[alert_id("AUTH-PRIVESC", host, [ident])] = ident
        elif kind == "threshold":
            groups[ip].append(ident)
    for ip, ids in groups.items():
        if len(ids) == 5:
            expected_alerts[alert_id("LATERAL-RDP", ip, ids)] = ids[-1]
    actual_alerts = sql(
        "alert",
        f"select source_alert_id||E'\t'||id from t_alarm where trigger_event_id like '{RUN}-%' and rule_id in ('AUTH-PRIVESC','LATERAL-RDP')",
    ).splitlines()
    alert_ids = [s.split("\t")[0] for s in actual_alerts]
    sql_ids = ",".join(("'" + s.split("\t")[1] + "'" for s in actual_alerts))
    ck_ids = []
    if sql_ids:
        code, data = http(
            os.environ.get("PIPELINE_CK", "http://127.0.0.1:8123") + "/",
            f"select distinct alarm_id from alert_agg.alarm_detail where tenant_id='default' and alarm_id in ({sql_ids}) FORMAT TabSeparated".encode(),
            {
                "Authorization": "Basic "
                + base64.b64encode(os.environ["PIPELINE_CK_AUTH"].encode()).decode()
            },
        )
        if code != 200:
            raise RuntimeError("ClickHouse reconciliation HTTP " + str(code))
        ck_ids = data.splitlines() if isinstance(data, str) else []
    checks = {
        "kafkaExactIds": set(kafka_seen) == expected and (not kafka_bad),
        "journalExactIds": set(actual_j) == expected
        and len(actual_j) == len(expected)
        and (not bad_j),
        "searchExactIds": set(search) == expected and len(search) == len(expected),
        "indexedExactIdsAndContent": set(actual_os) == expected
        and len(actual_os) == len(expected)
        and (not bad_os),
        "alarmExactIds": set(alert_ids) == set(expected_alerts)
        and len(alert_ids) == len(expected_alerts),
        "clickhouseExactAlarms": set(ck_ids)
        == {s.split("\t")[1] for s in actual_alerts},
    }
    result = {
        "checks": checks,
        "pass": all(checks.values()),
        "events": len(expected),
        "journalRows": len(actual_j),
        "kafkaPhysicalRecords": sum(kafka_seen.values()),
        "expectedAlarms": len(expected_alerts),
        "actualAlarms": len(alert_ids),
        "clickhouseAlarms": len(ck_ids),
        "missingJournal": sorted(expected - set(actual_j))[:10],
        "missingAlarms": sorted(set(expected_alerts) - set(alert_ids))[:10],
        "extraAlarms": sorted(set(alert_ids) - set(expected_alerts))[:10],
    }
    (OUT / "reconciliation.json").write_text(json.dumps(result, indent=2) + "\n")
    return result


def run_phase(name, eps, seconds, next_n):
    global phase, warm_id, query_ids
    phase = name
    saturation.clear()
    saturation_reason.clear()
    started_wall = time.time()
    start = time.monotonic()
    deadline = start + seconds
    batch_size = min(20, eps)
    slot = 0
    missed = 0
    pending = {}
    result_rows = []
    print(
        json.dumps({"phase": name, "targetEPS": eps, "seconds": seconds, "run": RUN}),
        flush=True,
    )

    def complete(f):
        r = f.result()
        result_rows.append(r)
        record_acknowledgement(db, r)
        with (OUT / "requests.jsonl").open("a") as out:
            out.write(json.dumps(r) + "\n")

    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        last_print = 0
        while (
            time.monotonic() < deadline
            and (not guard.is_set())
            and (not saturation.is_set())
        ):
            for f in list(pending):
                if f.done():
                    complete(f)
                    del pending[f]
            now = time.monotonic()
            due = start + slot * batch_size / eps
            if now < due:
                time.sleep(min(0.02, due - now))
                continue
            slot += 1
            if len(pending) >= 8:
                missed += batch_size
                continue
            batch = []
            for _ in range(batch_size):
                e, kind = event(next_n)
                batch.append(e)
                db.execute(
                    "insert into events(id,phase,n,kind,host,msg,src_ip,submitted) values(?,?,?,?,?,?,?,?)",
                    (
                        e["eventId"],
                        name,
                        next_n,
                        kind,
                        e["host"],
                        e["message"],
                        e.get("src_ip"),
                        time.time(),
                    ),
                )
                next_n += 1
            pending[pool.submit(send, batch)] = True
            if slot % 10 == 0 and len(pending) < 8:
                pending[pool.submit(send, batch, True)] = True
            if now - last_print > 30:
                last_print = now
                db.commit()
                print(
                    json.dumps(
                        {
                            "phase": name,
                            "elapsed": round(now - start),
                            "scheduledUnique": next_n,
                            "inFlight": len(pending),
                            "workingSetGiB": round(
                                memory_snapshot()["workingSetBytes"] / 1024**3, 2
                            ),
                            "diskFreeGiB": round(
                                shutil.disk_usage(ROOT).free / 1024**3, 2
                            ),
                        }
                    ),
                    flush=True,
                )
        for f in pending:
            complete(f)
    db.commit()
    wall = time.monotonic() - start
    good = db.execute(
        "select count(*) from events where phase=? and ack=1", (name,)
    ).fetchone()[0]
    errors = [r for r in result_rows if not r["ok"]]
    result = {
        "name": name,
        "startedAt": started_wall,
        "finishedSendingAt": time.time(),
        "targetEPS": eps,
        "requestedSeconds": seconds,
        "elapsedSeconds": wall,
        "acceptedUnique": good,
        "achievedEPS": good / wall,
        "missedSlots": missed,
        "requestErrors": len(errors),
        "httpCounts": dict(collections.Counter((r["http"] for r in result_rows))),
        "ingestP95Seconds": percentile([r["seconds"] for r in result_rows], 0.95),
        "ingestP99Seconds": percentile([r["seconds"] for r in result_rows], 0.99),
        "duplicateRequests": sum((r["duplicate"] for r in result_rows)),
        "resourceGuard": list(guard_reason),
        "capacityStop": list(saturation_reason),
    }
    phase = name + "-drain"
    drain_start = time.monotonic()
    expected = db.execute("select count(*) from events where ack=1").fetchone()[0]
    result["backlogAtEnd"] = counts()
    result["drain"] = drain(expected)
    result["drainSeconds"] = time.monotonic() - drain_start
    if warm_id is None:
        warm_id = db.execute(
            "select id from events where ack=1 order by n limit 1"
        ).fetchone()[0]
    for _ in range(12):
        oracle = reconcile()
        if oracle["pass"]:
            break
        time.sleep(5)
    result["reconciliation"] = oracle
    if oracle["pass"]:
        query_ids = [
            r[0]
            for r in db.execute(
                "select id from events where ack=1 order by n desc limit 1000"
            ).fetchall()
        ]
    qp = [q for q in queries if q["phase"] == name]
    result["queries"] = {
        "count": len(qp),
        "errors": sum((not q["ok"] for q in qp)),
        "p95Seconds": percentile([q["seconds"] for q in qp], 0.95),
        "p99Seconds": percentile([q["seconds"] for q in qp], 0.99),
    }
    result["capacityStable"] = result["drainSeconds"] <= 15 and (
        not result["capacityStop"]
    )
    result["pass"] = phase_passed(result, eps * seconds, guard.is_set())
    (OUT / (name + ".json")).write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps({"completedPhase": result}), flush=True)
    records.append(result)
    return (next_n, result)


def latency_evidence():
    phases = {r[0]: r[1] for r in db.execute("select id,phase from events where ack=1")}
    ingested = {}
    for row in sql(
        "search",
        f"select event_id||E'\\t'||extract(epoch from created_at) from t_search_event where event_id like '{RUN}-%'",
    ).splitlines():
        ident, stamp = row.split("\t")
        ingested[ident] = float(stamp)
    values = collections.defaultdict(lambda: collections.defaultdict(list))
    details = []
    for database, table, key, stamp, metric in [
        ("detect", "t_detection_event", "source_event_id", "completed_at", "detection"),
        ("alert", "t_alarm", "trigger_event_id", "created_at", "alarm"),
    ]:
        for row in sql(
            database,
            f"select {key}||E'\\t'||extract(epoch from {stamp}) from {table} where {key} like '{RUN}-%' and {stamp} is not null",
        ).splitlines():
            ident, raw = row.split("\t")
            if ident not in ingested or ident not in phases:
                continue
            elapsed = float(raw) - ingested[ident]
            values[phases[ident]][metric].append(elapsed)
            details.append(
                {
                    "eventId": ident,
                    "phase": phases[ident],
                    "metric": metric,
                    "seconds": elapsed,
                }
            )
    result = {
        phase: {
            metric: {
                "count": len(v),
                "p50Seconds": percentile(v, 0.5),
                "p95Seconds": percentile(v, 0.95),
                "p99Seconds": percentile(v, 0.99),
                "maxSeconds": max(v),
                "negativeSamples": sum((n < 0 for n in v)),
            }
            for metric, v in metrics.items()
        }
        for phase, metrics in values.items()
    }
    (OUT / "durable-latency.json").write_text(
        json.dumps(
            {
                "definition": "server PostgreSQL search event created_at to detection completed_at / alarm created_at; shared kernel UTC clock",
                "phases": result,
            },
            indent=2,
        )
        + "\n"
    )
    with (OUT / "durable-latency-samples.jsonl").open("w") as f:
        for row in details:
            f.write(json.dumps(row) + "\n")
    return result


def main():
    global TOKEN, token_at, consumer, initial_oom, phase
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--allow-local-fixture", action="store_true")
    parser.add_argument("--seconds", type=int, default=3600)
    parser.add_argument("--eps", type=int, default=5)
    parser.add_argument("--report-dir", type=pathlib.Path, default=P)
    args = parser.parse_args()
    initialize(args)
    from kafka import KafkaConsumer, TopicPartition

    TOKEN = login_token(GW)
    token_at = time.monotonic()
    for name in SERVICES:
        code, data = http(health_url(name))
        if code != 200 or data.get("status") != "UP":
            raise RuntimeError("service is not ready: " + name)
    for rule in ["AUTH-PRIVESC", "LATERAL-RDP"]:
        data = api("/detect-web/api/v1/rules/" + rule)
        if data["status"] != "ACTIVE" or data["enabled"] is not True:
            raise RuntimeError("required fixture rule is not active: " + rule)
    channels = api("/notify-web/api/v1/channels")
    channels = channels.get("items", []) if isinstance(channels, dict) else channels
    if any(c["type"] != "LOG" and c["enabled"] for c in channels):
        raise RuntimeError("load test refuses enabled external notification channels")
    consumer = KafkaConsumer(
        bootstrap_servers=os.environ.get("PIPELINE_KAFKA", "localhost:9092"),
        group_id=None,
        enable_auto_commit=False,
        allow_auto_create_topics=False,
        isolation_level="read_committed",
        auto_offset_reset="latest",
        max_poll_records=500,
        request_timeout_ms=10000,
        bootstrap_timeout_ms=10000,
        fetch_max_bytes=8 * 1024 * 1024,
        max_partition_fetch_bytes=4 * 1024 * 1024,
    )
    partitions = consumer.partitions_for_topic("socp-events")
    if not partitions or len(partitions) > 256:
        consumer.close()
        raise RuntimeError("canonical topic must already exist with 1..256 partitions")
    parts = [TopicPartition("socp-events", n) for n in sorted(partitions)]
    consumer.assign(parts)
    ends = consumer.end_offsets(parts)
    for p, n in ends.items():
        consumer.seek(p, n)
    initial_oom = int(memory_snapshot()["memoryEvents"]["oom_kill"])
    (OUT / "config.json").write_text(
        json.dumps(
            {
                "harnessSha256": hashlib.sha256(
                    pathlib.Path(__file__).read_bytes()
                ).hexdigest(),
                "run": RUN,
                "startedAt": started_at,
                "selectedSoakEPS": args.eps,
                "workingSetLimitBytes": working_set_limit,
                "hostMemoryLimitBytes": memory_limit,
                "thresholdIpBase": ip_base,
                "sourceCommit": subprocess.check_output(
                    ["git", "rev-parse", "HEAD"], text=True
                ).strip(),
                "sourceDiffSha256": hashlib.sha256(
                    subprocess.check_output(["git", "diff", "HEAD"])
                ).hexdigest(),
                "payload": "94% benign, 1% AUTH-PRIVESC, 5% LATERAL-RDP in groups of five; 10% duplicate batch replay",
                "tenants": 1,
                "queryConcurrency": 10,
                "queryBurstIntervalSeconds": 10,
                "indexStorage": "externally provisioned fixture; inspect workflow and middleware artifacts",
                "capacityCriteria": "zero request/query errors, offered unique rate >=98%, end backlog drain <=15 seconds; stop workload on detection lag >1000",
                "soakRate": "explicit CLI EPS",
                "soakSeconds": args.seconds,
                "kafkaStartOffsets": {str(p.partition): n for p, n in ends.items()},
            },
            indent=2,
        )
        + "\n"
    )
    threads = [
        threading.Thread(target=f, daemon=True)
        for f in [consume, monitor, queries_loop]
    ]
    for t in threads:
        t.start()
    try:
        next_n, first = run_phase("warmup", 10, 20, 0)
        if not first["reconciliation"]["pass"]:
            raise RuntimeError("warmup reconciliation failed")
        next_n, result = run_phase("soak", args.eps, args.seconds, next_n)
        phase = "final"
        final = reconcile()
        final["durableLatency"] = latency_evidence()
        stop.set()
        for t in threads:
            t.join(timeout=35)
        if any(t.is_alive() for t in threads):
            raise RuntimeError("evidence workers did not stop before final aggregation")
        final.update(
            phases=records,
            resourceGuard=guard_reason,
            monitorErrors=sum(("error" in s for s in samples)),
            healthFailures=sum((s.get("healthFailures", 0) for s in samples)),
            queryErrors=sum((not q["ok"] for q in queries)),
            queryCount=len(queries),
            startedAt=started_at,
            finishedAt=time.time(),
        )
        final["pass"] = (
            final["pass"]
            and (not guard_reason)
            and (not final["monitorErrors"])
            and (not final["healthFailures"])
            and (not final["queryErrors"])
            and (records[-1]["name"] == "soak" and records[-1]["pass"])
        )
        (OUT / "final.json").write_text(json.dumps(final, indent=2) + "\n")
        print(json.dumps({"final": final}), flush=True)
        return 0 if final["pass"] else 1
    except Exception as ex:
        (OUT / "fatal.json").write_text(
            json.dumps(
                {
                    "type": type(ex).__name__,
                    "message": str(ex)[:3000],
                    "resourceGuard": guard_reason,
                },
                indent=2,
            )
            + "\n"
        )
        raise
    finally:
        stop.set()
        for t in threads:
            t.join(timeout=35)
        (OUT / "queries.json").write_text(json.dumps(queries) + "\n")
        db.close()


if __name__ == "__main__":
    raise SystemExit(main())
