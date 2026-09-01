#!/usr/bin/env python3
"""Guard the repository's Java and frontend package/layout conventions."""

from __future__ import annotations

from pathlib import Path
import re
import sys


ROOT = Path(__file__).resolve().parents[1]


def java_files(source_root: Path) -> list[Path]:
    return sorted(source_root.glob("**/*.java"))


def check_java(errors: list[str]) -> tuple[int, int]:
    checked = 0
    for source_root in [
        *ROOT.glob("services/*/src/main/java"),
        *ROOT.glob("services/*/src/test/java"),
        *ROOT.glob("platform/*/src/main/java"),
        *ROOT.glob("platform/*/src/test/java"),
    ]:
        for path in java_files(source_root):
            checked += 1
            relative = path.relative_to(source_root)
            expected_package = ".".join(relative.with_suffix("").parts[:-1])
            content = path.read_text(encoding="utf-8")
            match = re.search(r"^\s*package\s+([\w.]+);", content, re.MULTILINE)
            if not match:
                errors.append(f"{path.relative_to(ROOT)}: missing package declaration")
                continue
            package = match.group(1)
            if package != expected_package:
                errors.append(
                    f"{path.relative_to(ROOT)}: package {package} does not match {expected_package}"
                )

            parts = relative.parts[:-1]
            filename = path.name
            main_source = "src" in path.parts and "main" in path.parts
            service_source = "services" in path.parts
            if main_source and len(parts) <= 3 and not filename.endswith("Application.java"):
                errors.append(f"{path.relative_to(ROOT)}: production class is in a module root package")
            normalized = "/".join(parts)
            if service_source and filename.endswith("Controller.java") and "/api/controller" not in "/" + normalized:
                errors.append(f"{path.relative_to(ROOT)}: Controller must be under api/controller")
            if service_source and filename.endswith("Request.java") and not (
                "/api/request" in "/" + normalized or "/temporal/request" in "/" + normalized
            ):
                errors.append(f"{path.relative_to(ROOT)}: Request must be under api/request or temporal/request")
            if service_source and filename.endswith("Response.java") and "/api/response" not in "/" + normalized:
                errors.append(f"{path.relative_to(ROOT)}: Response must be under api/response")
            if service_source and filename.endswith("Entity.java") and "/persistence/entity" not in "/" + normalized:
                errors.append(f"{path.relative_to(ROOT)}: Entity must be under persistence/entity")
            if service_source and filename.endswith("Repository.java") and "/persistence/repository" not in "/" + normalized:
                errors.append(f"{path.relative_to(ROOT)}: Repository must be under persistence/repository")
            if service_source and filename.endswith("Properties.java") and "/config" not in "/" + normalized:
                errors.append(f"{path.relative_to(ROOT)}: Properties must be under config")
            flat_api = service_source and package.endswith(".api")
            flat_store = service_source and package.endswith(".store") and not package.endswith(".persistence.store")
            if flat_api or flat_store:
                errors.append(f"{path.relative_to(ROOT)}: flat api/store package is not allowed")
            if service_source and main_source and re.search(r"\b(record|class|interface)\s+\w+Request\b", content):
                if "api/request" not in normalized and "temporal/request" not in normalized:
                    errors.append(f"{path.relative_to(ROOT)}: request model is declared outside a request package")
    return checked, len(errors)


def check_frontend(errors: list[str]) -> int:
    source = ROOT / "frontend/apps/workbench/src"
    api = source / "api"
    views = source / "views"
    components = source / "components"
    composables = source / "composables"
    for directory in (api, views, components, composables):
        if not directory.is_dir():
            errors.append(f"frontend: missing {directory.relative_to(ROOT)}")
    api_modules = list(api.glob("*.ts"))
    if len(api_modules) < 10:
        errors.append("frontend: domain API modules were collapsed into too few files")
    if list(api.rglob("*.vue")):
        errors.append("frontend: Vue components must not live under src/api")
    for view in views.glob("*.vue"):
        if not view.name.endswith("View.vue"):
            errors.append(f"frontend: view should be named *View.vue: {view.relative_to(ROOT)}")
    facade = source / "api.ts"
    if facade.exists() and len(facade.read_text(encoding="utf-8").splitlines()) > 80:
        errors.append("frontend: src/api.ts is a facade and should not contain domain implementations")
    return len(api_modules)


def main() -> int:
    errors: list[str] = []
    java_count, _ = check_java(errors)
    api_count = check_frontend(errors)
    if errors:
        print("Package layout gate failed:", file=sys.stderr)
        for error in errors:
            print(f"  - {error}", file=sys.stderr)
        return 1
    print(f"Package layout gate passed: {java_count} Java files, {api_count} frontend API modules")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
