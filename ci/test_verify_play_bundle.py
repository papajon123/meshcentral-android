"""Contract tests for ci/verify_play_bundle.py."""
import base64
import hashlib
import io
import struct
import subprocess
import sys
import unittest
import zipfile
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))
import verify_play_bundle as verify


PACKAGE = "io.github.papajon123.meshcentral"
VERSION = "0.9.9"
CODE = 33
ANDROID_NS = "http://schemas.android.com/apk/res/android"


def manifest_xml(
    *,
    package=PACKAGE,
    version=VERSION,
    code=CODE,
    min_sdk=26,
    target_sdk=36,
    debuggable=None,
    allow_backup="false",
    permissions=("android.permission.INTERNET",),
    permissions_sdk23=(),
):
    attributes = f'package="{package}" android:versionName="{version}" android:versionCode="{code}"'
    application = ""
    if debuggable is not None:
        application += f' android:debuggable="{debuggable}"'
    if allow_backup is not None:
        application += f' android:allowBackup="{allow_backup}"'
    uses_sdk = ""
    if min_sdk is not None or target_sdk is not None:
        attributes_used = ""
        if min_sdk is not None:
            attributes_used += f' android:minSdkVersion="{min_sdk}"'
        if target_sdk is not None:
            attributes_used += f' android:targetSdkVersion="{target_sdk}"'
        uses_sdk = f"<uses-sdk{attributes_used}/>"
    permission_xml = "".join(
        f'<uses-permission android:name="{name}"/>' for name in permissions
    )
    sdk23_xml = "".join(
        f'<uses-permission-sdk-23 android:name="{name}"/>' for name in permissions_sdk23
    )
    return (
        f'<manifest xmlns:android="{ANDROID_NS}" {attributes}>'
        f"{uses_sdk}{permission_xml}{sdk23_xml}"
        f"<application{application}/></manifest>"
    ).encode()


def make_elf(
    *,
    bits=64,
    magic=b"\x7fELF",
    data_encoding=1,
    align=16384,
    size=None,
    phoff=None,
    phentsize=None,
    phnum=1,
    filesz=None,
    memsz=None,
):
    """Build a minimal ELF with one PT_LOAD segment."""
    expected_phentsize = 56 if bits == 64 else 32
    header_size = 64 if bits == 64 else 52
    phoff = header_size if phoff is None else phoff
    phentsize = expected_phentsize if phentsize is None else phentsize
    phnum = phnum
    if size is None:
        size = header_size + expected_phentsize
    filesz = max(0, size - phoff) if filesz is None else filesz
    memsz = filesz if memsz is None else memsz
    endian = "<" if data_encoding == 1 else ">"
    ident = magic + bytes([1 if bits == 32 else 2, data_encoding, 1, 0]) + b"\0" * 8
    if bits == 64:
        header = ident + struct.pack(
            endian + "HHIQQQIHHHHHH",
            3, 0xB7, 1, 0, phoff, 0, 0,
            header_size, phentsize, phnum,
            64, 0, 0,
        )
        program = struct.pack(
            endian + "IIQQQQQQ", 1, 5, 0, 0, 0, filesz, memsz, align
        )
    else:
        header = ident + struct.pack(
            endian + "HHIIIIIHHHHHH",
            3, 40, 1, 0, phoff, 0, 0,
            header_size, phentsize, phnum,
            40, 0, 0,
        )
        program = struct.pack(
            endian + "IIIIIIII", 1, 0, 0, 0, filesz, memsz, 5, align
        )
    buffer = bytearray(size)
    buffer[: len(header)] = header
    if phoff + len(program) <= size:
        buffer[phoff : phoff + len(program)] = program
    return bytes(buffer)


