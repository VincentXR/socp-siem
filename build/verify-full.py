# -*- coding: utf-8 -*-
"""SOCP 全栈端到端验证：默认后端进程健康 + 采集→检测→告警→情报富化→通知→建案→SOAR 全链路。

用法： python socp/build/verify-full.py
前置： bash socp/build/run-all.sh backend  （默认 14 个进程全部 UP）

与 verify-slice.py 的分工：
  verify-slice.py  验证横切能力（鉴权/租户/审计/限流/追踪），只走网关 + alert-web。
  verify-full.py   验证业务全链路 + 新增的 THREAT / ATT&CK / 通知 / 案件 / 查找表 / 合规 能力。
"""
import atexit
import json
import os
import sys
import time
import random
import urllib.error
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor

PASS, FAIL = [], []


# 端口/地址唯一来源：build/ports.env（经 build/ports.py 读取）。
# 想换端口跑： SOCP_PORT_ALERT_WEB=28080 python build/verify-full.py
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from ports import SERVICES as SVC, base_url, health_url, GATEWAY_URL  # noqa: E402
from auth_client import login_token  # noqa: E402
from webhook_sink import webhook_fixture  # noqa: E402

#: 服务名 -> 基地址，供下面各用例拼 URL（不再出现任何硬编码端口）
U = {name: base_url(name) for name in SVC}


def _api_data(body):
    """统一响应信封 {code,message,data,traceId,timestamp}：code==0 返回 data，非 0 报错。"""
    if isinstance(body, dict) and "code" in body and "data" in body:
        if body.get("code") != 0:
            raise RuntimeError("API code=%s message=%s traceId=%s"
                               % (body.get("code"), body.get("message"), body.get("traceId")))
        return body["data"]
    return body


