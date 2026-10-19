# Step 4: put a newer version on your phone

Do this when Claude tells you that a new version is ready. An update keeps every password
on the phone, as long as two things are true:

- the new app file is signed with **your release key** (the same key as the app on the
  phone), and
- its build number (`versionCode`) is **higher** than the one on the phone. Claude raises it
  for each new version.

This page checks both before anything is installed.

## 4.1 Make a fresh backup first

On the phone, in PasswordVault: Settings › **Make a backup**, to Google Drive or to
Downloads. It asks for your master password: that also proves you still know it. If the
update ever went wrong, this backup brings your passwords back.

## 4.2 Build the new version and check it

Do these parts of [step 2](step-2-build-and-install.md) again:

1. Part 2.1: note the version of the code.
2. Part 2.4: build. If the folder then shows `app-release-unsigned.apk`,
   `keystore.properties` is missing: write it again with part 2.2 (you need the key's
   password from your paper), then build again.
3. Part 2.5: check the app file. You must see three `OK` lines.
4. Part 2.6: keep a copy of the file.

## 4.3 Compare it with the app on the phone

Connect the phone (step 2, part 2.8). Paste this and press Enter:

```bash
{
PHONE=$(/home/asus/Android/Sdk/platform-tools/adb devices -l | awk '/ usb:/ && $2 == "device" {print $1; exit}')
NEW=$(/home/asus/Android/Sdk/build-tools/36.0.0/aapt2 dump badging /home/asus/AndroidStudioProjects/PasswordVault/app/build/outputs/apk/release/app-release.apk 2>/dev/null | grep -m1 -o "versionCode='[0-9]*'" | tr -dc '0-9')
if [ -z "$PHONE" ]; then echo "STOP: no phone found. Connect it and allow USB debugging."
elif [ -z "$NEW" ]; then echo "STOP: app-release.apk was not found. Build it first."
else
  OLD=$(/home/asus/Android/Sdk/platform-tools/adb -s "$PHONE" shell dumpsys package io.github.dilshodbekmx.passwordvault | grep -m1 -o 'versionCode=[0-9]*' | tr -dc '0-9')
  echo "Version number on the phone: ${OLD:-none}. In the new file: $NEW."
  if [ -z "$OLD" ]; then echo "FIRST INSTALL: PasswordVault is not on this phone yet."
  elif [ "$NEW" -gt "$OLD" ]; then echo "OK: the new file is newer than the app on the phone."
  elif [ "$NEW" -eq "$OLD" ]; then echo "SAME: this is the version the phone already has."
  else echo "STOP: the new file is OLDER than the app on the phone. Android will refuse it."; fi
fi
}
```

| You see | What to do |
|---|---|
| `OK: the new file is newer than the app on the phone.` | Go on with 4.4. |
| `SAME: this is the version the phone already has.` | Installing it changes nothing. Ask Claude whether a new version was meant. |
| `STOP: the new file is OLDER ...` | Do not install it. Check part 2.1: the project is not at the newest version. |
| `FIRST INSTALL: ...` | The app is not on the phone. Use step 2 instead. |

## 4.4 Install the update

Over the USB cable: paste the box of step 2, part 2.9. You will see
`Performing Streamed Install` and `Success`.

Or with the file: step 2, part 2.10. The phone asks **Update this app?**: tap **Update**,
then **Open** when it says **App updated**.

Both ways replace the app and keep its passwords, its PIN and its settings.

## 4.5 After the update

1. Open PasswordVault. It shows its lock screen as before; your PIN or fingerprint opens
   it, and your passwords are there.
2. Settings › **Version** shows the new number, for example "Version 1.1 (2)".
3. Unplug the phone.

## 4.6 Never do these

| Never | Why |
|---|---|
| Uninstall the app to make an update install | **Uninstalling deletes the passwords on the phone.** Android also refuses an app signed with another key after an uninstall that kept the app's data. |
| Press **Run** in Android Studio with the phone connected | It installs a test build, which for Android is another app. Android refuses it, and Android Studio may offer to uninstall your app first: always answer **Cancel**. |
| Make a new release key | The app on the phone accepts updates only with the key it was installed with. |
| Install a file when part 2.5 or 4.3 says `STOP` | It could never be updated with your key, or Android refuses it anyway. |
| Use install commands that are not in these pages | Some of them uninstall first, or install test builds. |

