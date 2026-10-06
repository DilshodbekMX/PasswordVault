#!/usr/bin/env python3
"""Small driver for the window/input lab on emulator-5562.

Usage:
  labctl.py launch [key=value ...]   force-stop + start MainActivity with extras
                                     (true/false -> --ez, anything else -> --es)
  labctl.py shot NAME [x0 y0 x1 y1]  screencap -> shots/NAME.png, print pixel stats
  labctl.py dump [filter]            uiautomator dump, list nodes (id/text/bounds)
  labctl.py tap ID_OR_TEXT           tap the centre of the first matching node
  labctl.py bounds ID_OR_TEXT        print bounds of the first matching node
  labctl.py ime                      print the editor info part of dumpsys input_method
  labctl.py windows [grep]           dumpsys window windows, filtered
"""
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ADB = "/home/asus/Android/Sdk/platform-tools/adb"
SERIAL = "emulator-5562"
HERE = os.path.dirname(os.path.abspath(__file__))
PKG = os.environ.get("LAB_PKG", "com.example.pvlab.window")
ACTIVITY = os.environ.get("LAB_ACTIVITY", "com.example.pvlab.window.MainActivity")


def adb(*args, check=True, capture=True, binary=False):
    cmd = [ADB, "-s", SERIAL, *args]
    r = subprocess.run(cmd, capture_output=capture)
    if check and r.returncode != 0:
        sys.stderr.write((r.stderr or b"").decode(errors="replace"))
    if binary:
        return r.stdout
    return (r.stdout or b"").decode(errors="replace")


def sh(cmd):
    return adb("shell", cmd)


def launch(kv):
    extras = []
    for item in kv:
        k, v = item.split("=", 1)
        if v in ("true", "false"):
            extras += ["--ez", k, v]
        else:
            extras += ["--es", k, v]
    sh(f"am force-stop {PKG}")
    out = adb("shell", "am", "start", "-W", "-n", f"{PKG}/{ACTIVITY}", *extras)
    print(out.strip())


def shot(name, box=None):
    os.makedirs(os.path.join(HERE, "shots"), exist_ok=True)
    path = os.path.join(HERE, "shots", name + ".png")
    data = adb("exec-out", "screencap", "-p", binary=True)
    with open(path, "wb") as f:
        f.write(data)
    try:
        from PIL import Image
        im = Image.open(path).convert("RGB")
        w, h = im.size
        x0, y0, x1, y1 = box if box else (0, 0, w, h)
        region = im.crop((x0, y0, x1, y1))
        px = list(region.getdata())
        n = len(px)
        black = sum(1 for p in px if p[0] < 8 and p[1] < 8 and p[2] < 8)
        colors = {}
        for p in px[:: max(1, n // 20000)]:
            colors[p] = colors.get(p, 0) + 1
        top = sorted(colors.items(), key=lambda kv: -kv[1])[:4]
        print(f"{path} {w}x{h} region={x0},{y0},{x1},{y1} black={black / n:.3f} topColors={top}")
    except Exception as e:  # noqa
        print(path, "stats failed", e)
    return path


def uidump():
    for _ in range(3):
        out = sh("uiautomator dump /sdcard/ui.xml")
        if "dumped" in out:
            break
        time.sleep(1)
    xml = sh("cat /sdcard/ui.xml")
    xml = xml[xml.find("<?xml"):] if "<?xml" in xml else xml
    with open(os.path.join(HERE, "logs", "ui_last.xml"), "w") as f:
        f.write(xml)
    return ET.fromstring(xml)


def nodes(root):
    for n in root.iter("node"):
        yield n


def find(root, key):
    for n in nodes(root):
        rid = n.get("resource-id", "")
        if rid == key or rid.endswith("/" + key) or rid.endswith(":id/" + key):
            return n
    for n in nodes(root):
        if n.get("text", "") == key or n.get("content-desc", "") == key:
            return n
    for n in nodes(root):
        if key in n.get("text", "") or key in n.get("content-desc", ""):
            return n
    return None


def center(n):
    x0, y0, x1, y1 = map(int, re.findall(r"\d+", n.get("bounds")))
    return (x0 + x1) // 2, (y0 + y1) // 2


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return
    cmd, args = sys.argv[1], sys.argv[2:]
    if cmd == "launch":
        launch(args)
    elif cmd == "shot":
        box = tuple(map(int, args[1:5])) if len(args) >= 5 else None
        shot(args[0], box)
    elif cmd == "dump":
        root = uidump()
        flt = args[0] if args else None
        for n in nodes(root):
            line = (f"{n.get('class','')} id={n.get('resource-id','')} text={n.get('text','')!r} "
                    f"desc={n.get('content-desc','')!r} pkg={n.get('package','')} bounds={n.get('bounds')} "
                    f"focused={n.get('focused')} pwd={n.get('password')}")
            if flt is None or flt in line:
                print(line)
    elif cmd in ("tap", "bounds"):
        root = uidump()
        n = find(root, args[0])
        if n is None:
            print("NOT FOUND", args[0])
            sys.exit(2)
        x, y = center(n)
        print(f"{args[0]} bounds={n.get('bounds')} center={x},{y}")
        if cmd == "tap":
            sh(f"input tap {x} {y}")
    elif cmd == "ime":
        out = sh("dumpsys input_method")
        keep = []
        for line in out.splitlines():
            if re.search(r"inputType|imeOptions|EditorInfo|mCurClient|packageName=|fieldId|mCurFocusedWindow|privateImeOptions|hintText|mCurAttribute|curEditorInfo", line):
                keep.append(line)
        print("\n".join(keep))
    elif cmd == "windows":
        out = sh("dumpsys window windows")
        pat = args[0] if args else None
        for line in out.splitlines():
            if pat is None or re.search(pat, line):
                print(line)
    else:
        print(__doc__)


if __name__ == "__main__":
    main()
