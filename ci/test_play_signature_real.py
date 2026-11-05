"""Real jarsigner integration tests using disposable synthetic fixtures.

The keystore lives only in a temporary directory, the random password stays in
process memory and the child environment, and no key or PEM material is
printed. These are synthetic fixtures, not application signing identities.
"""
import base64
import hashlib
import os
import re
import secrets
import shutil
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import verify_play_bundle as verify


TOOL_TIMEOUT = 120
PASSWORD_ENV = "AAB_SIGNATURE_FIXTURE_STOREPASS"
ALIAS = "synthetic-aab-fixture"


def find_tool(name):
    executable = name + (".exe" if os.name == "nt" else "")
    home = os.environ.get("JAVA_HOME")
    if home:
        candidate = Path(home) / "bin" / executable
        if candidate.is_file():
            return str(candidate)
    found = shutil.which(name)
    return found if found else None


def make_temporary_directory():
    """Create a removable temp dir without relying on tempfile's 0700 ACL."""
    path = Path(tempfile.gettempdir()) / ("aab-signature-test-" + secrets.token_hex(8))
    path.mkdir()
    if os.name != "nt":
        os.chmod(path, 0o700)
    return path


def run(args, env):
    return subprocess.run(
        args,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        env=env,
        timeout=TOOL_TIMEOUT,
    )


