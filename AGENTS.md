# Project Rules

This repository is a native Android client for controlling MeshCentral from Android.

## Build

- Use PowerShell on Windows.
- Use JDK 17 and an Android SDK with platform/build-tools 35. Set `JAVA_HOME` and `ANDROID_HOME` to your installations.
- Validate changes with `./gradlew testDebugUnitTest lintDebug assembleDebug`; run `connectedDebugAndroidTest` on a dedicated emulator for UI changes.

## Security

- Do not commit credentials, tokens, passwords, keystores, `local.properties`, APK/AAB outputs, or generated private files.
- Credentials and session material must be stored only through Android Keystore-backed storage.
- Do not log credentials, cookies, PINs, session IDs, tokens, or full WebView URLs that may contain secrets.

## MeshCentral Integration

- This app is a mobile control client, not a MeshCentral agent.
- Users provide their own HTTPS MeshCentral endpoint. Use reserved example domains and synthetic device data in tests and documentation.
- Do not invent MeshCentral APIs. Use WebView fallback unless a native API/WebSocket path has been audited.
- The public client does not administer or deploy MeshCentral servers.

## Language

- User-facing project docs can be English for future open-source readiness.
- Chat with the user in Russian unless the user explicitly asks otherwise.
