#!/usr/bin/env python3
"""Enforce service boundaries and authorization invariants without loading Spring."""

from __future__ import annotations

from pathlib import Path
import re
import sys


ROOT = Path(__file__).resolve().parents[1]
BASELINE = ROOT / "build" / "architecture-controller-persistence-baseline.txt"
WRITE_MAPPING = re.compile(r"@(Post|Put|Patch|Delete)Mapping\b")
AUTH_BOUNDARY = re.compile(r"@(?:[\w.]+\.)?(RequireRole|RequireService|RequireIngestIdentity)\b")
PERSISTENCE_IMPORT = re.compile(r"^import\s+com\.socp\..*\.persistence\.(repository|entity)\.", re.MULTILINE)
SERVICE_DEPENDENCY = re.compile(r"<artifactId>([^<]+)</artifactId>")
METHOD_END = re.compile(r"^ {4}}\s*$", re.MULTILINE)


def relative(path: Path) -> str:
    return path.relative_to(ROOT).as_posix()


def controller_checks(errors: list[str]) -> tuple[int, set[str]]:
    controllers = sorted(ROOT.glob("services/*/src/main/java/**/*Controller.java"))
    direct_persistence: set[str] = set()
    public_auth_controllers = {
        "services/api-gateway/src/main/java/com/socp/gateway/api/controller/AuthController.java",
        "services/api-gateway/src/main/java/com/socp/gateway/api/controller/OidcAuthController.java",
    }
    for path in controllers:
        content = path.read_text(encoding="utf-8")
        name = relative(path)
        if PERSISTENCE_IMPORT.search(content):
            direct_persistence.add(name)
        if name in public_auth_controllers:
            continue
        class_match = re.search(r"\b(?:public\s+)?class\s+\w+", content)
        class_authorized = bool(class_match and AUTH_BOUNDARY.search(content[:class_match.start()]))
        for mapping in WRITE_MAPPING.finditer(content):
            if class_authorized:
                continue
            previous_methods = list(METHOD_END.finditer(content, 0, mapping.start()))
            previous_body = previous_methods[-1].end() if previous_methods else 0
            method_match = re.search(r"\bpublic\s+[\w<>,.?\[\]\s]+\s+\w+\s*\(", content[mapping.start():])
            if method_match is None:
                errors.append(f"{name}:{content.count(chr(10), 0, mapping.start()) + 1}: "
                              "cannot resolve mutating controller method")
                continue
            signature_start = mapping.start() + method_match.start()
            annotation_block = content[previous_body:signature_start]
            if not AUTH_BOUNDARY.search(annotation_block):
                line = content.count("\n", 0, mapping.start()) + 1
                errors.append(f"{name}:{line}: mutating method has no explicit authorization boundary")
    return len(controllers), direct_persistence


def dependency_checks(errors: list[str]) -> int:
    poms = sorted(ROOT.glob("services/*/pom.xml"))
    service_artifacts = {pom.parent.name for pom in poms}

    checked = 0
    for pom in poms:
        content = pom.read_text(encoding="utf-8")
        own = pom.parent.name
        for dependency in re.findall(
                r"<dependency>.*?<artifactId>([^<]+)</artifactId>.*?</dependency>",
                content, re.DOTALL):
            if dependency in service_artifacts and dependency != own:
                errors.append(
                    f"{relative(pom)}: service module depends directly on service '{dependency}'; "
                    "use a platform contract/client instead"
                )
        checked += 1
    return checked


def main() -> int:
    errors: list[str] = []
    controller_count, current_debt = controller_checks(errors)
    pom_count = dependency_checks(errors)
    expected_debt = {
        line.strip() for line in BASELINE.read_text(encoding="utf-8").splitlines()
        if line.strip() and not line.startswith("#")
    }
    introduced = current_debt - expected_debt
    retired = expected_debt - current_debt
    for item in sorted(introduced):
        errors.append(f"{item}: controller introduced a direct repository/entity dependency")
    if retired:
        errors.append(
            "architecture baseline contains retired debt; remove these entries: "
            + ", ".join(sorted(retired))
        )
    if errors:
        print("Architecture gate failed:", file=sys.stderr)
        for error in errors:
            print(f"  - {error}", file=sys.stderr)
        return 1
    print(
        f"Architecture gate passed: {controller_count} controllers, {pom_count} service modules; "
        f"direct-persistence baseline={len(current_debt)}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
