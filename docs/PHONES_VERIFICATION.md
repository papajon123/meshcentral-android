# M3 phone support — verification and handoff

Implemented on `mr/phones`, based on `3e3a6bf`. No server access, push, merge request, release, signing
change or agent-source change was performed. The maintainer performs independent review and live
acceptance. The shared `origin/main` advanced during this work; the feature branch retains its original
base and must be integrated by the maintainer rather than resetting either branch.

## Evidence

- `python ci/check_translations.py`: 23 locales, 1011 translated strings each, one shared string;
  32 new phone strings per locale in separate `phone_strings.xml` files.
- `gradlew.bat testDebugUnitTest lintDebug assembleDebug`: successful; 269 JVM tests, no failures,
  errors or skips. Lint: zero errors, 72 warnings and seven informational findings across the project.
- `connectedDebugAndroidTest` with the five targeted test classes: 39 tests passed. Two further
  Android-upload/readback tests were then added; the complete targeted set run with
  `adb -s emulator-5576 shell am instrument` passed **41 tests**, no failures or skips.
- The 41-test set comprises eight `PhoneUiTest`, one `AndroidPhoneControlTest`, four
  `AndroidFilesControllerTest`, and 28 existing Agent/SFTP controller regression tests.
- Device: dedicated `Medium_Phone_API_36.1`, API 36.1 / Android 16, serial `emulator-5576`, started
  `-read-only -no-snapshot -no-window -port 5576` with 12.5 GiB free host memory. Other emulators were
  not used. Only synthetic data and in-process fake/MockWebServer connections were exercised.
- Local, ignored evidence: `verification/build-final.log`, `verification/instrumentation-final.log`,
  `verification/final-check.log`, `verification/phone-evidence/*.png`. The screenshots cover the
  display-only notice/dock, actions, confirmation, Russian dark inspector/network and Arabic RTL
  actions at 150% font scale with a 360 dp content limit. They are not real customer device data.
- The final eight UI tests were rerun to capture all six screenshots after the visual spacing pass;
  all passed and the images were visually inspected. The emulator was stopped after capture. Its
  second read-only launch had 6.2 GiB free host memory, also above the specified 6 GiB threshold.

## Behaviours checked

- Agent 14 recognition, caps 13 (desktop/files, no terminal), input blocked even for administrators,
  portrait width fitting and independent vertical pan bounds.
- Exact command JSON, 401/5000 and 400/10000, alert/toast/dial/openUrl; text limits, quotes/newlines,
  Cyrillic/emoji, invalid URL schemes, malformed URLs and alphabetic phone-number rejection.
- Battery nodes/events, valid zero and fractional levels, unknown/malformed data, preserving live
  sessions through changenode, clearing at disconnect, and no command replay after reconnect.
- Unknown/view-only rights and stale/offline states deny phone actions; changing rights while a
  confirmation is open removes it. The client rechecks current permission immediately before sending.
- Inspector switching from a computer's Services tab to a phone always displays phone details.
  The regression was reproduced before the fix (`verification/switch-red.log`).
- Android p5 binary JSON and virtual-root translation; p10 options/handshake, bounded byte streaming,
  normal EOF, empty files, JSON-looking bytes, truncation, excess data and transport failure.
- Phone downloads wait for EOF and a fresh directory listing. Uploads require SHA-256 readback and
  reject same-size corrupt contents. Deletion waits for the phone reply before checking the listing.
- Existing Agent/SFTP file checks remain intact; unsupported Android create/rename operations are
  not offered. Cancelling an unsupported agent-side upload cancellation reports uncertainty and closes
  the session instead of claiming removal.

## Deliberate choices and remaining acceptance

The agent has no escape syntax for ASCII double quotes. They become U+201D, with a visible notice
and an exact message/title preview before confirmation. No invented backslash escaping is sent.
Alert always includes a nonempty title to avoid the upstream one-argument bug. Link/dial validation
is deliberately narrow. All action results mean locally sent, not confirmed phone execution.

Phone input remains disabled in one capability decision, even if an experimental agent branch has
input changes. The audited target is agent `main` at `5ba4560f749447afa4b90d52fc52f394c3c8ff40`.
Protocol sources and exact line references are in [PROTOCOL.md](PROTOCOL.md#5-official-android-phones-meshcentral-131).

Live acceptance still needs a real official agent and MeshCentral 1.3.1: screen-capture consent and
rotation, battery charging/discharging, cached system/network access, every phone command, and
Android storage permissions/MediaStore naming for upload/download/delete. MediaStore can choose a
different destination/name or expose duplicate names; such results must remain unverified. Broad
physical-device, Android-version, tablet, landscape and localization acceptance was not performed.
