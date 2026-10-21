# Validation

## Current status

This is a preliminary release. The exact 0.3.0 revision has **not** been validated on a physical phone or against third-party MeshCentral servers. Do not treat this document as a pass record for 0.3.0 until the maintainer fills in the release record at the end.

Earlier internal builds were exercised against a local MeshCentral 1.2.5 test instance and an Android emulator, but the test set changed for this release. The final test counts, device results, and signing evidence must be recorded after the release build is cut; do not copy numbers from older builds.

## Automated checks

Requirements: JDK 17, Android SDK platform 35, build-tools 35.0.0.

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

Windows PowerShell uses `.\gradlew.bat` with the same tasks. The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

Expected result: the build completes, JVM unit tests pass, and lint reports no new errors. The exact task output and test counts are recorded by the maintainer for the tagged revision.

### What the JVM tests cover

- Control-message parsing: `serverinfo`, `userinfo`, `meshes`, `nodes`, `authcookie`, events, two-factor challenges, clipboard replies, routing failures, and malformed input.
- KVM framing: tiles, copy rectangles, screen size, displays, geometry, key state, cursor, input lock, truncated frames, fragments, several commands in one frame, jumbo wrappers, and corrupt lengths.
- Captured KVM frames from a test agent session, stored as protocol bytes without a real desktop image.
- Input encoding: compression, pause, refresh, input lock, display selection, touch initialisation, keys, Unicode text, Ctrl+Alt+Del, and mouse packets.
- `x-meshauth` construction, two-factor request tokens, and close-message classification.
- Relay URL and tunnel-request construction, including the node-id match.
- Viewport mapping, zoom, pan clamping, and letterbox hit testing.

## Instrumented checks

```bash
./gradlew connectedDebugAndroidTest
```

Run this only on a dedicated emulator or test device that contains no personal data. Use a local MeshCentral test instance and reserved example domains such as `https://meshcentral.example.com/`; use synthetic computer names, never a production account or a third-party server.

The instrumented suite covers connection recovery, desktop resume, session controls and canvas behaviour, and native keyboard/IME forwarding. Results for the 0.3.0 revision are recorded by the maintainer.

## Manual checklist

These steps need a test MeshCentral instance you control and a test computer with a Windows Mesh Agent. They are not automated because they need credentials and a controlled desktop.

- [ ] Build the release variant with the maintainer's signing configuration; record the APK SHA-256 and `versionCode`.
- [ ] Install it on a dedicated emulator or test phone; confirm the app starts and the version matches the release.
- [ ] First launch: confirm the server field is empty; entering `http://…` is rejected in a release build; a valid `https://…` URL is accepted.
- [ ] Sign in with a **test** account; verify a rejected password shows the server's reason, and that a required two-factor code can be entered or requested by the offered channel.
- [ ] Device list: verify groups, online/offline state, search, favourites, per-group rights, and the reason shown for offline or non-agent computers.
- [ ] Open a native desktop on an online Mesh Agent; verify the first frame, latency display, reconnect, and clean close.
- [ ] Multi-monitor: switch between displays and the combined view; verify that the canvas redraws and the label follows the stream.
- [ ] Pointer: tap, long press, double tap, drag, two-finger scroll, and pinch zoom; confirm clicks land at the touched point and taps in the letterbox do nothing.
- [ ] Keyboard: on-screen keys, modifiers, shortcuts, Backspace/arrows/Enter, and Unicode text; confirm the remote window receives the expected input.
- [ ] Clipboard: copy text on the test computer, fetch it in the app, send text back, and confirm an exact round trip. Verify the empty-clipboard timeout message.
- [ ] View-only account: verify the session opens but no input is sent.
- [ ] App lock: set a PIN, background and foreground the app, unlock with PIN; on Android 9+ verify biometric unlock; on Android 8 verify the unavailable message and PIN fallback.
- [ ] Screenshot blocking: confirm screenshots and the recent-apps preview are blocked only when the setting is on.
- [ ] Data clearing: remove the saved sign-in and confirm the session and WebView data are gone.
- [ ] Update path: confirm a locally built APK with a different key is rejected over the release APK; confirm the same signed build with a higher `versionCode` installs.
- [ ] Crash scan: `adb logcat -b crash` and a filtered `adb logcat` show no `FATAL` or `ANR` for `io.github.papajon123.meshcentral`.
- [ ] Log hygiene: inspect debug logs and confirm no credentials, cookies, PINs, tokens, relay identifiers, or clipboard contents appear.

## Earlier internal verification

The protocol path was checked against a local MeshCentral 1.2.5 instance with a real Windows Mesh Agent, using a standalone test client independent of the Android UI:

- `x-meshauth` control-channel authentication and `serverinfo`/`userinfo`/`meshes`/`nodes`/`authcookie`.
- Tunnel request, relay open, text `c`, protocol text `2`, and the initialisation batch.
- Screen-size and display-list commands, tiles including a jumbo-wrapped tile, key state, cursor, and display geometry.
- RTT echo, mouse input, clean close, and a `setclip`/`getclip` text round trip.

The Android UI was exercised on an emulator for onboarding, sign-in, the device list, session controls, keyboard and text sheets, clipboard sheets, settings, and the lock screen.

These runs show that the protocol and UI paths worked in earlier builds. They are not evidence for the 0.3.0 build and are not reproduced with per-run screenshots or exact counts here.

## Evidence rules

- Never commit screenshots of a real desktop, a real server name, an account name, or a session cookie.
- Prefer synthetic device data and a test server; redact host names and addresses from logs.
- Record the command, revision, device or emulator, Android version, server version, and result for each release-gate check.
- Do not write a result that was not observed. “Not run” is a valid status.

## Release record (maintainer)

The maintainer adds the following before tagging the release:

- Git revision and `versionCode`/`versionName` of the tested build.
- Exact `testDebugUnitTest`, `lintDebug`, `assembleDebug`, and `connectedDebugAndroidTest` results and counts.
- Emulator/device model and Android version; MeshCentral server version.
- APK SHA-256, signing-certificate fingerprint, and package name.
- Any check that remained “not run” and why.
