#!/usr/bin/env python3
"""Print failed instrumented tests as a runner `class` argument, for one CI retry.

Emulator device tests can fail on timing alone. run-device-tests.sh reruns only the failures once,
and only when there are a few. An incomplete instrumentation run must never be replaced
by a partial retry. --verify-retry checks that every requested test actually ran and passed.
"""
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

MAX_RETRIED = 5


def current_cases(results: Path) -> list[ET.Element]:
    cases = []
    for report in sorted(results.rglob("*.xml")):
        if "first-attempt" in report.relative_to(results).parts:
            continue
        root = ET.parse(report).getroot()
        if any(int(suite.get("errors", "0")) for suite in root.iter("testsuite")):
            raise ValueError("Instrumentation report contains suite errors")
        for case in root.iter("testcase"):
            if (not case.get("classname") or not case.get("name") or
                    case.get("classname") == "android-test" or case.get("name") == "testRunFailed"):
                raise ValueError("Instrumentation run did not complete")
            cases.append(case)
    return cases


def failed_tests(results: Path) -> list[str]:
    failed = []
    for case in current_cases(results):
        if case.find("failure") is not None or case.find("error") is not None:
            name = f"{case.get('classname')}#{case.get('name')}"
            if name not in failed:
                failed.append(name)
    return failed


def verify_retry(results: Path, requested: str) -> bool:
    expected = set(requested.split(","))
    if not requested or len(expected) > MAX_RETRIED:
        return False
    passed = set()
    for case in current_cases(results):
        if case.find("failure") is not None or case.find("error") is not None:
            return False
        if case.find("skipped") is None:
            passed.add(f"{case.get('classname')}#{case.get('name')}")
    return expected <= passed


def main(argv: list[str]) -> int:
    try:
        if len(argv) == 4 and argv[1] == "--verify-retry":
            return 0 if verify_retry(Path(argv[2]), argv[3]) else 1
        if len(argv) != 2:
            return 1
        failed = failed_tests(Path(argv[1]))
    except (OSError, ValueError, ET.ParseError):
        return 1
    if not failed or len(failed) > MAX_RETRIED:
        return 1
    print(",".join(failed))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
