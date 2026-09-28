#!/usr/bin/env python3
"""Summarize the actual Gradle test XML and Android lint outputs for Actions."""

import os
from pathlib import Path
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "build" / "verification" / "test-summary.md"
TEST_MODULES = (
    "app", "engine",
    "core/model", "core/diagnostics", "core/operation", "core/artifact",
    "usb/api", "usb/android", "protocol/adb", "protocol/fastboot",
    "vendor/xiaomi",
)


def report_module(module: str) -> tuple[str, bool]:
    # JVM modules write build/test-results/test, Android modules testDebugUnitTest.
    reports = sorted(
        path
        for task in ("test", "testDebugUnitTest")
        for path in (ROOT / module / "build/test-results" / task).glob("TEST-*.xml")
    )
    if not reports:
        return f"| `{module}` | **MISSING** | — | — | — | — |", False

    totals = dict.fromkeys(("tests", "failures", "errors", "skipped"), 0)
    try:
        for report in reports:
            root = ET.parse(report).getroot()
            suites = [root] if root.tag == "testsuite" else root.findall("testsuite")
            if not suites:
                raise ValueError(f"No testsuite in {report}")
            for suite in suites:
                for key in totals:
                    totals[key] += int(suite.get(key, "0"))
    except (ET.ParseError, ValueError) as error:
        return f"| `{module}` | **INVALID XML** | — | — | — | — |\n\n{error}", False

    passed = totals["tests"] > 0 and totals["failures"] == totals["errors"] == 0
    return (
        f"| `{module}` | {'PASS' if passed else 'FAIL'} | {totals['tests']} | "
        f"{totals['failures']} | {totals['errors']} | {totals['skipped']} |",
        passed,
    )


def main() -> int:
    rows = [report_module(module) for module in TEST_MODULES]
    lint = ROOT / "app/build/reports/lint-results-debug.html"
    apk = ROOT / "app/build/outputs/apk/debug/app-debug.apk"
    lines = [
        "# NekoFlash Pro — CI verification",
        "",
        "These counts come from Gradle JUnit XML files, not from a manual checklist.",
        "",
        "| Module | Result | Tests | Failures | Errors | Skipped |",
        "| --- | --- | ---: | ---: | ---: | ---: |",
        *(row for row, _ in rows),
        "",
        f"Android lint report: {'present' if lint.is_file() else '**MISSING**'} "
        "(`app/build/reports/lint-results-debug.html`).",
        f"Debug APK: {'present' if apk.is_file() else '**MISSING**'} "
        "(`app/build/outputs/apk/debug/app-debug.apk`).",
        "",
        "See `docs/07_BUILD_AND_TESTING.md` for USB checks on a real phone. "
        "CI cannot perform those hardware checks.",
        "",
    ]
    text = "\n".join(lines)
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_text(text, encoding="utf-8")
    print(text)
    if summary := os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(summary, "a", encoding="utf-8") as handle:
            handle.write(text)
    return 0 if all(ok for _, ok in rows) and lint.is_file() and apk.is_file() else 1


if __name__ == "__main__":
    raise SystemExit(main())
