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
./gradlew --no-daemon assembleRelease
mkdir -p dist
cp app/build/outputs/apk/release/app-release.apk "dist/meshcentral-android-${RELEASE_VERSION}.apk"
python3 ci/verify_apk.py "dist/meshcentral-android-${RELEASE_VERSION}.apk" --version "$RELEASE_VERSION" --output dist/release-info.json
(cd dist && sha256sum "meshcentral-android-${RELEASE_VERSION}.apk" > SHA256SUMS)
