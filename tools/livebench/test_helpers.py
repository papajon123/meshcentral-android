"""Offline tests: no devices or credentials required."""
import contextlib
import io
import os
import unittest
from types import SimpleNamespace
from unittest.mock import patch
import xml.etree.ElementTree as ET

import totp
import ui


class SafetyTests(unittest.TestCase):
    def test_rfc6238_sha1_vector(self):
        # RFC 6238 public test key; six-digit projection of the 94287082 vector.
        self.assertEqual(totp.code('GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ', 59), '287082')

    def test_requires_explicit_owned_serial(self):
        for value in ('', 'emulator-5560', 'emulator-5562', 'emulator-5566', 'physical-device'):
            with patch.dict(os.environ, {'ANDROID_SERIAL': value}):
                with self.assertRaises(RuntimeError):
                    ui.serial()

    def test_requires_ownership_record(self):
        with patch.dict(os.environ, {'ANDROID_SERIAL': 'emulator-5568'}), patch('pathlib.Path.exists', return_value=False):
            with self.assertRaises(RuntimeError):
                ui.serial()

    def test_refuses_reused_emulator_pid(self):
        record = '{"pid":123,"created":"2026-10-25T00:00:00+00:00","avd":"PublicCompatibility35"}'
        result = SimpleNamespace(stdout=b'{"name":"qemu-system-x86_64.exe","created":"2026-10-25T01:00:00+00:00"}', returncode=0)
        with patch.dict(os.environ, {'ANDROID_SERIAL': 'emulator-5568'}), patch('pathlib.Path.exists', return_value=True), patch('pathlib.Path.read_text', return_value=record), patch('subprocess.run', return_value=result) as run, patch.object(ui, '_validated_owner', None):
            with self.assertRaisesRegex(RuntimeError, 'identity changed'):
                ui.serial()
            self.assertEqual(run.call_count, 1)  # Only process inspection, no ADB mutation.

    def test_refuses_different_avd_on_owned_port(self):
        record = '{"pid":123,"created":"2026-10-25T00:00:00+00:00","avd":"PublicCompatibility35"}'
        process = SimpleNamespace(stdout=b'{"name":"qemu-system-x86_64.exe","created":"2026-10-25T00:00:00+00:00"}', returncode=0)
        avd = SimpleNamespace(stdout=b'SomeOtherAvd\nOK\n', returncode=0)
        with patch.dict(os.environ, {'ANDROID_SERIAL': 'emulator-5568'}), patch('pathlib.Path.exists', return_value=True), patch('pathlib.Path.read_text', return_value=record), patch('subprocess.run', side_effect=[process, avd]), patch.object(ui, '_validated_owner', None):
            with self.assertRaisesRegex(RuntimeError, 'AVD ownership mismatch'):
                ui.serial()

    def test_dump_redacts_inputs_and_enrollment(self):
        items = [
            (ET.fromstring('<node class="android.widget.EditText" text="synthetic-password"/>'), (0, 0)),
            (ET.fromstring('<node class="android.widget.TextView" text="mc://synthetic-enrollment"/>'), (0, 0)),
        ]
        output = io.StringIO()
        with patch.object(ui, 'nodes', return_value=iter(items)), contextlib.redirect_stdout(output):
            ui.dump()
        self.assertNotIn('synthetic-password', output.getvalue())
        self.assertNotIn('synthetic-enrollment', output.getvalue())

    def test_screenshot_refuses_filled_forms(self):
        items = [(ET.fromstring('<node class="android.widget.EditText" text="synthetic-password"/>'), (0, 0))]
        with patch.object(ui, 'nodes', return_value=iter(items)), patch.object(ui, 'adb') as adb:
            with self.assertRaises(RuntimeError):
                ui.shot(ui.ROOT / 'test.png')
            adb.assert_not_called()

    def test_adb_errors_do_not_echo_arguments(self):
        with patch.object(ui, 'serial', return_value='emulator-5568'), patch('subprocess.run') as run:
            run.return_value.returncode = 1
            with self.assertRaisesRegex(RuntimeError, '^Scoped ADB command failed$'):
                ui.adb('shell', 'synthetic-secret')


if __name__ == '__main__':
    unittest.main()
