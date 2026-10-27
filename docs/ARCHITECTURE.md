# Architecture

## Product shape

MeshPilot is a native Android control client for an existing MeshCentral deployment. It is not an Android Agent, does not expose the phone for management, and does not administer the MeshCentral server.

The primary path is Kotlin and Jetpack Compose. The desktop is native KVM; Agent terminal/files use the MeshCentral relay; SSH/SFTP are server-side relays. The terminal's **text renderer** is a deliberately isolated local WebView, not the raw server WebView. The raw **Debug WebView** in Settings is a troubleshooting view of the configured MeshCentral page and is unrelated to the terminal renderer.

## Runtime flows

1. **Onboarding and server settings.** `ServerUrlValidator` accepts the user's own HTTPS URL. Untrusted server keys require explicit, per-profile fingerprint approval; plain HTTP and the legacy certificate bypass remain debug-only. No endpoint is preconfigured.
2. **Sign-in and control channel.** `MeshCentralClient` opens `wss://<host>/control.ashx` with `x-meshauth`, handles two-factor challenges, requests `serverinfo`, `userinfo`, `meshes`, `nodes` and `authcookie`, and keeps the device list current.
3. **Device details.** `DeviceScreen` presents Details, Desktop, Terminal, Files, SSH and SFTP. `SessionAvailability` decides `Available`, `Unknown`, `Offline`, `Unsupported` or `Denied`. Known denial is blocked; unknown permits an explicit attempt; the server remains the authority.
4. **Desktop.** `MeshDesktopRelay` requests a control-channel tunnel, opens `/meshrelay.ashx?browser=1&p=2`, performs the text `c`/`cr` handshake, sends protocol `2`, then decodes KVM frames. The existing reconnect and framebuffer path is unchanged.
5. **Agent terminal.** `ManagedRelayConnection` opens the relay with usage `1`, asks the Agent to dial the tunnel, waits for `c`/`cr`, sends the `xterm` options and protocol `1`. `TerminalController` passes raw bytes to the local renderer and sends input/resize back.
6. **Agent files.** The same relay type opens usage `5`. `FilesController` speaks the Agent file protocol: JSON listing/control messages plus 16,380-byte upload/download chunks. SAF supplies source and destination streams.
7. **SSH terminal and SFTP.** The server-side SSH relays are `/sshterminalrelay.ashx` and `/sshfilesrelay.ashx`. The viewer URL carries the device `nodeid` and the **same server-issued `authcookie`** used by the native control/relay path. `TerminalController` handles SSH auth prompts and `~data`; `FilesController` plus `SftpFilesAdapter` handle SFTP authentication, listings and transfers.
8. **Rendering.** Binary KVM frames are reassembled and composited by `KvmFramebuffer`; Compose draws the framebuffer. The terminal renderer is a local WebView serving bundled xterm assets, with no transport, authentication or filesystem responsibility.

The control/desktop wire formats are in [PROTOCOL.md](PROTOCOL.md); its compatibility section predates the 0.5 terminal/files additions and describes only that older subset. The terminal renderer provenance and isolation audit is in `app/src/main/assets/terminal/RENDERER-AUDIT.md`.

## Sessions and endpoints

| Tool | Endpoint | Protocol / handshake | Ownership |
|---|---|---|---|
| Desktop | `/meshrelay.ashx?p=2` | KVM over binary relay | `MeshDesktopRelay` |
| Agent terminal | `/meshrelay.ashx?p=1` | Agent relay: `c`/`cr`, options, `1` | `ManagedRelayConnection` + `TerminalController` |
| Agent files | `/meshrelay.ashx?p=5` | Agent relay: `c`/`cr`, `5`; JSON + binary | `ManagedRelayConnection` + `FilesController` |
| SSH | `/sshterminalrelay.ashx?nodeid=…&auth=…` | server-side SSH relay | `ManagedRelayConnection` + `TerminalController` |
| SFTP | `/sshfilesrelay.ashx?nodeid=…&auth=…` | server-side SFTP relay | `ManagedRelayConnection` + `FilesController` |

All endpoints are on the configured MeshCentral server. Agent terminal/files also use the control channel to ask the Agent to dial the relay. SSH/SFTP do not open a direct socket from Android.

## Main components