class ManifestContractTest(unittest.TestCase):
    def parse(self, xml):
        return verify.parse_manifest(xml, PACKAGE, VERSION, CODE)

    def test_accepts_expected_release_manifest(self):
        parsed = self.parse(manifest_xml())

        self.assertEqual(PACKAGE, parsed["package"])
        self.assertEqual(VERSION, parsed["versionName"])
        self.assertEqual(CODE, parsed["versionCode"])
        self.assertEqual(26, parsed["minSdk"])
        self.assertEqual(36, parsed["targetSdk"])
        self.assertFalse(parsed["debuggable"])
        self.assertFalse(parsed["allowBackup"])
        self.assertEqual(["android.permission.INTERNET"], parsed["permissions"])

    def test_rejects_wrong_package(self):
        with self.assertRaisesRegex(verify.VerificationError, "package"):
            self.parse(manifest_xml(package="io.github.papajon123.other"))

    def test_rejects_wrong_version_or_code(self):
        with self.assertRaisesRegex(verify.VerificationError, "versionName"):
            self.parse(manifest_xml(version="0.9.8"))
        with self.assertRaisesRegex(verify.VerificationError, "versionCode"):
            self.parse(manifest_xml(code=32))

    def test_rejects_debuggable_true(self):
        with self.assertRaisesRegex(verify.VerificationError, "debuggable"):
            self.parse(manifest_xml(debuggable="true"))
        self.assertFalse(self.parse(manifest_xml(debuggable="false"))["debuggable"])

    def test_requires_allow_backup_false(self):
        for value in (None, "true"):
            with self.subTest(value=value), self.assertRaisesRegex(
                verify.VerificationError, "allowBackup"
            ):
                self.parse(manifest_xml(allow_backup=value))

    def test_rejects_forbidden_permissions_from_either_declaration(self):
        for element, permissions in (
            ("uses-permission", {"permissions": ("android.permission.REQUEST_INSTALL_PACKAGES",)}),
            ("uses-permission-sdk-23", {"permissions_sdk23": ("android.permission.RECORD_AUDIO",)}),
        ):
            with self.subTest(element=element), self.assertRaisesRegex(
                verify.VerificationError, "forbidden permission"
            ):
                self.parse(manifest_xml(**permissions))

    def test_reports_all_permissions(self):
        parsed = self.parse(
            manifest_xml(
                permissions=(
                    "android.permission.INTERNET",
                    "android.permission.POST_NOTIFICATIONS",
                    "android.permission.INTERNET",
                ),
                permissions_sdk23=("android.permission.USE_BIOMETRIC",),
            )
        )

        self.assertEqual(
            [
                "android.permission.INTERNET",
                "android.permission.POST_NOTIFICATIONS",
                "android.permission.USE_BIOMETRIC",
            ],
            parsed["permissions"],
        )

    def test_requires_android_namespace_and_sdk_levels(self):
        no_namespace = (
            f'<manifest xmlns:android="urn:not-android" package="{PACKAGE}">'
            '<application android:allowBackup="false"/></manifest>'
        ).encode()
        with self.assertRaisesRegex(verify.VerificationError, "Android namespace"):
            self.parse(no_namespace)
        with self.assertRaisesRegex(verify.VerificationError, "minSdk"):
            self.parse(manifest_xml(min_sdk=27))
        with self.assertRaisesRegex(verify.VerificationError, "targetSdk"):
            self.parse(manifest_xml(target_sdk=35))
        with self.assertRaisesRegex(verify.VerificationError, "uses-sdk"):
            self.parse(manifest_xml(min_sdk=None, target_sdk=None))