@unittest.skipUnless(
    find_tool("jarsigner") and find_tool("keytool"),
    "JDK jarsigner and keytool are not available",
)
class RealSignedJarTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = make_temporary_directory()
        cls.addClassCleanup(shutil.rmtree, cls.tmp, ignore_errors=True)
        cls.keystore = cls.tmp / "fixture.p12"
        cls.jar = cls.tmp / "signed.aab"
        cls.env = os.environ.copy()
        cls.env[PASSWORD_ENV] = secrets.token_urlsafe(24)

        keytool = find_tool("keytool")
        jarsigner = find_tool("jarsigner")
        cls.keytool = keytool
        cls.jarsigner = jarsigner

        generated = run(
            [
                keytool,
                "-genkeypair",
                "-noprompt",
                "-keystore",
                str(cls.keystore),
                "-storetype",
                "PKCS12",
                "-storepass:env",
                PASSWORD_ENV,
                "-keypass:env",
                PASSWORD_ENV,
                "-alias",
                ALIAS,
                "-keyalg",
                "RSA",
                "-keysize",
                "3072",
                "-sigalg",
                "SHA384withRSA",
                "-validity",
                "3650",
                "-dname",
                "CN=Synthetic AAB Signature Fixture,O=Local Only",
            ],
            cls.env,
        )
        if generated.returncode != 0:
            shutil.rmtree(cls.tmp, ignore_errors=True)
            raise AssertionError("keytool could not create the synthetic fixture")

        with zipfile.ZipFile(cls.jar, "w") as archive:
            archive.writestr("base/manifest/AndroidManifest.xml", b"<manifest/>")
            archive.writestr("base/dex/classes.dex", b"synthetic-dex-fixture\n")
            archive.writestr("base/res/raw/payload.bin", b"synthetic-payload\n")
            archive.writestr("base/res/drawable/abc_list_selector_disabled_holo_dark.9.png", b"synthetic-resource")

        signed = run(
            [
                jarsigner,
                "-J-Duser.language=en",
                "-keystore",
                str(cls.keystore),
                "-storepass:env",
                PASSWORD_ENV,
                "-digestalg",
                "SHA-256",
                "-sigalg",
                "SHA256withRSA",
                str(cls.jar),
                ALIAS,
            ],
            cls.env,
        )
        if signed.returncode != 0:
            shutil.rmtree(cls.tmp, ignore_errors=True)
            raise AssertionError("jarsigner could not sign the synthetic fixture")

    def entries(self, jar=None):
        with zipfile.ZipFile(jar or self.jar) as archive:
            return archive.namelist()

    def test_real_valid_jar_is_accepted(self):
        raw = run(
            [
                self.jarsigner,
                "-J-Duser.language=en",
                "-verify",
                "-strict",
                str(self.jar),
            ],
            self.env,
        )
        output = (raw.stdout + raw.stderr).decode("utf-8", errors="replace")
        self.assertEqual(4, raw.returncode)
        self.assertIn("jar verified", output)
        self.assertIn("do not include a timestamp", output)
        self.assertNotIn("has expired", output)
        self.assertNotIn("not yet valid", output)
        self.assertNotIn("disabled", output)
        self.assertEqual(
            {"signed": True}, verify.verify_signature(self.jar, self.entries())
        )

    def test_real_expected_signer_fingerprint_matches(self):
        printed = run(
            [
                self.keytool,
                "-J-Duser.language=en",
                "-printcert",
                "-jarfile",
                str(self.jar),
                "-rfc",
            ],
            self.env,
        )
        self.assertEqual(0, printed.returncode)
        pem = (printed.stdout + printed.stderr).decode("utf-8", errors="replace")
        block = re.search(
            r"-----BEGIN CERTIFICATE-----(.*?)-----END CERTIFICATE-----", pem, re.S
        )
        self.assertIsNotNone(block)
        der = base64.b64decode(re.sub(r"\s+", "", block.group(1)))
        expected = hashlib.sha256(der).hexdigest()
        self.assertEqual(
            {"signed": True, "signerSha256": expected},
            verify.verify_signature(
                self.jar, self.entries(), expected_signer=expected.upper()
            ),
        )

    def test_real_unsigned_appended_payload_is_rejected(self):
        tampered = self.tmp / "tampered.aab"
        shutil.copyfile(self.jar, tampered)
        with zipfile.ZipFile(tampered, "a") as archive:
            archive.writestr("base/assets/unsigned-appended.bin", b"unsigned")
        raw = run(
            [
                self.jarsigner,
                "-J-Duser.language=en",
                "-verify",
                "-strict",
                str(tampered),
            ],
            self.env,
        )
        self.assertNotIn(raw.returncode, (0, 4))
        with self.assertRaisesRegex(verify.VerificationError, "jarsigner"):
            verify.verify_signature(tampered, self.entries(tampered))

    def test_manifest_last_resource_listing_is_not_a_disabled_algorithm(self):
        reordered = self.tmp / "manifest-last.aab"
        with zipfile.ZipFile(self.jar) as source, zipfile.ZipFile(reordered, "w", zipfile.ZIP_DEFLATED) as target:
            for entry in sorted(source.infolist(), key=lambda item: item.filename.startswith("META-INF/")):
                target.writestr(entry, source.read(entry))
        raw = run([self.jarsigner, "-J-Duser.language=en", "-verify", "-strict", str(reordered)], self.env)
        output = (raw.stdout + raw.stderr).decode("utf-8", errors="replace")
        self.assertEqual(4, raw.returncode)
        # Only newer JDK builds list JarFile/JarInputStream mismatches; JDK 21.0.8 does not.
        # The parser for that listing is covered by test_verify_play_bundle with recorded output.
        if "is signed in JarFile but is not signed in JarInputStream" in output:
            self.assertIn("abc_list_selector_disabled_holo_dark.9.png", output)
        self.assertEqual({"signed": True}, verify.verify_signature(reordered, self.entries(reordered)))

    def test_real_expired_and_future_certificates_are_rejected(self):
        for name, start in (("expired", "-10d"), ("future", "+2d")):
            with self.subTest(name=name):
                key = self.tmp / f"{name}.p12"
                generated = run([
                    self.keytool, "-genkeypair", "-noprompt", "-keystore", str(key),
                    "-storetype", "PKCS12", "-storepass:env", PASSWORD_ENV, "-keypass:env", PASSWORD_ENV,
                    "-alias", ALIAS, "-keyalg", "RSA", "-keysize", "3072", "-sigalg", "SHA384withRSA",
                    "-startdate", start, "-validity", "1", "-dname", "CN=Synthetic Validity Fixture",
                ], self.env)
                self.assertEqual(0, generated.returncode, "Synthetic certificate generation failed")
                jar = self.tmp / f"{name}.aab"
                with zipfile.ZipFile(jar, "w") as archive:
                    archive.writestr("payload.txt", b"synthetic")
                signed = run([
                    self.jarsigner, "-keystore", str(key), "-storepass:env", PASSWORD_ENV,
                    "-digestalg", "SHA-256", "-sigalg", "SHA256withRSA", str(jar), ALIAS,
                ], self.env)
                self.assertEqual(0, signed.returncode, "Synthetic archive signing failed")
                with self.assertRaisesRegex(verify.VerificationError, "not valid"):
                    verify.verify_signature(jar, self.entries(jar))

    def test_real_disabled_sha1_is_rejected(self):
        jar = self.tmp / "disabled-sha1.aab"
        with zipfile.ZipFile(jar, "w") as archive:
            archive.writestr("payload.txt", b"synthetic")
        signed = run([
            self.jarsigner, "-keystore", str(self.keystore), "-storepass:env", PASSWORD_ENV,
            "-digestalg", "SHA-1", "-sigalg", "SHA1withRSA", str(jar), ALIAS,
        ], self.env)
        self.assertEqual(0, signed.returncode, "Synthetic SHA1 signing control could not be created")
        with self.assertRaises(verify.VerificationError):
            verify.verify_signature(jar, self.entries(jar))


if __name__ == "__main__":
    unittest.main()
