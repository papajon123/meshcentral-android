#!/usr/bin/env bash
set -euo pipefail
set +x
umask 077
test "${GITHUB_REPOSITORY:-}" = papajon123/meshcentral-android
test "${GITHUB_REF:-}" = refs/tags/v0.10.1
test "${GITHUB_EVENT_NAME:-}" = workflow_dispatch
: "${ANDROID_RELEASE_KEYSTORE_B64:?Missing release keystore}"
: "${ANDROID_RELEASE_PASSWORD:?Missing release password}"
delivery=distribution/google-play/signing/owner-delivery-certificate.pem
delivery_sha=aaf9d76312e45b9a262c56db554a609c4bde0f1c7824f03d5a8240a3c67ea9b7
test "$(sha256sum "$delivery" | cut -d ' ' -f 1)" = "$delivery_sha"
test ! -e dist/play-signing-transfer.p7m
test ! -e dist/play-signing-transfer.json
test ! -L dist/play-signing-transfer.p7m
test ! -L dist/play-signing-transfer.json
work=$(mktemp -d "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/meshpilot-play-signing.XXXXXX")
trap 'rm -rf -- "$work"' EXIT
printf '%s' "$ANDROID_RELEASE_KEYSTORE_B64" | base64 --decode > "$work/signing.p12"
unset ANDROID_RELEASE_KEYSTORE_B64
export PLAY_TRANSFER_KEYSTORE="$work/signing.p12"
export PLAY_TRANSFER_KEY_ALIAS=meshcentral_release
recipient=distribution/google-play/signing/google-encryption-public-key.pem
recipient_sha=9d18948c65a80def2d9c17cc8749ead578d1c4809712adf83a3c263a26361480
signer=5458084705359e0dfa9cbb83ed73a0d77c7758370cffe2a7d6b036e9275eeb2f
test "$(sha256sum .tools/pepk.jar | cut -d ' ' -f 1)" = aaccc0774b240aa5304bdad2a49865e92f229ca73209ecb6eaafe75dc858e24e
mkdir -p "$work/classes" dist
javac -proc:none --release 17 -cp .tools/pepk.jar -d "$work/classes" ci/PlaySigningTransfer.java
java -cp "$work/classes:.tools/pepk.jar" PlaySigningTransfer "$recipient" "$work/play-signing-transfer.zip" "$recipient_sha" "$signer"
rm -f -- "$PLAY_TRANSFER_KEYSTORE"
unset ANDROID_RELEASE_PASSWORD PLAY_TRANSFER_KEYSTORE PLAY_TRANSFER_KEY_ALIAS
# PEPK ZIPs are sensitive even when encrypted to Google. A public repository must
# publish only an outer envelope decryptable by the owner's non-exportable CNG key.
openssl cms -encrypt -binary -aes-256-cbc \
  -in "$work/play-signing-transfer.zip" -out "$work/delivery.p7m" -outform DER \
  -recip "$delivery" -keyopt rsa_padding_mode:oaep \
  -keyopt rsa_oaep_md:sha256 -keyopt rsa_mgf1_md:sha256
python3 - "$work/play-signing-transfer.zip.json" "$delivery_sha" "$work/delivery.p7m" <<'PY'
import hashlib, json, os, pathlib, sys
info = json.loads(pathlib.Path(sys.argv[1]).read_text())
envelope = pathlib.Path(sys.argv[3]).read_bytes()
if len(envelope) > 1024 * 1024:
    raise RuntimeError('Delivery envelope exceeds size bound')
info.update(envelopeSha256=hashlib.sha256(envelope).hexdigest(),
            deliveryCertificateSha256=sys.argv[2],
            envelopeProfile='RSA-OAEP-SHA256-MGF1-SHA256-AES256-CBC-CMS',
            sourceRepository=os.environ['GITHUB_REPOSITORY'],
            sourceRef=os.environ['GITHUB_REF'], sourceCommit=os.environ['GITHUB_SHA'])
with pathlib.Path('dist/play-signing-transfer.p7m').open('xb') as output:
    output.write(envelope)
with pathlib.Path('dist/play-signing-transfer.json').open('x') as output:
    json.dump(info, output, indent=2)
print('Owner-encrypted delivery envelope SHA-256: ' + info['envelopeSha256'])
PY
