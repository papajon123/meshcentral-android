"""Contract tests for ci/PlaySigningTransfer.java.

The tests compile the production helper and a throwaway Java fixture, then
exercise the helper with synthetic RSA-2048 material only.
"""

from __future__ import annotations

import base64
import hashlib
import json
import os
import re
import subprocess
import tempfile
import unittest
import zipfile
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]
PEPK_SHA256 = "aaccc0774b240aa5304bdad2a49865e92f229ca73209ecb6eaafe75dc858e24e"
DEFAULT_JAVA_HOME = Path(r"C:\Program Files\Android\Android Studio\jbr")
CHILD_ENV_NAMES = ("PATH", "JAVA_HOME", "SystemRoot", "TEMP", "TMP", "LANG")
SYNTHETIC_PASSWORD = "synthetic"
WRONG_PASSWORD = "synthetic-wrong-password"
TIMEOUT_SECONDS = 180


def _tool(java_home: Path, name: str) -> Path:
    suffix = ".exe" if os.name == "nt" else ""
    return java_home / "bin" / f"{name}{suffix}"


def _child_env(java_home: Path, **explicit: str) -> dict[str, str]:
    env = {}
    for name in CHILD_ENV_NAMES:
        value = os.environ.get(name)
        if value:
            env[name] = value
    env["JAVA_HOME"] = str(java_home)
    env.setdefault("PATH", os.defpath)
    env.update(explicit)
    return env


def _receipt_path(output: Path) -> Path:
    return Path(str(output) + ".json")


def _run(command, *, env, cwd=None):
    return subprocess.run(
        [str(part) for part in command],
        cwd=str(cwd) if cwd is not None else None,
        env=env,
        capture_output=True,
        text=True,
        timeout=TIMEOUT_SECONDS,
    )


def _pem_certificate_sha256(pem_bytes: bytes) -> str:
    match = re.search(
        r"-----BEGIN CERTIFICATE-----\s*(.*?)\s*-----END CERTIFICATE-----",
        pem_bytes.decode("ascii"),
        re.DOTALL,
    )
    if match is None:
        raise ValueError("certificate PEM block not found")
    der = base64.b64decode(re.sub(r"\s+", "", match.group(1)), validate=True)
    return hashlib.sha256(der).hexdigest()


class PlaySigningTransferTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        pepk_setting = os.environ.get("PEPK_TEST_JAR")
        if not pepk_setting:
            raise unittest.SkipTest("PEPK_TEST_JAR is not set")
        pepk_jar = Path(pepk_setting).expanduser()
        if not pepk_jar.is_absolute():
            pepk_jar = (REPO_ROOT / pepk_jar).resolve()
        if not pepk_jar.is_file():
            raise RuntimeError(f"PEPK_TEST_JAR is missing: {pepk_jar}")
        digest = hashlib.sha256(pepk_jar.read_bytes()).hexdigest()
        if digest != PEPK_SHA256:
            raise RuntimeError(
                f"PEPK_TEST_JAR SHA-256 mismatch: expected {PEPK_SHA256}, got {digest}"
            )

        java_home_setting = os.environ.get("JAVA_HOME")
        java_home = (
            Path(java_home_setting).expanduser().resolve()
            if java_home_setting
            else DEFAULT_JAVA_HOME
        )
        java = _tool(java_home, "java")
        javac = _tool(java_home, "javac")
        if not java.is_file() or not javac.is_file():
            raise RuntimeError(f"JAVA_HOME has no java/javac: {java_home}")

        cls._java_home = java_home
        cls._java = java
        cls._javac = javac
        cls._env = _child_env(java_home)
        cls._temp = tempfile.TemporaryDirectory(prefix="play-signing-transfer-")
        cls._work = Path(cls._temp.name)
        cls._classes = cls._work / "classes"
        cls._classes.mkdir()
        cls._pepk_jar = pepk_jar
        cls._classpath = os.pathsep.join([str(cls._classes), str(pepk_jar)])
        try:
            cls._compile(
                REPO_ROOT / "ci" / "PlaySigningTransfer.java",
                classpath=[str(pepk_jar)],
            )
            cls._compile(
                REPO_ROOT / "ci" / "tests" / "PlaySigningFixture.java",
                classpath=[str(pepk_jar), str(cls._classes)],
            )
        except BaseException:
            cls._temp.cleanup()
            raise

    @classmethod
    def tearDownClass(cls):
        if getattr(cls, "_temp", None) is not None:
            cls._temp.cleanup()

    @classmethod
    def _compile(cls, source: Path, *, classpath) -> None:
        result = _run(
            [
                cls._javac,
                "--release",
                "17",
                "-proc:none",
                "-encoding",
                "UTF-8",
                "-cp",
                os.pathsep.join(classpath),
                "-d",
                cls._classes,
                source,
            ],
            env=cls._env,
            cwd=REPO_ROOT,
        )
        if result.returncode != 0:
            raise RuntimeError(
                f"javac failed for {source}:\n{result.stdout}{result.stderr}"
            )

    def setUp(self):
        self.case = Path(tempfile.mkdtemp(prefix="case-", dir=self._work))

    def _fixture_env(self, *, password=SYNTHETIC_PASSWORD, alias="fixture"):
        return _child_env(
            self._java_home,
            ANDROID_RELEASE_PASSWORD=password,
            PLAY_TRANSFER_KEY_ALIAS=alias,
        )

    def _generate(self):
        result = _run(
            [
                self._java,
                "-cp",
                self._classpath,
                "PlaySigningFixture",
                "generate",
                self.case,
            ],
            env=self._fixture_env(),
            cwd=self.case,
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stderr, "")
        self.assertNotIn(SYNTHETIC_PASSWORD, result.stdout)
        fixture = json.loads(result.stdout)
        for name in ("keystore", "recipientPublicPem", "recipientPrivatePkcs12"):
            self.assertTrue((self.case / fixture[name]).is_file(), fixture)
        return fixture

    def _transfer(
        self,
        fixture,
        *,
        password=SYNTHETIC_PASSWORD,
        recipient_hash=None,
        certificate_hash=None,
        alias="fixture",
        output=None,
        keystore=None,
    ):
        output = Path(output) if output is not None else self.case / "output.zip"
        keystore_path = (
            Path(keystore) if keystore is not None else self.case / fixture["keystore"]
        )
        expected_recipient = (
            recipient_hash if recipient_hash is not None else fixture["recipientSha256"]
        )
        expected_certificate = (
            certificate_hash
            if certificate_hash is not None
            else fixture["certificateSha256"]
        )
        env = _child_env(
            self._java_home,
            PLAY_TRANSFER_KEYSTORE=str(keystore_path),
            PLAY_TRANSFER_KEY_ALIAS=alias,
        )
        if password is not None:
            env["ANDROID_RELEASE_PASSWORD"] = password
        result = _run(
            [
                self._java,
                "-cp",
                self._classpath,
                "PlaySigningTransfer",
                self.case / fixture["recipientPublicPem"],
                output,
                expected_recipient,
                expected_certificate,
            ],
            env=env,
            cwd=self.case,
        )
        return result, output

    def _assert_fixed_failure(
        self, result, stage, *, secrets=(SYNTHETIC_PASSWORD,)
    ):
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(result.stdout, "")
        self.assertEqual(
            [line for line in result.stderr.splitlines() if line.strip()],
            [f"Signing transfer failed at stage: {stage}"],
        )
        combined = result.stdout + result.stderr
        for secret in secrets:
            if secret:
                self.assertNotIn(secret, combined)
        self.assertNotIn("Exception", combined)
        self.assertNotIn("\tat ", combined)

    def _assert_no_output(self, output):
        self.assertFalse(output.exists())
        self.assertFalse(_receipt_path(output).exists())

    def test_success_archive_receipt_certificate_and_decryption(self):
        fixture = self._generate()
        result, output = self._transfer(fixture)
        receipt_path = _receipt_path(output)

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stderr, "")
        self.assertNotIn(SYNTHETIC_PASSWORD, result.stdout + result.stderr)
        self.assertIn("Encrypted signing transfer verified", result.stdout)
        self.assertIn(fixture["certificateSha256"], result.stdout)
        self.assertTrue(output.is_file())
        self.assertTrue(receipt_path.is_file())
        self.assertEqual(list(self.case.glob(".play-signing-transfer-*")), [])

        with zipfile.ZipFile(output) as archive:
            self.assertEqual(
                archive.namelist(), ["encryptedPrivateKey", "certificate.pem"]
            )
            self.assertEqual(len(archive.infolist()), 2)
            encrypted = archive.read("encryptedPrivateKey")
            certificate_pem = archive.read("certificate.pem")

        self.assertGreater(len(encrypted), 256)
        self.assertEqual(
            _pem_certificate_sha256(certificate_pem), fixture["certificateSha256"]
        )

        receipt = json.loads(receipt_path.read_text(encoding="utf-8"))
        self.assertEqual(
            set(receipt),
            {
                "format",
                "recipientSha256",
                "certificateSha256",
                "zipSha256",
                "zipSize",
            },
        )
        self.assertEqual(receipt["format"], "PEPK_RSA_AES")
        self.assertEqual(receipt["recipientSha256"], fixture["recipientSha256"])
        self.assertEqual(receipt["certificateSha256"], fixture["certificateSha256"])
        self.assertEqual(
            receipt["zipSha256"], hashlib.sha256(output.read_bytes()).hexdigest()
        )
        self.assertEqual(receipt["zipSize"], output.stat().st_size)

        verify = _run(
            [
                self._java,
                "-cp",
                self._classpath,
                "PlaySigningFixture",
                "verify",
                output,
                self.case / fixture["keystore"],
                self.case / fixture["recipientPrivatePkcs12"],
            ],
            env=self._fixture_env(),
            cwd=self.case,
        )
        self.assertEqual(verify.returncode, 0, verify.stderr)
        self.assertEqual(verify.stdout.strip(), "PASS")
        self.assertEqual(verify.stderr, "")

    def test_wrong_recipient_hash_is_rejected_without_output(self):
        fixture = self._generate()
        result, output = self._transfer(
            fixture,
            recipient_hash=hashlib.sha256(b"wrong recipient").hexdigest(),
        )
        self._assert_fixed_failure(result, "recipient")
        self._assert_no_output(output)

    def test_wrong_certificate_fingerprint_is_rejected_without_output(self):
        fixture = self._generate()
        result, output = self._transfer(
            fixture,
            certificate_hash=hashlib.sha256(b"wrong certificate").hexdigest(),
        )
        self._assert_fixed_failure(result, "certificate")
        self._assert_no_output(output)

    def test_wrong_password_is_rejected_and_not_printed(self):
        fixture = self._generate()
        result, output = self._transfer(fixture, password=WRONG_PASSWORD)
        self._assert_fixed_failure(
            result,
            "certificate",
            secrets=(WRONG_PASSWORD, SYNTHETIC_PASSWORD),
        )
        self._assert_no_output(output)

    def test_missing_password_is_rejected_and_not_printed(self):
        fixture = self._generate()
        result, output = self._transfer(fixture, password=None)
        self._assert_fixed_failure(result, "certificate")
        self._assert_no_output(output)

    def test_existing_output_is_preserved(self):
        fixture = self._generate()
        output = self.case / "output.zip"
        sentinel = b"existing-output-sentinel"
        output.write_bytes(sentinel)
        result, returned = self._transfer(fixture, output=output)
        self.assertEqual(returned, output)
        self._assert_fixed_failure(result, "inputs")
        self.assertEqual(output.read_bytes(), sentinel)
        self.assertFalse(_receipt_path(output).exists())

    def test_existing_receipt_is_preserved(self):
        fixture = self._generate()
        output = self.case / "output.zip"
        receipt_path = _receipt_path(output)
        sentinel = b"existing-receipt-sentinel"
        receipt_path.write_bytes(sentinel)
        result, returned = self._transfer(fixture, output=output)
        self.assertEqual(returned, output)
        self._assert_fixed_failure(result, "inputs")
        self.assertFalse(output.exists())
        self.assertEqual(receipt_path.read_bytes(), sentinel)

    def test_bad_alias_is_rejected_without_output(self):
        fixture = self._generate()
        result, output = self._transfer(fixture, alias="missing-fixture-alias")
        self._assert_fixed_failure(result, "certificate")
        self._assert_no_output(output)

    def test_mismatched_private_key_and_certificate_are_rejected(self):
        fixture = self._generate()
        mismatch = _run(
            [
                self._java,
                "-cp",
                self._classpath,
                "PlaySigningFixture",
                "mismatch",
                self.case,
            ],
            env=self._fixture_env(),
            cwd=self.case,
        )
        self.assertEqual(mismatch.returncode, 0, mismatch.stderr)
        self.assertEqual(mismatch.stderr, "")
        self.assertNotIn(SYNTHETIC_PASSWORD, mismatch.stdout)
        mismatch_fixture = json.loads(mismatch.stdout)
        result, output = self._transfer(
            fixture,
            keystore=self.case / mismatch_fixture["keystore"],
        )
        self._assert_fixed_failure(result, "certificate")
        self._assert_no_output(output)

    def test_fixture_writes_only_public_and_encrypted_material(self):
        self._generate()
        files = {path.name for path in self.case.iterdir() if path.is_file()}
        self.assertEqual(
            files,
            {"file.p12", "recipient-private.p12", "recipient-public.pem"},
        )
        for pem in self.case.glob("*.pem"):
            self.assertTrue(
                pem.read_bytes().startswith(b"-----BEGIN PUBLIC KEY-----")
            )
        self.assertEqual(list(self.case.glob("*.der")), [])


if __name__ == "__main__":
    unittest.main()
