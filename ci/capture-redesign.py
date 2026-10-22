"""Capture production Compose screens on an explicitly selected test emulator.

Build/install debug and androidTest APKs first. No app data is cleared.
"""
import argparse
import json
import os
from pathlib import Path
import re
import shutil
import subprocess

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--serial', required=True)
parser.add_argument('--package', default='io.github.papajon123.meshcentral.debug')
parser.add_argument('--theme', choices=('light', 'dark'), default='light')
parser.add_argument('--locale', default='en')
parser.add_argument('--output', type=Path, required=True)
args = parser.parse_args()
if not args.serial.startswith('emulator-') or not args.package.endswith('.debug'):
    parser.error('Use a dedicated emulator and a debug package.')
sdk = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
adb_exe = shutil.which('adb') or (str(Path(sdk) / 'platform-tools' / ('adb.exe' if os.name == 'nt' else 'adb')) if sdk else None)
if not adb_exe:
    parser.error('Put adb on PATH or set ANDROID_HOME.')

def adb(*command):
    return subprocess.check_output([adb_exe, '-s', args.serial, *command], timeout=180)

api_level = int(adb('shell', 'getprop', 'ro.build.version.sdk'))
if api_level < 33 and args.locale != 'en':
    parser.error('Localized native modal windows require API 33+ for this capture runner. Use an English emulator on older Android.')
args.output.mkdir(parents=True, exist_ok=True)
old_locales = None
try:
    if api_level >= 33:
        raw = adb('shell', 'cmd', 'locale', 'get-app-locales', args.package).decode()
        match = re.search(r'\[([^]]*)\]', raw)
        if not match:
            raise RuntimeError('Could not read the existing app locale; no locale was changed.')
        old_locales = match[1]
        adb('shell', 'cmd', 'locale', 'set-app-locales', args.package, '--locales', args.locale)
    result = adb('shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
                 'ru.papajon.meshcentralmobile.ui.RedesignGalleryTest',
                 '-e', 'redesignGallery', 'true', '-e', 'galleryPhase', 'after',
                 '-e', 'galleryTheme', args.theme, '-e', 'galleryLocale', args.locale,
                 args.package + '.test/androidx.test.runner.AndroidJUnitRunner')
finally:
    if old_locales is not None:
        adb('shell', 'cmd', 'locale', 'set-app-locales', args.package, '--locales', old_locales or '""')
(args.output / 'instrumentation.log').write_bytes(result)
if b'OK (1 test)' not in result or b'FAILURES!!!' in result:
    raise RuntimeError('Gallery failed; inspect instrumentation.log.')
raw_manifest = adb('exec-out', 'run-as', args.package, 'cat', 'cache/redesign-after-manifest.json')
manifest = json.loads(raw_manifest)
for record in manifest['screenshots']:
    filename = record['file']
    if Path(filename).name != filename or not filename.endswith('.png'):
        raise RuntimeError('Invalid screenshot filename in manifest.')
    data = adb('exec-out', 'run-as', args.package, 'cat', 'cache/' + filename)
    if not data.startswith(b'\x89PNG\r\n\x1a\n'):
        raise RuntimeError('Invalid PNG: ' + filename)
    (args.output / filename).write_bytes(data)
(args.output / 'manifest.json').write_bytes(raw_manifest)
print(json.dumps({'screenshots': len(manifest['screenshots']), 'api': api_level,
                  'theme': args.theme, 'locale': args.locale, 'passed': True}))
