# Privacy

This is a technical privacy note for the app, not legal advice. The app is a client: the person or organisation running the MeshCentral server decides what the server logs and who can access it.

## Summary

- The app has no project account, no advertising, no analytics, no telemetry, and no crash-reporting SDK.
- It connects to the MeshCentral server you enter. The 0.3.0 release has no in-app store, self-updater, or background check against a project server.
- Credentials and the app PIN are encrypted with an Android Keystore key. Android backup and device transfer are disabled.
- Remote screen frames, input, and clipboard text pass through your MeshCentral server and the controlled computer. The app does not record or upload them.
- The app does not request microphone access; see [Microphone and voice](#microphone-and-voice).

## Data stored on the device

| Data | Storage | Protection |
|---|---|---|
| Server URL, onboarding state, favourites, debug switches, screenshot/biometric flags, lock timeout | App-private `SharedPreferences` | Not encrypted; this is configuration data, but the server URL can reveal an internal host name. |
| MeshCentral user name and password | App-private storage | AES-GCM with a non-exportable Android Keystore key; written only after a successful login. |
| App PIN | App-private storage | Random salt, PBKDF2-HMAC-SHA256 (120,000 iterations, 256-bit), then AES-GCM with the Keystore key. |
| MeshCentral session and relay cookies | Process memory | Not written to disk; cleared on logout or when the process ends. |
| WebView cookies and storage, only if the fallback is used | Android WebView storage | Cleared by the clear-session action in **Settings → Data**; excluded from backup. |
| Screenshots and recent-app previews | Android system | `FLAG_SECURE` when **Block screenshots** is enabled; otherwise the screen can be captured. Off by default. |

Android backup is disabled with `android:allowBackup="false"` and with backup and data-extraction rules that exclude all app preferences, databases, and files from cloud backup and device-to-device transfer.

You can remove stored credentials and the current session in **Settings → Data**. Uninstalling the app removes its local data; it does not delete anything from your MeshCentral server.

## Network connections

The native client opens an HTTPS/WSS connection only to the MeshCentral base URL you configure:

- `/control.ashx` for authentication and the control channel.
- `/meshrelay.ashx` for the remote desktop relay.

The server operator can see the connecting IP address, the account name, the device list, session timing, and the remote-control traffic that passes through the relay. Server-side logging and access control are outside the app.

The app does not send data to a project-operated backend. Release APKs are downloaded from GitHub in a browser; the app itself does not contact GitHub. The optional WebView fallback loads pages from your MeshCentral server, and those pages may request other resources according to your server's configuration.

## Remote session data

- Screen frames are decoded into memory to draw the remote desktop. The app does not record the screen or save frames to storage.
- Touch, pointer, keyboard, and text input are sent to the controlled computer through your server.
- Clipboard exchange is text-only and happens only when you use a clipboard action, paste, or copy. The app does not monitor the Android clipboard in the background.
- A view-only session does not send input.
- The controlled computer and the MeshCentral server may keep their own logs, clipboard history, or screen recordings according to their configuration.

## Microphone and voice

The app does not declare `RECORD_AUDIO` and contains no speech-recognition call.

Your installed Android keyboard may offer its own dictation feature. Its audio processing and privacy settings belong to that keyboard and its speech provider, not to this app. Text committed by the keyboard is handled like other remote input.

## App lock, biometrics, and screenshots

- The app lock is optional. When enabled, it can use a PIN, Android biometrics, or both.
- Biometric authentication is performed by the Android `BiometricPrompt`. The app receives only success or failure and does not access fingerprint or face templates.
- Biometric unlock requires Android 9 or newer; the PIN remains the fallback.
- **Block screenshots** is optional and off by default. When off, the remote desktop can appear in screenshots and in the recent-apps list. When on, Android blocks screenshots and hides the app in the recent-apps preview.

## Children and sensitive data

The app is a remote-administration tool. It is not directed at children. Treat remote desktops, clipboard contents, and captured credentials as sensitive; do not use the app on a device you do not control.

## Changes and contact

This note is versioned with the repository and should be updated when data handling changes. Privacy questions can be opened as a GitHub issue without personal data. Security problems should use the private channel in [SECURITY.md](SECURITY.md).
