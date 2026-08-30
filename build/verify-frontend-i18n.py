#!/usr/bin/env python3
"""Validate the workbench's Vue I18n message packs and usage boundaries."""

from pathlib import Path
import re
import sys


ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "frontend" / "apps" / "workbench" / "src"
LOCALE_DIR = SRC / "locales"
DIRECT_LOCALE_BRANCH = re.compile(
    r"\b(?:locale(?:\.value)?|isZh(?:\.value)?|isEn(?:\.value)?)\s*(?:===|\?(?!:))"
)
LITERAL_TRANSLATION = re.compile(r"\bt\(\s*['\"]([A-Za-z0-9_-]+(?:\.[A-Za-z0-9_-]+)*)['\"]")
KNOWN_MOJIBAKE = (
    "\ufffd",
    "\u9427",
    "\u7039",
    "\u93c3",
    "\u6fde",
    "\u93af",
    "\u7eeb\u8bf2",
    "\u9225",
    "\u934a",
)
CJK = re.compile(r"[\u3400-\u9fff]")
COMMENT_PREFIXES = ("//", "/*", "*", "*/", "<!--", "-->")
LEGACY_DATA_ALIASES = {
    "frontend/apps/workbench/src/views/AssetsView.vue",
    "frontend/apps/workbench/src/views/ThreatIntelView.vue",
}


def locale_keys(content: str) -> set[str]:
    """Extract scalar dotted keys from the plain object message pack."""
    keys: set[str] = set()
    stack: list[tuple[int, str]] = []
    for line in content.splitlines():
        match = re.match(r"^(\s+)([A-Za-z0-9_-]+):\s*(.*)$", line)
        if not match:
            continue
        indent = len(match.group(1).replace("\t", "    "))
        name, value = match.group(2), match.group(3)
        while stack and stack[-1][0] >= indent:
            stack.pop()
        path = ".".join([part for _, part in stack] + [name])
        if value.startswith("{"):
            stack.append((indent, name))
        else:
            keys.add(path)
    return keys


def main() -> int:
    errors: list[str] = []
    checked = 0
    zh_path = LOCALE_DIR / "zh-CN.ts"
    en_path = LOCALE_DIR / "en-US.ts"
    zh_content = zh_path.read_text(encoding="utf-8")
    en_content = en_path.read_text(encoding="utf-8")
    zh_keys = locale_keys(zh_content)
    en_keys = locale_keys(en_content)

    if zh_keys != en_keys:
        errors.append(
            "locale key mismatch: zh-only=" + ",".join(sorted(zh_keys - en_keys))
            + " en-only=" + ",".join(sorted(en_keys - zh_keys))
        )

    for path in sorted((*SRC.rglob("*.vue"), *SRC.rglob("*.ts"))):
        relative = path.relative_to(ROOT).as_posix()
        if "/locales/" in f"/{relative}" or "/i18n/" in f"/{relative}" or relative.endswith("/composables/useI18n.ts"):
            continue
        content = path.read_text(encoding="utf-8")
        checked += 1
        for match in DIRECT_LOCALE_BRANCH.finditer(content):
            line = content.count("\n", 0, match.start()) + 1
            errors.append(f"{relative}:{line}: resolve display copy through t(...) and locale files")
        if re.search(r"from\s+['\"][^'\"]*/locales/", content) and "/i18n/" not in f"/{relative}":
            errors.append(f"{relative}: feature code must use useI18n(), not import locale maps")
        if "inline." in content:
            errors.append(f"{relative}: legacy inline.* translation keys are not allowed")
        for marker in KNOWN_MOJIBAKE:
            if marker in content:
                errors.append(f"{relative}: contains mojibake marker {marker!r}")
                break
        for line_number, line in enumerate(content.splitlines(), 1):
            stripped = line.strip()
            code = re.sub(r"//.*$|/\*.*?\*/", "", line)
            if not CJK.search(code) or stripped.startswith(COMMENT_PREFIXES):
                continue
            # Importers intentionally accept common Chinese column aliases so
            # existing CSV/JSON exports remain compatible; these are not UI copy.
            if relative in LEGACY_DATA_ALIASES and "rowValue(" in line:
                continue
            errors.append(f"{relative}:{line_number}: user-visible CJK must live in locale files")

    references: set[str] = set()
    for path in (*SRC.rglob("*.vue"), *SRC.rglob("*.ts")):
        if "/locales/" in f"/{path.relative_to(ROOT).as_posix()}":
            continue
        references.update(LITERAL_TRANSLATION.findall(path.read_text(encoding="utf-8")))
    missing = references - zh_keys
    if missing:
        errors.append("missing locale keys: " + ", ".join(sorted(missing)))

    if errors:
        print("Frontend i18n gate failed:", file=sys.stderr)
        for error in errors:
            print(f"  - {error}", file=sys.stderr)
        return 1
    print(f"Frontend i18n gate passed: {checked} source files; zh/en keys={len(zh_keys)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
