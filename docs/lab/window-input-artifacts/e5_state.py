#!/usr/bin/env python3
"""Experiment 5: which typed text survives activity recreation, and what lands in saved state.

e5_state.py rotate|dontkeep [launch extras...]
"""
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import labctl  # noqa: E402
from af_scenario import tap, hide_ime, sl  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))


def main():
    mode, extras = sys.argv[1], sys.argv[2:]
    labctl.sh("settings put system accelerometer_rotation 0")
    labctl.sh("settings put system user_rotation 0")
    if mode == "rotate":
        labctl.sh("settings put global always_finish_activities 0")
    labctl.adb("logcat", "-c")
    labctl.launch(extras)
    sl(2.5)
    for key, text in [("f_a", "savedAlphaAAA"), ("f_b", "plainBravoBBB"), ("f_c", "vmCharlieCCC"),
                      ("f_e", "SecretEchoEEE")]:
        tap(key)
        sl(2)
        labctl.sh(f"input text {text}")
        sl(1)
    labctl.shot(f"e5_{mode}_before")
    labctl.sh("log -t LabStep 'typed all four fields'")
    if mode == "rotate":
        labctl.sh("log -t LabStep 'rotate to landscape (user_rotation 1)'")
        labctl.sh("settings put system user_rotation 1")
        sl(4)
        labctl.shot(f"e5_{mode}_after")
        labctl.sh("log -t LabStep 'rotate back (user_rotation 0)'")
        labctl.sh("settings put system user_rotation 0")
        sl(3)
    else:
        # "Don't keep activities" must be switched on in Developer options first: writing the
        # setting with `settings put` alone did not take effect on this build (see report).
        af = labctl.sh("settings get global always_finish_activities").strip()
        labctl.sh(f"log -t LabStep 'always_finish_activities={af} (set via Developer options), pressing HOME'")
        labctl.sh("input keyevent KEYCODE_HOME")
        sl(3)
        labctl.sh("log -t LabStep 'returning with the launcher intent'")
        # Same intent as the launch (component only, no action/category), so the existing task is
        # brought to front and its destroyed activity is recreated from the saved state.
        labctl.sh("am start -n com.example.pvlab.window/.MainActivity")
        sl(3)
        labctl.shot(f"e5_{mode}_after")
    log = labctl.adb("logcat", "-d", "-s", "PvLab:*", "LabStep:*")
    with open(os.path.join(HERE, "logs", f"e5_{mode}.log"), "w") as f:
        f.write(log)
    for line in log.splitlines():
        if any(k in line for k in ("LabStep", "onCreate", "onDestroy", "onSaveInstanceState", "composed:", "onStop",
                                   "LabViewModel", "AAA", "BBB", "CCC", "EEE")):
            print(line[19:400])


if __name__ == "__main__":
    main()
