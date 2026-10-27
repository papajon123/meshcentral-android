# Known Limitations

This page records current limitations and historical validation references. Limits below are deliberate, unverified or outside this client.

## Validation status

Local public build/JVM and API 35 instrumentation passed. Compatibility work uses an isolated real MeshCentral 1.3.0/Linux Agent and SSH/SFTP fixture. See [current evidence](ROADMAP_IMPLEMENTATION.md) and [historical checks](VALIDATION.md).

Physical-phone acceptance, Windows/systemd service mutations, TalkBack, OEM background delivery and battery measurements remain. Restrictive remote-command ACL and notification-token revocation were exercised; consent/refusal coverage is not exhaustive.

## TCP port forwarding (Unreleased)

- TCP only; no UDP or full VPN. Each rule occupies `127.0.0.1` and, when configured and supported,
  `::1` on one port, never a wildcard. An IPv4-only host can use the IPv4 listener; a port occupied
  on an available IPv6 loopback still fails instead of being silently bypassed. Any app can access an enabled
  port, including remote services which trust localhost without authentication. Browser cookies
  are shared across ports on `127.0.0.1`; loopback ports do not isolate browser sessions.
- Requires a connected desktop Mesh Agent and Remote Control or Relay rights. Android agents,
  server-local devices (`localrelay.ashx`) and unknown rights are not offered. An enabled rule
  means the local listener is ready; the remote target is contacted only when a client connects.
- At most 64 saved rules, eight active rules and 16 simultaneous TCP connections in total.
  Extra connections are closed without opening another relay. Rules contain no passwords or
  cookies; do not put sensitive information in their names. Rules never auto-start and stop
  after five minutes with no connections. Active but idle connections do not trigger this timer.
- MeshCentral raw TCP has no directional FIN/half-close frame. After local EOF, the app first
  waits while queued uploads make progress, allowing at most 120 seconds without a decrease in
  the queue. Expiry or remote EOF with unsent bytes causes RST;
  normal completion uses a WebSocket close handshake. An empty queue is not target-application
  acknowledgement. The raw WebSocket write timeout is 120 seconds. Existing responses
  drain until five seconds of quiet time after the last received frame, with an initial grace
  period and no interruption of an in-progress local write. Protocols which need a remote FIN
  before replying are unsupported. An empty Android queue does not prove that the server/agent
  has drained its own buffers: sending local FIN before a slow target responds can still lose
  those downstream bytes when the quiet deadline expires. Keep the write side open until the
  application response (as a normal HTTP upload does). This is not transparent TCP half-close emulation.
- The 20-second deadline confirms relay pairing only. MeshCentral 1.3.1 sends `c` before proving
  the target connected, and its agent has no TCP-connect error handler. An unreachable target
  may leave a paired connection open indefinitely; stop it manually if the client keeps waiting.
  No target-data timeout is imposed on protocols where silence can be legitimate.
- Pairing refusal/timeout stops the rule only when no other connections remain. Certificate/TLS-policy
  failure still stops the rule. Other failures after pairing
  reset the affected client socket without stopping sibling connections or retrying. RST also
  prevents an EOF-delimited client from treating a known failure/cancellation as a short success.
- TCP relay Ping is 120 seconds: a proxy with a 60-second idle timeout can still drop an idle
  SSH connection. It cannot terminate other live connections through the rule. A client-specific
  keepalive may be needed. Very long reader backpressure can still exceed the heartbeat window.
- `tcpaddr` is resolved/reached by the agent, not Android. Live checks cover explicit loopback
  addressing and the Windows host's HTTPS endpoint reached from the WSL Linux agent's network.
  A separate physical machine on a real LAN remains unverified.
- HTTP, SSH, echo traffic, notification updates/stop and the default idle expiry were live-tested on an API 36 emulator with
  MeshCentral 1.3.1. Physical phones, OEM battery policies, and installed Microsoft RDP/VNC/SSH
  handler behavior remain unverified. The URI is only a convenience; copy the endpoint if a
  particular client uses a different link format. HTTPS to loopback may fail the target site's
  certificate/hostname check; the tunnel never disables the external browser's TLS checks.