## 4.7 If the update is refused

| The message says | What it means | What to do |
|---|---|---|
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE ... signatures do not match ...` (on the phone: the app was not installed because it conflicts with the app that is there) | The app on the phone and the new file are signed with different keys. | If part 2.5 said `OK` for the new file, the app on the phone has another key. Find out which with 4.8. Do not uninstall anything yet. |
| `INSTALL_FAILED_VERSION_DOWNGRADE` | The app on the phone is newer than this file. | Do part 4.3. Ask Claude for the newest version. |
| `INSTALL_FAILED_USER_RESTRICTED` | Xiaomi, Redmi, POCO: installing over USB is not allowed yet. | Turn on **Install via USB** (step 2, part 2.7) and tap **Install** on the phone when it asks. |

## 4.8 If the app on the phone is signed with another key

This happens when a test build from Android Studio is on the phone, or when the release
key was lost and a new one was made. First find out which key the phone's app has. Connect
the phone (step 2, part 2.8), paste this and press Enter:

```bash
{
ADB=/home/asus/Android/Sdk/platform-tools/adb
PHONE=$($ADB devices -l | awk '/ usb:/ && $2 == "device" {print $1; exit}')
if [ -z "$PHONE" ]; then echo "STOP: no phone found. Do step 2, part 2.8 again."; else
  P=$($ADB -s "$PHONE" shell pm path io.github.dilshodbekmx.passwordvault | tr -d '\r' | sed -n 's/^package://p' | grep 'base\.apk$' | head -n 1)
  if [ -z "$P" ]; then echo "PasswordVault is not installed on the phone."
  elif ! $ADB -s "$PHONE" pull "$P" /home/asus/passwordvault-on-phone.apk > /dev/null 2>&1; then echo "STOP: the app could not be copied from the phone. Paste the box again."
  else
    want=$(/home/asus/.jdks/jdk-25/bin/keytool -printcert -file /home/asus/passwordvault-key/passwordvault-release-certificate.pem | grep -m1 'SHA256:' | sed 's/.*SHA256: //; s/://g' | tr 'A-F' 'a-f')
    CERTS=$(/home/asus/Android/Sdk/build-tools/36.0.0/apksigner -J-enable-native-access=ALL-UNNAMED verify --print-certs /home/asus/passwordvault-on-phone.apk 2>/dev/null)
    got=$(printf '%s\n' "$CERTS" | grep -m1 'certificate SHA-256 digest' | awk '{print $NF}')
    if [ -n "$want" ] && [ "$got" = "$want" ]; then echo "OK: the app on the phone is signed with your release key."
    else echo "The app on the phone is signed with ANOTHER key: $(printf '%s\n' "$CERTS" | grep -m1 'certificate DN' | sed 's/.*DN: //')"; fi
    rm -f /home/asus/passwordvault-on-phone.apk
  fi
fi
}
```

- `OK: the app on the phone is signed with your release key.`: then the new file is the
  problem. Do part 2.5 again and tell Claude what it says.
- `The app on the phone is signed with ANOTHER key: C=US, O=Android, CN=Android Debug`: a test
  build is on the phone.
- `... ANOTHER key: CN=PasswordVault release key, C=UZ`: an older release key of yours,
  made before this one.

To move from the other app to yours, your passwords travel in a backup file. Go slowly,
and stop at any step that does not show what it should:

1. Open the app that is on the phone. Settings › **Make a backup**, and save it to Google
   Drive **and** to Downloads. It asks for that app's master password.
2. Check that the backup is there: open the Google Drive app and find
   `PasswordVault-backup-….pvault`.
3. **Warning: the next step deletes the passwords on the phone.** Your backup from point 1
   is their copy, and that app's master password opens it. Uninstall the app: press and
   hold its icon › **Uninstall**.
4. Install your app: step 2, part 2.9.
5. On its first screen tap **Restore from a backup file** › **Choose file** › Google
   Drive › the backup. Type that app's master password, tap **Open backup**, then
   **Restore**.
6. Your accounts are back. Choose a PIN, and make a new backup (Settings › **Make a
   backup**).
