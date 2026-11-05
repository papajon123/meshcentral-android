"""Fetch the pinned official bundletool, verifying it before any execution."""
from pathlib import Path
import sys
import tempfile
import urllib.request
from verify_play_bundle import BUNDLETOOL_SHA256, sha256_file

ROOT = Path(__file__).resolve().parent.parent
VERSION = '1.18.3'
URL = f'https://github.com/google/bundletool/releases/download/{VERSION}/bundletool-all-{VERSION}.jar'

def main():
    target = ROOT / '.tools' / f'bundletool-all-{VERSION}.jar'
    target.parent.mkdir(exist_ok=True)
    if target.exists() and sha256_file(target) == BUNDLETOOL_SHA256:
        print(target.relative_to(ROOT).as_posix())
        return
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(dir=target.parent, suffix='.download', delete=False) as output:
            temporary = Path(output.name)
            with urllib.request.urlopen(URL, timeout=60) as response:
                total = 0
                while chunk := response.read(1024 * 1024):
                    total += len(chunk)
                    if total > 64 * 1024 * 1024:
                        raise RuntimeError('bundletool download exceeds its size limit')
                    output.write(chunk)
        if sha256_file(temporary) != BUNDLETOOL_SHA256:
            raise RuntimeError('bundletool checksum mismatch')
        temporary.replace(target)
        print(target.relative_to(ROOT).as_posix())
    finally:
        if temporary is not None and temporary.exists():
            temporary.unlink()

if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        print(f'bundletool download failed: {type(error).__name__}', file=sys.stderr)
        sys.exit(1)
