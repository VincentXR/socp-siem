# -*- coding: utf-8 -*-
"""SOCP 纵切端到端验证：鉴权 / 多租户 / 审计 / 存储 / 限流 / 链路追踪。

用法： python build/verify-slice.py [网关地址]
网关地址默认取 build/ports.env（唯一来源），所有请求均经网关转发到 alert-web。
"""
import json
import os
import sys
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from ports import GATEWAY_URL  # noqa: E402
from auth_client import login_token  # noqa: E402

GW = sys.argv[1] if len(sys.argv) > 1 else GATEWAY_URL
BASE = GW + "/alert-web/api/alarms"
PASS, FAIL = [], []


_TOKEN = {"t": None}
_AUTO_TOKEN = object()


def real_token():
    """登录网关，从 HttpOnly session cookie 提取真 JWT。"""
    if _TOKEN["t"]:
        return _TOKEN["t"]
    _TOKEN["t"] = login_token(GW, timeout=10)
    return _TOKEN["t"]


def call(method="GET", path="", token=_AUTO_TOKEN, tenant="t1", body=None):
    """返回 (http_status, headers, body_dict)。异常状态码不抛出，统一返回。
    未显式传 token 时自动用真实登录 token。"""
    url = BASE + path
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    if token is _AUTO_TOKEN:
        token = real_token()
    if token:
        req.add_header("Authorization", "Bearer " + token)
    if tenant:
        req.add_header("X-Tenant-Id", tenant)
    if data:
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            raw = r.read().decode("utf-8", "replace")
            return r.status, dict(r.headers), (json.loads(raw) if raw.strip() else {})
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "replace")
        try:
            parsed = json.loads(raw) if raw.strip() else {}
        except json.JSONDecodeError:
            parsed = {"_raw": raw[:200]}
        return e.code, dict(e.headers), parsed


def check(name, cond, detail=""):
    (PASS if cond else FAIL).append(name)
    print(("  [PASS] " if cond else "  [FAIL] ") + name + (("  -> " + detail) if detail else ""))


print("=" * 68)
print("SOCP 纵切端到端验证   网关: " + GW)
print("=" * 68)

# ---------- 1. 鉴权 ----------
print("\n[1] 鉴权（AuthInterceptor）")
st, hd, bd = call(token=None)
check("无 Bearer 令牌被网关拒绝（401 未认证）", st == 401,
      "HTTP=%s msg=%s" % (st, bd.get("message")))
check("网关拒绝时仍返回统一响应体 ApiResult", bd.get("code") == 401 and bool(bd.get("message")),
      "body=%s" % json.dumps(bd, ensure_ascii=False)[:120])
check("被拒请求同样可追溯（带 traceId）", bool(bd.get("traceId")), "traceId=%s" % bd.get("traceId"))
st, hd, bd = call()
check("带 Bearer 令牌放行", st == 200 and bd.get("code") == 0, "HTTP=%s code=%s" % (st, bd.get("code")))

# ---------- 2. 写入 + occurredAt ----------
print("\n[2] 告警写入（存储 + 审计 + occurredAt 尊重入参）")
OCCURRED = "2026-01-15T08:30:00Z"
st, hd, bd = call("POST", tenant="t1", body={
    "ruleId": "R-SSH-BRUTE", "ruleName": "SSH 暴力破解", "severity": "HIGH",
    "message": "10 分钟内 50 次失败登录", "entity": "10.0.0.7", "occurredAt": OCCURRED})
alarm = bd.get("data") or {}
check("POST 创建告警成功", st == 200 and bd.get("code") == 0, "HTTP=%s" % st)
check("已落库并返回 id", bool(alarm.get("id")), "id=%s" % alarm.get("id"))
check("occurredAt 尊重入参（不再被覆盖为 now）",
      str(alarm.get("occurredAt", "")).startswith("2026-01-15T08:30"),
      "occurredAt=%s" % alarm.get("occurredAt"))
