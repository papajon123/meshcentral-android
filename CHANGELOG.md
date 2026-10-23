# 0.7.2 — 2026-10-23

- Show specific localized request and job errors in the device inspector, with Refresh and Help actions.
- Refresh the selected Details, Services or Processes tab and clear stale status messages.
- Validate the job, device and template throughout polling; never replay a service action after an uncertain response.
- Share Companion error translations between System and Help without changing supported languages.
- Include Mobile Companion 1.1.2 source fixes for Windows PowerShell completion and UTF-8 output.

# 0.7.1 — 2026-10-22

- Keep saved computers reachable on short screens: search, filters and connection warnings scroll with the device list.
- Check each virtualized device row after scrolling, including disconnected status and unavailable connection actions.
- Includes the 0.7.0 profiles, sessions, sharing and optional companion features.

# Changelog

Notable changes to MeshCentral Mobile. This project is an independent client and does not follow the MeshCentral server's release cycle.

## 0.7.0 — prepared 2026-10-22, not published

`versionCode` 15. This is a prepared candidate. A public build, a public test run and publication to
GitHub Releases have **not** been performed. The download page remains the source for the latest
published build.

### Added

- Device search over the loaded list: Unicode-normalised, case-insensitive, every word must match
  (AND), across device name, group, host/IP and OS. No server request per keystroke.
- Up to 20 recently opened computers per profile and account, with a hashed scope key.
- Server profiles: up to 20 profiles with separate addresses, credentials, favourites and recents.
  The existing settings migrate to the first profile; credentials are scoped to their server; the PIN
  and privacy switches stay global. Switching or deleting a profile closes its sessions.
- Two live sessions: one desktop plus one tool, or two tools. Explicit close; saved device/tab; no
  automatic reconnect, input replay or command replay after transport or process death.
- Share to a computer: `ACTION_SEND` / `ACTION_SEND_MULTIPLE` for up to 20 `content://` documents, a
  visible queue, a foreground data-sync service, cancel, explicit retry from the beginning, and
  upload verification by readback SHA-256.
- System, disk, memory and network data from MeshCentral; optional service/process lists and confirmed
  service start/stop/restart; restart, shutdown and Wake-on-LAN under reported rights, with result
  polling instead of trusting the server ACK.
- Optional `mobilecompanion` 1.1.0 server plugin for fixed jobs, history and Quick Help: `diagnostics`,
  `services`, `processes` and confirmed service actions. The server retains 7 days of history, at most
  40 jobs and 64 KiB of output per job. `operationId` deduplicates; commands are never replayed; a
  running job cannot be cancelled.
- Quick Help flow (diagnostics, explanation, confirmed action, recheck, short report) with a report
  that omits device name, IP, user, hostname, node/job ids and raw output.
- Optional background notifications: WorkManager 15-minute best-effort polling, quiet hours, per-kind
  filters, dedupe/TTL, tap routing, and a read-only same-origin HTTPS token stored in Android Keystore.
  No FCM and no project push backend.

### Changed

- `versionName` 0.7.0 / `versionCode` 15. The public application id and signing identity are unchanged.
- The standard desktop, terminal, files, SSH and SFTP tools still work without any server plugin.
- Jobs, service/process views, confirmed service actions and notifications need MeshCentral 1.3.0 and
  the optional plugin. Jobs require `MESHRIGHT_REMOTECOMMAND (0x20000)`; agent-console right 24 is not
  sufficient. The app does not administer or deploy the server or plugin.
- No WebRTC. Research is recorded in the roadmap; MeshCentral 1.3.0 does not accept the terminal RTC
  offer, so the WSS relay remains.

### Validation and limits

- Development-line checks covered search, profiles, two sessions, share queue/retry/SHA-256, system
  data, jobs/ACL/tokens and notification policy. They are not a public-build acceptance.
- No public build, public test run or publication has been recorded for 0.7.0.
- Not verified on a physical phone, a real Windows Mesh Agent, systemd services, TalkBack, large fonts
  on a device, battery use or Doze/OEM background restrictions.
- No production deployment, plugin installation or public acceptance is claimed.

## 0.5.4 — 2026-10-21

Preview release; `versionCode` 13.

- Fix the PIN keypad zero button, which incorrectly deleted the previous digit. Existing PINs containing zero can now unlock the app.
- Add actual-keypad regression tests for zero input, backspace and the 12-digit limit.
- Remove private distribution references from public documentation and enforce a publication text policy.

## 0.5.3 — 2026-10-21

Preview release; `versionCode` 12.

- Correct terminal input ownership for Android IME processing-key events (`keyCode` 229), including the direct CJK input path exposed by the 0.5.2 release checks.
- Keep README download links pointed at published Releases, so an unpublished candidate never replaces the working download destination.

## 0.5.2 source candidate — 2026-10-21

Unpublished APK candidate; `versionCode` 11. The immutable source tag remains available.
The source-snapshot transfer to GitHub succeeded, but an API 35 release check exposed an IME input
race and stopped signing. No 0.5.2 APK was published to GitHub Releases.
Application runtime was unchanged from 0.5.1.

### Added

- English, Simplified Chinese and Russian guides with a shared visual header, live GitHub badges and genuine localized screenshots.
- A repeatable opt-in screenshot gallery using production screens with fictional data; setup screenshots come from the signed release APK.
- A private development pipeline and a release-snapshot bridge to GitHub. Only the selected source tree is exported; private development commits are not published.
- Snapshot checks for version/tag agreement, immutable tags, clean public ancestry and an atomic fast-forward publication. Android signing remains in the GitHub release environment.

