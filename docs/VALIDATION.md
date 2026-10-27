# Validation

## 0.7.3

[#10](https://github.com/papajon123/meshcentral-android/issues/10): on a server that requires a
second factor, sign-in showed the verification-code step, but the button stayed in "Signing in…" and
the screen kept refreshing with "Connection to the server was lost". MeshCentral answers a password-only `x-meshauth` header with a `tokenrequired`
close message and `ws.close()`, an empty close frame. OkHttp reports it as 1005; the client echoed
that reserved code, the resulting exception cleared the rejection and an endless reconnect followed.
A wrong password looped the same way. See [PROTOCOL.md](PROTOCOL.md).

- A local MeshCentral 1.3.1 test instance with an authenticator (TOTP) test account reproduced the
  loop with the 0.7.2 client code on an Android 16 / API 36.1 emulator. With the fix, the code step
  stayed stable and a current TOTP code signed in. A raw probe confirmed the server frames: a
  `tokenrequired` text frame followed by a close frame without a status code.
- `TwoFactorSignInTest` replays those frames over a socket fixture. On the 0.7.2 client code three
  of its four cases failed (three connections within 2.5 seconds instead of one); all four pass.
- Local checks: translation check, 21 CI script tests, 250 JVM tests, lint and debug build passed.
  The instrumented suite on the API 36.1 emulator: 135 passed, 13 skipped opt-in/gallery cases,
  no failures.
- Not verified: a physical phone, e-mail/SMS/push code delivery and a third-party server.

## 0.7.1

The public 0.7.0 signing job was blocked by its API 35 checks: a test assumed both virtualized rows were visible. A separate compact-screen reproduction exposed fixed controls consuming the list viewport. Version 0.7.1 fixes the layout and checks each row after scrolling. The original 0.7.0 source tag remains unchanged; no public APK was released for it.

Current feature, test and platform limits: [ROADMAP_IMPLEMENTATION.md](ROADMAP_IMPLEMENTATION.md). The records below are historical and retain their original versions.


## Current status

This is a preliminary release. Physical phones, Windows Agent tools, third-party servers and live
restrictive-right/consent flows have not been accepted for 0.5.1.

## 0.5.1 local acceptance — 2026-10-21

Source: the 0.5.1 / versionCode 10 changes after `3c5086d`; the final revision and APK metadata are
recorded by the release tag and `release-info.json`. These results are not evidence for the older
published 0.4.0 APK.

- **170 JVM tests passed**, no failures or errors.
- On the final dedicated Android 15 / API 35 emulator with WebView 124.0.6367.219,
  **66 regular Android tests passed**, with seven expected skips (six opt-in live cases and the
  legacy-WebView-only case), no failures. All **six live cases** were then functionally confirmed:
  four real Agent/SSH/files/SFTP controller tests and two real Activity/navigation/SAF tests.
  One live navigation case initially failed in the local HTTP fixture bootstrap, before opening
  the Activity; the other five passed. After checking the fixture's status, body length and schema,
  the isolated navigation case passed. This is evidence across two live runs, not a claim that the
  first six-case run was entirely green; the one bootstrap `ProtocolException` remains unexplained.
- The live UI test verifies the same session and previous output after Activity recreation, no
  command replay, actual visible header/terminal pixels and a real `ACTION_CREATE_DOCUMENT` save
  followed by a byte/hash check. A reproduced blank-rendering defect was fixed with an independently
  clipped native host and parent-specific release of the retained WebView.
- Five renderer tests cover ANSI/split UTF-8, OSC clipboard isolation, direct/composing CJK input,
  both native IME-key and system-key delivery, selection/copy, disconnect rejection and detach/reattach.
  The focused xterm helper's `opacity:0` was reproduced as the cause of dropped native key events on
  WebView 124. Keeping its original geometry at opacity 1 with transparent paint fixed both paths;
  the helper remains invisible and does not intercept touches. Vendor assets are unmodified.
  Synthetic system keys are not evidence for a physical or Bluetooth keyboard.
- Both file adapters have checks for failed/cancelled transfers and unconfirmed remote mutations;
  the real fixture exercised Linux Agent and server-mediated SFTP. Mock tests are not live server
  acceptance of every failure mode.
- Translation validation: **23 locale variants, 529 translated strings each**. Machine translation
  has not received native-speaker review in all languages.
- Debug assembly and Android Lint passed; final CI reports retain exact warning counts.

Live server: **npm MeshCentral 1.2.5** in owned isolated containers, with no host filesystem mounts.
The source audit at `60cea8731f88b91a09704ff9a00dab4992a98ef1` has package version 1.2.6; npm 1.2.6 was
unavailable, so no live 1.2.6 compatibility is claimed. No production account or computer was used.

The additional WebView compatibility test records the actual provider version. The five successful
renderer scenarios may be skipped only on Android below API 35 with a known Chromium major below 80;
then a separate real-provider test must show the localized update instruction within 20 seconds.
Unknown provider versions do not permit a skip. Chromium 80 is a syntax floor, not a tested minimum.
Earlier candidate checks, before the final opacity fix, ran on these providers:

| Environment | Passed | Skipped | Provider and scope |
| --- | ---: | ---: | --- |
| API 26 / Android 8 | 62 | 10 | Chrome 69.0.3497.100; the old-provider update instruction passed in 3.487 seconds. Four incompatible renderer scenarios and six opt-in live-fixture cases were skipped. |
| API 35 / Android 15 | 65 | 7 | WebView 124.0.6367.219; all four renderer scenarios passed. The legacy-only case and six opt-in live-fixture cases were skipped. |

Those individual device runs had no test failures, but a later v0.5.0 candidate run reproduced the
terminal-key defect and stopped signing. The v0.5.0 tag remains unchanged and no APK was published
for it. The earlier API 36.1 run had 71 passing tests; it is not validation of the later CSS change.
The first CI run also exposed a post-test shell-status collection error, fixed by
`ci/run-device-tests.sh`. The final [0.5.1 release workflow](https://github.com/papajon123/meshcentral-android/actions/runs/39613502450)
passed its build, API 26 and API 35 jobs before signing.

### Published artifact

The CI-produced 0.5.1 APK was independently checked for package/version, signer, release manifest
flags and SHA-256. Updating the signed 0.4.0 APK to it and launching it on the dedicated API 35 emulator
succeeded. The GitHub release hosts the 12,218,131-byte artifact:
`804c90c962c1968c841c4d723747cef1304ec8e984d0ff75ad16652632b296c6`.
This is not admission to the official F-Droid catalogue.

## 0.5.3 Android IME correction

The 0.5.2 release run stopped signing after API 35 exposed a direct-input race: Android emitted
`keydown(229)`, `beforeinput(insertText)`, `input(insertText)`, `keyup(229)`, but no relay input.
The native editor, window focus and controller were ready. The independent main-branch run passed,
so a successful retry alone would not establish a fix. The immutable 0.5.2 source tag remains;
no 0.5.2 APK was published.

The adapter now owns non-composition processing-key events through xterm's public custom-key hook,
and forwards their actual input event rather than racing xterm's zero-delay textarea comparison.
Ordinary keys and composition remain handled by xterm. A deterministic regression fails against
the old JavaScript and passes with the correction. It covers input without 229, both timer orderings,
repeated identical characters and a single native deletion; the real InputConnection checks remain.

On the dedicated API 35 / WebView 124.0.6367.219 emulator, **six renderer checks passed** with one
legacy-provider skip. The full suite passed **67 tests**, with eight expected skips: six opt-in live
cases, the opt-in gallery and the legacy-provider-only case. The APK's JavaScript bytes matched the
reviewed source. The existing limitations for non-composition IME replacement and multi-character
deletion are documented in [known limitations](KNOWN_LIMITATIONS.md); arbitrary remote-line editing
is not claimed. Transport code is unchanged; the 0.5.1 live-server evidence was not rerun for this fix.

## 0.5.2 source candidate: presentation and delivery

This candidate's application runtime was unchanged from 0.5.1. The opt-in international gallery passed on API 35:
nine screenshots of real production Composables with fictional data, in English, Simplified Chinese
and Russian. It checks actual pixels and text bounds, not only Compose semantics. Three additional
setup screenshots were captured from the signed 0.5.1 APK with Android per-app language settings.
The three READMEs were rendered with GitHub's Markdown API and passed **48 browser checks** at
1366, 390 and 320 pixel widths, with no reported serious accessibility, reflow or console violations.
The visual sheets were inspected. This verifies the local preview of GitHub-rendered Markdown;
dynamic badges reflect their external providers and can be cached.
Release-snapshot tests use local Git repositories to verify that private development history is
not transferred and that conflicting tags and invalid source trees are rejected. See
[the delivery guide](GITLAB_RELEASES.md) for the enforced release boundaries.

## Earlier readability and product review — 2026-10-21

These results apply to the local follow-up changes after commit `3c5086d`, not to the published
0.4.0 APK. That earlier stage did not change the version/signing identity or publish a new APK.

- JVM: **111 tests passed**. Added tests cover the desktop-denial bit, administrator rights and
  compatibility when the server omits rights metadata.
- Instrumented suite on the dedicated Android 16/API 36.1 emulator: **33 tests passed**.
  The four new adaptive tests failed before the UI fixes, then passed with the full existing suite.
- Debug assembly and Android Lint passed: **0 errors, 28 warnings, 6 informational findings**.
- Translation validation still passes for all **23 locale variants / 389 translated strings**.
- GitHub-rendered English/Russian README previews passed **28 browser checks**, with no reported
  overflow, serious accessibility violations or console errors at 1366/390/320-pixel widths.
  This is a local preview shell around the GitHub Markdown API output, not the live GitHub page.
- The lead and an independent reviewer inspected native screenshots and both README contact sheets.
  The Arabic selected-tab label, all three 2FA actions and the German landscape Save action are
  readable in the inspected cases.

`AdaptiveLayoutTest.kt` uses bounded Compose viewports with synthetic density/font settings and
consumed system insets. It verifies rendered glyph bounds, visible actions and callbacks; it does
not recreate the Activity, test physical rotation or prove persistence of edited settings. The
onboarding checks use a reserved example endpoint and never contact a live server. Screenshot
status-bar overlap belongs to this test harness. Physical-device acceptance, TalkBack, real IME
composition across locales, desktop toolbar coverage and all screen/locale combinations remain open.
Password and 2FA input currently reset on Activity recreation; storing these in a saved Bundle is
not proposed as a workaround.

The two-round product/protocol council and delivery gates are recorded in [ROADMAP.md](ROADMAP.md)
and [ROADMAP.ru.md](ROADMAP.ru.md). Terminal, SSH, SFTP and file management were not part of that earlier review stage; their later acceptance is recorded above.

## Version 0.4.0 localization verification — 2026-10-21

| Check | Result |
| --- | --- |
| Translation catalogs | 23 locale variants, 389 translated strings each, one shared application name; exact locale set and format placeholders verified |
| Translation checker tests | 3 passed |
| JVM unit tests | 108 passed, no failures |
| Instrumented tests on a dedicated Android 16 / API 36.1 emulator | 29 passed, no failures |
| Debug and signed release assembly, debug/release lint | Passed; debug lint: 0 errors, 28 warnings, 6 informational findings |
| APK package, version 0.4.0/code 8, pinned certificate, v2 signature and safety flags | Passed with `ci/verify_apk.py` |
| Real Android system-language selection | Russian and Simplified Chinese followed system language; unsupported Dutch fell back to English |
| Android per-app language | Arabic app override rendered correctly with an English system; original emulator language restored afterward |
| Rendered setup action | All 23 locales: action reachable and glyph bounds not clipped; screenshots inspected for English, Russian, both Chinese scripts, Arabic, German, Hindi and Tamil |
| Pending feedback after language change | Setup validation, Settings error, PIN error and runtime messages tested; no repeated action required |
| Gitleaks 8.30.1 on the working tree | No findings |

The additional tests are in `localization/LocaleResourcesTest.kt`, `LocalizedScreensTest.kt`,
and `LocaleFeedbackTest.kt`. They cover both Chinese scripts, RTL direction, supported-language
fallback, formatted resource resolution and visible feedback changing from English to Russian.
The platform checks also launched the actual application with system and per-app language settings.

Translations were produced with DeepSeek. Technical checks and selected visual inspection do not
replace proofreading by native speakers of all 22 languages. Complete live remote-desktop workflows,
biometrics and every dialog in every locale remain outside this validation. The release workflow
reruns the checks and instrumented tests on API 26 and API 35 before creating a signed draft APK.
The final workflow and APK metadata are attached or linked from the GitHub release.

## Version 0.3.0 historical verification

Local verification on 2026-10-21, for the application source in this public release:

| Check | Result |
| --- | --- |
| JVM unit tests | 108 passed, no failures |
| Instrumented tests on a dedicated Android 16 / API 36.1 emulator | 21 passed, no failures |
| Debug and signed release assembly | Passed |
| Android Lint (debug) | No errors; 18 warnings and 6 informational findings |
| APK identity, release version, certificate, v2 signature, debug/test flags | Passed with `ci/verify_apk.py` |
| Gitleaks 8.30.1 on the initial public history | No findings; private project history was not imported |
| Signed APK installation and first-launch screenshots | Passed on the dedicated emulator; only a reserved example endpoint was entered |

The tests include rejection of cross-endpoint saved credentials, prevention of credential-bearing
HTTP redirects, clearing typed sign-in fields after a server change, empty first-launch setup,
connection recovery, desktop controls and keyboard input. The Android test libraries were updated
to Espresso 3.7.0 / AndroidX Test JUnit 1.3.0 after the previous test runtime failed on Android 16.
These results do not establish live remote-desktop compatibility with arbitrary MeshCentral servers.

Earlier internal builds were exercised against a local MeshCentral 1.2.5 test instance. That historical
evidence is not counted as acceptance of this public build. GitHub Actions results and the final APK
hash are linked from the release; `release-info.json` contains the published APK metadata.

## Automated checks

Requirements: JDK 17, Android SDK platform 36, build-tools 35.0.0.

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
