"""Verify a local Android App Bundle against the Google Play release contract.

The verifier reads the bundle in place, never extracts it, and never prints
tool output or certificate material.
"""
import argparse
import base64
import binascii
import hashlib
import io
import json
import os
import re
import shutil
import struct
import subprocess
import sys
import zipfile
from pathlib import Path
from xml.etree import ElementTree

ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
DEFAULT_PACKAGE = "io.github.papajon123.meshcentral"
EXPECTED_MIN_SDK = 26
EXPECTED_TARGET_SDK = 36
BUNDLETOOL_SHA256 = "a099cfa1543f55593bc2ed16a70a7c67fe54b1747bb7301f37fdfd6d91028e29"
MAX_NATIVE_FILE_SIZE = 64 * 1024 * 1024
SIXTEEN_KB = 16 * 1024
ABI_64 = {"arm64-v8a", "x86_64"}
ABI_32 = {"armeabi-v7a", "x86"}
ABI_32_TO_64 = {"armeabi-v7a": "arm64-v8a", "x86": "x86_64"}
FORBIDDEN_PERMISSIONS = {
    "android.permission.REQUEST_INSTALL_PACKAGES",
    "android.permission.RECORD_AUDIO",
}
JAVA_TIMEOUT = 900
TOOL_TIMEOUT = 300


class VerificationError(Exception):
    """A release-contract violation with a short, user-safe message."""


