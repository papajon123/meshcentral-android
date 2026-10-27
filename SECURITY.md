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

### User-created settings backups and launcher surfaces

The Settings backup screen uses Android's Storage Access Framework; it does not request broad storage access or write intermediate plaintext files. Version 1 (`meshcentral-settings`) explicitly includes HTTPS server addresses and display names, pinned SHA-256 SPKI keys, favorite device IDs/names, custom keyboard shortcuts, favorite command text, screenshot protection, auto-lock delay, stylus preferences and appearance. It excludes account usernames/passwords (including login-token pairs), session/relay/notification tokens, remembered-device cookies, PIN data, biometric preferences and debug HTTP/TLS bypass flags. Review favorite command text before sharing: user-authored commands can themselves contain sensitive text.

The server access `loginKey` is omitted by default. Including it requires a separate checkbox and a password of 8–1024 characters. A supplied password encrypts the entire JSON document with AES-256-GCM, a random 16-byte salt and 12-byte nonce, and PBKDF2-HMAC-SHA256 with 600,000 iterations. Decryption accepts 600,000–5,000,000 iterations. The version 1 `meshcentral-settings-encrypted` envelope stores the exact serialized UTF-8 header as Base64; those decoded bytes, including the KDF parameters, salt and nonce, are GCM authenticated data. There is no password recovery; an incorrect password cannot be distinguished from modified ciphertext. Unencrypted imports cannot carry `loginKey`. Core fields remain strict. Unknown `extensions` sections are skipped with a preview notice and are not preserved on re-export. A future document version asks the user to update the app; corruption and size/item limits have separate errors. Port-forward rules are not restored by this version.

Import validates the whole document before writes, shows endpoints, expected keys, item counts and actual global preference values, and requires confirmation. Existing server endpoints are flagged and skipped by default; the user can explicitly import a second profile. New profiles receive fresh IDs. Profiles and selected global preferences (including appearance and launcher privacy) use one preferences transaction; new vault namespaces are cleared if publication fails. Existing profiles, sign-in data and PIN remain untouched. Imported certificate keys are only expectations, never prior trust: the first TLS handshake is blocked before authentication headers, even for a platform-trusted certificate. The normal trust dialog states whether the actual key matches and requires explicit approval. Android automatic app backup remains disabled.

Launcher entries expose device display names, last known availability and absolute timestamps, but no endpoint or account names. An optional setting (off by default) hides device metadata while PIN/biometric protection is locked or the app is backgrounded; dynamic shortcuts are removed and valid pinned copies remain enabled with a generic label. Tapping a hidden pin opens the normal lock gate; revoked pins remain disabled. Launcher behavior and shortcut update rate limits remain controlled by Android. Intents carry random opaque destination IDs; account and current device membership are checked again. Each pending launcher destination can activate its profile once, and manual profile changes or cancelled sign-in discard it. New launcher/event destinations replace each other. Profile switches retain pinned tokens; logout, deletion and account/endpoint changes revoke them. Launcher projection, persistence and platform updates, including worker refreshes, are queued on IO. This is not a claim that all existing settings or Keystore operations in the app run off Main.

Widget network refresh uses a 12-second timeout and reuses a live authenticated channel. Catalog/status rendering is queued separately; an operation already applying at cancellation is not rolled back, but its version prevents a later timeout from overwriting success. With PIN or biometric protection it never signs in using saved credentials. Otherwise a temporary client makes one attempt, at least 60 seconds after the previous background attempt, with automatic reconnect disabled and cleanup in `finally`. Credential rejection, 2FA, IP blocking and certificate rejection are persisted until a successful foreground app sign-in. The read-only vault cannot persist accepted credentials or cookies after concurrent logout. Unavailable refresh retains the last successful timestamp and offers an app entry point; an imported, unconfirmed certificate expectation cannot be used for background sign-in.

A refresh cooldown leaves the existing widget hint unchanged. A corrected wall clock cannot postpone refresh to an old future date: elapsed realtime enforces the minute; after an uptime reset a new minute starts. HTTP 429 blocks a temporary client's background attempts but does not revoke remembered-device cookies or stop the main client's normal retry budget. Legacy draft encrypted backups (210,000 iterations and constant AAD) are reported as unsupported before asking for a password; they are not decrypted. Duplicate normalized server endpoints, including repeats inside one file, can be skipped during import.

### Existing session protections

These properties are implemented in the current source tree:

- Credentials are persisted only after the server accepts the login, encrypted with AES-GCM using a non-exportable Android Keystore key.
- An explicitly entered MeshCentral login token is a generated `~t:` username and token password pair;
  it uses the same encrypted credential storage as an account password. It is not a TOTP secret.
