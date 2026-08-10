# -*- coding: utf-8 -*-
"""SOCP 端口表的 Python 侧读取器 —— 解析 build/ports.env（唯一来源），不重复维护端口。

用法：
    from ports import SERVICES, port_of, base_url, health_url, GATEWAY_URL

优先级：环境变量 SOCP_PORT_<SERVICE> > ports.env 里的默认值。
所以 `SOCP_PORT_ALERT_WEB=28080 python build/verify-full.py` 可以直接换端口跑，
不用改任何源码。
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

#: 统一北向入口。前端代理、服务间换 token、验证脚本登录都用它。
GATEWAY_URL = os.environ.get("SOCP_GATEWAY_URL") or base_url("api-gateway")

FRONTEND_PORT = int(os.environ.get("SOCP_PORT_FRONTEND_WORKBENCH")
                    or _DEFAULTS.get("SOCP_PORT_FRONTEND_WORKBENCH", 5173))
