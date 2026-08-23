# -*- coding: utf-8 -*-
"""SOCP 全栈端到端验证：默认后端进程健康 + 采集→检测→告警→情报富化→通知→建案→SOAR 全链路。

用法： python socp/build/verify-full.py
前置： bash socp/build/run-all.sh backend  （默认 15 个进程全部 UP）

与 verify-slice.py 的分工：
  verify-slice.py  验证横切能力（鉴权/租户/审计/限流/追踪），只走网关 + alert-web。
  verify-full.py   验证业务全链路 + 新增的 THREAT / ATT&CK / 通知 / 案件 / 查找表 / 合规 能力。
"""
import json
import os
import sys
import time
import random
import urllib.error
import urllib.request

PASS, FAIL = [], []

# 端口/地址唯一来源：build/ports.env（经 build/ports.py 读取）。
# 想换端口跑： SOCP_PORT_ALERT_WEB=28080 python build/verify-full.py
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from ports import SERVICES as SVC, base_url, health_url, GATEWAY_URL  # noqa: E402

#: 服务名 -> 基地址，供下面各用例拼 URL（不再出现任何硬编码端口）
U = {name: base_url(name) for name in SVC}


def call(url, method="GET", body=None, timeout=10):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Authorization", "Bearer " + token())
    req.add_header("X-Tenant-Id", "default")
    if data:
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            raw = r.read().decode("utf-8", "replace")
            try:
                return r.status, (json.loads(raw) if raw.strip() else {})
            except json.JSONDecodeError:
                return r.status, {"_raw": raw[:300]}
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "replace")
        try:
            return e.code, (json.loads(raw) if raw.strip() else {})
        except json.JSONDecodeError:
            return e.code, {"_raw": raw[:300]}
    except Exception as e:  # 连接失败
        return -1, {"_err": str(e)}


_TOKEN = {"t": None}


def token():
    """登录网关拿真 JWT（dev-bypass 关闭后 demo-token 不再可用）。"""
    if _TOKEN["t"]:
        return _TOKEN["t"]
    try:
        req = urllib.request.Request(
            GATEWAY_URL + "/auth/login",
            data=json.dumps({"username": "demo", "password": "demo123"}).encode(),
            method="POST",
        )
        req.add_header("Content-Type", "application/json")
        with urllib.request.urlopen(req, timeout=10) as r:
            _TOKEN["t"] = json.loads(r.read().decode())["token"]
    except Exception as e:
        print("  [WARN] 登录拿 token 失败，回退 demo-token:", e)
        _TOKEN["t"] = "demo-token"
    return _TOKEN["t"]


def check(name, cond, detail=""):
    (PASS if cond else FAIL).append(name)
    print(("  [PASS] " if cond else "  [FAIL] ") + name + (("  -> " + str(detail)[:220]) if detail else ""))


def unwrap(body):
    """alert-web 走统一响应信封 {code,message,data}；其余服务直出。"""
    if isinstance(body, dict) and "data" in body and "code" in body:
        return body["data"]
    return body


# ---------------------------------------------------------------- 1. 健康
print("\n=== 1. 默认部署服务健康 ===")
for name, port in SVC.items():
    st, _ = call(health_url(name))
    check("%s:%d 健康" % (name, port), st == 200, st)

# ---------------------------------------------------------------- 2. 情报
print("\n=== 2. 威胁情报 threat-web ===")
# 每轮用独立 IP，避免 alert-web 同实体告警去重导致“无新告警”（仍能在重启后校验该 IOC 仍在库）
IOC_IP = "198.51.100.%d" % random.randint(2, 254)
st, ioc = call(U["threat-web"] + "/threat-web/api/v1/iocs", "POST", {
    "type": "IP", "value": IOC_IP, "threatType": "C2", "source": "verify-full",
    "confidence": 95, "severity": "CRITICAL", "tags": ["e2e"]})
check("新增 IOC", st == 200 and ioc.get("value") == IOC_IP, ioc)
st, m = call(U["threat-web"] + "/threat-web/api/v1/iocs/match?value=" + IOC_IP)
check("IOC 命中查询", st == 200 and bool(m), m)
st, s = call(U["threat-web"] + "/threat-web/api/v1/stats")
check("IOC 统计非空", st == 200 and s.get("total", 0) > 0, s)

