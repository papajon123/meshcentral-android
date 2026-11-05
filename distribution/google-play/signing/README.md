# Import the existing app signing identity

This public encryption-recipient key was downloaded from MeshPilot's Play Console on 2026-11-04. It is not the application's private signing key.

- Google recipient PEM SHA-256: `9d18948c65a80def2d9c17cc8749ead578d1c4809712adf83a3c263a26361480`.
- Existing public app certificate SHA-256: `5458084705359e0dfa9cbb83ed73a0d77c7758370cffe2a7d6b036e9275eeb2f`.
- Pinned official PEPK binary SHA-256: `aaccc0774b240aa5304bdad2a49865e92f229ca73209ecb6eaafe75dc858e24e`.

The manual **Export encrypted Google Play signing key** workflow runs only on reviewed tag `v0.10.1`, in the existing protected `release` environment. Its `v*` tag-only deployment policy remains unchanged. There are no dispatch inputs that can substitute the recipient, source keystore or signer.

Before real secrets are used, throwaway-key tests exercise encryption/decryption, identity checks, redacted failures and non-overwrite behavior. The helper invokes PEPK's Java API rather than its CLI because the latter can echo its argument array on parse failure. The password is read from the existing secret environment only. The decoded PKCS12 stays in a private temporary runner directory and is removed by the shell trap.

The public artifact contains only `play-signing-transfer.p7m` and its public JSON receipt. The PEPK ZIP is additionally encrypted for the owner using RSA-OAEP SHA-256/MGF1 SHA-256 and AES-256-CBC CMS. Its private delivery key is non-exportable in Windows CNG; only the public delivery certificate is committed. Retention is one day. First authenticate the workflow run and confirm its tag/commit/tree; verify the envelope hash from that trusted run before decoding CBC. A receipt downloaded from an untrusted source is not sufficient. Use `ci/play-signing-delivery.ps1 -Action Open` with the local state and trusted expected envelope/ZIP hashes. It verifies the inner ZIP structure and app certificate before writing a new file.

In **App signing → Change key → Export and upload a key from Java Keystore**, upload the encrypted ZIP. After Google accepts it, check that the displayed application signing certificate matches the existing `545808…eeb2f` identity before uploading an AAB. Do not accept the unrelated Google-generated default key for this cross-store application.

Never place a raw keystore, password or unencrypted private key in repository files, workflow inputs, command arguments, logs or artifacts. Delete the temporary transfer artifact after its import has been verified. The signed AAB is a separate release artifact and must be retained for normal delivery.

References: [Google Play App Signing](https://support.google.com/googleplay/android-developer/answer/9842756?hl=en), [official PEPK source](https://www.gstatic.com/play-apps-publisher-rapid/signing-tool/prod/pepk-src.jar).

After import is confirmed, delete the exact transfer artifact, remove the task-owned CNG delivery key using the local state, and disable the one-time export workflow. Preserve the CNG key until verification is complete. The original app-signing key remains in protected CI and Google, and is never exported to this Windows machine in plaintext.
