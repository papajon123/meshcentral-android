# Quick unlock and compact settings — 0.9.5 (29)

Changes preserve the public application ID, signer, GitHub update source, update banner and support card.
PIN remains 4–12 digits; valid full PINs auto-submit, legacy encrypted records learn their length only
after success. Enabled biometrics appear automatically, cancellation allows PIN, stopped Activities
invalidate their pending system callbacks. PIN drafts are not saved in instance state.

The home screen has one profile/server label and one Settings entry. Device metadata is compact.
The app-lock setting is one row with a numeric editor: 0–86400 seconds, zero immediately after leaving
the last interactive window. Invalid input and cancellation preserve the stored value. Backup export
and import accept exactly the same range.

Local validation on 2026-10-29:
- `gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest`: exit 0.
- `python ci/check_translations.py`: 23 locales, 1508 strings and 3 plurals each, exit 0.
- API 35: 27 focused tests passed, including the real Activity/PiP return, legacy storage, automatic
  PIN, custom/invalid/zero delay and current device list.
- Independent source review found no confirmed defects and verified preservation of public-only features.
- The shared UI was inspected in light/dark themes and with font scale 1.5; the custom editor also passed
  the large-font interaction tests. The initial dialog focus timing failure was reproduced and fixed.

Release gates: the protected canonical MR and tag checks, GitHub's independent build/device/signing
workflow, anonymous download/hash/signer verification and real updater selection must pass before
publication is reported complete. Physical-phone fingerprint/face recognition is still unverified.
