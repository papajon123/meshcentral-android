import unittest
from contextlib import redirect_stdout
from io import StringIO
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

    def test_instrumentation_crash_refuses_partial_retry(self):
        with TemporaryDirectory() as directory:
            report(directory, [("broken", True)])
            path = Path(directory) / "connected" / "TEST-device.xml"
            path.write_text(
                '<testsuite tests="2" failures="2">'
                '<testcase classname="a.B" name="broken"><failure>assertion</failure></testcase>'
                '<testcase classname="android-test" name="testRunFailed">'
                '<failure>Instrumentation run failed due to Process crashed.</failure>'
                '</testcase></testsuite>', encoding="utf-8",
            )
            output = StringIO()
            with redirect_stdout(output):
                self.assertEqual(1, main(["", directory]))
            self.assertEqual("", output.getvalue())

    def test_retry_requires_every_requested_test_to_pass(self):
        with TemporaryDirectory() as directory:
            report(directory, [("first", False)])
            self.assertEqual(1, main(["", "--verify-retry", directory, "a.B#first,a.B#second"]))
            report(directory, [("first", False), ("second", False)])
            self.assertEqual(0, main(["", "--verify-retry", directory, "a.B#first,a.B#second"]))
            report(directory, [("first", False), ("second", True)])
            self.assertEqual(1, main(["", "--verify-retry", directory, "a.B#first,a.B#second"]))

    def test_retry_does_not_accept_skipped_or_empty_results(self):
        with TemporaryDirectory() as directory:
            self.assertEqual(1, main(["", "--verify-retry", directory, "a.B#broken"]))
            report(directory, [("broken", False)])
            path = Path(directory) / "connected" / "TEST-device.xml"
            path.write_text(
                '<testsuite tests="1" skipped="1">'
                '<testcase classname="a.B" name="broken"><skipped/></testcase></testsuite>',
                encoding="utf-8",
            )
            self.assertEqual(1, main(["", "--verify-retry", directory, "a.B#broken"]))

    def test_retry_ignores_archived_first_attempt_but_not_current_failure(self):
        with TemporaryDirectory() as directory:
            report(Path(directory) / "first-attempt", [("broken", True)])
            report(directory, [("broken", False)])
            self.assertEqual(0, main(["", "--verify-retry", directory, "a.B#broken"]))
            report(directory, [("broken", False), ("another", True)])
            self.assertEqual(1, main(["", "--verify-retry", directory, "a.B#broken"]))


if __name__ == "__main__":
    unittest.main()