- On the local Windows/API 36 emulator fixture, `10.0.2.2` can corrupt the TLS tail when a
  remote HTTP/1.0 connection ends (`SSLProtocolException: BAD_RECORD_MAC`). A raw Android
  WebSocket without the forwarding code reproduces it; the identical test through `adb reverse`
  succeeds. HTTP/1.1 keep-alive also succeeds over `10.0.2.2`. The precise virtual-network
  component is not identified. Treat this as a fixture-path limitation, not a reason to ignore
  TLS errors or retry the stream; physical-device acceptance is still required.
- Notification permission is requested on Android 13+. If notifications are denied or the channel
  is disabled, use the in-app switch/Stop all or Android's active-app controls; the notification
  drawer action cannot be shown. Process death closes sockets; no service/route is restored.
- Incoming TCP relay callbacks apply socket backpressure; a malicious server can still allocate a
  WebSocket frame inside OkHttp before the app rejects frames larger than 1 MiB. This is not a
  general resource-isolation boundary against a hostile authenticated MeshCentral server.

## Device capabilities

- System/network details depend on the data reported by MeshCentral. Missing fields stay unknown. Native processes/services use the agent control channel; the older Companion diagnostics remain optional; power/wake availability depends on rights, agent state and the network.
- Desktop, Terminal, Files, SSH and SFTP tabs are shown but disabled for a known denial, a known offline/unsupported state, or a missing required right. Unknown optional metadata enables an explicit attempt; the server decides the result.
- The app does not infer a grant from a missing field, nor does it bypass a server denial. A failed attempt reports the server's reason.
- At most two sessions can remain open, including at most one desktop. Closing is explicit; process death restores the selection, not a live connection.
- The **Open web panel** action opens the configured MeshCentral URL in a browser with a separate sign-in. No app credential or session is passed to the browser.

## Terminal

- The terminal needs a working, updated Android System WebView. A missing or obsolete provider, failed local script initialisation, or a 15-second startup timeout produces an actionable update/reopen message; the terminal cannot promise to run on every Android 8 device with an old WebView.
- Rendering is a local xterm.js 5.5.0 / addon-fit 0.10.0 view (MIT). It has no network/file access, no remote navigation, no OSC 8 links and no OSC 52 clipboard writes. Copy is limited to the explicit toolbar action and selection cap.
- Input is capped at 64 KiB per action, scrollback at 3,000 lines and the grid at 500 × 300 cells. Pending output is bounded to 1 MiB; a full buffer applies backpressure, and an individual frame over 1 MiB closes the session with an explicit error. Output is not silently dropped.
- Non-composition IME replacement and multi-character deletion retain xterm 5.5's limited terminal semantics: replacement text can be appended, and a multi-character deletion can produce one DEL. Use terminal editing keys for these operations; a full remote-line editor is not implemented.
- Each remote terminal shell is one-shot. After a relay/network/Agent failure, **Reconnect** explicitly creates a new shell while retaining the same local renderer, scrollback and viewport. Control-channel authentication and current device rights must be available again. Closing the tab, changing the account/profile or disposing the session still destroys its history. Submitted SSH passwords/keys are not retained or replayed; server-saved authentication may be offered by the server after an explicit reconnect.
- Buffered input from an old renderer generation is rejected. A broken WebView must be reopened/updated; reconnect does not repair the renderer. If the bounded output buffer is completely full, let it drain before reconnecting.
- After reconnect, the remote `ESC[3J` command no longer erases local scrollback: shell startup scripts can emit it as part of `clear`. Normal screen erasure still works. Close the tab to discard retained output. The previous visible screen and session marker are moved into scrollback before the fresh shell can clear its screen.
- MeshCentral can record terminal activity. The app shows a recording notice when the Agent relay reports it (`cr`). The audited SSH relay does not forward this marker to the client; absence of a notice never proves that recording is disabled.
- Fragmented UTF-8 at the MeshCentral SSH relay boundary is an upstream limitation noted in the renderer audit. The local renderer preserves split UTF-8 for raw Agent bytes; physical IMEs and composition remain a test gap.
- Terminal output and scrollback are not written to app storage. They are lost when the session is disposed.

## Files and transfers

