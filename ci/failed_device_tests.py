#!/usr/bin/env python3
"""Print failed instrumented tests as a runner `class` argument, for one CI retry.

Emulator device tests can fail on timing alone. run-device-tests.sh reruns only the failures once,
and only when there are a few: a broad failure is a real regression and must not be retried.
"""
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

MAX_RETRIED = 5


def failed_tests(results: Path) -> list[str]:
    failed = []
    for report in sorted(results.rglob("*.xml")):
        for case in ET.parse(report).getroot().iter("testcase"):
            if case.find("failure") is not None or case.find("error") is not None:
                name = f"{case.get('classname')}#{case.get('name')}"
                if name not in failed:
                    failed.append(name)
    return failed


def main(argv: list[str]) -> int:
    failed = failed_tests(Path(argv[1]))
    if not failed or len(failed) > MAX_RETRIED:
        return 1
    print(",".join(failed))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
