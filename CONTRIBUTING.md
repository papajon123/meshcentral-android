# Contributing

This is a small, preliminary Android project. Small, focused changes with clear evidence are easiest to review. Open an issue before a large refactor or a protocol change.

## Before you start

- Read [ARCHITECTURE.md](docs/ARCHITECTURE.md), [PROTOCOL.md](docs/PROTOCOL.md), and [KNOWN_LIMITATIONS.md](docs/KNOWN_LIMITATIONS.md).
- Do not invent MeshCentral APIs. If a protocol detail changes, add wire evidence and update `docs/PROTOCOL.md`.
- Never commit credentials, session cookies, tokens, keystores, `local.properties`, APK/AAB outputs, or logs from a real server.
- Do not use a production MeshCentral account or a server you do not own for testing.

## Build

Requirements: JDK 17, Android SDK platform 35, build-tools 35.0.0.

Windows PowerShell:

```powershell
$env:JAVA_HOME = "C:\path\to\jdk-17"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat assembleDebug
```

Linux or macOS:

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME="$HOME/Android/Sdk"
./gradlew assembleDebug
```

## Checks

Run all three before opening a pull request:

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

UI changes also need instrumented tests:

```bash
./gradlew connectedDebugAndroidTest
```

Run `connectedDebugAndroidTest` only on a dedicated emulator or test device that contains no personal data. Use reserved example domains such as `https://meshcentral.example.com/` and synthetic device names; never a real account, a production server, or a third-party server without explicit permission.

Unit tests must stay offline and deterministic. They cover control-message parsing, KVM framing, input encoding, clipboard message construction, `x-meshauth` construction, and viewport mapping. Instrumented tests use a local mock or synthetic data.

## Code guidelines

- Kotlin and Jetpack Compose; keep protocol and viewport logic free of Android types where possible so it remains unit-testable.
- Keep credentials and PIN material in `SecureVault` (Android Keystore, AES-GCM). Do not add plaintext storage or custom cryptography.
- Do not log credentials, cookies, PINs, tokens, relay identifiers, node URLs, clipboard contents, or captured screen data.
- Do not add permissions, analytics, advertising, or network endpoints without a documented need and a privacy review.
- User-facing strings belong in `app/src/main/res/values/strings.xml`; do not hard-code new UI strings in composables.
- Do not claim SSO support, self-signed certificates in release builds, or compatibility with every MeshCentral version. Test and document only what the code actually supports.

## Pull requests

- Keep the change focused; describe the user-visible behaviour and the reason for it.
- Include the commands you ran and their result. Screenshots of synthetic data are welcome; screenshots of a real desktop are not.
- Do not bump `versionCode`/`versionName`, change signing configuration, or publish a release; the maintainer handles versioning and signing.
- Do not commit generated APKs, build output, keystores, or local SDK paths.

## Security reports

Do not open a public issue with vulnerability details. Use the private advisory channel in [SECURITY.md](SECURITY.md).

## License

The project is distributed under the Apache License 2.0; see [LICENSE](LICENSE), [NOTICE](NOTICE), and [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). Contributions are accepted under the same license.
