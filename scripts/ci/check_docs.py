#!/usr/bin/env python3
"""Keep the documentation consistent with the repository and with itself.

Every rule here backs a sentence in docs/README.md or docs/07_BUILD_AND_TESTING.md
("Проверка документации"). The script reports every problem it finds, not just
the first one, and exits with 1 if there is any.
"""

from __future__ import annotations

import hashlib
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DOCS = ROOT / "docs"
ROADMAP = DOCS / "05_ROADMAP.md"
ARCHITECTURE = DOCS / "03_ARCHITECTURE.md"
BUILD_DOC = DOCS / "07_BUILD_AND_TESTING.md"
DECISIONS = DOCS / "08_DECISIONS.md"
WORKFLOW = ROOT / ".github/workflows/build.yml"
TEST_SUMMARY = ROOT / "scripts/ci-test-summary.py"
SETTINGS = ROOT / "settings.gradle.kts"
APP_BUILD = ROOT / "app/build.gradle.kts"
ARCHIVES = ROOT / "reference/archives"
CHECKSUMS = ROOT / "reference/SHA256SUMS"

STATUSES = {"нет", "в архиве", "в репо", "проверено"}
CURRENT_MARKER = "Текущий этап:"

problems: list[str] = []


def fail(message: str) -> None:
    problems.append(message)


def rel(path: Path) -> str:
    return path.relative_to(ROOT).as_posix()


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def markdown_files() -> list[Path]:
    files = [ROOT / "README.md", ROOT / "CLAUDE.md", ROOT / "reference/README.md"]
    files += sorted(DOCS.rglob("*.md"))
    return [path for path in files if path.is_file()]


def without_code(text: str) -> str:
    """Drop fenced blocks and inline code so examples are not read as links or headings."""
    text = re.sub(r"^```.*?^```", "", text, flags=re.MULTILINE | re.DOTALL)
    return re.sub(r"`[^`\n]*`", "", text)


def slug(heading: str) -> str:
    """GitHub heading anchor: lower case, punctuation removed, spaces to hyphens."""
    heading = re.sub(r"\[([^\]]*)\]\([^)]*\)", r"\1", heading)
    heading = re.sub(r"[^\w\- ]", "", heading.strip().lower())
    return heading.replace(" ", "-")


def anchors(path: Path) -> set[str]:
    text = re.sub(r"^```.*?^```", "", read(path), flags=re.MULTILINE | re.DOTALL)
    seen: dict[str, int] = {}
    result = set()
    for match in re.finditer(r"^#{1,6}\s+(.+?)\s*#*\s*$", text, flags=re.MULTILINE):
        base = slug(match.group(1))
        count = seen.get(base, 0)
        seen[base] = count + 1
        result.add(base if count == 0 else f"{base}-{count}")
    return result


def check_links() -> None:
    for source in markdown_files():
        for target in re.findall(r"\[[^\]]*\]\(([^)\s]+)\)", without_code(read(source))):
            if re.match(r"^[a-z]+:", target):
                continue
            path_part, _, anchor = target.partition("#")
            resolved = (source.parent / path_part).resolve() if path_part else source
            if not resolved.exists():
                fail(f"{rel(source)}: ссылка на несуществующий файл {target}")
                continue
            if anchor and resolved.suffix == ".md" and anchor not in anchors(resolved):
                fail(f"{rel(source)}: в {rel(resolved)} нет заголовка для якоря #{anchor}")


def check_map() -> None:
    index = DOCS / "README.md"
    linked = {
        (index.parent / target.partition("#")[0]).resolve()
        for target in re.findall(r"\[[^\]]*\]\(([^)\s#]+)(?:#[^)]*)?\)", without_code(read(index)))
    }
    for path in sorted(DOCS.rglob("*.md")):
        if path != index and path.resolve() not in linked:
            fail(f"{rel(path)}: документ не указан в карте docs/README.md")


def settings_modules() -> list[str]:
    modules: list[str] = []
    for call in re.findall(r"include\(([^)]*)\)", read(SETTINGS)):
        modules += re.findall(r'"(:[^"]+)"', call)
    return modules


