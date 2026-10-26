<h1 align="center"><img src="docs/assets/header.png" alt="MeshPilot" width="100%"></h1>

<p align="center"><a href="README.md"><strong>English</strong></a> · <a href="README.zh-CN.md">简体中文</a> · <a href="README.ru.md">Русский</a></p>

<p align="center"><strong>An independent Android client for your own MeshCentral server: computers and phones, desktop, terminal, files, SSH and SFTP from a phone or tablet.</strong></p>

> [!NOTE]
> **MeshPilot is an independent, third-party app** (formerly “MeshCentral Mobile”). It is not affiliated with, endorsed by or supported by the MeshCentral project or its developers. Please report problems with this app in [this repository's issues](https://github.com/papajon123/meshcentral-android/issues), not to the MeshCentral team.

<p align="center">
  <a href="https://github.com/papajon123/meshcentral-android/releases"><strong>Download APK</strong></a>
</p>

<p align="center">
  <a href="https://github.com/papajon123/meshcentral-android/releases"><img src="https://img.shields.io/github/v/release/papajon123/meshcentral-android?include_prereleases&label=release" alt="Latest release, including pre-releases"></a>
  <img src="https://img.shields.io/badge/License-Apache_2.0-blue" alt="Apache License 2.0">
  <img src="https://img.shields.io/badge/Android-8%2B%20%28API%2026%29-3DDC84" alt="Android 8 or newer, API 26 minimum">
  <img src="https://img.shields.io/badge/23-locales-4B5563" alt="23 Android locale variants">
</p>

> **Version 0.8.4** renames the app to MeshPilot and adds sign-in with login tokens and login keys. The 0.8 line adds stylus and S Pen input, phone viewing and files, phone control with an input-capable agent, server-key pinning, remembered two-factor sign-in and expanded computer tools. See the [changelog](CHANGELOG.md) and [validation](docs/RELEASE_0_8_0_VALIDATION.md).

This is a client: you manage computers and phones *from* your phone. The managed phone needs the official [MeshCentral Android agent](https://github.com/Ylianst/MeshCentralAndroidAgent), which shares its screen after Android consent and remains view-only. Remote touch, navigation and text entry require a modified agent with input support, enabled accessibility and sufficient rights. That agent support is not yet in the upstream project. See [how the options compare](docs/COMPARISON.md) and [how this app is built](AI_POLICY.md).

## Tour

<p align="center"><img src="docs/assets/demo.gif" width="300" alt="Animated tour with demo data: computer list, tool selection, terminal, files, system information and diagnostics"></p>

<p align="center"><sub>Demo screens rendered by the app with fictional data.</sub></p>

## Why not just the web panel?

The MeshCentral web panel opens in a mobile browser, but it is designed for a desktop screen. This app is designed for touch:

- **Remote desktop for fingers:** touch and trackpad modes, keyboard, clipboard text and monitor switching; the terminal has a Ctrl, Esc, Tab and arrow key row.
- **Share to a computer:** send files from a gallery, file manager or mail app straight to a folder on a computer, verified by SHA-256.
- **All your servers in one place:** up to 20 profiles, favourites, search and recent computers; optional app lock with a PIN or biometrics.
- **Private by design:** credentials stay in Android Keystore-backed storage; no ads, analytics or project-operated backend.

## Help wanted

This is a small independent project in public preview. The most useful help right now:

- **Try it on your phone** and leave a [device report](https://github.com/papajon123/meshcentral-android/issues/new?template=device_report.yml): what works and what does not. Real phones and Windows agents are especially welcome.
- **Ask questions and share ideas** in [Discussions](https://github.com/papajon123/meshcentral-android/discussions).
- **Report bugs** with the [bug report form](https://github.com/papajon123/meshcentral-android/issues/new?template=bug_report.yml). Never post server addresses, credentials or private device data.

## Quick start

1. **Install the app.** Download the [signed APK](https://github.com/papajon123/meshcentral-android/releases). Android 8.0 (API 26) or newer is required. With your consent, the app checks GitHub for updates once a day; downloads open in your browser.
2. **Keep the same signer.** Android accepts an update only with the same package name, the same signing key and a higher `versionCode`.
3. **Connect your own server.** Enter the full HTTPS URL of your MeshCentral server, for example `https://meshcentral.example.com/`, then sign in with a MeshCentral account. No server or account is preconfigured. Browser SSO is not supported. For the terminal, install a current Android System WebView.

## What you can do

### Devices, search and profiles

- Browse groups and computers, filter by online status or favourites, and see OS, address and Agent version.
- Phones with the official Android agent: live battery, cached system/network information, screen viewing, files and vibration, flashlight, message, link and dial-pad requests after confirmation. Submission is not proof of execution; console actions also need Console permission. Input requires the modified agent described above.
- Search the loaded list by device name, group, host/IP or OS. The query is Unicode-normalised and case-insensitive; every word must match (AND). Typing does not send a request.
- Keep up to 20 recently opened computers per profile and account. The scope key is hashed; device names and addresses are not stored in the recent list.
- Keep several server profiles (up to 20) with separate URLs, credentials, favourites and recent devices. The first profile is migrated from the existing settings. Credentials and favourites are profile-scoped; the app PIN and privacy switches stay global. Switching or deleting a profile closes its sessions.

### Sessions and the remote tools

- Keep **two sessions** open: one desktop plus one tool, or two tools. A third session is not admitted until one is closed explicitly.
- The selected device and tab survive Activity recreation; after process death the app restores the choice, not a fake live connection. Input, commands and file changes are not replayed.
- Computer desktop: touch and trackpad, two-finger right click, clipboard text, monitor switching and reconnect. Keep fixed shortcuts and up to 12 named keyboard combinations per profile. Phone input has its own capability and permission checks.
- Terminal: run commands in the Agent shell; keep up to 20 single-line favorites per profile, with insertion separate from Enter. A separate Run command screen offers preview, confirmation and bounded output; commands run as the agent account and their output can be visible to other users with device access. SSH and SFTP use the server-side relays.
- Windows Run command offers an explicit **PowerShell 5+ (UTF-8)** choice; the default shell/body stay unchanged. This avoids common code-page problems without guessing received text, but program-specific encoding and agent-side conversion can still lose data. Favorite-command dialogs keep the terminal grid stable. See [Windows fixes and limits](docs/WINDOWS_POLISH.md).
- Computer files: browse, download, upload, create folders, rename and delete. Phone files: categories and accessible storage, upload/download with size/SHA-256 checks, and deletion; no create/rename. Duplicate media names have restricted actions; select a category before uploading. An isolated one-byte zero download frame fails verification rather than silently changing the file.
- Standard desktop, terminal, files, SSH and SFTP **do not require a server plugin**. Tabs follow the capabilities reported by the server; a known denial disables a tab, while missing optional data allows an explicit attempt. The server decides.

### Share to a computer

- Accept `ACTION_SEND` and `ACTION_SEND_MULTIPLE` from a file manager, gallery or mail app, up to **20** `content://` documents.
- Choose the profile, computer and folder, then upload through a visible queue. Each row has its own state, progress, cancel and explicit retry.
- Uploads are checked by readback SHA-256. A failed or cancelled transfer is never shown as success. Retry starts **from the beginning**; there is no hidden resume or offset claim.
- The source URI stays in memory only. After process death, share the file again. A foreground data-sync service keeps an active queue alive while Android permits it.

### System, services and power

- Read cached system, BIOS, motherboard, TPM, disks, memory and network details. Online agents can supply software inventory and CPU/RAM samples without a plugin; Linux CPU is an average since the previous sample. Last boot and estimated uptime can be stale. Missing data stays unknown, including JSON null. Recognized Windows BIOS CIM_DATETIME values display as a localized date without shifting the release day.
- See reported Windows Security Center/antivirus status and a Needs attention filter, up to 50 device events, the latest 100 power transitions and a seven-day connectivity chart with unknown gaps.
- With the optional plugin, list services and processes. Confirmed service start/stop/restart is available only where the Agent, OS and user rights support it; the result is checked again after the action.
- Wake, sleep, restart and shutdown follow current rights and connection state. Agent uninstall and device removal require two confirmations and exact device-name entry; Android agents are excluded. A server ACK, disconnection or disappearance is not proof of execution. Destructive-action live acceptance remains pending.
- Preview and confirm a bounded message to a computer user with Chat/Notify permission; the app reports submission, not delivery.

### Jobs, history and Quick Help

- Run only fixed, allowlisted templates: `diagnostics`, `services`, `processes`, `service-start`, `service-stop` and `service-restart`. There is no arbitrary shell or user-supplied command.
- Jobs keep a status, result, exit code and bounded output. The server retains 7 days of history, at most 40 jobs and 64 KiB of output per job. `operationId` prevents a silent repeat; running jobs cannot be cancelled, and the app does not claim they were killed.
- Quick Help follows: diagnostics → explanation → confirmed action → recheck → short report. The shareable report omits the device name, IP address, user, hostname, node/job ids and raw output. Unknown findings stay unknown.

### Optional notifications

- Optional background notifications use WorkManager with a **15-minute** best-effort interval, quiet hours and per-kind filters. There is no FCM, no project push service and no permanent socket.
- After an explicit opt-in, the app stores a read-only bearer token in Android Keystore-backed storage (30-day TTL) and polls a same-origin HTTPS endpoint using the profile TLS policy, including an explicitly approved private-server key. A trust refusal stops polling before the token is sent. Cookies, redirects, query tokens and unapproved certificates are rejected.
- Duplicate and expired events are filtered; tapping a notification routes to the right profile, device or job after unlock. A failed online revoke is reported as a limitation, not as success.

## Optional server plugin

Jobs, service/process views, confirmed service actions and background notifications use the optional **`mobilecompanion` 1.1.2** plugin. Standard desktop, terminal, files, SSH and SFTP work without it.

- Requires MeshCentral **1.3.0** and the user right `MESHRIGHT_REMOTECOMMAND (0x20000)`. Agent-console right **24 is not sufficient**.
- The plugin exposes fixed templates, ACL-checked jobs, read-only HTTP events and notification tokens. It does not patch MeshCentral core and does not accept arbitrary code or shell strings.
- The app does **not** install, configure, deploy or administer MeshCentral or the plugin. The [plugin package and its README](server/mobile-companion/README.md) are separate from the APK.
- Live acceptance used an isolated MeshCentral 1.3.0 with a Linux Agent; Windows service/process reads were also verified with Companion 1.1.2. Windows and systemd service mutations remain untested. Multi-server MeshCentral is not supported by this plugin version.

## Validation and limitations

- Candidate checks and their exact scope are recorded in [0.8.0 validation](docs/RELEASE_0_8_0_VALIDATION.md); published 0.8.1 and 0.8.2 checks, hashes and remaining acceptance are in [release validation](docs/RELEASE_0_8_2_VALIDATION.md). Historical API 26/35 and international/RTL/large-font/tablet results remain in [implementation and limits](docs/ROADMAP_IMPLEMENTATION.md); they are not a fresh acceptance of every 0.8.0 flow.

- Live evidence covers Linux CPU/RAM, history and reviewed command output ([parity checks](docs/PARITY_FIXES_VALIDATION.md)), and phone downloads/verified uploads with official agent 1.0.23 ([phone file checks](docs/PHONE_FILES_FIXES_VALIDATION.md)). Phone input requires the modified agent; hardware effects, broad Windows/macOS acceptance, physical phones, systemd service actions, TalkBack, battery use, Doze/OEM restrictions and every 0.8.0 flow on Android 8 remain unverified.
- A physical-phone acceptance must still cover install/update over a signed build, sign-in/2FA, two sessions, rotation/background/lock/network loss/process death, share from real apps, notifications, and the optional plugin.
- No WebRTC in this release. WebRTC was researched, but MeshCentral 1.3.0 does not accept the terminal RTC offer; the WSS relay remains. See the [roadmap](docs/ROADMAP.md).
- Intel AMT KVM, RDP, VNC, chat, registry/software management and server administration remain in the web panel. The app does not administer the server.

## How this app is built

Most code, tests and docs are written by AI coding agents (Claude Code and OpenAI Codex) under the maintainer's direction. The maintainer personally reviews every change: reads the code and the architecture in the merge request and checks the behaviour before it ships. The [AI policy](AI_POLICY.md) describes the automated checks and the rules for AI-assisted contributions.

## Languages

- **22 languages in 23 Android locale variants** (Simplified and Traditional Chinese are two variants of one language). The [translation check](ci/check_translations.py) verifies matching resources in every variant; current counts are in the [release validation](docs/RELEASE_0_8_0_VALIDATION.md).
- Android 8–12 follows the system language. Android 13+ also exposes per-app language settings; there is no separate language picker inside the app.
- If no supported language matches, the app falls back to English. Arabic, Persian and Urdu use a right-to-left layout.
- Translations are machine-produced (DeepSeek) and have not been proofread by native speakers of every language. Server messages, device/file names, terminal output and physical key captions are not translated.

## Install and update

Download the signed APK from [GitHub Releases](https://github.com/papajon123/meshcentral-android/releases) and install it manually. Updates require the same package name, the same signing key and a higher `versionCode`. Earlier private builds use a different package name; they are separate applications and do not share data or signing keys.

To be notified about new versions, add `https://github.com/papajon123/meshcentral-android` to [Obtainium](https://github.com/ImranR98/Obtainium) and turn on **Include prereleases**: all current builds are public previews.

## Privacy and security

- No ads, analytics, telemetry, crash-reporting SDK or project-operated backend. The app connects to the MeshCentral server you configure.
- MeshCentral credentials and the app PIN are protected with an Android Keystore key. Android backup and device transfer are disabled. Session/relay cookies stay in process memory. Optional **Remember this device** stores the server-issued 2FA cookie separately with Keystore encryption, tied to the profile, endpoint, account and issuing TLS trust; expiry and Forget are shown in server settings.
- Self-signed/private-CA servers require explicit public-key approval after checking the SHA-256 fingerprint through an independent channel. Pins apply to control, tools and optional notification polling. A changed/rejected key stops requests; trust changes clear remembered-device credentials. Lost-phone recovery and server-side revocation limits are explained in [Security](SECURITY.md).
- App lock is optional (PIN, Android 9+ biometrics, or both). Screenshot blocking is off by default and does not stop a camera.
- Profile credentials are scoped to their server. The notification token is read-only, Keystore-backed and revocable; Quick Help reports are redacted before sharing.
- The server operator can see connection metadata, device and session activity, and traffic through the server. Server logging, terminal recording and access control are outside the app.

Read [Privacy](PRIVACY.md) and the [Security policy](SECURITY.md).

## Documentation

- [Roadmap and release status](docs/ROADMAP.md) · [Changelog](CHANGELOG.md)
- [Architecture](docs/ARCHITECTURE.md) · [Control and desktop protocol](docs/PROTOCOL.md)
- [Interface](docs/UI.md) · [Validation](docs/VALIDATION.md) · [Known limitations](docs/KNOWN_LIMITATIONS.md)
- [Optional server plugin](server/mobile-companion/README.md)
- [Privacy](PRIVACY.md) · [Security policy](SECURITY.md) · [Contributing](CONTRIBUTING.md)

<details>
<summary>Build from source</summary>

Requirements: JDK 17, Android SDK platform 35, build-tools 35.0.0. The Gradle wrapper downloads Gradle 8.10.2.

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME="$HOME/Android/Sdk"
./gradlew testDebugUnitTest lintDebug assembleDebug
python3 ci/check_translations.py
```

On Windows use `.\gradlew.bat` and set the same environment variables. The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. A locally built APK is not a public release; the maintainer signs and publishes release builds.

</details>

<details>
<summary>Release identity and signature</summary>

- Application ID: `io.github.papajon123.meshcentral`
- Debug builds add a `.debug` suffix.
- Full SHA-256 of the public signing certificate:

<code>5458084705359e0d<wbr>fa9cbb83ed73a0d77<wbr>c7758370cffe2a7d<wbr>6b036e9275eeb2f</code>

The same values are kept in [ci/release-identity.json](ci/release-identity.json).

</details>

## License

Apache License 2.0. See [LICENSE](LICENSE), [NOTICE](NOTICE) and [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