| Component | Responsibility |
|---|---|
| `SettingsRepository` | Non-secret settings: server URL, onboarding, favourites, lock and debug options. |
| `SecureVault` | MeshCentral credentials and app PIN, AES-GCM with an Android Keystore key. |
| `MeshCentralClient` | Control WebSocket, login/2FA, device state, auth cookies, desktop relay, tool relay factory and session invalidation. |
| `MeshControlParser` / `MeshModels` | Server capabilities, device rights, Agent capability bits and availability inputs. |
| `ManagedRelayConnection` | One-shot relay transport: credentials callback, handshake timeout, framing, backpressure, oversize rejection and close. |
| `SessionAvailability` | Separates known denial from unknown metadata and offline/unsupported states. |
| `TerminalController` | Terminal protocol, SSH auth state, shell-readiness gate, resize and explicit session close. No automatic reconnect. |
| `TerminalOutputBuffer` / `TerminalWebRenderer` | Bounded output queue and locked-down local xterm WebView bridge. |
| `FilesController` | Agent and SFTP listings, transfers, SAF streams, confirmations and readback verification. |
| `AgentFilesProtocol` / `SftpFilesAdapter` | Protocol-specific parsing, chunk framing, name safety and SFTP EOF/size rules. |
| `DeviceScreen` / `DeviceTab` | Details and tool tabs; one selected tool at a time. |
| `FilesScreen` / `TerminalScreen` | Compose UI for the two tool controllers, confirmations and SSH auth prompts. |
| `MeshSessionViewModel` | Owns the client and tool controllers across Activity recreation; closes them on identity/session loss. |
| `MeshDesktopRelay`, `Kvm*`, `RemoteViewport` | Existing desktop relay, decoder, framebuffer, input encoding and viewport. |
| `MeshWebViewController` | Raw troubleshooting WebView for the configured server page; separate cookie scope from the local terminal renderer. |

## Capability and lifecycle rules

- Rights and capability fields are optional inputs. A known `NOTERMINAL`/`NOFILES` denial, missing remote-control right, known offline state or known disabled server feature blocks the tab.
- Missing metadata is `Unknown`, not a grant: the tab enables an explicit attempt and the server's refusal is shown. The app does not invent SSH support, file permission or host-key trust.
- Selecting another tool, or opening Desktop, closes the previous tool. The UI does not create a second terminal/file surface in parallel.
- `MeshSessionViewModel` survives ordinary Activity recreation, so the relay, controller, terminal renderer and pending file-download selection remain. Secrets are never written to a Bundle or saved state.
- Logout, a changed server/account identity, control-channel failure/closure, authentication rejection or a stale device snapshot invalidates tool relays. Closing is final; tools do not reconnect automatically.
- Agent terminal/files, SSH and SFTP replay neither input nor file mutations. A new session is an explicit user action. The desktop keeps its existing separate resume/reconnect behaviour.
- If the app cannot prove a pending mutation, it reports the change as unverified rather than successful.

## Terminal renderer boundary

- Bundled `@xterm/xterm` 5.5.0 and `@xterm/addon-fit` 0.10.0 (MIT), with the complete notices beside them in `app/src/main/assets/terminal/`.
- Only six exact asset paths on `https://terminal.meshcentral.invalid/` are served from the APK. `blockNetworkLoads`, disabled file/content access, navigation rejection, CSP and a no-store response header are independent safeguards.
- The JavaScript-to-Kotlin bridge exposes only `input`, `resize`, `ready` and `consumed`. It receives no URL, cookie, SSH credential, key, path or file operation.
- OSC 8 links and OSC 52 clipboard writes are consumed. Copy is an explicit Kotlin action; terminal-driven remote navigation and clipboard writes are unavailable.
- One render write is in flight at a time. Kotlin pending output is bounded to 1 MiB; a full buffer applies backpressure. The relay channel holds eight frames; an individual frame over 1 MiB is rejected explicitly. Input is capped at 64 KiB, scrollback at 3,000 lines and the grid at 500 × 300 cells.
- A working, updated Android System WebView is required. Missing/obsolete WebView or failed local initialisation produces an actionable update/reopen error.

## File-transfer integrity

- Directory JSON is capped at 1 MiB and 20,000 entries; an over-limit reply fails rather than truncating.
- Downloads use a fresh listing for the expected size, a SAF destination opened with truncation, a size check, and a post-transfer listing. SFTP zero-byte and exact-block ends are completed without waiting for an EOF the server omits.
- Uploads are read back through the relay and checked by SHA-256; a fresh listing confirms name and stable size. An `uploaddone` acknowledgement alone is not success.
- A failed or cancelled download can leave a partial user-selected SAF document. The app does not delete or silently overwrite it.
- Pending mkdir/rename/delete cancellation is reported as unverified. Live cancellation semantics and transfer resume remain unverified.

## Security boundaries

