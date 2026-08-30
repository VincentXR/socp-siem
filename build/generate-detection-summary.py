#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Generate the review-visible detection content summary from the manifest."""

import argparse
import hashlib
import json
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_MANIFEST = ROOT / "services/detect-web/src/main/resources/detection-content/manifest.json"
START = "<!-- detection-summary:start -->"
END = "<!-- detection-summary:end -->"


def load(path: Path):
    raw = path.read_bytes()
    return json.loads(raw.decode("utf-8")), hashlib.sha256(raw).hexdigest()


def summary(manifest, digest):
    rules = manifest.get("rules", [])
    types = Counter(str(rule.get("spec", {}).get("type", "unknown")) for rule in rules)
    statuses = Counter(str(rule.get("status", "unknown")) for rule in rules)
    sources = sorted({str(source) for rule in rules for source in rule.get("dataSources", [])})
    techniques = sorted({str(technique) for rule in rules for technique in rule.get("mitre", [])})
    return {
        "packId": manifest.get("packId"),
        "version": manifest.get("version"),
        "schemaVersion": manifest.get("schemaVersion"),
        "sha256": digest,
        "rules": len(rules),
        "activeRules": statuses.get("ACTIVE", 0),
        "types": dict(sorted(types.items())),
        "statuses": dict(sorted(statuses.items())),
        "dataSources": sources,
        "dataSourceCount": len(sources),
        "mitreTechniques": techniques,
        "mitreTechniqueCount": len(techniques),
    }


def markdown(data):
    type_text = ", ".join(f"{key}={value}" for key, value in data["types"].items())
    status_text = ", ".join(f"{key}={value}" for key, value in data["statuses"].items())
    sources = ", ".join(data["dataSources"])
    return "\n".join([
        START,
        f"**Detection content**: `{data['rules']}` rules (`{data['activeRules']}` ACTIVE), "
        f"pack `{data['packId']}` version `{data['version']}` (schema `{data['schemaVersion']}`).",
        f"Types: {type_text}. Statuses: {status_text}.",
        f"ATT&CK techniques: `{data['mitreTechniqueCount']}`; data sources: `{data['dataSourceCount']}` ({sources}).",
        f"Manifest SHA-256: `{data['sha256']}`.",
        END,
    ])


def update_readme(path: Path, block: str):
    text = path.read_text(encoding="utf-8")
    start = text.find(START)
    end = text.find(END)
    if start < 0 or end < start:
        raise SystemExit(f"{path} is missing {START}/{END} markers")
    end += len(END)
    path.write_text(text[:start] + block + text[end:], encoding="utf-8")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", type=Path, default=DEFAULT_MANIFEST)
    parser.add_argument("--readme", type=Path, default=ROOT / "README.md")
    parser.add_argument("--json", type=Path, help="write machine-readable summary JSON")
    parser.add_argument("--update-readme", action="store_true")
    parser.add_argument("--check-readme", action="store_true")
    args = parser.parse_args()
    manifest, digest = load(args.manifest)
    data = summary(manifest, digest)
    block = markdown(data)
    if args.json:
        args.json.parent.mkdir(parents=True, exist_ok=True)
        args.json.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    if args.update_readme:
        update_readme(args.readme, block)
    if args.check_readme:
        text = args.readme.read_text(encoding="utf-8")
        start, end = text.find(START), text.find(END)
        actual = text[start:end + len(END)] if start >= 0 and end >= start else ""
        if actual != block:
            print("README detection summary is stale")
            print(block)
            return 1
    print(json.dumps(data, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
