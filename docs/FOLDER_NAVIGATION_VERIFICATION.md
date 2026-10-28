# Folder navigation verification — 2026-10-28

Candidate: 0.9.4 (28), based on fb0db98 (0.9.3). This addresses only the folder-navigation part of GitHub issue #14.

## Behaviour

- A short tap opens an actionable folder immediately; taps during selection toggle selection.
- More opens the existing folder-actions dialog without navigation. Its accessibility label includes the folder name. Drive roots do not show a redundant More button.
- Long press retains multi-selection. Existing unavailable-name explanations and busy/confirmation guards remain in place.

## Evidence

- Two independent Claude reviews of the initial and final diff found no confirmed regression. Follow-up observations about drive roots and the accessibility label were addressed.
- `gradlew testDebugUnitTest lintDebug assembleDebug`: passed; 859 JVM cases, 858 passed, one existing skip, zero failures/errors.
- `python ci/check_translations.py`: passed for 23 locales.
- `python -B -m unittest discover -s ci -p 'test_*.py'`: 27 passed.
- `FilesSelectionTest` and `PhoneFilesUiTest`: 5 passed on dedicated Gh35/API 35 and 5 passed on dedicated Gh26/API 26.
- New scenarios exercise AgentFiles and SFTP via synthetic BatchRelay: direct opening, separate actions without a path change, and two-folder selection without navigation. Existing phone-file restrictions and multi-file deletion confirmation also passed.
- The first SFTP UI run failed because its long explanatory text pushed the second row below a 320x640 viewport. The test now scrolls to that row and back to the selection count; the full targeted set passed afterward.
- Actual API 26 screenshots of folder contents, folder actions and selected folders were visually inspected.

## Limits and release gate

No physical-phone or live-server acceptance in this change. The live-navigation helper was updated for single-tap opening but that live suite was not run. Rapid repeated taps, busy-state taps and unavailable-folder taps were not separately instrumented. The unchanged dialog gates mutation actions on `entry.actionable`.

The candidate is not published by these local checks. Merge request review, main checks, protected release tag, GitHub signing/publication and download/signature verification remain required.
