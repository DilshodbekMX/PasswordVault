# Step 2: make the app file and put it on your phone

You need your release key from [step 1](step-1-release-key.md), your phone and its USB
cable. Parts 2.1 to 2.6 happen on the computer, parts 2.7 to 2.11 with the phone.

## 2.1 Before you start

Claude tells you when a version is ready for your phone. Then, in the terminal
(**Ctrl+Alt+T**), paste this and press Enter:

```bash
git -C /home/asus/AndroidStudioProjects/PasswordVault status --short; git -C /home/asus/AndroidStudioProjects/PasswordVault log --oneline -1
```

- The last line shows the version of the code, for example
  `2ca561f Round R4 merged ...`. Write its first seven characters on the form of step 3
  ("Built from").
- If other lines come before it, files were changed in the project folder and not saved in
  git. Ask Claude before you go on.

## 2.2 Tell the build where your key is (`keystore.properties`)

The build reads your key's password from a small file, `keystore.properties`, in the
project folder. Write it once; write it again only if it is ever deleted.

Paste this and press Enter. When it asks, type the key's password from your paper:

```bash
{
cd /home/asus/AndroidStudioProjects/PasswordVault
PV_KEY_PASS=""
while [ -z "$PV_KEY_PASS" ]; do read -r -s -p "Type the release key's password, then press Enter: " PV_KEY_PASS; echo; done
export PV_KEY_PASS
if /home/asus/.jdks/jdk-25/bin/keytool -list -keystore /home/asus/passwordvault-key/passwordvault-release.jks -storepass:env PV_KEY_PASS -alias passwordvault > /dev/null 2>&1; then
  printf 'storeFile=%s\nstorePassword=%s\nkeyAlias=%s\nkeyPassword=%s\n' /home/asus/passwordvault-key/passwordvault-release.jks "$PV_KEY_PASS" passwordvault "$PV_KEY_PASS" > keystore.properties \
  && chmod 600 keystore.properties && echo "DONE: keystore.properties is written."
else
  echo "STOP: this password does not open the key in /home/asus/passwordvault-key. Nothing was written."
fi
unset PV_KEY_PASS
}
```

You will see `DONE: keystore.properties is written.`

If you see `STOP: this password does not open the key ...`, paste the box again and type
the password slowly. If the key folder is missing (a new computer), bring the key back
first: step 1, part 1.8.

`keystore.properties` holds your key's password as plain text. It stays on this computer:
never send it to anyone, and never copy it into a message.

## 2.3 Check that git will never take `keystore.properties`

Paste this and press Enter:

```bash
{
if git -C /home/asus/AndroidStudioProjects/PasswordVault check-ignore -q keystore.properties; then echo "OK: git ignores keystore.properties: it can never be committed by mistake."; else echo "STOP: git does not ignore keystore.properties. Do not commit anything. Ask Claude."; fi
}
```

You must see `OK: git ignores keystore.properties ...`. The key itself is in
`/home/asus/passwordvault-key`, outside the project folder, so git never sees it.

## 2.4 Build the app file

Paste this and press Enter:

```bash
cd /home/asus/AndroidStudioProjects/PasswordVault && tools/gradle.sh :app:assembleRelease
```

It takes from one to ten minutes. Many lines pass by. It may say
`gradle.sh: all 2 build slots are taken; waiting.`: Claude is building something at the
same time, and your build starts by itself when a slot is free.

At the end you must see `BUILD SUCCESSFUL`. The build also checked the app on the way: for
example, that it asks for no internet access.

Then paste this:

```bash
ls -l /home/asus/AndroidStudioProjects/PasswordVault/app/build/outputs/apk/release/
```

You must see `app-release.apk`.

| You see | What it means |
|---|---|
| `app-release-unsigned.apk` instead of `app-release.apk` | The build did not find `keystore.properties`. Do part 2.2, then 2.4 again. Never install an unsigned file. |
| `BUILD FAILED` | Nothing was made. Copy the last 30 lines of the terminal into a message to Claude. |
| `Build checks failed` | The app broke one of its own rules. Do not install anything. Tell Claude. |

## 2.5 Check the app file

Paste this and press Enter:

