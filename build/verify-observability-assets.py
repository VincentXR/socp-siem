#!/usr/bin/env python3
"""Static gate for observability alert, runbook, and dashboard wiring.

The gate validates the following without depending on Helm:

* every alert in the chart ``PrometheusRule`` and ``soar-alerts.yml`` carries a
  ``runbook_url`` annotation;
* every ``runbook_url`` anchor into ``incident-response.md`` resolves to a real
  heading in that file;
* the referenced runbook / dashboard files exist;
* the event-path dashboard is valid JSON and uses the declared datasource uid.

Run it after editing rules or the runbook. It is intentionally pure-text so it
executes on any host (the sibling ``verify-helm.py`` needs Helm and is skipped on
machines without it).
"""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RULES = ROOT / "deploy/helm/socp-core/templates/prometheusrule.yaml"
SOAR_RULES = ROOT / "infra/observability/prometheus/soar-alerts.yml"
DASHBOARD = ROOT / "infra/observability/grafana/dashboards/socp-event-path.json"
DATASOURCE = ROOT / "infra/observability/grafana/provisioning/datasources/socp.yaml"
RUNBOOK = ROOT / "docs/operations/incident-response.md"


def github_anchor(heading: str) -> str:
    slug = heading.strip().lower()
    slug = re.sub(r"[^\w\s-]", "", slug)   # drop punctuation/backticks
    slug = re.sub(r"\s+", "-", slug)        # whitespace -> hyphen
    return slug


def runbook_anchors() -> set[str]:
    if not RUNBOOK.is_file():
        return set()
    anchors = set()
    for line in RUNBOOK.read_text(encoding="utf-8").splitlines():
        match = re.match(r"^#{2,6}\s+(.*)$", line)
        if match:
            anchors.add(github_anchor(match.group(1)))
    return anchors


def alerts_from_helm(text: str) -> list[dict]:
    """Parse `- alert: Name` blocks and the annotations that follow, until the next alert."""
    alerts: list[dict] = []
    current: dict | None = None
    for line in text.splitlines():
        name = re.match(r"^\s*-\s+alert:\s*(\S+)\s*$", line)
        if name:
            current = {"alert": name.group(1), "runbook_url": None}
            alerts.append(current)
            continue
        if current is not None:
            url = re.match(r"^\s*runbook_url:\s*(\S+)\s*$", line)
            if url:
                current["runbook_url"] = url.group(1)
    return alerts


def alerts_from_soar(text: str) -> list[dict]:
    alerts: list[dict] = []
    current: dict | None = None
    for line in text.splitlines():
        name = re.match(r"^\s*-\s+alert:\s*(\S+)\s*$", line)
        if name:
            current = {"alert": name.group(1), "runbook_url": None}
            alerts.append(current)
            continue
        if current is not None:
            url = re.match(r"^\s*runbook_url:\s*(\S+)\s*$", line)
            if url:
                current["runbook_url"] = url.group(1)
    return alerts


def check_runbook_reference(url: str) -> str | None:
    """Return an error string if a runbook_url does not resolve, else None."""
    path_part, _, anchor = url.partition("#")
    if not path_part.startswith("docs/"):
        return f"runbook_url is not a repo-relative docs path: {url}"
    target = ROOT / path_part
    if not target.is_file():
        return f"runbook_url points at a missing file: {path_part}"
    if anchor and target == RUNBOOK:
        if anchor not in runbook_anchors():
            return f"runbook_url anchor '#({anchor})' has no matching heading in incident-response.md"
    return None


def main() -> int:
    errors: list[str] = []
    if not RUNBOOK.is_file():
        errors.append("docs/operations/incident-response.md is missing")

    for path, parser, label in (
        (RULES, alerts_from_helm, "PrometheusRule"),
        (SOAR_RULES, alerts_from_soar, "soar-alerts.yml"),
    ):
        if not path.is_file():
            errors.append(f"missing rules file {path.relative_to(ROOT)}")
            continue
        alerts = parser(path.read_text(encoding="utf-8"))
        if not alerts:
            errors.append(f"{label}: no alerts parsed (regex drift?)")
        for alert in alerts:
            url = alert["runbook_url"]
            if not url:
                errors.append(f"{label}: alert {alert['alert']} has no runbook_url annotation")
                continue
            problem = check_runbook_reference(url)
            if problem:
                errors.append(f"{label}: alert {alert['alert']}: {problem}")

    # Event-path dashboard wiring.
    for path in (DASHBOARD, DATASOURCE):
        if not path.is_file():
            errors.append(f"missing {path.relative_to(ROOT)}")
    if DASHBOARD.is_file() and DATASOURCE.is_file():
        try:
            dashboard = json.loads(DASHBOARD.read_text(encoding="utf-8"))
        except json.JSONDecodeError as exc:
            errors.append(f"event-path dashboard is not valid JSON: {exc}")
            dashboard = None
        if dashboard is not None:
            ds_text = DATASOURCE.read_text(encoding="utf-8")
            uid_match = re.search(r"uid:\s*(\S+)", ds_text)
            uid = uid_match.group(1) if uid_match else "socp-prometheus"
            for panel in dashboard.get("panels", []):
                for target in panel.get("targets", []):
                    used = target.get("datasource", {}).get("uid")
                    if used != uid:
                        errors.append(
                            f"dashboard panel '{panel.get('title')}' uses datasource uid "
                            f"'{used}', not the provisioned '{uid}'")
                        break
            if not dashboard.get("panels"):
                errors.append("event-path dashboard has no panels")

    if errors:
        print("Observability alert/runbook wiring failed:", file=sys.stderr)
        for error in errors:
            print(f"  - {error}", file=sys.stderr)
        return 1
    print("Observability asset contract passed: every alert has a resolvable runbook_url; "
          "dashboard and datasource definitions agree")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
