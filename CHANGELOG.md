# 0.8.2 — 2026-10-26

## Stylus / S Pen

- Use a pen as an absolute mouse on computer desktops in every pointer mode, with hover, left-button drag, side-button right/middle clicks and an eraser mapped to the right button.
- Reject palm input while the pen is nearby or touching; keep two-finger local zoom and pan.
- Use the pen for precise tap, hold and swipe on phones that explicitly enable remote input; do not send hover to phones.
- Add an enabled-by-default “Stylus as a precise mouse” setting, a first-use hint and translations in all 23 locales. Pressure and tilt are not transmitted. See [M8 details](docs/STYLUS.md).

# 0.8.1 — 2026-10-26

- First public build of the 0.8 line. It contains the same app as 0.8.0, which did not reach GitHub Releases because two device tests failed on the release emulators (a keyboard covering a button on a small screen, and a slow WebSocket teardown). The tests were stabilized; app code is unchanged.

# 0.8.0 — 2026-10-26

## Connection reliability

- Wait for network availability after a lost connection, with bounded exponential backoff up to 30 seconds for DNS and connection failures. Keep the five automatic retries for server refusals; network and foreground callbacks do not renew that budget.
- Restore desktop relays after fresh control-channel credentials arrive, without spending server retries while offline. Keep authentication, two-factor and certificate rejection protections.
- Clear saved sign-in and WebView data in one operation that survives rotation, blocks competing sign-in, waits for cookie removal and moves cookie persistence/cache deletion off the UI thread. Enable disk and network StrictMode diagnostics in debug builds.

## Phones

- Recognize official Android agents as phones, with live battery state and cached system/network information.
- Show the official Android agent's desktop after screen-capture consent by ignoring its standalone keepalive outside KVM commands.
- Keep the official Android agent view-only. Touch, navigation and text input require a modified agent with input support, an explicitly enabled accessibility service and unrestricted control rights. That agent support is not yet in the upstream project.
- Add phone touch, hold and swipe gestures, labelled Android navigation buttons, and UTF-16 text entry with password-field guidance in all 23 locales. Two-finger gestures only zoom/pan the local picture; computer gestures remain unchanged.
- Add confirmed vibration, flashlight, message, link and dial-pad actions for online phones with Remote Control permission; messages, links and dial-pad commands also require Console permission. Report commands as sent, never as confirmed execution.
- Send phone links through the audited console command, require both Remote Control and Console permissions for console actions, and preserve `#` in dial strings.
- Adapt Android file listings/uploads/deletion to binary p5 messages and downloads to p10, retaining size/SHA-256 verification; hide unsupported create/rename operations.
- Load the Android agent's file categories and open their relative paths using the same binary UTF-8 commands as the web viewer.
- Fix phone downloads and verified uploads with both released Android agent 1.0.23 (headerless p10) and main; ignore keepalives and relay metadata without treating metadata as file contents, and handle control-socket loss without crashing. An isolated one-byte zero data frame is ambiguous and fails size verification; it is not silently repaired.
- Wait briefly for MediaStore to publish the final uploaded size before SHA-256 readback, without repeating the upload or accepting stale metadata.
- Keep duplicate media names visible with safe action restrictions, recover from oversized media lists, and disable queued uploads at the virtual phone root with a category hint.
- Add phone strings in all 23 locales. Straight quotes in messages are visibly converted to typographic quotes because the agent has no escaping syntax.

## Computers

- Display recognized BIOS CIM_DATETIME values as a date in the user's locale, preserving unknown formats.
- Show last reported Windows Security Center and antivirus product status in the device inspector, with a Needs attention filter. Missing or unrecognised data remains unknown.
- Correct Windows security health values, antivirus aggregation and Windows-only visibility.
- Send a bounded message to an online computer user after preview and confirmation, requiring Chat/Notify permission. Report submission without claiming delivery.
- Save up to 12 named keyboard shortcuts per server profile, with five suggestions and the existing fixed shortcuts retained. Release all attempted keys after a partial input failure.
- Read up to 50 device events and the latest 100 power transitions in the inspector. Show empty results separately from failed requests.
- Add a seven-day power/connectivity chart with unknown gaps, retaining up to 10,000 transitions for the chart and 100 for the recent list. Retain sleep requests and device events.
- Show unavailable offline power history as no data, omit unchanged-state transitions, and align seven-day rows to local midnight including today and DST.
- Enable confirmed Sleep requests for online computer agents with Reset/Off permission, and observe subsequent connectivity without claiming the power action completed.
- Add agent uninstall and device removal with current permission checks, two confirmations and exact device-name entry. Re-read the device list and distinguish absence/disconnection from proven execution; exclude Android agents.
- Show cached BIOS, motherboard, TPM and last-boot details; estimate uptime only for connected agents. Read installed software and CPU/memory samples through audited native MeshCentral 1.3.1 commands, without Mobile Companion.
- Restore offline Wake and agent-disconnection observation from real `nodes` snapshots; apply server device-group and per-user restrictions before offering actions.
- Preserve late notification denials and actual command/Wake/CPU refusal reasons; allow software refresh after timeout.
- Separate message preview from one-shot Send and label Linux CPU as an average since the previous sample. Add all new text in 23 locales.

