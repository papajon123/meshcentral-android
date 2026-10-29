# Monitor selection after locking the phone

The user's selected physical display or All (65535) is retained independently from agent reports.
Locking, screen-off, foreground resumption and relay reconnection reapply that selection. The screen
no longer chooses the first monitor when its composition is recreated. Non-secret preferences are
isolated by profile/device using the existing hashed DesktopPreferences keys and survive app restart.
If a physical monitor disappears, a valid available display is used. Phone-agent defaults and the
existing protocol-before-binary and lock/input restrictions are preserved.

The original failure was reproduced by
`PlatformNavigationTest#displaySelectionSurvivesLockReconnectAndActivityRecreation` before the fix
(`verification/retain-red.log`). After the fix the same test covers display 2 and All, power-key
screen-off/on, app lock, a replacement relay, PIN unlock and Activity recreation. It passed on API35
and API26 together with preference isolation, resume, lock and protocol-order integration tests.

New unit cases cover default selection, retained intent, All, missing monitors and unknown lists.
Build, lint, unit tests and both release pipelines must pass before publication. Availability is
verified against signed artifacts and actual updater selection separately from phone installation.
Physical-phone acceptance remains with the owner; these tests use local synthetic KVM fixtures.