class NativeLibraryContractTest(unittest.TestCase):
    def test_unknown_abi_cannot_bypass_64bit_alignment_rules(self):
        with self.assertRaisesRegex(verify.VerificationError, "unsupported Android ABI"):
            self.inspect({"base/lib/unknown/libsample.so": make_elf(bits=64, align=4096)})

    def inspect(self, entries):
        buffer = io.BytesIO()
        with zipfile.ZipFile(buffer, "w") as archive:
            archive.writestr("base/manifest/AndroidManifest.xml", b"<manifest/>")
            for name, data in entries.items():
                archive.writestr(name, data)
        buffer.seek(0)
        with zipfile.ZipFile(buffer) as archive:
            return verify.inspect_native_archive(archive)

    def test_accepts_aligned_elf64(self):
        libraries = self.inspect(
            {"base/lib/arm64-v8a/libsample.so": make_elf(bits=64, align=16384)}
        )

        self.assertEqual(
            [
                {
                    "name": "base/lib/arm64-v8a/libsample.so",
                    "abi": "arm64-v8a",
                    "minLoadAlignment": 16384,
                }
            ],
            libraries,
        )

    def test_rejects_elf64_with_4kb_load_alignment(self):
        with self.assertRaisesRegex(verify.VerificationError, "16384"):
            self.inspect(
                {"base/lib/x86_64/libsample.so": make_elf(bits=64, align=4096)}
            )

    def test_rejects_non_elf_truncated_and_wrong_class(self):
        cases = {
            "not-elf": b"not an elf at all",
            "truncated": make_elf(bits=64, size=70),
            "elf32-in-arm64": make_elf(bits=32, align=4096),
            "bad-phdr-bounds": make_elf(bits=64, filesz=4096, size=128),
        }
        for label, data in cases.items():
            with self.subTest(label=label), self.assertRaises(verify.VerificationError):
                self.inspect({"base/lib/arm64-v8a/libsample.so": data})

    def test_32bit_abi_requires_matching_64bit_abi(self):
        with self.assertRaisesRegex(verify.VerificationError, "arm64-v8a"):
            self.inspect(
                {"base/lib/armeabi-v7a/libsample.so": make_elf(bits=32, align=4096)}
            )
        with self.assertRaisesRegex(verify.VerificationError, "x86_64"):
            self.inspect({"base/lib/x86/libsample.so": make_elf(bits=32, align=4096)})

        libraries = self.inspect(
            {
                "base/lib/armeabi-v7a/libsample.so": make_elf(bits=32, align=4096),
                "base/lib/arm64-v8a/libsample.so": make_elf(bits=64, align=16384),
            }
        )
        self.assertEqual(["arm64-v8a", "armeabi-v7a"], [item["abi"] for item in libraries])

    def test_absent_native_libraries_are_allowed(self):
        self.assertEqual([], self.inspect({}))


