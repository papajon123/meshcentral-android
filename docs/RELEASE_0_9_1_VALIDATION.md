# 0.9.1 and 0.9.2 release validation

0.9.1 (code 25) is the first published build of the 0.9 line. The `v0.9.0` snapshot reached GitHub, but its
`Signed APK release` workflow stopped at device tests twice (API 26: 13 failures, API 35: 9), so no 0.9.0 APK
was released.

## Causes and fixes

| Area | Cause | Fix |
|---|---|---|
| Port forwarding tests (API 26) | `InputStream.readNBytes` exists from Android 13 | Test helper `readUpTo` |
| Favorites widget test (API 26) | Shell permission adoption needs Android 10 | `appwidget grantbind` below Android 10 |
| Launcher navigation (API 26) | Fullscreen change resumed off the UI thread after a background lookup | App: window flag changes run on the UI thread |
| Folder picker test (API 26) | Android 8 DocumentsUI uses "Select" and has no grant dialog | Version-specific labels |
| Files selection test (API 26) | Active-window root stayed on the dismissed dialog | Search every interactive window |
| Invitation clipboard tests (API 26) | Clearing requires `clearPrimaryClip` (Android 9+), as documented | Run on Android 9+ |
| Platform navigation (API 35) | Injected edge swipes are not Back on the API 35 image; one-time immersive hint took Back; IME still hiding | Back key below API 36, hint pre-acknowledged, wait for IME, typing state from dock semantics |
| Desktop wiring test | Relay now sends nothing before the KVM protocol number | Fixture marks the relay as paired |
| Locale fallback test | A WebView loaded earlier adds Dutch to the app's assets | Unsupported first locale `fy-NL` |
| Chat landscape keyboard | 320dp CI profile leaves ~70dp beside a docked IME; Android 8-10 pans the window | Layout checks on Android 11+ phones at least 360dp wide |

App changes: window flags on the UI thread; dock highlight exposed as `selected` to accessibility.

## Checks

- Local reproduction on AVDs matching the GitHub profile (`google_apis` x86_64, 320x640 160dpi, hardware keyboard,
  animations off), freshly wiped: full `connectedDebugAndroidTest` — API 26: 699 tests, API 35: 704 tests. Remaining
  failures: one `PortForwardLifecycleTest` connection reset on API 26 that passed on two reruns, and
  `HardwareKeyboardTest.escapeChordReleasesCaptureAndTabEnterOperateLocalMenu` on API 35, which fails the same way
  on the unchanged 0.9.0 tree locally but passed on GitHub in both 0.9.0 attempts.
- `testDebugUnitTest lintDebug assembleDebug compileDebugAndroidTestKotlin`, translations and CI fixture tests.

## Known limitation

On Android 8-10 in landscape with a docked keyboard the chat window is panned and its header can move out of view;
system Back still works. Not covered by device tests on the CI profile.

## 0.9.2

`v0.9.1` reached GitHub, but its signed build failed four device tests on the first attempt (API 26: a forward
rejection reported during connect; API 35: navigation Back while the IME was hiding, a chat draft sent from the
squeezed composer, the PiP unlock action below a short window) and two on the rerun (navigation again and
`HardwareKeyboardTest`, whose first Tab was consumed while capture was still releasing). Each was reproduced locally
and fixed in the test; the PiP case also revealed a keyboard left open over the lock keypad, which the lock screen
now hides. A full run on freshly wiped API 26/35 AVDs then showed three other timing failures (API 26: tab
navigation, `Parity2InspectorTest` scroll; API 35: `HardwareKeyboardTest` on a fresh device), each passing on
rerun. `ci/run-device-tests.sh` therefore reruns at most five failed tests once; broader failures are not
retried, and the first attempt is uploaded under `androidTest-results/first-attempt/`.
