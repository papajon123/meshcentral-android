# Maintainer release procedure

The public application is `io.github.papajon123.meshcentral`. Debug builds use `.debug`.
The signing certificate is pinned in `ci/release-identity.json`; it is public information.
The encrypted private keystore and its password must remain outside this repository.

1. Increase `versionCode` and `versionName` in `app/build.gradle.kts`. Run the checks in
   [VALIDATION.md](VALIDATION.md), review the changes, and merge the intended source into `main`.
2. Update `.github/RELEASE_NOTES.md` and create a `vX.Y.Z` tag whose version matches the build.
3. The tag workflow reruns JVM, lint and device tests, then builds and verifies the signed APK.
   The GitHub `release` environment is restricted to `v*` tags and contains
   `ANDROID_RELEASE_KEYSTORE_B64` and `ANDROID_RELEASE_PASSWORD`. Fork PRs do not receive them.
4. Inspect the draft release, download the APK and independently run
   `python ci/verify_apk.py path/to.apk --version X.Y.Z`. Compare its SHA-256 to `SHA256SUMS`
   and `release-info.json`. Install on a dedicated test device before publishing the draft.
5. Keep previews marked as pre-releases until the documented compatibility checks pass.

The workflow checks that the tag belongs to `main` and rejects an unexpected package, version,
signing certificate, missing v2 signature or debug/test flags. A successful build alone is not
permission to change a signing identity or to claim unperformed device tests.

Keep at least one independent, encrypted recovery copy of the keystore **and** its password.
GitHub secrets are not a downloadable backup; Windows DPAPI files are tied to their Windows account.
Never commit recovery files, base64 keystores, passwords, full authentication logs or real device screenshots.