- Directory replies are limited to 1 MiB of JSON and 20,000 entries. Over-limit replies are rejected, not truncated.
- Downloads use a fresh listing for the expected size, SAF truncation and post-transfer checks. Failure/cancellation attempts to remove only the newly created destination. If the provider refuses deletion, the app attempts to mark it `.partial` and reports whether it was removed, marked or retained. Previously existing documents and upload sources are not deleted.
- Both Agent and SFTP uploads are verified by readback SHA-256 and a fresh directory listing. An upload acknowledgement alone is not treated as success.
- There is no byte-offset resume or automatic retry. Computer queues offer explicit per-item or all-unfinished retry from byte zero; successful items are retained. Downloads create a new document, uploads can replace the same confirmed remote name. A fresh SFTP connection prompts for credentials again. Queue entries are process-local. Persistable SAF grants survive Activity destruction and are released on queue replacement/close; abrupt process death can leave a platform grant, but does not restore a queue.
- Long-press selection and download queues apply to computer Agent/SFTP sessions, not Android phone media categories. A batch holds at most 1,000 items. Recursive copying is bounded to 32 levels and 20,000 descendants per folder. A failed folder remains `name.partial` with completed contents. MeshCentral SFTP discards symlink/special-file metadata: the client attempts them as regular files, rejects size mismatch/refusal and pauses after a timeout. Same-size link targets cannot be distinguished. The opt-in `SftpLiveAcceptanceTest` exercises real OpenSSH through MeshCentral 1.3.1; Windows Agent compatibility remains unverified by that test.
- MeshCentral 1.3.1 closes its SFTP relay when `readdir` fails (including a directory without read permission). A regular-file refusal preserves the connection and later queue entries continue; a directory refusal or stalled FIFO pauses the remaining queue. Agent `dir:null`/over-limit subfolders now fail only their queue row and keep the session. After fixing the remote object, retry explicitly and authenticate again. The client cannot retain a connection the server has closed or infer missing Unix file-type metadata.
- Agent ZIP support is audited against MeshCentral 1.3.1, requires write permission and space for a remote copy, and has no capability/free-space preflight. If ZIP does not start, cancel plus a fresh absence check precedes recursive fallback. A ZIP that starts but never completes (write error or silence) is abandoned after an activity idle timeout and copied file-by-file. Completed archives require size/SHA-256 readback and ZIP/ZIP64 structure validation (65,535 entries and 32 MiB metadata maximum). Member CRC/decompression is not checked; validation requires a seekable SAF descriptor. Large ZIP64 is tested with sparse byte fixtures, not a physical 4 GiB transfer.
- Closing/cancelling an archive download sends `download stop`, discards late blocks until the next listing and attempts exact-name cleanup in the same session; a cancel during ZIP creation still closes the relay after bounded cancellation. A lost connection or refusal can leave `meshpilot-<UUID>.zip`; the exact path is shown in an in-app notice and optional private notification. A verified download stays successful with a cleanup warning. No broad temporary-file deletion runs. Process termination can prevent cleanup/notification.
- Recursive Agent folder deletion follows symbolic links and junctions, so targets outside the selected folder can be erased; the confirmation warns, but listings do not expose link targets. If a SAF provider cannot rename documents, verified files and folders keep the `.partial` suffix with a warning instead of being deleted.
- Downloads check the advertised size, local SAF readback SHA-256 against the received bytes, and a fresh remote listing. That is not a remote content snapshot: a file can change without changing its size. The live fixture independently compares the SHA-256 of all archive members. Android does not extract untrusted archives automatically.
- Cancelling an unconfirmed mkdir, rename or delete reports “unverified”, not success. The server may already have applied the mutation; live cancellation semantics remain unverified.
- Existing entries whose names cannot be safely created or operated are read-only. Use **Open web panel** for those entries; the app does not guess at destructive operations.
- SFTP directory deletion semantics and restrictive SSH/SFTP file permissions are server-dependent and were not exhaustively tested. Agent file permissions are not assumed to match SFTP permissions.
- Queues require persistable SAF grants and release their own grants on replacement/close. Providers refusing persistence cannot run queues. No broad storage permission is requested. Single-file downloads use CreateDocument, including the system Downloads destination.

## SSH and SFTP

