"""Scoped ADB/uiautomator driver, adapted from the earlier mc131-e2e tool.

ANDROID_SERIAL is mandatory. Only the two owned livebench serials are accepted.
Credential entry is in-process from the private data root; never use CLI secrets.
"""
import json
from datetime import datetime
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ROOT = Path(os.environ['LOCALAPPDATA']) / 'meshcentral-livebench'
ADB = Path(os.environ.get('ANDROID_HOME', str(Path(os.environ['LOCALAPPDATA']) / 'Android' / 'Sdk'))) / 'platform-tools' / 'adb.exe'
_validated_owner = None


def serial():
    global _validated_owner
    value = os.environ.get('ANDROID_SERIAL')
    if value not in ('emulator-5568', 'emulator-5570'):
        raise RuntimeError('ANDROID_SERIAL must explicitly select an owned livebench emulator')
    record = ROOT / 'emulators' / (value.split('-')[1] + '.json')
    if not record.exists():
        raise RuntimeError('No ownership record for emulator')
    owner = json.loads(record.read_text(encoding='utf-8-sig'))
    key = (value, owner['pid'], owner['created'])
    if key != _validated_owner:
        pid = owner['pid']
        if not isinstance(pid, int) or pid <= 0:
            raise RuntimeError('Invalid emulator ownership record')
        command = f"$p=Get-CimInstance Win32_Process -Filter 'ProcessId={pid}'; if($p){{@{{name=$p.Name;created=$p.CreationDate.ToUniversalTime().ToString('o')}} | ConvertTo-Json -Compress}}"
        result = subprocess.run(['pwsh', '-NoProfile', '-Command', command], capture_output=True, timeout=15)
        actual = json.loads(result.stdout.decode('utf-8-sig')) if result.stdout.strip() else {}
        if actual.get('name') not in ('qemu-system-x86_64.exe', 'qemu-system-x86_64-headless.exe') or datetime.fromisoformat(actual.get('created', '1970-01-01')) != datetime.fromisoformat(owner['created']):
            raise RuntimeError('Emulator process identity changed')
        avd = subprocess.run([str(ADB), '-s', value, 'emu', 'avd', 'name'], capture_output=True, timeout=10)
        expected = 'PublicCompatibility35' if value == 'emulator-5568' else 'PublicReadiness'
        if avd.returncode or avd.stdout.decode().splitlines()[0].strip() != expected or owner['avd'] != expected:
            raise RuntimeError('Emulator AVD ownership mismatch')
        _validated_owner = key
    return value


def adb(*args, binary=False):
    result = subprocess.run([str(ADB), '-s', serial(), *args], capture_output=True, timeout=20)
    if result.returncode:
        # Do not expose command arguments or stderr: they may contain enrollment URLs.
        raise RuntimeError('Scoped ADB command failed')
    return result.stdout if binary else result.stdout.decode('utf-8', 'replace')


