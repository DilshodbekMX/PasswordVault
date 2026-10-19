# PasswordVault on your phone: start here

These pages tell you how to make the app file for your phone, put it on the phone,
check it, and update it later. You do everything yourself, on this computer and on your
phone, mostly by copy and paste.

Read the pages in this order:

| Order | Page | What it is for | When |
|---|---|---|---|
| 1 | `README.md` (this page) | What you need, and the three things you must never lose | Now |
| 2 | [`step-1-release-key.md`](step-1-release-key.md) | Make your release key and keep two copies of it | Once in your life |
| 3 | [`step-2-build-and-install.md`](step-2-build-and-install.md) | Make the app file and put it on your phone | The first time |
| 4 | [`step-3-phone-checks.html`](step-3-phone-checks.html) | A form to print: check the app on your phone and write down what you see | After the first install |
| 5 | [`step-4-update-the-app.md`](step-4-update-the-app.md) | Put a newer version on the phone, and keep your passwords | Each new version |

The folder is `/home/asus/AndroidStudioProjects/PasswordVault/docs/release/`. To read a
page as a page, open it in Android Studio (it shows `.md` files as pages). The form of
step 3 opens in Firefox when you paste this into the terminal:

```bash
xdg-open /home/asus/AndroidStudioProjects/PasswordVault/docs/release/step-3-phone-checks.html
```

## The three things you must never lose

| What | Why it matters | Copy 1 | Copy 2 |
|---|---|---|---|
| **The release key**: the folder `/home/asus/passwordvault-key`, with the file `passwordvault-release.jks` | Android installs an update only when it is signed with the same key as the app on the phone. Nobody can make this key again, not even with the password. Without it the app on your phone can never be updated. | USB stick 1, in envelope 1, at home | USB stick 2, in envelope 2, in another building (a relative you trust, or a locked drawer at work) |
| **The release key's password** | The key file is useless without it. | On paper, in envelope 1 | On paper, in envelope 2 |
| **Your master password** | It opens your passwords on a new phone, from a backup file, and when you forget your PIN. Nobody can reset it: there is no "Forgot password" email. | On the paper from the app's setup ("Write it down"), at home, not with the phone | On a second paper, in a sealed envelope, in another building |

Two more rules:

- Keep the master password apart from the key envelopes, and apart from the phone.
- Make backup files of your passwords in the app (Settings › Make a backup) and keep them
  in two places, for example Google Drive and this computer. A backup file is locked with
  your master password. It is how your passwords survive a lost or broken phone, and it is
  the way out if the release key is ever lost.

## Words used in these pages

| Word | What it means |
|---|---|
| App file, or APK | The file that Android installs, for example `app-release.apk`. It holds the app, never your passwords. |
| Release key | Your own key that signs the app file. Step 1 makes it. |
| Test build | The app as Android Studio runs it on the emulator. It is signed with Android Studio's own key, not with yours. |
| Terminal | The Terminal app of Ubuntu. Open it with **Ctrl+Alt+T**. |
| Project folder | `/home/asus/AndroidStudioProjects/PasswordVault` |
| adb | A program of Android Studio that talks to the phone over the USB cable. These pages always give its full path. |

## How to use the commands

1. Copy the whole grey box, from the first line to the last.
2. In the terminal, paste with **Ctrl+Shift+V** (Ctrl+V does not work there).
3. Press **Enter**.
4. Read the last lines:
   - `DONE` or `OK` at the start of a line: all is well. Go on.
   - `STOP` at the start of a line: read the line and do what it says. A STOP line means
     that the command found a problem and changed nothing.
5. When the terminal asks for a password, you see nothing while you type. That is normal:
   type it and press Enter.

## The emulator and your phone hold two different apps

The app that Android Studio runs on the emulator is a test build, signed with Android
Studio's own key. The app on your phone is signed with your release key. They have the same
name, but for Android they are two different apps:

- Android never installs one over the other: `adb` then says
  `INSTALL_FAILED_UPDATE_INCOMPATIBLE`.
- Your passwords do not move between them by themselves. The emulator's test passwords stay
  on the emulator; the phone starts empty. The only way across is a backup file (step 2,
  part 2.12, says how).
- Test builds show a red line "TEST BUILD: PIN keys are not protected" on the lock screen.
  The app on your phone must never show it.

## Never press Run in Android Studio while the phone is connected

Android Studio's **Run** button installs a test build on the device chosen next to it. If
that device is your phone, Android refuses, because the keys are different. Android Studio
may then offer to uninstall the app first. **Uninstalling deletes every password on the
phone.** If you ever see such a question, answer **Cancel**.

Unplug the phone from the computer when you are not installing the app.

## What you need

- This computer, with this project.
- Your Android phone (Android 7.0 or newer) and a USB cable that carries data (some cables
  only charge; the cable that came with the phone usually carries data).
- Two USB sticks of any size, paper, a pen and two envelopes.
- About one hour for steps 1 and 2. The checks of step 3 take a few hours, over several
  days.

## If something goes wrong

- Stop, and read the last lines in the terminal again.
- Nothing in these pages deletes your passwords, except where a page says so in a line that
  starts with **Warning**.
- Ask Claude. Copy the last lines of the terminal into your message. Never put a password
  in the message.
