"""Validation and reporting for SOCP modules, processes, and logical domains."""

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
EXPECTED_STATUS = "current-layout-with-evidence-gated-consolidation"
EXPECTED_POLICY_MODE = "evidence-gated-per-candidate"
ALLOWED_CANDIDATE_STATUSES = {"evaluate", "accepted", "implemented", "rejected"}


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
    domains = topology.get("logicalDomains")
    compatibility = topology.get("compatibilityModules")
    policy = topology.get("deploymentPolicy")
    candidates = topology.get("consolidationCandidates")
    completed = topology.get("completedConsolidations")

    if topology.get("schemaVersion") != 2:
        errors.append("runtime topology schemaVersion must be 2")
    if topology.get("status") != EXPECTED_STATUS:
        errors.append(f"runtime topology status must be {EXPECTED_STATUS}")
    if not isinstance(frontend, str) or not frontend.strip():
        errors.append("runtime topology must declare one frontend path")
        frontend = ""
    if not isinstance(domains, list) or not domains:
        errors.append("runtime topology must declare logical domains")
        domains = []
    if not isinstance(compatibility, list) or not all(isinstance(item, str) for item in compatibility):
        errors.append("runtime topology compatibilityModules must be a string list")
        compatibility = []
    if not isinstance(policy, dict):
        errors.append("runtime topology must declare a deploymentPolicy object")
        policy = {}
    if policy.get("mode") != EXPECTED_POLICY_MODE:
        errors.append(f"deploymentPolicy.mode must be {EXPECTED_POLICY_MODE}")
    if "fixedTargetProcessCount" not in policy or policy.get("fixedTargetProcessCount") is not None:
        errors.append("deploymentPolicy.fixedTargetProcessCount must be null")
    required_checks = policy.get("requiredChecks")
    if (not isinstance(required_checks, list) or not required_checks
            or not all(isinstance(item, str) and item for item in required_checks)
            or len(required_checks) != len(set(required_checks))):
        errors.append("deploymentPolicy.requiredChecks must be a unique non-empty string list")

    domain_names: list[str] = []
    members: list[str] = []
    for domain in domains:
        if not isinstance(domain, dict):
            errors.append("runtime topology logical domain must be an object")
            continue
        name = domain.get("name")
        entries = domain.get("members")
        if not isinstance(name, str) or not name.strip():
            errors.append("runtime topology logical domain must have a name")
        else:
            domain_names.append(name)
        if not isinstance(entries, list) or not entries or not all(isinstance(item, str) for item in entries):
            errors.append(f"logical domain {name or '<unnamed>'} must have string members")
            continue
        members.extend(entries)

    duplicate_domains = sorted(name for name, count in Counter(domain_names).items() if count > 1)
    if duplicate_domains:
        errors.append(f"duplicate logical domain names: {duplicate_domains}")
    duplicate_members = sorted(name for name, count in Counter(members).items() if count > 1)
    if duplicate_members:
        errors.append(f"modules assigned to multiple logical domains: {duplicate_members}")

    allowed_members = module_set | ({frontend} if frontend else set())
    unknown_members = sorted(set(members) - allowed_members)
    if unknown_members:
        errors.append(f"logical domains contain unknown members: {unknown_members}")
    assigned_services = set(members) & module_set
    if assigned_services != service_set:
        errors.append(
            "logical domain service assignment drift: "
            f"missing={sorted(service_set - assigned_services)} "
            f"extra={sorted(assigned_services - service_set)}"
        )
    if frontend and members.count(frontend) != 1:
        errors.append("frontend must be assigned to exactly one logical domain")

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
            "compatibility launchers cannot be logical domain members: "
            f"{assigned_compatibility}"
        )

    if not isinstance(candidates, list):
        errors.append("runtime topology consolidationCandidates must be a list")
        candidates = []
    candidate_names: list[str] = []
    for entry in candidates:
        if not isinstance(entry, dict):
            errors.append("consolidation candidate must be an object")
            continue
        name = entry.get("name")
        entries = entry.get("members")
        status = entry.get("status")
        if not isinstance(name, str) or not name.strip():
            errors.append("consolidation candidate must have a name")
        else:
            candidate_names.append(name)
        if (not isinstance(entries, list) or len(entries) < 2
                or not all(isinstance(item, str) for item in entries)
                or len(entries) != len(set(entries))):
            errors.append(f"consolidation candidate {name or '<unnamed>'} must have unique members")
        else:
            unknown = sorted(set(entries) - service_set)
            if unknown:
                errors.append(f"consolidation candidate {name} contains unknown services: {unknown}")
        if status not in ALLOWED_CANDIDATE_STATUSES:
            errors.append(f"consolidation candidate {name or '<unnamed>'} has invalid status: {status!r}")
    duplicate_candidates = sorted(
        name for name, count in Counter(candidate_names).items() if count > 1
    )
    if duplicate_candidates:
        errors.append(f"duplicate consolidation candidate names: {duplicate_candidates}")

    if not isinstance(completed, list):
        errors.append("runtime topology completedConsolidations must be a list")
        completed = []
    completed_names: list[str] = []
    retired_processes: list[str] = []
    for entry in completed:
        if not isinstance(entry, dict):
            errors.append("completed consolidation must be an object")
            continue
        name = entry.get("name")
        owner = entry.get("owner")
        retired = entry.get("retiredProcess")
        if not isinstance(name, str) or not name.strip():
            errors.append("completed consolidation must have a name")
        else:
            completed_names.append(name)
        if owner not in service_set:
            errors.append(
                f"completed consolidation {name or '<unnamed>'} has unknown owner: {owner!r}"
            )
        if not isinstance(retired, str) or not retired.strip():
            errors.append(
                f"completed consolidation {name or '<unnamed>'} must name a retired process"
            )
        else:
            retired_processes.append(retired)
            if retired in module_set:
                errors.append(
                    f"completed consolidation {name or '<unnamed>'} still lists executable "
                    f"module {retired} as retired"
                )
    duplicate_completed = sorted(
        name for name, count in Counter(completed_names).items() if count > 1
    )
    if duplicate_completed:
        errors.append(f"duplicate completed consolidation names: {duplicate_completed}")
    duplicate_retired = sorted(
        name for name, count in Counter(retired_processes).items() if count > 1
    )
    if duplicate_retired:
        errors.append(f"processes retired more than once: {duplicate_retired}")
    return errors


