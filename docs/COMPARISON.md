# MeshCentral on Android: comparison

As of **2026-10-26**. Our column describes the published **0.8.2 prerelease (code 21)**.
Competitor columns describe the linked public sources checked on that date, not hands-on tests.
**Not stated** means not found in those descriptions; it does not mean absent.
**Partial** identifies a concrete capability or acceptance limit, not a claim of complete parity.
A client manages other devices from a phone; an agent makes that phone a managed device.

| Capability | MeshPilot 0.8.2 | PocketMesh [P] | MeshControl Pro [M] | Official Android agent [A] |
|---|---|---|---|---|
| Role, source and price | Independent client; Apache 2.0; free, no ads/analytics [1] | Client; closed source; currently free | Client; proprietary distribution; 15-day trial, then advertised €4.89/month or €48.99/year | Agent, not a control client; Apache 2.0 |
| Distribution and updates | GitHub APKs; opt-in daily/manual checks, browser download, no automatic installation [2] | GitHub APK; checks every 12 hours | Google Play | Google Play or GitHub APK/AAB |
| 2FA / remember device | TOTP and server-offered code requests; optional server-issued cookie encrypted with Keystore and scoped to account/endpoint/TLS trust. E-mail/SMS/push delivery not accepted [3] | 2FA; login token; optional saved TOTP secret | TOTP | Approves/rejects push 2FA |
| Self-signed/private-CA server | Explicit SHA-256 public-key approval and per-profile pinning in all builds; rejection stops requests [3] | Not stated | Self-signed support advertised | Agent pairing/trust model; not comparable to a client's sign-in |
| Profiles, search and app lock | Up to 20 server profiles; favorites, recent devices, search; PIN/biometrics [4] | Multiple servers; biometric lock | Multiple servers, groups, search; fingerprint | Not a multi-server control client |
| Computer desktop | Touch/trackpad, two-finger right click, clipboard text, monitors, fixed shortcuts and up to 12 saved combinations/profile [5] | Trackpad, gestures, key combinations, text | Gestures, special keys, saved combinations, rotation | Not a computer-control client |
| Stylus / S Pen | Pen as an absolute mouse in every pointer mode: hover moves the cursor without clicking, side-button right/middle clicks, eraser as the right button, palm rejection; precise tap/hold/swipe on phones with remote input. **Partial:** no pressure or tilt (not carried by the MeshCentral protocol); emulator-tested, physical S Pen acceptance pending [12] | Not stated | Not stated | Not a control client |
| Phone screen and battery | Screen viewing after Android capture consent; battery and cached system/network details. Emulator/file evidence; broad physical-phone acceptance pending [6] | Phone-specific support not stated | Phone-specific support not stated | Shares screen after consent; reports battery/system/network |
| Phone touch/navigation/text | **Requires modified input-capable agent**, enabled accessibility and sufficient rights; support is not yet upstream [6] | Not stated | Not stated | **View-only**; no touch, swipe or typing |
| Phone actions | **Partial:** confirmed vibration/flashlight/message/link/dial requests with rights checks; sent does not mean executed, hardware effects remain unverified [6] | Not stated | Not stated | Notifications and limited console commands |
| Phone files | **Partial:** categories/storage, verified upload/download and delete; no create/rename, ambiguous names restricted, isolated zero-byte-frame limitation [6] | Phone-specific support not stated | Phone-specific support not stated | Media/files accessible under Android permissions |
| Terminal and SSH | Agent shell and SSH; up to 20 favorites/profile, inserted without Enter; separately reviewed commands with bounded output; explicit Windows PowerShell 5+ UTF-8 mode [7] | Agent terminal; SSH not yet | Shell and favorites; website advertises SSH, not independently verified | Limited console commands, not a general terminal client |
| Computer files / SFTP | Browse/upload/download/create/rename/delete; SFTP relay; Linux live evidence [8] | File operations; SFTP not stated | File operations; SFTP not stated | Not a computer-file client |
| Share / simultaneous sessions | Up to 20 shared documents with SHA-256 readback; two sessions (one desktop maximum), no input/command replay [8] | Not stated | Not stated | Not applicable |
| Power, uninstall and removal | **Partial:** rights/confirmation-gated Wake/sleep/restart/shutdown/uninstall/remove; destructive execution not live-accepted, server ACK/disappearance is not proof [9] | Power actions, uninstall, removal | Not stated | Not a computer-management client |
| User messages / commands | **Partial:** preview/confirmation, permission checks, bounded output; delivery/execution and exit status are not inferred [7][9] | Notifications and shell commands | Website advertises interactive chat | Receives notifications/console commands |
| Hardware, software and metrics | Cached BIOS/board/TPM, online software, CPU/RAM, last boot/estimated uptime without plugin. **Partial:** agent-dependent/stale values, limited live platform coverage; localized BIOS dates, JSON null stays unknown [9] | Hardware/software/network details | Hardware and CPU/RAM/disk/uptime dashboards | Reports its own device/network/storage/battery |
| Windows security | Reported Security Center/antivirus and Needs attention filter. **Partial:** unknown stays unknown; not an independent security scan [9] | Not stated | Antivirus, firewall and update dashboards | Not applicable |
| Events / power history | Up to 50 events, 100 recent transitions, seven-day chart; missing periods stay unknown [9] | Events and seven-day connection chart | Not stated | Not a history-viewing client |
| Services, jobs, notifications | **Partial:** optional Companion plugin; background polling best effort (15 minutes), no running-job cancellation; service mutations need separate acceptance [10] | Not stated | Chat advertised; background scheduling not stated | Server notifications |
| Languages | 22 languages / 23 locale variants; machine translations, not all reviewed by native speakers [11] | Not stated | Not stated | Not stated |

