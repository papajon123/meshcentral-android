# Compact session toolbar validation

Validated on 2026-10-29 using the existing Compose Material 3 theme and synthetic desktop fixtures.

## Behaviour

- Green means connected with a frame, amber means connecting/reconnecting, red means offline/failed.
  The indicator exposes the same state to accessibility; tapping reveals the actual reason and a retry
  or redraw action. Verbose diagnostics and keyboard-release instructions stay in those details.
- One or two physical monitors have direct 48 dp icon controls. The All protocol sentinel is counted
  separately. Three or more monitors use a menu. Physical IDs are preserved and disconnected switching
  is disabled. Traffic uses decimal `B/s`, `KB/s`, `MB/s` units independently of the locale.
- The lower toolbar keeps a 56 dp content row, with 48 dp touch targets and accessible labels.
  Mode, drag, font and traffic changes do not wrap a caption or expand it. Narrow windows can scroll.
- The previous locale check was corrected to assert both formatted counts without requiring the
  intentionally removed server-name duplicate. A deterministic concurrent-set fixture reproduced
  `NoSuchElementException`; iterator-based snapshots fixed that race in all concurrent close loops.

## Evidence and limits

- App and instrumentation APK builds, unit tests, lint and translation validation passed locally.
- The new toolbar/status/locale group passed on API 35; navigation passed after using the already
  validated platform/IME fixture and adapting its menu scenario to three physical monitors.
- API 26 exercised toolbar, locale, retained-list/session restoration and navigation with no failures.
  Tablet and physical edge-gesture cases are conditional skips, not claimed device acceptance.
- Light, dark, large-font, connecting and error screenshots were viewed and independently reviewed.
  Source review of both variants found no confirmed defects; public-only features remain unchanged.
- Artifacts are under `verification/session-toolbar-*.log` and `verification/session-toolbar-screens/`.
  Tests use synthetic data; there was no interaction with a customer's remote desktop.
- Real multi-monitor switching, TalkBack and physical-device gestures/biometrics still need phone
  acceptance. Release availability is reported only after signed artifacts and update sources verify.

The first branch pipeline also exposed a portable-test issue: Linux may report the intentional TCP
reset for an over-limit connection during `Socket.connect`, before the read checked by the fixture.
The test now accepts reset at either stage, still rejects connection refusal/clean EOF, and retains
the assertions for exactly one opened connection and no restart. Product forwarding code is unchanged.