- SSH is performed by the MeshCentral server through `/sshterminalrelay.ashx`; SFTP uses `/sshfilesrelay.ashx`. The app does not open a direct SSH connection from Android.
- The Android app does not implement or claim SSH host-key pinning. Host-key policy belongs to the server side and was not live-audited for this release.
- New SSH passwords, private keys and passphrases are ephemeral and sent with `keep=false` (SFTP) or `keep:0` (SSH terminal) where the relay carries a keep flag; a server-saved key uses `sshkeyauth`. The app does not save the key or passphrase.
- SSH/SFTP require enabled server features and the relevant device rights. Known disabled features are blocked; missing metadata permits an explicit server attempt. Server configuration and SSH host policy remain external.
- The server may record SSH/SFTP sessions. The app cannot guarantee the absence of server-side recording.

## Remote desktop scope

- Only the native Mesh Agent desktop path is supported: `/meshrelay.ashx?p=2`.
- Intel AMT KVM, RDP, VNC and WebRTC are not native clients. Device events, chat, registry/software and server administration are also outside this release; use the web panel where appropriate.
- The KVM cursor-shape index is parsed but the app draws its own pointer; the Agent does not paint the cursor into tiles.
- Touch uses mouse packets, not the KVM touch command. Pinch zoom is bounded at 16×; a tap outside the picture is ignored.
- View-only desktop sessions do not send input. Input can also be blocked by the remote input lock or keyboard state.

## Clipboard and text input

- Clipboard exchange is text-only, uses `getclip`/`setclip`, and needs the Agent to run as a service. Domain settings can disable it. An empty remote clipboard or a disabled setting may look like a timeout.
- Clipboard access happens only on an explicit action; there is no background sync.
- Phone keyboard input goes through a hidden native field and `InputConnection`. Vendor IMEs and physical keyboards can differ; composing text is not reproduced character by character.
- Some Android key codes have no MeshCentral virtual-key mapping and are unsupported. Voice dictation is not implemented and the app does not request microphone permission.

## Interface, language and accessibility

- The 0.5.1 interface has 22 languages in 23 locale variants, with 529 translated strings per variant and a shared application name. English is the default and fallback.
- Android 8–12 follows the system language; Android 13+ also exposes the standard per-app language setting. There is no in-app language picker.
- Arabic, Persian and Urdu use right-to-left layout. Server messages, device names, terminal output and physical key captions are not translated.
- Translations were produced with DeepSeek and have not been proofread by native speakers of every language.
- Phone, tablet, short-landscape, large-font, RTL and TalkBack coverage is not complete for every new device/terminal/files/SSH screen. The emulator checks are not physical-device acceptance.

## WebView and app lock

- **Settings → Debug WebView** is a raw view of the configured MeshCentral page, not the local terminal renderer. It has its own WebView cookies and storage, cleared by **Settings → Data**. WebView contents debugging is enabled only in debug builds.
- The terminal's local WebView serves only bundled assets from a reserved origin and blocks network/file access. It receives no MeshCentral cookie or SSH secret.
- App lock is optional. Biometrics require Android 9+; the PIN remains the fallback. App lock protects the UI, not the MeshCentral account.
- Active port-forwarding tunnels continue while the app is locked so external RDP/VNC/SSH/browser clients keep working. Stop them explicitly or through the notification. One open connection prevents the five-minute no-connection timeout, even without traffic.
- Screenshot blocking (`FLAG_SECURE`) is opt-in and off by default. It does not stop a camera pointed at the screen and may hide the app from the recent-apps preview.

## Updates and distribution

- GitHub Releases is the public distribution channel. There is no official F-Droid package, and inclusion in the official F-Droid catalogue is not promised.
- There is no in-app updater or store. Android accepts an update only with the same package name and signing key and a higher `versionCode`.
- The public package/signing identity is unchanged; earlier private builds use a distinct package and remain separate applications.
- The repo uses GitHub Actions. No GitLab workflow is implemented.

## Network and deployment

- Native control/relay connections support one explicitly approved SHA-256 leaf-SPKI pin per profile
  and normalized endpoint. Approval depends on an independent, trusted fingerprint comparison.
  SPKI and certificate fingerprints are different; browsers show the latter. Behind a TLS-terminating
  reverse proxy, the pinned key is the proxy's external key, not an internal MeshCentral/Agent key.