def nodes():
    for _ in range(3):
        adb('shell', 'uiautomator', 'dump', '/sdcard/livebench-ui.xml')
        xml = adb('exec-out', 'cat', '/sdcard/livebench-ui.xml')
        if '<hierarchy' in xml:
            root = ET.fromstring(xml[xml.index('<hierarchy'):])
            adb('shell', 'rm', '/sdcard/livebench-ui.xml')
            for node in root.iter('node'):
                bounds = re.findall(r'\d+', node.get('bounds', ''))
                if len(bounds) != 4:
                    continue
                x1, y1, x2, y2 = map(int, bounds)
                yield node, ((x1 + x2) // 2, (y1 + y2) // 2)
            return
        time.sleep(.5)
    raise RuntimeError('No UI hierarchy returned')


def find(attr, value, occurrence=0):
    items = list(nodes())
    for exact in (True, False):
        matches = []
        for node, center in items:
            current = node.get(attr, '')
            if (current == value) if exact else (value.lower() in current.lower()):
                matches.append((node, center))
        if matches:
            return matches[occurrence]
    raise RuntimeError('Requested UI element not found')


def tap(value, attr='text', occurrence=0):
    node, (x, y) = find(attr, value, occurrence)
    adb('shell', 'input', 'tap', str(x), str(y))
    return node


def type_text(value):
    # Quote for Android's remote shell; local subprocess receives distinct arguments.
    import shlex
    adb('shell', 'input', 'text', shlex.quote(value.replace(' ', '%s')))


def field(index, value):
    edits = [(n, c) for n, c in nodes() if n.get('class', '').endswith('EditText')]
    node, (x, y) = edits[index]
    adb('shell', 'input', 'tap', str(x), str(y))
    adb('shell', 'input', 'keyevent', 'KEYCODE_MOVE_END')
    count = len(node.get('text', ''))
    if count:
        adb('shell', 'input', 'keyevent', *(['KEYCODE_DEL'] * min(count, 256)))
    if value:
        type_text(value)


def dump():
    for node, center in nodes():
        text, desc = node.get('text', ''), node.get('content-desc', '')
        if node.get('class', '').endswith('EditText') or node.get('password') == 'true':
            text, desc = '<input redacted>', '<input redacted>'
        if 'mc://' in text or 'mc://' in desc or re.search(r'\b\d{6}\b', text):
            text, desc = '<sensitive value redacted>', ''
        if text or desc:
            print(f'{center} {node.get("class", "").split(".")[-1]} text={text!r} desc={desc!r}')


def shot(filename):
    target = Path(filename).resolve()
    if not target.is_relative_to(ROOT.resolve()):
        raise RuntimeError('Screenshots must be captured in the private bench directory first')
    # Never capture a visible password, OTP or enrollment form.
    for node, _ in nodes():
        if node.get('class', '').endswith('EditText') and node.get('text'):
            raise RuntimeError('Clear input fields or leave the form before taking a screenshot')
        if 'mc://' in node.get('text', ''):
            raise RuntimeError('Enrollment screen cannot be captured')
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(adb('exec-out', 'screencap', '-p', binary=True))
    print('Screenshot saved:', target.name)


def pair_agent():
    if serial() != 'emulator-5568':
        raise RuntimeError('Agent pairing is restricted to emulator-5568')
    import shlex
    link = (ROOT / 'android-enrollment.txt').read_text().strip()
    if not link.startswith('mc://10.0.2.2:4443,'):
        raise RuntimeError('Enrollment URL must point to the isolated bench')
    adb('shell', 'am', 'start', '-n', 'com.meshcentral.agent2/com.meshcentral.agent.MainActivity')
    time.sleep(1)
    labels = [n.get('text', '') for n, _ in nodes()]
    if 'Connected' in labels and '10.0.2.2' in labels:
        print('Android agent already connected to the local bench.')
        return
    adb('shell', 'am', 'start', '-a', 'android.intent.action.VIEW', '-d', shlex.quote(link), '-n', 'com.meshcentral.agent2/com.meshcentral.agent.MainActivity')
    for _ in range(15):
        time.sleep(1)
        labels = [n.get('text', '') for n, _ in nodes()]
        if 'Connected' in labels:
            print('Android agent connected to the local bench.')
            return
        for button in ('OK', 'CONNECT', 'Allow', 'Allow all'):
            if button in labels:
                tap(button)
                break
    raise RuntimeError('Android agent did not connect; inspect its UI')


def consent():
    if serial() != 'emulator-5568':
        raise RuntimeError('Capture consent is restricted to the owned agent emulator')
    labels = [n.get('text', '') for n, _ in nodes()]
    if 'Start recording or casting with MeshCentral Agent?' not in labels:
        raise RuntimeError('Expected MeshCentral screen-capture dialog is not present')
    if 'A single app' in labels:
        tap('A single app')
        tap('Entire screen')
    tap('Start')
    print('Screen capture approved on the owned agent emulator.')


def main():
    cmd = sys.argv[1]
    if cmd == 'dump': dump()
    elif cmd in ('tap', 'tapdesc'): tap(sys.argv[2], 'text' if cmd == 'tap' else 'content-desc')
    elif cmd == 'key': adb('shell', 'input', 'keyevent', sys.argv[2])
    elif cmd == 'type': type_text(sys.argv[2])
    elif cmd == 'shot': shot(sys.argv[2])
    elif cmd == 'pair-agent': pair_agent()
    elif cmd == 'consent': consent()
    else: raise RuntimeError('Unknown UI command')


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        message = str(error) if isinstance(error, RuntimeError) else 'UI operation failed (arguments withheld).'
        print(type(error).__name__ + ': ' + message, file=sys.stderr)
        sys.exit(1)
