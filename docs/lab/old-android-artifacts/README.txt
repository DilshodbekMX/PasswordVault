Old-Android lab (API 24 and API 28 emulators) - how to boot them again and install an APK
==========================================================================================
The same commands as a page: https://claude.ai/artifact/VRGcpX15hDE2XhGnxYz5Ta (private until shared)

Everything lives under this directory (LAB below). Nothing was added to /home/asus/Android/Sdk
or /home/asus/.android: the devices are found only through ANDROID_AVD_HOME.

  LAB=/tmp/claude-1000/-home-asus-AndroidStudioProjects-PasswordVault/096e95f8-3ae0-41bf-b235-08771bb88852/scratchpad/lab/old-android

  sysimg/android-24/default/x86_64/   Android 7.0 AOSP image, x86_64-24_r08.zip (sha1 f6559e19...0181684)
  sysimg/android-28/default/x86_64/   Android 9   AOSP image, x86_64-28_r04.zip (sha1 d47a85c8...f0eb0ef0)
  avd/pvlab_api24.ini + avd/pvlab_api24.avd/config.ini   (image.sysdir.1 is an absolute path; accepted as is)
  avd/pvlab_api28.ini + avd/pvlab_api28.avd/config.ini
  dl/                                  the downloaded zips and the repository index files
  apks/                                lab APKs (see "APKs" below)
  project/                             the lab app (copy of the starter, package com.example.pvlab.old)

Device state left behind (it is kept on the userdata image between boots):
  - Screen lock PIN 1111 on both devices.
  - API 24: fingerprints with emulator finger ids 1, 2 and 3 enrolled.  API 28: finger ids 1 and 2.
  - Animations off, stay-awake on. The lab app (variant C) is installed.
  - The emulator lock screen is enabled now (it was disabled before the PIN was set).

1. Boot (each command returns immediately; the emulator keeps running in the background)
-----------------------------------------------------------------------------------------
  export LAB=/tmp/claude-1000/-home-asus-AndroidStudioProjects-PasswordVault/096e95f8-3ae0-41bf-b235-08771bb88852/scratchpad/lab/old-android
  export ANDROID_AVD_HOME=$LAB/avd
  ADB=/home/asus/Android/Sdk/platform-tools/adb

  # API 24 (Android 7.0) on port 5566
  nohup setsid /home/asus/Android/Sdk/emulator/emulator -avd pvlab_api24 -port 5566 -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader_indirect > $LAB/logs/emu24.log 2>&1 < /dev/null &

  # API 28 (Android 9) on port 5568
  nohup setsid /home/asus/Android/Sdk/emulator/emulator -avd pvlab_api28 -port 5568 -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader_indirect > $LAB/logs/emu28.log 2>&1 < /dev/null &

  # wait until booted (about 10 s on this machine with KVM)
  for p in 5566 5568; do until [ "$($ADB -s emulator-$p shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; do sleep 2; done; echo "emulator-$p booted"; done

2. Unlock after boot (PIN 1111)
-------------------------------
  for p in 5566 5568; do $ADB -s emulator-$p shell input keyevent 82; sleep 1; $ADB -s emulator-$p shell input text 1111; $ADB -s emulator-$p shell input keyevent 66; done

  API 24: this dismisses the normal lock screen.
  API 28: the image uses full-disk encryption and the PIN became the start-up password, so the
  first screen is "To start Android, enter your PIN" and sys.boot_completed is already 1 there.
  The command above decrypts; the framework then restarts (vold.decrypt=trigger_restart_framework)
  and the launcher appears without a second lock screen. Wait for it before installing:
  until $ADB -s emulator-5568 shell dumpsys window windows 2>/dev/null | grep -q 'mCurrentFocus.*Launcher'; do sleep 2; done

  Later unlocks can use a finger:  $ADB -s emulator-5566 emu finger touch 1

3. Install and start an APK
---------------------------
  $ADB -s emulator-5566 install -r $LAB/apks/lab-C-bio140a07-core1191-heapfalse.apk
  $ADB -s emulator-5568 install -r $LAB/apks/lab-C-bio140a07-core1191-heapfalse.apk
  $ADB -s emulator-5566 shell am start -n com.example.pvlab.old/.MainActivity --es run all
  $ADB -s emulator-5566 logcat -d -s PVLAB
  (adb install hung once on API 24; if it does, kill that adb client process and run it again.
   Never run adb kill-server: other labs share the adb server.)

  Any other APK, e.g. the real app:   $ADB -s emulator-5566 install -r /path/to/app-debug.apk

4. Driving the lab app
----------------------
  source $LAB/labenv.sh                   helper functions (lab_run, lab_fresh, lab_install, lab_shot, lab_finger)
  lab_fresh 5566 kdf 15                   fresh process, run an action, print the PVLAB log lines
  Actions: info kdf bc64 bc64x2 storage clip clip-bg crypto extras all bio-status bio-keygen
           bio-noauth bio-enc bio-dec secure-on secure-off
  Activities: MainActivity (Theme.AppCompat.DayNight.NoActionBar) and PlatformThemeActivity
              (@android:style/Theme.Material.Light.NoActionBar):  lab_run 5566 bio-enc 3 PlatformThemeActivity
  Fingerprint touch:  $ADB -s emulator-5566 emu finger touch 1     (id 1 is enrolled; an unknown id = wrong finger)
  $LAB/biotest.sh 5566 C-bio140a07-core1191-heapfalse MainActivity mytag   full biometric round trip
  $LAB/heapsim.sh 5566 128m 256m          smaller Java heap (adb root + setprop + framework restart; reboot undoes it)
  python3 $LAB/ui.py emulator-5566 list   UI tree;  ui.py SERIAL tap REGEX;  ui.py SERIAL wait REGEX SECONDS
  Screenshots: $ADB -s emulator-5566 exec-out screencap -p > shot.png

5. Stop
-------
  $ADB -s emulator-5566 emu kill
  $ADB -s emulator-5568 emu kill

APKs (built by ./build-variants.sh; JAVA_HOME=/home/asus/.jdks/jdk-25)
-----------------------------------------------------------------------
  apks/starter-asis-debug.apk                 the starter app unchanged except package name
  apks/lab-A-bio110-core1191-heapfalse.apk    lab app, androidx.biometric 1.1.0, largeHeap=false
  apks/lab-B-bio110-core1191-heaptrue.apk     lab app, androidx.biometric 1.1.0, largeHeap=true
  apks/lab-C-bio140a07-core1191-heapfalse.apk lab app, androidx.biometric 1.4.0-alpha07, largeHeap=false
  (variant D, biometric 1.1.0 with androidx.core forced to 1.17.0, does not compile: activity 1.13.0
   needs androidx.core.app.PictureInPictureProvider, which first appears in core 1.18.0.)

Results: returned to the orchestrator in the lab's structured output (the harness refused to write
REPORT.md). Evidence files: logs/ (logcat captures, heap simulations, builds) and shots/ (screenshots).
