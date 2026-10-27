"""Real-time swipes in the prepared debug app, outside the Compose instrumentation clock.

Usage: python tools/livebench/large-scroll.py <output-directory>
Requires the owned emulator-5666 and LargeServerScrollSetupTest preparation.
"""
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

SERIAL = "emulator-5666"
PACKAGE = "io.github.papajon123.meshcentral.debug"
ADB = Path(os.environ["ANDROID_HOME"]) / "platform-tools" / "adb.exe"


def adb(*args):
    return subprocess.check_output([str(ADB), "-s", SERIAL, *args], text=True, encoding="utf-8", errors="replace")


def nodes():
    for attempt in range(10):
        try:
            dumped = adb("shell", "uiautomator", "dump", "/data/local/tmp/large-scroll.xml")
            if "UI hierchary dumped" not in dumped and "UI hierarchy dumped" not in dumped:
                raise RuntimeError("UI is not ready")
            return list(ET.fromstring(adb("shell", "cat", "/data/local/tmp/large-scroll.xml")).iter("node"))
        except (subprocess.CalledProcessError, ET.ParseError, RuntimeError):
            if attempt == 9:
                raise
            time.sleep(1)


def main():
    if os.environ.get("ANDROID_SERIAL") != SERIAL:
        raise RuntimeError("Select only the owned emulator-5666")
    out = Path(sys.argv[1])
    out.mkdir(parents=True, exist_ok=True)
    current = nodes()
    consent = next((n for n in current if n.get("text") == "Not now"), None)
    if consent is not None:
        bounds = list(map(int, re.findall(r"\d+", consent.get("bounds", ""))))
        adb("shell", "input", "tap", str((bounds[0]+bounds[2])//2), str((bounds[1]+bounds[3])//2))
    deadline = time.monotonic() + 30
    while not any("2000" in n.get("text", "") for n in current) and time.monotonic() < deadline:
        time.sleep(1)
        current = nodes()
    if any(n.get("password") == "true" for n in current):
        raise RuntimeError("Expected device list, not authentication")
    if "--all" not in sys.argv:
        favorite = next((n for n in current if n.get("text", "").startswith("Favorites")), None)
        if favorite is None:
            raise RuntimeError("Prepared English device list required")
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", favorite.get("bounds", "")))
        adb("shell", "input", "tap", str((x1+x2)//2), str((y1+y2)//2))
        time.sleep(3)
        current = nodes()
        if not any(n.get("text") == "Favorites 2000" for n in current):
            raise RuntimeError("The 2000-device collected list is not ready")
    elif not any("2000" in n.get("text", "") for n in current):
        raise RuntimeError("The 2000-device full list is not ready")
    initial_rows = [int(m.group(1)) for n in current if (m := re.search(r"Workstation\s+(\d+)", n.get("text", "")))]
    if not initial_rows:
        raise RuntimeError("No synthetic device rows visible")
    initial_row = min(initial_rows)
    size = re.search(r"(\d+)x(\d+)", adb("shell", "wm", "size"))
    width, height = map(int, size.groups())
    # Warm up layout and rendering before collecting FrameStats.
    for _ in range(3):
        adb("shell", "input", "swipe", "12", str(height*4//5), "12", str(height//3), "300")
        time.sleep(.3)
    adb("shell", "dumpsys", "gfxinfo", PACKAGE, "reset")
    started = time.monotonic()
    for _ in range(12):
        adb("shell", "input", "swipe", "12", str(height*4//5), "12", str(height//3), "300")
        time.sleep(.3)
    elapsed = round((time.monotonic()-started)*1000)
    stats = adb("shell", "dumpsys", "gfxinfo", PACKAGE, "framestats")
    final_nodes = nodes()
    valid_list = any(n.get("text") == "Computers" for n in final_nodes)
    final_rows = [int(m.group(1)) for n in final_nodes if (m := re.search(r"Workstation\s+(\d+)", n.get("text", "")))]
    final_row = min(final_rows) if final_rows else -1
    valid_list = valid_list and final_row > initial_row + 5
    if not valid_list:
        (out / "invalid.txt").write_text("The app left the device list; this run is excluded.\n", encoding="utf-8")
        raise RuntimeError("Gesture left the list; invalid measurement")
    (out / "real-scroll-gfxinfo.txt").write_text(stats, encoding="utf-8")
    keys = ("Total frames rendered", "Janky frames", "50th percentile", "90th percentile", "95th percentile", "99th percentile", "Number Missed Vsync")
    summary = {"valid_list": valid_list, "initial_row": initial_row, "final_row": final_row, "devices": 2000, "swipes": 12, "elapsed_ms": elapsed,
               "gfxinfo": [line.strip() for line in stats.splitlines() if any(line.strip().startswith(k) for k in keys)]}
    (out / "real-scroll.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
    adb("shell", "screencap", "-p", "/data/local/tmp/large-server-real-scroll.png")
    adb("pull", "/data/local/tmp/large-server-real-scroll.png", str(out / "real-scroll.png"))
    print(json.dumps(summary))


if __name__ == "__main__":
    main()
