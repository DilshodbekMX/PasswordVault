# Step 1: make your release key (once in your life)

**What it is.** Android installs an update of an app only when the update is signed with
the same key as the app on the phone. You make this key once, here. Every later version of
PasswordVault for your phone is signed with it.

**Why it matters.** Nobody can make the same key again: not you, not Claude, not Google.
If the key is lost, the app on your phone can never be updated. You would have to make a
backup file of your passwords, uninstall the app (which deletes the passwords on the
phone), install the app signed with a new key, and restore the backup.

**Do this only once.** Part 1.1 checks whether you already have a key.

## 1.1 Check that you have no key yet

Open the terminal (**Ctrl+Alt+T**). Paste this and press Enter:

```bash
ls -l /home/asus/passwordvault-key
```

- You see `ls: cannot access '/home/asus/passwordvault-key': No such file or directory`:
  you have no key yet. Go on with 1.2.
- You see a list with `passwordvault-release.jks`: **you already have a key. Do not make
  another one.** Go to part 1.7 and check your two copies instead.

## 1.2 Choose the key's password and write it down

Rules for this password:

- at least 16 characters;
- only English letters (a–z, A–Z), the digits 0–9 and the hyphen `-`: no spaces, no
  Cyrillic letters, no `ʻ`;
- not your master password, and not a password you use anywhere else.

For example, four English words and a number joined by hyphens. Do not use the example
on this page.

Take **two** pieces of paper. On each one write, clearly:

```
PasswordVault release key
Password: ............................
Made on: (today's date)
```

Read both papers again, letter by letter. Capital and small letters count.

## 1.3 Make the key

Paste this into the terminal and press Enter:

```bash
{
mkdir -p /home/asus/passwordvault-key && chmod 700 /home/asus/passwordvault-key && cd /home/asus/passwordvault-key
if [ -e passwordvault-release.jks ]; then
  echo "STOP: a release key is already in /home/asus/passwordvault-key. Never make a second one."
else
  PV_KEY_PASS=""; PV_KEY_PASS2=""
  while [ -z "$PV_KEY_PASS" ]; do read -r -s -p "Choose the key's password, then press Enter: " PV_KEY_PASS; echo; done
  while [ -z "$PV_KEY_PASS2" ]; do read -r -s -p "Type the same password again, then press Enter: " PV_KEY_PASS2; echo; done
  if [ "$PV_KEY_PASS" != "$PV_KEY_PASS2" ]; then
    echo "STOP: the two passwords are different. Nothing was made. Paste the commands again."
  elif [ ${#PV_KEY_PASS} -lt 16 ] || [[ "$PV_KEY_PASS" == *[!A-Za-z0-9-]* ]]; then
    echo "STOP: use at least 16 characters: only English letters, digits and the hyphen (-). Nothing was made. Paste the commands again."
  else
    export PV_KEY_PASS
    /home/asus/.jdks/jdk-25/bin/keytool -genkeypair -storetype PKCS12 -keystore passwordvault-release.jks -storepass:env PV_KEY_PASS -alias passwordvault -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=PasswordVault release key, C=UZ" \
    && /home/asus/.jdks/jdk-25/bin/keytool -exportcert -rfc -keystore passwordvault-release.jks -storepass:env PV_KEY_PASS -alias passwordvault -file passwordvault-release-certificate.pem \
    && printf '%s\n' "PasswordVault release key, made on $(date +%F)." "Keep this folder for ever: without it the app on the phone can never be updated." "Its password is written on paper, kept with this copy." > READ-ME.txt \
    && echo "DONE: the release key is made." && ls -l /home/asus/passwordvault-key
  fi
  unset PV_KEY_PASS PV_KEY_PASS2
fi
}
```

It asks twice for the password. Type the password from your paper each time and press
Enter. You see nothing while you type: that is normal.

You will see:

```
Generating 4096-bit RSA key pair and self-signed certificate (SHA384withRSA) with a validity of 10,000 days
	for: CN=PasswordVault release key, C=UZ
Certificate stored in file <passwordvault-release-certificate.pem>
DONE: the release key is made.
```

and a list of three files:

| File | What it is |
|---|---|
| `passwordvault-release.jks` | **The release key.** Secret. Never lose it. |
| `passwordvault-release-certificate.pem` | The key's public part. Not secret. The checks of step 2 compare the app file with it. |
| `READ-ME.txt` | A note that says what this folder is. |

The key is valid for 10,000 days (until about 2054).

If it goes wrong:

| You see | What to do |
|---|---|
| `STOP: the two passwords are different.` | Nothing was made. Paste the box again and type more carefully. |
| `STOP: use at least 16 characters ...` | Nothing was made. Choose a password that follows the rules of 1.2, write it on both papers again, and paste the box again. |
| `STOP: a release key is already in ...` | You have a key already. Never make a second one. Go to part 1.7. |

## 1.4 Check the password on your paper

