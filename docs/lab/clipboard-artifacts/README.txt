Clipboard clearing lab - what is kept where

SensitiveClipboard.kt (this folder)
  The recommended code for package io.github.dilshodbekmx.passwordvault.clipboard. It was generated from the
  file measured in the lab (com.example.pvlab.clip.SensitiveClipboard) by gen_snippet.py, which removes the
  13 lines marked LAB-ONLY (measurement logging) and the lab-only function dropTimerForLab, and renames the
  package and the two string constants that contain it. The generated file compiles.
  Manifest lines to add: see section 6 of the report.

In the report
  Appendix A: how each mechanism was called in the lab app.
  Appendix B: the driver clipexp.py up to and including run_once.
  Appendix C: the watcher activity ProbeActivity.kt.

In the scratchpad (lost at a reboot)
  /tmp/claude-1000/-home-asus-AndroidStudioProjects-PasswordVault/096e95f8-3ae0-41bf-b235-08771bb88852/scratchpad/lab/clipboard-storage/clipboard-lab-kit.tar.gz
  contains: project/ (complete test app, three manifest variants full, sea, noperm), clipexp.py (complete driver
  with all scenarios and plans), analyze.py, report_tables.py, gen_snippet.py, runplans.sh, snippet/,
  results/runs-emulator-*.jsonl (one JSON record per run), results/report-tables.md (the tables of the report
  and one line per run), and the overlay screenshots.

To run it again, for example on a phone
  cd project; ./gradlew --no-daemon assembleDebug
  adb install the full and noperm debug APKs (and sea for the permission scenario)
  SERIAL=<adb serial> BUILD=4 python3 clipexp.py base kill freeze screenoff swipe verify
  python3 report_tables.py
  The swipe condition taps fixed coordinates for a 1080x2400 screen; the locked condition sets and removes PIN 1111.