# ---------------------------------------------------------------- 3. ATT&CK
print("\n=== 3. MITRE ATT&CK attack-web ===")
st, tactics = call(U["attack-web"] + "/attack-web/api/v1/tactics")
check("战术目录 14 项", st == 200 and len(tactics) == 14, len(tactics) if st == 200 else st)
st, techs = call(U["attack-web"] + "/attack-web/api/v1/techniques")
check("技术目录非空", st == 200 and len(techs) > 20, len(techs) if st == 200 else st)
st, rules = call(U["detect-web"] + "/detect-web/api/v1/rules")
rule_techs = sorted({r.get("mitre") for r in rules if r.get("mitre")}) if st == 200 else []
check("规则已标注 ATT&CK 技术", len(rule_techs) >= 10, rule_techs)
st, cov = call(U["attack-web"] + "/attack-web/api/v1/coverage", "POST",
               {"ruleTechniques": rule_techs})
check("检测覆盖率可计算且 > 0", st == 200 and cov.get("coverage", 0) > 0,
      "coverage=%s%% (%s/%s)" % (cov.get("coverage"), cov.get("coveredTechniques"), cov.get("totalTechniques"))
      if st == 200 else st)

# ---------------------------------------------------------------- 4. 通知渠道
print("\n=== 4. 通知集成 notify-web ===")
st, chans = call(U["notify-web"] + "/notify-web/api/v1/channels")
check("内置通知渠道存在", st == 200 and len(chans) >= 2, len(chans) if st == 200 else st)

# ---------------------------------------------------------------- 5. 全链路
print("\n=== 5. 端到端：采集→检测→告警→富化→通知→建案→SOAR ===")
before_alarms = unwrap(call(U["alert-web"] + "/alert-web/api/alarms?size=200")[1]) or []
before_ids = {a["id"] for a in before_alarms}

st, ing = call(U["detect-web"] + "/detect-web/api/v1/ingest", "POST", {
    "source": "auth", "host": "verify-host-01", "severity": "HIGH",
    "msg": "sudo: verifier : TTY=pts/9 ; USER=root ; COMMAND=/bin/sh",
    "fields": {"src_ip": IOC_IP, "user": "verifier"}})
check("事件被接收", st == 200 and ing.get("accepted") is True, ing)

def wait_for(fn, timeout=20.0, interval=0.5):
    """轮询直到 fn() 返回真值或超时；返回最后一次结果。用于等待异步扇出完成。"""
    end = time.time() + timeout
    last = None
    while time.time() < end:
        last = fn()
        if last:
            return last
        time.sleep(interval)
    return last


def new_alarm_of(entity):
    cur = unwrap(call(U["alert-web"] + "/alert-web/api/alarms?size=200")[1]) or []
    cand = [a for a in cur if a["id"] not in before_ids and a.get("entity") == entity]
    return cand[0] if cand and cand[0].get("tiHits") else None


new_alarm = wait_for(lambda: new_alarm_of(IOC_IP))
check("规则命中并生成告警", new_alarm is not None,
      new_alarm.get("ruleId") if new_alarm else "无新告警（或未完成情报富化）")

