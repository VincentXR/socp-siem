#!/usr/bin/env python3
"""Verify deployment, gateway, frontend health, and port registry contracts."""

from __future__ import annotations

from pathlib import Path
import re
import sys

from runtime_topology import topology_report


ROOT = Path(__file__).resolve().parents[1]


def quoted_list(text: str, name: str) -> list[str]:
    match = re.search(rf'^{name}="([^"]+)"$', text, re.MULTILINE)
    if not match:
        raise ValueError(f"missing {name}")
    return match.group(1).split()


def main() -> int:
    errors: list[str] = []
    ports_text = (ROOT / "build/ports.env").read_text(encoding="utf-8")
    try:
        services = quoted_list(ports_text, "SOCP_SERVICE_NAMES")
        modules = quoted_list(ports_text, "SOCP_MODULE_NAMES")
    except ValueError as error:
        print(f"[FAIL] {error}", file=sys.stderr)
        return 1

    if len(services) != len(set(services)) or len(modules) != len(set(modules)):
        errors.append("service/module registry contains duplicate names")
    if not set(services).issubset(modules):
        errors.append("default services must be a subset of executable modules")
    expected_modules = {path.parent.name for path in ROOT.glob("services/*/pom.xml")}
    if set(modules) != expected_modules:
        errors.append(f"SOCP_MODULE_NAMES drift: missing={sorted(expected_modules - set(modules))} extra={sorted(set(modules) - expected_modules)}")

    port_entries = re.findall(r'^SOCP_PORT_([A-Z0-9_]+)="\$\{[^:]+:-(\d+)\}"$', ports_text, re.MULTILINE)
    backend_ports = {name: int(port) for name, port in port_entries if name != "FRONTEND_WORKBENCH"}
    if len(backend_ports.values()) != len(set(backend_ports.values())):
        errors.append("backend port registry contains duplicate ports")
    for module in modules:
        key = module.upper().replace("-", "_")
        if key not in backend_ports:
            errors.append(f"{module}: missing SOCP_PORT entry")

    gateway = (ROOT / "services/api-gateway/src/main/resources/application.yml").read_text(encoding="utf-8")
    route_ids = set(re.findall(r"^\s+- id: ([a-z0-9-]+)$", gateway, re.MULTILINE))
    downstream = set(services) - {"api-gateway"}
    if not downstream.issubset(route_ids):
        errors.append(f"gateway misses default routes: {sorted(downstream - route_ids)}")
    # The split detection deployment has one deliberate auxiliary route.  It
    # is not a legacy URL alias: the browser still addresses /detect-web/**,
    # while the runtime-only paths must reach the worker.  Keep that route in
    # the contract explicitly so this check does not confuse it with the two
    # historical collector aliases.
    auxiliary_route_ids = {"detect-web-runtime"}
    legacy_route_ids = {"asset-collect", "hips-collect"}
    unexpected_routes = route_ids - downstream - auxiliary_route_ids
    if unexpected_routes != legacy_route_ids:
        errors.append(f"unexpected non-default routes: {sorted(unexpected_routes)}")
    if "detect-web-runtime" not in route_ids:
        errors.append("gateway runtime route missing: detect-web-runtime")
    for legacy, owner in (("asset-collect", "asset-web"), ("hips-collect", "hips-web")):
        expected = f"RewritePath=/{legacy}/?(?<segment>.*), /{owner}/$\\{{segment}}"
        if expected not in gateway:
            errors.append(f"gateway compatibility rewrite missing: {legacy} -> {owner}")

    health_registry = (
        ROOT / "frontend/apps/workbench/src/api/health.ts"
    ).read_text(encoding="utf-8")
    health_names = set(re.findall(r"\{ name: '([a-z0-9-]+)' \}", health_registry))
    if health_names != set(services):
        errors.append(f"frontend health registry drift: missing={sorted(set(services) - health_names)} extra={sorted(health_names - set(services))}")

    runtime = topology_report()
    errors.extend(f"runtime topology: {error}" for error in runtime["errors"])

    if errors:
        print("Contract gate failed:", file=sys.stderr)
        for error in errors:
            print(f"  - {error}", file=sys.stderr)
        return 1
    print(
        f"Contract gate passed: {len(modules)} modules, {len(services)} default processes, "
        f"{runtime['targetDeploymentUnits']} target units, {len(route_ids)} gateway routes"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
