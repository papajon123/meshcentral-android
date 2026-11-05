# Reviewer instructions — MeshPilot (Google Play)

**Status: not ready for Google submission.** The local-only Docker fixture in `demo/` has been built and tested: admin and restricted reviewer sign in, an invalid password is rejected, and exactly one synthetic agent is online. No public HTTPS review endpoint is published. Desktop, terminal, files and SSH/SFTP review scenarios still need end-to-end acceptance on the publicly reachable fixture. Do not use a maintainer's, customer's, or other user's MeshCentral server or computers for review.

## 1. What the reviewer is testing

MeshPilot is an independent Android client for a MeshCentral server. It is not a MeshCentral agent and does not install, host, or administer the server. The reviewer needs a working HTTPS MeshCentral instance and an account on it; the app has no preconfigured server, account, or demo content.

The planned Google Play build must not check GitHub for updates, must not offer external updates, and must not show the project's support/donation link. This is a build requirement, not yet a verified property of a published artifact.

## 2. Isolated review stand

Provide a dedicated environment that contains only synthetic data:

- A MeshCentral instance isolated from production, customers, and the maintainer's own devices. No public sign-up, no federation, and no real user data.
- HTTPS with a certificate trusted by ordinary Android devices. Do not require the reviewer to install a private CA or approve a self-signed key. The certificate must remain valid for the whole review window.
- A stable hostname and network path reachable by the reviewer without a VPN, client certificate, or undocumented IP allowlist. If an allowlist is unavoidable, document exactly how the reviewer is admitted.
- The exact MeshCentral version recorded in the review notes. If the optional `mobilecompanion` plugin is installed for notification or job testing, record its version too; do not imply that plugin features exist when the stand does not provide them.
- At least one online test computer with a synthetic name and a non-sensitive desktop. It must remain online during review.
- A synthetic folder tree for file tests and a throwaway SSH target reachable through MeshCentral, because the listing claims SSH/SFTP. No personal files, real host keys, or production credentials.
- Server logs limited to what is needed for review. The reviewer's IP and session metadata may be logged by the operator; say so in the review notes.

A reviewer may point the app at a MeshCentral server they control themselves. That is technically possible, but it is **not** a substitute for ready access: the submission still needs a prepared stand and account that the reviewer can use immediately.

## 3. Review account

- One dedicated account, used only for this review, with access to the synthetic computer and test folders.
- Enough rights to open the desktop, terminal, files, and SSH/SFTP, which the listing claims. Do not grant server-administration rights that are not needed for the scenario.
- 2FA must be either disabled for this account or completable by the reviewer with a documented, non-personal method. Do not require a code sent to a private phone or mailbox.
- Credentials must be delivered only through the Play Console **App access** instructions or another private review channel. Never commit them to this repository, the listing, or this file.
- Rotate or revoke the account and any SSH credentials after review; delete synthetic data and stop the instance when it is no longer needed.

## 4. Access package to prepare

Before submission, supply in Play Console:

1. The HTTPS server address.
2. The review account name and password.
3. Any required 2FA procedure that the reviewer can complete.
4. The synthetic computer name and the test folder path.
5. A short note that the stand is isolated and contains no customer data.
6. The exact app artifact/track under review.

Keep reviewer credentials out of the repository. The public support contact is recorded separately in `app-details.json`.

## 5. Review scenarios

Run these on the Play-delivered build, not on a locally built debug APK.

1. **Install and first launch.** Install from the review track, open the app, and confirm that no server or account is preconfigured. Add the review server over HTTPS.
2. **Sign in.** Use the review account. If 2FA is enabled, complete the documented test flow. Confirm that an invalid password fails without exposing secrets.
3. **Device list.** Find the synthetic computer, use search and favorites, and open its details. Confirm that only the test device is visible.
4. **Remote desktop.** Open the desktop, verify that the synthetic screen is shown, try touch/trackpad and keyboard input, and switch monitors if the test host has more than one. Close the session.
5. **Terminal.** Open the terminal, print a fixed test string, verify the output, and close the session. Do not run destructive commands.
6. **Files.** Browse the synthetic folder, upload a generated text file, download it, compare its SHA-256, then rename and delete only the test copy.
7. **SSH/SFTP (claimed in the listing).** Connect to the throwaway SSH target, run a harmless command, list and transfer a synthetic file. If the stand has no SSH target, either provide one before review or remove the SSH/SFTP claim from the listing; do not substitute a user system.
8. **Profiles.** Add a second profile for the same isolated stand, switch between profiles, and delete the added profile. Confirm that sessions close as expected.
9. **App lock.** Set a test PIN, background and foreground the app, lock it, and unlock it. Test biometrics only if the review device supports them; the PIN remains the fallback.
10. **Notifications (only if the optional plugin is installed).** Enable notifications, trigger a synthetic event, and verify the generic notification body. If the plugin is absent, skip this scenario and do not claim it works.
11. **Play-build checks.** In Settings, confirm that no GitHub update section and no support/donation link are present. Confirm that no external update page opens. With a network capture, confirm that the app contacts only the review server origin and not `api.github.com`, GitHub release assets, or an external donation site.
12. **Resilience.** Rotate the device, background the app, interrupt connectivity, and reopen. Confirm that the app reports failure instead of showing a stale live session as success.

## 6. Evidence to keep

- The artifact identifier, version, and hash tested.
- The exact MeshCentral and plugin versions, if any.
- Pass/fail for each scenario, with the observed limitation rather than a guess.
- A network-capture summary showing the contacted origins.
- Confirmation that no real user server, computer, or personal data was used.

## 7. Known gaps before this can be submitted

- The local fixture is verified only on loopback. A public HTTPS endpoint, trusted certificate and Google reviewer access are still required.
- Local Play-build tests verify the disabled external updater and hidden support link; the Play-delivered signed build is not yet available.
- The owner selected `papajon1234@gmail.com` as the public support contact; reviewer access is separate.
- Local test credentials exist only in the owned Docker runtime. They must not be reused as public review credentials or committed to this repository.
- This document is not a substitute for the Play Console App access declaration.
