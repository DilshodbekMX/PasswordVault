#!/usr/bin/env python3
"""Experiment 3: report the EditorInfo the keyboard receives for each field.

ime_probe.py NAME [launch extras...]
Focuses f_a, f_d, f_e, f_f on the main screen, then the two Dialog fields and the bottom-sheet
field, and after each focus prints the EditorInfo from `dumpsys input_method`:
  - curEditorInfo   (InputMethodManagerService's copy = what was sent to the keyboard)
  - mInputEditorInfo (the keyboard process's own InputMethodService copy)
"""
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import labctl  # noqa: E402
from af_scenario import tap, hide_ime, to_top, sl  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
IME_FLAG_NO_PERSONALIZED_LEARNING = 0x1000000


def editor_info():
    out = labctl.sh("dumpsys input_method")
    res = {}
    lines = out.splitlines()
    for i, line in enumerate(lines):
        s = line.strip()
        for key in ("curEditorInfo:", "mInputEditorInfo:"):
            if s == key and key not in res and i + 1 < len(lines):
                res[key] = lines[i + 1].strip()
    shown = re.search(r"mInputShown=(\w+)", out)
    res["mInputShown"] = shown.group(1) if shown else "?"
    return res


def decode(line):
    m = re.search(r"inputType=0x([0-9a-f]+) imeOptions=0x([0-9a-f]+)", line or "")
    if not m:
        return "?"
    it, io = int(m.group(1), 16), int(m.group(2), 16)
    variation = it & 0xff0
    flags = []
    for v, n in [(0x8000, "AUTO_CORRECT"), (0x10000, "AUTO_COMPLETE"), (0x20000, "MULTI_LINE"),
                 (0x80000, "NO_SUGGESTIONS"), (0x200000, "SUGGESTION_SELECTED"), (0x1000, "CAP_CHARS"),
                 (0x2000, "CAP_WORDS"), (0x4000, "CAP_SENTENCES")]:
        if it & v:
            flags.append(n)
    var = {0x0: "normal", 0x80: "PASSWORD", 0x90: "VISIBLE_PASSWORD", 0xe0: "WEB_PASSWORD",
           0x20: "EMAIL"}.get(variation, hex(variation))
    npl = "YES" if io & IME_FLAG_NO_PERSONALIZED_LEARNING else "no"
    return f"class={it & 0xf} variation={var} flags={'|'.join(flags) or '-'} NO_PERSONALIZED_LEARNING={npl}"


def probe(label, key, scroll=True):
    ok = tap(key, scroll=scroll)
    sl(2.5)
    info = editor_info()
    cur = info.get("curEditorInfo:")
    ime = info.get("mInputEditorInfo:")
    print(f"[{label}] tapped={ok} imeShown={info['mInputShown']}")
    print(f"    IMMS curEditorInfo : {cur}")
    print(f"    IME  mInputEditorInfo: {ime}")
    print(f"    decoded: {decode(cur)}")


def main():
    name, extras = sys.argv[1], sys.argv[2:]
    labctl.adb("logcat", "-c")
    labctl.launch(extras)
    sl(2.5)
    probe("A OutlinedTextField default KeyboardOptions", "f_a")
    probe("D OutlinedTextField KeyboardOptions(autoCorrectEnabled=false, Password)", "f_d")
    probe("E OutlinedSecureTextField default", "f_e")
    probe("F SecureTextField Hidden", "f_f")
    to_top()
    tap("btn_dialog")
    sl(2)
    probe("Dialog username (default KeyboardOptions)", "d_user", scroll=False)
    probe("Dialog password (OutlinedSecureTextField)", "d_pass", scroll=False)
    hide_ime()
    tap("d_close", scroll=False)
    sl(1.5)
    to_top()
    tap("btn_sheet")
    sl(2.5)
    probe("Bottom-sheet field (default KeyboardOptions)", "s_field", scroll=False)
    hide_ime()
    tap("s_close", scroll=False)
    sl(1)
    log = labctl.adb("logcat", "-d", "-s", "PvLab:*")
    with open(os.path.join(HERE, "logs", f"ime_{name}.log"), "w") as f:
        f.write(log)
    for line in log.splitlines():
        if "EditorInfo intercepted" in line:
            print("   app log:", line[31:])


if __name__ == "__main__":
    main()
