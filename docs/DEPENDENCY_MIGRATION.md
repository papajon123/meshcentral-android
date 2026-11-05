# Dependency migration (Dependabot PR 15)

Status: locally built and independently reviewed on 2026-11-04; not merged or released.

## Scope

- Branch `mr/gradle-dependencies`, base commit `90f01f9`.
- Dependency set from Dependabot commit `7c16667` (12 updates): AGP 9.5.0, Kotlin/Compose plugin
  2.4.21, zxing 3.5.4, Compose BOM 2026.09.00, WorkManager 2.12.0, OkHttp/okhttp-tls/mockwebserver
  5.5.1, coroutines 1.11.0, org.json 20260814, Gradle wrapper 9.8.2.
- The Kotlin Android Gradle plugin is removed; the Compose compiler plugin remains.
- `kotlinOptions` is replaced by `kotlin.compilerOptions` with JVM target 17.
- `compileSdk = 37`, `buildToolsVersion = "36.0.0"`, `targetSdk = 36`.

## Deliberately excluded

- biometric 1.1.0, Direct/Play product flavors, the external-update build flag, private package
  changes, version/versionCode bumps, feedback and Play features.
- The remote keyboard map option in `SessionSheets`; navigation/auth/consent changes in
  `MeshCentralApp`.
- Release scripts and GitHub workflows outside `android-setup`; GitLab checks only receive SDK/cache updates.

## Source compatibility adjustments

- `ServerTrust.serverCertificateFailure()` walks causes and suppressed exceptions with identity and
  size bounds; `NotificationHttpClient` re-wraps rejected certificates as a TLS handshake failure.
- Tests use the shared `hasSameJsonContent` helper (Android JSON API, numeric equality across
  number types) and `JSONObject.keys()` instead of `keySet()`/`similar()`, which are unavailable in the Android compile stubs.
- New regression test: `ServerTrustFailureTraversalTest`.
- Lint-only Compose changes: hoisted `stringResource`/`LocalResources`/`LocalLocale` values in
  `BackupScreen`, `PortForwardScreen`, `DeviceAdminDialogs`, `Parity2InspectorContent`,
  `TaskToolsScreen` and `TaskToolsScreenTest`; clipboard label capture in `SessionSheets`;
  keyed `collectAsState` for file sessions in `MeshCentralApp`.
- `DevicesScreen` restores the `device-connect-<nodeId>` test tag expected by
  `PlatformNavigationTest`.

## Verification

- `git diff --check` and a search of the diff for the agreed forbidden identifiers (language,
  consent, remote keyboard map, external updates, Play privacy).
- JDK 17, Android SDK platform 37.0, build-tools 36.0.0 (35.0.0 kept for APK verification):
  `./gradlew testDebugUnitTest lintDebug assembleDebug compileDebugAndroidTestKotlin`.
- `connectedDebugAndroidTest` on a dedicated emulator for the UI/test-tag changes.
- CI: GitHub `android-setup` installs platform 37.0 and build-tools 36.0.0 plus 35.0.0; GitLab
  installs 37.0/36.0.0 and uses the `android-37` cache keys while keeping the existing commands.

Local command: testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest. Exit 0;
881 unit tests, 0 failures, 0 errors, 1 skipped. Lint and both APK builds succeeded.
Independent source review found no confirmed defects in TLS handling, JSON compatibility or build changes.
Device tests and remote CI for this isolated branch have not run; the feature branch runs its own device acceptance.