- An existing pin is mandatory even for platform-trusted certificates. A different key is blocked
  until explicitly replaced. Returning to platform-only trust requires **Forget**. Both actions
  require confirmation and fresh app unlock if enabled, and close all active native connections.
- A matching pin permits a chain/name exception only at that endpoint. It never bypasses certificate
  expiry or a not-yet-valid date: fix the phone clock or server certificate. Renewal with the same
  key works within the new validity interval. Without a pin, chain and hostname checks remain strict.
- An unknown issuer or omitted root can be approved only by explicitly verifying the leaf-key
  fingerprint. This supports MeshCentral's default private root. A public certificate may instead
  need server-side chain repair, as explained in the dialog. Broken signatures and invalid CA
  basic constraints/key usage cannot be approved or bypassed with a saved pin. Private chains with
  RSA keys below 2048 bits, EC keys below 256 bits, MD5/SHA-1 leaf signatures or an explicit EKU
  without serverAuth are also rejected. Issuer names alone
  do not establish identity.
- Only one key is supported per endpoint. Load balancers serving different keys (including separate
  RSA/ECDSA keys) can cause intermittent refusals. Use a consistent key; keys are never auto-added.
- Desktop, Agent terminal/files and SSH/SFTP relays derive from `baseUrl`. Separate relay hosts are
  unsupported. The profile's trust exception cannot be forwarded to another host or port.
- Background notifications use the profile pin and stop on TLS rejection. After resolving trust,
  the user must re-enable notifications. The raw Debug WebView does not inherit pins and is
  available only in debug builds.
- The app needs an HTTPS/WSS path to `/control.ashx`, `/meshrelay.ashx`, `/sshterminalrelay.ashx` and `/sshfilesrelay.ashx`. A reverse proxy that blocks WebSocket upgrades breaks sign-in or a tool session.
- Browser SSO and server administration are not supported by the native sign-in path. MeshCentral login-token pairs and the separate domain access key (`loginKey`) are supported; set the key in server settings. The app reports policy refusals instead of bypassing them.
- A reverse proxy with a subpath still needs live compatibility testing.
- Network changes, VPNs, captive portals and TLS interception can cause failures that the app reports but cannot repair.
- No SLA, general server-version support matrix, or bug-fix timeline is provided.

## Native task tools

- Computer task tabs cover Windows, Linux and macOS agents. Android tabs remain hidden until a successful process-list probe. PID 0 is neither displayed nor queried. Field and process-detail availability vary by agent/OS.
- Process verification is conservative: an empty list or a list retaining no more than half of the pre-command PID/name identities cannot prove termination. A different name at the same PID can confirm the previous identity ended only on Windows and with that continuity check. Linux/macOS/unknown-platform renames remain unverified; same-name PID reuse remains indistinguishable.
- Agent list replies have no request identifier. Ordinary cancellation retains the abandoned slot for up to 15 seconds; draining its late reply restores fresh reads and mutations without reconnecting. A real request timeout or an expired drain releases the slot but makes subsequent replies display-only until an agent-offline or control-disconnect boundary. Agent restarts invalidate pending reads and operation generations; they do not require reconnecting the server socket. Mutation verification deadlines are checked between reads and may be exceeded by one read's own 15-second timeout.
- Android agent 14 remains read-only even after a successful task-list probe. Missing `features2` is treated as zero, matching the official web client's bitwise input-allowed check.
- Missing Linux service state remains unknown. For systemd, a formerly running service is marked no longer running only when the same unit remains in a complete-enough list and a majority of the other previously running units remain running. Without these witnesses, a failed running-table probe is indistinguishable from a stopped service. A vanished unit is not proof of a stopped state. Restart still requires an observed not-running-to-running transition or a changed nonzero PID.
- Startup type is obtained on opening service details and retained only for the screen session. Known startup codes, process-detail labels, sizes, durations and dates are localized/formatted; missing or invalid values remain unknown.
- Critical-name detection is a warning heuristic, not an exhaustive OS protection list. It includes the Mesh Agent, common Windows core services, ssh/sshd, networking and systemd units.
- Windows/macOS live mutations, physical-device TalkBack and low-end-device performance need separate acceptance. Synthetic Windows fixtures use the actual JSON-string envelope sent by meshcore 1.3.1; they are not recorded Windows traffic.
