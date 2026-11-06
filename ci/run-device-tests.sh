#!/usr/bin/env bash
# android-emulator-runner starts each script line in a separate shell. Keep status and diagnostics
# in this single process so reporting never changes a failed test result into success.
#
# Optional sharding: NUM_SHARDS=N SHARD_INDEX=i runs only AndroidJUnitRunner shard i of N.
# Every test belongs to exactly one shard, so the union of all shard jobs is the complete suite.
# A retry names its failed tests explicitly and therefore never carries shard arguments.
# The small Play-distribution suite runs once, in the last shard (or in an unsharded run).
set -uo pipefail
num_shards=${NUM_SHARDS:-1}
shard_index=${SHARD_INDEX:-0}
if ! [[ "$num_shards" =~ ^[1-9][0-9]*$ && "$shard_index" =~ ^[0-9]+$ ]] || [ "$shard_index" -ge "$num_shards" ]; then
  echo "Invalid shard configuration: NUM_SHARDS=$num_shards SHARD_INDEX=$shard_index" >&2
  exit 2
fi
shard_args=()
if [ "$num_shards" -gt 1 ]; then
  shard_args=(
    "-Pandroid.testInstrumentationRunnerArguments.numShards=$num_shards"
    "-Pandroid.testInstrumentationRunnerArguments.shardIndex=$shard_index"
  )
  echo "Running device-test shard $((shard_index + 1)) of $num_shards"
fi
adb shell settings put secure show_ime_with_hard_keyboard 1
# A slow emulator can leave the system launcher unresponsive after Home/PiP tests; its ANR dialog
# would take window focus from the app under test. Hide system error dialogs (Android 9+ honours it).
adb shell settings put global hide_error_dialogs 1 || true
results=app/build/outputs/androidTest-results
test_status=0
./gradlew --no-daemon --max-workers=2 --stacktrace connectedDirectDebugAndroidTest ${shard_args[@]+"${shard_args[@]}"} || test_status=$?
if [ "$test_status" -ne 0 ] && retry=$(python3 ci/failed_device_tests.py "$results"); then
  first_attempt=$(mktemp -d)
  cp -R "$results/." "$first_attempt/"
  echo "Retrying failed device tests once: $retry"
  test_status=0
  ./gradlew --no-daemon --max-workers=2 --stacktrace connectedDirectDebugAndroidTest \
    "-Pandroid.testInstrumentationRunnerArguments.class=$retry" || test_status=$?
  if [ "$test_status" -eq 0 ]; then
    python3 ci/failed_device_tests.py --verify-retry "$results" "$retry" || test_status=$?
  fi
  mkdir -p "$results/first-attempt"
  cp -R "$first_attempt/." "$results/first-attempt/"
fi
if [ "$test_status" -eq 0 ] && [ "$shard_index" -eq $((num_shards - 1)) ]; then
  play_classes='-Pandroid.testInstrumentationRunnerArguments.class=ru.papajon.meshcentralmobile.update.PlayDistributionTest,ru.papajon.meshcentralmobile.update.PlayPrivacyPolicyTest'
  # The Play suite is two small classes; on failure rerun both completely once, never a subset.
  if ! ./gradlew --no-daemon --max-workers=2 --stacktrace connectedPlayDebugAndroidTest "$play_classes"; then
    echo "Retrying the Play distribution suite once"
    ./gradlew --no-daemon --max-workers=2 --stacktrace connectedPlayDebugAndroidTest "$play_classes" || test_status=$?
  fi
fi
mkdir -p app/build/reports/webview
adb shell dumpsys webviewupdate > app/build/reports/webview/provider.txt || true
exit "$test_status"