- Without a pin, native TLS requires Android platform trust and a matching hostname. An explicitly approved per-profile key is mandatory once saved, as described below. Debug-only overrides are explicit and off by default.
- New SSH passwords/keys are sent with `keep=false` (SFTP) or `keep:0` (Agent terminal); server-saved key unlock uses `sshkeyauth`. All are used only for the current outbound message and none is persisted or logged.
- SSH is terminated at MeshCentral. The Android app has no direct SSH stack, host-key store or host-key pinning claim; server-side host-key policy was not live-audited.
- Terminal content may be recorded by the server. The relay's `cr` marker drives a visible recording notice; no marker does not prove recording is disabled.
- The local terminal WebView and the raw server Debug WebView are separate. The raw view loads the configured server page and stores its own WebView cookies; **Settings → Data** clears them.
- Android backup and device transfer exclude all app data. Screenshot blocking is opt-in `FLAG_SECURE`.
- The app has no telemetry or project backend.

### TLS server-key approval

`ServerProfile.pinnedSpkiSha256` stores SHA-256 of the **leaf certificate's SubjectPublicKeyInfo**, as
64 lowercase hexadecimal characters. Older profiles default to `null`. This is public key metadata,
not authentication material. Trust is scoped to the profile and normalized HTTPS endpoint (host,
port and MeshCentral domain path); changing the address clears the pin. Equivalent casing/default
port/trailing-slash spellings do not change the endpoint. A different profile never inherits trust.

`mesh/tls/ServerTrust.kt` checks certificate validity before accepting any key. Expired and
not-yet-valid certificates produce a separate non-overridable error asking the user to check the
phone clock and server certificate. An existing pin is **mandatory even for platform-trusted
chains**: a different key is blocked. Returning to platform-only trust requires explicitly choosing
**Forget**. Renewal with the same public key is accepted while the certificate is valid.

Without a pin, a platform-trusted chain uses Android's path builder and normal OkHttp hostname
validation. The app checks leaf dates only, leaving alternate/reordered/expired intermediate
certificates to Android's path selection. An
untrusted but internally consistent chain can be approved explicitly, including a self-signed leaf
or a leaf issued by an unknown CA whose root is omitted (the default MeshCentral setup). Every
supplied link must have a matching issuer name and valid signature; issuers must satisfy CA basic
constraints, path length and keyCertSign usage when present. Invalid self-signatures and broken
chains cannot be approved, even with a saved pin. Private and pinned chains check dates across the
supplied chain. Private chains also reject RSA keys below 2048 bits, EC keys below 256 bits,
MD5/SHA-1 leaf signatures and explicit extended key usage without serverAuth.
The dialog says when an issuer identifies itself as a MeshCentral root, or identifies an unknown CA; for a public certificate with an
incomplete chain, it recommends repairing the server. The issuer name is untrusted descriptive
metadata, not evidence of authenticity. An approvable hostname mismatch has a separate warning.
An exact saved key permits a
hostname mismatch, including a platform-trusted chain with a wrong name, only at the configured
host and port. The verifier reads that specific TLS session's certificate; it has no mutable
last-match flag or shared chain-decision cache.

The TLS context and OkHttp receive the **same** trust manager. Its Android
`checkServerTrusted(chain, authType, host)` overload supports `X509TrustManagerExtensions` and
OkHttp's Android chain cleaner; unpinned checks delegate to the platform's host-aware validation.
Network Security Config and system trust anchors are not relaxed. Native requests are restricted
to the configured origin, and redirects remain disabled. Desktop and all tool relay URLs derive
from `baseUrl`; a separate relay host is unsupported and cannot inherit the pin.

The dialog shows two different SHA-256 values: **public key (SPKI)**, which is pinned, and the
**certificate**, which browsers display. Both use colon-separated bytes. It also shows the subject,
validity dates and endpoint. An administrator can calculate SPKI from a trusted local certificate:

```sh
openssl x509 -in cert.pem -pubkey -noout | openssl pkey -pubin -outform DER | openssl dgst -sha256 -c
```

For direct TLS, substitute `meshcentral-data/webserver-cert-public.crt` for `cert.pem`; if a reverse
proxy terminates TLS, use the proxy certificate instead. `openssl x509 -in cert.pem -fingerprint
-sha256 -noout` calculates the separate certificate fingerprint. Verify through an independent,
trusted channel: a fingerprint fetched over the same unverified connection cannot detect an
intermediary. These instructions and errors are included in all 23 locale variants.

