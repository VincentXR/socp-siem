# -*- coding: utf-8 -*-
"""等待所有 SOCP 后端服务健康（bash sleep 不可用，用 python 轮询）。"""
import sys, time, urllib.request, urllib.error

SVC = {
    "alert-web": (18080, "alert-web"), "search-config": (18081, "search-config"),
    "detect-web": (18082, "detect-web"), "soar-web": (18083, "soar-web"),
    "report-web": (18084, "report-web"), "asset-web": (18085, "asset-web"),
    "soc-base": (18086, "soc-base"), "hips-web": (18087, "hips-web"),
    "ai-assistant": (18088, "ai-assistant"), "detect-model": (18090, "detect-model"),
    "asset-collect": (18091, "asset-collect"), "api-gateway": (18092, ""),
    "hips-collect": (18093, "hips-collect"), "threat-web": (18094, "threat-web"),
    "attack-web": (18095, "attack-web"), "notify-web": (18096, "notify-web"),
    "incident-web": (18097, "incident-web"),
}

def up(port, ctx):
    path = "/actuator/health" if not ctx else "/%s/actuator/health" % ctx
    url = "http://127.0.0.1:%d%s" % (port, path)
    try:
        return urllib.request.urlopen(urllib.request.Request(url), timeout=3).status == 200
    except Exception:
        return False

# 主目标：threat-web（最慢、最易崩）。最多等 90s。
deadline = time.time() + 90
ti_ok = False
while time.time() < deadline:
    if up(18094, "threat-web"):
        ti_ok = True
        break
    time.sleep(2)
print("threat-web UP=%s (waited ~%ds)" % (ti_ok, int(90 - (deadline - time.time()))))

# threat-web 没起则先报，但仍给出全量快照
print("\n--- 全量健康快照 ---")
allup = 0
for name, (port, ctx) in SVC.items():
    ok = up(port, ctx)
    allup += 1 if ok else 0
    print(("  UP  " if ok else "  DOWN") + " %-13s :%d" % (name, port))
print("\n总 UP: %d / %d" % (allup, len(SVC)))
sys.exit(0 if (ti_ok and allup == len(SVC)) else 1)
