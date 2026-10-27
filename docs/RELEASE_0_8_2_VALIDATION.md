# 0.8.1–0.8.4 release validation

Date: 2026-10-26. Public package: `io.github.papajon123.meshcentral`. Both releases are GitHub
prereleases signed with the identity in [`ci/release-identity.json`](../ci/release-identity.json).
Candidate checks of the 0.8 line remain in [0.8.0 validation](RELEASE_0_8_0_VALIDATION.md).

## Releases

| Release | Code | Source commit | GitHub Actions | APK SHA-256 | Size, bytes |
|---|---|---|---|---|---|
| [0.8.1](https://github.com/papajon123/meshcentral-android/releases/tag/v0.8.1) | 20 | `5d8946c` | [40300511339](https://github.com/papajon123/meshcentral-android/actions/runs/40300511339) | `cb154c9794a533b5ee2ec419d0950010f14eea4b4e0fde2e3bb822f2b797393e` | 14 564 124 |
| [0.8.2](https://github.com/papajon123/meshcentral-android/releases/tag/v0.8.2) | 21 | `6fcdade` | [40317057892](https://github.com/papajon123/meshcentral-android/actions/runs/40317057892) | `72eaaac6fdcb4a5e8d9439683eefba1145ad7b6c8c6f028278b880a8171e2a2f` | 14 612 964 |
| [0.8.4](https://github.com/papajon123/meshcentral-android/releases/tag/v0.8.4) | 23 | `289727d` | [40378683272](https://github.com/papajon123/meshcentral-android/actions/runs/40378683272) | `6a169161f81896fcf47390827055be9c18ab43c815ed52be87c3d6caaed0c8da` | 14 645 716 |

Signing certificate SHA-256 for both: `5458084705359e0dfa9cbb83ed73a0d77c7758370cffe2a7d6b036e9275eeb2f`.

There is no GitHub release 0.8.0. The `v0.8.0` workflow failed two device tests on GitHub's
emulators: on the small default screen the keyboard covered a confirmation button, and a mock server
shutdown waited too long for a WebSocket to close. Tags are immutable, so the stabilized tests shipped
as 0.8.1 with unchanged app code.

## Checks

### 0.8.1

- Before tagging, both failing tests ran 10 times each on API 26 and API 35 emulators matching the
  GitHub profile. Full device suites: API 26 — 295 passed, 20 skipped; API 35 — 303 passed, 13 skipped.
- GitHub Actions: build, device tests on API 26 and 35, and release passed.
- `ci/verify_apk.py --version 0.8.1` passed; the APK hash equals `SHA256SUMS` and `release-info.json`.
- Upgrade 0.7.3 (18) → 0.8.1 (20) on an API 36.1 emulator: saved data kept after **Not now** on the
  new one-time update-check question; no crash.

### 0.8.2

- New: [stylus and S Pen input](STYLUS.md).
- Before tagging, full device suites on local emulators matching the GitHub profile (`google_apis`
  x86_64, 320×640, 160 dpi, hardware keyboard with the on-screen keyboard enabled as in
  [`ci/run-device-tests.sh`](../ci/run-device-tests.sh)): API 35 — 338 tests, 0 failures, 13 skipped;
  API 26 — 337 tests, 0 failures, 20 skipped.
- `TerminalControllerTest` was stabilized: a 20 ms SSH handshake deadline could expire on a loaded CI
  runner before the first scripted server frame. Tests that script a frame before the deadline now use
  500 ms; the empty-output test also asserts the shell-deadline message.
- GitHub Actions: build, device tests on API 26 and 35, and release passed.
- `ci/verify_apk.py --version 0.8.2` passed; the APK hash equals `SHA256SUMS` and `release-info.json`.
- Upgrade 0.8.1 (20) → 0.8.2 (21) on an API 36.1 emulator: installed over the previous version, app
  user ID and first-install time kept, saved server address visible, no crash.
- Update check: the GitHub update code of 0.8.1 (unchanged in 0.8.2), run on the JVM as installed code
  20 with consent, selects 0.8.2 (code 21) with the expected hash and certificate and no metadata
  rejections.

### 0.8.3 (not on GitHub) and 0.8.4

- New: the MeshPilot name with the independence notice, sign-in with MeshCentral login tokens and login keys
  (GitHub #11), and decoded desktop tiles released right after drawing.
- 0.8.3 (code 22, source `a1de435`) passed the full device suites on GitHub-profile emulators before tagging:
  API 35 — 359 tests, 0 failures, 13 skipped; API 26 — 358 tests, 0 failures, 20 skipped. Its snapshot was never
  published: the deploy key of the snapshot bridge cannot push changes under `.github/workflows/`, and 0.8.3 changed
  the release title in `release.yml`. Tags are immutable, so the same app shipped as 0.8.4; the workflow file was
  first uploaded to GitHub `main` unchanged byte for byte, then the bridge published the 0.8.4 snapshot. The bridge
  now connects through `ssh.github.com:443` with the same pinned host keys.
- 0.8.4: GitHub Actions device tests on API 35 failed on the first attempt with timeouts in `TerminalRendererTest`
  (WebView terminal) and `ParityUiTest` and passed when the failed jobs were rerun; build, API 26 and release passed.
  Those timeouts are being stabilized separately.
- `ci/verify_apk.py --version 0.8.4` passed; the APK hash equals `SHA256SUMS` and `release-info.json`.
- Upgrade 0.8.2 (21) → 0.8.4 (23) on an API 36.1 emulator: installed over the previous version, app user ID and
  first-install time kept, saved server address visible, no crash.
- Update check: the GitHub update code (unchanged since 0.8.1), run on the JVM as installed code 21 with consent,
  selects 0.8.4 (code 23) with the expected hash and certificate and no metadata rejections.

## Limits and remaining acceptance

- Stylus input was tested with injected stylus events on emulators, not on a physical device with an
  S Pen. Pressure and tilt are not carried by the MeshCentral remote-desktop protocol.
- No physical phone was used for these releases; the physical-phone acceptance listed in the
  [README](../README.md#validation-and-limitations) remains open.
- Phone control still requires a modified agent with input support; it is not yet in the upstream
  project.
