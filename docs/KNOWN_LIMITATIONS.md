# Known Limitations

This release is preliminary. The limitations below are deliberate or currently unimplemented; they are not promises of future work.

## Validation status

The 0.4.0 build has not been validated on a physical phone or against third-party MeshCentral servers. Earlier internal builds were exercised against a local MeshCentral 1.2.5 instance and an emulator; that is not validation of this release. See [VALIDATION.md](VALIDATION.md).

## Remote desktop scope

Only the native Mesh Agent desktop path is supported: `/meshrelay.ashx?p=2` through the MeshCentral server.

Intel AMT KVM, Web-RDP, Web-VNC, WebRTC optimisation, terminal or shell sessions, file transfer, and a native file manager are not implemented. The device list explains why a computer cannot be opened instead of silently falling back. A computer must be online, be a Mesh Agent session, and grant remote-control or remote-view rights.

## Server and account compatibility

- The client was written against MeshCentral 1.2.5. Compatibility with other server versions is not claimed; protocol details can change.
- Authentication uses the `x-meshauth` header, not the browser login form. Browser SSO (SAML, OIDC, or a similar redirect flow) is not supported.
- Domain URL access keys (`?key=`) are not supported. The setup form rejects query parameters in the server URL.
- An account with the `notools` site right can use the browser but is refused for API-style clients. The app reports this refusal as-is.
- Two-factor codes are supported through `tokenrequired`; a new code can be requested by e-mail, SMS, or push where the server offers it.
- Server-issued login-token credentials must be entered in their corresponding username and password fields; the token username starts with `~t:`. The app does not create login tokens.
- The server must offer HTTPS with a certificate valid for its host name. Release builds validate against platform CAs.
- The app does not administer the server: it cannot create meshes, users, policies, or server settings.

## Clipboard

- The exchange uses MeshCentral `getclip`/`setclip`, not simulated keystrokes.
- The agent answers clipboard commands only when it runs as a service, and `getclip` produces no answer when the remote clipboard is empty. The app waits 15 seconds and then reports the failure with that explanation.
- Domain settings `clipboardget` / `clipboardset` can disable the feature server-side; when they are off, the server silently drops the command and the app reports a timeout.
- Text only. Images, HTML, files, and rich formats are not transferred.
- Clipboard access happens only on an explicit user action; there is no background clipboard sync.

## Remote text and keyboard

- Text sending uses Mesh Agent KVM Unicode key packets (command 85) while the native relay is connected, so the remote keyboard layout does not matter for the characters themselves.
- The focused window, the remote input lock, and the agent-side keyboard state can still affect the result.
- The phone keyboard is forwarded through a hidden native input field and an `InputConnection`. Vendor IMEs and physical keyboards can behave differently; composing text is not reproduced character-by-character.
- The debug WebView fallback uses Android `KeyEvent` dispatch and is less reliable than the native path.
- Some Android key codes have no MeshCentral virtual-key mapping and are reported as unsupported.
- Voice dictation is not implemented in this source tree, although the manifest declares `RECORD_AUDIO`. If dictation is added through Android's system speech provider, review [PRIVACY.md](../PRIVACY.md) for the external-processing caveat.

## Pointer, touch, and zoom

- The KVM touch protocol (command 15) is not used. The app drives the pointer with mouse packets, which Mesh Agent accepts.
- Direct touch sends absolute positions; Trackpad moves relatively. Long press is the right button; long press plus drag drags on the computer; two fingers scroll; two-finger tap right-clicks.
- Multi-finger gestures are limited to the modes the session implements. There is no three-finger gesture and no custom gesture editor.
- Pinch zoom is clamped to 16x. Panning is bounded at fit zoom (35% of the canvas) so the picture can still be moved.
- A tap outside the picture is ignored rather than clamped to the nearest framebuffer edge.

## Multi-monitor

- The display list (command 11) and geometry (command 82) are parsed and shown when a computer has more than one display; switching sends command 12.
- Combined "all displays" framebuffers are supported.
- The cursor shape command (command 88) is parsed but not drawn: MeshCentral sends an index into a browser CSS cursor list, which has no Android equivalent. The client draws its own pointer at the last position it sent.

## Interface and language

- The 0.4.0 build ships 22 languages in 23 locale variants, with English as the default. Android 8–12 follows the system language; Android 13+ also exposes the standard system per-app language setting. There is no separate in-app language picker.
- If no supported language matches, the app falls back to English.
- Arabic, Persian, and Urdu use a right-to-left layout and a mirrored navigation arrow.
- User-visible strings live in `res/values/strings.xml` and `res/values/runtime_strings.xml`, with one catalog per `values-*` locale directory. Server messages, device names, and other server-provided text are shown verbatim and are not translated.
- Product and protocol names remain in Latin script; physical key captions remain `Ctrl`, `Alt`, `Shift`, `Win`, `Esc`, `Tab`, `Enter`, `Backspace`, `Del`, and `F1`–`F12`.
- Translations were produced with DeepSeek and have not yet been proofread by native speakers of every language; wording and terminology may still change.
- The app contains no online translation or geolocation feature.

## App lock, biometrics, and screenshots

- PIN and biometric lock are optional; the app lock can be disabled.
- Biometric unlock uses Android's platform `BiometricPrompt` and requires Android 9 or newer. On Android 8 the app reports that biometric unlock is unavailable and keeps PIN as the fallback.
- Screenshot blocking (`FLAG_SECURE`) is opt-in and off by default. It does not stop a camera pointed at the screen, and it may also hide the app from the recent-apps preview.
- App lock protects the UI, not the MeshCentral account. Anyone with the account password can sign in from another client.

## WebView fallback

The WebView in Settings is a raw troubleshooting view. It is not the supported remote-desktop path, does not mirror the native UI, and may not support the same server features. It disables file and content access, blocks mixed content, and is configured with WebView debugging only in debug builds. Its cookies are stored by Android WebView and can be cleared from Settings → Data.

## Updates and distribution

- There is no in-app updater or store in 0.4.0. Download APKs manually from GitHub Releases in a browser.
- Android accepts an update only with the same package name and signing key and a higher `versionCode`. A locally built APK has a different key and cannot replace a release APK.
- Version 0.4.0 keeps the package name and signing key of the public 0.3.0 release, so it installs over that release and preserves local application data. Your server may still require authentication. Earlier private builds are separate applications and require their own sign-in.
- APKs are distributed through GitHub Releases only; there is no store listing.
- The repository does not provide an SLA, a supported-version matrix beyond 0.4.x, or a bug-fix timeline.

## Network and deployment

- The app needs a working HTTPS/WSS path to `/control.ashx` and `/meshrelay.ashx`. A reverse proxy that blocks WebSocket upgrades will break sign-in or the desktop session.
- The app does not configure Nginx, certificates, firewalls, or MeshCentral itself.
- Network address changes, VPNs, captive portals, and proxy interception can produce TLS or connection failures; the app reports the failure but cannot repair the network.
