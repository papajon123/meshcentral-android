"""Create only a new, isolated Linux Agent fixture for the opt-in tools-plus tests.

Run inside the local Linux bench as a user who owns its fixture directory:
  python3 tools_plus_fixture.py /opt/meshcentral-livebench/fixture/tools-plus-RUN

Never overwrites an existing directory or deletes files. The printed manifest matches
app/src/androidTest/assets/tools-plus-manifest.json. Remove only this owned directory
after testing. The terminal test must start with no terminal-counter file.
"""
import hashlib
import json
from pathlib import Path
import re
import sys


def seed(directory):
    root = Path(directory)
    parent = Path('/opt/meshcentral-livebench/fixture')
    if root.parent != parent or not re.fullmatch(r'tools-plus-[a-zA-Z0-9-]+', root.name):
        raise ValueError('Expected a tools-plus-RUN directory directly under the local bench fixture')
    if parent.resolve() != parent or root.is_symlink():
        raise ValueError('Fixture paths must not be redirected through symlinks')
    root.mkdir(exist_ok=False)
    (root / 'source').mkdir()
    manifest = {}
    sizes = [0, 1, 16_380, 16_381, 32_760, 65_536, 262_144, 1_048_576]
    for index in range(30):
        size = sizes[index % 8] + (index if index % 8 else 0)
        data = bytes((offset * 17 + index) % 256 for offset in range(size))
        name = f'file-{index:02d}.bin'
        (root / 'source' / name).write_bytes(data)
        manifest[name] = {'size': size, 'sha256': hashlib.sha256(data).hexdigest()}
    for name in ('delete-a.txt', 'delete-b.txt'):
        (root / name).write_text('owned deletion test', encoding='utf-8')
    return manifest


if __name__ == '__main__':
    if len(sys.argv) != 2:
        raise SystemExit('Usage: tools_plus_fixture.py /opt/meshcentral-livebench/fixture/tools-plus-RUN')
    print(json.dumps(seed(sys.argv[1]), sort_keys=True))
