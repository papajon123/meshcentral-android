# 0.8.0 release candidate validation

Date: 2026-10-26. Public package: `io.github.papajon123.meshcentral`; candidate version
`0.8.0`, code `19`. This report records local candidate preparation, not publication or
acceptance of a signed APK. No push, merge request, release tag or publication was performed.

## Checks

Initial source base: `15a098c`; final checked upstream base: **`737b2fe`**, including Windows
changes `179b53c` and `737b2fe`. Release preparation changes only version metadata and documents.
The CHANGELOG rebase conflict was resolved by retaining all prior facts and the four Windows additions.

| Check | Result |
|---|---|
| `gradlew.bat testDebugUnitTest lintDebug assembleDebug compileReleaseKotlin` | Passed |
| JVM XML | 494 cases: 493 passed, 1 skipped, no failures/errors; 73 suites |
| Android Lint XML | No errors; 79 warnings and 7 informational findings |
| `python ci/check_translations.py` | 23 locales, 1161 strings and 1 plurals each; 1 shared string |
| `python -B -m unittest discover -s ci -p "test_*.py"` | 25 passed |
| `python ci/publish_github_snapshot.py --check-only --tag v0.8.0` | Blocked: no source tag `v0.8.0`; no tag was created |

The optional `GitHubReleaseSourceLiveTest` accounts for the single JVM skip. Ordinary tests do
not contact GitHub for update metadata. The snapshot CLI requires an existing source tag at
`prepare_snapshot`; it stops at tag resolution before constructing a publication plan. A check-only
invocation is not a successful snapshot validation when that precondition is missing.

Environment: the JBR installation prescribed by the assignment reports OpenJDK **21.0.8**;
Kotlin/Java target compatibility stays **17**. Android SDK platform/build-tools **35**.
Ignored local evidence: `verification/release-0.8.0/gradle-final.log`, `translations-final.log`,
`python-tests-final.log`, `snapshot-check.log`; XML under `app/build/test-results/testDebugUnitTest/`
and `app/build/reports/lint-results-debug.xml`. Generated APKs and logs are not committed.

No new UI implementation was made in release preparation, so no emulator was started and
`connectedDebugAndroidTest` was not rerun here. Earlier instrumentation and live runs below
retain their own exact scope, versions and limitations.

## Scope and evidence

The [comparison](COMPARISON.md) pairs every affirmative client feature with production code and
its relevant report. The following candidate JVM suites were executed, not merely discovered:

| Area | Candidate test evidence | Meaning and limit |
|---|---|---|
| TLS and notification pins | `ServerTrustTest` 25, `ServerTlsTest` 16, `NotificationTlsTest` 5 | Synthetic certificates/transports and refusal policy; not a physical-device trust-change acceptance |
| Remember device | `RememberedDeviceStoreTest` 14, `RememberDeviceProtocolTest` 5, `RememberDeviceCookieJarTest` 1 | Storage policy, account/endpoint/trust scope and protocol; no fresh live server-cookie acceptance |
| Phone capability/input/actions | `PhoneRemoteInputTest` 3, `PhoneInputPacketsTest` 9, `PhoneCommandsTest` 6 | Capability, protocol and permission tests; not proof of a public input-capable agent or hardware effects |
| Update checks | `GitHubReleaseSourceTest` 22, `UpdatePolicyTest` 16 | Synthetic metadata and policy; no signed install/update or browser/device acceptance |
| Profiles/shortcuts/favorites | `ProfileCatalogTest` 3, `ProfileMigration08Test` 5, `CustomShortcutsTest` 9, `FavoriteCommandTest` 2 | Model/storage rules; physical gestures and biometrics remain separate |
| Windows additions | `JsonNullRegressionTest` 3, `BiosDateTest` 2, `RunCommandTest` 5, `TerminalControllerTest` 14 | Null, date, explicit shell and literal insertion regressions; not a fresh live Windows run |
| History/commands/sessions | `DeviceHistoryParserTest` 7, `RunCommandTest` 5, `SessionRegistryTest` 1 | Local protocol/state checks; an ACK is never evidence of remote execution |

Static release review keeps the public package, signing configuration and Apache 2.0 license
unchanged. No ads/analytics dependencies or server deployment are introduced by this change.
The changelog preserves all 50 original Unreleased bullets plus four Windows additions, regrouped under Phones, Computers,
Sign-in and security, Terminal and files, and Quality. Superseded notification TLS and blanket
live-pending statements were reconciled with current code and the scoped reports below.

An independent Astra review of the initial release-document patch found no substantial or minor
findings. After synchronization, the added Windows wording was checked against production code and
[Windows evidence](WINDOWS_POLISH.md), and the full required local checks were rerun. DeepSeek
collected the initial source/report map; the release author checked the material claims.
Short store changelogs contain **465 English** and **488 Russian** characters, including the final newline.
Local Markdown targets and `git diff --check` passed; generated debug APK metadata is 0.8.0/code 19.

## Existing live and Android evidence

- [Phone file fixes](PHONE_FILES_FIXES_VALIDATION.md): official agent 1.0.23, verified downloads and
  uploads (8,388,633 bytes) with independent SHA-256 checks, duplicate-name UI and a real share queue;
  pinned server key independently matched. The final successful large transfer used ADB reverse;
  the other emulator network path failed. This is not a universal physical-phone/network result.
- [Original phone checks](PHONES_VERIFICATION.md): synthetic phone UI, battery, permissions and
  file protocol checks. Its original input-disabled/live-files-pending wording predates the
  conditional input feature and later live file acceptance.
- [Parity fixes](PARITY_FIXES_VALIDATION.md): live Linux CPU/RAM, seven-day history and reviewed
  command output. No live offline Wake or destructive power/removal test was performed.
- [M5](M5_PARITY_VALIDATION.md) and [M5b](M5B_PARITY_VALIDATION.md): scoped local and emulator checks
  for computer tools, shortcuts, inventory, commands, events and safety states.
- [Earlier validation](VALIDATION.md) and [implementation report](ROADMAP_IMPLEMENTATION.md):
  scoped Linux Agent/SSH/SFTP, plugin, session/share and API 26/35 checks. They are historical,
  not a fresh run of every 0.8.0 flow.
- [Windows fixes](WINDOWS_POLISH.md): local PowerShell 5+ UTF-8 envelope probes, synthetic null/date
  and terminal-grid regression evidence. The mode cannot repair agent-side encoding loss or force
  arbitrary programs to emit UTF-8; the original live Windows favorites scenario still needs acceptance.
- [Inspector report](INSPECTOR_VERIFICATION.md): Windows service/process reads through Companion
  1.1.2. This does not establish Windows Security/AV or service mutation acceptance.

## Remaining acceptance and release gates

- Modified Android input agent support is **not upstream**. The official agent is view-only;
  conditional input still requires accessibility, current rights and the phone owner's consent.
- Physical-phone installation/update, hardware input/biometrics, phone hardware effects, TalkBack,
  battery/Doze/OEM behavior and broad OS/agent coverage have not been accepted here.
- Live remembered-device behavior and the full trust replacement/Forget/unlock matrix remain
  separate checks. MeshCentral 1.3.1 cookie revocation limits are documented in [Security](../SECURITY.md).
- Power/uninstall/remove execution and Windows/systemd service mutations remain unverified.
- The updater's [manual Android acceptance](UPDATE_CHECK.md#manual-android-acceptance-not-performed-in-m23)
  and a signed upgrade from the prior public APK remain pending.
- Maintainer review, merge, tag creation, signed-release CI/device checks and explicit publication
  approval are separate future steps under [Releasing](RELEASING.md). None is authorized by this report.
