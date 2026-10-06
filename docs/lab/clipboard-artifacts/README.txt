Clipboard clearing lab - what is kept where

The report is docs/lab/clipboard.md. Everything the lab made is in this folder; nothing is left in a scratchpad.

SensitiveClipboard.kt (this folder)
  The recommended code for package io.github.dilshodbekmx.passwordvault.clipboard. It was generated from the
  file measured in the lab (kit/project/app/src/main/java/com/example/pvlab/clip/SensitiveClipboard.kt, build 3,
  SHA-256 in kit/results/SensitiveClipboard.kt.sha256.build3) by kit/gen_snippet.py, which removes the 13 lines
  marked LAB-ONLY (measurement logging) and the lab-only function dropTimerForLab, and renames the package and
  the two string constants that contain it. The generated file compiles (kit/logs/build-snippet-check.log).
  It is identical to kit/snippet/SensitiveClipboard.kt; kit/results/SensitiveClipboard.lab-vs-snippet.diff
  shows what the generator changed.
  Manifest lines to add: see section 6 of the report. architecture.md D-14 decides what the app takes from it.

In the report
  Appendix A: how each mechanism was called in the lab app (kit/project/.../Lab.kt).
  Appendix B: the driver kit/clipexp.py up to and including run_once.
  Appendix C: the watcher activity kit/project/.../ProbeActivity.kt.

kit/ (clipboard-lab-kit.tar.gz, unpacked on 2026-10-06 by build-plan package 0.3; nothing in it was changed)
  project/        the complete test app com.example.pvlab.clip (minSdk 24, targetSdk 37, WorkManager 2.12.0) with
                  the three manifest variants as product flavours: full (USE_EXACT_ALARM, and SCHEDULE_EXACT_ALARM
                  with maxSdkVersion 32), sea (SCHEDULE_EXACT_ALARM only), noperm (neither; it also plays
                  "the other app"). Its local.properties named this machine's SDK and is not kept.
  clipexp.py      the complete driver: every scenario and plan, and main.
  runplans.sh     starts one driver per emulator port in the background; stopdrivers.sh stops them.
  analyze.py      a summary of results/runs-*.jsonl; report_tables.py builds the report's tables from the same
                  records.
  gen_snippet.py  writes snippet/SensitiveClipboard.kt from the measured file (see above).
  results/        runs-emulator-<port>.jsonl: one JSON record per run or scripted scenario; runs.jsonl: the two
                  smoke runs; report-tables.md: the tables of the report and one line per run; driver-*.log and
                  pvclip-events-*.log: the drivers' and the app's logs; aborted/: a first batch that was stopped.
                  Paths inside the records point to the lab's old directory in the scratchpad; the screenshots
                  they name are in shots/.
  shots/          the overlay screenshots and UI dumps behind section 5 of the report.
  logs/           the four builds of the test app and the compile check of the snippet.

To run it again (only on a private emulator, never on the user's own device on port 5554)
  Start a device with tools/emu/boot.sh (build-plan package 0.2) and use the serial it prints.
  cd kit/project; ANDROID_HOME=/home/asus/Android/Sdk JAVA_HOME=/home/asus/.jdks/jdk-25 ./gradlew --no-daemon assembleDebug
    (once tools/gradle.sh exists, build through it instead: tools/gradle.sh -p docs/lab/clipboard-artifacts/kit/project assembleDebug)
  adb -s <serial> install the full and noperm debug APKs (and sea for the permission scenario)
  cd kit; SERIAL=<serial> BUILD=4 python3 clipexp.py base kill freeze screenoff swipe verify
  python3 report_tables.py
  clipexp.py falls back to emulator-5560 when SERIAL is not set, so always set it. runplans.sh takes its ports
  from PORTS. The swipe condition taps fixed coordinates for a 1080x2400 screen; the locked condition sets and
  removes the screen-lock PIN 1111.
