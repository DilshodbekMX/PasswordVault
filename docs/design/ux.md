# PasswordVault: UX specification and version 1 entry model

Date 2026-10-06; revised the same day after the UX critique (what changed and why: §15). Status: proposal for review. Scope: every screen of version 1, the entry model, the copy flow, onboarding text, accessibility, localisation, navigation, and what is left out.

Other documents own parts of the picture, and this one points to them instead of repeating them:
- **The lock design** owns the PIN logic: length, attempt limits, waiting times, what is stored where, and when the master password is required. Two candidates exist, `scratchpad/design/lock-designs/design-1.md` and `design-2.md` (called "lock design 1" and "lock design 2" below). This document fixes how the lock screens look and what they say. Where the experience depends on a lock decision, it gives a recommendation.
- **[formats.md](formats.md)** owns the exact layout of every exported and imported file.
- **[i18n.md](i18n.md)** owns translation, the glossary and the sort key.
- **[search.md](search.md)** owns the search key, matching and ranking.

How I know things:
- **[E]** from the evidence file `scratchpad/prior/ux.txt`, `docs/research/*.md`, `docs/research/VERIFICATION.md` (items V-xx), the lock fact sheet `scratchpad/design/lock-facts/FACTS.md`, or the critique `scratchpad/design/ux-critic/CRITIQUE.md` (source named);
- **[V]** checked by me today in the named primary source;
- **[X]** my own experiment today;
- **[I]** design judgement or inference.

## 0. Decisions in brief