def check_modules() -> None:
    text = read(ARCHITECTURE)
    block = re.search(r"<!-- modules:begin -->(.*?)<!-- modules:end -->", text, flags=re.DOTALL)
    if block is None:
        fail("docs/03_ARCHITECTURE.md: нет блока <!-- modules:begin/end -->")
        return
    documented = re.findall(r"^- `(:[^`]+)`", block.group(1), flags=re.MULTILINE)
    actual = settings_modules()
    if sorted(documented) != sorted(actual):
        fail(
            "docs/03_ARCHITECTURE.md: текущие модули не совпадают с settings.gradle.kts: "
            f"в документе {sorted(documented)}, в сборке {sorted(actual)}"
        )


def gradle_tokens(text: str, where: str) -> list[str]:
    match = re.search(r"bash \./gradlew ([^\n]+)", text)
    if match is None:
        fail(f"{where}: не найдена команда bash ./gradlew")
        return []
    return match.group(1).split()


def check_ci_command() -> None:
    block = re.search(r"<!-- ci-command:begin -->(.*?)<!-- ci-command:end -->", read(BUILD_DOC), flags=re.DOTALL)
    if block is None:
        fail("docs/07_BUILD_AND_TESTING.md: нет блока <!-- ci-command:begin/end -->")
        return
    documented = gradle_tokens(block.group(1), rel(BUILD_DOC))
    workflow = gradle_tokens(read(WORKFLOW), rel(WORKFLOW))
    if documented != workflow:
        fail(f"команда сборки в docs/07 и в {rel(WORKFLOW)} различается:\n  docs: {documented}\n  ci:   {workflow}")
    jvm_modules = [module for module in settings_modules() if module != ":app"]
    for module in jvm_modules:
        if f"{module}:test" not in workflow:
            fail(f"{rel(WORKFLOW)}: тесты модуля {module} не запускаются")
    summary = re.search(r"TEST_MODULES\s*=\s*\(([^)]*)\)", read(TEST_SUMMARY))
    listed = [":" + name.replace("/", ":") for name in re.findall(r'"([^"]+)"', summary.group(1))] if summary else []
    if sorted(listed) != sorted(jvm_modules):
        fail(f"{rel(TEST_SUMMARY)}: TEST_MODULES {sorted(listed)} не совпадает с модулями {sorted(jvm_modules)}")
    for source in (WORKFLOW, TEST_SUMMARY):
        for path in re.findall(r"docs/[\w./-]+\.md", read(source)):
            if not (ROOT / path).is_file():
                fail(f"{rel(source)}: ссылается на несуществующий {path}")


def check_version() -> None:
    build = re.search(r'versionName\s*=\s*"([^"]+)"', read(APP_BUILD))
    documented = re.search(r"^Версия APK: `([^`]+)`$", read(ROADMAP), flags=re.MULTILINE)
    if build is None or documented is None:
        fail("версия APK не найдена в app/build.gradle.kts или docs/05_ROADMAP.md")
    elif build.group(1) != documented.group(1):
        fail(f"docs/05_ROADMAP.md: версия {documented.group(1)}, а в app/build.gradle.kts {build.group(1)}")


