# Architecture

## Product shape

MeshCentral Mobile is a native Android control client for an existing MeshCentral deployment. It is not an Android agent, does not expose the Android device for management, and does not administer the MeshCentral server.

The primary path is native Kotlin and Jetpack Compose. A raw WebView is available from Settings as a troubleshooting fallback, not as the supported remote-desktop path.

## Runtime flow

1. **Onboarding and server settings.** The first launch asks for the user's own MeshCentral base URL. `ServerUrlValidator` accepts `https://` URLs; `http://` is accepted only in a debug build with the debug switch on. No server is preconfigured.
2. **Sign-in.** `MeshCentralClient` opens `wss://<host>/control.ashx` with the `x-meshauth` header. `serverinfo` and `userinfo` arrive first; two-factor challenges are reported to the UI and answered by reconnecting with a code or a request token.
3. **Device list.** The client requests `meshes`, `nodes`, and `authcookie`. `MeshControlParser` turns the replies into groups and devices, and live `event` frames update the list. Mesh rights decide whether a computer can be opened and whether input is allowed.
4. **Desktop session.** `MeshDesktopRelay` asks the agent to dial the relay with a control-channel `tunnel` message, then opens `/meshrelay.ashx?browser=1&p=2`. After the relay's text `c`/`cr`, it sends the KVM protocol number `2`; after the first screen-size command it sends the initialisation batch.
5. **Rendering.** Binary KVM frames are reassembled, jumbo frames are unwrapped, and tiles, copy rectangles, screen-size, display, key-state, input-lock, and cursor commands are decoded. `KvmFramebuffer` composites the image; Compose draws the framebuffer through `RemoteViewport`.
6. **Input and clipboard.** `KvmInputEncoder` builds byte-exact mouse, keyboard, Unicode text, display, and control commands. Clipboard text uses `getclip`/`setclip` messages on the control channel.

The detailed wire formats and source references are in [PROTOCOL.md](PROTOCOL.md).

## Main components

| Component | Responsibility |
|---|---|
| `SettingsRepository` | Non-secret settings: server URL, onboarding state, debug switches, screenshot blocking, lock timeout, favourites. |
| `SecureVault` | Credentials and PIN material, encrypted with an Android Keystore AES-GCM key. |
| `MeshAuth` / `MeshAuthDiagnostics` | Builds the `x-meshauth` header and turns `{action:'close', cause, msg}` frames into user-readable reasons. |
| `MeshControlMessages` | Control-channel JSON: device list, auth cookie, tunnel request, relay URL, clipboard. |
| `MeshCentralClient` | Owns OkHttp, the control WebSocket, login and two-factor flows, reconnects, node events, relay cookies, and clipboard routing. |
| `MeshControlParser` | Parses `serverinfo`, `userinfo`, `meshes`, `nodes`, `authcookie`, `event`, `msg`, and `close` frames and resolves mesh rights. |
| `MeshDesktopRelay` | Requests the tunnel, performs the relay handshake, keeps the session alive, sends input, reconnects with backoff, and closes cleanly. |
| `KvmPacketParser` / `KvmFrameDecoder` | Streaming decoder for the KVM channel: fragments, several commands per WebSocket frame, jumbo unwrapping, and per-command payload validation. |
| `KvmFramebuffer` | Composites tiles and copy rectangles into one ARGB bitmap. |
| `KvmInputEncoder` | Client-to-agent commands: compression, refresh, pause, input lock, displays, mouse, keys, Unicode text, and Ctrl+Alt+Del. |
| `RemoteViewport` | Touch-to-remote coordinate mapping, fitting, zoom, pan clamping, and hit testing. Pure Kotlin and unit tested. |
| `SessionGesturePolicy` | Decides whether a gesture is a click, drag, scroll, or view movement for each pointer mode. |
| `RemoteInputBridge` / `NativeKeyboardCapture` | Delivers text and keys to the native relay; optionally dispatches to the WebView fallback. |
| `MeshWebViewController` | Hardened WebView configuration for the fallback. |
| `MeshSessionViewModel` | Holds the client and survives configuration changes. |

## Security boundaries

- The configured MeshCentral server is trusted for the session, but the app still validates TLS in release builds. Debug-only trust overrides are explicit and off by default.
- Credentials are persisted only after a successful login and are never logged. PIN verification is local; biometric authentication is delegated to `BiometricPrompt`.
- The KVM parser treats relay data as untrusted input: it checks declared lengths, rejects oversized frames (32 MiB cap), tolerates fragments, and drops a corrupt stream instead of looping.
- Android backup and device-to-device transfer exclude all app data. The optional WebView fallback stores its own cookies; **Settings → Data** clears them.
- Screenshot blocking is an opt-in `FLAG_SECURE` window flag. It does not protect against a camera pointed at the screen.
- The app has no telemetry or project backend. Manual updates rely on the GitHub release artifact and Android's package/signature checks.

## Lifecycle and threading

- The client runs on coroutines and exposes `StateFlow` state to Compose.
- Entering the background pauses the remote stream and records the time for the app lock; returning to the foreground resumes or reconnects the relay.
- The relay sends an RTT request every 10 seconds, answers server pings, and uses a 6-second resume watchdog before reopening a session.
- Reconnects use bounded backoff and stop when the user closes the session.

## Deployment expectations

The app repository contains no server component. The MeshCentral server must have:

- HTTPS with a certificate valid for the public host name.
- WebSocket upgrades allowed for `/control.ashx` and `/meshrelay.ashx` through any reverse proxy.
- A Mesh Agent that supports the desktop relay on the controlled computer.
- A user account with remote-control or remote-view rights for the relevant device group.

If a reverse proxy or certificate change is involved, validate the configuration before reloading it and confirm that normal browser access to MeshCentral still works. The app cannot diagnose a broken proxy from the phone side beyond reporting the connection failure.

## Tech stack

- Kotlin 2.0.21, Jetpack Compose (BOM 2024.12.01), Material 3.
- Android Gradle Plugin 8.7.3, Gradle 8.10.2, JDK 17.
- `compileSdk`/`targetSdk` 35, `minSdk` 26 (Android 8.0).
- OkHttp 4.12.0 for HTTP and WebSocket, kotlinx.coroutines 1.9.0.
- Public application ID `io.github.papajon123.meshcentral`; version 0.3.0, `versionCode` 7. Debug builds add `.debug`. The Kotlin namespace is independent of the installed application ID.

## Source layout

- `app/src/main/java/ru/papajon/meshcentralmobile/mesh/` — control channel, relay, KVM parser, framebuffer, input encoder, viewport.
- `input/` — Android key and IME translation.
- `security/` — Keystore-backed credential and PIN storage.
- `settings/` — preferences, URL validation, app lock.
- `ui/` — Compose screens, sheets, components, theme.
- `web/` — WebView fallback configuration.

## Testing strategy

Pure logic (control parsing, KVM framing, input encoding, header construction, viewport maths) is kept out of Android classes and covered by JVM unit tests. Instrumented tests use Compose UI tests and mock servers on an emulator. Live testing requires a real MeshCentral test instance and a real agent; it is a manual step because it needs credentials and a controlled computer. See [VALIDATION.md](VALIDATION.md).

## Non-goals

The app does not implement Intel AMT KVM, Web-RDP, Web-VNC, WebRTC, terminal sessions, file management, a MeshCentral server, or an Android agent. It does not manage MeshCentral users, meshes, policies, or server configuration.
