"""In-process TOTP generation; CLI deliberately never prints the code."""
import base64
import hashlib
import hmac
import json
import os
from pathlib import Path
import struct
import time


def code(secret, at=None):
    at = time.time() if at is None else at
    key = base64.b32decode(secret + '=' * ((-len(secret)) % 8))
    digest = hmac.new(key, struct.pack('>Q', int(at // 30)), hashlib.sha1).digest()
    offset = digest[-1] & 15
    return f'{(struct.unpack(">I", digest[offset:offset+4])[0] & 0x7fffffff) % 1000000:06d}'


def current():
    root = Path(os.environ['LOCALAPPDATA']) / 'meshcentral-livebench'
    secret = json.loads((root / 'credentials.json').read_text())['totp-user']['otpsecret']
    remaining = 30 - time.time() % 30
    if remaining < 8:
        time.sleep(remaining + 1)
    return code(secret)


if __name__ == '__main__':
    current()
    print('TOTP generated successfully; value withheld. Import current() for UI entry.')
