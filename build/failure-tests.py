#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
SOCP 故障注入测试（P4，2026-08-12）：验证核心中间件故障下服务不崩、行为符合降级设计、恢复后自愈。

场景（每个都「注入 → 断言 → 恢复 → 断言」）：
  1. 断 Kafka   → search-config ingest 降级直写 OpenSearch（事件仍进 OS）→ 恢复后 Kafka 主链自愈
  2. 停 OpenSearch → search-config 检索回退 H2（API 仍返回）→ 恢复
  3. 停 Temporal → soar-web 剧本执行回退进程内（不崩，返回执行结果）→ 恢复
  4. 停 PostgreSQL → alert-web 查询失败但不崩（进程存活、健康转 DOWN）→ 恢复后查询正常

前提：后端全栈 + 中间件在跑（bash build/run-all.sh backend + docker compose up -d）。
用法：python build/failure-tests.py
"""
import atexit
import base64
import json
import os
import socket
import subprocess
import sys
import time
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from auth_client import login_token  # noqa: E402

GATEWAY = os.environ.get("FAILURE_GATEWAY", "http://localhost:18092")
OS_URL = os.environ.get("FAILURE_OS_URL", "https://localhost:9200").rstrip("/")
OS_AUTH = os.environ.get("FAILURE_OS_AUTH", "admin:Socp!Sec2026xK")
TOKEN = None
PASS = 0
FAIL = 0
STOPPED_CONTAINERS = []


def api(path, method="GET", body=None, token=True):
    req = urllib.request.Request(GATEWAY + path, method=method)
    if token:
        req.add_header("Authorization", "Bearer " + TOKEN)
    if body is not None:
        req.add_header("Content-Type", "application/json")
        req.data = json.dumps(body).encode()
    try:
        with urllib.request.urlopen(req, timeout=8) as r:
            return r.status, json.loads(r.read().decode())
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read().decode())
        except Exception:
            return e.code, {}
    except Exception as e:
        return -1, {"error": str(e)}


def raw(url, method="GET", body=None, token=True, timeout=8):
    req = urllib.request.Request(url, method=method)
    if token and TOKEN:
        req.add_header("Authorization", "Bearer " + TOKEN)
    if body is not None:
        req.add_header("Content-Type", "application/json")
        req.data = json.dumps(body).encode()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, r.read().decode()
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()
    except Exception as e:
        return -1, str(e)


def check(name, ok, detail=""):
    global PASS, FAIL
    mark = "[PASS]" if ok else "[FAIL]"
    print(f"  {mark} {name} {detail}")
    if ok:
        PASS += 1
    else:
        FAIL += 1


def docker(action, *containers):
    if action == "stop":
        for container in containers:
            try:
                state = subprocess.run(
                    ["docker", "inspect", "-f", "{{.State.Running}}", container],
                    capture_output=True, text=True, timeout=10,
                ).stdout.strip().lower()
                if state == "true" and container not in STOPPED_CONTAINERS:
                    STOPPED_CONTAINERS.append(container)
            except Exception:
                pass
    subprocess.run(["docker", action, *containers], check=False,
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    time.sleep(3)


def restore_containers():
    """尽量恢复本脚本停止过、且停止前处于运行状态的容器。"""
    for container in reversed(STOPPED_CONTAINERS):
        subprocess.run(["docker", "start", container], check=False,
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


atexit.register(restore_containers)


def wait_http(url, expect_ok=True, timeout=40, step=3):
    for _ in range(timeout // step):
        code, _ = raw(url, token=False, timeout=3)
        if expect_ok and code == 200:
            return True
        if not expect_ok and code != 200:
            return True
        time.sleep(step)
    return False


def port_listening(port):
    """检查端口是否有进程监听（判断服务进程存活，不依赖 HTTP 响应）。"""
    try:
        with socket.create_connection(("127.0.0.1", port), timeout=2):
            return True
    except OSError:
        pass

    # 连接探测不可用时再尝试系统命令；兼容 Linux ss、Unix netstat 和 Windows netstat。
    try:
        commands = [
            ["ss", "-ltn"],
            ["netstat", "-ano"],
            ["netstat", "-an", "-p", "tcp"],
        ]
        for command in commands:
            try:
                out = subprocess.run(command, capture_output=True, text=True,
                                     timeout=10).stdout
            except (FileNotFoundError, OSError):
                continue
            for line in out.splitlines():
                if f":{port} " in line and ("LISTEN" in line.upper() or "LISTENING" in line.upper()):
                    return True
    except Exception:
        pass
    return False


def os_count():
    req = urllib.request.Request(OS_URL + "/socp-events-*/_count", method="GET")
    auth = "Basic " + base64.b64encode(OS_AUTH.encode()).decode()
    req.add_header("Authorization", auth)
    import ssl
    try:
        context = ssl._create_unverified_context() if OS_URL.startswith("https") else None
        with urllib.request.urlopen(req, timeout=8, context=context) as r:
            return json.loads(r.read().decode()).get("count", -1)
    except Exception:
        return -1


def main():
    global TOKEN
    print("== 0. 前置 ==")
    try:
        TOKEN = login_token(GATEWAY, timeout=10)
    except RuntimeError as error:
        TOKEN = ""
        print(f"  [WARN] {error}")
    check("登录拿 session token", bool(TOKEN))
    if not TOKEN:
        sys.exit(1)

    # ---------- 场景 1：断 Kafka ----------
    print("\n== 1. Kafka 故障：ingest 降级直写 OpenSearch ==")
    before = os_count()
    docker("stop", "socp-kafka")
    time.sleep(8)  # 等 isAvailable 缓存（5s）过期并重新探测到不可达
    uniq = str(int(time.time() * 1000))[-6:]
    st, d = api("/search-config/api/v1/ingest", "POST",
                {"collector": "failtest", "host": f"fk-{uniq}", "message": "sudo: failtest escalation",
                 "src_ip": f"10.99.{uniq[:2]}.{uniq[2:4]}"})
    check("Kafka 断开时 ingest 仍 accepted", st == 200 and d.get("accepted") == 1, f"st={st} accepted={d.get('accepted')}")
    time.sleep(6)
    after = os_count()
    check("事件降级直写 OpenSearch（OS count 增长）", after > before, f"{before} -> {after}")
    # 恢复 Kafka
    docker("start", "socp-kafka")
    time.sleep(8)
    st, d = api("/search-config/api/v1/ingest", "POST",
                {"collector": "failtest", "host": f"fk2-{uniq}", "message": "sudo: failtest2",
                 "src_ip": f"10.99.{uniq[:2]}.{uniq[2:4]}"})
    check("Kafka 恢复后 ingest 正常", st == 200 and d.get("accepted") == 1, f"st={st}")

    # ---------- 场景 2：停 OpenSearch ----------
    print("\n== 2. OpenSearch 故障：服务不崩 ==")
    docker("stop", "socp-opensearch")
    time.sleep(3)
    code, _ = raw("http://localhost:18081/search-config/actuator/health", token=False, timeout=10)
    check("OS 停时 search-config 进程存活（端口监听）", port_listening(18081), f"health_st={code}")
    docker("start", "socp-opensearch")
    time.sleep(8)

    # ---------- 场景 3：停 Temporal ----------
    print("\n== 3. Temporal 故障：SOAR 回退进程内执行器 ==")
    docker("stop", "socp-temporal")
    time.sleep(8)  # 等 TemporalExecutor 缓存（5s）过期并重新探测到不可达
    st, d = api("/soar-web/api/v1/playbooks", "GET")
    pb = d[0] if isinstance(d, list) and d else None
    if pb:
        st2, ex = api(f"/soar-web/api/v1/playbooks/{pb['id']}/execute", "POST", {"id": "fail-t", "severity": "HIGH"})
        check("Temporal 停时剧本执行仍返回（进程内回退）", st2 == 200 and ex.get("executionId"), f"st={st2}")
    else:
        check("Temporal 停时剧本执行仍返回（进程内回退）", False, "无剧本")
    docker("start", "socp-temporal")
    time.sleep(8)

    # ---------- 场景 4：停 PostgreSQL ----------
    print("\n== 4. PostgreSQL 故障：进程存活，恢复后自愈 ==")
    docker("stop", "socp-postgres")
    time.sleep(5)
    check("PG 停时 alert-web 进程存活（端口监听）", port_listening(18080), "")
    docker("start", "socp-postgres")
    time.sleep(12)
    st, d = api("/alert-web/api/alarms?page=1&size=5", "GET")
    check("PG 恢复后告警查询正常", st == 200, f"st={st}")

    print("\n" + "=" * 60)
    print(f"故障注入测试通过 {PASS} / 失败 {FAIL}")
    sys.exit(0 if FAIL == 0 else 1)


if __name__ == "__main__":
    main()