```bash
{
APK=/home/asus/AndroidStudioProjects/PasswordVault/app/build/outputs/apk/release/app-release.apk
BT=/home/asus/Android/Sdk/build-tools/36.0.0
if [ ! -f "$APK" ]; then echo "STOP: app-release.apk is not there. Build it first (part 2.4)."; else
  want=$(/home/asus/.jdks/jdk-25/bin/keytool -printcert -file /home/asus/passwordvault-key/passwordvault-release-certificate.pem | grep -m1 'SHA256:' | sed 's/.*SHA256: //; s/://g' | tr 'A-F' 'a-f')
  got=$($BT/apksigner -J-enable-native-access=ALL-UNNAMED verify --print-certs "$APK" 2>/dev/null | grep -m1 'certificate SHA-256 digest' | awk '{print $NF}')
  if [ -n "$want" ] && [ "$got" = "$want" ]; then echo "OK: it is signed with your release key."; else echo "STOP: it is NOT signed with your release key. Do not install it."; fi
  PERMS=$($BT/aapt2 dump permissions "$APK" 2>/dev/null)
  if [ -z "$PERMS" ]; then echo "STOP: the app file cannot be read. Build it again."; elif printf '%s' "$PERMS" | grep -q 'android.permission.INTERNET'; then echo "STOP: it asks for the internet. Do not install it."; else echo "OK: it cannot use the internet."; fi
  if $BT/zipalign -c -P 16 4 "$APK" > /dev/null 2>&1; then echo "OK: it is ready for phones with 16 KB memory pages."; else echo "STOP: it is not ready for phones with 16 KB memory pages. Do not install it."; fi
  $BT/aapt2 dump badging "$APK" 2>/dev/null | grep -m1 -o "versionCode='[0-9]*' versionName='[^']*'"
fi
}
```

You must see three `OK` lines and the version, for example:

```
OK: it is signed with your release key.
OK: it cannot use the internet.
OK: it is ready for phones with 16 KB memory pages.
versionCode='1' versionName='1.0'
```

`versionName` is the version people see (1.0); `versionCode` is the build number that
Android compares in an update. Settings › Version in the app shows both: "Version 1.0 (1)".

If any line starts with `STOP`, do not install the file. A file signed with any other key
(for example the test key of Android Studio) could never be updated with your key later.

The check compares the app file with your key's public part
(`passwordvault-release-certificate.pem`), so it needs no password. To see the details
yourself:

```bash
/home/asus/Android/Sdk/build-tools/36.0.0/apksigner -J-enable-native-access=ALL-UNNAMED verify --print-certs --verbose /home/asus/AndroidStudioProjects/PasswordVault/app/build/outputs/apk/release/app-release.apk
```

It prints `Verifies`, `Verified using v2 scheme (APK Signature Scheme v2): true` and
`Signer #1 certificate DN: CN=PasswordVault release key, C=UZ`. A test build says
`CN=Android Debug` there.

## 2.6 Keep a copy of the app file

Paste this and press Enter:

```bash
{
APK=/home/asus/AndroidStudioProjects/PasswordVault/app/build/outputs/apk/release/app-release.apk
NAME=$(/home/asus/Android/Sdk/build-tools/36.0.0/aapt2 dump badging "$APK" 2>/dev/null | awk -F"'" '/^package:/ {print "passwordvault-" $6 "-build-" $4 ".apk"}')
if [ -z "$NAME" ]; then echo "STOP: app-release.apk is not there. Build it first (part 2.4)."; else
  mkdir -p /home/asus/passwordvault-releases && cp "$APK" "/home/asus/passwordvault-releases/$NAME" && echo "DONE: /home/asus/passwordvault-releases/$NAME"
fi
}
```

You will see, for example, `DONE: /home/asus/passwordvault-releases/passwordvault-1.0-build-1.apk`.
The folder keeps every version you gave the phone. The files hold no passwords.

## 2.7 Prepare the phone (the first time only)

1. **Developer options.** Open the phone's Settings › About phone, and tap **Build number**
   seven times. The phone may ask for its screen lock, then says that you are now a
   developer.
   - Samsung: Settings › About phone › Software information › Build number.
   - Xiaomi, Redmi, POCO: Settings › About phone › tap **OS version** (or **MIUI version**)
     seven times.
2. **USB debugging.** Open Settings › System › Developer options (Samsung: Settings ›
   Developer options; Xiaomi: Settings › Additional settings › Developer options) and turn
   on **USB debugging**.
3. Xiaomi, Redmi, POCO only: in Developer options also turn on **Install via USB**. It may
   ask you to sign in to a Mi account.
4. Samsung only: if Settings › Security and privacy › **Auto Blocker** is on, it blocks
   installing over USB. Turn it off before part 2.9 and on again after part 2.11.

## 2.8 Connect the phone

1. Connect the phone to the computer with the USB cable. If the phone asks what the USB
   connection is for, choose **File transfer**.
2. The phone asks **Allow USB debugging?** Tick **Always allow from this computer** and tap
   **Allow**. If it does not ask, unplug the cable and plug it in again.