Paste this and press Enter. When it asks `Enter keystore password:`, type the password
**reading it from your paper**:

```bash
/home/asus/.jdks/jdk-25/bin/keytool -list -keystore /home/asus/passwordvault-key/passwordvault-release.jks
```

Good: you see

```
Your keystore contains 1 entry

passwordvault, ..., PrivateKeyEntry,
```

Bad: you see `keytool error: java.io.IOException: keystore password was incorrect`. Then
the paper does not say what you typed in 1.3. Try once more, slowly. If it still fails,
and you have **not yet built any app with this key** (step 2), you may start again:
delete the new key with the command below, then do 1.2 and 1.3 again.

```bash
rm -r /home/asus/passwordvault-key
```

**Warning:** never run that command after you have installed an app signed with this key.

## 1.5 Copy the key to USB stick 1

1. Plug in **USB stick 1**, and only that one.
2. Paste this and press Enter:

   ```bash
   {
   STICK=$(ls -d /media/asus/*/ 2>/dev/null)
   if [ "$(printf '%s' "$STICK" | grep -c .)" -ne 1 ]; then
     echo "STOP: plug in exactly one USB stick. Found: ${STICK:-nothing}"
   else
     cp -rv /home/asus/passwordvault-key "$STICK" && sync && echo "DONE: the key is copied to $STICK"
   fi
   }
   ```

   You will see a few lines with `->` (the folder and its files) and then
   `DONE: the key is copied to /media/asus/...` with your stick's name.
   If you see `STOP: plug in exactly one USB stick.`, check that the stick is plugged in,
   wait 10 seconds and paste the box again.
3. Open the **Files** app. In the list on the left, click the **⏏** (eject) button next to
   the stick's name. Wait until the stick disappears from the list. Unplug it.
4. Plug the same stick in again and wait 10 seconds. Paste this and press Enter:

   ```bash
   {
   A=$(sha256sum < /home/asus/passwordvault-key/passwordvault-release.jks)
   B=$(cat /media/asus/*/passwordvault-key/passwordvault-release.jks 2>/dev/null | sha256sum)
   if [ "$A" = "$B" ]; then echo "OK: the key on the stick is the same as the key on this computer."; else echo "STOP: the key on the stick is missing or different. Copy it again (part 1.5)."; fi
   }
   ```

   You must see `OK: the key on the stick is the same as the key on this computer.`
5. Eject the stick again (the **⏏** button in Files) and unplug it.

## 1.6 Copy the key to USB stick 2

Do all of 1.5 again with **USB stick 2**.

## 1.7 The two envelopes

| Envelope | Put inside | Keep it |
|---|---|---|
| 1 | USB stick 1 and paper 1 | At home, in a dry place where you keep important papers |
| 2 | USB stick 2 and paper 2 | In another building: with a relative you trust, or in a locked drawer at work |

Write on each envelope: "PasswordVault release key. Copy 1 of 2 (or 2 of 2). Never throw
away."

- Do **not** put your master password, or a backup of your passwords, in these envelopes.
- The folder `/home/asus/passwordvault-key` stays on this computer too. Do not move it into
  the project folder: git must never see the key.
- **Once a year**, check both sticks with part 1.5, points 4 and 5. USB sticks can fail
  after some years. If one fails, copy the key to a new stick with part 1.5.

## 1.8 If this computer breaks, or you move to a new computer

The key comes back from one of the sticks. On the new or repaired computer:

1. Plug in **one** of your two key sticks.
2. Paste this and press Enter:

   ```bash
   {
   FROM=$(ls -d /media/asus/*/passwordvault-key/ 2>/dev/null)
   if [ -e /home/asus/passwordvault-key/passwordvault-release.jks ]; then
     echo "STOP: this computer already has the key in /home/asus/passwordvault-key. Nothing was copied."
   elif [ "$(printf '%s' "$FROM" | grep -c .)" -ne 1 ]; then
     echo "STOP: plug in exactly one of your two key sticks. Found: ${FROM:-nothing}"
   else
     mkdir -p /home/asus/passwordvault-key && chmod 700 /home/asus/passwordvault-key \
     && cp -v "$FROM"* /home/asus/passwordvault-key/ && echo "DONE: the key is back in /home/asus/passwordvault-key."
   fi
   }
   ```

3. Check the password with part 1.4, then eject the stick and put it back in its envelope.

This needs the same user name (`asus`) on the new computer. If it is different, ask Claude.

## 1.9 If the key is lost for ever

Your passwords are not lost if you have a backup file and know your master password. The
app on the phone can no longer be updated, though. To move to a new key: make a new key
(this page), build the app with it (step 2, parts 2.1 to 2.6), and then follow
[step 4, part 4.8](step-4-update-the-app.md#48-if-the-app-on-the-phone-is-signed-with-another-key):
a backup, the uninstall, the new app, and "Restore from a backup file". Ask Claude to go
through it with you; do not start it alone.

Next: [step 2, build the app file and install it](step-2-build-and-install.md).