st2, _, bd2 = call("POST", body={"ruleId": "R-PORTSCAN", "ruleName": "端口扫描",
                                 "severity": "MEDIUM", "message": "扫描 1024 端口", "entity": "10.0.0.9"})
check("occurredAt 缺省时回退服务端时间",
      bool((bd2.get("data") or {}).get("occurredAt")),
      "occurredAt=%s" % (bd2.get("data") or {}).get("occurredAt"))

# ---------- 3. 多租户隔离 ----------
print("\n[3] 多租户隔离（TenantContext + BaseEntity.tenantId）")
call("POST", tenant="t2", body={"ruleId": "R-T2", "ruleName": "租户2专属规则",
                                "severity": "LOW", "message": "仅 t2 可见", "entity": "192.168.1.1"})
time.sleep(1.1)  # 让限流桶回满，避免污染本组断言
_, _, t1 = call(tenant="t1")
time.sleep(1.1)
_, _, t2 = call(tenant="t2")
t1_rules = [a["ruleId"] for a in (t1.get("data") or [])]
t2_rules = [a["ruleId"] for a in (t2.get("data") or [])]
check("t1 看不到 t2 的告警", "R-T2" not in t1_rules, "t1=%s" % t1_rules)
check("t2 只看到自己的告警", t2_rules and all(r == "R-T2" for r in t2_rules), "t2=%s" % t2_rules)

# ---------- 4. 链路追踪 ----------
print("\n[4] 链路追踪（网关注入 traceId 并回写响应头）")
st, hd, bd = call()
hdr_trace = hd.get("X-Trace-Id") or hd.get("x-trace-id")
check("响应头带 X-Trace-Id", bool(hdr_trace), "X-Trace-Id=%s" % hdr_trace)
check("body.traceId 与响应头一致", bool(bd.get("traceId")) and bd.get("traceId") == hdr_trace,
      "body.traceId=%s" % bd.get("traceId"))

# ---------- 5. 限流 ----------
print("\n[5] 限流（@RateLimit permits=10 seconds=1，每租户独立配额）")
time.sleep(1.2)  # 桶回满


def burst(tenant, n=20):
    with ThreadPoolExecutor(max_workers=n) as ex:
        return list(ex.map(lambda _: call(tenant=tenant), range(n)))


res = burst("t1", 20)
ok_n = sum(1 for s, _, _ in res if s == 200)
limited = [(s, h, b) for s, h, b in res if s == 429]
check("突发 20 次出现 HTTP 429（不再被吞成 200）", len(limited) > 0,
      "200=%d  429=%d" % (ok_n, len(limited)))
check("放行数不超过桶容量+补充量（<=13）", ok_n <= 13, "放行 %d 次" % ok_n)
if limited:
    s, h, b = limited[0]
    check("429 响应带 Retry-After 头", bool(h.get("Retry-After")), "Retry-After=%s" % h.get("Retry-After"))
    check("429 body.code=429", b.get("code") == 429, "message=%s" % b.get("message"))

# 租户配额独立：t1 打满后 t2 仍应可用
res_t2 = burst("t2", 3)
check("t1 被限流不影响 t2（配额按租户隔离）", all(s == 200 for s, _, _ in res_t2),
      "t2 状态=%s" % [s for s, _, _ in res_t2])

# 补充速率：等 1.2s 后应恢复约 10 个令牌（验证 TokenBucket refill 修复）
time.sleep(1.2)
res2 = burst("t1", 12)
recovered = sum(1 for s, _, _ in res2 if s == 200)
check("等待 1.2s 后配额基本回满（refill 速率正确，>=8）", recovered >= 8,
      "恢复放行 %d 次（修复前每秒只补 1 个令牌）" % recovered)

print("\n" + "=" * 68)
print("通过 %d 项，失败 %d 项" % (len(PASS), len(FAIL)))
if FAIL:
    print("失败项：" + ", ".join(FAIL))
print("=" * 68)
sys.exit(1 if FAIL else 0)
