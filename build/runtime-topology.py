#!/usr/bin/env python3
"""Report the supported runtime grouping without changing deployment state."""

from __future__ import annotations

import argparse
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
GROUPS = {
    "gateway-ui": ["api-gateway", "frontend/apps/workbench"],
    "ingest-search": ["search-config", "asset-collect", "hips-collect"],
    "detection": ["detect-web", "detect-model"],
    "alert-incident": ["alert-web", "incident-web"],
    "response-integration": ["soar-web", "notify-web", "asset-web", "hips-web", "threat-web", "attack-web"],
    "report-ai": ["report-web", "ai-assistant", "soc-base"],
}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()
    modules = sorted(path.parent.name for path in (ROOT / "services").glob("*/pom.xml"))
    grouped = {name: [item for item in entries if item in modules or item.startswith("frontend/")]
               for name, entries in GROUPS.items()}
    assigned = {item for entries in grouped.values() for item in entries if not item.startswith("frontend/")}
    report = {
        "currentServiceModules": len(modules),
        "targetDeploymentUnits": len(GROUPS),
        "groups": grouped,
        "unassignedModules": sorted(set(modules) - assigned),
        "status": "contract-only-until-aggregate-apps-pass-integration-tests",
    }
    print(json.dumps(report, ensure_ascii=False, indent=2) if args.json else
          f"service modules={len(modules)} target deployment units={len(GROUPS)} "
          f"unassigned={len(report['unassignedModules'])}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
