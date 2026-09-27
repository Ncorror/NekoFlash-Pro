#!/usr/bin/env python3
"""Проверки Compose-кода, которые не требуют Android-компилятора.

Зачем это существует. Google Maven доступен только в CI, поэтому `:app` и
`NekoFlashApp.kt` локально не собираются вовсе (`docs/16` §5.1): detekt видит
синтаксис и стиль, но не типы и не Compose. Из-за этого сборка падала дважды,
и оба раза — на механической ошибке, которую видно разбором текста.

2026-09-07: групповая замена в четырёх сигнатурах подряд добавила один и тот же
параметр четыре раза, потому что образец замены содержался в её результате.
2026-09-11: вставка функции между KDoc и объявлением унесла чужую `@Composable`
себе, оставив соседа без неё.

Гейт закрывает ровно эти три случая. Он намеренно не пытается быть
компилятором: его работа — стоить миллисекунды и ловить механику, а не
семантику.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

SOURCES = ("app/src/main/kotlin",)

# Вызовы, существующие только внутри @Composable.
COMPOSE_ONLY = ("stringResource(", "pluralStringResource(", "remember {", "remember(")

DECLARATION = re.compile(r"^\s*(?:private |internal |public )?fun\s+(\w+)")
NEXT_TOP_LEVEL = re.compile(
    r"^\s*(?:@|(?:private |internal |public )?fun\s"
    r"|(?:private |internal |public )?(?:data )?class\s)"
)
PARAMETER = re.compile(r"(?:^|,)\s*(\w+)\s*:")


def annotations_above(lines: list[str], index: int) -> list[str]:
    """Аннотации, относящиеся к объявлению на [index], сквозь KDoc и пустые строки."""
    found: list[str] = []
    cursor = index - 1
    while cursor >= 0:
        line = lines[cursor].strip()
        if line.startswith("@"):
            found.append(line.split("(")[0])
        elif line.startswith(("*", "/**")) or line.endswith("*/") or not line:
            pass
        else:
            break
        cursor -= 1
    return found


def parameters_of(text: str, opening: int) -> list[str]:
    """Имена параметров объявления, скобка которого открыта на [opening]."""
    depth, cursor = 1, opening + 1
    while depth and cursor < len(text):
        if text[cursor] == "(":
            depth += 1
        elif text[cursor] == ")":
            depth -= 1
        cursor += 1
    return PARAMETER.findall(text[opening + 1 : cursor - 1])


def body_of(lines: list[str], index: int) -> str:
    """Тело объявления: до следующего объявления или аннотации верхнего уровня."""
    cursor = index + 1
    while cursor < len(lines) and not NEXT_TOP_LEVEL.match(lines[cursor]):
        cursor += 1
    return "\n".join(lines[index:cursor])


def inspect(path: Path) -> list[str]:
    text = path.read_text(encoding="utf-8")
    lines = text.split("\n")
    problems: list[str] = []
    offset = 0

    for index, line in enumerate(lines):
        declaration = DECLARATION.match(line)
        if declaration is None:
            offset += len(line) + 1
            continue
        name = declaration.group(1)
        relative = path.relative_to(ROOT)

        annotations = annotations_above(lines, index)
        repeated = sorted({a for a in annotations if annotations.count(a) > 1})
        if repeated:
            problems.append(f"{relative}:{index + 1} {name}: аннотация повторяется {repeated}")

        opening = text.find("(", offset)
        if opening != -1:
            names = parameters_of(text, opening)
            duplicated = sorted({n for n in names if names.count(n) > 1})
            if duplicated:
                problems.append(f"{relative}:{index + 1} {name}: параметр объявлен дважды {duplicated}")

        body = body_of(lines, index)
        if any(call in body for call in COMPOSE_ONLY) and "@Composable" not in annotations:
            problems.append(f"{relative}:{index + 1} {name}: зовёт Compose-API без @Composable")

        offset += len(line) + 1

    return problems


def main() -> int:
    files = [path for source in SOURCES for path in sorted((ROOT / source).rglob("*.kt"))]
    problems = [problem for path in files for problem in inspect(path)]
    if problems:
        print("compose wiring: FAIL", file=sys.stderr)
        for problem in problems:
            print(f"  {problem}", file=sys.stderr)
        return 1
    print(f"compose wiring: PASS ({len(files)} files)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
