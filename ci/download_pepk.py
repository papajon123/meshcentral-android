#!/usr/bin/env python3
"""Download the exact public PEPK binary inspected for this Google Play setup."""
import hashlib
import pathlib
import urllib.request

URL = "https://www.gstatic.com/play-apps-publisher-rapid/signing-tool/prod/pepk.jar"
SHA256 = "aaccc0774b240aa5304bdad2a49865e92f229ca73209ecb6eaafe75dc858e24e"
TARGET = pathlib.Path(".tools/pepk.jar")


def main():
    if TARGET.is_file() and hashlib.sha256(TARGET.read_bytes()).hexdigest() == SHA256:
        print(TARGET.as_posix())
        return
    with urllib.request.urlopen(URL, timeout=60) as response:
        if response.geturl() != URL:
            raise RuntimeError("Unexpected PEPK redirect")
        data = response.read(16 * 1024 * 1024 + 1)
    if len(data) > 16 * 1024 * 1024 or hashlib.sha256(data).hexdigest() != SHA256:
        raise RuntimeError("PEPK integrity check failed")
    TARGET.parent.mkdir(parents=True, exist_ok=True)
    TARGET.write_bytes(data)
    print(TARGET.as_posix())


if __name__ == "__main__":
    main()
