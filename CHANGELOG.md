# Changelog

Notable changes to MeshCentral Mobile. This project is an independent client and does not follow the MeshCentral server's release cycle.

## 0.5.3 — 2026-10-21

Preview release; `versionCode` 12.

- Correct terminal input ownership for Android IME processing-key events (`keyCode` 229), including the direct CJK input path exposed by the 0.5.2 release checks.
- Keep README download links pointed at published Releases, so an unpublished candidate never replaces the working download destination.

## 0.5.2 source candidate — 2026-10-21

Unpublished APK candidate; `versionCode` 11. The immutable source tag remains available.
The GitLab-to-GitHub snapshot transfer succeeded, but an API 35 release check exposed an IME input
race and stopped signing. No 0.5.2 APK was published to GitHub Releases.
Application runtime was unchanged from 0.5.1.

### Added

- English, Simplified Chinese and Russian guides with a shared visual header, live GitHub badges and genuine localized screenshots.
- A repeatable opt-in screenshot gallery using production screens with fictional data; setup screenshots come from the signed release APK.
- A private GitLab development pipeline and a release-snapshot bridge to GitHub. Only the selected source tree is exported; private development commits are not published.
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
