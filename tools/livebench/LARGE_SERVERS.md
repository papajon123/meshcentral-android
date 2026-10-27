# Isolated large-server acceptance

`large-servers.cjs` requires an **installed MeshCentral 1.3.1** package and a new empty
run directory. It never uses the shared livebench data or port 4443. Example (PowerShell):

```powershell
node tools/livebench/large-servers.cjs "$env:LOCALAPPDATA/meshcentral-livebench/server/node_modules/meshcentral" C:/path/to/new-isolated-run
```

Append `unpaged` as the fourth argument for a 1.3.1 server without `maxdeviceview`.
Use a new run directory and stop only the owned server before switching modes on port 4445.

The server listens on 4445 with `maxdeviceview=25`, two thousand synthetic NeDB nodes,
and a random local account. Its plaintext password remains in process memory and is supplied
only through a loopback helper on 18885 to the explicitly opted-in Android test. Do not log
or save `/fixture` responses. Do not use this fixture on a public host.
The wrapper exposes the upstream server instance in memory; it does not edit the installed
package. Rename/removal use authenticated control commands; node insertion uses the server DB
and its normal dispatch targets; connectivity uses the upstream connectivity/event methods.
These are synthetic nodes, not 2000 connected Mesh Agents.

The review regression test can opt into `/slow` (150 ms delay per ordinary nodes DB callback)
and `/fast`. These preserve upstream query results/protocol and expose full/addressed read counts
and offsets through `/status`, without credentials or raw frames. `/group-event` and
`/rename-last`/`restore-last` exercise collection coalescing and retained off-page devices.
The power command is acknowledged by the real server, but synthetic nodes cannot physically
reboot; connectivity transitions in this fixture are injected through the upstream server API.

Use only a dedicated emulator. This task uses `emulator-5666`:

```powershell
$env:JAVA_HOME='C:/Program Files/Android/Android Studio/jbr'
$env:ANDROID_HOME="$env:LOCALAPPDATA/Android/Sdk"
$env:ANDROID_SERIAL='emulator-5666'
.\gradlew.bat connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=ru.papajon.meshcentralmobile.discovery.LargeServerLiveTest,ru.papajon.meshcentralmobile.discovery.LargeServerUiTest,ru.papajon.meshcentralmobile.discovery.LargeListBenchmarkTest' '-Pandroid.testInstrumentationRunnerArguments.largeServer=4445'
```

The live test is skipped without the exact `largeServer=4445` opt-in. It verifies page movement,
server-wide name search, empty search recovery, favorites/recents, connectivity, rename,
insertion/removal, totals, and one control connection across these actions. New nodes can take
up to the 15-second reconciliation interval because the server suppresses their event outside
the current page. RTL/2x font screenshots are copied to `/data/local/tmp/large-server-*.png`.

`LargeListBenchmarkTest` measures a repeatable 2000-row UI workload; its times include test
synchronization and are not FPS. `DeviceListBenchmarkTest` compares the old full sort with
incremental row insertion over 500 events, reporting medians of five warmed-up rounds and
asserting identical output. Shared host load can affect emulator timings.

To measure real-time rendering without the Compose test clock, install the debug and test APKs,
then explicitly seed the owned emulator and launch the real app:

```powershell
adb -s emulator-5666 shell am instrument -w -e class ru.papajon.meshcentralmobile.discovery.LargeServerScrollSetupTest -e largeScroll 4445 io.github.papajon123.meshcentral.debug.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5666 shell am start -n io.github.papajon123.meshcentral.debug/ru.papajon.meshcentralmobile.MainActivity
python tools/livebench/large-scroll.py C:/path/to/results --all
```

The setup test stores the synthetic credentials through SecureVault and prepares 2000 favorites.
Dismiss the optional update-check consent prompt with **Not now** on the fresh debug install.
Use `--all` on the unpaged 2000-device fixture for before/after comparisons. The script checks
the full list, warms up rendering, performs 12 real-time swipes on the non-clickable left gutter,
and verifies that the device list remained open and its visible row index advanced. It writes
raw `dumpsys gfxinfo` statistics (including missed vsync and both jank counters) and a screenshot.
An invalid screen/gesture run fails and is excluded, never reported as scrolling performance.
Alternate the two APKs on the same emulator and dataset, with the same warm-up and gesture sequence;
record every run and host-load limitations rather than picking the fastest result. Clear only
this debug app's data on the owned emulator afterwards. Never use this preparation on a personal
phone or an emulator belonging to another task.

`compare-scroll.ps1 -BeforeApk <apk> -AfterApk <apk> -OutputRoot <directory>` automates three
counterbalanced pairs (AB/BA/AB), records both APK hashes and available host memory, and stops on
an invalid screen/gesture run. Keep other builds and instrumentation tests out of the measurement
window. The synthetic fixture disables agent downloads/signing activity and binds to loopback.

After testing, stop only the Node process whose command line contains this script and the
chosen run directory, and only your emulator. Never kill all Node/Java/emulator processes.
