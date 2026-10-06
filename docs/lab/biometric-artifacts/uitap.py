#!/usr/bin/env python3
"""uitap.py PORT REGEX  - tap the centre of the first enabled node whose text, content-desc or resource-id
matches REGEX (case-insensitive). Exit 0 if tapped, 1 if no node matched. Coordinates come from the UI tree."""
import re, subprocess, sys, xml.etree.ElementTree as ET
ADB = "/home/asus/Android/Sdk/platform-tools/adb"
port, rx = sys.argv[1], re.compile(sys.argv[2], re.I)
dev = ["-s", f"emulator-{port}"]
subprocess.run([ADB, *dev, "shell", "uiautomator", "dump", "/sdcard/ui.xml"], capture_output=True)
xml = subprocess.run([ADB, *dev, "shell", "cat", "/sdcard/ui.xml"], capture_output=True, text=True).stdout
try:
    root = ET.fromstring(xml)
except ET.ParseError:
    print("no ui tree"); sys.exit(2)
for n in root.iter("node"):
    a = n.attrib
    if a.get("enabled") != "true":
        continue
    if any(rx.search(a.get(k, "")) for k in ("text", "content-desc", "resource-id")):
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", a["bounds"]))
        x, y = (x1 + x2) // 2, (y1 + y2) // 2
        subprocess.run([ADB, *dev, "shell", "input", "tap", str(x), str(y)])
        print(f"tapped {a.get('text') or a.get('content-desc') or a.get('resource-id')!r} at {x},{y}")
        sys.exit(0)
print("no match for", sys.argv[2]); sys.exit(1)