1. **One job comes first: open, find, copy.** After unlock the list is on screen. Every row has a one-tap "copy password" button, and search filters the list in place. Bitwarden hides copy in an overflow menu [E: `CipherListViewExtensions.kt`]. KeePassDX users called an extra step before copying "annoying" (#2321) [E].
2. **Proposed: a bank-style lock of the app's own.** A 6-dot PIN pad drawn by the app (no system keyboard involved), fingerprint as a shortcut, and "Forgot PIN? Use master password" always one tap away. It is separate from the phone's screen lock. The user wrote "home lock like banking account", which may instead mean the phone's own lock. This must be confirmed with the user (§14 question 1); until then the lock screen is built so that either can become the main way in.
3. **Defaults are the secure choice; settings are few.** The app locks 30 s after leaving and at once when the screen turns off; copied text is cleared after 1 minute; screenshots are always blocked. Some participants simply keep the preset settings ("these [settings] were preset and I kept what was recommended") [E: CCS 2019].
4. **Version 1 entry**: title (required), username, password, websites (up to 10), notes, extra fields (label, value, hidden or not), favourite, three timestamps and the last 5 previous passwords. Four small fields keep the security check and deletion safe: `changePending`, `pendingOldPassword`, `checkIgnored` and `deletedAt`. **Tags are deferred**; "recently used" is not tracked.
5. **Nothing the user types is lost, and nothing reachable from the lock screen deletes anything.**
   - An unsaved form is kept as an encrypted draft when the user leaves the app.
   - A backup, export or import that passes through the system file picker survives a lock.
   - The guided change keeps the password the website still has until the user confirms the change.
   - A deleted account waits 30 days in "Recently deleted".
   - "Start with an empty app" keeps the old passwords on the phone, locked.
6. **The master password is practised, and a forgotten one can be replaced.** The app asks for it now and then, and "Not now" works for a few days. If the user forgets it while the PIN still opens the app, they can choose a new one (§5.24).
7. **The security check fixes one account at a time.** It groups reused passwords, starts with email, and walks the user through change, paste and confirm, with a pending state that survives locking. No score, no number badges, no notifications. SOUPS 2025: 78.94 % of novices had warning flags in week 4 and nobody changed a password [E].
8. **Exports match their purpose** (layouts in formats.md):
   - an encrypted backup file (recommended; opens with the master password);
   - **Excel `.xlsx` with every cell stored as text**;
   - CSV in Chrome's 5-column layout, for moving to another password app;
   - JSON in Bitwarden's layout, which keeps everything.

   My test: opened from a CSV, a spreadsheet turned `+998901234567` into 998901234567 and `0123` into 123, and evaluated `=1+2`. The text-only `.xlsx` kept every value [X].
9. **Plain-text exports need the master password and a full-screen warning.** They are saved only through the file picker, and the app keeps reminding the user until the file is deleted (it can delete it for them).
10. **Uzbek and Russian text in search.**
    - All apostrophe variants of oʻ and gʻ match each other.
    - The 2026 letters ö, ğ, ş, ç match oʻ, gʻ, sh, ch.
    - Cyrillic matches Latin (Тошкент = Toshkent).
    - Russian-keyboard spellings match Uzbek Cyrillic ("узбек" finds "Ўзбекистон").
    - A query typed on the wrong keyboard layout is retried.

    Sources: search.md, and §5.11 here.
11. **Languages for version 1**: English (default), Uzbek Latin, Uzbek Cyrillic and Russian (i18n.md §0). All text is in resources with proper plurals; Russian has one/few/many/other [V: CLDR].
12. **Material 3 (material3 1.4.0 in Compose BOM 2026.09.00)** [V: Maven POM], with a bottom navigation bar of three items: Passwords, Security, Settings.

## 1. The user and the principles

The user is not an expert and reuses one password everywhere. They want every account's details on the phone, open the app to see and copy a password, and asked for a banking-style lock and for CSV, JSON and Excel files. Their English is not native. They are probably in Uzbekistan, with Uzbek Latin and Russian Cyrillic in their data and on their keyboard.

Principles, in priority order:

1. **Never lose the user's data.** A local-only vault has no server copy. So:
   - nothing reachable from the lock screen deletes anything;
   - deleted accounts can be restored for 30 days;
   - unsaved forms, unfinished file tasks and password changes survive a lock;
   - the master password is practised, and a forgotten one can be replaced while the PIN still works.
2. **Fast path first.** Unlock → (search) → copy in at most three taps plus typing.
3. **Plain words.** No "encrypt", "vault key", "KDF", "slot" or "entropy" in the interface. Say "locked file", "master password", "backup".
4. **Honest limits.** The app says what it cannot protect against: a rooted phone, a harmful keyboard, apps with accessibility control [E: platform.md §4].
5. **Calm security.** Explain once, then help with one account at a time. Never shame, never nag, no red counters.

## 2. Version 1 entry model

There is one entry type (a login). Character counts are Unicode code points. No field is trimmed or normalised on storage except where stated; passwords are stored exactly as typed, because websites compare them exactly.

| Field | Type | Required | Limit and rules |
|---|---|---|---|
| `id` | UUID v4 (string) | yes, generated | 36 chars; never shown |
| `title` | string | **yes** (non-blank after trim) | 1–200; leading and trailing spaces trimmed; one line. If left empty when saving, it is filled from the first website's host (for example "mail.google.com") or with "Untitled" plus the time |
| `username` | string | no | ≤ 500; trimmed; one line. Hint: "Email, phone number or login" |
| `password` | string | no | ≤ 1,000; one line; never trimmed. An account that signs in by SMS code may have none |
| `urls` | list of strings | no | ≤ 10 items, each ≤ 2,048; trimmed; `https://` added when no scheme is given; `android://…` values from Chrome are kept and shown as "App: com.example", never opened |
| `notes` | string | no | ≤ 10,000; line breaks allowed |
| `fields` | list of {`name`, `value`, `secret`} | no | ≤ 20 per entry; `name` 1–100, one line; `value` ≤ 1,000, line breaks allowed; `secret` boolean, default true for labels the app does not know |
| `favourite` | boolean | yes, default false | |
| `createdAt` | instant (RFC 3339, UTC, ms) | yes, set by app | |
| `updatedAt` | instant | yes, set by app | on any saved change |
| `passwordChangedAt` | instant or null | no | set when the password is first set and whenever its value changes |
| `passwordHistory` | list of {`password`, `replacedAt`} | no | newest first; keep the **5** newest; an item is added when a saved password changes |
| `changePending` | boolean | yes, default false | true from "Create new password" until "Yes, it's changed" or "No, put back the old password" in the guided change (§5.17) |
| `pendingOldPassword` | string or null (**new**) | no | the password the website had when the guided change started. Set only while `changePending` is true. It is not one of the 5 previous passwords and is never pruned; it is cleared when the change ends (§5.17) |
| `checkIgnored` | boolean | yes, default false | "Leave out of the security check", for PINs or passwords the user cannot change |
| `deletedAt` | instant or null (**new**) | no | set by Delete; the account waits in Recently deleted and is removed 30 days later (§5.23) |

Rules:

- **Control characters** (U+0000–U+001F, U+007F) are refused on input and reported on import, except line breaks in `notes` and field values. Reason: they cannot be pasted into websites, and XML 1.0 (used by `.xlsx`) cannot carry most of them.
- **Why these limits.** Excel holds at most 32,767 characters in a cell [V: Microsoft "Excel specifications and limits"]. With these limits the largest cells in the Excel export fit: notes 10,000; extra fields about 22,000; other websites about 18,500. The limits also keep list rendering and the vault file bounded.
- **Entry count**: no hard limit; designed and tested for 5,000 entries. The storage benchmark sealed and wrote 5,000 entries in a median 37 ms on a desktop [E: storage.md §4].
- **Compared with Google Password Manager**: Chromium's importer accepts passwords and usernames up to 1,000 characters, websites up to 2,048 and notes up to 1,000 [V: Chromium `password_manager_constants.h`]. Our password and website limits match. Notes may be longer here, and the CSV export says which accounts Google would refuse (§5.20.2).
- Unknown JSON keys are preserved (storage rule) [E: storage.md §5.1].
- Accounts with `deletedAt` set are hidden everywhere except Recently deleted. They are left out of search, the security check and plain exports.

From the provisional model:

| Item | Decision | Reason |
|---|---|---|
| `tags` | **Deferred** | Search plus favourites covers retrieval for tens to a few hundred accounts; tags need an editor and a filter UI. Bitwarden's folder-heavy home was criticised as cumbersome [E: ux.txt, community.bitwarden.com "My Vault" critique]. The schema can add `tags` later as an optional key |
| `urls` as a list | **Kept** | Chrome exports one row per affiliated domain [E: Chromium `password_csv_writer.cc`; V-A6]. The import keeps one login on related websites as one account (§5.21) |
| custom fields | **Kept, simplified** | The user wants "every account's details": PIN codes, security answers, bank code words. Without fields these would sit unmasked in notes with no per-value copy |
| password history | **Kept, capped at 5** | It is the safety net when a saved password changes. During a guided change the website's own password is kept apart in `pendingOldPassword`, so retries cannot push it out (§5.17) |
| `passwordChangedAt` | **Kept** | Shown as "Password changed 3 days ago". Not used for "old password" warnings (no forced rotation) |
| `lastUsedAt` / recently used | **Not added** | Every copy would rewrite and re-encrypt the vault file and leave a usage history in the kept generations [I from storage.md §5.2–5.4] |
| `changePending`, `pendingOldPassword`, `checkIgnored` | **Added** | Needed to make the security check actionable and safe (§5.16, §5.17) |
| `deletedAt` | **Added** | Recently deleted (§5.23), like the trash in Bitwarden and Proton Pass [E: critique m9] |

Vault-level data the UX needs (owners: storage, lock and settings designs):

- **Outside the vault** (needed before unlock, not secret):
  - lock delay, clipboard delay, theme, language and generator options;
  - last backup time and place name, the number of changes since the last backup, and the backup-reminder snooze;
  - one-time tips shown, and "setup words were shown" (§5.5);
  - an unfinished file task: its kind, step and file location (§4);
  - the dates of archived vaults (§5.10).

  The lock design keeps its own state: attempts, the practice schedule, device keys, and the highest generation seen (V-C8).
- **Inside the encrypted body**:
  - at most one unsaved-form draft (§5.14);
  - the locations of unprotected exports not yet deleted (§5.20);
  - the locations of backups this app saved (§5.18);
  - the ids of entries whose password equals the master password (§5.16);
  - which security-check results the user has seen (§5.16).

## 3. Rules every screen follows

From the platform research, applied to the UI [E: platform.md §1, §3; VERIFICATION]:

- **`FLAG_SECURE` on the window, always.** There is no "allow screenshots" setting.
- **Masking.**
  - Passwords and secret field values are masked until the user taps "Show". Masks are always **8 dots**, whatever the length.
  - A revealed value hides again **30 seconds after the last touch on the screen**, and whenever the screen stops.
  - Reason: render secrets rarely and briefly, because Pixnapping cannot be stopped by `FLAG_SECURE` [E: platform.md §4].
  - Every touch restarts the timer, so a user copying a long password by hand is not cut off (WCAG 2.2.1 "Timing adjustable"). "Show in big letters" (§5.13) is the tool for long reading.
- **Every text field.**
  - The per-field autofill opt-out `semantics { contentDataType = ContentDataType.None }` is the main control. The window opt-out cannot be relied on from Android 14 [E: V-P4].
  - Stored passwords are displayed with `Text`, never with a read-only text field, which is an autofill node holding its text [E: V-P4].
  - `IME_FLAG_NO_PERSONALIZED_LEARNING` is set; auto-correct is off for usernames, passwords, websites and PINs.
  - Secrets are never in saved state. Each `TextFieldState` is held in a ViewModel; `rememberTextFieldState()` saves its text and is never used. On lock the state is discarded, or `clearText()` is followed by `undoState.clearHistory()`, because `clearText()` alone leaves the text in the undo history [E: V-C11].
- **Password inputs** use `SecureTextField` or `OutlinedSecureTextField` with `textObfuscationMode` set explicitly: `RevealLastTyped` plus a show/hide button. In foundation 1.12 the secure field reads the platform's password-visibility settings for touch and physical keyboards (`rememberPlatformPasswordVisibilitySettingsState`) [V: bytecode], so the last character is shown only when the phone allows it.
- **Secrets, usernames, websites and PIN dots are always laid out left-to-right**, even in a right-to-left language. KeePassDX does the same for protected fields [E: `TemplateView.kt`].
- **The app never reads what is on the clipboard.**
  - It reads only the clip's description (`getPrimaryClipDescription()`), to recognise its own copy (§6).
  - Android shows no "pasted from your clipboard" notice for that, nor for reading the app's own clip [V: Android "Copy and paste" guide].
- **Overlays.**
  - Touches arriving through an overlay are filtered (`filterTouchesWhenObscured`). On API 31+, `setHideOverlayWindows(true)` also hides overlays; it needs the `HIDE_OVERLAY_WINDOWS` permission (normal level) and throws without it [E: platform.md §3.5; V: API 31].
  - **Visible effect**: on Android 7–11, taps may "do nothing" while a screen filter or chat bubble is on top.
  - When the activity sees an obscured touch (`MotionEvent.FLAG_WINDOW_IS_OBSCURED`), it shows once per session: "Another app is drawing over this screen, so taps are blocked for safety. Turn off screen filters or chat bubbles and try again."
- **Accessibility-sensitive nodes.**
  - Every node that shows or takes a secret carries `semantics { isSensitiveData = true }`: revealed values, the master-password field, the PIN dots and the keypad keys. It takes effect on API 34+ and is not inherited [E: V-P5, FACTS §8].
  - Accessibility services that are not accessibility tools then cannot read which key was pressed. That is the route the local banking malware uses (§7.6).
  - If TalkBack cannot operate the keypad with the flag (lock design 2, test E12), the keys drop it and the dots keep it.
- **Layout.** Edge-to-edge with inset handling; both orientations; content width capped at 640 dp on large screens (Android 16+ ignores orientation locks at ≥ 600 dp) [V: Android 16 behaviour changes].

## 4. Navigation and back behaviour

There are three top-level states. The app owns one back stack per state; switching state replaces the whole stack.

```
Process start ──► no vault file? ──► SETUP: Welcome → (Restore | Open my previous passwords | Master password → Write down → Type again)
                                               → [vault created] → PIN → Confirm PIN → Fingerprint? → Backups → UNLOCKED
              └─► vault file ─────► LOCKED: PIN pad ⇄ Master password (practice, PIN off, "Welcome back", damaged file…)
                                    after a master-password unlock without a usable PIN → Choose a PIN (skippable)
UNLOCKED (bottom bar):  Passwords ─► Entry ─► Edit / Show in big letters / Generator sheet
                                  └─► Recently deleted
                        Security  ─► Group list ─► Guided change
                        Settings  ─► App lock · Backup & transfer · Appearance & language · Help
Auto-lock, "Lock now", process death ──► LOCKED (what is kept: "What survives a lock" below)
```

**Implementation.** Navigation 3 (`androidx.navigation3` 1.2.0 stable) is recommended [V: Google Maven], because the app holds the back stack as a list it can replace on lock. Navigation Compose 2.10 is an acceptable alternative. `NavigationSuiteScaffold` (material3-adaptive-navigation-suite 1.4.0, stable) shows a bottom `NavigationBar` on phones and a `NavigationRail` at ≥ 600 dp [V: bytecode].

Back rules. Predictive back is on at target 36: `onBackPressed` is not called and `KEYCODE_BACK` is not dispatched [V: Android 16 behaviour changes].

| Where | Back does | Notes |
|---|---|---|
| Passwords (root) | Leaves the app (system back-to-home animation) | Leaving starts the lock delay. No "press back again to exit" |
| Security or Settings root | Goes to Passwords, then leaves | Fixed start destination |
| Passwords with a search query | Clears the query first | Then normal |
| Entry, group list, settings pages, Recently deleted | Previous screen with the in-app predictive animation | |
| Edit with unsaved changes | Dialog "Save changes?" — Save · Discard · Keep editing | `BackHandler` is enabled only while the form is dirty, so the predictive preview is not shown for a back that will be stopped. Discard also deletes the draft (§5.14) |
| Generator sheet, dialogs | Close the sheet or dialog | `ModalBottomSheet` follows the gesture |
| Guided change | Back to the group list; progress is kept (`changePending`) | |
| Lock screen | Leaves the app | The lock screen never reveals anything behind it; after unlock it is removed from the stack |
| Setup, before the vault exists | Previous setup step; on Welcome, leaves the app | The generated passphrase stays in memory only (§5.5 covers a kill) |
| Setup, after the vault exists | Leaves the app; the next start asks for the master password and resumes at the PIN step | There is no way back to "create master password" |
| While "Opening…" or "Saving…" runs | Ignored for those 1–2 seconds | |

**Auto-lock as the user experiences it.** The numbers and mechanics belong to the lock design.
- The app locks:
  - when the lock delay (default 30 s) has passed since the user left it;
  - at once when the screen turns off;
  - after 5 minutes without a touch while open;
  - on every process start.
- **Screen off.** When the app stops while `PowerManager.isInteractive()` is false (API 20 [V]), it locks at once.
  - If the screen turns off while the app is already in the background, Android 14+ may hold the screen-off broadcast until the app runs again [E: platform.md §3.3]. The lock is then applied when the broadcast arrives.
  - On API 35+, `BiometricManager.getLastAuthenticationTime()` also shows a phone unlock that happened after the app was left [E: FACTS §8; V: API 35].
  - This background case is best effort; the lock delay still applies.

**System screens the app opens.**
- **The problem.** The system file picker and the share sheet stop the app's process lifecycle just as leaving the app does [E: V-P3]. Without a rule, the 30 s lock (or "Immediately") would fire in the middle of a backup, export or import.
- **The rule.** Time spent in such a screen that the app opened itself counts as time in the app, for at most **10 minutes** (the value both lock designs use). This covers saving a backup or export, choosing an import file, and sending a backup.
- **What still locks.** The screen turning off still locks at once. Opening a website or switching to another app is ordinary leaving: the normal delay applies.

**What survives a lock.**

| Kept | Where | After unlock |
|---|---|---|
| Route: tab, entry id, guided-change entry | Memory and saved state (UUIDs only, not secret, so keeping them across process death is acceptable) | The same screen. Secrets are masked again and dialogs and sheets are closed; a route to a deleted entry falls back to Passwords |
| An add or edit form with changes | An encrypted draft in the vault body (§5.14) | The form reopens with the snackbar "Your unsaved changes were kept." |
| An unfinished backup, export or import | Kind, step and file location, outside the vault (not secret) | The task screen reopens at its step with "This was paused when the app locked." |
| A guided change | The entry (`changePending`, `pendingOldPassword`) | The change screen, at the same place |
| A copied password | The clipboard; the lock does not clear it (§6) | The clipboard status row continues |

**Unfinished file tasks.**
1. **When the picker returns a file location,** the app first takes a persistable permission on it and records the task. Nothing secret is recorded.
2. **After unlock, the task continues.**
   - A backup asks for the master password again only if §5.19 needs it.
   - A plain export asks for the master password again, because it is never kept.
   - An import reads the chosen file again. Its preview holds passwords, so it is not kept.
3. **Cleaning up.** The picker creates the file before the app writes anything [E: critique C1, SAF guide]. If the user cancels, or writing fails, the app deletes that file (`DocumentsContract.deleteDocument`, API 19 [V]) and says so:
   - "No backup was made. The empty file was removed.";
   - for exports, "No file was saved. The empty file was removed.";
   - if the place does not allow deleting: "No backup was made. Delete the empty file PasswordVault-backup-2026-10-06.pvault in Google Drive yourself."

   Reason: an empty `.pvault` file in Drive looks like a backup and fails only at restore time.
4. **If the process dies while the picker is open,** the location arrives at the new, locked process. It is recorded first and handled after the next unlock.

## 5. Screens

### 5.1 Welcome (first run)

- **Layout**: app icon, title "Your passwords, safe on this phone", and three short lines with icons:
  - "Everything stays on this phone. No sign-up, no internet."
  - "Open the app with a PIN or your fingerprint, like a bank app."
  - "Copy a password with one tap when you need it."
- **Buttons**, top to bottom:
  - primary `Button` "Start";
  - `OutlinedButton` "Open my previous passwords (locked on 6 Oct 2026)", only when an archived vault exists (§5.10);
  - `OutlinedButton` "Restore from a backup file".
- **Language**: a top-right `IconButton` (globe) opens a dialog: System language · Oʻzbekcha · Ўзбекча · English · Русский. Each name is in its own language (i18n.md §1.4.5).
- **"Open my previous passwords"**:
  - With one archive, it asks "Type the master password you used before 6 Oct 2026." The archive is opened and checked; it becomes the app's vault again, and setup continues at the PIN step.
  - With several archives, a list by date comes first.
- **States**: none beyond default. Back leaves the app.

### 5.2 Restore from a backup file (first run)

1. System file picker (`OpenDocument`, any type). The file is recognised by its content, not its extension.
2. "Type the master password you had when you made this backup." `OutlinedSecureTextField`, button "Open backup".
3. "Opening…" with `CircularProgressIndicator` (about 0.3–1 s while the key derivation runs) [E: crypto.md §3].
4. Summary card: "52 accounts, backup made on 3 October 2026." Button "Restore".
5. Continue at the PIN step (§5.6). The restored vault keeps the backup's master password.

Nothing on the phone is replaced before the backup has been opened and checked. A vault already on the phone (restore from the lock screen, §5.10) is archived, never deleted.

States and messages:
- Not our file: "This is not a Password Vault backup. To bring passwords from Chrome or another app, finish setup first, then use Settings › Import."
- Wrong password: "This password does not open this backup. Try the master password you used when you made it."
- Damaged: "This backup file is damaged and cannot be opened. Try another backup file."
- Made by a newer app version: "This backup was made by a newer version of the app. Install the newest version of Password Vault and try again."
- Not enough memory: "Your phone does not have enough free memory to open the file. Close other apps and try again." [E: V-C2]

### 5.3 Create master password

- **Top**: title "Choose your master password". Body: "Your master password is the key to all your other passwords. You need it if you forget your PIN, to open a backup, and to move to a new phone."
- **Warning card** (`OutlinedCard` with an error-coloured border and icon):
  - "Nobody can reset it. We don't keep a copy, and there is no 'Forgot password' email. If you forget it, your passwords can't be opened on a new phone or from a backup."
  - "Don't use a password you already use anywhere else."

  Reason for the second line: 44 % of novices' answers on how they made their primary password involved reusing an existing password or personal data [E: SOUPS 2025].
- **Suggested passphrase** (the default view).
  - The six words appear as a **numbered list of plain text** in large type (1 vowel · 2 shiny · 3 shell · 4 crisp · 5 shaft · 6 cadet). Below it is the whole passphrase in monospace, exactly as it is typed: `vowel-shiny-shell-crisp-shaft-cadet`.
  - No chips: chips are buttons for TalkBack and invite taps.
  - Buttons: `Button` "Use these words", `TextButton` "Show other words", `TextButton` "Type my own instead".
  - Under the words: "The words are English. Type them with Latin letters."
  - **Generation.**
    - EFF short wordlist 1: 1,296 common words of 3–5 letters (mean 4.5); the one hyphenated word "yo-yo" is dropped.
    - 6 words, lower case: 62.0 bits [V: computed from the EFF list].
    - This is recommended over 5 words from the long list (64.6 bits, words of mean 7 letters), because short common words are easier for a non-native speaker to read, write and type [I]. **The crypto owner must confirm this change** (crypto.md proposed the long list).
    - Re-rolling a few times costs at most log2 of the number of rolls, in bits [I].
  - **Separator: hyphen for now.**
    - On Gboard a hyphen costs about 3 key presses (to the symbol layer and back); a space costs 1 [E: critique m16].
    - Proposed to the crypto owner: spaces. The strength stays at 62 bits, because no word contains a space. Runs of spaces in the master password would be folded to one and trimmed before NFC.
    - This needs a typing test on Gboard and Samsung Keyboard in a password field first (double-space full stop, automatic spaces) (§14).
- **Own password view.**
  - **The field**: an `OutlinedSecureTextField` with show/hide, a strength meter and a checklist.
  - **Every item of the checklist must pass.** The button stays enabled and says what is missing when pressed (no unexplained disabled buttons):
    - "At least 12 characters" (Bitwarden's minimum for master passwords) [E: `SetPasswordViewModel.kt`].
    - "Hard to guess":
      - zxcvbn score **4** (its highest: 10^10 guesses or more) for the password and for a folded copy of it (the search key of §5.11, search.md §1, so "Oʻzbekiston" is also checked as "ozbekiston" and "Ташкент" as "tashkent");
      - with the bundled dictionary below, plus the app name, "password", "parol" and "пароль" as user inputs.
    - When a dictionary word, name or year is found, the hint is: "Avoid city names, people's names and years."
    - No apostrophe-like characters, unless the crypto owner accepts the folding rule below.
  - **When the checklist passes**, a dialog asks: "Do you use this password for any other account?"
    - "Yes" → "Then choose a different one. If a website leaks it, anyone with your backup file could open it." The user goes back to the field.
    - "No, it's new" → continue.
  - **Mixed scripts**: if the text mixes Latin and Cyrillic letters, the screen says "Your password mixes Latin and Cyrillic letters. Is your keyboard set to the right language?"
- **Why score 4 and a local dictionary** [X: zxcvbn4j 1.9.0, re-running the critic's test in `scratchpad/design/ux/zx/`; numbers reproduced]:
  - zxcvbn4j ships English dictionaries only.
  - Under the old rule (12 characters, score ≥ 3), it accepted Toshkent2020 (score 3), and Mustaqillik1991, Samarqand2024!, Oʻzbekiston2024, Shahzoda2003! and "Assalomu alaykum" (all score 4).
  - With only 12 Uzbek words added, Toshkent2020, Mustaqillik1991 and Ozbekiston2024 drop to 1, and Samarqand2024! and Shahzoda2003! drop to 2. The apostrophe in Oʻzbekiston2024 hides the word, so it stays at 4 unless the folded copy is scored too.
  - Score 3 means 10^8 to 10^10 guesses: 16 hours to 68 days on one RTX 4090 at 1,703 Argon2id guesses per second [E: V-C13, zxcvbn README]. That is weak for a backup file the user may keep in Telegram or Drive.
- **Bundled dictionary** (new; also used by the strength meter in §5.14 and the security check in §5.16).
  - It is one ranked Uzbek and Russian list, added to zxcvbn4j's English lists with `ZxcvbnBuilder` and `StandardDictionaries` (both exist in 1.9.0 [X]).
  - Contents: Uzbekistan's regions, cities and districts; the most common Uzbek and Russian first names; and common words and phrases (salom, assalomu alaykum, vatan, mustaqillik, onajon, …). Each is in Latin, in Cyrillic and in the usual transliterations (Toshkent, Tashkent, Ташкент).
  - Engineering chooses the source lists and checks their licences. The test vectors in §12 decide whether the list is good enough.
- **Apostrophe-like characters.**
  - **The problem.** Phones type oʻ and gʻ with different characters (ʻ ‘ ’ ʼ '), and NFC does not unify them [E: FACTS §9]. A master password typed with one keyboard can fail on another phone, which happens exactly when a backup must be restored.
  - **Recommended (crypto owner; lock design 1 asks the same).** Before NFC, map every apostrophe-like character (the list of search.md §1, step 1) to U+0027 in the master password, at creation and at every use, with test vectors.
  - **Until that is accepted, creation refuses them:** "Your password contains ʻ or ’. Phones type these differently, so you might not be able to type it on another phone. Leave them out or choose another password."
  - Passwords of accounts are not affected: they stay exactly as typed.
- **Strength meter.**
  - 4 segments plus a word: "Very weak", "Weak", "Good", "Strong" (zxcvbn 0–1, 2, 3, 4). Colour is never the only signal.
  - Library: zxcvbn4j `com.nulab-inc:zxcvbn` 1.9.0, MIT, no dependencies, 503 KB jar [V: Maven Central]. It uses the same 0–4 scale as Bitwarden's report (§5.16).
  - Its English feedback strings are not shown. The app shows its own short hints: "Add another word", "Avoid city names, people's names and years", "Avoid keyboard rows like qwerty".

### 5.4 Write it down

- Title "Write it down on paper". Body: "Write your master password on paper now. Keep the paper at home in a safe place — not in your phone case, not in your wallet, and not as a photo on this phone."
- The password is shown in large monospace with its words numbered as in §5.3, plus a "Show in big letters" link (§5.13).
- `Checkbox` "I wrote it down" (optional; not enforced) and `Button` "Next". Reason: only 5 of 37 novices wrote their primary password into 1Password's Emergency Kit [E: SOUPS 2025]. The reminder matters; a forced checkbox does not.

### 5.5 Type it again

- Title "Type your master password again". Body: "This checks that you wrote it correctly."
- The field is focused and the keyboard opens when the screen appears. The keyboard's action key is "Done" and submits. A full-width `Button` "Next" under the field does the same.
- **On mismatch**: "This doesn't match. Look at your paper and try again."
  - After 3 mismatches, a `TextButton` "Show my password again" is added (back to §5.4).
  - If the attempt contains Cyrillic letters: "Your keyboard may be set to Russian. Switch to Latin letters."
- **On match**: "Setting up…" (`CircularProgressIndicator`, about 1 s for key derivation, the file write and a read-back check) [E: crypto.md §11]. Error: "Couldn't save. Your phone's storage may be full." with "Try again".
- Then the vault exists, and back now leaves the app (§4).
- **Interrupted setup.**
  - The words are never stored. If the app is closed or killed after they were shown and before the vault exists, the next start shows new words with: "Setup was interrupted. Cross out the words you wrote down before. These are your new words."
  - A non-secret flag remembers that words were shown.

### 5.6 Create the PIN

- Title "Choose a 6-digit PIN". Body: "Use this PIN to open the app on this phone, like your bank app. Don't use your phone's unlock PIN or your bank card PIN." Family members often know the phone PIN [I]. The lock design adds its own rules, for example lock design 2's "Don't use birthdays or other dates…".
- When TalkBack (or another spoken-feedback service) is on, the body adds: "TalkBack reads each key aloud. Use headphones if other people are near."
- The keypad and dots are the same as on the lock screen (§5.9), without the fingerprint key.
- Confirm step: "Enter the PIN again". Errors: "The PINs don't match. Try again." · "This PIN is too easy to guess. Choose another." The blocklist and its messages belong to the lock design.
- Footer: "Forgot your PIN later? Tap 'Forgot PIN? Use master password' and choose a new one."
- **Length**: 6 digits, submitted automatically after the sixth. Both lock designs choose exactly this.
  - If fewer digits are ever allowed, the keypad gets an "OK" key and never submits by itself. Otherwise the length would be revealed (CDD 17 C-SR-7) [E: FACTS §6.3].

### 5.7 Fingerprint offer

**When it is shown.**
- On Android 9 and newer, when `BiometricManager.canAuthenticate(BIOMETRIC_STRONG)` returns `BIOMETRIC_SUCCESS`.
- Also on Android 9 and 10 when it returns `BIOMETRIC_STATUS_UNKNOWN`, provided the phone has a fingerprint sensor (`FEATURE_FINGERPRINT`) and a screen lock.
  - With the androidx.core that every Compose app ships, Android 10 returns `STATUS_UNKNOWN` on almost every phone even though authentication works [E: V-P1; lock design 2 §8.4, lab].
  - Turning it on decides: a failure says so.
- On Android 7 and 8 the library cannot reach the sensor [E: V-P1]. The offer is skipped, and Settings says "Needs Android 9 or newer".
- Weak (Class 2) sensors are not offered, as in Keyguard [E: keyguard docs].

**Names.**
- On Android 12+ the button and setting names come from `BiometricManager.getStrings(BIOMETRIC_STRONG)` (API 31 [V]), so a phone whose only strong biometric is the face says "face".
- The app's own sentences say "fingerprint", or "face" when the phone has a strong face sensor and no fingerprint sensor.

**The screen.**
- Title "Use your fingerprint?" Body: "Open the app with your fingerprint instead of your PIN. Your PIN and master password still work."
- Note: "If this phone's fingerprints or screen lock change, fingerprint unlock turns off for safety. You can turn it on again in Settings."
- `Button` "Use fingerprint" opens `BiometricPrompt` with the crypto object (title "Turn on fingerprint unlock", negative button "Cancel"). `TextButton` "Not now".
- Errors:
  - cancelled → stay on the screen;
  - failure → "Fingerprint unlock could not be turned on. You can try again later in Settings."
  - Setup is never blocked.

### 5.8 Backups explained

- Title "Make backups". Body:
  - "Your passwords are kept only on this phone. If the phone is lost, broken or reset, they are gone. Uninstalling the app or clearing its storage also deletes them."
  - "A backup is a locked file with all your passwords. It opens only with your master password."
  - "Keep a backup outside this phone — in your Google Drive, on a computer, or send it to yourself."
- `Switch` "Remind me to make a backup" (on). `Button` "Finish".
- No backup is made now, because the vault is empty. The first reminder appears one day after the first account is added (§5.11).

### 5.9 Lock screen — PIN (look and wording; behaviour from the lock design)

**Layout** (portrait):
- app icon 64 dp; title "Password Vault"; subtitle "Enter your PIN";
- a row of 6 dots, filled as digits are entered;
- a status line for errors;
- a 3 × 4 keypad `1 2 3 / 4 5 6 / 7 8 9 / [fingerprint] 0 [⌫]`;
- below it, `TextButton` **"Forgot PIN? Use master password"**.

Details:
- **Keys.** At least **64 dp** (72 dp preferred); digits in `headlineMedium`; a ripple and `HapticFeedbackType.VirtualKey` on press (Compose 1.12 has it [V: bytecode]).
  - No letters under the digits; **never a shuffled keypad** (it harms accessibility and speed).
  - Keys and dots are marked sensitive (§3).
- The fingerprint key appears only when fingerprint unlock is usable; otherwise that cell is empty.
- **Landscape**: dots and texts on the left, keypad on the right. With large fonts the keypad scales and the column scrolls; nothing is cut off.
- **Input**: the keypad is drawn by the app, so no keyboard app sees the PIN [I from platform.md §4: a keyboard sees every keystroke]. A hardware keyboard's digit and Backspace keys also work.

**Behaviour.**
- When fingerprint unlock is usable, the system fingerprint prompt opens by itself when the lock screen appears (cold start or return).
- It does not open by itself:
  - on a practice day;
  - after the PIN was turned off, if the lock design blocks fingerprint then;
  - again after the user cancelled it.

  The fingerprint key opens it again.
- Prompt: title "Open Password Vault", negative button "Use PIN".

**States and wording.** The triggers and numbers come from the lock design and the storage design.

| State | What the user sees |
|---|---|
| Wrong PIN | Dots shake (not when animations are off), `HapticFeedbackType.Reject`, dots clear, "Wrong PIN. 4 tries left." On the last try: "Wrong PIN. 1 try left. After that you'll need your master password." |
| Same wrong PIN again (lock design 2) | "That's the PIN you just tried." It is not counted |
| Waiting (only if the lock design adds waiting times; neither candidate does) | Keypad disabled, "Too many wrong tries. Try again in 0:30." |
| PIN turned off after too many wrong tries | The §5.10 screen: "Your PIN was entered wrong too many times, so it was turned off. Type your master password, then choose a new PIN." If fingerprint is still allowed (recommended below), its prompt opens and the text starts "Use your fingerprint or type your master password…" |
| Opening | The keypad is replaced by `CircularProgressIndicator` and "Opening…" |
| PIN can't be checked right now (a temporary Keystore error [E: V-C6]) | The §5.10 screen: "Your PIN can't be checked right now. Use your master password this time. Restarting the phone usually fixes this." Nothing is removed |
| Storage full | "Your phone's storage is full, so the PIN can't be checked now. Free some space or use your master password." |
| PIN stopped working (its key is gone) | The §5.10 screen: "Your PIN stopped working on this phone. Type your master password, then choose a PIN again." |
| **No PIN on this phone yet**: the vault came with "Copy apps and data", the app was installed again with its data kept, or the lock file is missing or damaged [E: V-P6; platform.md §3.4, §3.7] | The §5.10 screen with the title **"Welcome back"**: "Your PIN and fingerprint don't work here yet. This happens after you move to a new phone or install the app again. Type your master password once, then choose a PIN for this phone." Then "Choose a PIN" (skippable) and the fingerprint offer. Nothing is deleted |
| Fingerprint turned off by the phone (only when its key is permanently invalidated) | "Fingerprint unlock was turned off because this phone's fingerprints or screen lock changed. Use your PIN. You can turn it on again in Settings." |
| Fingerprint can't be checked right now (any other fingerprint error) | The fingerprint key is hidden until the next lock, with "Fingerprint can't be checked right now. Use your PIN." The option stays on [E: V-C6] |
| Fingerprint locked by the system after failures | "Fingerprint is locked for now. Use your PIN." |
| **File damaged**: no saved copy opens, although the PIN or master password is right [E: V-S3] | Title "Your passwords can't be opened". Body: "The file with your passwords is damaged. Nothing was deleted. Restart your phone and try again. If that doesn't help, restore from a backup file." `Button` "Try again", `OutlinedButton` "Restore from a backup file". The backup is opened and checked before anything changes, and the damaged files are kept (§5.10) |

**After unlock**, notices appear one at a time:
- **Older copy opened** (storage fell back a generation): `AlertDialog` "The newest copy of your passwords could not be read. We opened the copy from 5 October, 18:42. Changes after that time are missing." Buttons "Make a backup now" and "OK". A copy that cannot be read can be a sign of storage trouble [E: storage.md §5.4].
- **Older than last time** (rollback [E: V-C8]): "These passwords are older than the ones this app opened on 6 October, 09:10. Changes after that time are missing. If you didn't restore anything, someone may have replaced the app's files." Button "OK".
- **Failed tries since the last unlock**: the lock design's notice, for example "Someone typed a wrong PIN 3 times before you opened the app (last time today at 14:32)…".
- **After an unlock through "Forgot PIN?", or after the PIN was turned off**: the "Choose a new PIN" screen (§5.6) with `TextButton` "Not now". This takes 16 taps and one passphrase in all, instead of a trip to Settings with the passphrase typed twice (21 taps) [E: critique task 7].

**Never** a "wipe after N attempts" message (no wipe exists) [E: account-based.md finding 4], and never a hint of how many accounts are stored.

**Recommendations to the lock design** (from the user's side):
1. **Keep fingerprint unlock usable after the PIN has been turned off.** Lock design 1 does; lock design 2 does not. The sensor has its own hardware lockout, so a child pressing keys cannot force the master password, and an owner who has forgotten the master password still gets in and can choose a new one (§5.24).
2. **Allow a practice grace period of about 7 days** (§5.10).
3. **Keep the forgotten-master-password route through the PIN** (§5.24). Both candidates have one.

### 5.10 Lock screen — master password

**Layout**:
- title "Type your master password";
- an `OutlinedSecureTextField` (keyboard type password, no auto-capitalisation, marked sensitive);
- `Button` "Unlock";
- `TextButton` "Use PIN" when the PIN can be used now (labelled "Not now: use PIN" on a practice day);
- `TextButton` "Forgot master password?".

The **subtitle** says why the master password is needed now:
- practice (below);
- the PIN states of §5.9 ("turned off", "can't be checked right now", "stopped working", "Welcome back");
- none when the user chose it with "Forgot PIN?".

**Practice** (recommendation to the lock design). Both candidates ask about 1, 3 and 7 days after the master password is created or changed, then every 14 days; they count the intervals slightly differently.
- **On a practice day**, the lock screen opens on this screen instead of the PIN pad, and the fingerprint prompt does not open by itself. Subtitle: "Time to practise your master password. We ask now and then so you don't forget it. You need it to open a backup and to move to a new phone."
- **"Not now: use PIN"** opens the PIN pad with its fingerprint key. The next lock screen asks again. Aegis works the same way: when its reminder is due it focuses the password field, but its fingerprint button still unlocks after one dialog [V: Aegis 3.4.3 `ui/AuthActivity.java:180-193, 211-224`].
- **Grace period.** Skipping works for a grace period; about **7 days** is recommended (lock design 1 allows one skip, lock design 2 three days).
- **After the grace period**, the master password is required. "Forgot master password?" still offers the PIN route (§5.24), so nobody is locked out of their passwords because the paper is at home.
- **Why not skippable forever.** A reminder that can always be skipped stops finding out that the password was forgotten, and only while the PIN still works can a forgotten password be replaced. crypto.md §11 step 6 ("after 14 days … require the password") should be restated as this rule (§14).
- **What counts as practice.** Any correct master password: unlock, practice, export, backup or change.

**Wrong master password.**
- The message is "That's not the right master password." If the attempt contains Cyrillic letters, it adds "Your keyboard may be set to Russian."
- After 2 wrong tries, when the PIN or fingerprint still works, it adds "Can't remember it? Open the app with your PIN, then choose a new master password." with `TextButton` "I forgot it", which opens the sheet below.
- If the lock design pauses the field after several wrong tries: "Wait 30 seconds, then try again. Take your time and look at your paper." (lock design 2 §5.6).

**Not enough memory** (the master-password check needs about 64 MiB) [E: V-C2]: "Your phone doesn't have enough free memory to open your passwords. Close other apps or restart the phone, then try again." `Button` "Try again".

**Master-password reuse check.** After every correct master-password entry (unlock, practice, backup, export, change), the app compares the typed master password with every entry's password in memory. It stores the ids of the matches inside the locked file (§2, §5.16). Nothing about the master password itself is stored [I]. A single new or changed password is also checked when it is saved (§5.14).

**"Forgot master password?"** opens a `ModalBottomSheet`.
- **Title**: "Forgot your master password?" Body: "Nobody can recover it: it is not stored anywhere."
- **The options**, only those that apply, in this order:
  1. **When the PIN or fingerprint still works**: "Your PIN still opens the app on this phone. Open the app with it, then choose a new master password." `Button` "Use my PIN" (or "Use my fingerprint") → §5.24.
  2. "If you remember the master password of an older backup, you can open that backup instead. Your current passwords stay on this phone, locked." `OutlinedButton` "Restore from a backup file". This follows §5.2 steps 1–4: the backup is opened and checked with its password, and its summary card adds "Your current passwords stay on this phone, locked." with the button "Use this backup". Only then is the current vault archived and the backup installed. Setup continues at the PIN step.
  3. **Only when neither the PIN nor the fingerprint works**: "Start with an empty app. Your current passwords stay on this phone, locked. If you remember the master password later, you can open them again." `TextButton` "Start with an empty app…" opens a dialog:
     - title "Start with an empty app?";
     - body "Your passwords stay on this phone, locked with your old master password. You can open them again from the first screen or from Settings › Backup & transfer.";
     - buttons "Start with an empty app" and "Cancel".
  4. `TextButton` "Try again" closes the sheet.
- **No path that starts at the lock screen deletes anything, and none uses the word "Delete"** [E: V-S3: "Never offer a destructive 'reset vault' in response to a wrong password"].

**Archived vaults** (owners: storage and lock designs).
- "Start with an empty app" and a restore over an existing vault move the vault's files to an archive name that clean-up never touches, like the quarantine of V-S4. Then setup starts.
- Archives appear:
  - on Welcome as "Open my previous passwords (locked on 6 Oct 2026)" (§5.1);
  - in Settings › Backup & transfer as "Old passwords (locked on 6 Oct 2026)", with "Open" and "Delete".

  "Open" in Settings adds the archive's accounts to the current vault, like a backup import (§5.21).
- **Deleting an archive needs that archive's master password.** Otherwise anyone holding the phone could start over and then delete the owner's passwords from the new vault's Settings. An archive whose password is forgotten simply stays; it is small.

### 5.11 Passwords (home list) and search

**Layout** (`Scaffold`):
- **`TopAppBar`**: title "Passwords"; action `IconButton` "Lock now" (lock icon).
- **Search field**, directly below: `SearchBarDefaults.InputField` from the Material 3 `SearchBar` family, used as an in-place filter (the full-screen expanded mode is not used). Placeholder "Search accounts".
- **Banners** (`OutlinedCard`), **at most two at a time**, in this priority order (the others wait):
  1. a new-master-password request waiting for its date (§5.24; only if the lock design has a waiting time);
  2. an unprotected export not yet deleted (§5.20);
  3. backup overdue (rule below);
  4. a password change waiting for confirmation (§5.17).

  Because two are allowed, a kept export file or an unfinished change can no longer hide the backup reminder.
- **`LazyColumn`**:
  - a "Favourites" section header, then the favourite rows (more than 10: show 10 and "Show all favourites");
  - an "All accounts · 48" header, then all entries with sticky letter headers (headers are semantic headings);
  - last, when not empty, a row "Recently deleted (3)" (§5.23).
  - Its bottom content padding is at least the height of the Add button plus 16 dp, plus the clipboard status row when it shows. So the button never covers the last row's copy button.
- **`ExtendedFloatingActionButton`** "Add" (collapses to the + icon while scrolling down).
- **Bottom**: the navigation bar (Passwords · Security · Settings) and, when something is copied, the clipboard status row (§6) above it. The Security item shows a dot when there are new results (§5.16).

**Row** (`ListItem`):
- leading: a 40 dp circle with the first letter of the title (first grapheme; colour picked from 8 tones by a hash of the title, letter contrast ≥ 4.5:1);
- headline: the title (up to 2 lines);
- supporting: the username, or the first website's host if there is no username (1 line);
- trailing: `IconButton` "Copy password" (only when the entry has a password; 48 dp target).

Behaviour:
- Tap the row → entry detail. Tap copy → §6; the icon turns into a check mark for 1.5 s.
- **While `changePending` is true**, the row shows a small clock icon, and its copy button opens a two-item menu: "Copy old password" · "Copy new password (not confirmed)" (§5.17).
- Long press → `DropdownMenu`: Copy username · Copy password · Open website · Show in big letters · Add to favourites / Remove from favourites · Edit · Delete. The same actions are TalkBack custom actions on the row.

**Sorting.**
- A–Z by title with `android.icu.text.Collator` for the app's language (API 24+ [V: api-versions.xml]), on the sort key of i18n.md §2.7. That key writes oʻ and gʻ the same way whatever apostrophe was typed. Favourites come first. There is no sort menu and no "recently used" (§2).
- **Letter headers** come from `android.icu.text.AlphabeticIndex` (API 24 [V]) built for the same language, with English and Russian labels added, and fed the same sort key.
  - Under Uzbek, ICU 78 sorts Oʻ, Gʻ, Sh and Ch after Z, as the alphabet does, and AlphabeticIndex gives them their own headers.
  - Android 7's ICU 56 has no Uzbek order and gives C, G, O and S, which again match its sort.
  - Headers taken from each title's first letter would print a second O, G, S and C after Z [X: ICU4J 78.1 and 56.1, `scratchpad/design/ux/icu/`].

**Search** (all in memory, on every keystroke, no debounce up to 5,000 entries).

[search.md](search.md) specifies the search key, matching and ranking, with working code and measurements. For search it supersedes the fold of i18n.md §2.7, and it passes the test vectors of both documents. What the user gets:
- **Matched fields**: title, username, website hosts, extra-field names, values of extra fields that are not secret, and notes.
  - **Never** the password, previous passwords or secret values. Proton's search index also excludes passwords [E: account-based.md §3].
  - Website paths are not searched, because short queries would then match almost everything (search.md §8).
- **Words**: the query is split on spaces, and every word must match somewhere, in any order (AND), as in Proton and Keyguard [E: ux.txt `ItemUiFilter`, Keyguard search docs]. Partial matches count, and spaces and punctuation are ignored inside a match ("clickuz" finds "Click Uz") [E: search.md §2].
- **Spellings.** One search key covers every spelling (search.md §1):
  - every apostrophe variant of oʻ and gʻ;
  - the 2026 letters (ö ğ ş ç → o g sh ch);
  - Cyrillic to Latin by the official rules;
  - accents removed.

  A second, slightly lower tier finds Russian-keyboard spellings (у к г х for ў қ ғ ҳ, е for ё): "узбек" finds "Ўзбекистон", and "хамкор" finds "Ҳамкорбанк".

  Why it matters: the Senate's sponsor said Oʻ and Gʻ are written in "more than 10 variants" that software treats as different words [V: Gazeta.uz 2026-09-10], and even Android's own Uzbek strings mix U+2018 and U+02BB [V: AOSP SettingsLib `values-uz`].
- **Two additions from the critique** (asked of the search owner, §14):
  - **Accent-free ş and ç.** search.md maps ş and ç only to sh and ch, so "besiktas" does not find "Beşiktaş" and "garcon" does not find "Garçon" (its §6 item 4). Adding ş → s and ç → c to the second tier keeps accent-free Turkish, Azerbaijani and French queries working [E: critique m1].
  - **The wrong keyboard layout.** When a query finds nothing, it is retried through the Russian ЙЦУКЕН ↔ Latin QWERTY key map in both directions. If that finds accounts, they are shown under "Showing results for "inst"" ("штые" typed on the wrong layout).
- **Ranking** (search.md §2):
  - the field first: title › username and website host › extra fields and notes;
  - then the position: the whole field › its start › the start of a later word › inside a word;
  - second-tier matches rank slightly lower;
  - ties keep the A–Z order.
- **Test vectors**: search.md's tests, which include the vectors of the first version of this document and of i18n.md §2.7 ("Oʻzbekiston" = "O'zbekiston" = "O‘zbekiston" = "Özbekiston" = "Ўзбекистон" = query "ozbek"; "Toshkent" = "Toşkent"; "maʼlumot" = "ma'lumot"; "Köln" matches "koln"; "мой" does not match "мои"). Plus the two additions: "besiktas" finds "Beşiktaş"; "штые" shows the results for "inst".
- **Focus.**
  - Nothing is focused after unlock (the keyboard would hide favourites); one tap on the search field focuses it and opens the keyboard.
  - The keyboard's search key hides the keyboard.
  - The query and scroll position are kept while the user opens an entry and comes back; back clears the query.
- While searching, the favourites section is hidden and matched text is bold (search.md `matchedRanges`).

**States**:
- **Empty vault**: the text "No accounts yet" and "Add your first account and its password, or bring your passwords from Chrome." Buttons "Add account" and `OutlinedButton` "Import from Google Password Manager".
- **No search results** (after the layout retry): 'No accounts match "gmal".' and `TextButton` 'Add "gmal" as a new account', which opens Add with the title filled in.
- **Many entries**: tested with 5,000; stable keys; no images; frame time within 16 ms on a mid-range phone.
- **Long content**: titles wrap to 2 lines, then end with "…"; usernames take 1 line.
- **Error**: a failed save shows the snackbar "Couldn't save. Your phone's storage may be full." with "Try again"; the screen keeps the user's data.
- **Backup reminder banner**: "Your newest passwords are not in a backup yet." Buttons "Back up now" and "Later" (snooze 3 days). It shows when all of these hold:
  - the reminder is on;
  - there are changes not in any backup;
  - either no backup exists and the first account was added a day or more ago, or the last backup is 7 or more days old.

  The guided change also offers a backup right after a password is changed (§5.17).

### 5.12 Entry detail

**Layout**: a `TopAppBar` with back, the title (one line; the full title is the first line of the content), actions `IconButton` star "Add to favourites" and `IconButton` "Edit", and an overflow menu (Show in big letters · Leave out of the security check · Delete).

Content (`LazyColumn` of `ListItem`s):
- **Username**: the value; trailing "Copy username".
- **Password**:
  - 8 dots; trailing `IconToggleButton` "Show password"/"Hide password" and `FilledTonalIconButton` "Copy password"; below, `TextButton` "Show in big letters".
  - When revealed: monospace, digits and symbols in different colours (as Bitwarden and KeePassDX do [E: ux.txt]); it hides again after 30 s without a touch.
  - While `changePending` is true, there are two rows instead, each with its own show and copy: "Old password (the website may still use it)" and "New password (not confirmed yet)".
- **Websites**: each in its own row, with the host in bold and the full address below.
  - Trailing "Open" opens the browser with `ACTION_VIEW` (only `http`/`https`); on `ActivityNotFoundException`: "No browser found on this phone."
  - Trailing "Copy".
  - `android://` rows show "App: com.example" with copy only.
- **Notes**: the full text, selectable; longer than 12 lines collapses with "Show more".
- **Extra fields**: label and value (masked when secret, with show), each with copy.
- **History and dates** (smaller text): "Password changed 3 days ago" · "Added 1 Oct 2026" · "Edited 5 Oct 2026". `TextButton` "Previous passwords (2)" expands to masked rows with date, show and copy.
- **Banners**:
  - `changePending` → "You started changing this password on 6 Oct. Did the website accept the new one?" Buttons "Yes, it's changed" · "No, put back the old password" (§5.17).
  - `checkIgnored` → "This account is left out of the security check." with "Check it again".
  - The password equals the master password (§5.14) → "This password is also your master password. If this website leaks it, anyone with your backup file can open it. Choose a new master password." `Button` "Choose a new master password" (§5.18).

**Delete** moves the account to Recently deleted at once, with the snackbar "Gmail deleted" and "Undo" (about 10 s). There is no confirmation dialog, because the delete can be undone for 30 days (§5.23).

**States**:
- entry not found (deleted elsewhere) → back to the list with "This account no longer exists.";
- long content wraps fully;
- an empty entry (title only) shows "No details yet" and "Edit".

### 5.13 Show in big letters

A full-screen dialog for a password, username or secret value of up to 128 characters (Keyguard's limit [V: Keyguard docs]). Longer values show "Too long to show in big letters".
- **Cells.** Each character sits in its own cell, monospace, about 32 sp, with its position number underneath. Cells wrap in rows of 4 to 8 depending on width, and digits and symbols are coloured.
- **Keeping the place.** Tapping a cell highlights it and every cell before it, to keep the user's place [V: Keyguard docs].
- **Time limits.**
  - The screen stays on (`FLAG_KEEP_SCREEN_ON`) while it is open.
  - It **closes after 2 minutes without a touch**; any tap restarts the time. Reason: §3's "render secrets rarely and briefly" also applies here. Showing a whole password large is an accepted exception, which the security owner records in the threat model (§14).
- **Closing.** It closes with back or "Close", and it is never kept after the screen stops.
- **Font.** Recommendation: bundle one monospace font whose 0/O and 1/l/I differ clearly and which covers Latin, Cyrillic and ʻ (U+02BB), used only for secrets. Android's DroidSansMono lacks U+02BB [E: i18n.md §2.6]. Engineering checks the licence and size.

### 5.14 Add and edit

**Layout**: a `TopAppBar` with "Close" (X) and the title "New account" or "Edit account", and the action `TextButton` "Save". The form is a scrolling column, and the keyboard never covers the focused field (`imePadding`). One-line fields move to the next field with the keyboard's Next key; on the last field the key is Done.

Fields in order:
1. **Name** (`OutlinedTextField`, required, capitalise words): label "Name", example "Gmail".
2. **Email, phone or login** (`OutlinedTextField`, keyboard type email, no auto-correct).
3. **Password** (`OutlinedSecureTextField`).
   - Trailing icons "Show" and **"Create a strong password"** (dice icon).
   - When the field is empty, a `TextButton` "Create a strong password" also sits under it, because users could not find generators in the tested apps [E: CCS 2019 "lack of guidance"].
   - Under a typed password:
     - the strength meter (§5.3, with the bundled dictionary);
     - a reuse note: "You use this password for 5 other accounts. You can change it later in Security." It is information and never blocks: the user first needs to record the current password.
4. **Website** (`OutlinedTextField`, keyboard type URI): `TextButton` "Add another website" (up to 10); each extra row has "Remove".
5. **Notes** (multi-line `OutlinedTextField`, sentences capitalised).
6. **Extra fields**: `TextButton` "Add field" opens a small dialog:
   - label chips "PIN code", "Phone number", "Recovery email", "Security question", "Answer", "Code word" or "Other…";
   - then the value, and `Switch` "Hide this value" (on by default, except for phone number and email).

**Behaviour**:
- **Saving.**
  - Save validates. A blank name is filled from the website host, or with "Untitled" plus the time (Save is never disabled without explanation).
  - If another saved account has the same name and username, a dialog asks "You already have Gmail with this login. Save anyway?" It is checked only against other saved accounts, never against this form's own draft.
  - Changing the password of an existing entry moves the old one to Previous passwords and sets `passwordChangedAt`.
- **After Save**:
  - a new account opens its detail screen with the snackbar "Saved", so its password can be copied with one tap (for example, to paste it into a sign-up form);
  - an edited account returns to its detail screen.
- **Same as the master password.**
  - After Save, if the password is new or changed, the app checks it against the master password in the background. This is one run of the master-password check (about 1 s); it uses the stored lock, not a copy of the master password.
  - A match shows the banner of §5.12. If the phone lacks memory for the check, it is skipped silently; the next master-password entry repeats it (§5.10).
  - Nothing is checked while the user types: that would need the master password, or a fast hash of it, in memory.
- **Unsaved changes when the app is left** (the user goes to another app, or the screen turns off).
  - The app saves a **draft** inside the locked file: the entry id (none for a new account), every field as typed, and the time. The entry itself is not changed. A generated password counts as a change.
  - After unlock, the form reopens from the draft with the snackbar "Your unsaved changes were kept."
  - Save commits the entry and deletes the draft; Discard deletes the draft.
  - There is only one draft (the newest form wins).
  - A draft older than 30 days is offered once ("You have unsaved changes for Gmail from 5 Sep. Open them?" Open · Discard) and then dropped.
  - Reason: the user often generates a password, switches to the website to sign up, and returns after the lock delay, and low-RAM phones also kill background apps [I]. A draft keeps that work without writing half-typed passwords into the entry or its history, and without "Untitled" entries or duplicates.
- **Back or Close with changes**: "Save changes?" (§4).

**States**:
- saving (the button shows a small progress after 300 ms);
- save error (snackbar; the form is kept);
- long input stops at the limit, with a counter ("10,000 / 10,000").

### 5.15 Password generator (bottom sheet from add, edit and the guided change)

A `ModalBottomSheet` (predictive back closes it).
- **Preview**: the generated password large, monospace, with coloured digits and symbols; `IconButton` "New password" (refresh) next to it; the strength word under it.
- **Mode**: `SingleChoiceSegmentedButtonRow` — "Random" · "Words".
- **Random** (default):
  - "Length" `Slider` 4–64, default **16**, with − and + `IconButton`s and the number shown (sliders alone are hard for some users).
  - `Switch`es "A–Z", "a–z", "0–9" and "Symbols ! # $ % & * + - = ? @ _" (all on); `Switch` "Avoid look-alike characters (I l 1 O 0)" (on).
  - At least one character of each chosen kind is included. The last remaining kind cannot be switched off ("At least one kind of character is needed.").
  - With the defaults the alphabet has 69 characters: about 97 bits at 16 characters [I].
- **Words**: "Number of words" 4–12, default 6 (EFF short list as in §5.3); "Separator" (- . _ space); "Capital letters" (on); "Add a number" (on).
- Hint: "If a website refuses this password, try fewer characters or turn symbols off."
- **"Use this password"** (primary `Button`) fills the field. If the field already has a password, a dialog asks "Replace the current password?" (Bitwarden asks the same [E: `password_override_alert`]).
- **No "Copy" button in the sheet**: a generated password is saved with its account first. Save then opens the detail screen, where copying takes one tap (§5.14).
- **Randomness**: `SecureRandom`, unbiased choice [E: crypto.md §9]. The last options are remembered (not secret).
- **Defaults compared** [E: ux.txt]:
  - Bitwarden 14 characters (5–128), passphrase 3 words;
  - KeePassDX 14 (1–64), 8 words;
  - Proton words mode by default, 5 words with capitals and a number, random 20;
  - Keyguard 16 (4–128), 5 words.

  16 is chosen because some sites cap length at 16–20, and copy-paste makes length free [I].

### 5.16 Security check (tab "Security")

**Computation.**
- The check runs in the background after unlock. zxcvbn scores each password with the bundled dictionary (§5.3), using the parts of the username and the title as user inputs, as Bitwarden does [V: bitwarden/clients `weak-passwords-report.component.ts`].
- The tab shows `LinearProgressIndicator` "Checking 120 of 480…" until it is done. Results are cached for the session and updated after each save.

**Top card** (first visit, then collapsible): "Why this matters: if one website leaks your password, criminals try it on your other accounts. Give every account its own password. Start with your email — whoever controls your email can reset your other passwords."

**Cards** (`ElevatedCard`, in this order, each with a count and one button):
1. **Master password used elsewhere** (only if found; §5.10 and §5.14).
   - Text: "Your master password is also the password for 50 accounts (Gmail, Facebook…). Anyone who learns it from one website can open your backups. Choose a new master password now, make a new backup, and delete your old backup files."
   - With one account: "…also the password for Gmail."
   - Button "Choose a new master password" → §5.18. That flow ends with the new backup and the list of old backup places.
   - Changing one website's password would not help: old backups keep the old master password, and it may already have leaked.
2. **Same password on several accounts**: "17 accounts use the same password." with a progress line for the biggest group: "3 changed, 14 to go". → group list.
3. **Easy to guess** (zxcvbn ≤ 2 with the bundled dictionary; ≤ 2 is Bitwarden's threshold [V]): "4 passwords are easy to guess." → list.
4. **Changes not finished**: "1 password change is waiting for your answer (Gmail)." → entry.
5. **Left out**: "2 accounts are left out of the check." → list, with "Check it again".

Other states:
- All clear: "All your passwords are different and hard to guess. Well done."
- Empty vault: "Add some accounts first. The check runs on this phone only."
- No breach ("leaked password") check: it needs the internet, which the app does not have; Help says so.

**Badge** on the Security tab:
- It is a small dot, never a number, shown when the results contain something the user has not seen since last opening the tab.
- For this user, cards 1–3 cover nearly every account for months, and a red "50" at every unlock is the nagging principle 5 rules out.
- No notifications.

**Group list** (one group of reused passwords):
- Header "These 17 accounts share one password". The password itself is never shown in headers.
- Rows: title, username, `TextButton` "Change".
- Order: email-like accounts first (the website or name contains gmail, google, mail, yandex, outlook, hotmail, icloud, yahoo, pochta, почта), then favourites, then A–Z.
- Overflow per row: "Leave out of the security check" (`checkIgnored`).
- **Nothing here combines accounts.**
  - Same-login duplicates on related websites are joined at import, under exact rules (§5.21).
  - A rule like "same username and password and they look related" would match almost every pair for a user who reuses one password. It would merge separate accounts such as Facebook and Instagram, after which a change would update only one site.

### 5.17 Guided change of one password

One scrolling screen with numbered steps (`OutlinedCard` per step), because the user leaves and returns several times. The state is stored in the entry (`changePending`, `pendingOldPassword`), so it survives a lock and a process kill.

Passwords are named from the website's point of view:
- the **old password** is the one the website has now;
- the **new password** is the one the app created.

1. **"Open Gmail and sign in."** `Button` "Open website" (if a website is stored) and `OutlinedButton` "Copy old password".
2. **"Find 'Change password'."** "It is usually in Settings › Security, or Account › Password."
3. **"Create the new password."** `Button` "Create new password" uses the generator defaults.
   - The app **saves it at once** as the entry's password, keeps the old one in `pendingOldPassword`, and sets `changePending`.
   - Text: "Saved. Your old password is kept until you finish."
   - `TextButton` "Password rules" opens the generator sheet.
   - **Retries.** After the first time, the button reads "Create another password". The replaced new password goes to Previous passwords, in case the website took it after all. The old password stays in `pendingOldPassword` however many tries it takes, so no number of retries can push it out.
4. **"Paste the passwords into the website."** `OutlinedButton` "Copy old password" and `Button` "Copy new password". Hints:
   - "Websites usually ask for the old password once and the new one twice."
   - "If Chrome offers to create a password for you, don't use it. Paste ours."
5. **"Did the website accept the new password?"**
   - `Button` **"Yes, it's changed"**:
     - The old password goes to the front of Previous passwords, and `pendingOldPassword` and `changePending` are cleared.
     - The screen shows "Gmail now has its own password." with `Button` "Next: Facebook" or "Done for now".
     - Once per session, when there are changes not in a backup, it adds "Gmail's new password is only on this phone until you make a backup." with `TextButton` "Back up now".
   - `OutlinedButton` **"No, put back the old password"**: the old password becomes the entry's password again, the rejected new one goes to Previous passwords, and `pendingOldPassword` and `changePending` are cleared.
   - `TextButton` **"I'll finish later"** keeps `changePending`.

**While `changePending` is true**, every place that copies the password offers both:
- the list row's menu (§5.11);
- the detail screen's two rows (§5.12);
- TalkBack labels that say the same.

Last account of a group: "Last one! After this, your old password is not used anywhere."

**Why save before the website change.** The classic failure is a password changed on the website but never saved. `pendingOldPassword` keeps the website's password whatever happens [I]. SOUPS 2025 recommends nudges that "encourage users to change weak passwords immediately", and better audit presentation [E].

### 5.18 Settings

A `LazyColumn` of `ListItem`s grouped under headings.

| Group | Item | Control | Default |
|---|---|---|---|
| **App lock** | Change PIN | screen | — |
| | Fingerprint unlock | `Switch`. When unavailable, the reason: "Needs Android 9 or newer" · "Set a screen lock and add a fingerprint in your phone's settings first" · "This phone's fingerprint sensor isn't secure enough". On Android 12+ the name comes from the system (§5.7) | off |
| | Lock the app after leaving it | dialog with `RadioButton`s: Immediately · After 30 seconds · After 1 minute · After 5 minutes. Time in a system file picker or share sheet the app opened counts as time in the app (§4) | **30 seconds** |
| | (always) | Lock at once when the screen turns off; lock after 5 minutes without a touch while open | not configurable |
| | Clear copied text after | 30 seconds · 1 minute · 2 minutes · 5 minutes (no "Never") | **1 minute** |
| | Change master password | screen; includes "I don't remember it" (§5.24) | — |
| Backup & transfer | Make a backup | screen; supporting text "Last backup: 3 days ago, Google Drive" | — |
| | Backup reminders | `Switch` | on |
| | Export | screen | — |
| | Import | screen | — |
| | Old passwords (locked on 6 Oct 2026) | only when an archived vault exists: "Open" · "Delete" (§5.10) | — |
| Appearance & language | Language | dialog: System language · Oʻzbekcha · Ўзбекча · English · Русский | System language |
| | Theme | System · Light · Dark | System |
| Help | How your passwords are protected · If you lose your phone or move to a new one · If you forget your master password · If you forget your PIN · Open-source licences · Version | screens | — |

The group is called "App lock" (glossary term 69 in i18n.md), so that "Security" means only the tab.

**Defaults compared** [E: ux.txt]:
- lock: Bitwarden 15 min, Proton 2 min, KeePassDX 5 min, Keyguard 5 min;
- clipboard: Bitwarden and Keyguard never, Proton 60 s, KeePassDX 20 s.

The app is stricter on locking because the user asked for a bank-like lock. It keeps a short grace period because frequent unlocking is the classic complaint. In SOUPS 2025, 72 % of improvement wishes were about convenience: entering the primary password less often, and easier import. CCS 2019 participants turned off lock-on-exit ("it is annoying to constantly have to be unlocking the app when I'm going back and forth") and clipboard clearing [E].

**Change master password.**
1. **Current master password.** Or `TextButton` "I don't remember it" → §5.24.
2. The new one (§5.3), written down (§5.4) and typed again (§5.5), then "Saving…".
3. **"Enter your PIN to keep using it."** The vault key changes, so the PIN must be confirmed to keep working [E: V-C5, V-S5; lock design 2 §9.7]. If there is no PIN, this step is "Choose a PIN". "Not now" leaves the app opening with the master password until a PIN is set.
4. **"Touch the sensor to keep fingerprint unlock on."** Only when it was on.
5. **"Make a new backup now. Your old backups still open only with your old master password."** [Back up now] [Later] [E: crypto.md §11].
   - If the change was started from the security check's card 1 (§5.16), the old password may be known to others. This screen then also lists the places where this app saved backups, with "Delete" where the place allows it, and says "Also delete copies you sent to Telegram or by email."

**Change PIN.**
- The flow: current PIN (or the master password) → new PIN → confirm.
- Whether a fingerprint touch may stand in for the current PIN is the lock design's decision; lock design 1 requires the master password for any change that adds a way in.
- After "Forgot PIN?" the app goes straight to "Choose a new PIN" instead (§5.9).

### 5.19 Make a backup

1. **Explanation** (first time only): "A backup is a locked copy of all your passwords. It opens only with your master password, on any phone."
2. **Master password, only when needed.**
   - **Proposed:** if the master password was typed correctly in the last 14 days (at an unlock, practice, backup, export or change), this step is skipped. The backup is then a copy of the locked file that was opened and checked in this session, so it still opens only with the current master password. This is what storage.md §5.4 describes, and what Aegis does for exports and automatic backups [E: crypto_code.md:10-12].
   - **Otherwise** the screen says: "Type your master password. This checks that you still know the password this backup will need." It counts as practice.
   - **The crypto owner must accept this** (crypto.md §10–11 gives each backup a new key, which needs the typed password). **Until then the step is always shown.**
   - Either way, no backup is made while the user has said they forgot the master password and has not yet chosen a new one (§5.24). Such a backup would need a password nobody remembers.
   - **Why.** The paper is kept at home, and typing a 32–35-character passphrase for every backup makes backups rarer. Yet backups are the only protection against a lost phone.
3. **File picker** (`CreateDocument`, suggested name `PasswordVault-backup-2026-10-06.pvault`). Google Drive appears in the picker when its app is installed. Time in the picker counts as time in the app (§4).
4. **"Saving…" then "Checking the saved file…"** The file is read back and opened before success is shown [E: storage.md §5.4; V-S6].
5. **Result**: "Backup saved: PasswordVault-backup-2026-10-06.pvault (52 accounts)." The next line depends on the place, recognised by the document's provider:
   - **Phone storage** (the Downloads provider, or the main storage root of the external-storage provider): "This copy is on this phone. If the phone is lost, this copy is lost too. Keep a copy somewhere else." `Button` "Send to another app…".
   - **Google Drive** (Drive's document provider [I: check its authority on a device]): "Saved to Google Drive. It is uploaded when the phone is online."
   - **Anywhere else** (SD card, USB drive, other apps): "Saved to <the place's name>." `OutlinedButton` "Send to another app…".
6. **"Send to another app…"** (encrypted backup only) opens the share sheet with the file through a `FileProvider` (Telegram Saved Messages, email to yourself, Drive).
   - Text: "The file is locked with your master password. Keep your master password strong."
   - The shared copy is encrypted and is kept until the next app start, so a receiving app can still read it after this app has locked.

Errors:
- storage full;
- the picker cancelled (no message; nothing was created);
- provider write failure: "Couldn't save to this place. Try another folder." The empty file is removed (§4);
- read-back check failed: "The saved file could not be checked. Try again or choose another place." The file is removed.

Some providers change the extension. Import recognises the file by its first bytes (`PVAULT01`) [E: crypto.md §1].

### 5.20 Export

Exact file layouts are in formats.md. This section covers what the user sees.

**Choose a format** (a radio list with one line each):

| Format | Line shown to the user | Content |
|---|---|---|
| Backup file (recommended) | "Locked with your master password. To keep a safe copy or move to a new phone." | §5.19 |
| Excel (.xlsx) | "To see your passwords in a table on a computer. Not protected." | §5.20.1 |
| CSV | "To move your passwords to another password app, such as Google Password Manager. Not protected." | §5.20.2 |
| JSON | "Complete data, including previous passwords. Bitwarden, Proton Pass and KeePassXC can import it. Not protected." | §5.20.3 |

**Warning screen** for the three plain formats (full screen, not a dialog):
- Title "This file will not be protected". Body: "Anyone who opens this file can read all your passwords. It has no password."
- Bullets: "Save it only where you control it: your own computer or USB stick." · "Don't send it by Telegram, email or other messengers." · "Delete it as soon as you're done."
- Per-format lines (formats.md §2):
  - CSV: "Don't open this file in Excel: Excel changes phone numbers and codes and can run formulas. Choose Excel export instead."
  - Excel: "Google Sheets and other apps may keep a copy in their cloud."
  - Android 7–10: "On this phone other apps with storage access can read files in Downloads."
- `Checkbox` "I understand" (required), then `Button` "Continue" leads through:
  - the master password, always typed now (Bitwarden also asks for it [E: `export_vault_master_password_description`]);
  - the file picker (`CreateDocument`; time there counts as time in the app, §4);
  - "Saving…", then the read-back check.
- Plain exports are **not offered to the share sheet**; the user can still save and move them [I].
- **During a pending change** the exported password is the new, unconfirmed one. formats.md decides how the old one is shown (§14).

**After export**:
- The screen says "Saved: passwords-2026-10-06.xlsx. When you're done with it, delete it."
  - `Button` "Done" (primary).
  - `OutlinedButton` "Delete it now" (`DocumentsContract.deleteDocument`; possible when the provider reports `FLAG_SUPPORTS_DELETE` [V: Android "documents and files" guide]).

  The primary button is "Done" because the user just asked for this file.
- **The banner.** The app takes a persistable permission on the file and stores its location inside the vault. Until the user deletes the file or chooses "I deleted it myself", a banner on Passwords says "You have an unprotected file with your passwords: passwords-2026-10-06.xlsx (saved 2 hours ago)." Buttons "Delete" and "I deleted it myself".
- If deletion is not supported: "This place doesn't let apps delete files. Delete it yourself in your Files app."

#### 5.20.1 Excel (.xlsx)

- **Layout**: formats.md §6.
  - One sheet, "Passwords" (localised). Columns: Name · Username · Password · Website · Other websites · Notes · Extra fields · Favourite · Added · Last changed · Password changed.
  - Every cell is a **shared string with text number format** (`@`), so nothing is evaluated or converted. Small readers skip inline strings, so shared strings replace the inline strings of the first version of this document.
  - The header row is bold and frozen; dates are text in the phone's time zone. No password history.
- **Why not CSV for Excel**: opened as CSV, a spreadsheet changed these values [X: LibreOffice 24.2 CSV import with default options]:
  - `+998901234567` became 998901234567;
  - `0123` became 123;
  - a 19-digit value became 1.23456789012346E+18;
  - `1e5` became 100000;
  - `=1+2` was computed as 3.

  Microsoft documents the same leading-zero, scientific-notation and 15-digit behaviour for Excel [V]. Phone numbers are common logins in Uzbekistan, so this matters [I].
- **Why not legacy `.xls`**: `.xlsx` opens in every Excel since 2007, Google Sheets, LibreOffice and WPS, and writing the old binary format is costly [I].
- Excel itself, Google Sheets and WPS Office still need a test (§12, §14).

#### 5.20.2 CSV (for other password apps)

- **Layout**: formats.md §4.
  - Chrome's header and order `name,url,username,password,note` [E: Chromium `password_csv_writer.cc`].
  - One row per website, as Chrome itself exports.
  - Extra fields go in a marked block at the end of the note (`--- PasswordVault ---`, one line per field), which this app reads back.
  - UTF-8 without BOM; rows end in CRLF.
- **The account's name.** Google ignores the `name` column and shows its own site name [E: V-A6; formats.md §4.2]. So when an account's name differs from its website's host, the block starts with a line `Title: <name>`. The formats owner adds this, and the importer turns the line back into the title. "Title" rather than "Name" avoids a clash with a user's own field called "Name".
- **What still does not travel**: the name is visible in Google only inside the note. Favourite, dates and history are not in CSV (JSON has them).
- **What Google will refuse.**
  - Chromium's importer rejects a row with no password, no valid website, a password, username or note longer than 1,000 characters, or a website longer than 2,048. It takes at most 3,000 rows and 1,000 KB per file [V: Chromium `password_importer.cc`, `password_manager_constants.h`; Google help gives the same 3,000].
  - Google Password Manager on Android runs closed code, so these limits need one real test (§14).
  - Before saving, the export shows a summary card: "Google Password Manager will skip 12 accounts without a website, 2 without a password and 1 with notes over 1,000 characters. They are still in the file; other apps may accept them." with "See which".
  - Above 3,000 rows or 1,000 KB, formats.md writes `-part1`, `-part2`, … (one "Save as" each) and the card says so.

#### 5.20.3 JSON

Bitwarden's layout plus a `passwordVault` object per item (formats.md §5). Bitwarden, Proton Pass and KeePassXC import it, and this app reads it back without loss: history, flags, dates and Android links included. It replaces the app-specific schema of the first version of this document.

### 5.21 Import

**Sources screen**: "Backup file from this app" · "Google Password Manager or Chrome (CSV)" · "Another password app or a spreadsheet saved as CSV" · "JSON (this app or Bitwarden)". Each opens the file picker (any type). The content decides the reader (formats.md §8.2):
- `PVAULT01` → backup;
- `PK\x03\x04` or `D0 CF 11 E0` → "This is an Excel file. In Excel choose File › Save As › CSV UTF-8, then import that file.";
- a KeePass database → "This is a KeePass database. In KeePassXC: Database › Export › CSV.";
- `{` → JSON;
- other text → CSV.

Time in the picker counts as time in the app (§4).

**Google Password Manager help** (shown before the picker):
- "In Chrome on this phone: ⋮ › Settings › (Autofill and passwords ›) Google Password Manager › Settings › Export passwords › Download file. The names may differ a little. Then come back and choose the file."
- `TextButton` "I can't find it" → "Open your phone's Settings app and search for "Password Manager". Open Google Password Manager › Settings › Export passwords."
- Sources: Google's own help pages label the step "Export passwords & passkeys" or "Export passwords", and only one includes "Autofill and passwords" [E: V-A6, account-based.md §4]. The Settings route needs a check on the user's phone [I].

**CSV reading** (formats.md §8.2–8.3):
- **Text decoding.**
  - Text that is valid UTF-8 is read as UTF-8, with or without a byte-order mark. Chrome writes UTF-8 without a BOM.
  - A UTF-16 byte-order mark means UTF-16.
  - Only anything else is read as Windows-1251, which Russian-language Excel writes [I].
- The delimiter is detected among `,`, `;` and tab.
- Header aliases cover Chrome, Bitwarden, KeePassXC and Firefox, plus Russian and Uzbek headers.
- A column-mapping screen opens only when a needed column is missing or ambiguous. Each column is shown with its first value and an `ExposedDropdownMenuBox`: Name · Website · Username · Password · Notes · Skip.
- Limits: 10 MB and 10,000 rows per file.

**Preview** (before anything is saved):
- **Sample**: the names and usernames (never the passwords) of the first three accounts, preferring ones with non-Latin letters, so garbled text is visible. Below them, "Text looks wrong? Read as UTF-8".
- **Counts**:
  - "Found 48 accounts."
  - "3 are already here (same website, username and password) and will be skipped."
  - "2 have the same website and username but a different password; they will be added so you can compare."
  - "1 row has no password."
  - "1 row was skipped: password longer than 1,000 characters."
  - "1 note was longer than 10,000 characters and was shortened."
- **Exact repeats.** Rows that repeat exactly (same website, username and password) are imported once.
- **Same login on related websites** [E: critique M6; V-A6]. Chrome writes one row per affiliated domain, so one login can arrive as several rows. Rows with the same username and password are kept as **one account with all its websites** only when:
  - (a) they are adjacent rows equal except the website (formats.md §8.3);
  - (b) one is an `android://` row and the other an `https://` row, and the app's package matches the site (com.instagram.android ↔ instagram.com);
  - (c) both sites are in one fixed sign-on family: Google (google.com, youtube.com, gmail.com), Microsoft (live.com, outlook.com, hotmail.com, microsoft.com), Yandex (yandex.ru, yandex.uz, yandex.com, ya.ru), Apple (apple.com, icloud.com).

  Nothing else is joined: Facebook and Instagram stay apart, because that kind of sharing is the reuse this app exists to fix. The preview says "6 rows were the same login on related websites and were kept together as 2 accounts." with `TextButton` "Keep them separate".
- **Tidy names** (`Switch`, on). Chrome makes up names such as "accounts.google.com" [E: V-A6].
  - The rule: strip "www.", "m.", "mobile.", "accounts.", "account.", "login." and "passport."; then drop ".com", ".ru", ".uz", ".org" or ".net" when one word is left, and capitalise that word.
  - Examples: accounts.google.com → Google, m.facebook.com → Facebook, passport.yandex.ru → Yandex. my.gov.uz stays my.gov.uz.
  - Two examples from the file are shown before and after.
- `Button` "Import 45 accounts".

**Result**: "45 accounts imported." `TextButton` "Undo this import" removes exactly those entries; it is available until the screen is left. Then:
- **For CSV and JSON**: "The file Chrome Passwords.csv still contains all these passwords unprotected. Delete it now?" `Button` "Delete file" (when supported) and "Keep".
- **If many imported passwords are shared**: "Many of these accounts share a password. Open Security to fix them one by one." `TextButton` "Open Security".
- **Master-password check.** Imported passwords are compared with the master password the next time it is typed (§5.10). Practice on day 1 usually comes soon after setup.

**Backup file into an existing vault**: the user types that backup's master password. Entries with new ids are added; identical entries are skipped; an entry with the same id but different content is added as "Gmail (from backup)". Nothing is overwritten [I].

**Build order** (until the user says where their passwords are today, §14 question 3):
1. our backup;
2. Google/Chrome CSV;
3. our JSON;
4. Bitwarden and KeePassXC files and any CSV with named columns;
5. last, the column-mapping screen.

Errors:
- unreadable file;
- no usable columns ("We couldn't find columns for website, username and password. Choose them yourself.");
- wrong backup password;
- damaged file;
- newer format version.

Evidence for priority: KeePassDX still lacks CSV import (#505, open since 2020) [E], and the only setup problem novices reported was importing [E: SOUPS 2025].

### 5.22 Help pages (Settings › Help)

Short pages of plain text (the text is in §7.6):
- "How your passwords are protected";
- "If you lose your phone or move to a new one";
- "If you forget your master password";
- "If you forget your PIN";
- "Open-source licences" (EFF wordlist CC BY, zxcvbn4j MIT, the dictionary sources, the Argon2 library, fonts);
- "Version".

### 5.23 Recently deleted

- **Delete** (from the detail overflow, a long press or a TalkBack action) sets `deletedAt` and hides the account at once. The snackbar says "Gmail deleted" with "Undo" (about 10 s). There is no confirmation dialog.
- **Finding it.** "Recently deleted (3)" is the last row of the Passwords list, shown only when not empty.
  - Its screen lists the accounts with the date each was deleted and `TextButton` "Restore".
  - Each row's overflow has "Delete forever", with the dialog "Delete Gmail forever? This can't be undone." (Delete · Cancel).
  - The top-bar action "Empty" opens a dialog with the count.
- **Where deleted accounts are.** They are left out of search, the list, the security check and plain exports. They stay inside the locked file, and in backups made meanwhile, until 30 days have passed; then they are removed at the next unlock.
- **Reason.** A lock within the snackbar's 10 seconds dropped the only undo, and the older copies the storage keeps are not reachable from the screens. Bitwarden and Proton Pass keep a trash for the same reason [E: critique m9].

### 5.24 Set a new master password (the old one is forgotten)

This is for a user who has forgotten the master password while the PIN or fingerprint still opens the app. Without this route, every backup becomes unopenable, and a later PIN lockout or a lost phone loses everything. Both lock designs provide the route. They differ only in a waiting time: none in lock design 2; 72 hours in lock design 1, measured so that changing the date does not shorten it.

**Entry points**:
- the "Forgot master password?" sheet (§5.10, the first option);
- the hint after 2 wrong master-password tries (§5.10);
- Settings › App lock › Change master password › "I don't remember it".

**Steps**:
1. **Explanation**: "You can choose a new master password because your PIN still opens the app on this phone. Backups you made before will still need your old master password." `Button` "Continue", `TextButton` "Cancel".
2. **Confirm** with the PIN typed now, or the fingerprint if the lock design allows it. If the app was locked, this also unlocks it.
3. **If the lock design has a waiting time**:
   - The screen says "For your safety, you can choose the new master password after 9 October, 14:30. Until then your passwords open with your PIN as usual."
   - A banner on every unlock says "A new master password was requested on 6 Oct. You can choose it after 9 Oct. Not you?" with `TextButton` "Cancel request". Cancelling needs nothing, because it is the safe direction.
   - When the time comes, the banner becomes "Choose your new master password now." `Button` "Choose".
4. **Choose it**: §5.3, §5.4 and §5.5, then "Saving…".
   - The user may stop with "Later" before §5.3 starts. The banner then says: "Choose a new master password. Until you do, you can't make backups."
   - Practice prompts are paused until a new master password exists.
5. **Keep the other ways in.**
   - The PIN was just typed, so it is kept without asking again (lock design 2 §9.8).
   - When fingerprint was on: "Touch the sensor to keep fingerprint unlock on."
6. **Back up**: "Make a new backup now. Backups you made before still need your old master password; keep them in case you remember it." [Back up now] [Later].

**Recommendation on the waiting time** (lock design and security owner): from the user's side, a short wait is better. During the wait no usable backup can be made, because the old master password is forgotten. Up to 24 hours is easy to accept, as long as the PIN keeps working meanwhile. The security trade-off (someone who saw the PIN could take over) belongs to the lock design.

## 6. The copy flow

**Where**: the list row button (password), the detail screen (each field), the long-press menu, the guided change, previous passwords, extra fields, and text selected in notes.

**What happens on a copy** [E: platform.md §3.2, V-P2; V: Android "Copy and paste" guide]:
1. **The clip.** `ClipData.newPlainText("", value)` with an empty label.
   - The `ClipDescription` extras carry `android.content.extra.IS_SENSITIVE = true` for passwords, previous passwords, secret field values and anything copied from notes, which often hold security answers. The constant exists from API 33; the string works on all versions. The extra is false for usernames, websites and non-secret fields, so the Android 13+ preview confirms the right one was copied.
   - The same extras (`setExtras`, API 24 [V]) carry **a random 128-bit token** that identifies this copy.
2. **The clear job.** Any earlier clear job is replaced, and a new one is scheduled for the chosen delay (default 1 minute). The mechanism (WorkManager or an alarm) is chosen after the clipboard lab reports. The job clears **only the app's own clip**. It reads the clip's description, never its content:
   - **Description readable, token differs or missing**: the user has copied something else since, so nothing is cleared.
   - **Description readable, token matches**: the clip is cleared (`clearPrimaryClip()` on API 28+; an empty clip below).
   - **Description not readable**: on Android 10+ a background app can neither read the clipboard nor learn that it changed [E: V-P2], so the clip is cleared anyway, as Proton fails closed [E: platform_code.md:34]. On Android 7–9 the check always works.

   When the app comes back to the front with a clear pending, it checks the same way. If the clipboard holds something else, it cancels the pending clear and hides the status row.
3. **Confirmation.** The row's icon becomes a check mark for 1.5 s, with `HapticFeedbackType.Confirm`.
4. **Feedback.** Android 13 and higher show their own confirmation, and Google "strongly recommend[s] removing toasts or snackbars shown after an in-app copy for Android 13 and higher" [V]. So:
   - **Android 13+**: no toast or snackbar. The **clipboard status row** (below) appears without a "copied" headline: "Password for Gmail · clears in 1:00". The system preview hides sensitive content.
   - **Android 7–12L**: the same row, with the headline "Password copied". This is the in-app feedback Google recommends for those versions [V].
5. **One helper.** All in-app copies go through one helper. A custom `LocalClipboard` is provided at the root (Compose UI 1.12 `Clipboard`, `setClipEntry`), so text selected and copied in notes is also flagged and timed [V: ui-android 1.12.1 bytecode] [I].

**Clipboard status row** (app-wide; part of the bottom-bar area, so the "Add" button moves above it):
- **Content**: a `Surface` (secondary container) with a key icon, the text "Password for Gmail · clears in 0:52" (or "Username for Gmail · …"), and `TextButton` "Clear now".
- **Announcements**: the countdown updates every second, visually only. TalkBack hears one polite live-region message when the row appears: "Password copied. It will be removed from the clipboard in 1 minute."
- **When the clear runs while the app is open**: "Clipboard cleared" for 2 s, then the row hides.
- **Late jobs**: a scheduled job may run late. If the planned time has passed and the clear has not run, the row says "Clearing…" instead of a negative countdown.

**When the clipboard is cleared**:
- when the timer ends, on "Clear now", and on "Lock now", each time only if the clip is still the app's own or cannot be checked;
- **never when the app goes to the background or locks automatically.** That would remove the password before the user can paste it, which is PocketVault's mistake [E: offline.md §6]. The timer bounds the exposure instead.

**This departs from platform.md §1 item 3 ("clear on lock"); the security owner must approve it (§14).**

**First-copy tip** (once; an `AlertDialog` with "OK"):
- The main text: "Now open the app or website, touch and hold the password box, and tap Paste. The password is removed from the clipboard after 1 minute. Anything else you copy in that minute may be removed too. Never pin a password in your keyboard's clipboard: pinned items are never removed."
- Reason: Gboard's pinned clips survive every clear, including Android's own one-hour clear [E: V-P2].
- **On Samsung phones** it adds: "Samsung phones keep a list of copied text. To keep passwords out of it, turn off or clear the clipboard history in Samsung Keyboard settings." A 2025 report says One UI's clipboard history stores copied text, including passwords, in plain text with no expiry [2: cyberpress.org, 2025-04-22]. Whether it honours the sensitive flag is unknown.
- **On other phones**: "Some keyboards keep their own list of copied text; you can turn it off in the keyboard's settings." [E: platform.md, Gboard help]

**Failures.**
- If copying throws (some vendor builds), the snackbar says "This phone blocked copying. Open the password with Show in big letters and type it."
- The system's own auto-clear after one hour on Android 13+ is a backstop, not the plan [E: platform.md §3.2].

**Speed targets.**
- Unlock (fingerprint) → copy from a favourite: 2 interactions.
- From search: tap search, type 2–4 letters, tap copy.
- The critique measured 4 taps from the launcher, which meets the target [E: critique task 4].

## 7. Onboarding and help text (plain English, B1 level)

Rules for all text:
- short sentences, one idea per sentence;
- "you" and "your";
- no technical words;
- numbers as digits;
- the same word for the same thing everywhere (glossary in i18n.md §3).

**7.1 What the master password is** — "Your master password is the key to all your other passwords. You need it if you forget your PIN, to open a backup, and to move to a new phone."

**7.2 Nobody can recover it** — "Nobody can reset it. We don't keep a copy, and there is no 'Forgot password' email. If you forget it, your passwords can't be opened on a new phone or from a backup. Write it on paper and keep the paper at home."

**7.3 Why the app asks for it now and then** — "We ask for your master password a few times in the first weeks and then every 2 weeks, so you don't forget it. If it's not a good moment, tap 'Not now' and use your PIN. You need it to open a backup and to move to a new phone."

**7.4 Why a backup matters** — "Your passwords are kept only on this phone. If the phone is lost, broken or reset, they are gone. Uninstalling the app or clearing its storage also deletes them. A backup is a locked file with all your passwords. It opens only with your master password. Keep it outside this phone — in Google Drive, on a computer, or send it to yourself."

**7.5 What the PIN is for** — "Use your PIN to open the app on this phone, like your bank app. It works only on this phone. Your master password works everywhere. Don't use your phone's unlock PIN or your bank card PIN."

**7.6 Help pages**
- *How your passwords are protected*:
  - "Your passwords are kept in a locked file. Only your master password can unlock it. The app never connects to the internet; it does not have permission to. Screenshots of the app are blocked. Passwords you copy are removed from the clipboard after 1 minute."
  - "What the app cannot protect you from: a phone that has been hacked or 'rooted', a harmful keyboard app, or an app you allowed to control your phone (accessibility)."
  - "Never install an app file (APK) sent to you in Telegram or offered by a website, even if it seems to come from someone you know. Never let an app control your phone (accessibility) unless you need it because of a disability."
  - "The app cannot tell you if a website leaked your password, because it does not use the internet."

  The local threat: Group-IB's Ajina.Banker campaign spread malicious APKs through Telegram channels, mostly aimed at users in Uzbekistan, and abused accessibility services [E: critique m11, group-ib.com/blog/ajina-malware]. "Install apps only from Google Play" was dropped, because this app itself may be installed from a file.
- *If you lose your phone or move to a new one*:
  - "Install Password Vault on your new phone, tap 'Restore from a backup file', choose your newest backup and type your master password. Without a backup, your passwords cannot be brought back."
  - "If you moved your apps with 'Copy apps and data', open Password Vault on the new phone and type your master password once. Then choose a PIN for the new phone."
- *If you forget your master password*:
  - "If your PIN or fingerprint still opens the app, open it and choose a new master password: Settings › App lock › Change master password › I don't remember it. Then make a new backup."
  - "If you can't open the app at all: try the words on your paper, or restore a backup whose master password you remember. You can also start with an empty app. Your old passwords stay on the phone, locked, in case you remember the password later."
- *If you forget your PIN*: "Tap 'Forgot PIN? Use master password', type your master password, and choose a new PIN."

## 8. Accessibility

- **TalkBack labels** (every icon button names its object):

| Element | Spoken |
|---|---|
| List row | "Gmail, aziz@gmail.com" + custom actions (Copy password, Copy username, Open website, Show in big letters, Add to favourites, Edit, Delete) |
| Row copy button | "Copy password for Gmail"; while a change is pending, "Copy password for Gmail, choose old or new" (opens the menu) |
| Masked password | "Password, hidden" (password semantics; length never spoken) |
| Revealed password | the value, with the hint "Hides after 30 seconds without a touch. For more time, use Show in big letters." |
| Show toggle | "Show password" / "Hide password" with state description |
| Website open | "Open mail.google.com in browser" |
| Generator icon | "Create a strong password" |
| Strength meter | "Password strength: weak" |
| Passphrase words (§5.3) | "Word 1 of 6: vowel" … (plain text, not buttons) |
| PIN dots | "PIN, 3 of 6 digits entered" (no digits spoken) |
| Keypad | "1" … "0", "Delete last digit", "Unlock with fingerprint" (keys marked sensitive, §3) |
| Choose a PIN screen | the body adds the headphones sentence when TalkBack is on (§5.6) |
| Security tab | "Security" or "Security, new results" |
| Clipboard row | one polite live region on appearance (not every second) |

- **Screen structure.** Every screen sets a pane title; section headers are headings; error text is linked to its field and announced.
- **Announcements.** `announceForAccessibility` is not used (deprecated in API 36 [V: api-versions.xml]); live regions are used instead.
- **Secret values** carry `isSensitiveData` semantics (API 34+, Compose UI 1.12 [V]). TalkBack, an accessibility tool, still reads them.
- **Touch targets**: at least 48 × 48 dp everywhere (`minimumInteractiveComponentSize`); keypad keys at least 64 dp; 8 dp between adjacent targets. The list's bottom padding keeps the Add button off the last row's copy button (§5.11).
- **Font scaling.**
  - Android 14+ scales text up to 200 %, non-linearly [V].
  - All text is in `sp`, with no fixed heights on text containers. Rows and buttons grow; top-bar titles may cut, but the full title appears in the content.
  - Every screen is tested at 200 % and with the largest display size.
- **Contrast.**
  - WCAG 2.2 AA: 4.5:1 for text; 3:1 for large text, icons, the strength meter and focus indicators. Material 3 colour roles meet this when used as pairs.
  - Dynamic colour on Android 12+, a fixed scheme below.
  - Colour is never the only signal (strength words, warning icons).
- **Motion and time.**
  - "Remove animations" is respected: no shake; text and haptics remain.
  - The re-hide timer restarts on every touch (§3).
  - Show in big letters keeps the screen on and stays open while touched, closing after 2 minutes without a touch (§5.13).
  - The other security timeouts are adjustable in Settings.
- **No gesture-only actions.** No swipe-to-delete; long-press actions are also in menus and TalkBack actions. A hardware keyboard and Switch Access reach everything, including the keypad.
- **Focus.**
  - After unlock, focus starts at the top bar; opening search focuses the field.
  - Dialogs return focus to their trigger.
  - A failed save moves focus to the first invalid field.
  - "Type it again" (§5.5) focuses its field.
- **Testing.** Test with TalkBack, Switch Access, Accessibility Scanner, 200 % font, dark theme and landscape, and test the keypad with `isSensitiveData` under TalkBack.

## 9. Localisation

i18n.md is the localisation plan: resource directories, the in-app language switch, the Uzbek alphabet decision, the four-language glossary, writing rules and tests. This section keeps only what the screens depend on.

- **Languages for version 1**: English (`values/`, default), Uzbek Latin (`values-uz/`), Uzbek Cyrillic (`values-b+uz+Cyrl/`), Russian (`values-ru/`) (i18n.md §0). The language dialog is "System language · Oʻzbekcha · Ўзбекча · English · Русский".
- **Uzbek spelling**:
  - oʻ and gʻ are written with U+02BB and the tutuq with U+02BC; no ö ğ ş ç yet (i18n.md §2.4).
  - Search accepts every variant (§5.11).
  - Master passwords must not depend on which variant a keyboard types (§5.3).
- **Strings**:
  - Everything is in string resources: no concatenation, positional placeholders (`%1$s`), translator comments on every string.
  - Plurals use `<plurals>` and `pluralStringResource`. Russian has one/few/many/other ("1 аккаунт", "3 аккаунта", "5 аккаунтов") [V: CLDR]. Never "account(s)".
- **New terms for the glossary** (i18n.md §3), from this revision: "Show in big letters", "Leave out of the security check", "Recently deleted", "Start with an empty app", "Open my previous passwords", "Forgot PIN? Use master password" (glossary terms 60 and 50), "App lock" for the Settings group (term 69), and "tries left" (simpler than term 58's "attempts left").
- **Per-app language**:
  - `generateLocaleConfig = true` for the Android 13+ system setting;
  - `AppCompatDelegate.setApplicationLocales` for Android 7–12, which requires the activity to extend `AppCompatActivity` (it is also the `FragmentActivity` the biometric library needs) and the `autoStoreLocales` service entry [V: Android "per-app language" guide; i18n.md §1.4].
- **Formats**: dates, times, relative times ("3 days ago") and numbers go through `android.icu` (`DateFormat`, `RelativeDateTimeFormatter`, available from API 24 [V]), with the app's language passed explicitly. Sorting uses `android.icu.text.Collator` and `AlphabeticIndex` (§5.11).
- **Space**: design for +40 % text length; prefer full-width buttons in onboarding; allow two-line labels; test with the `en-XA` pseudo-locale (measurements in i18n.md §4.5).
- **Right-to-left**: there is no RTL language in version 1, but `supportsRtl="true"`, start/end everywhere, auto-mirrored icons and the `ar-XB` pseudo-locale in testing. Secrets, usernames, websites and PIN dots stay left-to-right (§3).
- **Do not translate**: Google Password Manager, Chrome, Excel, CSV, JSON, Telegram, file names, and the CSV block's marker lines (formats.md §4.4).
- **Review**: translations of warnings must be reviewed by a native speaker (§14).

## 10. Material 3 components

material3 1.4.0 from Compose BOM 2026.09.00 [V: Maven]. Components marked *exp* carry `@ExperimentalMaterial3Api` in 1.4.0 [V: bytecode of the 1.4.0 AAR]; opting in is acceptable with the BOM pinned.

| Screen | Components |
|---|---|
| Shell | `Scaffold`, `NavigationSuiteScaffold` (`NavigationBar` / `NavigationRail`, `Badge` as a dot), `SnackbarHost`, `Surface` (clipboard row) |
| Welcome, setup, help | `Button`, `OutlinedButton`, `TextButton`, `OutlinedCard`, `Checkbox`, `Switch`, `CircularProgressIndicator`, `AlertDialog` (button-slot version, stable); passphrase words as plain `Text` |
| Lock | Custom keypad of `FilledTonalButton`/`Surface` keys, `IconButton`, `TextButton`, `OutlinedSecureTextField`, `ModalBottomSheet` *exp* ("Forgot master password?"), `AlertDialog` (notices after unlock) |
| Passwords | `TopAppBar` (classic overload, stable), `SearchBarDefaults.InputField` *exp* (in-place filter), `LazyColumn` + `ListItem`, sticky headers, `ExtendedFloatingActionButton`, `IconButton`, `DropdownMenu`, `OutlinedCard` banners (up to two) |
| Detail, Recently deleted | `TopAppBar`, `ListItem`, `IconToggleButton`, `FilledTonalIconButton`, `TextButton`, `AlertDialog`, `TooltipBox`/`PlainTooltip` *exp* (icon tooltips) |
| Add/edit | `OutlinedTextField`, `OutlinedSecureTextField`, `IconButton`, `TextButton`, `Switch`, `SuggestionChip`/`FilterChip` (field labels), `AlertDialog` |
| Generator | `ModalBottomSheet` *exp*, `SingleChoiceSegmentedButtonRow` + `SegmentedButton`, `Slider` (value overload, stable), `IconButton`, `Switch`, `Button` |
| Security | `ElevatedCard`, `LinearProgressIndicator`, `ListItem`, `TextButton`, `OutlinedCard` (steps), `Badge` |
| Settings, import, export | `ListItem`, `RadioButton`, `Switch`, `ExposedDropdownMenuBox` *exp* (column mapping), `Checkbox`, `LinearProgressIndicator` |

Icons are Material Symbols imported as vector drawables; `material-icons-extended` is frozen at 1.7.8 in this BOM [V: BOM POM]. Haptics: `HapticFeedbackType.Confirm`, `Reject`, `VirtualKey` [V].

## 11. Left out of version 1

| Left out | Reason |
|---|---|
| Autofill service, Credential Manager provider, passkeys | Decided: this is the most attack-exposed code, and its safety rests on verification that needs a network [E: platform.md §3.11]. The copy flow replaces it |
| Sync, account, cloud | Decided: local only, no INTERNET permission |
| Leaked-password (breach) check, website icons | Need the internet |
| Tags and folders | §2; search and favourites suffice |
| Recently used | §2; it would rewrite the vault on every copy and keep a usage trail |
| Cards, identities, secure-note types | One flexible entry with extra fields covers them; type-specific screens come later |
| One-time codes (TOTP) | A second factor in the same vault weakens it; separate apps (Aegis) exist |
| Attachments | The single-file vault is not built for them [E: storage.md §1] |
| Combining accounts in the security check | Replaced by exact rules at import (§5.16, §5.21) |
| Excel import, legacy `.xls`, KDBX export, PDF or print | Spreadsheets can save CSV for import; KDBX via kotpass is untested on a device [E: offline.md §4]; printing leaves copies in the print spooler [I] |
| Password-protected Excel | formats.md §7 and §11 propose it for version 1 if real Excel opens its sample before release, otherwise 1.1; its screen (a generated file password with Copy) is specified there |
| Credential Transfer (import from Google Password Manager without a file) | Planned. `androidx.credentials:providerevents` is at 1.0.0-beta01 and works on Android 8+ [E: V-A7]. It removes the unprotected CSV entirely. First check that an app that is not a credential provider may import: Android's guide names the importer "a credential provider or setup wizard" [E: critique m4] |
| "Never" for clipboard clearing; "allow screenshots" | Keep a safe floor |
| Notifications, widgets, quick-settings tile, app shortcuts | No need; they could show data outside the lock |
| Restoring older automatic copies from the UI | The storage keeps them for automatic recovery; Recently deleted, history and import undo cover common mistakes |
| "Similar password" detection (Tashkent2020 vs Tashkent2021) | Useful for this user, but false positives need design; next version |
| Uzbek or Russian passphrase wordlists | No vetted list found; English short words in version 1 |
| Two-pane tablet layout, Wear OS | Phones first |
| Bulk select and delete | Not needed for this user |

## 12. Acceptance checks

1. **Copying.** Copying from a list row takes one tap. On Android 13+ no toast or snackbar appears and the system preview hides the password; on Android 7–12L the status row says "Password copied".
2. **Clearing.** With the app in the background, the clipboard is empty about 1 minute after the copy. Test on Android 7, 9, 13 and 17, and on one Samsung and one Xiaomi phone if available.
   - Leaving the app or an automatic lock never clears it early.
   - A text copied after the password is not cleared on Android 7–9 (token check), nor on any version when the app is in front at clear time.
3. **Returning.**
   - Returning within 30 s shows the last screen with secrets masked.
   - Returning later shows the lock screen, and so does returning after the screen turned off while the app was open. When the screen turned off while the app was in the background, this holds on Android 15+ via the last phone unlock, and is best effort before.
   - Back on the lock screen leaves the app. Nothing of the vault is ever visible behind it or in Recents.
4. **Draft.** Generating a password in "New account", leaving for the browser and coming back after the lock reopens the form with that password and "Your unsaved changes were kept". No entry is saved until Save, and Discard leaves no entry and no draft.
5. **Guided change.** After 7 taps on "Create another password", "No, put back the old password" restores the original password. `changePending` and `pendingOldPassword` survive a lock and a process kill.
6. **Excel.** The row set of formats.md §9, exported to `.xlsx`, reads back identical in Excel, Google Sheets, LibreOffice and WPS Office.
7. **CSV.** The CSV export imports into Google Password Manager on Android and into Chrome. The rows it skips match the summary card, and an account whose name differs from its host shows the "Title:" line in Google.
8. **Search and plurals.** The search test vectors of §5.11 pass (search.md's tests plus the two additions). Russian plurals are right for 1, 2, 5, 11, 21, 22 and 25.
9. **Accessibility.** Every screen works at 200 % font, with TalkBack and with Switch Access; no text is cut except where this document allows it. The last list row's copy button is reachable above the Add button.
10. **Performance.** With 5,000 entries, search updates within 50 ms per keystroke, and the list scrolls without dropped frames on a mid-range phone.
11. **Plain exports.** They ask for the master password. The "unprotected file" banner stays until the file is deleted or the user confirms they deleted it, and "Done" is the primary button after export.
12. **System screens.** A backup to Google Drive with 90 seconds spent in the picker succeeds.
13. **Kill in the picker.** With the app killed while the picker is open, the backup either completes after the next unlock or its empty file is deleted with "No backup was made".
14. **"Immediately".** With the lock set to "Immediately", backup, export and import all complete.
15. **Forgotten master password.** With a working PIN, a user who forgot the master password can set a new one (§5.24) and is offered a new backup.
16. **No deletion from the lock screen.** No path that starts at the lock screen deletes a vault file. After "Start with an empty app", the old passwords open again with their master password from Welcome and from Settings.
17. **Lock-screen states.** Every state of §5.9 and §5.10 can be produced in a debug build and shows its text. A temporary Keystore error never turns fingerprint or PIN unlock off.
18. **Strength rules.** Toshkent2020, Tashkent2020, Ташкент2020, Mustaqillik1991, Samarqand2024!, Oʻzbekiston2024, Shahzoda2003! and "Assalomu alaykum" are refused as master passwords and score 2 or less (so they appear under "Easy to guess"). The suggested passphrase scores 4.
19. **Practice.** "Not now: use PIN" works throughout the grace period. After it, the master password is required, but "Forgot master password?" still offers the PIN route.
20. **Recently deleted.** A deleted account can be restored after a lock and after a restart, and it is gone 30 days later.

## 13. Evidence and checks made today

Evidence file and research (summaries):
- **Bitwarden 2026.9.x code**: clipboard sensitive flag, toast only on Android 12L and lower, WorkManager clear, default never; timeout default 15 min; generator 14/3; search by name, subtitle and URIs; export warnings.
- **KeePassDX 4.5.5**: clipboard 20 s, password copy off by default, timeout 5 min, generator 14/8, LTR protected fields; issues #2321, #2488, #505.
- **Proton Pass 1.41.x**: clipboard 60 s, lock 2 min, generator words 5, search AND with accent removal, reused = same non-empty password.
- **Keyguard r20260916**: clipboard never, lock 5 min, app password after 14 days, generator 16/5, large type, search rules.
- **Chromium**: the CSV writer and importer.
- **Help pages**: Google and Bitwarden.
- **Studies**: SOUPS 2025 (Cabarcos and Mayer, n = 37); CCS 2019 (Seiler-Hwang et al., 4 apps × 20 users); USENIX Security 2021 (Ray et al., n = 26 older adults).

Checked for the first version:
- Android "Copy and paste" guide: the Android 13 confirmation, the recommendation to remove toasts or snackbars on 13+, and the sensitive flag [V].
- API 37 `api-versions.xml`: `EXTRA_IS_SENSITIVE` 33, `clearPrimaryClip` 28, `announceForAccessibility` deprecated in 36, `android.icu` Collator and RelativeDateTimeFormatter 24, `Transliterator` 29, `deleteDocument` 19 [V].
- Android 16 behaviour changes (predictive back, large screens, edge-to-edge); Android 14 font scaling to 200 %; the per-app language guide; the documents-and-files guide (delete, persistable grants, no permissions, no overwrite) [V].
- Google Maven: BOM 2026.09.00 → material3 1.4.0, navigation-suite 1.4.0, ui/foundation 1.12.1, icons 1.7.8; Navigation 3 1.2.0; bytecode of material3 1.4.0, navigation-suite 1.4.0, ui 1.12.1 and foundation 1.12.1 [V].
- CLDR plural rules for en, uz, ru; Microsoft Excel limits and leading-zero behaviour; the Uzbek alphabet (Wikipedia, Gazeta.uz 2026-09-10, Times of Central Asia); EFF short wordlist 1 statistics; zxcvbn4j 1.9.0 on Maven Central; Bitwarden web's weak-password threshold (score ≤ 2) [V].
- Chromium HEAD: no BOM handling in the CSV importer; 1,000 KB file limit; 3,000 rows per file; password, username and note at most 1,000 characters, URL at most 2,048; rows without a password or valid URL rejected [V]. AOSP `values-uz` strings (Latin script, mixed U+2018 and U+02BB) [V].
- Experiment: a hand-written `.xlsx` round trip and CSV-in-spreadsheet conversion with LibreOffice (`scratchpad/design/ux/xlsx_probe.py`, `lo_out/`) [X].
- Secondary: the Samsung One UI clipboard-history report (cyberpress.org, 2025-04-22) [2].

Checked for this revision:
- **zxcvbn4j 1.9.0** (jar SHA-1 `47e0b800…` matches Maven Central's): the critic's two test programs, re-run with JDK 25 in `scratchpad/design/ux/zx/`, give the same scores (§5.3) [X].
- **ICU4J 78.1 and 56.1**: the sort order and `AlphabeticIndex` buckets for uz, ru and en on 17 sample titles, in `scratchpad/design/ux/icu/` (§5.11) [X].
- **Aegis 3.4.3, `ui/AuthActivity.java:180-193, 211-224`**: when the password reminder is due, the password field is focused and the fingerprint prompt does not open by itself, but the fingerprint button still unlocks after one dialog [V].
- **Android "Copy and paste" guide**: no toast "when your app … retrieves metadata for the clip object, such as by calling `getPrimaryClipDescription()`", nor for the app's own clip [V].
- **API levels** (API 37 `api-versions.xml`) [V]:
  - `BiometricManager.getStrings` 31, `getLastAuthenticationTime` 35;
  - `AlphabeticIndex` 24;
  - `ClipDescription.setExtras`/`getExtras` 24, `getPrimaryClipDescription` 11;
  - `PowerManager.isInteractive` 20;
  - `Window.setHideOverlayWindows` and `HIDE_OVERLAY_WINDOWS` 31;
  - `takePersistableUriPermission` and `deleteDocument` 19;
  - `Settings.Global.BOOT_COUNT` 24.
- **Read today**:
  - VERIFICATION.md items V-P1–V-P6, V-C2, V-C5, V-C6, V-C8, V-C11, V-C13, V-S3–V-S6, V-A6, V-A7;
  - platform.md §1, §3.2–3.7 and §4;
  - crypto.md §7 and §10–12;
  - storage.md §5.4;
  - account-based verification notes (Chrome's `name` column; the export labels);
  - crypto verification notes (Aegis exports reuse the vault key);
  - the lock fact sheet §0, §6, §8 and §9;
  - both lock designs;
  - formats.md, i18n.md and search.md.

Not verified:
- the clipboard lab's results (clearing from the background);
- Excel, Sheets and WPS opening the `.xlsx`;
- Google Password Manager on Android importing our CSV;
- Samsung keyboard behaviour with the sensitive flag;
- Google Drive's document-provider authority;
- the "search Settings for Password Manager" route;
- how Gboard and Samsung Keyboard type spaces in a password field.

## 14. Open questions

1. **User.** Did "home lock like banking account" mean the app's own PIN (this design) or the phone's screen lock? Ask with two pictures: "Your phone's lock screen" and "The app's own PIN, like your bank app". The lock screen stays modular either way, and both lock designs describe how the phone's lock would work (lock design 1 §7, lock design 2 §7).
2. **User.** Where are your passwords today (Google Password Manager, Chrome, Samsung Pass, Yandex Browser, an Excel file)? This sets the import build order (§5.21).
3. **Translations.** Who reviews the Uzbek and Russian translations of the warnings? (The alphabet question is settled for now in i18n.md §2.4.)
4. **Lock design.**
   - PIN length and attempts.
   - Fingerprint after the PIN is turned off: recommended to keep it (§5.9).
   - Practice grace: about 7 days recommended (§5.10).
   - The waiting time before a new master password can be set with the PIN: none, or up to 24 hours, recommended (§5.24).
   - Whether a fingerprint touch may approve "Change PIN".
5. **Clipboard lab.** Does clearing from the background work on Android 7–17 and vendor builds? If it is unreliable, the status row must say so.
6. **Crypto owner.**
   - (a) 6 words from the EFF short list (62 bits) instead of 5 long-list words (§5.3).
   - (b) Spaces as the separator, with runs of spaces in the master password folded (§5.3).
   - (c) Folding apostrophe-like characters in the master password before NFC (§5.3).
   - (d) Score 4 with the Uzbek and Russian dictionary for own master passwords (§5.3).
   - (e) Backups without retyping when the master password was typed in the last 14 days (§5.19).
   - (f) Restating crypto.md §11 step 6 as the practice rule of §5.10.
7. **Lock delay.** Is 30 s the right default, against "Immediately" (closer to a bank app, but every copy round trip then needs a new unlock)?
8. **Storage owner.**
   - The `draft` object in the vault body (§5.14).
   - Archiving instead of deleting, with archives never pruned (§5.10).
   - `pendingOldPassword` and `deletedAt` (§2).
   - A backup as a copy of the verified generation (§5.19).
   - The data behind the rollback notice (§5.9, V-C8).
9. **Security owner.**
   - The per-entry "Leave out of the security check".
   - The master-password reuse check at each master-password entry and once at Save (§5.10, §5.14).
   - Not clearing the clipboard on automatic lock, a deviation from platform.md §1 item 3 (§6).
   - Show in big letters as an accepted exception to "render secrets briefly" (§5.13).
   - The 10-minute grace for system screens the app opens (§4).
10. **Formats owner.**
    - The "Title:" line in the CSV block (§5.20.2).
    - The related-website rules at import (§5.21). formats.md §8.4's "the security check later offers to combine" no longer applies (§5.16).
    - `pendingOldPassword` and `deletedAt` in JSON.
    - What a plain export writes during a pending change.
    - Leaving deleted accounts out of exports.
    - Notes cut on import instead of skipping the account (formats.md §8.4, adopted in §5.21).
11. **Search and i18n owners.** In search.md: ş → s and ç → c in the second tier, and the wrong-layout retry (§5.11). In i18n.md: the new glossary terms (§9).
12. **Plain exports.** Should they also be offered to the share sheet (for example Telegram), or only saved through the file picker (this design)?
13. **Real-world checks.** Who can run the checks this design depends on?
    - our CSV imported into Google Password Manager on the user's phone;
    - the `.xlsx` opened in the Excel, Google Sheets or WPS Office the user actually has;
    - the Chrome export path and the Settings route on the user's phone;
    - Google Drive's provider authority;
    - Gboard and Samsung Keyboard typing spaces in a password field.

## 15. Review: the critique of 2026-10-06

The UX critique (`scratchpad/design/ux-critic/CRITIQUE.md`) raised 3 critical, 16 major and 18 minor problems. I judged each one and re-checked the facts the decisions hinge on (§13, "Checked for this revision"). None was rejected outright. 34 were accepted, several with a small change that the table names, and 3 only in part (M1, m13, m15).

| # | Raised | Decision | Changed |
|---|---|---|---|
| C1 | The file picker and share sheet stop the app, so the lock fires mid-backup and leaves an empty `.pvault` in Drive | Accepted. The grace is 10 min, as in both lock designs, instead of the critic's 5 | §4 (system screens, what survives a lock, unfinished file tasks); §5.19–5.21; §12 checks 12–14 |
| C2 | No way to replace a forgotten master password while the PIN works | Accepted. The waiting time is left to the lock design, with a UX recommendation | §5.24 (new); §5.10 hint after 2 wrong tries; forgot sheet with the PIN first; §5.9 fingerprint after PIN lockout |
| C3 | "Start over" deletes everything from the lock screen in 5 taps | Accepted with one change: deleting an archive needs that archive's master password, not just an unlocked app. Otherwise anyone could start over and then delete the archive from the new vault's Settings | §5.10 (sheet, archives); §5.1; §5.2; §5.18 |
| M1 | Forced practice can lock the user out away from the paper | Partly. "Not now: use PIN" at every unlock for a grace period (about 7 days), then required, with the PIN route always offered. "Never force" was declined: a reminder that can be skipped forever stops finding out about forgetting while the PIN can still fix it. The Aegis precedent was confirmed in its source | §5.10, §7.3, §14 |
| M2 | zxcvbn rates Uzbek words as random | Accepted; scores reproduced | §5.3 (dictionary, folded copy, score 4, hint); §5.14; §5.16; §12 check 18 |
| M3 | Retries push the website's password out of the 5-item history | Accepted | §2 `pendingOldPassword`; §5.17; §12 check 5 |
| M4 | "Copy current password" means two different passwords | Accepted, with the labels "old" and "new" (old = the one the website has now) | §5.11, §5.12, §5.17, §8 |
| M5 | The master-password card advises 50 website changes | Accepted | §5.16 card 1; §5.3 "anywhere else?"; §5.18 old backup places |
| M6 | "Combine them" can merge different accounts | Accepted: removed from Security; exact rules at import | §5.16, §5.21, §2, §11 |
| M7 | Auto-save on leaving commits half-typed edits | Accepted | §4, §5.14 (draft); §12 check 4 |
| M8 | No state for a moved or reinstalled vault | Accepted | §5.9 "Welcome back"; §7.6; §5.22 |
| M9 | Lock-screen dead ends | Accepted | §5.9 states table and notices; §5.10 memory |
| M10 | Clipboard: a safe check is possible, later copies get erased, the deviation is unrecorded | Accepted; the guide text was confirmed | §3, §6, §12 check 2, §14 |
| M11 | Apostrophes in the master password differ between phones | Accepted: folding recommended, refusal until then | §5.3, §14 |
| M12 | The fingerprint offer is hidden on Android 10 and mislabelled | Accepted; the lock designs' lab results (API 28–29) were used | §5.7, §5.9, §5.18 |
| M13 | Forgetting the PIN costs two passphrase entries | Accepted (21 → 16 taps). Fingerprint approval of "Change PIN" is left to the lock design, because it adds a way in | §5.6, §5.9, §5.18, §7.6 |
| M14 | Every backup needs the passphrase, which is on paper at home | Accepted as a proposal to the crypto owner (Aegis and storage.md §5.4 copy the vault's key); until accepted, the step stays | §5.19, §14 |
| M15 | Backup prompts miss the moments that matter | Accepted | §5.8, §5.11 (two banners), §5.17, §7.4 |
| M16 | Sign-up: no way to copy a fresh password; Save's destination undefined | Accepted. "Save and copy password" was declined: the detail screen's copy is one tap | §5.14, §5.15 |
| m1 | Search folding gaps | Accepted. The Russian-keyboard part is already in search.md, which appeared during this revision; accent-free ş/ç and the layout retry are asked of its owner | §5.11, §14 |
| m2 | Letter headers versus the Uzbek order | Accepted; reproduced on ICU 78.1 and 56.1 | §5.11 |
| m3 | Titles are lost when importing the CSV into Google | Accepted, as a "Title:" line (not "Name:", which a user's own field may use) | §5.20.2 |
| m4 | The Chrome menu path depends on one label | Accepted | §5.21, §11 |
| m5 | Import encoding, Excel files, Chrome's titles | Encoding and Excel detection were already in formats.md §8.2; the wording is fixed here. Tidy names accepted | §5.21 |
| m6 | Accessibility failures | Accepted. The headphones sentence goes on PIN creation, not on the lock screen, where TalkBack would repeat it at every unlock | §3, §5.3, §5.6, §5.11, §8 |
| m7 | Show large has no time limit | Accepted | §5.13, §14 |
| m8 | Wording a B1 reader misreads | Accepted. The Settings group is named "App lock" (an existing glossary term) rather than "Lock" | throughout |
| m9 | Delete is final after 10 s | Accepted | §5.23 (new), §5.12, §2 |
| m10 | The export result's main button deletes the file | Accepted | §5.20 |
| m11 | Help names the wrong threat | Accepted | §7.6 |
| m12 | Platform details stated loosely | Accepted | §3, §4, §5.6, §5.9, §12 check 3 |
| m13 | "Same as your master password" while typing needs a secret | Partly. The check runs at Save. Imported passwords are compared at the next master-password entry, not with one 64 MiB run (about 1 s) per distinct password right after the import: practice comes a day later anyway | §5.10, §5.14, §5.21 |
| m14 | The Security badge reads "50" for months | Accepted: a dot for new results | §5.16, §8 |
| m15 | Complexity with little gain | Partly. "Where the file landed" was simplified and the build order adopted. CSV splitting was kept: formats.md has built and tested it, and it appears only above 3,000 rows | §5.19, §5.20.2, §5.21 |
| m16 | Setup asks for a lot of typing | Accepted; the separator is a crypto-owner question | §5.3, §5.5, §14 |
| m17 | Decision 2 is stated as decided | Accepted | §0, §14 |
| m18 | Changing the master password forgets the PIN | Accepted | §5.18, §5.24 |

**Tap counts after the changes** (critique §1):
- Getting back in after forgetting the PIN: 16 taps and one passphrase (was 21 taps and two).
- A backup from the banner: 4 taps with no typing if the crypto owner accepts §5.19 (was 8 taps plus the passphrase).
- Adding an account now lands on its detail screen, so the new password is one tap from the clipboard.
- The guided change keeps its roughly 31 taps but can no longer lose the website's password.

**Also changed, to match documents written after the first version of this one:**
- four languages and "System language" (i18n.md §0, §6);
- search now follows search.md and the sort key i18n.md §2.7. So "search across scripts" left §11, and website paths are no longer searched (search.md §8);
- export layouts now live in formats.md: JSON in Bitwarden's layout, shared strings in `.xlsx`, one CSV row per website;
- the per-field autofill rule and text-field clearing in §3 follow V-P4 and V-C11;
- notes over the limit are cut on import instead of losing the account (formats.md §8.4).