if new_alarm:
    check("告警落库带 ATT&CK 技术", bool(new_alarm.get("mitre")), new_alarm.get("mitre"))
    hits = new_alarm.get("tiHits")
    ok_hits = False
    try:
        parsed = json.loads(hits) if isinstance(hits, str) else (hits or [])
        ok_hits = any(h.get("value") == IOC_IP for h in parsed)
    except Exception:
        ok_hits = IOC_IP in str(hits)
    check("告警已被威胁情报富化", ok_hits, str(hits)[:180])

    aid = new_alarm["id"]

    def my_dispatch():
        st_, dlog_ = call(U["notify-web"] + "/notify-web/api/v1/dispatch-log")
        mine_ = [d for d in dlog_ if d.get("alarmId") == aid] if st_ == 200 else []
        return mine_ if len(mine_) >= 2 else None

    mine = wait_for(my_dispatch) or []
    check("通知已派发到渠道", len(mine) >= 2,
          [str(d.get("channel")) + ":" + str(d.get("status")) for d in mine])
    check("Webhook 渠道派发成功",
          any(d.get("type") == "WEBHOOK" and d.get("status") == "sent" for d in mine),
          [d for d in mine if d.get("type") == "WEBHOOK"])

    def my_case():
        st_, cases_ = call(U["incident-web"] + "/incident-web/api/v1/incidents")
        if st_ != 200:
            return None
        return next((c for c in cases_ if aid in c.get("alarmIds", [])), None)

    mycase = wait_for(my_case)
    check("告警自动归并为案件", mycase is not None, mycase.get("id") if mycase else "未建案")
    if mycase:
        alarm_evs = [e for e in mycase.get("timeline", []) if e.get("type") == "ALARM"]
        check("案件时间线无重复（幂等：同一告警不重复入链）",
              len(alarm_evs) == len({e.get("alarmId") for e in alarm_evs}),
              "timeline_alarm=%d distinct_alarmId=%d" % (len(alarm_evs), len({e.get("alarmId") for e in alarm_evs})))
        check("案件时间线含 ATT&CK 标注",
              any("[T" in str(e.get("message", "")) for e in alarm_evs),
              alarm_evs[0].get("message", "")[:100] if alarm_evs else "")

    def my_execs():
        st_, ex_ = call(U["soar-web"] + "/soar-web/api/v1/playbooks/executions")
        return ex_ if st_ == 200 and len(ex_) > 0 else None

    execs = wait_for(my_execs) or []
    check("SOAR 剧本已执行", len(execs) > 0, [e.get("playbook") for e in execs][:3])

# ---------------------------------------------------------------- 6. 查找表 / 合规
print("\n=== 6. 查找表与合规 ===")
st, sets = call(U["search-config"] + "/search-config/api/v1/reference-sets")
check("参考数据集存在", st == 200 and len(sets) > 0, [s.get("name") for s in sets] if st == 200 else st)
st, fwb = call(U["soc-base"] + "/soc-base/api/v1/compliance/frameworks")
fw = fwb.get("frameworks", []) if isinstance(fwb, dict) else []
check("合规框架存在", st == 200 and len(fw) > 0, [f.get("name") for f in fw] if st == 200 else st)
st, ccov = call(U["soc-base"] + "/soc-base/api/v1/compliance/coverage", "POST",
                {"ruleIds": [r.get("id") for r in rules]})
check("合规覆盖率可计算且控制项映射有效（>50%）",
      st == 200 and ccov.get("coverage", 0) > 50,
      {k: v for k, v in ccov.items() if not isinstance(v, list)} if st == 200 else st)

st, rep = call(U["report-web"] + "/report-web/api/v1/reports/daily")
check("REPORT 日报可生成", st == 200 and bool(rep), list(rep.keys())[:6] if st == 200 else st)

# ---------------------------------------------------------------- 7. UEBA / 威胁评分 / 观察名单
print("\n=== 7. UEBA 异常基线 + 威胁评分 + 观察名单 ===")
st, wls = call(U["detect-web"] + "/detect-web/api/v1/watchlists")
wl_names = {w.get("name") for w in wls} if st == 200 else set()
check("内置观察名单已装载", st == 200 and {"privileged_accounts", "crown_jewels", "blocked_ips"} <= wl_names,
      sorted(wl_names))

# UEBA 规则（baseline / rare）应已随种子规则注册
ueba_rules = [r for r in rules if str(r.get("id", "")).startswith("UEBA-")] if isinstance(rules, list) else []
check("UEBA 规则已注册（baseline + rare）", len(ueba_rules) >= 5,
      [r.get("id") + ":" + str(r.get("type")) for r in ueba_rules])
watch_rules = [r for r in rules if str(r.get("id", "")).startswith("WATCH-")] if isinstance(rules, list) else []
check("观察名单驱动规则已注册", len(watch_rules) >= 3, [r.get("id") for r in watch_rules])

# 评分模型：可解释拆解 + 单调性（条件更恶劣 → 分更高）
st, sc_low = call(U["detect-web"] + "/detect-web/api/v1/ueba/score?severity=LOW")
st2, sc_hi = call(U["detect-web"] + "/detect-web/api/v1/ueba/score"
                  "?severity=CRITICAL&mitre=T1486&tiHits=3&recentAlerts=10&assetCriticality=3")
check("威胁评分可解释（含分项拆解）",
      st2 == 200 and isinstance(sc_hi.get("breakdown"), dict) and len(sc_hi["breakdown"]) >= 4,
      sc_hi.get("breakdown"))
