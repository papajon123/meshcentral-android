# Stylus / S Pen — M8, planned for 0.8.1

This work adds pen input to the existing native desktop canvas. It does not change the 0.8.0 release
version, add a server extension or publish a build. Source branch: `mr/stylus`.

## Behavior and controls

| Target / action | Result |
| --- | --- |
| Computer, pen hover | Absolute cursor movement, no button |
| Computer, pen contact / movement / lift | Left down / drag / left up |
| Primary side button at contact | Right button |
| Secondary side button at contact | Middle button (wins if both side buttons are held) |
| Eraser end | Right button, not an erasing operation |
| Palm while pen is nearby or touching | No remote click, movement or scroll |
| Two actual fingers | Local zoom/pan; cancels any pen stroke |
| Phone with enabled input | Precise tap, hold and swipe through the existing bounded replay |
| Phone hover / unavailable input | No remote command |

Settings → Session → **Stylus as a precise mouse** is on by default. It applies across server
profiles. Turning it off gives the pen the existing finger behavior of the chosen mode. A short
hint appears on the first pen use and its seen flag survives reopening the app. All four new strings
are in `values*/stylus_strings.xml` for all 23 locales.

The existing `DESIGN.md`, Compose/Material 3 theme, switch row and Snackbar components remain the
visual contract. Android emulator checks use a displaced canvas to verify that window insets and
the phone header do not offset the pen. There is no browser interface in this feature.

## Protocol and implementation

- `StylusInputController` owns one contact, the selected button, palm state and optional phone
  gesture; it has no Android dependencies and is checked against literal KVM bytes.
- `StylusCanvasInput` observes real `MotionEvent` tool types/buttons/hover before the existing
  Compose finger recognizers. Raw screen coordinates are converted to the actual canvas layout.
- `SessionCanvas` suppresses finger commands for the whole rejected gesture; only actual fingers
  participate in local transforms. `PhoneSessionCanvas` reuses bounded `PhoneTouchGesture` replay.
- Lost input permission, cancellation, geometry changes and disposal cancel the contact. No input
  is replayed into a replacement session. The normal relay checks still govern every command.

