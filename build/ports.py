# -*- coding: utf-8 -*-
"""SOCP 端口表的 Python 侧读取器 —— 解析 build/ports.env（唯一来源），不重复维护端口。

用法：
    from ports import SERVICES, port_of, base_url, health_url, GATEWAY_URL
    from ports import SERVICE_ORDER, service_records, render_markdown_table
    python build/ports.py --markdown-table   # 交付文档（RELEASE.md）的端口表

优先级：环境变量 SOCP_PORT_<SERVICE> > ports.env 里的默认值。
所以 `SOCP_PORT_ALERT_WEB=28080 python build/verify-full.py` 可以直接换端口跑，
不用改任何源码。交付产物用 service_records()/render_markdown_table() 取默认值。
"""
import os
import re

_HERE = os.path.dirname(os.path.abspath(__file__))
_ENV_FILE = os.path.join(_HERE, "ports.env")

# SOCP_PORT_ALERT_WEB="${SOCP_PORT_ALERT_WEB:-18080}"
_PORT_RE = re.compile(r'^\s*(SOCP_PORT_[A-Z0-9_]+)="?\$\{\1:-(\d+)\}"?\s*$')
_NAMES_RE = re.compile(r'^\s*SOCP_SERVICE_NAMES="([^"]+)"\s*$')


def _parse():
    names, defaults = [], {}
    try:
        with open(_ENV_FILE, "r", encoding="utf-8") as f:
            for line in f:
                m = _NAMES_RE.match(line)
                if m:
                    names = m.group(1).split()
                    continue
                m = _PORT_RE.match(line)
                if m:
                    defaults[m.group(1)] = int(m.group(2))
    except OSError as e:  # ports.env 缺失属于严重配置错误，直接抛，不要静默兜底
        raise RuntimeError("读不到端口表 %s: %s" % (_ENV_FILE, e))
    if not names:
        raise RuntimeError("ports.env 里没找到 SOCP_SERVICE_NAMES")
    return names, defaults


_NAMES, _DEFAULTS = _parse()


def _key(service):
    return "SOCP_PORT_" + service.upper().replace("-", "_")


def port_of(service):
    """服务端口：环境变量优先，其次 ports.env 默认值。"""
    k = _key(service)
    v = os.environ.get(k)
    if v:
        return int(v)
    if k not in _DEFAULTS:
        raise KeyError("ports.env 里没有服务 %s（%s）" % (service, k))
    return _DEFAULTS[k]


def ctx_of(service):
    """context-path：api-gateway 挂根路径，其余等于服务名。"""
    return "" if service == "api-gateway" else service


def base_url(service, host="127.0.0.1"):
    return "http://%s:%d" % (host, port_of(service))


def health_url(service, host="127.0.0.1"):
    ctx = ctx_of(service)
    suffix = "/actuator/health" if not ctx else "/%s/actuator/health" % ctx
    return base_url(service, host) + suffix


#: 服务名 -> 端口（按 ports.env 里的启动顺序）
SERVICES = {name: port_of(name) for name in _NAMES}

#: ports.env 里 SOCP_SERVICE_NAMES 的声明顺序（启动顺序）。
SERVICE_ORDER = tuple(_NAMES)

#: 统一北向入口。前端代理、服务间换 token、验证脚本登录都用它。
GATEWAY_URL = os.environ.get("SOCP_GATEWAY_URL") or base_url("api-gateway")

FRONTEND_PORT = int(os.environ.get("SOCP_PORT_FRONTEND_WORKBENCH")
                    or _DEFAULTS.get("SOCP_PORT_FRONTEND_WORKBENCH", 5173))


def service_records(defaults_only=True):
    """[(服务名, 端口, context-path)]，顺序即 ports.env 的启动顺序。

    任何需要"逐服务列出端口"的产物（发布包 RELEASE.md、文档表格）都必须从这里渲染，
    而不是再手抄一份端口表：手抄副本没有门禁，端口一改即漂。

    defaults_only=True 时给出 ports.env 的出厂默认值（不受打包机环境变量影响），
    交付文档用的就是这一份；本地换端口跑栈时不看这份表。
    """
    records = []
    for name in SERVICE_ORDER:
        if defaults_only:
            key = _key(name)
            if key not in _DEFAULTS:
                raise KeyError("ports.env 里没有服务 %s（%s）" % (name, key))
            port = _DEFAULTS[key]
        else:
            port = port_of(name)
        records.append((name, port, ctx_of(name)))
    return records


def render_markdown_table():
    """渲染 Markdown 端口速查表（发布包 RELEASE.md 用，出厂默认端口）。"""
    lines = ["| 服务 | 默认端口 | context-path |", "| --- | --- | --- |"]
    for name, port, ctx in service_records():
        lines.append("| %s | %d | %s |" % (name, port, ctx or "（根路径）"))
    return "\n".join(lines) + "\n"


if __name__ == "__main__":  # pragma: no cover - CLI 只在打包/渲染时调用
    import argparse
    import sys

    # 产物会被重定向进 RELEASE.md：Windows 下 Python 默认按本地代码页（GBK）写管道，
    # 中文表头会变成乱码。交付文档必须是 UTF-8，所以显式固定编码与换行。
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", newline="\n")

    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--markdown-table", action="store_true",
                        help="print the service/default-port table as Markdown")
    parsed = parser.parse_args()
    if parsed.markdown_table:
        print(render_markdown_table(), end="")
    else:
        parser.print_help()
