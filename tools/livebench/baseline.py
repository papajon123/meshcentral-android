"""Repeatable client authentication baseline via ui.py; private output only.

Run after livebench.ps1 -Start with ANDROID_SERIAL=emulator-5570.
This resets only the debug client's app data on that owned emulator.
Remote-tool observations are deliberately separate from these automatic checks.
"""
import json
import os
import sys
import time

import ui
import totp

PACKAGE = 'io.github.papajon123.meshcentral.debug'
ACTIVITY = PACKAGE + '/ru.papajon.meshcentralmobile.MainActivity'
OUTPUT = ui.ROOT / 'baseline'


def labels():
    return [node.get('text', '') for node, _ in ui.nodes()
            if not node.get('class', '').endswith('EditText') and node.get('password') != 'true']


def wait_any(expected, timeout=30):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        current = labels()
        for value in expected:
            if any(value.lower() in text.lower() for text in current):
                return value
        time.sleep(1)
    return None


def configure():
    if ui.serial() != 'emulator-5570':
        raise RuntimeError('Client baseline may only run on emulator-5570')
    ui.adb('shell', 'pm', 'clear', PACKAGE)
    ui.adb('shell', 'am', 'start', '-n', ACTIVITY)
    if not wait_any(['Server address']):
        raise RuntimeError('Server address screen missing')
    ui.field(0, 'https://10.0.2.2:4443')
    ui.adb('shell', 'input', 'keyevent', 'KEYCODE_BACK')
    ui.tap('Trust self-signed certificates')
    ui.tap('Save address')
    if not wait_any(['Username']):
        raise RuntimeError('Login screen missing')


def enter_login(user, password):
    ui.field(0, user)
    ui.field(1, password)
    ui.adb('shell', 'input', 'keyevent', 'KEYCODE_BACK')
    ui.tap('Sign in', occurrence=-1)


def main():
    credentials = json.loads((ui.ROOT / 'credentials.json').read_text())
    OUTPUT.mkdir(parents=True, exist_ok=True)
    results = []
    def record(name, passed, observation):
        results.append({'scenario': name, 'passed': passed, 'observation': observation})
        (OUTPUT / 'auth-ui.json').write_text(json.dumps(results, indent=2) + '\n')
        print(name + ': ' + ('PASS' if passed else 'FAIL'))

    configure()
    enter_login('admin', 'intentionally-invalid-password')
    outcome = wait_any(['MeshCentral rejected the username or password.', 'Computers'])
    record('wrong-password', outcome == 'MeshCentral rejected the username or password.', outcome or 'No outcome within 30 seconds')
    # No screenshot of credential fields, even though the wrong password is synthetic.
    configure()
    enter_login('admin', credentials['admin']['password'])
    outcome = wait_any(['Computers', 'sign-in failed'])
    record('password-login-and-devices', outcome == 'Computers', outcome or 'No outcome within 30 seconds')
    if outcome == 'Computers':
        ui.shot(OUTPUT / '10-auth-password-devices.png')
    configure()
    enter_login('totp-user', credentials['totp-user']['password'])
    outcome = wait_any(['Verification code required', 'Computers', 'sign-in failed'])
    record('totp-challenge', outcome == 'Verification code required', outcome or 'No challenge within 30 seconds')
    if outcome == 'Verification code required':
        ui.field(0, totp.current())
        ui.adb('shell', 'input', 'keyevent', 'KEYCODE_BACK')
        ui.tap('Sign in with code', occurrence=-1)
        outcome = wait_any(['Computers', 'sign-in failed'])
        record('totp-login', outcome == 'Computers', outcome or 'No outcome within 30 seconds')
        if outcome == 'Computers':
            ui.shot(OUTPUT / '11-auth-totp-devices.png')
    else:
        record('totp-login', False, 'Client did not expose the code field; server TOTP is checked separately')
    if not all(result['passed'] for result in results):
        sys.exit(1)


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        # Never emit an exception with subprocess arguments containing credentials.
        print(type(error).__name__ + ': baseline stopped; inspect the owned emulator.', file=sys.stderr)
        sys.exit(1)
