# Device inspector: failure and recovery

Version 0.7.2 keeps the existing Android interface contract in [UI.md](UI.md)
and the `MeshTheme` color, spacing, shape and typography tokens. The target is
an administrator checking a device from a phone or tablet.

## Behavior

- Services and Processes show the known request or job failure: missing rights,
  offline agent, active job, full history, timeout, server restart, invalid result
  or failed command. Server messages and exception details are never rendered.
- Error messages reuse the Companion resources across all supported locales;
  the Help and System screens share the same mapping.
- Refresh loads the currently selected inspector tab. The error action refreshes
  data; it never repeats a service start, stop or restart. Help opens the existing
  companion surface, whose initial request reads capabilities and job history.
- Starting a request clears the previous error and stale completion notice.
  Cancellation caused by screen/workspace lifecycle propagates normally.
- Notices, errors and pending status scroll with the selected tab's content, so
  a long localized message cannot make the device list unreachable at large font sizes.
- A missing optional-plugin response does not block reading standard system
  information. Service-management controls still require capability and rights.

## Acceptance contract

The job runner starts once, then polls only that job. Every response must match
the node, job and template. Success requires completed status, exit code zero
and a parsed result. Timeout, connection loss and invalid responses do not
trigger an automatic restart of the command.

JVM regressions exercise the real Companion parser/client through its request
interface. Compose scenarios verify updated visible rows, selected-tab refresh,
error actions, disabled actions during loading and unchanged confirmation of
service mutations. Visual checks cover English, Russian and Chinese, light/dark
themes and enlarged fonts on dedicated Android emulators. Record actual results
in the release report; an APK build alone is not visual acceptance.