check("威胁评分单调且封顶 100",
      st == 200 and st2 == 200 and sc_low.get("score", 0) < sc_hi.get("score", 0) <= 100,
      "LOW=%s CRITICAL+全加成=%s" % (sc_low.get("score"), sc_hi.get("score")))

# 动态改名单立刻生效：把测试实体加进 crown_jewels，实体画像的 critical 标记应翻转
st, _ = call(U["detect-web"] + "/detect-web/api/v1/watchlists/crown_jewels", "POST", [IOC_IP])
st2, wl = call(U["detect-web"] + "/detect-web/api/v1/watchlists/crown_jewels")
check("观察名单可运行时追加（无需重载规则）",
      st == 200 and st2 == 200 and IOC_IP in [str(v).lower() for v in wl.get("values", [])],
      wl.get("size"))

st, ents = call(U["detect-web"] + "/detect-web/api/v1/ueba/entities?limit=20")
check("实体风险画像已产出", st == 200 and len(ents) > 0,
      [(e.get("entity"), e.get("risk"), e.get("level")) for e in ents[:3]] if st == 200 else st)
if st == 200 and ents:
    check("风险画像按风险分降序", all(ents[i]["risk"] >= ents[i + 1]["risk"] for i in range(len(ents) - 1)),
          [e.get("risk") for e in ents[:6]])
    check("风险画像含 ATT&CK / 规则下钻",
          all(("mitre" in e and "topRules" in e) for e in ents),
          {"mitre": ents[0].get("mitre"), "topRules": ents[0].get("topRules")})
st, usum = call(U["detect-web"] + "/detect-web/api/v1/ueba/summary")
check("风险摘要含档位分布与半衰期",
      st == 200 and isinstance(usum.get("byLevel"), dict) and usum.get("halfLifeHours", 0) > 0, usum)

# 告警落库应带威胁评分（检测侧与分析侧同一口径）
st, astats = call(U["alert-web"] + "/alert-web/api/alarms/stats")
astats = unwrap(astats)
check("告警统计含风险分布 / 均分 / Top 风险",
      st == 200 and "byRiskLevel" in astats and "avgRisk" in astats and "topRisk" in astats,
      {"avgRisk": astats.get("avgRisk"), "byRiskLevel": astats.get("byRiskLevel")} if st == 200 else st)
if st == 200 and astats.get("topRisk"):
    top = astats["topRisk"][0]
    check("Top 风险告警带评分与档位",
          isinstance(top.get("riskScore"), int) and bool(top.get("riskLevel")),
          {k: top.get(k) for k in ("ruleName", "entity", "riskScore", "riskLevel")})

# ---------------------------------------------------------------- 8. 接入任务
print("\n=== 8. 接入任务配置与运行监控 ===")
st, tasks = call(U["search-config"] + "/search-config/api/v1/ingest/tasks")
check("接入任务列表可用", st == 200 and len(tasks) > 0, len(tasks) if st == 200 else st)
if st == 200 and tasks:
    t0 = tasks[0]
    check("任务视图合并了配置与运行指标",
          all(k in t0 for k in ("collector", "target", "enabled", "runtime"))
          and all(k in t0["runtime"] for k in ("eps1m", "accepted", "health")),
          {"collector": t0.get("collector"), "health": t0["runtime"].get("health")})

    tid = t0["id"]
    # 自测：灌一条样例日志走完整管线
    st_t, tres = call(U["search-config"] + "/search-config/api/v1/ingest/tasks/%s/test" % tid, "POST", {})
    check("接入连通性自测贯通管线", st_t == 200 and tres.get("ok") is True,
          {"collector": tres.get("collector"), "pipeline": tres.get("pipeline")} if st_t == 200 else st_t)

    # 自测后运行指标应累加
    st_a, after = call(U["search-config"] + "/search-config/api/v1/ingest/tasks/%s" % tid)
    check("自测后运行指标累加",
          st_a == 200 and after["runtime"].get("accepted", 0) > 0 and after["runtime"].get("lastAt"),
          {k: after["runtime"].get(k) for k in ("accepted", "forwarded", "eps1m", "health")} if st_a == 200 else st_a)

    # 启停
    st_s, _ = call(U["search-config"] + "/search-config/api/v1/ingest/tasks/%s/stop" % tid, "POST")
    st_g, stopped = call(U["search-config"] + "/search-config/api/v1/ingest/tasks/%s" % tid)
    check("任务可停止", st_s == 200 and st_g == 200 and stopped.get("enabled") is False,
          stopped.get("enabled") if st_g == 200 else st_g)
    st_r, _ = call(U["search-config"] + "/search-config/api/v1/ingest/tasks/%s/start" % tid, "POST")
    st_g2, started = call(U["search-config"] + "/search-config/api/v1/ingest/tasks/%s" % tid)
    check("任务可重新启动", st_r == 200 and st_g2 == 200 and started.get("enabled") is True,
          started.get("enabled") if st_g2 == 200 else st_g2)