3. Paste this and press Enter:

   ```bash
   /home/asus/Android/Sdk/platform-tools/adb devices -l
   ```

   You see a list. Lines that start with `emulator-` are emulators: yours and Claude's.
   Leave them alone. **Your phone is the line with `usb:` in it**, for example:

   ```
   R58M12ABCDE            device usb:1-4 product:a52qnseea model:SM_A525F device:a52q transport_id:7
   ```

   | Your phone's line says | What to do |
   |---|---|
   | `device` as its second word | Good. Go on. |
   | `unauthorized` | Look at the phone and tap **Allow**. Paste the command again. |
   | `no permissions` | Ubuntu needs a rule for phones. Paste `sudo apt install android-sdk-platform-tools-common`, type your computer password, then unplug and plug the phone. |
   | There is no line with `usb:` | Check that USB debugging is on, that the phone said **File transfer**, and try another cable: some cables only charge. |

4. Write your phone's facts on the first page of the form of step 3. Paste this and press
   Enter:

   ```bash
   {
   PHONE=$(/home/asus/Android/Sdk/platform-tools/adb devices -l | awk '/ usb:/ && $2 == "device" {print $1; exit}')
   if [ -z "$PHONE" ]; then echo "STOP: no phone found. Do part 2.8 again."; else
     A="/home/asus/Android/Sdk/platform-tools/adb -s $PHONE shell"
     PS=$($A getconf PAGE_SIZE 2>/dev/null | tr -d '\r')
     echo "Phone:            $($A getprop ro.product.manufacturer | tr -d '\r') $($A getprop ro.product.model | tr -d '\r')"
     echo "Android:          $($A getprop ro.build.version.release | tr -d '\r') (API $($A getprop ro.build.version.sdk | tr -d '\r'))"
     echo "RAM:              $($A cat /proc/meminfo | awk '/^MemTotal/ {printf "%.1f GB", $2 / 1048576}')"
     echo "Memory page size: ${PS:-4096}"
     echo "PasswordVault:    $($A dumpsys package io.github.dilshodbekmx.passwordvault | tr -d '\r' | awk '/versionCode=/ && c == "" {c = $1; sub(/.*=/, "", c)} /versionName=/ && n == "" {n = $1; sub(/.*=/, "", n)} END {if (n == "") print "not installed"; else print n " (build " c ")"}')"
   fi
   }
   ```

## 2.9 Install the app over the USB cable (the best way)

Paste this and press Enter:

```bash
{
PHONE=$(/home/asus/Android/Sdk/platform-tools/adb devices -l | awk '/ usb:/ && $2 == "device" {print $1; exit}')
if [ -z "$PHONE" ]; then echo "STOP: no phone found. Do part 2.8 again."
else /home/asus/Android/Sdk/platform-tools/adb -s "$PHONE" install -r /home/asus/AndroidStudioProjects/PasswordVault/app/build/outputs/apk/release/app-release.apk; fi
}
```

You will see `Performing Streamed Install` and then `Success`. On a Xiaomi phone, look at
the phone: it may ask you to confirm. Tap **Install** within a few seconds.

`install -r` installs the app, or replaces the app that is there and keeps its passwords.
It never deletes passwords. If you see a line with `Failure`, look it up in part 2.13.

## 2.10 Or: install by copying the file

Use this way if you do not want to turn on USB debugging.

1. Connect the phone with the cable and choose **File transfer** on the phone.
2. Open the **Files** app on the computer. The phone appears in the list on the left.
   Open it, then **Internal storage** (or **Internal shared storage**, or **Phone**), then
   **Download**.
3. Copy the file from `/home/asus/passwordvault-releases/` (part 2.6) into that folder.
4. On the phone, open its Files app (Samsung: My Files; Xiaomi: File Manager), open
   **Downloads** and tap the file.
5. The phone may ask before it installs an app from a file:
   - "not allowed to install unknown apps from this source": tap **Settings**, turn on
     **Allow from this source**, and go back;
   - a warning that unknown apps can harm the phone: tap **Continue**;
   - Google Play Protect says it does not know this app: choose to install anyway. It
     warns because the app is not from the Play Store.
6. Android asks **Install this app?** (or **Update this app?** when it is already there).
   Tap **Install** (or **Update**).
7. It says **App installed** (or **App updated**). Tap **Open**.
8. Delete the file from the phone's Download folder afterwards. It holds no passwords; it
   only takes space.

If the phone ever refuses with a message that the app's developer is not verified (Google
brings this rule in country by country from 2026), use part 2.9, and if that is refused
too, write down the exact words and ask Claude.

## 2.11 The first start

1. Open **PasswordVault**. You see "Your passwords, safe on this phone" and **Start**.
2. Do **not** put your real passwords in yet. First do the checks of step 3; they start
   with the setup.
