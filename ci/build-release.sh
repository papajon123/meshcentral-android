#!/usr/bin/env bash
set -euo pipefail
: "${ANDROID_RELEASE_KEYSTORE_B64:?Missing signing keystore}"
: "${ANDROID_RELEASE_PASSWORD:?Missing signing password}"
: "${RELEASE_VERSION:?Missing release version}"
umask 077
keystore=$(mktemp "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/meshcentral-signing.XXXXXX.p12")
trap 'rm -f "$keystore"' EXIT
printf '%s' "$ANDROID_RELEASE_KEYSTORE_B64" | base64 --decode > "$keystore"
test -s "$keystore"
export ANDROID_RELEASE_KEYSTORE_FILE="$keystore"
# Keep each distribution's compilation/packaging heap bounded independently.
./gradlew --no-daemon --max-workers=2 --stacktrace assembleDirectRelease
./gradlew --no-daemon --max-workers=2 --stacktrace bundlePlayRelease
mkdir -p dist
cp app/build/outputs/apk/direct/release/app-direct-release.apk "dist/meshcentral-android-${RELEASE_VERSION}.apk"
cp app/build/outputs/bundle/playRelease/app-play-release.aab "dist/meshpilot-google-play-${RELEASE_VERSION}.aab"
python3 ci/verify_apk.py "dist/meshcentral-android-${RELEASE_VERSION}.apk" --version "$RELEASE_VERSION" --output dist/release-info.json
bundletool=$(python3 ci/download_bundletool.py)
code=$(python3 -c 'import json; print(json.load(open("dist/release-info.json"))["versionCode"])')
signer=$(python3 -c 'import json; print(json.load(open("ci/release-identity.json"))["certificateSha256"])')
python3 ci/verify_play_bundle.py "dist/meshpilot-google-play-${RELEASE_VERSION}.aab" \
  --bundletool "$bundletool" --version "$RELEASE_VERSION" --code "$code" \
  --require-signed --signer-sha256 "$signer" --output dist/play-bundle-report.json
(cd dist && sha256sum "meshcentral-android-${RELEASE_VERSION}.apk" > SHA256SUMS)
