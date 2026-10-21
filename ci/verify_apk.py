"""Fail closed on the identity and safety properties of a signed public APK."""
import argparse
import hashlib
import json
import os
import re
import subprocess
from pathlib import Path


def tool(name):
    sdk = Path(os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") or "")
    suffix = ".bat" if name == "apksigner" and os.name == "nt" else ".exe" if os.name == "nt" else ""
    executable = sdk / "build-tools" / "35.0.0" / (name + suffix)
    if not executable.is_file():
        raise SystemExit(f"Android Build Tools 35.0.0 required: {name}")
    return str(executable)


def run(*args):
    return subprocess.check_output(args, text=True, encoding="utf-8", errors="replace")


def verify(apk, version):
    root = Path(__file__).resolve().parent.parent
    expected = json.loads((root / "ci/release-identity.json").read_text())
    signed = run(tool("apksigner"), "verify", "--verbose", "--print-certs", "--min-sdk-version", "26", str(apk))
    fingerprints = re.findall(r"^Signer #\d+ certificate SHA-256 digest: ([a-fA-F0-9]+)$", signed, re.M)
    if len(fingerprints) != 1 or fingerprints[0].lower() != expected["certificateSha256"].lower():
        raise SystemExit("APK signing certificate does not match the public release identity")
    if not re.search(r"Verified using v2 scheme .*: true", signed):
        raise SystemExit("APK v2 signing is required for Android 8 compatibility")
    badging = run(tool("aapt"), "dump", "badging", str(apk))
    match = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging, re.M)
    if not match or match[1] != expected["applicationId"] or match[3] != version:
        raise SystemExit("APK package/version differs from the expected release")
    gradle = (root / "app/build.gradle.kts").read_text()
    code = re.search(r"versionCode\s*=\s*(\d+)", gradle)
    if not code or match[2] != code[1]:
        raise SystemExit("APK version code differs from the source")
    if "application-debuggable" in badging or "android.permission.REQUEST_INSTALL_PACKAGES" in badging:
        raise SystemExit("Public APK contains a debug flag or obsolete package installation permission")
    manifest = run(tool("aapt"), "dump", "xmltree", str(apk), "AndroidManifest.xml")
    for attribute in ("debuggable", "testOnly", "usesCleartextTraffic", "allowBackup"):
        if re.search(rf"android:{attribute}\b[^\n]*0xffffffff", manifest):
            raise SystemExit(f"Unsafe release manifest attribute: {attribute}")
    return {"applicationId": match[1], "versionCode": int(match[2]), "versionName": match[3],
            "sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
            "certificateSha256": fingerprints[0].lower(), "sizeBytes": apk.stat().st_size}


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("apk", type=Path)
    parser.add_argument("--version", required=True)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    metadata = verify(args.apk, args.version)
    if args.output:
        args.output.write_text(json.dumps(metadata, indent=2) + "\n")
    print(json.dumps(metadata, indent=2))
