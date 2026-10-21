<h1 align="center">MeshCentral Mobile</h1>

<p align="center"><strong>An independent Android client for your own MeshCentral server: control a Mesh Agent desktop, open an Agent terminal or file browser, and reach server-side SSH and SFTP from a phone or tablet.</strong></p>

<p align="center">
  <a href="https://github.com/papajon123/meshcentral-android/releases/tag/v0.5.1"><strong>v0.5.1 release page</strong></a>
  ·
  <a href="https://github.com/papajon123/meshcentral-android/releases/download/v0.5.1/meshcentral-android-0.5.1.apk">APK asset (future tag)</a>
  ·
  <a href="README.ru.md">Russian guide</a>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Android-8%2B-3DDC84" alt="Android 8 or newer">
  <img src="https://img.shields.io/badge/License-Apache_2.0-blue" alt="Apache License 2.0">
  <img src="https://img.shields.io/badge/22-languages-4B5563" alt="22 languages, 23 locale variants">
  <a href="https://github.com/papajon123/meshcentral-android/actions/workflows/android.yml"><img src="https://github.com/papajon123/meshcentral-android/actions/workflows/android.yml/badge.svg?branch=main" alt="Android checks CI status"></a>
</p>

> **Prepared release: 0.5.1 (versionCode 10).** This page documents the build being prepared for publication. At the time of writing, 0.5.1 is **not yet published** on GitHub Releases or F-Droid. The v0.5.1 links target the future tag and become valid only after the maintainer uploads the signed APK. A personal F-Droid repository is planned; official F-Droid inclusion is not promised.

> **Validation status:** feature checks used a real isolated Linux Mesh Agent and SSH/SFTP host through npm MeshCentral 1.2.5. The build has **not** been run on a physical phone or with a Windows Agent, and live restrictive-rights/consent flows were not exercised. Exact results are kept in [Validation](docs/VALIDATION.md); this page does not copy test counts.

## Quick start

