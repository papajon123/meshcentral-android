#!/usr/bin/env bash
# android-emulator-runner starts each script line in a separate shell. Keep status and diagnostics
# in this single process so reporting never changes a failed test result into success.
set -uo pipefail
adb shell settings put secure show_ime_with_hard_keyboard 1
test_status=0
./gradlew --no-daemon connectedDebugAndroidTest || test_status=$?
mkdir -p app/build/reports/webview
adb shell dumpsys webviewupdate > app/build/reports/webview/provider.txt || true
# AGP uninstalls the test application after execution. Provider metadata stays available through
# the system service; renderer decisions and skips are retained in the JUnit/device reports.
exit "$test_status"
