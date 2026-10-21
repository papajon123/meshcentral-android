# Security Policy

## Supported versions

This is a preliminary open-source release. Only the latest published preview is supported; there is no long-term support branch. The project is an independent client, not an official MeshCentral component.

## Reporting a vulnerability

Use GitHub private vulnerability reporting:

<https://github.com/papajon123/meshcentral-android/security/advisories/new>

Do not open a public issue containing exploit details, credentials, session cookies, relay or tunnel identifiers, real server names or addresses, or screenshots of a working desktop. A desktop screenshot can expose documents, notifications, and account names even when the app is not visible.

The maintainer enables and verifies private vulnerability reporting on the public repository as part of the release process. If the advisory form is not available yet, open a minimal issue asking for a private security contact and do not include technical details.

Please include:

- App version and `versionCode`, Android version, and device or emulator model.
- MeshCentral server version, if known.
- Steps to reproduce, expected and observed behaviour, and impact.
- Sanitized logs with host names, user names, cookies, and tokens removed.

Never attach a production keystore, a signed production APK, or a database dump. Do not test against servers or accounts you do not own or have permission to use.

## Scope

In scope: this app's source and release APK. Examples include credential storage, PIN and lock handling, TLS validation in release builds, `x-meshauth` handling, two-factor flows, the WebView fallback configuration, and remote input or clipboard handling.

Out of scope: vulnerabilities in MeshCentral itself (report them to the upstream project), a compromised MeshCentral server or Mesh Agent, a rooted or otherwise compromised Android device, denial of service caused by a hostile server, missing features, and risks that require debug-only HTTP or self-signed certificate settings.

## Security model

These properties are implemented in the current source tree:

- Credentials are persisted only after the server accepts the login, encrypted with AES-GCM using a non-exportable Android Keystore key.
- The PIN is salted and hashed with PBKDF2-HMAC-SHA256 (120,000 iterations, 256-bit output), then encrypted with the same Keystore key. Verification happens locally.
- Session and relay cookies are kept in memory. Android backup and device-to-device transfer are disabled for app data.
- Release builds validate TLS certificates with the platform trust store. The permissive trust manager and cleartext HTTP are debug-only and off by default.
- Logs contain connection outcomes and event counts, not credentials, cookies, PINs, tokens, or full request payloads.
- The WebView fallback disables file and content access, blocks mixed content, and enables WebView debugging only in debug builds.
- Screenshot blocking is optional and uses `FLAG_SECURE` when enabled.
- The app has no in-app updater or installer. GitHub Releases distribute APKs; a separately configured F-Droid client can manage repository updates.

## Updates

Install release APKs only from the project's GitHub Releases page. Android will reject an APK whose package name or signing key differs from the installed app; this is expected and protects app data. A maintainer-signed release APK cannot be replaced by a locally built APK, and vice versa.

This is a volunteer project: there is no bug bounty and no guaranteed response time. Reports are handled on a best-effort basis.