## Sign-in and security

- Support explicitly approved self-signed server keys in all builds, with a separate SHA-256 SPKI fingerprint per profile.
- Enforce saved keys even for platform-trusted chains. Reject expired/not-yet-valid certificates, explain server-chain errors, and block all recovery retries after a trust refusal.
- Bind trust to the profile and normalized endpoint. Require confirmation and fresh app unlock when enabled for replacement or removal; cancel active requests and close control, desktop and tool sockets on trust changes.
- Show both SHA-256 SPKI and certificate fingerprints, independent-channel verification instructions and an OpenSSL command in all 23 locale variants. Background notifications use the same profile pin policy, including explicitly approved private-server keys.
- Show previously trusted and new public-key fingerprints when confirming a server key change, and match the OpenSSL fingerprint format to the app.
- Apply profile TLS pins to background notifications and stop polling on certificate rejection before sending the bearer token.
- Restrict Debug WebView to debug builds. Delegate unpinned public-chain path building to Android, including redundant, reordered and expired alternate intermediates.
- Reject weak private-server keys, MD5/SHA-1 leaf signatures and incompatible server EKU. Clarify issuer claims and warn about approvable hostname mismatches in all 23 locales; align the security documentation with explicit private-CA approval.
- Remember this device after a two-factor sign-in using MeshCentral's native `twofactor` cookie, stored with Android Keystore encryption and bound to the profile, server and username.
- Show the server's remember-device duration on the code step and the expiry/forget action in server settings, in all 23 locales. Forget on logout, profile/server/account changes or authentication rejection; fall back to the code step without reconnect loops.
- Bind remembered-device cookies to their issuing TLS trust mode and pinned server key; discard them on explicit trust changes or certificate rejection while preserving a matching saved pin across app restarts.
- Document MeshCentral 1.3.1 remembered-cookie revocation limits and lost-phone recovery; use correct day plurals in all 23 locales.

## Terminal and files

- Add an explicit Windows PowerShell 5+ UTF-8 command mode; keep the default shell and command unchanged, and explain agent-side encoding loss in all 23 locales.
- Keep the terminal grid stable while editing favorite commands and dismissing the dialog keyboard; insert literal text once without Enter.
- Save up to 20 single-line terminal favorites per profile; selection edits a local draft and insertion never adds Enter. Add a separately confirmed Run command screen with bounded output, agent-account warning and uncertain-result handling.
- Bound large command output before JSON decoding, display truncation, and keep output out of connection status. Warn that all users with device access can see command output.
- Keep file sessions alive while Android's save-document picker covers the app, so Linux downloads reach the chosen document; release the pending operation on picker cancellation or launch failure.
- Delete newly created SAF download documents after transfer failure, cancellation or session closure; if deletion is refused, try marking the document `.partial` and report the actual cleanup result. Preserve successfully verified downloads and identify phone transport failures separately from invalid file data.
- Replay large Android p10 streams and interrupted 19-block streams in socket tests. Keep TLS integrity checks strict; document the emulator network-path failure separately from the application protocol.
- Serialize document-picker state and release foreground-transfer reservations on every outcome. Trim KVM packet padding without consuming the next command.

## Quality

- Treat JSON null as missing throughout text parsing, including device addresses, cookies, saved profiles and file paths.
- Prevent a crash when changing servers or closing the client by moving TLS pool cleanup off the main thread.
- Keep form dialog actions above the keyboard on phones while the form content scrolls.
- Add phone/file error and permission hints in all 23 locales and socket, controller, command and UI regression tests.
- Add 36 strings in all 23 locales and regression coverage for command permissions, response correlation, parsers, favorite storage, confirmations and existing two-finger right click. Linux CPU/RAM, history and confirmed command output were checked live; destructive power/removal actions remain unverified on real devices.
- Add the new strings in all 23 locale variants. Live Linux checks are recorded in [parity validation](docs/PARITY_FIXES_VALIDATION.md); Windows and destructive-action acceptance must be assessed separately.
- Add opt-in update checks against public GitHub Releases, including prereleases: automatically once a day, and from Settings at any time with a one-minute cooldown. The consent question is asked once.
- Validate release metadata, APK name/size, SHA-256 checksums and the public signing identity before offering a newer compatible version.
- Show updates in Settings and the computer list, with browser download, postpone and skip actions; no APK download or installation inside the app.
- Add consent, update preferences and results in all 23 supported locales.

# 0.7.3 — 2026-10-25

- Fix sign-in on servers that require a second factor ([#10](https://github.com/papajon123/meshcentral-android/issues/10)): the verification-code step stays on screen instead of reconnecting endlessly with "Connection to the server was lost" and a disabled button.
- Show a rejected password or code request once; never retry rejected credentials automatically.
- Count an automatic reconnect as successful only after the server accepts the account, so a server that keeps closing the connection stops after five attempts.

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