def call(url, method="GET", body=None, timeout=10, auth_token=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Authorization", "Bearer " + (auth_token if auth_token is not None else token()))
    req.add_header("X-Tenant-Id", "default")
    if data:
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            raw = r.read().decode("utf-8", "replace")
            try:
                return r.status, _api_data(json.loads(raw) if raw.strip() else {})
            except json.JSONDecodeError:
                return r.status, {"_raw": raw[:300]}
    except urllib.error.HTTPError as e:
        # 错误信封（如 404 + {code,message,...}）原样返回，由各断言的 st 守卫短路处理
        raw = e.read().decode("utf-8", "replace")
        try:
            return e.code, (json.loads(raw) if raw.strip() else {})
        except json.JSONDecodeError:
            return e.code, {"_raw": raw[:300]}
    except Exception as e:  # 连接失败
        return -1, {"_err": str(e)}


_TOKEN = {"t": None}


def token():
    """登录网关，从 HttpOnly session cookie 提取真 JWT。"""
    if _TOKEN["t"]:
        return _TOKEN["t"]
    _TOKEN["t"] = login_token(GATEWAY_URL, timeout=10)
    return _TOKEN["t"]


def check(name, cond, detail=""):
    (PASS if cond else FAIL).append(name)
    print(("  [PASS] " if cond else "  [FAIL] ") + name + (("  -> " + str(detail)[:220]) if detail else ""))


def unwrap(body):
    """统一分页对象 {items,total,page,size,totalPages} → items 列表；其余原样返回。"""
    if isinstance(body, dict) and isinstance(body.get("items"), list) and "total" in body:
        return body["items"]
    return body


def list_alarms():
    status, body = call(U["alert-web"] + "/alert-web/api/alarms?size=200")
    alarms = unwrap(body)
    if (status != 200 or not isinstance(alarms, list)
            or any(not isinstance(alarm, dict) or "id" not in alarm for alarm in alarms)):
        raise RuntimeError("alarm list failed: HTTP %s, response=%s" % (status, str(body)[:500]))
    return alarms


_SOAR_FIXTURE = {}


def cleanup_soar_fixture():
    rule_id = _SOAR_FIXTURE.get("ruleId")
    if rule_id:
        status, body = call(U["soar-web"] + "/soar-web/api/automation-rules/" + rule_id, "DELETE",
                            auth_token=_SOAR_FIXTURE.get("token"))
        check("清理 SOAR 探针规则", status in (200, 204), body if status not in (200, 204) else "")
        if status in (200, 204):
            _SOAR_FIXTURE.pop("ruleId", None)
    playbook_id = _SOAR_FIXTURE.get("playbookId")
    if playbook_id:
        status, body = call(U["soar-web"] + "/soar-web/api/playbooks/" + playbook_id,
                            "PATCH", {"status": "ARCHIVED"}, auth_token=_SOAR_FIXTURE.get("token"))
        check("归档 SOAR 探针剧本并保留运行证据", status == 200, body if status != 200 else "")
        if status == 200:
            _SOAR_FIXTURE.pop("playbookId", None)


def install_soar_fixture(entity):
    # Published automation is explicit configuration, never an assumed demo
    # default. This fixture only matches the probe entity and has no action node.
    atexit.register(cleanup_soar_fixture)
    fixture_token = login_token(GATEWAY_URL, os.environ.get("SOAR_VERIFY_USERNAME", "admin"),
                                os.environ.get("SOAR_VERIFY_PASSWORD", "admin123"), timeout=10)
    _SOAR_FIXTURE["token"] = fixture_token
    name = "Full-stack alert probe " + str(time.time_ns())
    status, draft = call(U["soar-web"] + "/soar-web/api/playbooks/import", "POST", {
        "name": name, "description": "Isolated event delivery verification", "tags": ["ci"],
        "definition": {
            "schemaVersion": "soar.playbook", "entryNodeId": "start",
            "limits": {"maxNodeExecutions": 20, "maxParallelism": 2},
            "nodes": [{"id": "start", "type": "START", "name": "Start"},
                      {"id": "end", "type": "END", "name": "End", "outcome": "SUCCEEDED"}],
            "edges": [{"from": "start", "to": "end"}],
        }, "layout": {},
    }, auth_token=fixture_token)
    if status not in (200, 201) or not draft.get("playbookId") or not draft.get("id"):
        raise RuntimeError("SOAR fixture import failed: %s %s" % (status, draft))
    _SOAR_FIXTURE.update(playbookId=draft["playbookId"], versionId=draft["id"])
    version_path = "/soar-web/api/playbooks/%s/versions/%s" % (draft["playbookId"], draft["version"])
    status, _ = call(U["soar-web"] + version_path + "/publish", "POST")
    check("分析员不能发布 SOAR 剧本", status == 403, status)
    status, published = call(U["soar-web"] + version_path + "/publish", "POST", auth_token=fixture_token)
    if status != 200 or published.get("status") != "PUBLISHED":
        raise RuntimeError("SOAR fixture publish failed: %s %s" % (status, published))
    status, rule = call(U["soar-web"] + "/soar-web/api/automation-rules", "POST", {
        "name": name, "triggerType": "alert.created", "priority": 1, "enabled": True,
        "conditions": {"field": "data.entity", "operator": "equals", "value": entity},
        "actions": [{"playbookVersionId": draft["id"]}], "suppression": {},
    }, auth_token=fixture_token)
    if status not in (200, 201) or not rule.get("id"):
        raise RuntimeError("SOAR fixture rule failed: %s %s" % (status, rule))
    _SOAR_FIXTURE["ruleId"] = rule["id"]
    check("SOAR 已发布探针剧本和限定实体的触发规则", True, name)


def run_verification(webhook_target):
    """Run mutating probes only when explicitly invoked against a disposable stack."""
    _WEBHOOK_TARGET = webhook_target
    PASS.clear()
    FAIL.clear()
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
    tactics = unwrap(tactics)
    check("战术目录 14 项", st == 200 and len(tactics) == 14, len(tactics) if st == 200 else st)
    st, techs = call(U["attack-web"] + "/attack-web/api/v1/techniques")
    techs = unwrap(techs)
    st, rules = call(U["detect-web"] + "/detect-web/api/v1/rules")
    rules = unwrap(rules)
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
    chans = unwrap(chans)
    check("内置通知渠道存在", st == 200 and len(chans) >= 2, len(chans) if st == 200 else st)
    if st == 200:
        for channel in chans:
            if channel.get("enabled"):
                call(U["notify-web"] + "/notify-web/api/v1/channels/" + channel["id"] + "/toggle", "POST")
    st, fixture_channel = call(U["notify-web"] + "/notify-web/api/v1/channels", "POST", {
        "name": "Full-stack webhook sink", "type": "WEBHOOK",
        "target": _WEBHOOK_TARGET,
        "enabled": True, "description": "Hermetic full-stack verification fixture"})
    check("Webhook verification fixture is ready",
          st == 200 and fixture_channel.get("enabled") is True,
          fixture_channel if st == 200 else st)
    st, recovery_channel = call(U["notify-web"] + "/notify-web/api/v1/channels", "POST", {
        "name": "Full-stack recovery probe", "type": "WEBHOOK",
        "target": "http://127.0.0.1:1/unreachable",
        "enabled": True, "description": "Terminal receipt recovery verification fixture"})
    check("通知恢复探针以不可达目标启动",
          st == 200 and recovery_channel.get("enabled") is True,
          recovery_channel if st == 200 else st)

    # ---------------------------------------------------------------- 5. 全链路
    print("\n=== 5. 端到端：采集→检测→告警→富化→通知→建案→SOAR ===")
    install_soar_fixture(IOC_IP)
    before_alarms = list_alarms()
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
        cur = list_alarms()
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
            dlog_ = unwrap(dlog_) if st_ == 200 else []
            mine_ = [d for d in dlog_ if d.get("alarmId") == aid] if st_ == 200 else []
            return mine_ if any(d.get("type") == "WEBHOOK" and d.get("status") == "sent" for d in mine_) else None

        mine = wait_for(my_dispatch) or []
        check("通知已派发到渠道", len(mine) >= 1,
              [str(d.get("channel")) + ":" + str(d.get("status")) for d in mine])
        check("Webhook 渠道派发成功",
              any(d.get("type") == "WEBHOOK" and d.get("status") == "sent" for d in mine),
              [d for d in mine if d.get("type") == "WEBHOOK"])

        # A non-retryable/unknown Notify receipt must be explicitly reopened before
        # Alert can replay its durable delivery. Merely resetting alarm_delivery
        # would otherwise return the cached terminal receipt without a network call.
        def notify_dead_delivery():
            status_, deliveries_ = call(
                U["alert-web"] + "/alert-web/api/alarms/%s/deliveries" % aid)
            if status_ != 200 or not isinstance(deliveries_, list):
                return None
            return next((delivery for delivery in deliveries_
                         if delivery.get("destination") == "NOTIFY"
                         and delivery.get("status") == "DEAD"), None)

        dead_notify = wait_for(notify_dead_delivery)
        check("通知永久失败会形成可管理的 DEAD 投递",
              dead_notify is not None,
              dead_notify if dead_notify else "未观察到 NOTIFY DEAD 回执")
        recovery_channel_id = recovery_channel.get("id") if isinstance(recovery_channel, dict) else None
        if dead_notify and recovery_channel_id:
            st_fix, fixed_channel = call(
                U["notify-web"] + "/notify-web/api/v1/channels/" + recovery_channel_id,
                "PUT", {
                    "name": recovery_channel["name"], "type": recovery_channel["type"],
                    "target": _WEBHOOK_TARGET, "enabled": True,
                    "description": recovery_channel.get("description")})
            check("管理员可修正失败渠道配置",
                  st_fix == 200 and fixed_channel.get("target") == _WEBHOOK_TARGET,
                  fixed_channel if st_fix == 200 else st_fix)
            st_requeue, replayed_delivery = call(
                U["alert-web"] + "/alert-web/api/admin/outbox/alarm-deliveries/%s/requeue"
                % dead_notify["deliveryId"], "POST", {
                    "reason": "full-stack fixture corrected", "confirmUnknown": True},
                auth_token=_SOAR_FIXTURE.get("token"))
            check("Alert 重投先重开 Notify 终态再重置本地投递",
                  st_requeue == 200 and replayed_delivery.get("status") == "PENDING",
                  replayed_delivery if st_requeue == 200 else st_requeue)

            def notify_delivered():
                status_, deliveries_ = call(
                    U["alert-web"] + "/alert-web/api/alarms/%s/deliveries" % aid)
                if status_ != 200 or not isinstance(deliveries_, list):
                    return None
                return next((delivery for delivery in deliveries_
                             if delivery.get("destination") == "NOTIFY"
                             and delivery.get("status") == "DELIVERED"), None)

            recovered_delivery = wait_for(notify_delivered)
            check("修正配置后历史失败通知发生真实重发并完成",
                  recovered_delivery is not None,
                  recovered_delivery if recovered_delivery else "NOTIFY 未恢复为 DELIVERED")

            st_log, recovery_log = call(
                U["notify-web"] + "/notify-web/api/v1/dispatch-log?page=1&size=500")
            recovery_log = unwrap(recovery_log) if st_log == 200 else []
            channel_log = [entry for entry in recovery_log
                           if entry.get("alarmId") == aid
                           and entry.get("channelId") == recovery_channel_id]
            delivery_ids = {entry.get("deliveryId") for entry in channel_log
                            if entry.get("deliveryId")}
            check("恢复历史保留失败、人工重开与新投递诊断",
                  any(entry.get("status") in ("failed", "unknown") for entry in channel_log)
                  and any(entry.get("status") == "requeued" for entry in channel_log)
                  and any(entry.get("status") == "sent" for entry in channel_log)
                  and len(delivery_ids) >= 2,
                  [{key: entry.get(key) for key in
                    ("status", "deliveryId", "httpStatus", "errorCode", "retryable")}
                   for entry in channel_log])
            call(U["notify-web"] + "/notify-web/api/v1/channels/" + recovery_channel_id,
                 "DELETE")

        def my_case():
            # Incident summaries intentionally expose bounded association counts,
            # not every historical alarm ID. Prove the exact persisted reverse
            # link instead of scanning an arbitrary list page.
            st_, incident_ = call(
                U["incident-web"] + "/incident-web/api/v1/incidents/by-alarm?alarmId="
                + urllib.parse.quote(aid, safe=""))
            return incident_ if st_ == 200 and incident_.get("id") else None

        mycase = wait_for(my_case)
        check("告警自动归并为案件", mycase is not None, mycase.get("id") if mycase else "未建案")
        if mycase:
            timeline_status, timeline_body = call(
                U["incident-web"] + "/incident-web/api/v1/incidents/%s/timeline?page=1&size=100" % mycase["id"])
            timeline = unwrap(timeline_body)
            if timeline_status != 200 or not isinstance(timeline, list):
                raise RuntimeError("case timeline failed: %s %s" % (timeline_status, timeline_body))
            alarm_evs = [e for e in timeline if e.get("type") == "ALARM"]
            check("案件时间线无重复（幂等：同一告警不重复入链）",
                  any(e.get("alarmId") == aid for e in alarm_evs)
                  and len(alarm_evs) == len({e.get("alarmId") for e in alarm_evs}),
                  "timeline_alarm=%d distinct_alarmId=%d" % (len(alarm_evs), len({e.get("alarmId") for e in alarm_evs})))
            check("案件时间线含 ATT&CK 标注",
                  any("[T" in str(e.get("message", "")) for e in alarm_evs),
                  alarm_evs[0].get("message", "")[:100] if alarm_evs else "")

        # One investigation result has exactly one case target. Competing analyst
        # requests for different cases must produce one side effect and one 409,
        # with the receipt agreeing with the persisted target.
        st_inv, investigation = call(
            U["ai-assistant"] + "/ai-assistant/api/v1/ai/investigations", "POST",
            {"alertId": aid})
        investigation_id = investigation.get("investigationId") if st_inv == 200 else None
        check("真实告警可生成持久化 AI 研判",
              st_inv == 200 and investigation_id
              and investigation.get("status") in ("COMPLETED", "PARTIAL"),
              investigation if st_inv == 200 else st_inv)
        if investigation_id:
            incident_ids = []
            for suffix in ("A", "B"):
                st_case, created_case = call(
                    U["incident-web"] + "/incident-web/api/v1/incidents", "POST", {
                        "title": "AI append race %s %s" % (suffix, time.time_ns()),
                        "entity": IOC_IP, "severity": "HIGH", "assignee": "admin"})
                case_id = (created_case.get("case") or {}).get("id") if st_case == 200 else None
                if case_id:
                    incident_ids.append(case_id)
            check("AI 并发入案验收已准备两个真实案件", len(incident_ids) == 2, incident_ids)
            if len(incident_ids) == 2:
                def append_to_case(case_id):
                    return call(
                        U["ai-assistant"]
                        + "/ai-assistant/api/v1/ai/investigations/%s/append-to-incident"
                        % investigation_id, "POST", {"incidentId": case_id})

                with ThreadPoolExecutor(max_workers=2) as pool:
                    append_results = list(pool.map(append_to_case, incident_ids))
                successful = [(status_, body_) for status_, body_ in append_results if status_ == 200]
                conflicts = [(status_, body_) for status_, body_ in append_results if status_ == 409]
                st_saved, saved_investigation = call(
                    U["ai-assistant"]
                    + "/ai-assistant/api/v1/ai/investigations/" + investigation_id)
                target_id = successful[0][1].get("incidentId") if successful else None
                check("不同目标的并发 AI 入案只有一个成功且另一请求冲突",
                      len(successful) == 1 and len(conflicts) == 1,
                      [(status_, body_.get("message") if isinstance(body_, dict) else body_)
                       for status_, body_ in append_results])
                check("AI 入案回执目标与数据库最终目标一致",
                      st_saved == 200 and target_id in incident_ids
                      and saved_investigation.get("incidentId") == target_id,
                      {"response": target_id,
                       "persisted": saved_investigation.get("incidentId") if st_saved == 200 else st_saved})
                note_counts = {}
                for case_id in incident_ids:
                    timeline_status, timeline_body = call(
                        U["incident-web"]
                        + "/incident-web/api/v1/incidents/%s/timeline?page=1&size=100" % case_id)
                    timeline = unwrap(timeline_body) if timeline_status == 200 else []
                    note_counts[case_id] = len([
                        event for event in timeline
                        if event.get("idempotencyKey") == "note:" + investigation_id])
                check("AI 摘要只写入最终目标且重复副作用被稳定身份约束",
                      note_counts.get(target_id) == 1
                      and sum(note_counts.values()) == 1,
                      note_counts)

        last_soar_runs = []

        def my_execs():
            # SOAR is the production path. A passing full-stack check must prove
            # that an alert reached the durable Run projection.
            st_, payload = call(U["soar-web"] + "/soar-web/api/runs?size=200")
            data = unwrap(payload)
            if st_ != 200 or not isinstance(data, list):
                return None
            # Prove this alert reached SOAR, rather than accepting an unrelated
            # historical Run that happened to exist in the tenant.
            matched = []
            for run in data:
                subject = run.get("subject") if isinstance(run, dict) else None
                if (isinstance(subject, dict) and subject.get("id") == aid
                        and run.get("playbookVersionId") == _SOAR_FIXTURE.get("versionId")):
                    matched.append(run)
            last_soar_runs[:] = matched
            return matched if any(run.get("status") == "SUCCEEDED" and run.get("temporalWorkflowId")
                                  for run in matched) else None

        execs = wait_for(my_execs) or []
        check("SOAR durable Run 已接收", len(last_soar_runs) > 0,
              [e.get("status") for e in last_soar_runs][:3])
        check("告警触发的 SOAR Run 已通过 Temporal 执行完成", len(execs) > 0,
              [e.get("status") for e in last_soar_runs][:3])

    cleanup_soar_fixture()

    # ---------------------------------------------------------------- 6. 查找表 / 合规
    print("\n=== 6. 查找表与合规 ===")
    st, sets = call(U["search-config"] + "/search-config/api/v1/reference-sets")
    sets = unwrap(sets)
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
    wls = unwrap(wls)
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
    ents = unwrap(ents)
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
    # Treat source editing as a complete replacement contract and prove that an
    # unchanged optional field survives a real create -> edit -> refresh -> query.
    source_probe = "full-stack-source-%s" % time.time_ns()
    source_payload = {
        "name": source_probe, "type": "FILE", "format": "AUTO",
        "path": "/var/log/%s.log" % source_probe, "address": None, "topic": None,
        "env": "verify", "enabled": False, "readFrom": "beginning",
        "multiline": None, "sinkTargetId": None, "parseRuleIds": [],
        "description": "preserve this description", "protocol": "tcp",
        "charset": "utf-8", "timeField": "event.created", "timezone": "UTC",
        "tags": ["full-stack", "source-edit"], "frequency": 7,
        "categoryId": None, "groupId": None,
    }
    st_source, created_source = call(
        U["search-config"] + "/search-config/api/v1/sources", "POST", source_payload)
    source_id = created_source.get("id") if st_source == 200 else None
    check("真实 API 新增日志源并持久化完整字段",
          st_source == 200 and source_id
          and created_source.get("description") == source_payload["description"]
          and created_source.get("timeField") == source_payload["timeField"],
          created_source if st_source == 200 else st_source)
    if source_id:
        edited_payload = dict(source_payload)
        edited_payload["name"] = source_probe + "-renamed"
        st_edit, edited_source = call(
            U["search-config"] + "/search-config/api/v1/sources/" + source_id,
            "PUT", edited_payload)
        edited_source = edited_source.get("source", {}) if st_edit == 200 else edited_source
        st_get, fetched_source = call(
            U["search-config"] + "/search-config/api/v1/sources/" + source_id)
        fetched_source = fetched_source.get("source", {}) if st_get == 200 else fetched_source
        st_list, source_page = call(
            U["search-config"] + "/search-config/api/v1/sources?page=1&size=20&q="
            + urllib.parse.quote(edited_payload["name"], safe=""))
        source_items = unwrap(source_page) if st_list == 200 else []
        check("编辑名称后未修改的描述、时间字段和采集配置完整保留",
              st_edit == 200 and st_get == 200
              and fetched_source.get("description") == source_payload["description"]
              and fetched_source.get("timeField") == source_payload["timeField"]
              and fetched_source.get("path") == source_payload["path"]
              and fetched_source.get("tags") == source_payload["tags"],
              fetched_source)
        check("保存后列表刷新和重新查询返回同一持久化日志源",
              st_list == 200 and len(source_items) == 1
              and source_items[0].get("id") == source_id,
              source_items)
        call(U["search-config"] + "/search-config/api/v1/sources/" + source_id, "DELETE")

    # A rejected save followed by a corrected retry must not leave a partial row
    # or create duplicates under the same user task.
    retry_name = "full-stack-retry-%s" % time.time_ns()
    invalid_source = dict(source_payload)
    invalid_source.update(name=retry_name, description="x" * 2001)
    st_invalid, _ = call(
        U["search-config"] + "/search-config/api/v1/sources", "POST", invalid_source)
    valid_source = dict(invalid_source)
    valid_source["description"] = "corrected after validation failure"
    st_retry, retried_source = call(
        U["search-config"] + "/search-config/api/v1/sources", "POST", valid_source)
    st_retry_list, retry_page = call(
        U["search-config"] + "/search-config/api/v1/sources?page=1&size=20&q="
        + urllib.parse.quote(retry_name, safe=""))
    retry_items = unwrap(retry_page) if st_retry_list == 200 else []
    check("日志源保存失败后修正重试最终只落一条记录",
          st_invalid == 400 and st_retry == 200 and len(retry_items) == 1
          and retry_items[0].get("id") == retried_source.get("id"),
          {"invalid": st_invalid, "retry": st_retry, "matches": len(retry_items)})
    if st_retry == 200 and retried_source.get("id"):
        call(U["search-config"] + "/search-config/api/v1/sources/"
             + retried_source["id"], "DELETE")

    st, tasks = call(U["search-config"] + "/search-config/api/v1/ingest/tasks")
    tasks = unwrap(tasks)
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
        # 前一次探针写入的 IOC、案件和接入源在服务重启后应仍然存在。
        st, m = call(U["threat-web"] + "/threat-web/api/v1/iocs/match?value=" + prev["ioc"])
        check("重启后 IOC 仍在库（threat-web H2）", st == 200 and bool(m) and m.get("matched", True) is not False,
              prev["ioc"])
        st, srcs = call(U["search-config"] + "/search-config/api/v1/sources")
        srcs = unwrap(srcs)
        check("重启后接入源仍在库（search-config H2）",
              st == 200 and any(s.get("id") == prev["source"] for s in srcs), prev["source"])
        st, alarms_now = call(U["alert-web"] + "/alert-web/api/alarms?size=500")
        alarms_now = unwrap(alarms_now) if st == 200 else []
        check("重启后历史告警仍在库（alert-web H2）",
              st == 200 and len(alarms_now) >= prev.get("alarmCount", 0) and prev.get("alarmCount", 0) > 0,
              "before=%s now=%s" % (prev.get("alarmCount"), len(alarms_now)))
        st, cases_now = call(U["incident-web"] + "/incident-web/api/v1/incidents")
        cases_now = unwrap(cases_now) if st == 200 else []
        # default 租户只看到本租户案件，并在重启后保持持久化数据。
        check("重启后案件仍在库（incident-web H2）",
              st == 200 and len(cases_now) > 0,
              "now=%s (default 租户隔离视图)" % len(cases_now))

    # 保存持久化探针基线，供重启验证。
    try:
        src_id = "persist-probe-" + str(int(time.time()))
        st_src, created = call(U["search-config"] + "/search-config/api/v1/sources", "POST",
             {"id": src_id, "name": src_id, "type": "FILE", "format": "AUTO",
              "path": "/var/log/persist-probe.log", "env": "verify", "enabled": False})
        # createFull 忽略请求中的 id 并生成 UUID 主键；基线必须使用响应返回的真实 id。
        real_src_id = created.get("id") if (st_src == 200 and isinstance(created, dict) and created.get("id")) else src_id
        cur_alarms = unwrap(call(U["alert-web"] + "/alert-web/api/alarms?size=500")[1]) or []
        cur_cases = unwrap(call(U["incident-web"] + "/incident-web/api/v1/incidents")[1]) or []
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
    return 1 if FAIL else 0


def main():
    with webhook_fixture(os.environ.get("SOCP_WEBHOOK_FIXTURE_URL", "")) as target:
        return run_verification(target)


if __name__ == "__main__":
    raise SystemExit(main())
