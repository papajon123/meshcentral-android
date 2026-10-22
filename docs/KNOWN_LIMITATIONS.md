# Known Limitations

This page describes 0.7.1. Limits below are deliberate, unverified or outside this client.

## Validation status

Local public build/JVM and API 35 instrumentation passed. Compatibility work uses an isolated real MeshCentral 1.3.0/Linux Agent and SSH/SFTP fixture. See [current evidence](ROADMAP_IMPLEMENTATION.md) and [historical checks](VALIDATION.md).

Physical-phone acceptance, Windows/systemd service mutations, TalkBack, OEM background delivery and battery measurements remain. Restrictive remote-command ACL and notification-token revocation were exercised; consent/refusal coverage is not exhaustive.

## Device screen and capabilities

- System/network details depend on the data reported by MeshCentral. Missing fields stay unknown. Services/processes and events require the optional plugin; power/wake availability depends on rights, agent state and the network.
- Desktop, Terminal, Files, SSH and SFTP tabs are shown but disabled for a known denial, a known offline/unsupported state, or a missing required right. Unknown optional metadata enables an explicit attempt; the server decides the result.
- The app does not infer a grant from a missing field, nor does it bypass a server denial. A failed attempt reports the server's reason.
- At most two sessions can remain open, including at most one desktop. Closing is explicit; process death restores the selection, not a live connection.
- The **Open web panel** action opens the configured MeshCentral URL in a browser with a separate sign-in. No app credential or session is passed to the browser.

## Terminal

- The terminal needs a working, updated Android System WebView. A missing or obsolete provider, failed local script initialisation, or a 15-second startup timeout produces an actionable update/reopen message; the terminal cannot promise to run on every Android 8 device with an old WebView.
- Rendering is a local xterm.js 5.5.0 / addon-fit 0.10.0 view (MIT). It has no network/file access, no remote navigation, no OSC 8 links and no OSC 52 clipboard writes. Copy is limited to the explicit toolbar action and selection cap.
- Input is capped at 64 KiB per action, scrollback at 3,000 lines and the grid at 500 × 300 cells. Pending output is bounded to 1 MiB; a full buffer applies backpressure, and an individual frame over 1 MiB closes the session with an explicit error. Output is not silently dropped.
- Non-composition IME replacement and multi-character deletion retain xterm 5.5's limited terminal semantics: replacement text can be appended, and a multi-character deletion can produce one DEL. Use terminal editing keys for these operations; a full remote-line editor is not implemented.
- A terminal is one-shot. Closing, losing the control/server/account session, or a relay failure ends it; reopen the tab for a new session. Previous input is never replayed automatically.
- MeshCentral can record terminal activity. The app shows a recording notice when the Agent relay reports it (`cr`). The audited SSH relay does not forward this marker to the client; absence of a notice never proves that recording is disabled.
- Fragmented UTF-8 at the MeshCentral SSH relay boundary is an upstream limitation noted in the renderer audit. The local renderer preserves split UTF-8 for raw Agent bytes; physical IMEs and composition remain a test gap.
- Terminal output and scrollback are not written to app storage. They are lost when the session is disposed.

## Files and transfers

- Directory replies are limited to 1 MiB of JSON and 20,000 entries. Over-limit replies are rejected, not truncated.
- Downloads use a fresh listing for the expected size, SAF truncation and post-transfer checks. A failed or cancelled download may still leave a partial document at the user-selected location; the app does not delete or automatically rewrite it.
- Both Agent and SFTP uploads are verified by readback SHA-256 and a fresh directory listing. An upload acknowledgement alone is not treated as success.
- There is no transfer resume and no automatic retry of a file operation. Reopening the tab or retrying requires a new explicit action.
- Cancelling an unconfirmed mkdir, rename or delete reports “unverified”, not success. The server may already have applied the mutation; live cancellation semantics remain unverified.
- Existing entries whose names cannot be safely created or operated are read-only. Use **Open web panel** for those entries; the app does not guess at destructive operations.
- SFTP directory deletion semantics and restrictive SSH/SFTP file permissions are server-dependent and were not exhaustively tested. Agent file permissions are not assumed to match SFTP permissions.
- SAF grants are temporary. The app does not request broad storage permission or persist a URI grant.

## SSH and SFTP

- SSH is performed by the MeshCentral server through `/sshterminalrelay.ashx`; SFTP uses `/sshfilesrelay.ashx`. The app does not open a direct SSH connection from Android.
- The Android app does not implement or claim SSH host-key pinning. Host-key policy belongs to the server side and was not live-audited for this release.
- New SSH passwords, private keys and passphrases are ephemeral and sent with `keep=false` (SFTP) or `keep:0` (SSH terminal) where the relay carries a keep flag; a server-saved key uses `sshkeyauth`. The app does not save the key or passphrase.
- SSH/SFTP require enabled server features and the relevant device rights. Known disabled features are blocked; missing metadata permits an explicit server attempt. Server configuration and SSH host policy remain external.
- The server may record SSH/SFTP sessions. The app cannot guarantee the absence of server-side recording.

## Remote desktop scope

- Only the native Mesh Agent desktop path is supported: `/meshrelay.ashx?p=2`.
- Intel AMT KVM, RDP, VNC and WebRTC are not native clients. Device events, wake/power, chat, registry/software and server administration are also outside this release; use the web panel where appropriate.
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
- Screenshot blocking (`FLAG_SECURE`) is opt-in and off by default. It does not stop a camera pointed at the screen and may hide the app from the recent-apps preview.

## Updates and distribution

- GitHub Releases is the public distribution channel. There is no official F-Droid package, and inclusion in the official F-Droid catalogue is not promised.
- There is no in-app updater or store. Android accepts an update only with the same package name and signing key and a higher `versionCode`.
- The public package/signing identity is unchanged; earlier private builds use a distinct package and remain separate applications.
- The repo uses GitHub Actions. No GitLab workflow is implemented.

## Network and deployment

- The app needs an HTTPS/WSS path to `/control.ashx`, `/meshrelay.ashx`, `/sshterminalrelay.ashx` and `/sshfilesrelay.ashx`. A reverse proxy that blocks WebSocket upgrades breaks sign-in or a tool session.
- Browser SSO, domain URL access keys (`?key=`) and server administration are not supported by the native sign-in path. The app reports policy refusals instead of bypassing them.
- A reverse proxy with a subpath still needs live compatibility testing.
- Network changes, VPNs, captive portals and TLS interception can cause failures that the app reports but cannot repair.
- No SLA, general server-version support matrix, or bug-fix timeline is provided.