def sha256_file(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def java_tool(name):
    """Resolve a JDK tool through JAVA_HOME or PATH only."""
    executable = name + (".exe" if os.name == "nt" else "")
    home = os.environ.get("JAVA_HOME")
    if home:
        candidate = Path(home) / "bin" / executable
        if candidate.is_file():
            return str(candidate)
    found = shutil.which(name)
    if found:
        return found
    raise VerificationError(f"{name} was not found via JAVA_HOME or PATH")


def run_tool(args, timeout):
    try:
        return subprocess.run(
            args,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            timeout=timeout,
        )
    except subprocess.TimeoutExpired:
        raise VerificationError("an external verification tool timed out")
    except OSError:
        raise VerificationError("an external verification tool could not be started")


def decode_output(data):
    if isinstance(data, str):
        return data
    return data.decode("utf-8", errors="replace")


def android_attribute(element, name):
    return element.get(f"{{{ANDROID_NAMESPACE}}}{name}")


def parse_integer(value, label):
    if value is None:
        raise VerificationError(f"{label} is missing")
    if not re.fullmatch(r"[0-9]+", value.strip()):
        raise VerificationError(f"{label} is not an integer")
    return int(value)


def parse_manifest(xml_bytes, package, version, code):
    """Parse and validate the base module manifest XML."""
    if isinstance(xml_bytes, str):
        xml_bytes = xml_bytes.encode("utf-8")
    namespaces = set()
    try:
        for _, declaration in ElementTree.iterparse(
            io.BytesIO(xml_bytes), events=("start-ns",)
        ):
            namespaces.add(declaration[1])
        root = ElementTree.fromstring(xml_bytes)
    except ElementTree.ParseError:
        raise VerificationError("manifest is not well-formed XML")

    if root.tag != "manifest":
        raise VerificationError("manifest root element is not <manifest>")
    if ANDROID_NAMESPACE not in namespaces:
        raise VerificationError("manifest does not declare the Android namespace")

    declared_package = root.get("package")
    if declared_package != package:
        raise VerificationError("manifest package does not match the expected package")

    declared_version = android_attribute(root, "versionName")
    if declared_version != version:
        raise VerificationError("manifest versionName does not match --version")
    declared_code = parse_integer(android_attribute(root, "versionCode"), "versionCode")
    if declared_code != code:
        raise VerificationError("manifest versionCode does not match --code")

    sdk_nodes = root.findall("uses-sdk")
    if len(sdk_nodes) != 1:
        raise VerificationError("manifest must contain exactly one uses-sdk element")
    min_sdk = parse_integer(android_attribute(sdk_nodes[0], "minSdkVersion"), "minSdk")
    target_sdk = parse_integer(
        android_attribute(sdk_nodes[0], "targetSdkVersion"), "targetSdk"
    )
    if min_sdk != EXPECTED_MIN_SDK:
        raise VerificationError(f"minSdk must be {EXPECTED_MIN_SDK}")
    if target_sdk != EXPECTED_TARGET_SDK:
        raise VerificationError(f"targetSdk must be {EXPECTED_TARGET_SDK}")

    applications = root.findall("application")
    if len(applications) != 1:
        raise VerificationError("manifest must contain exactly one application element")
    application = applications[0]
    debuggable = android_attribute(application, "debuggable")
    if debuggable is not None and debuggable.strip().lower() != "false":
        raise VerificationError("manifest application must not be debuggable")
    allow_backup = android_attribute(application, "allowBackup")
    if allow_backup is None or allow_backup.strip().lower() != "false":
        raise VerificationError("manifest allowBackup must be false")

    permissions = []
    for element in root:
        if element.tag not in ("uses-permission", "uses-permission-sdk-23"):
            continue
        name = android_attribute(element, "name")
        if not name:
            raise VerificationError("a manifest permission has no name")
        if name in FORBIDDEN_PERMISSIONS:
            raise VerificationError(f"forbidden permission: {name}")
        permissions.append(name)

    return {
        "package": declared_package,
        "versionName": declared_version,
        "versionCode": declared_code,
        "minSdk": min_sdk,
        "targetSdk": target_sdk,
        "debuggable": False,
        "allowBackup": False,
        "permissions": sorted(set(permissions)),
    }


def elf_min_load_alignment(data, abi):
    """Return the smallest PT_LOAD p_align, rejecting malformed or unsafe ELF."""
    if abi not in ABI_32 | ABI_64:
        raise VerificationError("native library has an unsupported Android ABI")
    if len(data) < 16 or data[:4] != b"\x7fELF":
        raise VerificationError("native library is not an ELF file")
    elf_class = data[4]
    elf_data = data[5]
    if elf_class not in (1, 2):
        raise VerificationError("native library has an unsupported ELF class")
    if elf_data not in (1, 2):
        raise VerificationError("native library has an unsupported ELF byte order")
    endian = "<" if elf_data == 1 else ">"
    is_64 = elf_class == 2

    if abi in ABI_64 and not is_64:
        raise VerificationError(f"{abi} native library is not ELF64")
    if abi in ABI_32 and is_64:
        raise VerificationError(f"{abi} native library is not ELF32")

    if is_64:
        header_size = 64
        expected_phentsize = 56
        if len(data) < header_size:
            raise VerificationError("native library has a truncated ELF header")
        phoff = struct.unpack_from(endian + "Q", data, 32)[0]
        phentsize = struct.unpack_from(endian + "H", data, 54)[0]
        phnum = struct.unpack_from(endian + "H", data, 56)[0]
    else:
        header_size = 52
        expected_phentsize = 32
        if len(data) < header_size:
            raise VerificationError("native library has a truncated ELF header")
        phoff = struct.unpack_from(endian + "I", data, 28)[0]
        phentsize = struct.unpack_from(endian + "H", data, 42)[0]
        phnum = struct.unpack_from(endian + "H", data, 44)[0]

    if phentsize != expected_phentsize:
        raise VerificationError("native library has an unexpected program header size")
    if phnum == 0:
        raise VerificationError("native library has no program headers")
    if phoff < header_size:
        raise VerificationError("native library program header table overlaps the ELF header")
    table_end = phoff + phentsize * phnum
    if table_end > len(data):
        raise VerificationError("native library program header table exceeds the file")

    alignments = []
    for index in range(phnum):
        base = phoff + index * phentsize
        p_type = struct.unpack_from(endian + "I", data, base)[0]
        if p_type != 1:  # PT_LOAD
            continue
        if is_64:
            p_offset = struct.unpack_from(endian + "Q", data, base + 8)[0]
            p_vaddr = struct.unpack_from(endian + "Q", data, base + 16)[0]
            p_filesz = struct.unpack_from(endian + "Q", data, base + 32)[0]
            p_memsz = struct.unpack_from(endian + "Q", data, base + 40)[0]
            p_align = struct.unpack_from(endian + "Q", data, base + 48)[0]
        else:
            p_offset = struct.unpack_from(endian + "I", data, base + 4)[0]
            p_vaddr = struct.unpack_from(endian + "I", data, base + 8)[0]
            p_filesz = struct.unpack_from(endian + "I", data, base + 16)[0]
            p_memsz = struct.unpack_from(endian + "I", data, base + 20)[0]
            p_align = struct.unpack_from(endian + "I", data, base + 28)[0]

        if p_align > 1 and p_align & (p_align - 1):
            raise VerificationError("native library PT_LOAD has an invalid p_align")
        if p_filesz > p_memsz:
            raise VerificationError("native library PT_LOAD file size exceeds memory size")
        if p_offset + p_filesz > len(data):
            raise VerificationError("native library PT_LOAD exceeds the file bounds")
        if p_align > 1 and p_offset % p_align != p_vaddr % p_align:
            raise VerificationError("native library PT_LOAD offset and address are misaligned")
        alignments.append(p_align)

    if not alignments:
        raise VerificationError("native library has no PT_LOAD segment")
    minimum = min(alignments)
    if abi in ABI_64 and minimum < SIXTEEN_KB:
        raise VerificationError(
            f"{abi} native library requires PT_LOAD alignment of at least 16384 bytes "
            f"(found {minimum})"
        )
    return minimum


def inspect_native_archive(archive):
    """Inspect native libraries from an open bundle ZIP."""
    libraries = []
    abis = set()
    try:
        for info in archive.infolist():
            if not info.filename.lower().endswith(".so"):
                continue
            match = re.search(
                r"(?:^|/)lib/([^/]+)/([^/]+\.so)$",
                info.filename,
                re.IGNORECASE,
            )
            if not match:
                raise VerificationError(
                    "a native library is outside the standard module lib/<abi> layout"
                )
            abi = match.group(1)
            if info.file_size > MAX_NATIVE_FILE_SIZE:
                raise VerificationError("a native library exceeds the 64 MiB limit")
            with archive.open(info) as stream:
                data = stream.read(MAX_NATIVE_FILE_SIZE + 1)
            if len(data) > MAX_NATIVE_FILE_SIZE:
                raise VerificationError("a native library exceeds the 64 MiB limit")
            if len(data) != info.file_size:
                raise VerificationError("a native library has an inconsistent ZIP size")
            alignment = elf_min_load_alignment(data, abi)
            abis.add(abi)
            libraries.append(
                {
                    "name": info.filename,
                    "abi": abi,
                    "minLoadAlignment": alignment,
                }
            )
    except zipfile.BadZipFile:
        raise VerificationError("bundle is not a valid ZIP archive")
    except OSError:
        raise VerificationError("bundle could not be read")

    for abi_32, abi_64 in ABI_32_TO_64.items():
        if abi_32 in abis and abi_64 not in abis:
            raise VerificationError(f"32-bit ABI {abi_32} requires 64-bit ABI {abi_64}")

    return sorted(libraries, key=lambda item: (item["name"], item["abi"]))


def inspect_native_libraries(bundle):
    """Open the bundle and inspect every native library in place."""
    try:
        with zipfile.ZipFile(bundle) as archive:
            return inspect_native_archive(archive)
    except zipfile.BadZipFile:
        raise VerificationError("bundle is not a valid ZIP archive")
    except OSError:
        raise VerificationError("bundle could not be read")


def signature_entries(names):
    """Find top-level JAR signature files in the bundle."""
    signature_files = []
    signature_blocks = []
    for name in names:
        if not name.startswith("META-INF/"):
            continue
        remainder = name[len("META-INF/") :]
        if not remainder or "/" in remainder:
            continue
        upper = remainder.upper()
        if upper.endswith(".SF"):
            signature_files.append(name)
        elif upper.endswith((".RSA", ".DSA", ".EC")):
            signature_blocks.append(name)
    return signature_files, signature_blocks


def normalize_sha256(value):
    cleaned = value.strip().replace(":", "").replace(" ", "").lower()
    if not re.fullmatch(r"[0-9a-f]{64}", cleaned):
        raise VerificationError("--signer-sha256 must be a SHA-256 hex digest")
    return cleaned


def parse_certificates(data):
    """Decode RFC 1421 certificates from keytool output without exposing them."""
    text = decode_output(data)
    blocks = re.findall(
        r"-----BEGIN CERTIFICATE-----(.*?)-----END CERTIFICATE-----", text, re.S
    )
    certificates = []
    for block in blocks:
        try:
            certificate = base64.b64decode(re.sub(r"\s+", "", block), validate=True)
        except (ValueError, binascii.Error):
            raise VerificationError("keytool returned invalid certificate data")
        if not certificate:
            raise VerificationError("keytool returned an empty certificate")
        certificates.append(certificate)
    return certificates


def verify_signature(bundle, entry_names, require_signed=False, expected_signer=None):
    """Check JAR signing and, when requested, the signer certificate fingerprint."""
    if expected_signer is not None:
        expected_signer = normalize_sha256(expected_signer)
    signature_files, signature_blocks = signature_entries(entry_names)
    has_signature = bool(signature_files or signature_blocks)
    if not has_signature:
        if require_signed:
            raise VerificationError("bundle is not signed but --require-signed was given")
        if expected_signer is not None:
            raise VerificationError("--signer-sha256 requires a signed bundle")
        return {"signed": False}

    jarsigner = java_tool("jarsigner")
    result = run_tool(
        [jarsigner, "-J-Duser.language=en", "-verify", "-strict", str(bundle)],
        TOOL_TIMEOUT,
    )
    output = decode_output(result.stdout) + decode_output(result.stderr)
    # Android signing certificates are normally self-signed (strict exit bit 4).
    # In particular, strict bit 16 rejects unsigned payload appended to a signed JAR.
    if result.returncode not in (0, 4):
        raise VerificationError("jarsigner verification failed")
    lower = output.lower()
    if result.returncode == 4 and any(reason in lower for reason in ("has expired", "not yet valid", "disabled")):
        raise VerificationError("signer certificate or signature algorithm is not valid")
    if "jar verified" not in lower or "jar is unsigned" in lower:
        raise VerificationError("jarsigner did not confirm 'jar verified.'")

    if expected_signer is None:
        return {"signed": True}

    keytool = java_tool("keytool")
    result = run_tool(
        [keytool, "-J-Duser.language=en", "-printcert", "-jarfile", str(bundle), "-rfc"],
        TOOL_TIMEOUT,
    )
    if result.returncode != 0:
        raise VerificationError("keytool could not read the signer certificate")
    certificates = parse_certificates(result.stdout)
    if not certificates:
        raise VerificationError("keytool returned no signer certificate")
    signer_sha256 = hashlib.sha256(certificates[0]).hexdigest()
    if signer_sha256 != expected_signer:
        raise VerificationError(
            "signer certificate SHA-256 does not match --signer-sha256"
        )
    return {"signed": True, "signerSha256": signer_sha256}


def verify_bundle(
    bundle,
    bundletool,
    version,
    code,
    *,
    package=DEFAULT_PACKAGE,
    require_signed=False,
    signer_sha256=None,
):
    bundle = Path(bundle)
    bundletool = Path(bundletool)
    if not bundle.is_file():
        raise VerificationError("bundle file does not exist")
    if not bundletool.is_file():
        raise VerificationError("bundletool JAR does not exist")
    if not version:
        raise VerificationError("--version must not be empty")
    if isinstance(code, bool) or not isinstance(code, int) or code < 0:
        raise VerificationError("--code must be a non-negative integer")

    try:
        bundletool_sha256 = sha256_file(bundletool)
    except OSError:
        raise VerificationError("bundletool JAR could not be read")
    if bundletool_sha256 != BUNDLETOOL_SHA256:
        raise VerificationError(
            "bundletool SHA-256 does not match the pinned official release"
        )

    try:
        bundle_sha256 = sha256_file(bundle)
    except OSError:
        raise VerificationError("bundle could not be read")

    java = java_tool("java")
    result = run_tool(
        [java, "-jar", str(bundletool), "validate", "--bundle", str(bundle)],
        JAVA_TIMEOUT,
    )
    if result.returncode != 0:
        raise VerificationError("bundletool validate failed")

    result = run_tool(
        [
            java,
            "-jar",
            str(bundletool),
            "dump",
            "manifest",
            "--bundle",
            str(bundle),
            "--module",
            "base",
        ],
        TOOL_TIMEOUT,
    )
    if result.returncode != 0:
        raise VerificationError("bundletool manifest dump failed")
    manifest = parse_manifest(result.stdout, package, version, code)

    try:
        with zipfile.ZipFile(bundle) as archive:
            entry_names = archive.namelist()
    except (zipfile.BadZipFile, OSError):
        raise VerificationError("bundle is not a valid ZIP archive")

    signature = verify_signature(
        bundle,
        entry_names,
        require_signed=require_signed,
        expected_signer=signer_sha256,
    )
    native_libraries = inspect_native_libraries(bundle)

    report = {
        "bundle_sha256": bundle_sha256,
        "package": manifest["package"],
        "versionName": manifest["versionName"],
        "versionCode": manifest["versionCode"],
        "minSdk": manifest["minSdk"],
        "targetSdk": manifest["targetSdk"],
        "signed": signature["signed"],
    }
    if "signerSha256" in signature:
        report["signerSha256"] = signature["signerSha256"]
    report["permissions"] = manifest["permissions"]
    report["nativeLibraries"] = native_libraries
    return report


def main(argv=None):
    parser = argparse.ArgumentParser(
        description="Verify an unsigned or signed Android App Bundle for Google Play."
    )
    parser.add_argument("bundle", type=Path)
    parser.add_argument("--bundletool", type=Path, required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--code", type=int, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--package", default=DEFAULT_PACKAGE)
    parser.add_argument("--require-signed", action="store_true")
    parser.add_argument("--signer-sha256")
    args = parser.parse_args(argv)

    try:
        output = args.output.resolve()
        if output in {args.bundle.resolve(), args.bundletool.resolve()}:
            raise VerificationError("--output must not overwrite the bundle or bundletool JAR")
        if not output.parent.is_dir():
            raise VerificationError("output directory does not exist")
        report = verify_bundle(
            args.bundle,
            args.bundletool,
            args.version,
            args.code,
            package=args.package,
            require_signed=args.require_signed,
            signer_sha256=args.signer_sha256,
        )
        output.write_text(
            json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
        )
    except VerificationError as error:
        print(f"error: {error}", file=sys.stderr)
        return 1
    except OSError:
        print("error: output report could not be written", file=sys.stderr)
        return 1
    except Exception:
        print("error: verification failed", file=sys.stderr)
        return 1

    print(json.dumps(report, separators=(",", ":"), ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
