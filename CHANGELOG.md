# Changelog

Notable changes to MeshCentral Mobile. This project is an independent client and does not follow the MeshCentral server's release cycle.

## 0.3.0 — in preparation

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
