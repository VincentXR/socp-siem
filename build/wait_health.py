# -*- coding: utf-8 -*-
"""等待所有 SOCP 后端服务健康（部分受限 shell 里 sleep 不可用，用 python 轮询）。

端口来源：build/ports.env（唯一来源），本文件不再自己维护端口表。
"""
import os
import sys
import time
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from ports import SERVICES, health_url  # noqa: E402

# 主目标：threat-web（启动最慢、最易崩），它起来了基本说明其余也差不多了
PRIMARY = "threat-web"
DEADLINE_SEC = int(os.environ.get("SOCP_WAIT_TIMEOUT", "90"))


def up(service):
    try:
        req = urllib.request.Request(health_url(service))
        return urllib.request.urlopen(req, timeout=3).status == 200
    except Exception:
        return False


started = time.time()
deadline = started + DEADLINE_SEC
primary_ok = False
while time.time() < deadline:
    if up(PRIMARY):
        primary_ok = True
        break
    time.sleep(2)
print("%s UP=%s (waited ~%ds)" % (PRIMARY, primary_ok, int(time.time() - started)))

# 主目标没起也仍然给全量快照，方便一眼看出是个别服务问题还是全挂
print("\n--- 全量健康快照 ---")
allup = 0
for name, port in SERVICES.items():
    ok = up(name)
    allup += 1 if ok else 0
    print(("  UP  " if ok else "  DOWN") + " %-14s :%d" % (name, port))
print("\n总 UP: %d / %d" % (allup, len(SERVICES)))
sys.exit(0 if (primary_ok and allup == len(SERVICES)) else 1)