## Evidence for this app

Each numbered note gives production code and a validation report. Source availability or a passing
unit test does **not** establish live support on every agent, OS or physical device. Candidate checks
are recorded in [0.8.0 validation](RELEASE_0_8_0_VALIDATION.md); published releases and remaining acceptance in
[0.8.1 and 0.8.2 release validation](RELEASE_0_8_2_VALIDATION.md).
Historical reports keep their original versions; newer scoped evidence supersedes only matching claims.

1. [License](../LICENSE), [dependencies and package](../app/build.gradle.kts), [privacy](../PRIVACY.md);
   [candidate scope review](RELEASE_0_8_0_VALIDATION.md#scope-and-evidence).
2. [Update source/policy](../app/src/main/java/ru/papajon/meshcentralmobile/update/),
   [checks and unperformed Android acceptance](UPDATE_CHECK.md#repeatable-checks),
   [candidate JVM results](RELEASE_0_8_0_VALIDATION.md#checks).
3. [TLS policy](../app/src/main/java/ru/papajon/meshcentralmobile/mesh/tls/ServerTls.kt),
   [remembered-device store](../app/src/main/java/ru/papajon/meshcentralmobile/security/RememberedDeviceStore.kt),
   [candidate TLS/cookie tests](RELEASE_0_8_0_VALIDATION.md#scope-and-evidence),
   [live pinned-key file session](PHONE_FILES_FIXES_VALIDATION.md#живая-проверка).
   [Security](../SECURITY.md) explains server-side cookie revocation limits and lost-phone recovery.
4. [Settings](../app/src/main/java/ru/papajon/meshcentralmobile/settings/SettingsRepository.kt),
   [app lock](../app/src/main/java/ru/papajon/meshcentralmobile/settings/AppLockController.kt),
   [profile model](../app/src/main/java/ru/papajon/meshcentralmobile/model/ServerProfile.kt),
   [validation and limits](ROADMAP_IMPLEMENTATION.md).
5. [Desktop UI](../app/src/main/java/ru/papajon/meshcentralmobile/ui/screens/RemoteDesktopScreen.kt),
   [shortcuts](../app/src/main/java/ru/papajon/meshcentralmobile/model/CustomShortcut.kt),
   [M5 validation](M5_PARITY_VALIDATION.md), [M5b gesture checks](M5B_PARITY_VALIDATION.md).
6. [Battery](../app/src/main/java/ru/papajon/meshcentralmobile/mesh/PhoneBattery.kt),
   [input capability](../app/src/main/java/ru/papajon/meshcentralmobile/mesh/PhoneRemoteInput.kt),
   [commands](../app/src/main/java/ru/papajon/meshcentralmobile/mesh/PhoneCommands.kt),
   [Android files](../app/src/main/java/ru/papajon/meshcentralmobile/mesh/files/AndroidFilesConnection.kt),
   [original phone checks](PHONES_VERIFICATION.md), [newer live file checks and limits](PHONE_FILES_FIXES_VALIDATION.md),
   [candidate input tests](RELEASE_0_8_0_VALIDATION.md#scope-and-evidence).
   The original phone report predates conditional input and the corrected console-command permissions;
   its blanket input-disabled/live-files-pending statements are historical.
7. [Terminal](../app/src/main/java/ru/papajon/meshcentralmobile/terminal/),
   [favorites](../app/src/main/java/ru/papajon/meshcentralmobile/model/FavoriteCommand.kt),
   [command policy](../app/src/main/java/ru/papajon/meshcentralmobile/management/RunCommand.kt),
   [M5b local checks](M5B_PARITY_VALIDATION.md), [live Linux output](PARITY_FIXES_VALIDATION.md#эмулятор-и-стенд),
   [Windows encoding/terminal evidence and limits](WINDOWS_POLISH.md). Windows UTF-8 mode requires
   PowerShell 5+ and normal language permissions; it cannot recover text already lost inside the
   agent or force every child program to emit UTF-8. Real Windows favorite-insertion acceptance remains pending.
8. [Files](../app/src/main/java/ru/papajon/meshcentralmobile/mesh/files/),
   [share queue](../app/src/main/java/ru/papajon/meshcentralmobile/transfer/),
   [session ownership](../app/src/main/java/ru/papajon/meshcentralmobile/workspace/),
   [live Agent/SSH/SFTP evidence](VALIDATION.md#051-local-acceptance--2026-10-21),
   [share/session checks](ROADMAP_IMPLEMENTATION.md).
9. [Inspector/management](../app/src/main/java/ru/papajon/meshcentralmobile/management/),
   [M5 checks](M5_PARITY_VALIDATION.md), [M5b checks](M5B_PARITY_VALIDATION.md),
   [corrected permissions/parsers and scoped Linux acceptance](PARITY_FIXES_VALIDATION.md),
   [Windows null/date behavior](WINDOWS_POLISH.md),
   [null-safe text reader](../app/src/main/java/ru/papajon/meshcentralmobile/json/JsonText.kt).
10. [Plugin](../server/mobile-companion/),
    [notifications](../app/src/main/java/ru/papajon/meshcentralmobile/notifications/),
    [live plugin checks and limits](ROADMAP_IMPLEMENTATION.md),
    [Windows read-only inspector evidence](INSPECTOR_VERIFICATION.md),
    [candidate notification TLS tests](RELEASE_0_8_0_VALIDATION.md#scope-and-evidence).
11. [Localized resources](../app/src/main/res/), [translation verifier](../ci/check_translations.py),
    [candidate translation results](RELEASE_0_8_0_VALIDATION.md#checks).
12. [Stylus controller](../app/src/main/java/ru/papajon/meshcentralmobile/mesh/StylusInputController.kt),
    [canvas input](../app/src/main/java/ru/papajon/meshcentralmobile/ui/screens/session/StylusCanvasInput.kt),
    [design and limits](STYLUS.md), [0.8.2 device checks](RELEASE_0_8_2_VALIDATION.md#082).

## Public sources

- **[P] PocketMesh:** [feature list and FAQ](https://mesh.pocketsized.app/),
  [distribution repository](https://github.com/pocketsized-apps/pocketmesh).
  The table deliberately does not assign a latest version without a verified release record.
- **[M] MeshControl Pro:** [Google Play description](https://play.google.com/store/apps/details?id=org.meshcentral.androidclient)
  and [developer website](https://www.rhelpy.com/meshcontrol-pro-landing.html).
  SSH is advertised by the website; the store description only describes a shell. Prices are the
  advertised euro prices on the website, not a quote for every region. No public source repository was
  found in these listings; the proprietary label is a distribution observation, not a code audit.
- **[A] Official agent:** [upstream README](https://github.com/Ylianst/MeshCentralAndroidAgent#readme).
  It explicitly states that remote desktop is view-only. Our conditional control support must not be
  read as an upstream agent release announcement.

Stylus support was checked against the same sources on 2026-10-26; none of them mentions pen, stylus or S Pen input.

The MeshCentral web panel remains the reference for server administration and tools this client does
not implement; see [why use a mobile client](../README.md#why-not-just-the-web-panel). This comparison
claims neither complete web-panel parity nor a performance advantage. The [AI policy](../AI_POLICY.md)
describes how this app is developed. Please [report corrections](https://github.com/papajon123/meshcentral-android/issues).
