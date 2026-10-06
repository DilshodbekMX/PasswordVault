#!/usr/bin/env python3
"""Tiny uiautomator driver for headless emulators.

  ui.py SERIAL list                  print every node with text/desc/id and its bounds
  ui.py SERIAL tap REGEX [N]         tap the N-th (default 0) node whose text|desc|id matches REGEX
  ui.py SERIAL wait REGEX [SECONDS]  wait until a node matches REGEX (exit 1 on timeout)
  ui.py SERIAL has REGEX             exit 0 if a node matches now, else 1
"""
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ADB = "/home/asus/Android/Sdk/platform-tools/adb"


def adb(serial, *args, timeout=60):
    return subprocess.run([ADB, "-s", serial, *args], capture_output=True, text=True, timeout=timeout)


def dump(serial):
    last = ""
    for _ in range(6):
        r = adb(serial, "shell", "uiautomator", "dump", "/sdcard/ui.xml")
        last = (r.stdout + r.stderr).strip()
        x = adb(serial, "shell", "cat", "/sdcard/ui.xml").stdout
        if "<hierarchy" in x and "</hierarchy>" in x:
            x = x[x.index("<hierarchy"): x.rindex("</hierarchy>") + len("</hierarchy>")]
            return ET.fromstring(x)
        time.sleep(1)
    raise SystemExit("uiautomator dump failed: " + last)


def center(bounds):
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", bounds))
    return (x1 + x2) // 2, (y1 + y2) // 2


def matches(root, pattern):
    rx = re.compile(pattern, re.I)
    out = []
    for n in root.iter("node"):
        for attr in ("text", "content-desc", "resource-id"):
            v = n.get(attr) or ""
            if v and rx.search(v):
                out.append(n)
                break
    return out


def main():
    serial, cmd = sys.argv[1], sys.argv[2]
    if cmd == "list":
        for n in dump(serial).iter("node"):
            t, d, i = n.get("text") or "", n.get("content-desc") or "", n.get("resource-id") or ""
            if t or d or i:
                print(f"{n.get('bounds'):>24} click={n.get('clickable')[0]} pkg={n.get('package')} "
                      f"id={i.split('/')[-1]!r} text={t!r} desc={d!r}")
    elif cmd == "tap":
        idx = int(sys.argv[4]) if len(sys.argv) > 4 else 0
        found = matches(dump(serial), sys.argv[3])
        if len(found) <= idx:
            print(f"no node matches {sys.argv[3]!r}")
            sys.exit(1)
        x, y = center(found[idx].get("bounds"))
        adb(serial, "shell", "input", "tap", str(x), str(y))
        print(f"tapped {found[idx].get('text') or found[idx].get('content-desc')!r} at {x},{y}")
    elif cmd == "wait":
        limit = float(sys.argv[4]) if len(sys.argv) > 4 else 30
        t0 = time.time()
        while time.time() - t0 < limit:
            if matches(dump(serial), sys.argv[3]):
                print(f"found {sys.argv[3]!r} after {time.time() - t0:.1f}s")
                return
            time.sleep(1)
        print(f"timeout waiting for {sys.argv[3]!r}")
        sys.exit(1)
    elif cmd == "has":
        sys.exit(0 if matches(dump(serial), sys.argv[3]) else 1)
    else:
        print(__doc__)
        sys.exit(2)


if __name__ == "__main__":
    main()
