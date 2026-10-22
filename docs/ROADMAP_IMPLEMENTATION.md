# Roadmap implementation — 0.7.0 (code 15)

The implementation of roadmap items 1–8 is in this release candidate. Item 9 is a researched transport decision; item 10 uses an optional plugin, without a permanent MeshCentral fork.

- Discovery: Unicode AND search and 20 recent node ids per account/profile.
- Profiles: independent credentials, favourites, recents and notification tokens; default-profile migration; global app PIN preserved.
- Workspace: two explicit sessions, one desktop maximum; selection restored after process death without replaying remote input.
- Share: 20 content URIs maximum; destination/overwrite confirmation; foreground queue, cancel, explicit retry from the beginning, SHA-256 readback. Re-share after process death.
- System: cached system/network data, capability/rights-aware power controls. An ACK is not treated as execution.
- Companion: fixed diagnostic/service/process templates, confirmation before service mutations, persistent jobs and redacted reports. Requires optional mobilecompanion 1.1.0 on MeshCentral 1.3.0 and remote-command right 0x20000.
- Events: optional WorkManager polling (15-minute best effort), quiet hours, scoped HTTPS read token, deduplication and correct profile routing. No FCM; no guaranteed instant push.

## Implementation map

`discovery/`, `model/ServerProfile.kt`, `settings/SettingsRepository.kt`, `security/SecureVault.kt`, `workspace/`, `mesh/control/`, `transfer/`, `management/`, `companion/`, `notifications/`, `ui/screens/`, and `server/mobile-companion/` contain the implementation. Application-lifetime transports are owned by `ConnectionWorkspace`, not a screen.

## Checks actually run

- Original app: Gradle testDebugUnitTest/lintDebug/assembleDebug/assembleDebugAndroidTest passed; 248 JVM tests, 0 failures; lint 0 errors, 78 warnings.
- API 35 original: 141 cases, 137 passed, 4 assumptions skipped (opt-in galleries/legacy WebView path); no failures.
- Public app: same Gradle checks passed; API 35 instrumentation 139 cases, 135 passed, 4 assumptions skipped; no failures.
- Node plugin: 44 tests passed. Real isolated MeshCentral 1.3.0/Linux Agent verified jobs, exit codes, operation dedupe, results after control disconnect, file retry/readback, SSH/SFTP, rights 24 denial and 0x20000 allowance, token HTTP/read/revoke.
- Public API 26: 130 passed, 9 compatibility/opt-in assumptions skipped, 0 failures (139 cases). The legacy WebView error path and real system save dialog were verified. The five affected modern API 35 scenarios also passed.
- 105 roadmap screenshots across seven configurations plus nine international device/tool captures passed. Independent review inspected 12 full PNGs; a memory-label placeholder was fixed and recaptured. Documentation screenshots are in `docs/images/roadmap/`.
- Signed publication and in-place upgrade are verified separately from these local tests.

## Limits and follow-up acceptance

A physical phone, TalkBack, OEM/Doze timing, battery consumption, real Windows service actions and Linux systemd service mutations have not been accepted. The Linux test agent has no systemd. No performance improvement is claimed from an emulator. Running server jobs cannot be cancelled. History is bounded to 40 jobs globally, 7 days and 64 KiB output per job. No multi-server MeshCentral cluster support.

## Build and inspect

Use JDK 17-compatible tooling and Android SDK 35. Run `gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest`. Dedicated-device tests use AndroidJUnitRunner; live acceptance requires the isolated fixture. Opt-in gallery: `-e roadmapGallery true -e galleryLocale en -e galleryTheme light -e class ru.papajon.meshcentralmobile.workspace.RoadmapGalleryTest`. Gallery screenshots render production Composables with synthetic data; they do not prove remote execution.
