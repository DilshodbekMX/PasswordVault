#!/usr/bin/env python3
"""Autofill scenario runner for experiment 2.

af_scenario.py NAME LEAVE [steps=a,e,dialog,manual] [launch extras...]
  LEAVE = finish | next | back | none
  steps (comma separated, run in order):
    a       focus field A (OutlinedTextField + TextFieldState), type alphaUserAAA
    e       focus field E (OutlinedSecureTextField), type SuperSecretMasterPw1
    dialog  open the Compose Dialog, type into its username and password fields, close it
    manual  long-press empty field B and tap the text toolbar's "Autofill" item if present
  Then leave the screen; if the autofill save UI appears, tap its Save button.
Logs: logs/af_NAME.log
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


def tap(key, scroll=True):
    for attempt in range(4):
        root = labctl.uidump()
        n = labctl.find(root, key)
        if n is not None:
            x, y = labctl.center(n)
            labctl.sh(f"input tap {x} {y}")
            print(f"  tap {key} at {x},{y}")
            return True
        if not scroll:
            break
        if attempt == 0 and ime_shown():
            labctl.sh("input keyevent KEYCODE_BACK")  # hide the IME first
        else:
            labctl.sh("input swipe 540 1500 540 900 300")  # scroll the form up
        sl(1)
    print("  tap: NOT FOUND", key)
    return False


def type_text(t):
    labctl.sh(f"input text {t}")
    print(f"  typed {t}")


def mark(msg):
    labctl.sh(f"log -t LabStep '{msg}'")
    print("STEP", msg)


def ime_shown():
    return "mInputShown=true" in labctl.sh("dumpsys input_method")


def hide_ime():
    if ime_shown():
        labctl.sh("input keyevent KEYCODE_BACK")
        sl(1)


def to_top():
    hide_ime()
    labctl.sh("input swipe 540 900 540 1900 300")
    sl(1)


def tap_toolbar(name, where):
    # The floating text toolbar is a platform PopupWindow (not in the default uiautomator dump),
    # so find it in dumpsys window and tap its centre. On an empty field with an empty clipboard
    # its only item is "Autofill".
    out = labctl.sh("dumpsys window windows")
    frame = None
    lines = out.splitlines()
    for i, line in enumerate(lines):
        if "PopupWindow:" in line and "Window #" in line:
            block = "\n".join(lines[i:i + 30])
            if "APPLICATION_ABOVE_SUB_PANEL" not in block:
                continue
            mm = re.search(r" frame=\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]", block)
            if mm:
                x0, y0, x1, y1 = map(int, mm.groups())
                frame = (x0, y0, x1 - x0, y1 - y0)
    labctl.shot(f"af_{name}_toolbar_{where}")
    if frame:
        x0, y0, w, h = frame
        print(f"  text toolbar popup ({where}) frame={frame}")
        mark(f"tap the text toolbar item (Autofill) in {where}")
        labctl.sh(f"input tap {x0 + w // 2} {y0 + h // 2}")
        sl(3)
    else:
        print(f"  no text toolbar popup shown ({where})")
        mark(f"no text toolbar popup shown in {where}")


def main():
    name, leave, rest = sys.argv[1], sys.argv[2], sys.argv[3:]
    steps = ["a", "e"]
    extras = []
    for r in rest:
        if r.startswith("steps="):
            steps = r[len("steps="):].split(",")
        else:
            extras.append(r)
    labctl.adb("logcat", "-c")
    labctl.launch(extras)
    sl(2.5)
    for st in steps:
        if st == "a":
            mark("focus A")
            tap("f_a")
            sl(2.5)
            type_text("alphaUserAAA")
            sl(1.5)
        elif st == "e":
            mark("focus E (OutlinedSecureTextField)")
            tap("f_e")
            sl(2.5)
            type_text("SuperSecretMasterPw1")
            sl(1.5)
        elif st == "dialog":
            to_top()
            mark("open dialog")
            tap("btn_dialog")
            sl(2)
            mark("focus dialog username")
            tap("d_user", scroll=False)
            sl(2.5)
            type_text("dialogUserDDD")
            sl(1)
            mark("focus dialog password")
            tap("d_pass", scroll=False)
            sl(2.5)
            type_text("DialogSecretPw9")
            sl(1.5)
            hide_ime()
            mark("close dialog")
            tap("d_close", scroll=False)
            sl(2)
        elif st == "manual":
            to_top()
            mark("long-press empty field B for the text toolbar")
            root = labctl.uidump()
            n = labctl.find(root, "f_b")
            x, y = labctl.center(n)
            labctl.sh(f"input swipe {x} {y} {x} {y} 900")
            sl(1.5)
            tap_toolbar(name, "main")
        elif st == "sheet":
            to_top()
            mark("open bottom sheet")
            tap("btn_sheet")
            sl(2.5)
            mark("focus sheet field")
            tap("s_field", scroll=False)
            sl(2.5)
            type_text("sheetSecretSSS")
            sl(1.5)
            hide_ime()
            mark("close sheet")
            tap("s_close", scroll=False)
            sl(2)
        elif st == "dialogmanual":
            to_top()
            mark("open dialog for manual autofill")
            tap("btn_dialog")
            sl(2)
            hide_ime()
            root = labctl.uidump()
            n = labctl.find(root, "d_pass")
            x, y = labctl.center(n)
            mark("long-press empty dialog password field for the text toolbar")
            labctl.sh(f"input swipe {x} {y} {x} {y} 900")
            sl(1.5)
            tap_toolbar(name, "dialog")
            hide_ime()
            tap("d_close", scroll=False)
            sl(2)
    mark(f"leave via {leave}")
    if leave in ("finish", "next"):
        to_top()
        tap("btn_finish" if leave == "finish" else "btn_next")
    elif leave == "back":
        labctl.sh("input keyevent KEYCODE_BACK")
        sl(1)
        labctl.sh("input keyevent KEYCODE_BACK")
    sl(3)
    labctl.shot(f"af_{name}_after_leave")
    root = labctl.uidump()
    save_btn = None
    for n in labctl.nodes(root):
        rid = n.get("resource-id", "")
        if rid.endswith("autofill_save_yes") or n.get("text", "").lower() == "save":
            save_btn = n
            break
    if save_btn is not None:
        texts = [n.get("text") for n in labctl.nodes(root) if n.get("text")]
        print("  SAVE UI visible, texts:", texts)
        mark("tap Save in autofill save UI")
        x, y = labctl.center(save_btn)
        labctl.sh(f"input tap {x} {y}")
        sl(3)
    else:
        print("  no save UI found")
        mark("no save UI")
    sl(1)
    log = labctl.adb("logcat", "-d", "-v", "time", "-s", "LabFill:*", "PvLab:*", "LabStep:*",
                     "AutofillManager:*", "AutofillSession:*", "AutofillManagerServiceImpl:*", "AutofillUI:*",
                     "SaveUi:*", "AutofillManagerService:*", "RemoteFillService:*",
                     "RemoteAugmentedAutofillService:*")
    path = os.path.join(HERE, "logs", f"af_{name}.log")
    with open(path, "w") as f:
        f.write(log)
    print("log ->", path)
    starts = sum(1 for l in log.splitlines() if "startSessionLocked()" in l)
    fills = [l for l in log.splitlines() if "LabFill" in l and "requestId=" in l and "flags=" in l]
    saves = [l for l in log.splitlines() if "LabFill" in l and "SAVE#" in l and "contexts=" in l]
    print(f"  SUMMARY {name}: app-side startSession calls={starts} fillRequests={len(fills)} saveRequests={len(saves)}")
    for l in fills + saves:
        print("   ", l[19:200])
    for l in log.splitlines():
        if "LabFill" in l and ("SAVE#" in l or "FILL#" in l) and ('autofillValue="' in l and 'autofillValue=""' not in l):
            print("    VALUE:", l[19:330])


if __name__ == "__main__":
    main()
