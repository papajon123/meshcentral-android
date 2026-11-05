# Google Play signing transfer implementation plan

> Use the existing executing-plans workflow; the maintainer authorized delivery and accepted Play App Signing. No signing material is committed.

**Goal:** keep the existing public MeshPilot signing identity when importing it into Play App Signing.

**Architecture:** a manual, v0.10.1-tag-only workflow in the protected `release` environment encrypts the existing CI keystore key to the Google public key downloaded from this app's Console. The Java helper calls PEPK's public API directly, so passwords stay in environment-backed character arrays and never enter command-line arguments or PEPK's argument-error logger. Only an additional owner-encrypted CMS envelope and a public verification receipt leave the runner; the PEPK ZIP itself stays in the private temporary directory.

**Pinned inputs:** app certificate `5458084705359e0dfa9cbb83ed73a0d77c7758370cffe2a7d6b036e9275eeb2f`; Google encryption PEM SHA-256 `9d18948c65a80def2d9c17cc8749ead578d1c4809712adf83a3c263a26361480`; official PEPK JAR SHA-256 `aaccc0774b240aa5304bdad2a49865e92f229ca73209ecb6eaafe75dc858e24e`.

## Tasks

- [ ] Add `ci/PlaySigningTransfer.java`: arguments are public-key path, output ZIP, expected public-key hash and expected app-certificate hash. Secrets come only from `ANDROID_RELEASE_PASSWORD`; keystore path comes from `PLAY_TRANSFER_KEYSTORE`, alias from `PLAY_TRANSFER_KEY_ALIAS`. Validate both identities and prove the private key matches the certificate before encryption. Refuse an existing output. Report fixed stage names on failure, never exception details.
- [x] Add a pinned downloader and `ci/export-play-signing.sh`: private temporary keystore, cleanup trap, no shell tracing, only owner-encrypted CMS envelope/public receipt in `dist/`.
- [ ] Test with throwaway PKCS12 keys: successful encrypted ZIP and certificate; decryption of the fixture matches its original key; wrong recipient hash, wrong signer, wrong password and existing output all fail without output overwrite or secret disclosure.
- [ ] Add a manual `.github/workflows/play-signing-transfer.yml`, repository/exact-tag guards, protected release environment, no user inputs, pinned actions, mandatory fixture tests before secret-bearing export, one-day artifact retention.
- [ ] Independently review the complete change, run tests, and merge through a reviewed `mr/` branch. Do not run real export on a review branch.
- [ ] After the public snapshot contains this workflow, run it on reviewed tag `v0.10.1`, verify its receipt/certificate, upload only the encrypted ZIP to the selected Console app, and confirm Play's fingerprint matches the existing public identity before AAB upload.

## Review focus

Wrong recipient/key must fail closed. No raw private key, keystore, password or exception payload may be printed or uploaded. Dispatch inputs must not let a caller substitute an encryption recipient. Existing files must remain untouched on rejection. Google-generated alternative keys are unsuitable for the required cross-store identity.

The existing GitHub `release` environment only permits `v*` tags. Keep that policy intact; the manual transfer job is restricted to `v0.10.1` and verifies the commit is reachable from main.

Delivery boundary: this is a public GitHub repository. Treat the PEPK ZIP as sensitive regardless of unverified Google account-binding assumptions. Wrap it for the owner using the pinned public delivery certificate, RSA-OAEP SHA-256/MGF1 SHA-256 and AES-256-CBC CMS. The corresponding private RSA key is non-exportable in Windows CNG. Native Windows CMS did not support the tested GCM envelope; CBC interoperability was verified. Before CBC decryption, authenticate the CI run/tag/tree and verify the expected envelope hash obtained from that trusted run; a neighboring receipt alone is not an authenticity proof. Then validate inner ZIP hash/structure and the existing app certificate.
