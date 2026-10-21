# MeshCentral Mobile

[Русская инструкция](README.ru.md)

An independent native Android client for an existing [MeshCentral](https://github.com/Ylianst/MeshCentral) server. It lets you sign in to your own server, see your computers, and open a Mesh Agent remote desktop from a phone or tablet.

This is **not** an official MeshCentral application, **not** a MeshCentral agent, and **not** affiliated with the MeshCentral project. You need your own MeshCentral deployment and an account on it.

> **Status: 0.4.0 (versionCode 8), preliminary.** This release has not yet been validated on a physical device or against third-party servers. The interface is available in 22 languages (23 locale variants), with English as the default; translations have not yet been proofread by native speakers of every language. See [Known limitations](docs/KNOWN_LIMITATIONS.md).

## Download and install

- APK: [GitHub Releases](https://github.com/papajon123/meshcentral-android/releases).
- Android 8.0 (API 26) or newer.
- Open the downloaded APK. Android will ask you to allow installs from the browser or file manager you used: enable **Install unknown apps** for that app, then confirm the installation.
- Updates are manual: install a newer APK from Releases. Android accepts an update only when the package name (`io.github.papajon123.meshcentral`), the signing key, and a higher `versionCode` match. A build made with your own key will not install over a release APK.
- Version 0.4.0 keeps the package name and signing key of the public 0.3.0 release, so it installs over that release and preserves local application data. The update does not force a new sign-in; your server may still require authentication. Earlier private builds remain separate applications with their own sign-in. Debug builds have an additional `.debug` application ID suffix.

The public signing certificate SHA-256 is
`5458084705359e0dfa9cbb83ed73a0d77c7758370cffe2a7d6b036e9275eeb2f`.
Each release includes APK checksums and `release-info.json`. Maintainers should follow
[the release procedure](docs/RELEASING.md).

## Languages

The 0.4.0 interface is available in 22 languages (23 locale variants), with English as the default:

English, русский, 简体中文, 繁體中文, español, français, português, Deutsch, العربية, हिन्दी, বাংলা, اردو, 日本語, 한국어, Bahasa Indonesia, Türkçe, Tiếng Việt, ไทย, italiano, فارسی, தமிழ், తెలుగు, मराठी.

Simplified and Traditional Chinese are counted as one language and two locale variants. Each variant translates the same 389 strings; the application name is shared.

- Android 8–12: the app follows the system language automatically.
- Android 13 and newer: the app also appears in the system per-app language settings (usually **Settings → System → Languages & input → App languages**, or the app's App info page; the exact path varies by device). This is the standard Android setting; the app has no separate in-app language picker.
- If none of the supported languages matches, the app falls back to English.
- Arabic, Persian, and Urdu use a right-to-left layout; the navigation arrow is mirrored.
- Server messages, device names, and physical key captions (`Ctrl`, `F1`, …) are not translated.
- Translations were produced with DeepSeek and have not yet been proofread by native speakers of every language. The app does not use online translation or geolocation.

## Your own server

<p>
  <img src="docs/screenshots/setup.png" width="260" alt="First launch with an empty server address">
  <img src="docs/screenshots/sign-in.png" width="260" alt="Sign-in screen using a reserved example server">
</p>

- Enter the full MeshCentral URL manually, for example `https://meshcentral.example.com/`. No server is preconfigured.
- Use HTTPS with a certificate valid for that host name. The release build validates certificates against the platform trust store.
- Use the final server URL. Authenticated requests do not follow HTTP redirects. Domain paths are preserved in control and viewer URLs; live deployments with reverse-proxy subpaths still need compatibility testing.
- Plain HTTP and self-signed certificates are available only in debug builds, for a local test server.
- The account needs the remote-control right for the computer group; view-only sessions are supported.

## Features

- Native Mesh Agent desktop over the MeshCentral relay, with multi-monitor switching, reconnect, and quality presets.
- Direct-touch and trackpad pointer modes, pinch zoom up to 16x, bounded panning, and a client-drawn pointer.
- On-screen keyboard, shortcut grid, Unicode text input, and view-only sessions that do not send input.
- Text clipboard exchange in both directions, subject to MeshCentral and Mesh Agent limits.
- Two-factor sign-in by e-mail, SMS, or push, as offered by the server.
- Credentials and PIN protected with Android Keystore AES-GCM; optional PIN or biometric app lock and optional screenshot blocking.
- Saved credentials and in-progress sign-in fields are isolated when the server address changes.
- Interface in 22 languages (23 locale variants) with English as the default, automatic system-language selection, the Android 13+ per-app language setting, and right-to-left layouts for Arabic, Persian, and Urdu.
- No ads, analytics, telemetry, or project-operated backend. The 0.4.0 release has no in-app store, self-updater, microphone access, or APK installation flow.

## Not supported

Intel AMT KVM, Web-RDP, Web-VNC, WebRTC, terminal sessions, file management, non-agent devices, browser SSO, and clipboard images are not implemented. The raw WebView fallback in Settings is a troubleshooting aid, not the supported desktop path. See [Known limitations](docs/KNOWN_LIMITATIONS.md).

## Build

Requirements: JDK 17, Android SDK platform 35, build-tools 35.0.0. The Gradle wrapper downloads Gradle 8.10.2.

Windows PowerShell (`JAVA_HOME` and `ANDROID_HOME` must point at your installations; replace the JDK placeholder with your own JDK 17 path):

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

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Tests

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

On Windows use `.\gradlew.bat` with the same tasks.

Instrumented tests need a dedicated emulator or test device; use reserved example domains and synthetic device data:

```bash
./gradlew connectedDebugAndroidTest
```

Translation catalogs have their own local checks:

```bash
python3 ci/check_translations.py
python3 -m unittest discover -s ci -p 'test_*.py'
```

`ci/check_translations.py` verifies that every locale has the same 389 strings, that format placeholders match, and that no Russian text leaks into another locale. The unittest command runs the checker's own tests.

Final test counts and release evidence are recorded in [docs/VALIDATION.md](docs/VALIDATION.md) by the maintainer.

## Documentation

- [Architecture](docs/ARCHITECTURE.md)
- [MeshCentral protocol notes](docs/PROTOCOL.md)
- [Interface](docs/UI.md)
- [Validation](docs/VALIDATION.md)
- [Known limitations](docs/KNOWN_LIMITATIONS.md)
- [Privacy](PRIVACY.md)
- [Security policy](SECURITY.md)
- [Contributing](CONTRIBUTING.md)
- [Changelog](CHANGELOG.md)

## Reporting bugs

Open a [GitHub issue](https://github.com/papajon123/meshcentral-android/issues) with the app version, Android version, MeshCentral version, steps to reproduce, and sanitized logs. Do **not** post credentials, session cookies, real server names, or screenshots of a working desktop. Security problems go through the private channel in [SECURITY.md](SECURITY.md).

## License

Apache License 2.0. See [LICENSE](LICENSE), [NOTICE](NOTICE), and [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## Кратко по-русски

Независимый нативный Android-клиент для вашего существующего сервера MeshCentral. Это не агент Android и не официальное приложение MeshCentral; нужны собственный сервер и учётная запись на нём.

- Скачайте APK в разделе [Releases](https://github.com/papajon123/meshcentral-android/releases).
- Разрешите установку из выбранного приложения (браузера или файлового менеджера) и установите APK.
- Укажите адрес своего сервера по HTTPS с действующим сертификатом и войдите в MeshCentral.
- Обновления ставятся вручную из Releases; должны совпадать пакет и подпись, а `versionCode` — увеличиваться.
- 0.4.0 ставится поверх публичного 0.3.0 без повторного входа; более ранние частные сборки остаются отдельными приложениями.
- Интерфейс переведён на 22 языка (23 варианта), по умолчанию английский. На Android 8–12 приложение следует языку системы, на Android 13+ язык выбирается в настройках приложения. Подробнее — в [README.ru.md](README.ru.md).
- Выпуск 0.4.0 предварительный: на физических устройствах и чужих серверах он ещё не проверялся.