3. After the setup, check that this is the app for your phone and not a test build:
   - Settings › **Version** shows "Version 1.0 (1)" (or the newer number) and "This phone:
     Android ...". It must **not** show "Test build: made for checking the app. Don’t keep
     your real passwords in it."
   - Settings › **Lock now**: the lock screen must **not** show the red line
     "TEST BUILD: PIN keys are not protected".

   If you see either line, stop and tell Claude: a test build is on your phone.

## 2.12 Your emulator's passwords do not come to the phone

The emulator holds a test build, and the phone holds the app signed with your key: for
Android these are two different apps. What you typed into the emulator stays there. The
phone's app starts empty, and that is right for real passwords.

If you do want some of the emulator's accounts on the phone, move them with a backup file.
Do it before you set up the app on the phone:

1. In the app on the emulator: Settings › **Make a backup**, type its master password, and
   in the file picker choose **Downloads**, then **Save**. It says
   "Backup saved: PasswordVault-backup-....pvault".
2. Copy the newest backup from the emulator to this computer. Paste this and press Enter:

   ```bash
   {
   F=$(/home/asus/Android/Sdk/platform-tools/adb -s emulator-5554 shell ls -t /sdcard/Download/ 2>/dev/null | tr -d '\r' | grep '^PasswordVault-backup-.*\.pvault$' | head -n 1)
   if ! /home/asus/Android/Sdk/platform-tools/adb -s emulator-5554 get-state > /dev/null 2>&1; then echo "STOP: your emulator is not running. Start it in Android Studio first."
   elif [ -z "$F" ]; then echo "STOP: no backup file in the emulator's Download folder."
   else /home/asus/Android/Sdk/platform-tools/adb -s emulator-5554 pull "/sdcard/Download/$F" "/home/asus/$F" && echo "DONE: the backup is now /home/asus/$F"; fi
   }
   ```

3. Connect the phone (part 2.8). Copy the file to the phone:

   ```bash
   {
   PHONE=$(/home/asus/Android/Sdk/platform-tools/adb devices -l | awk '/ usb:/ && $2 == "device" {print $1; exit}')
   F=$(ls -t /home/asus/PasswordVault-backup-*.pvault 2>/dev/null | head -n 1)
   if [ -z "$PHONE" ]; then echo "STOP: no phone found. Do part 2.8 again."
   elif [ -z "$F" ]; then echo "STOP: no backup file in /home/asus."
   else /home/asus/Android/Sdk/platform-tools/adb -s "$PHONE" push "$F" /sdcard/Download/ && echo "DONE: the backup file is in the phone's Download folder."; fi
   }
   ```

4. On the phone, on the first screen of PasswordVault, tap **Restore from a backup file** ›
   **Choose file** › Downloads › the file. If you do not see it under Downloads, open the
   menu (⋮), choose **Show internal storage**, and look in Internal storage › Download.
5. Type the master password **of the emulator's app**, tap **Open backup**, then
   **Restore**. The phone's app now has the emulator's master password.

## 2.13 After installing, and if something went wrong

- Unplug the phone. Never press **Run** in Android Studio while the phone is connected
  (README, "Never press Run").
- You may turn **USB debugging** off again in Developer options, and on again for the next
  update. While it is on, a computer that you allowed can read the phone.
- Samsung: turn **Auto Blocker** on again if you turned it off.

| `adb install` says | What it means and what to do |
|---|---|
| `Success` | All is well. |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE: ... signatures do not match ...` | The phone already has a PasswordVault signed with another key, for example a test build that Android Studio installed. **Warning: do not uninstall it while it holds passwords you need.** First open that app and make a backup file (Settings › Make a backup) and copy it off the phone. Step 4, part 4.8 shows which key that app has and how to move to yours. |
| `INSTALL_FAILED_VERSION_DOWNGRADE` | The phone has a newer version than this file. Install the newest file. |
| `INSTALL_FAILED_NO_MATCHING_ABIS` | You are installing on an emulator, not on your phone. The phone's app file has code for phones only. Check part 2.8: your phone is the line with `usb:`. |
| `INSTALL_FAILED_ALREADY_EXISTS` | `-r` was left out. Use the box of part 2.9 as it is. |
| `INSTALL_FAILED_USER_RESTRICTED` | Xiaomi, Redmi, POCO: turn on **Install via USB** (part 2.7) and tap **Install** on the phone when it asks. |
| `INSTALL_FAILED_INSUFFICIENT_STORAGE` | The phone is full. Free some space and try again. |
| `STOP: no phone found.` | Do part 2.8 again. |

Next: [step 3, the phone checks](step-3-phone-checks.html). Open it in Firefox: paste
`xdg-open /home/asus/AndroidStudioProjects/PasswordVault/docs/release/step-3-phone-checks.html`
in the terminal, and print it with **Ctrl+P**.