1. **Get the signed APK after publication.** The release target is `0.5.1` (`versionCode` 10). Use the [future v0.5.1 release page](https://github.com/papajon123/meshcentral-android/releases/tag/v0.5.1) or the personal F-Droid repository once the maintainer announces them. Android 8.0 (API 26) or newer is required.
2. **Install it.** Android will ask you to allow installs from the browser or file manager you used. Updates require the same package name and signing key, and a higher `versionCode`.
3. **Connect your server.** Enter the full HTTPS URL of your own MeshCentral server, for example `https://meshcentral.example.com/`, then sign in. No server or account is preconfigured.

<p align="center">
  <img src="docs/screenshots/setup.png" width="260" alt="First-launch screen asking for the MeshCentral server address">
  <img src="docs/screenshots/sign-in.png" width="260" alt="Sign-in screen using the reserved example endpoint https://demo.example/">
</p>

<p align="center"><sub>Existing setup and sign-in captures. The gallery will be refreshed separately; this page does not add screenshots.</sub></p>

## What 0.5.1 provides

- **Device details and tabs.** Open a computer from the list to see its group, operating system, address and Agent version, then choose Details, Desktop, Terminal, Files, SSH or SFTP.
- **Mesh Agent desktop.** Native KVM control remains available with touch, trackpad, keyboard, clipboard text, display switching and reconnect.
- **Mesh Agent terminal.** A real command shell on the managed computer, rendered by a locally bundled xterm.js and driven from Kotlin.
- **Mesh Agent files.** Browse directories, download to a user-selected document, upload, create a directory, rename and delete.
- **SSH and SFTP through MeshCentral.** Use the server's SSH endpoints with server-side SSH authentication, without opening a direct SSH connection from Android.
- **Capability-aware tabs.** Known denial or unavailable support disables a tab; missing metadata permits an explicit attempt, and the MeshCentral server remains authoritative.
- **One tool at a time.** Selecting another terminal/file tab, or opening the desktop, closes the previous tool session. Tool relays close on actual control/server/account loss; ordinary Activity recreation keeps the ViewModel and live session. Terminal/files do not reconnect or replay input or file mutations automatically.
- **22 languages.** 529 translated strings per locale variant in 23 variants, following the Android system language.

Not included in 0.5.1: native Intel AMT KVM, RDP, VNC or WebRTC clients; device events, wake/power controls, chat, registry/software management, or server administration. Those remain web-panel or later work. The **Open web panel** action opens your MeshCentral URL in a browser with a separate sign-in.

## The device screen

The Details tab always opens. Other tabs are enabled only when the current account, device and server can plausibly support them. The status is deliberately split into:

- **known denial** — the account lacks the required right, the device is offline, or the server reports the feature as disabled;
- **unknown** — the server omitted optional metadata; the tab can be tried explicitly and the server's answer decides the result.

The app does not invent permission from a missing field. SSH/SFTP also require the relevant server feature flags; Agent terminal/files use the Agent capability bits when the server provides them.

| Tab | Transport | Notes |
|---|---|---|
| Details | control channel data | Group, OS, address, Agent version; no events or power actions. |
| Desktop | `/meshrelay.ashx?p=2` | Existing native Mesh Agent KVM path. |
| Terminal | `/meshrelay.ashx?p=1` | Mesh Agent shell, through the MeshCentral server. |
| Files | `/meshrelay.ashx?p=5` | Mesh Agent file protocol, separate from SFTP. |
| SSH | `/sshterminalrelay.ashx` | SSH is performed by MeshCentral, not by Android. |
| SFTP | `/sshfilesrelay.ashx` | Server-side SFTP through the authenticated MeshCentral session. |

## Terminal

The terminal is a **local renderer**: `@xterm/xterm` 5.5.0 and `@xterm/addon-fit` 0.10.0 are bundled in the APK under `app/src/main/assets/terminal/` (MIT; see [third-party notices](THIRD_PARTY_NOTICES.md)). Input, resize, ready and consumed signals pass through a narrow local DOM bridge; output is written into the local renderer. Kotlin owns the relay, authentication and lifecycle. The JavaScript gets no server URL, cookie, username, password, private key or filesystem path.

The local view has no network or file access and rejects remote navigation. OSC 8 hyperlinks and OSC 52 clipboard writes are disabled. Copying is an explicit toolbar action, limited to the terminal selection. A working, updated **Android System WebView** is required; if it cannot start, the app shows an actionable update/reopen message rather than a blank terminal.

The terminal sends ordinary keystrokes plus Ctrl, Esc, Tab, arrows and Enter. Output queues are bounded: reading waits for available space, an oversized individual frame is rejected explicitly, and output is not silently dropped. A tool session does **not** reconnect automatically and never replays earlier input; reopen the tab to start a new session.

MeshCentral may record a terminal session. When the Agent relay reports it, the app shows a recording notice. The audited SSH relay does not forward this marker; absence of a notice is not proof that recording is disabled.

## Files

The Agent Files tab and the SFTP tab use different protocols but present the same basic actions:

- browse directories and refresh;
- download a file into a document chosen through Android's Storage Access Framework (SAF);
- upload a file, with an overwrite warning;
- create a directory, rename or delete, with explicit confirmation.

Both Agent and SFTP uploads are verified after the server's acknowledgement: the app reads the uploaded content back through the relay, compares its SHA-256 with the source, and checks a fresh directory listing for the name and stable size. A transfer is not reported as successful on an acknowledgement alone.

Downloads are preflighted against a fresh listing for the expected size, opened with SAF truncation, and checked again after transfer. The SFTP adapter handles zero-byte and exact-block endings without waiting for an EOF that the server does not send. A failed or cancelled download can still leave a partial document at the user-selected SAF location; the app reports the partial result and does not delete or rewrite that document.

Directory responses are bounded to 1 MiB of JSON and 20,000 entries; an over-limit response is rejected rather than silently shortened. Entries whose names cannot be safely created or operated are shown read-only, with a handoff to the web panel. There is no automatic retry, transfer resume, or replay of file mutations.

Cancelling a pending mkdir, rename or delete is reported as unverified, not as success: the server may already have applied the change. Live cancellation semantics for those mutations remain a verification gap.

## SSH and SFTP through MeshCentral

SSH is terminated by the MeshCentral server and exposed through `/sshterminalrelay.ashx`; SFTP uses `/sshfilesrelay.ashx`. Android does not connect directly to the SSH host. These viewer sockets use the **same server-issued authentication cookie** as the native control/relay path, and the SSH password or key is sent only for the current authenticated session.

- SSH passwords, private keys and key passphrases are kept in memory for the prompt and outbound message only. New credentials are sent with `keep=false` (SFTP), or `keep:0` on the Agent terminal path where the flag is numeric; the app does not save them.
- A server-saved key can be unlocked by entering its passphrase through the server's `sshkeyauth` message; the app still does not store the key or passphrase.
- **No Android host-key pinning claim is made.** The app has no direct SSH stack or host-key store. Host-key policy belongs to the server side and was not live-audited for this release.
- The SSH/SFTP server may record the session. The app cannot guarantee that no server-side recording occurs.

## Security and privacy

- No ads, analytics, telemetry, crash-reporting service, project account or project-operated backend. The native client talks only to the MeshCentral server you configure: `/control.ashx`, `/meshrelay.ashx` (desktop, Agent terminal, Agent files), `/sshterminalrelay.ashx` and `/sshfilesrelay.ashx`.
- The terminal renderer is a **local** WebView serving bundled assets from the APK, with network/file access blocked. It is separate from the raw **Debug WebView** in Settings, which loads the configured MeshCentral page and keeps its own WebView cookies.
- MeshCentral credentials and the app PIN are encrypted with an Android Keystore AES-GCM key. Android backup and device transfer are disabled. Session cookies stay in process memory; clear credentials and sessions in **Settings → Data**.
- Remote screen frames, terminal output, clipboard text and transferred file content pass through the MeshCentral server and the controlled computer. The app does not record screens or write terminal output to its own storage; copied terminal selection goes to the Android clipboard only on an explicit action.
- App lock can use a PIN, Android biometrics (Android 9+), or both. Screenshot blocking uses `FLAG_SECURE`, is off by default, and does not stop a camera pointed at the screen.
- The server operator can see connection metadata, device and session activity, and traffic through the server. Server logging, terminal recording and access control are outside the app.

See [Privacy](PRIVACY.md) and [Security policy](SECURITY.md).

## Compatibility and validation

- The live bench used npm **MeshCentral 1.2.5** with a real isolated Linux Mesh Agent, an SSH terminal and an SFTP host.
- Protocol review also inspected MeshCentral package **1.2.6**, commit [`60cea8731f88b91a09704ff9a00dab4992a98ef1`](https://github.com/Ylianst/MeshCentral/tree/60cea8731f88b91a09704ff9a00dab4992a98ef1). npm 1.2.6 was unavailable, so **no MeshCentral 1.2.6 server was tested** and compatibility with it is not claimed.
- Still unverified: a physical phone, a Windows Mesh Agent, live restrictive-right accounts, live consent/refusal, SSH host-key policy, and some cancellation/partial-transfer paths.
- The release does not claim full parity with the MeshCentral web panel. AMT, RDP, VNC, WebRTC, events, power, chat and administration remain outside the native client.
- Exact check results and the remaining release gates are recorded in [Validation](docs/VALIDATION.md) and [Known limitations](docs/KNOWN_LIMITATIONS.md). The validation document also contains historical 0.4.0 results; this page does not restate them as 0.5.1 evidence.

## Distribution, package and updates

- **GitHub Releases and a personal F-Droid repository are planned for 0.5.1; neither 0.5.1 channel is live at documentation time.** Official F-Droid inclusion is not promised. The maintainer will finish the public distribution text after a successful upload.
- The application ID remains `io.github.papajon123.meshcentral`. Debug builds add a `.debug` suffix. The package name and signing key preserve the public identity; earlier private builds use a different package and remain separate applications.
- Android accepts an update only with the same package name and signing key and a higher `versionCode`. There is no in-app updater or store in this build.
- The repository uses GitHub Actions for checks. No GitLab CI/workflow has been implemented, and none is claimed.

The full SHA-256 fingerprint of the public signing certificate is:

<code>5458084705359e0d<wbr>fa9cbb83ed73a0d77<wbr>c7758370cffe2a7d<wbr>6b036e9275eeb2f</code>

## Languages and translation status

The 0.5.1 interface is available in 22 languages in 23 Android locale variants, with English as the default:

English, русский, 简体中文, 繁體中文, español, français, português, Deutsch, العربية, हिन्दी, বাংলা, اردو, 日本語, 한국어, Bahasa Indonesia, Türkçe, Tiếng Việt, ไทย, italiano, فارسی, தமிழ், తెలుగు, मराठी.

Each variant carries the same **529 translated strings**; the application name is shared. Simplified and Traditional Chinese count as one language and two variants.

- Android 8–12 follows the system language automatically. On Android 13 and newer, the app also appears in the system per-app language settings; there is no separate in-app language picker.
- If no supported language matches, the app falls back to English. Arabic, Persian and Urdu use right-to-left layout.
- Server messages, device names, terminal output and physical key captions are not translated.
- Translations were produced with DeepSeek and have **not** been proofread by native speakers of every language.

## Build from source

Requirements: JDK 17, Android SDK platform 35, build-tools 35.0.0. The Gradle wrapper downloads Gradle 8.10.2.

Windows PowerShell (`JAVA_HOME` and `ANDROID_HOME` must point at your installations; replace the JDK placeholder with your own JDK 17 path):

```powershell
$env:JAVA_HOME = "C:\path\to\jdk-17"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat assembleDebug
```

Linux or macOS:

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME="$HOME/Android/Sdk"
./gradlew assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Tests and translation checks

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
python3 ci/check_translations.py
python3 -m unittest discover -s ci -p 'test_*.py'
```

On Windows use `.\gradlew.bat` for the Gradle tasks. Instrumented tests need a dedicated emulator or test device with no personal data; use reserved example domains and synthetic device data:

```bash
./gradlew connectedDebugAndroidTest
```

`ci/check_translations.py` checks 23 locale variants against the base catalog, verifies format placeholders, rejects Russian text leaking into other locales, and confirms the current **529 translated strings** per variant. JVM, device and bench results and the release gates are maintained in [Validation](docs/VALIDATION.md); this README intentionally does not quote a test count.

## Documentation

- [Architecture](docs/ARCHITECTURE.md)
- [Control and desktop protocol notes](docs/PROTOCOL.md)
- [Interface](docs/UI.md)
- [Validation](docs/VALIDATION.md)
- [Known limitations](docs/KNOWN_LIMITATIONS.md)
- [Roadmap](docs/ROADMAP.md)
- [Privacy](PRIVACY.md)
- [Security policy](SECURITY.md)
- [Contributing](CONTRIBUTING.md)
- [Changelog](CHANGELOG.md)

## Reporting bugs

Open a [GitHub issue](https://github.com/papajon123/meshcentral-android/issues) with the app version, Android version, MeshCentral version, steps to reproduce, and sanitized logs. Do **not** post credentials, session cookies, private keys, real server names, terminal output containing secrets, or screenshots of a working desktop. Security problems go through the private channel in [SECURITY.md](SECURITY.md).

## License

Apache License 2.0. See [LICENSE](LICENSE), [NOTICE](NOTICE), and [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