- The optional server `loginKey` is a separate encrypted setting, scoped to the profile and normalized
  HTTPS endpoint (HTTP only in debug builds). It is not part of public profile metadata or saved UI
  state. Only that endpoint's `control.ashx` request receives it as `?key=`, never a relay or WebView.
  The draft key cannot follow an edited endpoint; deleting a profile removes its key. Pasting a server
  link separates its key into the masked field. Only query-free, credential-free endpoint drafts can
  enter saved UI state. Account logout keeps this server setting; clearing the key field and saving
  removes it. URLs containing it must not be logged.
- The PIN is salted and hashed with PBKDF2-HMAC-SHA256 (120,000 iterations, 256-bit output), then encrypted with the same Keystore key. Verification happens locally.
- Session and relay cookies are kept in memory. Android backup and device-to-device transfer are disabled for app data.
- The optional **Remember this device** 2FA cookie is stored separately in `SecureVault`, encrypted with
  AES-GCM and the non-exportable Android Keystore key. Its normalized endpoint, username, creation time,
  advertised lifetime, expiry and issuing connection's trust mode (platform trust or the pinned SPKI)
  are encrypted alongside it, within the profile namespace. It is only
  requested after an accepted code-based sign-in with the default-checked option enabled. No TOTP
  secret is saved, and remembering does not create a MeshCentral login token. The username and password
  (or an explicitly entered login-token pair) are still required on each login.
- Remembered-device cookies are sent only to that account's exact HTTPS `control.ashx` endpoint (HTTP
  only in debug builds), never to relays, other hosts/ports/domain paths or WebView. Expired cookies and
  server-rejected cookies are discarded. A cookie is eligible only under the same trust mode and,
  for pinned trust, the same key. Explicit pin approval/replacement/removal and certificate rejection
  erase it; restoring the same saved pin on app startup preserves it. Records from older versions
  without trust metadata are discarded. The debug TLS bypass cannot issue or send remembered cookies;
  debug HTTP fixtures use a separate trust scope that cannot be used over HTTPS.
  Logout, credential clearing, profile switch/deletion,
  server/account change and **Forget this device** erase them. Forgetting deletes the local copy; it
  does not revoke copies on other devices or terminate an already accepted session. See the audited
  [1.3.1 protocol](docs/PROTOCOL.md#remember-this-device-meshcentral-131).
- MeshCentral **1.3.1 has no server-side per-device revocation for this cookie**. Changing or resetting
  TOTP does not invalidate it. If a phone is lost, change the account password from a trusted device
  promptly: this blocks new native logins with the old password, but does not itself invalidate the
  cookie or terminate an already authenticated session. Ask the server administrator to terminate
  active sessions and, when cookie invalidation is required, rotate `loginCookieEncryptionKey` using
  the server's administration procedure (this affects other cookies protected by that key too).
  The Android client does not perform these server operations.
- Native control, relay and background notification connections require platform chain and hostname validation without a pin.
  An explicitly approved SHA-256 leaf-SPKI pin is mandatory for its profile and normalized host/port,
  even with a platform-trusted chain. Only a matching pin permits a hostname mismatch; validity dates
  are always checked. For an unpinned platform-trusted server, Android builds the trusted path and
  the app checks the leaf dates; unrelated or expired alternate intermediates are not a separate veto.
  A changed key blocks connections without recovery retries. An unknown issuer or omitted root may
  be offered for explicit leaf-key approval after independent verification. Private/pinned chains
  retain supplied-chain date, signature and CA-constraint checks. Private chains cannot be approved
  with RSA keys below 2048 bits, EC keys below 256 bits, MD5/SHA-1 leaf signatures or an explicit EKU
  that omits serverAuth. The dialog warns about hostname mismatches and treats issuer names as claims.
  Replacement and removal require confirmation and fresh app unlock when enabled; only removal
  returns to platform-only trust. Trust/profile changes cancel requests, close all WebSockets and
  create a new OkHttp client with its own TLS context and pool. Android's host-aware trust API is
  supported without relaxing Network Security Config. Background notifications use the current
  profile pin and stop polling on TLS rejection until the user resolves trust and re-enables them.
  See [TLS server-key approval](docs/ARCHITECTURE.md#tls-server-key-approval).
  The legacy permissive certificate override and cleartext HTTP remain debug-only and off by default.
- Logs contain connection outcomes and event counts, not credentials, cookies, PINs, tokens, or full request payloads.
- The raw Debug WebView is available only in debug builds and does not inherit native pins. It disables
  file and content access and blocks mixed content.
- Screenshot blocking is optional and uses `FLAG_SECURE` when enabled.
- The app has no in-app updater or installer. GitHub Releases distribute APKs; a separately configured F-Droid client can manage repository updates.

## Updates

Install release APKs only from the project's GitHub Releases page. Android will reject an APK whose package name or signing key differs from the installed app; this is expected and protects app data. A maintainer-signed release APK cannot be replaced by a locally built APK, and vice versa.

This is a volunteer project: there is no bug bounty and no guaranteed response time. Reports are handled on a best-effort basis.
