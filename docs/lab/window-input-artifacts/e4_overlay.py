#!/usr/bin/env python3
"""Experiment 4: tapjacking demo with a pass-through overlay from another app.

e4_overlay.py NAME TARGET ALPHA [launch extras...]
  TARGET = btn_target (main window) | d_close (Dialog window, needs show=dialog)
Draws a FLAG_NOT_TOUCHABLE TYPE_APPLICATION_OVERLAY (helper app) over TARGET, taps TARGET's
centre, and reports what the app saw.
"""
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import labctl  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))


def sl(s):
    time.sleep(s)


def bounds(key):
    root = labctl.uidump()
    n = labctl.find(root, key)
    if n is None:
        return None
    return tuple(map(int, re.findall(r"\d+", n.get("bounds"))))


def main():
    name, target, alpha, extras = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4:]
    labctl.sh("am broadcast -n com.example.pvlab.helper/.OverlayReceiver --ez remove true")
    labctl.adb("logcat", "-c")
    labctl.launch(extras)
    sl(3)
    b = bounds(target)
    if b is None:
        print("target not found", target)
        return
    x0, y0, x1, y1 = b
    cx, cy = (x0 + x1) // 2, (y0 + y1) // 2
    ox, oy, ow, oh = x0 + 60, y0 - 10, (x1 - x0) - 120, (y1 - y0) + 20
    out = labctl.sh(
        f"am broadcast -n com.example.pvlab.helper/.OverlayReceiver --ei x {ox} --ei y {oy} "
        f"--ei w {ow} --ei h {oh} --ef alpha {alpha} --ez touchable false")
    sl(1.5)
    labctl.shot(f"e4_{name}")
    overlay_visible = "isVisible=true" in "\n".join(
        l for l in labctl.sh("dumpsys window windows").split("Window #") if "LabOverlay" in l)
    labctl.sh(f"input tap {cx} {cy}")
    sl(1.5)
    still_there = None
    if target == "d_close":
        still_there = bounds("d_close") is not None
    labctl.sh("am broadcast -n com.example.pvlab.helper/.OverlayReceiver --ez remove true")
    log = labctl.adb("logcat", "-d", "-s", "PvLab:*", "LabOverlay:*", "InputDispatcher:*", "WindowManager:W")
    with open(os.path.join(HERE, "logs", f"e4_{name}.log"), "w") as f:
        f.write(log)
    print(f"== {name}: target={target} bounds={b} tap={cx},{cy} overlay=({ox},{oy},{ow}x{oh}) alpha={alpha} extras={extras}")
    print(f"   overlay window visible (dumpsys)={overlay_visible}")
    for line in log.splitlines():
        if any(k in line for k in ("touch DOWN", "TARGET CLICKED", "added overlay", "setHideOverlay",
                                   "untrusted", "Untrusted", "obscur", "Dropping")):
            print("   ", line[31:260])
    if still_there is not None:
        print(f"   dialog still open after tap: {still_there}")


if __name__ == "__main__":
    main()