The [protocol audit](PROTOCOL.md#stylus--s-pen-m8-081) describes the audited MeshCentral 1.3.1 source
and exact packet fields. Pressure, tilt, handwriting recognition and native remote pen/eraser events
are outside this protocol. This is a precision pointer, not a graphics-tablet transport.

## Validation

The M8 acceptance set comprises JVM literal-packet/state tests, production-canvas Android tests
with synthesized `TOOL_TYPE_STYLUS`/`TOOL_TYPE_ERASER` events, the complete existing test suites,
translation parity, debug packaging, release Kotlin compilation and a live isolated phone session.
The original M8 run had 10 pen JVM tests, 12 pen canvas/settings/hint tests and two pen relay tests. The Android
fixture aims at pixel centers after window positioning settles, while retaining exact byte assertions.

Final complete run on 2026-10-26: **JVM 512 = 511 passed + 1 skipped; Android 329 = 316 passed +
13 skipped; zero failures**. Counts are individual JUnit XML cases, not Gradle's inflated 342-case
console total. Skips are existing opt-in galleries/live fixtures and the legacy-WebView check.
`testDebugUnitTest`, `lintDebug`, `assembleDebug`, `compileReleaseKotlin` and the full
`connectedDebugAndroidTest` finished with exit code 0. Translation parity passed for 23 locales,
1173 strings, one plural and one shared string.

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
$env:ANDROID_SERIAL = 'emulator-5638'
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug compileReleaseKotlin connectedDebugAndroidTest `
  '-Pandroid.testInstrumentationRunnerArguments.parityLocale=ru' `
  '-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true' --max-workers=2 --console=plain
python ci/check_translations.py
```

Live acceptance on 2026-10-26 used a dedicated API 35 tablet client and API 36 phone agent, the local
MeshCentral 1.3.1 bench and agent `feature/remote-control` at `b23d415`. Screen-sharing and remote-input
consent were approved on the agent; the visible stop-control indicator remained active. Real
`adb shell input stylus` events on the **client** opened the phone app drawer, tapped the Settings
icon (the agent foreground became `com.android.settings/.Settings`), scrolled that list and held
the home screen for 800 ms to open its context menu. The first-use hint was visible in the real
viewer and did not repeat. Agent screenshots and foreground inspection confirmed the results;
UI automation was not attached to the agent during control.

The language parameter `-Pandroid.testInstrumentationRunnerArguments.parityLocale=ru` matches the
test emulator's application locale. Two existing form tests otherwise expect English Cancel labels
inside Russian platform dialogs; both pass with the matching locale. No assertions were removed.
Two existing network-recovery WebSocket fixtures now answer Close frames, completing the handshake
instead of intermittently timing out during `MockWebServer.shutdown`. Retry budgets and assertions
are unchanged.

Physical S Pen hardware, vendor palm filtering and Windows/Linux/macOS live applications are not
covered by an Android emulator. No pressure/tilt or graphics-tablet compatibility is claimed.

## M8b review fixes

- Hover is cleared on Compose `PointerEventType.Exit`, including exits backed by native
  `ACTION_HOVER_MOVE`, on pen down and on a completed touch stream. Missing exit events expire
  after 750 ms using Android's monotonic uptime clock. An already rejected palm stays rejected
  until it lifts.
- Synthetic cancellation releases the remote press without erasing pen/palm state and opens a
  250 ms transition guard. Finger-only cancellation does not create pen proximity. The unreachable
  raw `ACTION_CANCEL` branch was removed; Compose's synthetic release handles cancellation.
  A subsequent hover clears the cancelled physical-contact flag while retaining rejected palms.
- Pen cursor updates also set the trackpad cursor through the current viewport's `toCanvas`,
  so the next finger tap clicks the pen's remote pixel, including at increased zoom and after a
  successful release at a new point without an intermediate move event.
- Phone replay availability is separate from session input permission. Starting or completing
  replay cannot reset palm rejection; busy replay still prevents a second remote contact.

New regression coverage includes canvas-to-panel hover exit, hover expiry/refresh, down without
hover exit, cancellation and recovery, touchscreen-source palm input during a pen stroke,
trackpad taps after pen hover at 1x/2x zoom, and pen contact spanning phone replay completion.
The three initial JVM regressions and three initial Android regressions failed before the fixes.
The phone replay regression also failed when only the old replay/permission coupling was restored.
Independent review added two further regressions for hover after cancellation and the final
release pixel; both failed on JVM and Android before their fixes. DeepSeek provided a read-only
test-surface inventory; Astra independently reviewed the changes. The primary agent implemented
and verified the corrections.

M8b is based on `39711aa`, fetched and rebased at the start. While validation was running,
`origin/main` advanced to `5d8946c` (including the preparation of 0.8.1). The tested base is kept
explicit so these results are not mistaken for validation of that later release commit.

Final M8b run on 2026-10-26: **JVM 520 = 519 passed + 1 skipped; Android 336 = 323 passed +
13 skipped; zero failures/errors**. Counts come from JUnit XML, not the 349-case Gradle console
total. The pen-specific sets now contain 18 JVM and 19 canvas/settings/hint cases; the two relay
cases are unchanged. All requested Gradle tasks and translation parity returned exit code 0.
Lint reported 0 errors and 82 warnings. Translation parity remains 23 locales, 1173 strings,
one plural and one shared string.

The dedicated `Medium_Tablet` API 35 emulator used port 5646 with `-read-only -no-snapshot
-no-window -no-audio -no-boot-anim`; 11.07 GB of memory was free before launch. The requested
Android Studio JBR is Java 21.0.8, with the project's Java/Kotlin target still 17 and SDK/build-tools
35. The final command was:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME = 'C:\Users\alex\AppData\Local\Android\Sdk'
$env:ANDROID_SERIAL = 'emulator-5646'
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug compileReleaseKotlin connectedDebugAndroidTest `
  '-Pandroid.testInstrumentationRunnerArguments.parityLocale=en' `
  '-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true' --max-workers=2 --console=plain
python ci/check_translations.py
```

This emulator's dialog resources use English. An initial run with the previous report's `ru`
parameter failed the two existing form-action label checks; both passed unchanged with `en`,
including in the final full run. One intermediate pen-drag test received a MOVE at (170, 270)
instead of (150, 250). Its unchanged isolated rerun and the final full run passed; the transient
coordinate discrepancy's cause was not established, and no assertion was weakened.

Local logs and inspected screenshots are in the ignored `verification/m8b/` directory, particularly
`full-final.log`, `review-red.log`, `review-green.log`, `pen-move-recheck.log`, `stylus-settings.png`
and `stylus-first-use.png`. M8b did not repeat the earlier live-server acceptance or test physical
S Pen hardware. No push, merge request, release or publication was performed.

## M8c: exact input samples after rebasing onto 0.8.1

The intermittent MOVE mismatch also recorded above on the pre-rebase base is caused by
Android input resampling, not a change in the production canvas on `main`. The range
`39711aa..5d8946c` changes other instrumentation tests and release metadata, with no
`app/src/main` changes. Ten isolated repetitions of the original test passed on the rebased
branch, so those passes alone did not rule out the existing timing-dependent failure.

A diagnostic run pinned the DOWN/MOVE sample interval to 10 ms. The injected screen MOVE
was `(230.5, 390.5)`, but the Activity received `(255.5, 415.5)` with one historical sample;
the canvas origin remained `(80, 140)`. The coordinate change happened before application
dispatch. Android 15's [InputConsumer resampling implementation](https://android.googlesource.com/platform/frameworks/native/+/refs/heads/android15-release/libs/input/InputConsumer.cpp)
extrapolates stylus MOVE samples for intervals of 2–20 ms, depending on frame timing.

`StylusCanvasTest` now requests unbuffered dispatch on DOWN through a temporary Activity
window callback, restored after each test. This is local to the test touch stream and uses
the API available since before the app's minimum SDK. Real `UiAutomation.injectInputEvent`,
the production canvas/encoder, all modes, and every exact byte assertion remain in use.
Production input behavior and global emulator settings are unchanged. A new regression
pins a 10 ms sample interval across all computer modes; it failed with the dispatch fix
disabled and passed with the fix enabled. Temporary diagnostic tracing was removed.

Validation on 2026-10-26 after rebasing onto `5d8946c`:

- Original test after the fix: **10/10** independent runs.
- Entire `StylusCanvasTest`: **20/20**, including the short-interval regression.
- Full `connectedDebugAndroidTest`: **338 = 325 passed + 13 skipped**, zero failures/errors
  in JUnit XML (Gradle's console reports 351 including repeated skip notifications).
- `testDebugUnitTest lintDebug assembleDebug compileReleaseKotlin`: exit 0; JVM XML
  **520 = 519 passed + 1 skipped**; lint **0 errors, 82 existing warnings**.

The full run used `parityLocale=en`, matching this AVD's dialog resources, and the same
`leaveApksInstalledAfterRun=true --max-workers=2 --console=plain` options as M8b. The dedicated
`Medium_Tablet` API 35 emulator used port **5650** with `-read-only -no-snapshot -no-window
-no-audio -no-boot-anim`; free memory before its two launches was 12.07 GB and 22.03 GB.
The requested Android Studio JBR is Java 21.0.8; Java/Kotlin compilation still targets 17.

The first full attempt lost the instrumentation result stream at test 294; Android's runner
continued, but UiAutomation subsequently became unavailable. The reported test completed
without an assertion failure in Android's log, and both tests around the interruption passed
unchanged in an isolated rerun. The interruption's cause was not established. After restarting
only this dedicated emulator, the complete unfiltered suite passed in 11m 26s. This successful
repeat required no further code changes; the interrupted run is retained separately.

Evidence is under ignored `verification/stylus-rebase/`: `diagnostic-fast.log`,
`regression-red.log`, `regression-green.log`, `fixed-1.log` through `fixed-10.log`,
`stylus-class.log`, `required-build.log`, `full-final.log`, and `full-interrupted-results/`.
DeepSeek Flash compared the base commits read-only; the primary agent verified that comparison,
implemented the fix, and ran the checks. The emulator was stopped after validation.
Physical pen hardware and live servers were not tested. No push, MR or publication was performed.
