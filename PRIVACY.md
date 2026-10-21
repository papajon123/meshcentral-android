# Privacy

This is a technical privacy note for the app, not legal advice. The app is a client: the person or organisation running the MeshCentral server decides what the server logs and who can access it.

This note describes the prepared 0.5.1 release (`versionCode` 10), which is not published at documentation time.

## Summary

- The app has no project account, advertising, analytics, telemetry, or crash-reporting SDK, and no project-operated backend.
- It connects only to the MeshCentral server you configure. The native client uses `/control.ashx`, `/meshrelay.ashx` (desktop, Agent terminal and Agent files), `/sshterminalrelay.ashx` (SSH) and `/sshfilesrelay.ashx` (SFTP).
- MeshCentral credentials and the app PIN are encrypted with an Android Keystore key. Android backup and device transfer are disabled.
- SSH passwords, private keys and passphrases are kept only in memory for the current prompt and outbound message. New credentials use `keep=false` or `keep:0` where the relay carries a keep flag; a server-saved key passphrase uses `sshkeyauth`. The app does not save any of them.
- Screen frames, terminal output and file transfers pass through your MeshCentral server and the controlled computer. The app does not record the remote desktop. Server-side terminal recording may exist and is outside the app.
- The terminal uses a local WebView with bundled assets. The raw **Debug WebView** in Settings is a separate WebView that loads the configured server page.
- The app does not request microphone access; see [Microphone and voice](#microphone-and-voice).

## Data stored on the device

| Data | Storage | Protection |
|---|---|---|
| Server URL, onboarding state, favourites, debug switches, screenshot/biometric flags, lock timeout | App-private `SharedPreferences` | Not encrypted; configuration data, but the server URL can reveal an internal host name. |
| MeshCentral user name and password | App-private storage | AES-GCM with a non-exportable Android Keystore key; written only after a successful login. |
| App PIN | App-private storage | Random salt, PBKDF2-HMAC-SHA256 (120,000 iterations, 256-bit), then AES-GCM with the Keystore key. |
| SSH password, private key and passphrase | Process memory only | Used for the current authenticated SSH/SFTP session; new credentials use `keep=false`/`keep:0` where supported, saved-key unlock uses `sshkeyauth`; not persisted, logged, or put in a Bundle. |
| MeshCentral session, relay and SSH viewer authentication cookies | Process memory | Not written to disk; cleared on logout, identity change, or process exit. SSH/SFTP viewer sockets use the same server-issued authentication cookie as the native relay. |
| Terminal output and scrollback | Process memory | Not written to app files or preferences; discarded when the session is disposed or the process ends. |
| Remote directory names/paths and transfer state | Process memory | Used to render the file screen and drive a transfer; not persisted as a local file index. |
| Local files chosen through SAF | The document provider selected by the user | The app reads an upload source or writes a download destination. Failed/cancelled downloads may leave a partial document at that user-selected location. |
| WebView cookies and storage, only if the raw Debug WebView is used | Android WebView storage | Separate from the local terminal renderer; cleared by the clear-session action in **Settings → Data**; excluded from backup. |
| Clipboard text copied from a terminal selection | Android system clipboard | Written only by an explicit **Copy selection** action; other apps may read the Android clipboard under platform rules. |
| Screenshots and recent-app previews | Android system | `FLAG_SECURE` when **Block screenshots** is enabled; otherwise the screen can be captured. Off by default. |

Android backup is disabled with `android:allowBackup="false"` and with backup/data-extraction rules that exclude all app preferences, databases, and files from cloud backup and device-to-device transfer.

You can remove stored MeshCentral credentials and the current session in **Settings → Data**. Uninstalling the app removes its local data; it does not delete anything from your MeshCentral server.

## Network connections

The native client opens HTTPS/WSS connections only to the MeshCentral base URL you configure:

- `/control.ashx` for authentication and the control channel.
- `/meshrelay.ashx` for the desktop, Agent terminal and Agent files relays.
- `/sshterminalrelay.ashx` for the SSH terminal relay.
- `/sshfilesrelay.ashx` for the SFTP relay.

The SSH/SFTP sockets include the device `nodeid` and the server-issued authentication cookie. SSH is terminated by MeshCentral; Android does not connect directly to the SSH host. The server operator can see the connecting IP address, account name, device list, session timing, SSH host selection, and traffic passing through these relays. Server-side logging, SSH host-key policy, terminal recording, and access control are outside the app.

The app does not send data to a project-operated backend. Release APKs are obtained from GitHub outside the app; the app itself does not contact GitHub.

The terminal renderer is local: it loads only bundled xterm assets through an intercepted reserved origin, with network and file access blocked. It receives no MeshCentral cookie or SSH secret. This is separate from the raw **Debug WebView**, which loads pages from the configured MeshCentral server; those pages may request other resources according to the server configuration and may create WebView cookies.

## Remote session data

- Desktop frames are decoded into memory to draw the remote screen. The app does not record the desktop or save frames to storage.
- Terminal input, output and resize messages travel through the MeshCentral relay. MeshCentral or the controlled computer may record terminal sessions; when the relay reports recording, the app displays a notice. The app itself does not save terminal output to storage.
- SSH passwords/keys are sent only to the MeshCentral server for the current session. They are not written to preferences, logs, saved state, or the terminal renderer.
- File listings and contents pass through MeshCentral. The app streams an upload from the chosen source, or writes a download to a newly selected document. No file content is sent to the project or kept in an app file cache.
- Clipboard exchange is text-only and happens only when you use a clipboard action, paste, or copy. The app does not monitor the Android clipboard in the background.
- A view-only desktop session does not send input.
- The controlled computer and the MeshCentral server may keep their own logs, clipboard history, terminal recordings, file-operation logs, or screen recordings according to their configuration.

## Microphone and voice

The app does not declare `RECORD_AUDIO` and contains no speech-recognition call.

Your installed Android keyboard may offer its own dictation feature. Its audio processing and privacy settings belong to that keyboard and its speech provider, not to this app. Text committed by the keyboard is handled like other remote input.

## App lock, biometrics, and screenshots

- The app lock is optional. When enabled, it can use a PIN, Android biometrics, or both.
- Biometric authentication is performed by the Android `BiometricPrompt`. The app receives only success or failure and does not access fingerprint or face templates.
- Biometric unlock requires Android 9 or newer; the PIN remains the fallback.
- **Block screenshots** is optional and off by default. When off, the remote desktop or terminal can appear in screenshots and in the recent-apps list. When on, Android blocks screenshots and hides the app in the recent-apps preview.

## Children and sensitive data

The app is a remote-administration tool. It is not directed at children. Treat remote desktops, terminal output, clipboard contents, SSH credentials, file contents and captured credentials as sensitive; do not use the app on a device you do not control.

## Changes and contact

This note is versioned with the repository and should be updated when data handling changes. Privacy questions can be opened as a GitHub issue without personal data. Security problems should use the private channel in [SECURITY.md](SECURITY.md).
