#!/usr/bin/env python3
from __future__ import annotations

import re
import sys
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_STRINGS = ROOT / "app/src/main/res/values/strings.xml"
RUSSIAN_STRINGS = ROOT / "app/src/main/res/values-ru/strings.xml"
RESOURCES_PROPERTIES = ROOT / "app/src/main/res/resources.properties"
KOTLIN_ROOT = ROOT / "app/src/main/kotlin"


def resource_keys(path: Path, *, include_non_translatable: bool) -> set[tuple[str, str]]:
    root = ET.parse(path).getroot()
    keys: set[tuple[str, str]] = set()
    for child in root:
        name = child.attrib.get("name")
        if not name:
            continue
        if not include_non_translatable and child.attrib.get("translatable") == "false":
            continue
        keys.add((child.tag, name))
    return keys


# Русский различает четыре формы. Объявить только "other" — значит выводить
# "1 байтов" и "2 байтов": ошибка, которую не видно, пока число не совпадёт.
RUSSIAN_QUANTITIES = {"one", "few", "many", "other"}


def incomplete_russian_plurals() -> dict[str, set[str]]:
    """Русские plurals, в которых объявлены не все формы."""
    root = ET.parse(RUSSIAN_STRINGS).getroot()
    incomplete: dict[str, set[str]] = {}
    for child in root:
        if child.tag != "plurals":
            continue
        name = child.attrib.get("name")
        if not name:
            continue
        declared = {item.attrib.get("quantity") for item in child}
        missing = RUSSIAN_QUANTITIES - declared
        if missing:
            incomplete[name] = missing
    return incomplete


def format_keys(keys: set[tuple[str, str]]) -> str:
    return "\n".join(f"  {kind}:{name}" for kind, name in sorted(keys))


def main() -> int:
    default_translatable = resource_keys(DEFAULT_STRINGS, include_non_translatable=False)
    default_all = resource_keys(DEFAULT_STRINGS, include_non_translatable=True)
    russian_all = resource_keys(RUSSIAN_STRINGS, include_non_translatable=True)

    missing = default_translatable - russian_all
    extra = russian_all - default_all

    if missing:
        print("localization: Russian resources are missing keys:", file=sys.stderr)
        print(format_keys(missing), file=sys.stderr)
        return 1

    if extra:
        print("localization: Russian resources contain unknown keys:", file=sys.stderr)
        print(format_keys(extra), file=sys.stderr)
        return 1

    missing_quantities = incomplete_russian_plurals()
    if missing_quantities:
        print("localization: Russian plurals are missing quantity forms:", file=sys.stderr)
        for name, quantities in sorted(missing_quantities.items()):
            print(f"  plurals:{name} needs {', '.join(sorted(quantities))}", file=sys.stderr)
        return 1

    properties = RESOURCES_PROPERTIES.read_text(encoding="utf-8")
    if "unqualifiedResLocale=en" not in properties.splitlines():
        print("localization: resources.properties must declare unqualifiedResLocale=en", file=sys.stderr)
        return 1

    hardcoded_patterns = (
        re.compile(r'\bText\s*\(\s*"'),
        re.compile(r'\btext\s*=\s*"'),
        re.compile(r'\bcontentDescription\s*=\s*"'),
    )
    violations: list[str] = []
    for path in sorted(KOTLIN_ROOT.rglob("*.kt")):
        for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
            if any(pattern.search(line) for pattern in hardcoded_patterns):
                violations.append(f"{path.relative_to(ROOT)}:{line_number}: {line.strip()}")

    if violations:
        print("localization: hardcoded user-facing Compose text found:", file=sys.stderr)
        print("\n".join(f"  {item}" for item in violations), file=sys.stderr)
        return 1

    print(
        "localization: PASS "
        f"({len(default_translatable)} translatable default resources, "
        f"{len(russian_all)} Russian resources)"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
