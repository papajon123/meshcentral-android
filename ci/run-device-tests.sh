#!/usr/bin/env bash
# android-emulator-runner starts each script line in a separate shell. Keep status and diagnostics
# in this single process so reporting never changes a failed test result into success.
set -uo pipefail
adb shell settings put secure show_ime_with_hard_keyboard 1
results=app/build/outputs/androidTest-results
test_status=0
./gradlew --no-daemon connectedDebugAndroidTest || test_status=$?
# Shared CI emulators produce occasional timing failures. Rerun only a few failed tests, once; a broad
# failure is not retried. The first attempt stays in the uploaded results under first-attempt/.
if [ "$test_status" -ne 0 ] && retry=$(python3 ci/failed_device_tests.py "$results"); then
  first_attempt=$(mktemp -d)
  cp -R "$results/." "$first_attempt/"
  echo "Retrying failed device tests once: $retry"
  test_status=0
  ./gradlew --no-daemon connectedDebugAndroidTest \
    "-Pandroid.testInstrumentationRunnerArguments.class=$retry" || test_status=$?
  mkdir -p "$results/first-attempt"
  cp -R "$first_attempt/." "$results/first-attempt/"
fi
mkdir -p app/build/reports/webview
adb shell dumpsys webviewupdate > app/build/reports/webview/provider.txt || true
# AGP uninstalls the test application after execution. Provider metadata stays available through
# the system service; renderer decisions and skips are retained in the JUnit/device reports.
exit "$test_status"