Every native control, desktop, Agent terminal/files and SSH/SFTP relay uses this policy in debug
and release. A certificate refusal stops automatic retries, network-recovery retries and desktop
resume. Trusting retries only the current in-memory sign-in attempt. Replacing a changed key requires
a second explicit warning/confirmation and fresh PIN/biometric unlock when app lock is enabled.
Forgetting also requires confirmation and the same unlock, so it cannot bypass replacement protection.
Confirmations are bound to profile, endpoint, old key and displayed certificate. Settings shows the
saved fingerprint and Forget. Changing an endpoint clears its pin; switching profiles creates a new
client. Changing trust cancels active requests, closes all control/desktop/tool sockets and replaces
the OkHttp client, TLS context and connection pool. Control callbacks run in the owner's Main scope
and verify the session generation and transport identity, so a late handshake cannot authenticate
or persist credentials for a replacement profile or endpoint.

**NotificationHttpClient uses the same ServerTls policy:** each poll reads the current profile's
endpoint and pin and creates an isolated client. A changed key blocks the handshake before the
bearer token is sent, disables polling and cancels its scheduled work. The user must resolve trust
and re-enable notifications. Unpinned servers require Android trust and a valid hostname; approved
private keys work. A profile observer cancels in-flight calls when the endpoint/pin changes, and a
network interceptor rechecks policy after TLS but before sending HTTP headers. Obsolete polls do
not disable or overwrite the replacement policy's status; their responses are discarded. Notification clients
never use the permissive debug override. The raw Debug WebView is available only in debug builds
and does not inherit the native client's pins. The legacy debug-only override remains for local
tests and is not enabled by the pinning flow; it cannot override an existing pin or validity failure.

## Lifecycle and threading

- The client, controllers and UI state run on coroutines and `StateFlow`; files and terminal byte handling run off the main thread.
- The terminal consumer suspends on bounded output rather than blocking the WebSocket reader or dropping bytes. Close/cancellation wakes blocked senders.
- Entering the background pauses the desktop stream and drives app-lock timing. Tool sessions are explicit and do not resume or replay after a dropped relay.
- Relay handshakes have bounded auth/handshake/shell timeouts (20 s auth, 60 s relay handshake, 30 s shell or SSH response in the tool controllers).

## Deployment expectations

The repository contains no server component. The MeshCentral server must provide:

- HTTPS with a platform-trusted certificate valid for the host name, or an independently verified,
  explicitly approved leaf public key for native control/relay connections. Notification polling
  still requires platform trust and a valid host name.
- WebSocket upgrades for `/control.ashx`, `/meshrelay.ashx`, `/sshterminalrelay.ashx` and `/sshfilesrelay.ashx` through any reverse proxy.
- A Mesh Agent supporting desktop/terminal/file relays, plus an SSH host and enabled SSH/SFTP server features for those tabs.
- An account with the relevant remote-control/view, terminal/file and SSH rights for the device group.

A reverse proxy or certificate problem is reported as a connection failure; the app does not configure Nginx, firewalls, MeshCentral or an SSH server.

## Tech stack

- Kotlin 2.0.21, Jetpack Compose (BOM 2024.12.01), Material 3.
- Android Gradle Plugin 8.10.1, Gradle 8.11.1, JDK 17.
- `compileSdk`/`targetSdk` 36, `minSdk` 26 (Android 8.0).
- OkHttp 4.12.0, kotlinx.coroutines 1.9.0.
- Bundled xterm.js 5.5.0 and addon-fit 0.10.0, MIT.

## Source layout

- `mesh/` — control channel, desktop relay, KVM parser/framebuffer/input, viewport, model and cookie handling.
- `mesh/session/` — one-shot relay transport, protocol enum and capability availability.
- `mesh/files/` — Agent file protocol, SFTP adapter and `FilesController`.
- `terminal/` — terminal controller, bounded output buffer and local WebView renderer.
- `ui/` — Compose screens, device tabs, sheets, components and theme.
- `web/` — raw troubleshooting WebView for the server page.
- `input/`, `security/`, `settings/` — IME mapping, Keystore vault, app lock and preferences.

## Testing strategy

JVM tests cover control parsing, KVM framing, input encoding, relay construction, availability rules, Agent/SFTP protocol handling and terminal buffering. Instrumented tests cover Compose navigation, real Activity recreation, terminal renderer behaviour and file controllers; opt-in live tests require an isolated MeshCentral fixture. Feature checks used a real isolated Linux Agent and SSH/SFTP host on npm MeshCentral 1.2.5. The 1.2.6 package was source-audited, not run as a server. Physical-phone, Windows-Agent and live restrictive-rights/consent checks remain open. See [VALIDATION.md](VALIDATION.md); test presence is not a pass result.

## Non-goals

The app does not implement Intel AMT KVM, RDP, VNC, WebRTC, device events, wake/power management, registry/software management, chat, guest sharing, a direct-from-phone SSH product, a MeshCentral server, or an Android Agent. It does not administer MeshCentral users, meshes, policies or server settings.