def topology_report() -> dict[str, Any]:
    modules, services = current_registry()
    topology = load_topology()
    domains = topology.get("logicalDomains", [])
    candidates = topology.get("consolidationCandidates", [])
    return {
        "currentExecutableModules": len(modules),
        "currentDefaultProcesses": len(services),
        "fixedTargetProcessCount": topology.get("deploymentPolicy", {}).get("fixedTargetProcessCount"),
        "logicalDomainCount": len(domains) if isinstance(domains, list) else 0,
        "logicalDomains": {
            domain.get("name", "<unnamed>"): domain.get("members", [])
            for domain in domains if isinstance(domain, dict)
        },
        "consolidationCandidates": {
            entry.get("name", "<unnamed>"): {
                "members": entry.get("members", []),
                "status": entry.get("status"),
            }
            for entry in candidates if isinstance(entry, dict)
        },
        "compatibilityModules": topology.get("compatibilityModules", []),
        "completedConsolidations": topology.get("completedConsolidations", []),
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
            f"{report['currentDefaultProcesses']}, fixed target=none, logical domains="
            f"{report['logicalDomainCount']}, consolidation candidates="
            f"{len(report['consolidationCandidates'])}, compatibility launchers="
            f"{len(report['compatibilityModules'])}"
        )
    return 1 if report["errors"] else 0


if __name__ == "__main__":
    raise SystemExit(main())