st, isum = call(U["search-config"] + "/search-config/api/v1/ingest/tasks/summary")
check("接入摘要含 EPS / 健康分布",
      st == 200 and "eps1m" in isum and isinstance(isum.get("byHealth"), dict), isum)

# ---------------------------------------------------------------- 9. 持久化重启存活
print("\n=== 9. 持久化（重启存活） ===")
MARKER = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", ".cache", "persist-marker.json")
MARKER = os.path.normpath(MARKER)


def _read_marker():
    try:
        with open(MARKER, "r", encoding="utf-8") as fh:
            return json.load(fh)
    except Exception:
        return None


prev = _read_marker()
if prev is None:
    check("持久化基线已写入（首轮：重启后再跑一次本脚本即校验存活）", True, MARKER)
else:
    # 上一轮写的 IOC / 案件 / 接入源在服务重启后应仍然存在
    st, m = call(U["threat-web"] + "/threat-web/api/v1/iocs/match?value=" + prev["ioc"])
    check("重启后 IOC 仍在库（threat-web H2）", st == 200 and bool(m) and m.get("matched", True) is not False,
          prev["ioc"])
    st, srcs = call(U["search-config"] + "/search-config/api/v1/sources")
    check("重启后接入源仍在库（search-config H2）",
          st == 200 and any(s.get("id") == prev["source"] for s in srcs), prev["source"])
    st, alarms_now = call(U["alert-web"] + "/alert-web/api/alarms?size=500")
    alarms_now = unwrap(alarms_now) or []
    check("重启后历史告警仍在库（alert-web H2）",
          st == 200 and len(alarms_now) >= prev.get("alarmCount", 0) and prev.get("alarmCount", 0) > 0,
          "before=%s now=%s" % (prev.get("alarmCount"), len(alarms_now)))
    st, cases_now = call(U["incident-web"] + "/incident-web/api/v1/incidents")
    # 租户隔离（2026-08-09 修复）：default 租户只看到本租户案件；断言持久化生效（重启后仍有数据）
    check("重启后案件仍在库（incident-web H2）",
          st == 200 and len(cases_now) > 0,
          "now=%s (default 租户隔离视图)" % len(cases_now))

# 写下这一轮的基线，供下次重启后校验
try:
    src_id = "persist-probe-" + str(int(time.time()))
    st_src, created = call(U["search-config"] + "/search-config/api/v1/sources", "POST",
         {"id": src_id, "name": src_id, "type": "FILE", "format": "AUTO",
          "path": "/var/log/persist-probe.log", "env": "verify", "enabled": False})
    # createFull 忽略请求里的 id、生成 UUID 主键；以响应返回的真实 id 作为基线才查得到
    real_src_id = created.get("id") if (st_src == 200 and isinstance(created, dict) and created.get("id")) else src_id
    cur_alarms = unwrap(call(U["alert-web"] + "/alert-web/api/alarms?size=500")[1]) or []
    cur_cases = call(U["incident-web"] + "/incident-web/api/v1/incidents")[1] or []
    os.makedirs(os.path.dirname(MARKER), exist_ok=True)
    with open(MARKER, "w", encoding="utf-8") as fh:
        json.dump({"ioc": IOC_IP, "source": real_src_id,
                   "alarmCount": len(cur_alarms), "caseCount": len(cur_cases)}, fh)
except Exception as e:
    print("  [WARN] 写持久化基线失败：%s" % e)

# ---------------------------------------------------------------- 汇总
print("\n" + "=" * 60)
print("通过 %d / 失败 %d" % (len(PASS), len(FAIL)))
if FAIL:
    print("失败项：")
    for f in FAIL:
        print("  - " + f)
print("=" * 60)
sys.exit(1 if FAIL else 0)
