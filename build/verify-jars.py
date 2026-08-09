# -*- coding: utf-8 -*-
"""校验所有后端服务 jar 是否为可运行 fat jar（含 Main-Class + BOOT-INF）。
背景：增量 package 时 spring-boot repackage 的 up-to-date 判断不可靠，
可能随机产出"瘦 jar"（几十 KB、无主清单）导致 java -jar 起不来。
用法：python socp/build/verify-jars.py  （退出码 0=全部正常）
"""
import glob
import os
import sys
import zipfile

root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
jars = sorted(glob.glob(os.path.join(root, "services", "*", "target", "*-1.0.0-SNAPSHOT.jar")))
bad = []
for j in jars:
    with zipfile.ZipFile(j) as z:
        has_main = "META-INF/MANIFEST.MF" in z.namelist() and b"Main-Class" in z.read("META-INF/MANIFEST.MF")
        boot = any(n.startswith("BOOT-INF/") for n in z.namelist())
        if not (has_main and boot):
            bad.append(os.path.basename(os.path.dirname(os.path.dirname(j))) + "  (" + j + ")")

print("jar 总数: %d" % len(jars))
if bad:
    print("BAD(%d) 瘦 jar（需 clean package 重建）:" % len(bad))
    for b in bad:
        print("  " + b)
    sys.exit(1)
print("全部 %d 个 jar 均为可运行 fat jar" % len(jars))