class SignatureContractTest(unittest.TestCase):
    def test_partially_signed_archive_is_rejected(self):
        with mock.patch.object(verify, "java_tool", return_value="jarsigner"), \
                mock.patch.object(verify, "run_tool", return_value=subprocess.CompletedProcess(
                    [], 20, b"jar verified, with signer errors.\n", b"")):
            with self.assertRaisesRegex(verify.VerificationError, "jarsigner"):
                verify.verify_signature(Path("bundle.aab"), ["META-INF/CERT.SF", "META-INF/CERT.RSA"])

    def test_valid_self_signed_android_certificate_is_allowed_but_expired_is_not(self):
        with mock.patch.object(verify, "java_tool", return_value="jarsigner"), \
                mock.patch.object(verify, "run_tool", return_value=subprocess.CompletedProcess(
                    [], 4, b"jar verified, with signer errors.\nThe signer certificate is self signed.\n", b"")):
            self.assertEqual({"signed": True}, verify.verify_signature(
                Path("bundle.aab"), ["META-INF/CERT.SF", "META-INF/CERT.RSA"]))
        with mock.patch.object(verify, "java_tool", return_value="jarsigner"), \
                mock.patch.object(verify, "run_tool", return_value=subprocess.CompletedProcess(
                    [], 4, b"jar verified, with signer errors.\nThe signer certificate has expired.\n", b"")):
            with self.assertRaisesRegex(verify.VerificationError, "not valid"):
                verify.verify_signature(Path("bundle.aab"), ["META-INF/CERT.SF", "META-INF/CERT.RSA"])

    def test_unsigned_bundle_is_allowed_without_require_signed(self):
        result = verify.verify_signature(
            Path("bundle.aab"),
            ["base/manifest/AndroidManifest.xml", "base/root/META-INF/services/example"],
        )

        self.assertEqual({"signed": False}, result)

    def test_unsigned_bundle_is_rejected_when_signature_is_required(self):
        with self.assertRaisesRegex(verify.VerificationError, "not signed"):
            verify.verify_signature(Path("bundle.aab"), [], require_signed=True)
        with self.assertRaisesRegex(verify.VerificationError, "signed"):
            verify.verify_signature(
                Path("bundle.aab"),
                [],
                expected_signer="0" * 64,
            )

    @staticmethod
    def fake_run(pem=b"", jarsigner_code=0, jarsigner_output=b"jar verified.\n"):
        calls = []

        def run(args, **kwargs):
            calls.append(args)
            name = Path(args[0]).name.lower()
            if name.startswith("jarsigner"):
                return subprocess.CompletedProcess(
                    args, jarsigner_code, jarsigner_output, b""
                )
            if name.startswith("keytool"):
                return subprocess.CompletedProcess(args, 0, pem, b"")
            raise AssertionError(f"unexpected tool: {args[0]}")

        return run, calls

    def test_signed_bundle_is_verified_and_signer_fingerprint_is_checked(self):
        certificate = b"unit-test-signer-certificate"
        pem = (
            b"Certificate[1]:\n-----BEGIN CERTIFICATE-----\n"
            + base64.encodebytes(certificate)
            + b"-----END CERTIFICATE-----\n"
        )
        expected = hashlib.sha256(certificate).hexdigest()
        run, calls = self.fake_run(pem=pem)

        with mock.patch.dict(verify.os.environ, {"PATH": ""}), \
                mock.patch.object(verify.shutil, "which", side_effect=lambda name: name), \
                mock.patch.object(verify.subprocess, "run", side_effect=run):
            result = verify.verify_signature(
                Path("bundle.aab"),
                ["META-INF/CERT.SF", "META-INF/CERT.RSA"],
                expected_signer=expected.upper(),
            )

        self.assertTrue(result["signed"])
        self.assertEqual(expected, result["signerSha256"])
        self.assertIn("-verify", calls[0])
        self.assertIn("-strict", calls[0])
        self.assertIn("-printcert", calls[1])
        self.assertIn("-rfc", calls[1])

    def test_signed_bundle_without_expected_signer_does_not_run_keytool(self):
        run, calls = self.fake_run()

        with mock.patch.dict(verify.os.environ, {"PATH": ""}), \
                mock.patch.object(verify.shutil, "which", side_effect=lambda name: name), \
                mock.patch.object(verify.subprocess, "run", side_effect=run):
            result = verify.verify_signature(
                Path("bundle.aab"), ["META-INF/CERT.SF", "META-INF/CERT.EC"]
            )

        self.assertEqual({"signed": True}, result)
        self.assertEqual(1, len(calls))

    def test_failed_jarsigner_verification_is_rejected(self):
        run, _ = self.fake_run(jarsigner_code=1, jarsigner_output=b"jar is unsigned.\n")

        with mock.patch.dict(verify.os.environ, {"PATH": ""}), \
                mock.patch.object(verify.shutil, "which", side_effect=lambda name: name), \
                mock.patch.object(verify.subprocess, "run", side_effect=run):
            with self.assertRaisesRegex(verify.VerificationError, "jarsigner"):
                verify.verify_signature(
                    Path("bundle.aab"), ["META-INF/CERT.SF", "META-INF/CERT.RSA"]
                )

    def test_signer_fingerprint_mismatch_is_rejected_without_leaking_pem(self):
        pem = (
            b"-----BEGIN CERTIFICATE-----\n"
            + base64.encodebytes(b"other-certificate")
            + b"-----END CERTIFICATE-----\n"
        )
        run, _ = self.fake_run(pem=pem)

        with mock.patch.dict(verify.os.environ, {"PATH": ""}), \
                mock.patch.object(verify.shutil, "which", side_effect=lambda name: name), \
                mock.patch.object(verify.subprocess, "run", side_effect=run):
            with self.assertRaises(verify.VerificationError) as raised:
                verify.verify_signature(
                    Path("bundle.aab"),
                    ["META-INF/CERT.SF", "META-INF/CERT.RSA"],
                    expected_signer="0" * 64,
                )

        message = str(raised.exception)
        self.assertIn("SHA-256", message)
        self.assertNotIn("BEGIN CERTIFICATE", message)


if __name__ == "__main__":
    unittest.main()
