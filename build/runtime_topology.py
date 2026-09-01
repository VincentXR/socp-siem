"""Validation and reporting for SOCP code modules versus deployment units."""

from __future__ import annotations

import argparse
from collections import Counter
import json
from pathlib import Path
import re
import sys
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
TOPOLOGY = ROOT / "build" / "runtime-topology.json"
PORTS = ROOT / "build" / "ports.env"


def quoted_list(text: str, name: str) -> list[str]:
    match = re.search(rf'^{name}="([^"]+)"$', text, re.MULTILINE)
    if not match:
        raise ValueError(f"missing {name}")
    return match.group(1).split()


def current_registry() -> tuple[list[str], list[str]]:
    text = PORTS.read_text(encoding="utf-8")
    return quoted_list(text, "SOCP_MODULE_NAMES"), quoted_list(text, "SOCP_SERVICE_NAMES")


def load_topology() -> dict[str, Any]:
    return json.loads(TOPOLOGY.read_text(encoding="utf-8"))


def validate_registry(modules: list[str], services: list[str]) -> list[str]:
    errors: list[str] = []
    if len(modules) != len(set(modules)) or len(services) != len(set(services)):
        errors.append("module/process registry contains duplicate names")
    module_set = set(modules)
    service_set = set(services)
    if not service_set.issubset(module_set):
        errors.append(
            f"default processes are not executable modules: {sorted(service_set - module_set)}"
        )
    expected_modules = {path.parent.name for path in ROOT.glob("services/*/pom.xml")}
    if module_set != expected_modules:
        errors.append(
            "executable module registry drift: "
            f"missing={sorted(expected_modules - module_set)} "
            f"extra={sorted(module_set - expected_modules)}"
        )
    return errors


def validate_topology(topology: dict[str, Any], modules: list[str],
                      services: list[str]) -> list[str]:
    errors: list[str] = []
    module_set = set(modules)
    service_set = set(services)
    frontend = topology.get("frontend")
    units = topology.get("units")
    compatibility = topology.get("compatibilityModules")

    if topology.get("schemaVersion") != 1:
        errors.append("runtime topology schemaVersion must be 1")
    if not isinstance(frontend, str) or not frontend.strip():
        errors.append("runtime topology must declare one frontend path")
        frontend = ""
    if not isinstance(units, list) or not units:
        errors.append("runtime topology must declare deployment units")
        units = []
    if not isinstance(compatibility, list) or not all(isinstance(item, str) for item in compatibility):
        errors.append("runtime topology compatibilityModules must be a string list")
        compatibility = []

    unit_names: list[str] = []
    members: list[str] = []
    for unit in units:
        if not isinstance(unit, dict):
            errors.append("runtime topology unit must be an object")
            continue
        name = unit.get("name")
        entries = unit.get("members")
        if not isinstance(name, str) or not name.strip():
            errors.append("runtime topology unit must have a name")
        else:
            unit_names.append(name)
        if not isinstance(entries, list) or not entries or not all(isinstance(item, str) for item in entries):
            errors.append(f"runtime topology unit {name or '<unnamed>'} must have string members")
            continue
        members.extend(entries)

    duplicate_units = sorted(name for name, count in Counter(unit_names).items() if count > 1)
    if duplicate_units:
        errors.append(f"duplicate runtime unit names: {duplicate_units}")
    duplicate_members = sorted(name for name, count in Counter(members).items() if count > 1)
    if duplicate_members:
        errors.append(f"modules assigned to multiple runtime units: {duplicate_members}")

    allowed_members = module_set | ({frontend} if frontend else set())
    unknown_members = sorted(set(members) - allowed_members)
    if unknown_members:
        errors.append(f"runtime units contain unknown members: {unknown_members}")
    assigned_services = set(members) & module_set
    if assigned_services != service_set:
        errors.append(
            "runtime unit service assignment drift: "
            f"missing={sorted(service_set - assigned_services)} "
            f"extra={sorted(assigned_services - service_set)}"
        )
    if frontend and members.count(frontend) != 1:
        errors.append("frontend must be assigned to exactly one runtime unit")

    compatibility_set = set(compatibility)
    expected_compatibility = module_set - service_set
    if len(compatibility) != len(compatibility_set):
        errors.append("compatibilityModules contains duplicate names")
    if compatibility_set != expected_compatibility:
        errors.append(
            "compatibility module drift: "
            f"missing={sorted(expected_compatibility - compatibility_set)} "
            f"extra={sorted(compatibility_set - expected_compatibility)}"
        )
    assigned_compatibility = sorted(compatibility_set & set(members))
    if assigned_compatibility:
        errors.append(
            "compatibility launchers cannot be target runtime members: "
            f"{assigned_compatibility}"
        )
    return errors


def topology_report() -> dict[str, Any]:
    modules, services = current_registry()
    topology = load_topology()
    units = topology.get("units", [])
    return {
        "currentExecutableModules": len(modules),
        "currentDefaultProcesses": len(services),
        "targetDeploymentUnits": len(units) if isinstance(units, list) else 0,
        "units": {
            unit.get("name", "<unnamed>"): unit.get("members", [])
            for unit in units if isinstance(unit, dict)
        },
        "compatibilityModules": topology.get("compatibilityModules", []),
        "status": topology.get("status"),
        "errors": validate_registry(modules, services)
        + validate_topology(topology, modules, services),
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--json", action="store_true")
    parser.add_argument("--check", action="store_true",
                        help="validate the topology contract without changing runtime state")
    args = parser.parse_args()
    report = topology_report()
    if args.json:
        print(json.dumps(report, ensure_ascii=False, indent=2))
    elif report["errors"]:
        print("Runtime topology contract failed:", file=sys.stderr)
        for error in report["errors"]:
            print(f"  - {error}", file=sys.stderr)
    else:
        print(
            f"Runtime topology contract passed: executable modules="
            f"{report['currentExecutableModules']}, current processes="
            f"{report['currentDefaultProcesses']}, target units="
            f"{report['targetDeploymentUnits']}, compatibility launchers="
            f"{len(report['compatibilityModules'])}"
        )
    return 1 if report["errors"] else 0


if __name__ == "__main__":
    raise SystemExit(main())
