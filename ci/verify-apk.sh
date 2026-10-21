#!/usr/bin/env bash
set -euo pipefail
python3 ci/verify_apk.py "${1:?APK path required}" --version "${2:?Release version required}"
