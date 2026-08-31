#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
verify-pipeline.py —— 真链路 E2E（中间件接线验证）

验证 event-driven SIEM 核心链路（本地与 GitHub Actions 通用）：

    push 攻击事件 → search-config 归一化
        ├─ Kafka socp-events topic 出现事件        ✓
        ├─ OpenSearch socp-events-* raw event      ✓
        └─ detect-web 规则命中 → alert-web
            ├─ PG alert.t_alarm 出现告警（经 API 查询验证）✓
            └─ ClickHouse alert_agg.alarm_detail   ✓
    API 查询验证（alert-web 告警 / report-web 日报）

依赖：Kafka/OpenSearch/PostgreSQL/ClickHouse 4 中间件 + search-config/detect-web/alert-web/report-web 4 服务。
用法：python3 build/verify-pipeline.py
环境变量（CI 注入）：
  PIPELINE_GATEWAY  (默认 http://127.0.0.1:18092)  网关
  PIPELINE_OS       (默认 https://localhost:9200)  OpenSearch
  PIPELINE_OS_AUTH  (默认 admin:Socp!Sec2026xK)    OpenSearch basic
  PIPELINE_CK       (默认 http://127.0.0.1:8123)   ClickHouse HTTP
  PIPELINE_CK_AUTH  (默认 default:socp)
"""
import base64
import json
import os
import ssl
import sys
import time
import urllib.request
import urllib.error

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from auth_client import login_token  # noqa: E402

GW = os.environ.get("PIPELINE_GATEWAY", "http://127.0.0.1:18092")
OS_URL = os.environ.get("PIPELINE_OS", "https://localhost:9200")
OS_AUTH = os.environ.get("PIPELINE_OS_AUTH", "admin:Socp!Sec2026xK")
CK_URL = os.environ.get("PIPELINE_CK", "http://127.0.0.1:8123")
CK_AUTH = os.environ.get("PIPELINE_CK_AUTH", "default:socp")
JWT = os.environ.get("PIPELINE_JWT", "")
USER = os.environ.get("PIPELINE_USER", "demo")
PASSWD = os.environ.get("PIPELINE_PASS", "demo123")

PASS, FAIL = [], []
_os_ctx = None


def check(name, cond, detail=""):
    (PASS if cond else FAIL).append(name)
    print(("  [PASS] " if cond else "  [FAIL] ") + name + (("  -> " + str(detail)[:200]) if detail else ""))


def wait_for(fn, timeout=40.0, interval=1.0):
    end = time.time() + timeout
    last = None
    while time.time() < end:
        last = fn()
        if last:
            return last
        time.sleep(interval)
    return last


def login():
    global JWT
    if JWT:
        return
    JWT = login_token(GW, USER, PASSWD)


def api(path, body=None, method=None):
    login()
    method = method or ("POST" if body is not None else "GET")
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(GW + path, data=data, method=method)
    req.add_header("Authorization", "Bearer " + JWT)
    req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=15) as r:
            return r.status, json.loads(r.read().decode())
    except urllib.error.HTTPError as e:
        return e.code, {}


def os_get(path):
    req = urllib.request.Request(OS_URL + path)
    req.add_header("Authorization", "Basic " + base64.b64encode(OS_AUTH.encode()).decode())
    if OS_URL.startswith("https"):
        global _os_ctx
        if _os_ctx is None:
            _os_ctx = ssl.create_default_context()
            _os_ctx.check_hostname = False
            _os_ctx.verify_mode = ssl.CERT_NONE
        return json.loads(urllib.request.urlopen(req, timeout=15, context=_os_ctx).read())
    return json.loads(urllib.request.urlopen(req, timeout=15).read())


def ck_query(sql):
    req = urllib.request.Request(CK_URL + "/",
                                 data=sql.encode(),
                                 headers={"Authorization": "Basic " + base64.b64encode(CK_AUTH.encode()).decode()})
    with urllib.request.urlopen(req, timeout=15) as r:
        return r.read().decode().strip()


def kafka_topic_offsets():
    try:
        from kafka import KafkaConsumer
        from kafka.structs import TopicPartition
        c = KafkaConsumer(bootstrap_servers=os.environ.get("PIPELINE_KAFKA", "127.0.0.1:9092"),
                          group_id=None, enable_auto_commit=False,
                          request_timeout_ms=5000)
        partitions = c.partitions_for_topic("socp-events") or set()
        tps = [TopicPartition("socp-events", p) for p in sorted(partitions)]
        if not tps:
            c.close()
            return None
        c.assign(tps)
        ends = c.end_offsets(tps)
        c.close()
        return sum(ends.values())
    except Exception as e:
        print("  [WARN] Kafka 探针:", e)
        return None


print("=== SOCP 真链路 E2E（Kafka→Detect→PG/OpenSearch/ClickHouse） ===")

# ---- 0. 前置健康 ----
print("\n== 0. 中间件与服务健康 ==")
for name, url in [("OpenSearch", OS_URL), ("ClickHouse", CK_URL), ("网关", GW + "/actuator/health")]:
    try:
        if name == "OpenSearch":
            os_get("/")
            check(f"{name} 可达", True, "ok")
        else:
            r = urllib.request.urlopen(url, timeout=8)
            check(f"{name} 可达", r.status == 200, r.status)
    except Exception as e:
        check(f"{name} 可达", False, e)
check("Kafka 端口可达", kafka_topic_offsets() is not None)

# ---- 1. 基线 ----
print("\n== 1. 基线计数 ==")
base_offset = kafka_topic_offsets() or 0
base_alarms, base_alarm_total = api("/alert-web/api/alarms?page=1&size=1")
base_total = base_alarm_total.get("data", {}).get("total", 0) if isinstance(base_alarm_total, dict) else 0
try:
    os_total = os_get("/socp-events-*/_count").get("count", 0)
except Exception:
    os_total = 0
try:
    ck_total = int(ck_query("SELECT count(*) FROM alert_agg.alarm_detail") or "0")
except Exception as e:
    ck_total = -1
    print("  [WARN] CK 查询:", e)
print(f"  基线: Kafka offset={base_offset} alarms={base_total} OS={os_total} CK={ck_total}")

# ---- 2. 注入攻击事件（走 search-config 归一化管线 → Kafka + OpenSearch） ----
print("\n== 2. 注入攻击事件（search-config ingest → Kafka/OpenSearch） ==")
st, tasks = api("/search-config/api/v1/ingest/tasks")
items = tasks.get("data", []) if isinstance(tasks, dict) else tasks
check("获取接入任务列表", st == 200 and len(items) > 0, f"st={st} n={len(items)}")
if not items:
    print("无接入任务，退出"); sys.exit(1)
tid = items[0]["id"]
# 注入攻击事件：sudo 权限提升（pattern 单条命中）。
# host/src_ip 带随机后缀：规避 Suppressor（同规则+同实体 5 分钟抑制）与告警归并，保证每次运行可独立验证。
uniq = str(int(time.time() * 1000))[-6:]
attack_host = "ci-attack-%s" % uniq
attack_ip = "10.9.%s.%s" % (uniq[:2], uniq[2:4])
samples = [
    '{"collector":"auth","host":"%s","source":"auth","severity":"HIGH","message":"sudo: ciattacker : TTY=pts/9 ; USER=root ; COMMAND=/bin/sh","src_ip":"%s","user":"ciattacker"}'
    % (attack_host, attack_ip),
]
st, r = api("/search-config/api/v1/ingest/tasks/%s/test" % tid, {"sample": samples[0]}, "POST")
check("注入 sudo 攻击事件走管线", st == 200 and r.get("ok") is True, r.get("pipeline"))

# ---- 3. Kafka topic 出现事件 ----
print("\n== 3. Kafka socp-events 事件写入 ==")
def kafka_grew():
    now = kafka_topic_offsets()
    return now if now and now > base_offset else None
new_offset = wait_for(kafka_grew, timeout=60)
check("Kafka topic 出现新事件", new_offset is not None, f"offset {base_offset} -> {new_offset}")

# ---- 4. OpenSearch raw event ----
print("\n== 4. OpenSearch socp-events-* raw event ==")
def os_grew():
    try:
        # OpenSearch 异步 refresh：写入后强制 refresh 保证 _count 可见
        req = urllib.request.Request(OS_URL + "/socp-events-*/_refresh", method="POST")
        req.add_header("Authorization", "Basic " + base64.b64encode(OS_AUTH.encode()).decode())
        if OS_URL.startswith("https"):
            global _os_ctx
            if _os_ctx is None:
                _os_ctx = ssl.create_default_context()
                _os_ctx.check_hostname = False
                _os_ctx.verify_mode = ssl.CERT_NONE
            urllib.request.urlopen(req, timeout=10, context=_os_ctx)
        else:
            urllib.request.urlopen(req, timeout=10)
    except Exception:
        pass
    try:
        n = os_get("/socp-events-*/_count").get("count", 0)
        return n if n > os_total else None
    except Exception:
        return None
new_os = wait_for(os_grew, timeout=40)
check("OpenSearch 出现 raw event", new_os is not None, f"{os_total} -> {new_os}")

# ---- 5. 检测命中 → PG alert（经 alert-web API 验证） ----
print("\n== 5. 检测命中 → 告警持久化（PG t_alarm，API 查询验证） ==")
def alarm_grew():
    _, a = api("/alert-web/api/alarms?page=1&size=500")
    total = a.get("data", {}).get("total", 0) if isinstance(a, dict) else 0
    return total if total > base_total else None
new_alarms = wait_for(alarm_grew, timeout=60)
check("PG 出现新告警（alert-web API 查询）", new_alarms is not None, f"{base_total} -> {new_alarms}")
if new_alarms:
    _, a = api("/alert-web/api/alarms?page=1&size=500")
    items = a.get("data", {}).get("items", [])
    hit = [x for x in items if attack_host in str(x.get("entity", "")) or "ciattacker" in str(x.get("message", ""))]
    check("告警来自注入的攻击事件（规则命中）", len(hit) >= 1, [x.get("severity") for x in hit[:2]])

# ---- 6. ClickHouse alarm_detail ----
print("\n== 6. ClickHouse alert_agg.alarm_detail ==")
def ck_grew():
    try:
        n = int(ck_query("SELECT count(*) FROM alert_agg.alarm_detail") or "0")
        return n if n > ck_total else None
    except Exception:
        return None
new_ck = wait_for(ck_grew, timeout=40)
check("ClickHouse 出现 alarm_detail", new_ck is not None, f"{ck_total} -> {new_ck}")

# ---- 7. API 查询验证（报表链路） ----
print("\n== 7. API 查询验证 ==")
st, rep = api("/report-web/api/v1/reports/daily")
check("report-web 日报查询", st == 200, st if st != 200 else "")

# ---- 汇总 ----
print("\n============================================================")
print("真链路 E2E 通过 %d / 失败 %d" % (len(PASS), len(FAIL)))
for f in FAIL:
    print("  FAILED:", f)
sys.exit(1 if FAIL else 0)
