#!/usr/bin/env python3
"""Keep locale selection and translated copy out of Vue feature components."""

from pathlib import Path
import re
import sys


ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "frontend" / "apps" / "workbench" / "src"
DIRECT_LOCALE_BRANCH = re.compile(
    r"\b(?:locale(?:\.value)?|isZh(?:\.value)?|isEn(?:\.value)?)\s*(?:===|\?)"
)


def inline_keys(content: str) -> set[str]:
    keys: set[str] = set()
    in_inline = False
    component = ""
    for line in content.splitlines():
        if line == "  inline: {":
            in_inline = True
            continue
        if not in_inline:
            continue
        if line == "  },":
            break
        component_match = re.match(r"^    (\w+): \{$", line)
        if component_match:
            component = component_match.group(1)
            continue
        key_match = re.match(r"^      (\w+):", line)
        if component and key_match:
            keys.add(f"inline.{component}.{key_match.group(1)}")
    return keys


def main() -> int:
    errors: list[str] = []
    checked = 0
    for path in sorted((*SRC.rglob("*.vue"), *SRC.rglob("*.ts"))):
        relative = path.relative_to(ROOT).as_posix()
        if "/locales/" in f"/{relative}" or relative.endswith("/composables/useI18n.ts"):
            continue
        content = path.read_text(encoding="utf-8")
        checked += 1
        for match in DIRECT_LOCALE_BRANCH.finditer(content):
            line = content.count("\n", 0, match.start()) + 1
            errors.append(
                f"{relative}:{line}: resolve display copy through t(...) and locale files"
            )
        if re.search(r"from\s+['\"][^'\"]*/locales/", content):
            errors.append(f"{relative}: feature code must use useI18n(), not import locale maps")

    zh_keys = inline_keys((SRC / "locales" / "zh-CN.ts").read_text(encoding="utf-8"))
    en_keys = inline_keys((SRC / "locales" / "en-US.ts").read_text(encoding="utf-8"))
    if zh_keys != en_keys:
        errors.append(
            "inline locale key mismatch: zh-only=" + ",".join(sorted(zh_keys - en_keys))
            + " en-only=" + ",".join(sorted(en_keys - zh_keys))
        )
    references: set[str] = set()
    for path in (*SRC.rglob("*.vue"), *SRC.rglob("*.ts")):
        references.update(re.findall(
            r"\bt\(\s*['\"](inline\.[A-Za-z0-9_.]+)['\"]",
            path.read_text(encoding="utf-8"),
        ))
    missing = references - zh_keys
    if missing:
        errors.append("missing inline locale keys: " + ", ".join(sorted(missing)))

    if errors:
        print("Frontend i18n gate failed:", file=sys.stderr)
        for error in errors:
            print(f"  - {error}", file=sys.stderr)
        return 1
    print(f"Frontend i18n gate passed: {checked} source files; no component-local locale branches")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