## 0.5.1 — 2026-10-21 (`versionCode` 10)

Published on GitHub Releases. The signed APK and anonymous downloads were verified. The v0.5.0 tag was an unpublished candidate whose checks stopped signing; it remains unchanged.

### Added

- Device details screen with Details, Desktop, Terminal, Files, SSH and SFTP tabs, one tool session at a time, and an explicit web-panel handoff.
- Mesh Agent terminal over `/meshrelay.ashx?p=1` with a locally bundled xterm.js 5.5.0 / addon-fit 0.10.0 renderer, bounded output and no automatic reconnect or input replay.
- Mesh Agent files over `/meshrelay.ashx?p=5`: browse, SAF download, confirmed upload, mkdir, rename and delete.
- SSH terminal and SFTP through `/sshterminalrelay.ashx` and `/sshfilesrelay.ashx`, using the same server authentication cookie as the native relay path.
- Upload verification through readback SHA-256 and a fresh directory listing for both Agent files and SFTP; download size checks and SFTP empty/exact-block handling.
- Capability states that distinguish known denial from unknown metadata; unknown permits an explicit attempt, with the server remaining authoritative.

### Changed

- Fix native terminal key delivery on Android WebView 124 by keeping the hidden input's opacity at 1 with transparent paint; preserve its geometry and prevent touch interception.
- Wait for Android's actual text-editor readiness in IME tests; verify composition followed by exactly one native key through both input paths.
- Localization now covers 529 translated strings in each of 23 locale variants (22 languages). Translations remain machine-produced with DeepSeek and are not native-speaker verified.
- README and user documentation describe the delivered terminal/files/SSH/SFTP scope, privacy traffic paths and remaining validation gates.
- Keep first-launch setup reachable with large text and short landscape windows by scrolling the introduction and server form together.
- Wrap two-factor delivery actions, allow the sign-in button to grow with text, and keep the selected navigation label readable at large font scales.
- Render server addresses left-to-right inside RTL interfaces and respect the explicit desktop-denial permission.

### Validation and remaining gates

- Feature checks used an isolated real Linux Mesh Agent and SSH/SFTP host through npm MeshCentral 1.2.5. The protocol review used MeshCentral 1.2.6 source at commit `60cea8731f88b91a09704ff9a00dab4992a98ef1`, but no MeshCentral 1.2.6 server was tested.
- Not yet verified: physical phone, Windows Agent, live restrictive-right and consent/refusal flows, SSH host-key policy, and cancellation/partial-transfer edge cases.
- Pending mkdir/rename/delete cancellation is reported as unverified, not success. A failed or cancelled download may leave a partial SAF document. No exact test counts are claimed here; results are maintained in `docs/VALIDATION.md`.

## 0.4.0 — 2026-10-21

Preliminary public release; `versionCode` 8.

### Added

- Interface translations for 22 languages in 23 locale variants: English, Russian, Simplified Chinese, Traditional Chinese, Spanish, French, Portuguese, German, Arabic, Hindi, Bengali, Urdu, Japanese, Korean, Indonesian, Turkish, Vietnamese, Thai, Italian, Persian, Tamil, Telugu, and Marathi. Simplified and Traditional Chinese count as one language and two variants.
- 389 translated strings per variant plus the shared application name.
- Right-to-left layout for Arabic, Persian, and Urdu, including a mirrored navigation arrow.
- System per-app language selection on Android 13+ through `locales_config.xml`; Android 8–12 follows the system language.

### Changed

- English is the default interface language and the fallback when no supported locale matches.
- Runtime messages that were Russian literals are now Android string resources, so pending status text follows the selected language.
- The package name and signing key are unchanged: 0.4.0 installs over the public 0.3.0 release without a mandatory new sign-in. Earlier private builds remain separate.
- `ci/check_translations.py` and its unit tests check the translation catalogs as part of the repository checks.

### Known issues

- Translations were produced with DeepSeek and have not yet been proofread by native speakers of every language.
- Server messages, device names, and physical key captions are not translated.
- The app has no online translation or geolocation.
- This release has not yet been validated on a physical device or against third-party MeshCentral servers.

## 0.3.0 — 2026-10-21

The first public source and APK release; `versionCode` 7.

### Added

- Native Mesh Agent remote desktop over the MeshCentral relay: KVM decoding, multi-monitor switching, reconnect, quality presets, and a client-drawn pointer.
- Direct-touch and trackpad pointer modes, pinch zoom up to 16x, and bounded panning.
- Phone keyboard forwarding, shortcut grid, Unicode text input, and text clipboard exchange in both directions.
- MeshCentral `x-meshauth` sign-in with e-mail, SMS, and push two-factor challenges.
- Android Keystore AES-GCM storage for credentials and PIN, optional PIN/biometric app lock, and optional screenshot blocking.
- Project documentation for users, contributors, privacy, security, architecture, protocol, and validation.

### Changed

- The server address is entered by the user; no personal or hosted server is preconfigured.
- Documentation moved to the public GitHub repository and no longer describes internal build hosts or private test infrastructure.
- Updates are installed manually from GitHub Releases instead of from an app-managed source.

### Removed

- The in-app store, update check, APK download, and APK installation flow, together with the `REQUEST_INSTALL_PACKAGES` permission and update file provider.
- The Python release-publishing tests and the store-publishing CI path.

### Known issues

- This release has not yet been validated on a physical device or against third-party MeshCentral servers.
- The interface is Russian; no other locales are shipped yet.
- Manual updates require the same package name and signing key; a locally built APK cannot replace a release APK.

## Earlier versions

0.1.0–0.2.4 were internal development builds and were not published as a supported public release.
