import unittest
from pathlib import Path
from tempfile import TemporaryDirectory
from failed_device_tests import MAX_RETRIED, failed_tests, main


def report(directory, cases):
    body = "".join(
        f'<testcase name="{name}" classname="a.B">{"<failure>x</failure>" if failed else ""}</testcase>'
        for name, failed in cases
    )
    path = Path(directory) / "connected" / "TEST-device.xml"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(f'<testsuite tests="{len(cases)}">{body}</testsuite>', encoding="utf-8")


class FailedDeviceTestsTest(unittest.TestCase):
    def test_lists_only_failures_as_class_method(self):
        with TemporaryDirectory() as directory:
            report(directory, [("ok", False), ("broken", True), ("flaky", True)])
            self.assertEqual(["a.B#broken", "a.B#flaky"], failed_tests(Path(directory)))

    def test_no_retry_without_failures_or_for_broad_failures(self):
        with TemporaryDirectory() as directory:
            report(directory, [("ok", False)])
            self.assertEqual(1, main(["", directory]))
        with TemporaryDirectory() as directory:
            report(directory, [(f"t{i}", True) for i in range(MAX_RETRIED + 1)])
            self.assertEqual(1, main(["", directory]))


if __name__ == "__main__":
    unittest.main()
