#!/usr/bin/env python3
"""Check every registered service's Spring Boot archive before release packaging.

This validates archive structure, not application startup or dependency health.
Run after Maven package: python build/verify-jars.py.
"""

from pathlib import Path
import sys
import xml.etree.ElementTree as ET
import zipfile

from runtime_topology import current_registry, validate_registry


ROOT = Path(__file__).resolve().parents[1]
NS = {"m": "http://maven.apache.org/POM/4.0.0"}


def expected_jar(module: str) -> Path:
    pom = ET.parse(ROOT / "services" / module / "pom.xml").getroot()
    artifact = pom.findtext("m:artifactId", namespaces=NS)
    version = (pom.findtext("m:version", namespaces=NS)
               or pom.findtext("m:parent/m:version", namespaces=NS))
    if not artifact or not version:
        raise ValueError(f"{module}: missing artifactId/version in Maven project")
    name = pom.findtext("m:build/m:finalName", namespaces=NS) or f"{artifact}-{version}"
    name = name.replace("${project.artifactId}", artifact).replace("${project.version}", version)
    if "${" in name or any(part in name for part in ("/", "\\")):
        raise ValueError(f"{module}: cannot resolve archive name {name!r}")
    return ROOT / "services" / module / "target" / f"{name}.jar"


def check_archive(path: Path) -> None:
    with zipfile.ZipFile(path) as archive:
        entries = set(archive.namelist())
        manifest = archive.read("META-INF/MANIFEST.MF").decode("utf-8")
        # Manifest continuation lines begin with a single space.
        manifest = manifest.replace("\r\n ", "").replace("\n ", "")
        attributes = {}
        for line in manifest.splitlines():
            if not line:  # Only the main section describes the launch classes.
                break
            key, separator, value = line.partition(": ")
            if separator:
                attributes[key.lower()] = value.strip()
        main = attributes.get("main-class", "")
        start = attributes.get("start-class", "")
        if not main or main.replace(".", "/") + ".class" not in entries:
            raise ValueError("missing Main-Class or launcher bytecode")
        if not start or "BOOT-INF/classes/" + start.replace(".", "/") + ".class" not in entries:
            raise ValueError("missing Start-Class or application bytecode")
        if not any(name.startswith("BOOT-INF/lib/") and name.endswith(".jar") for name in entries):
            raise ValueError("missing packaged dependencies under BOOT-INF/lib")


def main() -> int:
    try:
        modules, services = current_registry()
        errors = validate_registry(modules, services)
        if not modules:
            errors.append("executable module registry is empty")
    except (OSError, ValueError) as failure:
        print(f"[FAIL] cannot read executable module registry: {failure}", file=sys.stderr)
        return 1
    checked = 0
    for module in modules:
        try:
            path = expected_jar(module)
            check_archive(path)
            checked += 1
        except (OSError, ValueError, KeyError, ET.ParseError, zipfile.BadZipFile) as failure:
            errors.append(f"{module}: {failure}")
    if errors:
        for error in errors:
            print(f"[FAIL] {error}", file=sys.stderr)
        return 1
    print(f"Spring Boot archive structure passed: {checked} registered service jars")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