def check_roadmap() -> None:
    text = read(ROADMAP)
    for path in markdown_files():
        if path != ROADMAP and CURRENT_MARKER in read(path):
            fail(f"{rel(path)}: строка «{CURRENT_MARKER}» живёт только в docs/05_ROADMAP.md")
    markers = re.findall(rf"^{CURRENT_MARKER} \*\*(.+?)\*\*$", text, flags=re.MULTILINE)
    if len(markers) != 1:
        fail(f"docs/05_ROADMAP.md: строка «{CURRENT_MARKER}» должна быть ровно одна, найдено {len(markers)}")

    stages = []
    for match in re.finditer(r"^## Этап (\d+) — (.+?)(?: \((завершён|текущий)\))?$", text, flags=re.MULTILINE):
        body_end = text.find("\n## ", match.end())
        body = text[match.end(): body_end if body_end != -1 else len(text)]
        stages.append((int(match.group(1)), match.group(2), match.group(3), body))
    if [number for number, *_ in stages] != list(range(len(stages))):
        fail("docs/05_ROADMAP.md: этапы должны идти подряд с нуля")
    current = [stage for stage in stages if stage[2] == "текущий"]
    if len(current) != 1:
        fail(f"docs/05_ROADMAP.md: текущий этап должен быть ровно один, найдено {len(current)}")
    else:
        number, title, _, _ = current[0]
        if markers and markers[0] != f"Этап {number} — {title}":
            fail(f"docs/05_ROADMAP.md: «{CURRENT_MARKER}» говорит «{markers[0]}», а текущий — «Этап {number} — {title}»")
        for other, other_title, state, _ in stages:
            if other < number and state != "завершён":
                fail(f"docs/05_ROADMAP.md: Этап {other} идёт до текущего, но не помечен «завершён»")
            if other > number and state is not None:
                fail(f"docs/05_ROADMAP.md: Этап {other} после текущего не может быть «{state}»")

    for number, _, state, body in stages:
        rows = [line for line in body.splitlines() if line.startswith("|")]
        if not rows or [cell.strip() for cell in rows[0].strip("|").split("|")] != ["Пункт", "Статус", "Где"]:
            fail(f"docs/05_ROADMAP.md: у Этапа {number} нет таблицы «Пункт | Статус | Где»")
            continue
        for row in rows[2:]:
            cells = [cell.strip() for cell in row.strip().strip("|").split("|")]
            status = cells[1].strip("`") if len(cells) > 1 else ""
            if status not in STATUSES:
                fail(f"docs/05_ROADMAP.md: Этап {number}, «{cells[0]}»: статус «{status}» не из словаря {sorted(STATUSES)}")
            elif state == "завершён" and status not in {"в репо", "проверено"}:
                fail(f"docs/05_ROADMAP.md: Этап {number} завершён, но «{cells[0]}» в статусе «{status}»")

    for path in sorted(DOCS.glob("*.md")):
        if path == ROADMAP:
            continue
        for line in without_code(read(path)).splitlines():
            if line.startswith("|") and "Статус" in [cell.strip() for cell in line.strip("|").split("|")]:
                fail(f"{rel(path)}: колонка «Статус» бывает только в docs/05_ROADMAP.md")


def check_numbering() -> None:
    adr = sorted(int(path.name[:4]) for path in (DOCS / "adr").glob("[0-9][0-9][0-9][0-9]_*.md"))
    if adr != list(range(1, len(adr) + 1)):
        fail(f"docs/adr: номера ADR должны идти подряд с 0001, найдено {adr}")
    decisions = [int(number) for number in re.findall(r"^- \*\*D(\d{3}) ", read(DECISIONS), flags=re.MULTILINE)]
    if decisions != list(range(1, len(decisions) + 1)):
        fail(f"docs/08_DECISIONS.md: номера решений должны идти подряд с D001, найдено {decisions}")


def check_archives() -> None:
    listed: dict[str, str] = {}
    for line in read(CHECKSUMS).splitlines():
        if line.strip():
            digest, name = line.split(maxsplit=1)
            listed[name.strip()] = digest
    present = {path.name for path in ARCHIVES.iterdir() if path.is_file()}
    for name in sorted(present - listed.keys()):
        fail(f"reference/archives/{name}: нет в reference/SHA256SUMS")
    for name, digest in sorted(listed.items()):
        path = ARCHIVES / name
        if not path.is_file():
            fail(f"reference/SHA256SUMS: {name} отсутствует в reference/archives")
            continue
        sha = hashlib.sha256()
        with path.open("rb") as handle:
            for chunk in iter(lambda: handle.read(1 << 20), b""):
                sha.update(chunk)
        if sha.hexdigest() != digest:
            fail(f"reference/archives/{name}: SHA-256 не совпадает с reference/SHA256SUMS — архив неизменяем")


def main() -> int:
    for check in (check_links, check_map, check_modules, check_ci_command, check_version,
                  check_roadmap, check_numbering, check_archives):
        check()
    if problems:
        print("docs: FAIL", file=sys.stderr)
        for problem in problems:
            print(f"  - {problem}", file=sys.stderr)
        return 1
    print(f"docs: PASS ({len(markdown_files())} документов, модули {len(settings_modules())}, архивы сверены)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
