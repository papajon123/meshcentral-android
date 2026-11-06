"""Shard and retry wiring of ci/run-device-tests.sh, exercised with stub gradlew/adb/python3."""
import os
import shutil
import subprocess
import tempfile
import textwrap
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parent / "run-device-tests.sh"
BASH = shutil.which("bash")
SHARD_FLAG = "-Pandroid.testInstrumentationRunnerArguments.numShards="
INDEX_FLAG = "-Pandroid.testInstrumentationRunnerArguments.shardIndex="


@unittest.skipUnless(BASH, "bash is not available")
class RunDeviceTestsTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp, True)
        (self.tmp / "ci").mkdir()
        shutil.copyfile(SCRIPT, self.tmp / "ci" / "run-device-tests.sh")
        self.bin = self.tmp / "bin"
        self.bin.mkdir()
        self.log = self.tmp / "calls.log"
        # gradlew: log arguments; fail the first direct run when FAIL_FIRST is set.
        self.stub("gradlew", """
            printf 'gradlew' >> "$CALL_LOG"; for a in "$@"; do printf ' %s' "$a" >> "$CALL_LOG"; done; echo >> "$CALL_LOG"
            if [ -n "${FAIL_FIRST:-}" ] && [ ! -e "$CALL_LOG.failed" ]; then : > "$CALL_LOG.failed"; exit 1; fi
            exit 0
        """, directory=self.tmp)
        self.stub("adb", 'exit 0')
        # python3 ci/failed_device_tests.py <results> -> one retryable test; --verify-retry -> success.
        self.stub("python3", """
            if [ "$2" = "--verify-retry" ]; then echo "verify $4" >> "$CALL_LOG"; exit 0; fi
            echo "a.B#c"
        """)

    def stub(self, name, body, directory=None):
        path = (directory or self.bin) / name
        path.write_text("#!/usr/bin/env bash\n" + textwrap.dedent(body).strip() + "\n", newline="\n")
        path.chmod(0o755)

    def run_script(self, **env):
        full_env = dict(os.environ, CALL_LOG=str(self.log), PATH=f"{self.bin}{os.pathsep}{os.environ['PATH']}")
        full_env.pop("NUM_SHARDS", None)
        full_env.pop("SHARD_INDEX", None)
        full_env.update(env)
        result = subprocess.run([BASH, "ci/run-device-tests.sh"], cwd=self.tmp, env=full_env,
                                capture_output=True, text=True, timeout=60)
        calls = self.log.read_text().splitlines() if self.log.exists() else []
        return result.returncode, [c for c in calls if c.startswith("gradlew")], calls

    def test_unsharded_runs_direct_suite_then_play_suite(self):
        status, gradle, _ = self.run_script()
        self.assertEqual(0, status)
        self.assertEqual(2, len(gradle))
        self.assertIn("connectedDirectDebugAndroidTest", gradle[0])
        self.assertNotIn(SHARD_FLAG, gradle[0])
        self.assertIn("connectedPlayDebugAndroidTest", gradle[1])

    def test_first_shard_runs_only_its_part_without_play_suite(self):
        status, gradle, _ = self.run_script(NUM_SHARDS="2", SHARD_INDEX="0")
        self.assertEqual(0, status)
        self.assertEqual(1, len(gradle))
        self.assertIn(SHARD_FLAG + "2", gradle[0])
        self.assertIn(INDEX_FLAG + "0", gradle[0])

    def test_last_shard_also_runs_play_suite_once(self):
        status, gradle, _ = self.run_script(NUM_SHARDS="2", SHARD_INDEX="1")
        self.assertEqual(0, status)
        self.assertEqual(2, len(gradle))
        self.assertIn(INDEX_FLAG + "1", gradle[0])
        self.assertIn("connectedPlayDebugAndroidTest", gradle[1])
        self.assertNotIn(SHARD_FLAG, gradle[1])

    def test_retry_names_failed_tests_without_shard_arguments(self):
        status, gradle, calls = self.run_script(NUM_SHARDS="2", SHARD_INDEX="0", FAIL_FIRST="1")
        self.assertEqual(0, status)
        self.assertEqual(2, len(gradle))
        self.assertIn(SHARD_FLAG + "2", gradle[0])
        self.assertIn("class=a.B#c", gradle[1])
        self.assertNotIn(SHARD_FLAG, gradle[1])
        self.assertNotIn(INDEX_FLAG, gradle[1])
        self.assertIn("verify a.B#c", calls)

    def play_failing_gradlew(self, failures):
        # Fails the Play suite `failures` times, everything else succeeds.
        self.stub("gradlew", f"""
            printf 'gradlew' >> "$CALL_LOG"; for a in "$@"; do printf ' %s' "$a" >> "$CALL_LOG"; done; echo >> "$CALL_LOG"
            case "$*" in *connectedPlayDebugAndroidTest*)
              count=$(cat "$CALL_LOG.play" 2>/dev/null || echo 0); echo $((count + 1)) > "$CALL_LOG.play"
              if [ "$count" -lt {failures} ]; then exit 1; fi;;
            esac
            exit 0
        """, directory=self.tmp)

    def test_play_suite_is_rerun_completely_once(self):
        self.play_failing_gradlew(1)
        status, gradle, _ = self.run_script(NUM_SHARDS="2", SHARD_INDEX="1")
        self.assertEqual(0, status)
        play = [call for call in gradle if "connectedPlayDebugAndroidTest" in call]
        self.assertEqual(2, len(play))
        self.assertEqual(play[0], play[1])
        self.assertIn("PlayDistributionTest,", play[1])

    def test_play_suite_failing_twice_fails_the_shard(self):
        self.play_failing_gradlew(2)
        status, gradle, _ = self.run_script(NUM_SHARDS="2", SHARD_INDEX="1")
        self.assertNotEqual(0, status)
        self.assertEqual(2, len([call for call in gradle if "connectedPlayDebugAndroidTest" in call]))

    def test_error_dialogs_are_hidden_before_tests(self):
        self.stub("adb", 'echo "adb $*" >> "$CALL_LOG"')
        self.run_script()
        calls = self.log.read_text().splitlines()
        hide = calls.index("adb shell settings put global hide_error_dialogs 1")
        first_gradle = next(i for i, c in enumerate(calls) if c.startswith("gradlew"))
        self.assertLess(hide, first_gradle)

    def test_invalid_shard_configuration_fails_before_running_tests(self):
        for env in ({"NUM_SHARDS": "2", "SHARD_INDEX": "2"}, {"NUM_SHARDS": "0", "SHARD_INDEX": "0"},
                    {"NUM_SHARDS": "x", "SHARD_INDEX": "0"}):
            with self.subTest(env=env):
                status, gradle, _ = self.run_script(**env)
                self.assertEqual(2, status)
                self.assertEqual([], gradle)


if __name__ == "__main__":
    unittest.main()
