# PasswordVault: UX specification and version 1 entry model

Date 2026-10-06; revised the same day after the UX critique (what changed and why: §15). Status: proposal for review. Scope: every screen of version 1, the entry model, the copy flow, onboarding text, accessibility, localisation, navigation, and what is left out.

Other documents own parts of the picture, and this one points to them instead of repeating them:
- **The lock design, [lock.md](lock.md),** owns the PIN logic: length, attempt limits, waiting times, what is stored where, when the master password is required, and the English wording of the lock screens (its §13). It was chosen from candidates that this document calls "lock design 1" and "lock design 2" (their texts are in `docs/design/lock-work/`). This document fixes how the lock screens look; where the two differ, lock.md wins.
- **[architecture.md](architecture.md)** §6 decides the questions this document left to other owners (§14).

**Revised on 2026-10-06** by build-plan package 0.3 to follow lock.md (the twelve changes of its §16.2) and architecture.md §6 (D-6c, D-7, D-9b, D-10d, D-11, D-14, D-15, D-23, D-24, D-25), and to stop using the phone's last unlock time as a lock signal, which lock.md §10.2 dropped. Where a passage is replaced, the old text is gone; §14 marks each question as answered.
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
2. **A bank-style lock of the app's own.** A 6-dot PIN pad drawn by the app (no system keyboard involved), fingerprint as a shortcut, and "Forgot PIN? Use master password" always one tap away. It is separate from the phone's screen lock. The user confirmed that "home lock like banking account" means the app's own PIN (lock.md §2.4, architecture.md D-2).
3. **Defaults are the secure choice; settings are few.** The app locks 30 s after leaving and at once when the screen turns off; copied text is cleared after 1 minute; screenshots are always blocked. Setup writes both delays (architecture.md D-7); a delay that is missing or cannot be read counts as the strictest value, "Immediately" for the lock and 30 seconds for the clipboard. Some participants simply keep the preset settings ("these [settings] were preset and I kept what was recommended") [E: CCS 2019].
4. **Version 1 entry**: title (required), username, password, websites (up to 10), notes, extra fields (label, value, hidden or not), favourite, three timestamps and the last 5 previous passwords. Four small fields keep the security check and deletion safe: `changePending`, `pendingOldPassword`, `checkIgnored` and `deletedAt`. **Tags are deferred**; "recently used" is not tracked.
5. **Nothing the user types is lost, and nothing reachable from the lock screen deletes anything.**
   - An unsaved form is kept as an encrypted draft when the user leaves the app.
   - A backup, export or import that passes through the system file picker survives a lock.
   - The guided change keeps the password the website still has until the user confirms the change.
   - A deleted account waits 30 days in "Recently deleted".
   - "Start with an empty app" keeps the old passwords on the phone, locked.
6. **The master password is practised, and a forgotten one can be replaced.** The app asks for it now and then, and "Not now" works for a few days. If the user forgets it while the PIN still opens the app, they can choose a new one, which starts working after 24 hours with the phone switched on (§5.24, lock.md F11).
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
| `fields` | list of {`name`, `value`, `secret`} | no | ≤ 20 per entry; `name` 1–100, one line, trimmed (formats.md §4.4 relies on it); `value` ≤ 1,000, line breaks allowed; `secret` boolean, default true for labels the app does not know |
| `favourite` | boolean | yes, default false | |
| `createdAt` | instant, stored as an integer: milliseconds since 1970 UTC (architecture.md §4.3, D-18; ISO 8601 text only in the JSON export) | yes, set by app | |
| `updatedAt` | instant | yes, set by app | on any saved change |
| `passwordChangedAt` | instant or null | no | set when the password is first set and whenever its value changes |
| `passwordHistory` | list of {`password`, `replacedAt`} | no | newest first; keep the **5** newest; an item is added when a saved password changes |
| `changePending` | boolean | yes, default false | true from "Create new password" until "Yes, it's changed" or "No, put back the old password" in the guided change (§5.17) |
| `pendingOldPassword` | string or null (**new**) | no | the password the website had when the guided change started. Set only while `changePending` is true. It is not one of the 5 previous passwords and is never pruned; it is cleared when the change ends (§5.17) |
| `checkIgnored` | boolean | yes, default false | "Leave out of the security check", for PINs or passwords the user cannot change |
| `deletedAt` | instant or null (**new**) | no | set by Delete; the account waits in Recently deleted and is removed a day or more after 30 days (§5.23, architecture.md D-24) |

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
  - the backup-reminder snooze (when and where the last backup was made is in the locked file, below);
  - one-time tips shown, and "setup words were shown" (§5.5);
  - an unfinished file task: its kind, step and file location (§4);
  - the dates of archived vaults (§5.10).

  The lock design keeps its own state: attempts, the practice schedule, device keys, and the highest generation seen (V-C8).
- **Inside the encrypted body**:
  - at most one unsaved-form draft (§5.14);
  - the locations of unprotected exports not yet deleted (§5.20);
  - the locations and times of backups this app saved (§5.18). A backup is due when an account changed or was deleted after the newest of them; no counter is kept (architecture.md §4.3);
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
  - If TalkBack cannot operate the keypad with the flag (lock.md §13.5, check E12), the keys drop it and the dots keep it.
- **Layout.** Edge-to-edge with inset handling; both orientations; content width capped at 640 dp on large screens (Android 16+ ignores orientation locks at ≥ 600 dp) [V: Android 16 behaviour changes].

## 4. Navigation and back behaviour

There are three top-level states. The app owns one back stack per state; switching state replaces the whole stack.

```
Process start ──► no vault file? ──► SETUP: Welcome → (Restore | Open my previous passwords | Master password → Write down → Type again)
                                               → [vault created] → PIN → Confirm PIN → Fingerprint? → Backups → UNLOCKED
              └─► vault file ─────► LOCKED: PIN pad, or fingerprint only ⇄ Master password (practice, PIN off, "Welcome back"…)
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

**Auto-lock as the user experiences it.** The numbers and mechanics belong to lock.md §10.
- The app locks:
  - when the lock delay has passed since the user left it: 30 s, written at setup; a setting that is missing or cannot be read counts as "Immediately" (architecture.md D-7);
  - at once when the screen turns off;
  - after 5 minutes without a touch while open (the timer stands still while the app is not in front);
  - on every process start.
- **Screen off.** When the app stops while `PowerManager.isInteractive()` is false (API 20 [V]), it locks at once.
  - If the screen turns off while the app is already in the background, Android 14+ may hold the screen-off broadcast until the app runs again [E: platform.md §3.3]. The lock is then applied when the broadcast arrives.
  - This background case is best effort; the lock delay still applies. lock.md §10.2 says why the phone's own last unlock time is not used as a signal: a fingerprint prompt in another app moves it.

**System screens the app opens.**
- **The problem.** The system file picker and the share sheet stop the app's process lifecycle just as leaving the app does [E: V-P3]. Without a rule, the 30 s lock (or "Immediately") would fire in the middle of a backup, export or import.
- **The rule.** Time spent in such a screen that the app opened itself counts as time in the app, for at most **10 minutes** (lock.md §10.3, architecture.md D-9e). This covers saving a backup or export, choosing an import file, and sending a backup.
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
- **"Open my previous passwords"** (lock.md F17 step 4):
  - It shows **that vault's own lock screen**, with the line "Passwords locked on 6 Oct 2026": its PIN pad and fingerprint if their records and keys still work, otherwise its master-password field. Its own PIN, fingerprint or master password opens it, with its own counts and waits (lock.md §6.8); a vault marked as replaced (§5.24) shows only its master-password field.
  - Only when it has been opened does it become the app's vault again. Back returns to Welcome and changes nothing, so a stray tap cannot swap anything. When it opened without a working PIN, the app then offers to choose one.
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
- Not our file: "This is not a PasswordVault backup. To bring passwords from Chrome or another app, finish setup first, then use Settings › Import."
- Wrong password: "This password does not open this backup. Try the master password you used when you made it."
- Damaged: "This backup file is damaged and cannot be opened. Try another backup file."
- Made by a newer app version: "This backup was made by a newer version of the app. Install the newest version of PasswordVault and try again."
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
    - This is chosen over 5 words from the long list (64.6 bits, words of mean 7 letters), because short common words are easier for a non-native speaker to read, write and type [I]. Confirmed by architecture.md D-6a (crypto.md had proposed the long list).
    - Re-rolling a few times costs at most log2 of the number of rolls, in bits [I].
  - **Separator: hyphen in version 1** (architecture.md D-6b).
    - On Gboard a hyphen costs about 3 key presses (to the symbol layer and back); a space costs 1 [E: critique m16].
    - The master password already folds white space from the first release, after NFC (lock.md §8.1): runs of spaces become one and the ends are trimmed. So spaces can become the suggested separator later without changing any key; the strength stays at 62 bits, because no word contains a space.
    - Which separator to suggest is settled by the keyboard test on the user's phone (Gboard and Samsung Keyboard in a password field: double-space full stop, automatic spaces; architecture.md §8.6 item 7).
- **Own password view.**
  - **The field**: an `OutlinedSecureTextField` with show/hide, a strength meter and a checklist.
  - **Every item of the checklist must pass.** The button stays enabled and says what is missing when pressed (no unexplained disabled buttons):
    - "At least 12 characters" (Bitwarden's minimum for master passwords) [E: `SetPasswordViewModel.kt`].
    - "Hard to guess":
      - zxcvbn score **4** (its highest: 10^10 guesses or more) for the password and for a folded copy of it (the search key of §5.11, search.md §1, so "Oʻzbekiston" is also checked as "ozbekiston" and "Ташкент" as "tashkent");
      - with the bundled dictionary below, plus the app name, "password", "parol" and "пароль" as user inputs.
    - When a dictionary word, name or year is found, the hint is: "Avoid city names, people's names and years."
    - Apostrophes are allowed (architecture.md D-6c; see "Apostrophe-like characters" below).
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
  - **Decided** (architecture.md D-6c, lock.md §8.1): the master password is turned into bytes by `MasterPasswordBytes`, which after NFC folds white space and then 17 apostrophe-like characters to U+0027, at creation and at every use, with test vectors. So apostrophes are allowed in the master password, and "oʻgʻlim" typed with ʻ on one phone and with ' on another is the same password.
  - At creation the only characters refused are code points Unicode has not assigned (lock.md §8.1), which a later phone might treat differently.
  - Passwords of accounts are not affected: they stay exactly as typed.
- **Strength meter.**
  - 4 segments plus a word: "Very weak", "Weak", "Good", "Strong" (zxcvbn 0–1, 2, 3, 4). Colour is never the only signal.
  - Library: zxcvbn4j `com.nulab-inc:zxcvbn` 1.9.0, MIT, no dependencies, 503 KB jar [V: Maven Central]. It uses the same 0–4 scale as Bitwarden's report (§5.16).
  - Its English feedback strings are not shown. The app shows its own short hints: "Add another word", "Avoid city names, people's names and years", "Avoid keyboard rows like qwerty".

### 5.4 Write it down

- Title "Write it down on paper". Body: "Write your master password on paper now. Keep the paper at home in a safe place — not in your phone case, not in your wallet, and not as a photo on this phone."
- The password is shown in large monospace with its words numbered as in §5.3, plus a "Show in big letters" link (§5.13).
- `Checkbox` "I wrote it down" (optional; not enforced) and `Button` "Next". Reason: only 5 of 37 novices wrote their primary password into 1Password's Emergency Kit [E: SOUPS 2025]. The reminder matters; a forced checkbox does not.
- **In a change or a replacement of the master password** (§5.18, §5.24) this screen and the next keep the screen on (`FLAG_KEEP_SCREEN_ON`, lock.md §10.1): a screen that timed out while six words are copied to paper would lock at once and throw the flow away. The 5 minutes without a touch still apply.

### 5.5 Type it again

- Title "Type your master password again". Body: "This checks that you wrote it correctly."
- The field is focused and the keyboard opens when the screen appears. The keyboard's action key is "Done" and submits. A full-width `Button` "Next" under the field does the same.
- **On mismatch**: "This doesn't match. Look at your paper and try again."
  - After 3 mismatches, a `TextButton` "Show my password again" is added (back to §5.4).
  - If the attempt contains Cyrillic letters: "Your keyboard may be set to Russian. Switch to Latin letters."
- **On match**: "Setting up…" (`CircularProgressIndicator`, about 1 s for key derivation, the file write and a read-back check) [E: crypto.md §11]. Error: "Couldn't save. Your phone's storage may be full." with "Try again".
- Then the vault exists, and back now leaves the app (§4).
- In a change or a replacement of the master password the screen is kept on, as on "Write it down" (§5.4).
- **Interrupted setup.**
  - The words are never stored. If the app is closed or killed after they were shown and before the vault exists, the next start shows new words with: "Setup was interrupted. Cross out the words you wrote down before. These are your new words."
  - A non-secret flag remembers that words were shown.

### 5.6 Create the PIN

- Title "Choose a 6-digit PIN". Body (lock.md §7.3):
  - "Use this PIN to open the app on this phone, like your bank app. This is not your phone's PIN."
  - "Don't use a date or a year, or a pattern like 123456 or 112233."
  - "Don't use your phone's unlock PIN or your bank card PIN. Other people may know them."

  Family members often know the phone PIN [I].
- When TalkBack (or another spoken-feedback service) is on, the body adds: "TalkBack reads each key aloud. Use headphones if other people are near."
- The keypad and dots are the same as on the lock screen (§5.9), without the fingerprint key.
- **Refused PINs** (lock.md §7.2, §7.3): after the sixth digit, when a rule of the blocklist matches, the dots clear, the pad stays, and one of three messages says why:
  - "This PIN looks like a date or a year. Choose another."
  - "This PIN repeats digits or follows a pattern. Choose another."
  - "This PIN is a sequence or a shape on the keypad. Choose another."

  In "Change PIN", a new PIN equal to the current one gives "Choose a PIN different from your current one."
- Confirm step: "Enter the PIN again". On a mismatch: "The PINs don't match. Try again."; after 3 mismatches the user starts again from the first entry.
- Footer (lock.md §7.3): "After 5 wrong tries in a row the PIN turns off. Then you open the app with your fingerprint or master password and choose a new PIN."
- Choosing a PIN is not an unlock, so nothing is counted.
- **Length**: exactly 6 digits, submitted automatically after the sixth (lock.md §7.1).
  - If fewer digits are ever allowed, the keypad gets an "OK" key and never submits by itself. Otherwise the length would be revealed (CDD 17 C-SR-7) [E: FACTS §6.3].

### 5.7 Fingerprint offer

**When it is shown** (lock.md §9.4, the same rule on every Android version from 7.0): `BiometricManager.canAuthenticate(BIOMETRIC_STRONG)` is asked off the main thread when the screen opens.
- `BIOMETRIC_SUCCESS`: offered.
- Any other answer: not offered. Settings › App lock then shows the reason (§5.18). `BIOMETRIC_STATUS_UNKNOWN`, which only Android 9 and 10 give (a screen lock, but no enrolled fingerprint the library can see), leaves the switch in Settings enabled: turning it on decides, and a failure says so [E: V-P1].
- There is no Android version limit: on Android 7 and 8 the library draws its own fingerprint dialog in the app's activity, which the old-Android lab ran (docs/lab/old-android.md §3, lock.md §13.5).
- Weak (Class 2) sensors are not offered, as in Keyguard [E: keyguard docs]: only a strong sensor can unlock a key in the phone's secure hardware. A phone that cannot keep the key in secure hardware is told: "This phone can't keep a fingerprint key in protected hardware."

**Names.**
- On Android 12+ the button and setting names come from `BiometricManager.getStrings(BIOMETRIC_STRONG)` (API 31 [V]), so a phone whose only strong biometric is the face says "face".
- The app's own sentences say "fingerprint", or "face" when the phone has a strong face sensor and no fingerprint sensor.

**The screen.**
- Title "Use your fingerprint?" Body: "Open the app with your fingerprint instead of your PIN. Your PIN and master password still work."
- "Anyone whose fingerprint is saved in this phone's settings can open the app." (lock.md §9.4)
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

The states, what each shows and its English wording are lock.md §13.1 to §13.3; this section is the look, and its texts are lock.md's.

**Layout** (portrait):
- app icon 64 dp; title "PasswordVault"; subtitle **"Enter your app PIN"**. "App" tells it apart from the phone's own PIN, which the system's fingerprint prompt asks for after several wrong fingers (lock.md §9.3);
- a row of 6 dots, filled as digits are entered;
- a status line (below). A line about a wrong PIN or about tries left stays until the visit ends: typing or deleting digits does not remove it, so that someone who is handed the phone sees it;
- a 3 × 4 keypad `1 2 3 / 4 5 6 / 7 8 9 / [fingerprint] 0 [⌫]`;
- below it, `TextButton` **"Forgot PIN? Use master password"**.

**The fingerprint-only screen** (lock.md §13.1 row 6, §13.2), when fingerprint is usable and the PIN is not: title "PasswordVault", the status line, and the buttons "Use fingerprint" and "Use master password". No pad and no dots. The prompt opens by itself.

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
  - on a practice day (after "Not now" the pad appears and the prompt opens as on any other visit);
  - again after the user cancelled it.

  The fingerprint key opens it again. Fingerprint stays usable after the PIN has been turned off (lock.md §6.5): the fingerprint-only screen then appears.
- Prompt (lock.md §9.2): title "Open PasswordVault", negative button "Use PIN", or "Use master password" when no PIN is usable.

**States and wording** (lock.md §13.3; the triggers and numbers come from lock.md and the storage design). There are no waiting times for the PIN: no "Try again in 0:30" and never a message about wiping.

| State | What the user sees |
|---|---|
| Fresh pad, 2 or fewer tries left | "2 tries left before the PIN turns off." |
| Wrong PIN | Dots shake (not when animations are off), `HapticFeedbackType.Reject`, dots clear, "Wrong PIN. 4 tries left." On the last try: "Wrong PIN. 1 try left. After that you'll need your fingerprint or master password." (without fingerprint: "…your master password.") |
| The last try while a new master password is not working yet (§5.24) | "Careful: your new master password is not working yet. If the next PIN is wrong, the PIN turns off." |
| The same wrong PIN again | "You already tried that PIN. It was not counted again." |
| A try was counted at start (the last entry was interrupted) | "Your last PIN entry was interrupted and counted as a try. 4 tries left." |
| PIN turned off after too many wrong tries | With fingerprint usable, the fingerprint-only screen: "PIN unlock was turned off after too many wrong tries. Nothing was deleted. Use your fingerprint or master password, then choose a new PIN." Otherwise the §5.10 screen: "Your PIN was entered wrong too many times, so it was turned off. Nothing was deleted. Type your master password, then choose a new PIN." |
| Opening | The keypad is replaced by `CircularProgressIndicator` and "Opening…" |
| PIN can't be checked right now (the Keystore is not answering, or its record cannot be read right now [E: V-C6]) | The fingerprint-only screen: "Your PIN can't be checked right now. Use your fingerprint or master password. Restarting the phone usually fixes this. If it keeps happening, set your PIN up again in Settings." Without fingerprint the §5.10 screen: "Your PIN can't be checked right now. Use your master password this time. Restarting the phone usually fixes this. If it keeps happening, set your PIN up again in Settings." Nothing is removed |
| Storage full (the try could not be written) | "Your phone's storage is full, so the PIN can't be checked now. Free some space or use your master password." (with fingerprint: "…Free some space, or use your fingerprint or master password.") |
| PIN stopped working (its key is gone, or its record is damaged) | "Your PIN stopped working on this phone. Use your fingerprint or master password, then choose a PIN again." Without fingerprint the §5.10 screen: "Your PIN stopped working on this phone. Type your master password, then choose a PIN again." |
| The PIN was set up by a newer version of the app | "Your PIN was set up by a newer version of this app. Use your fingerprint or master password, and update the app." (without fingerprint: "Use your master password, and update the app.") |
| **No PIN on this phone yet**: the vault came with "Copy apps and data", the app was installed again with its data kept, or the lock file is missing or damaged [E: V-P6; platform.md §3.4, §3.7] | The §5.10 screen with the title **"Welcome back"**: "Your PIN and fingerprint don't work here yet. This happens after you move to a new phone or install the app again. Type your master password once, then choose a PIN for this phone." Then "Choose a PIN" (skippable) and the fingerprint offer. Nothing is deleted |
| Fingerprint turned off by the phone (only when its key is permanently invalidated) | "Fingerprint unlock was turned off because this phone's fingerprints or screen lock changed. Use your PIN. You can turn it on again in Settings." |
| Fingerprint locked by the system after failures | "Fingerprint is locked for now. Use your PIN." |
| The fingerprint was accepted but did not open the app (the phone's own PIN was typed in the system prompt first, lock.md §9.3) | "Fingerprint did not open the app this time. Enter your app PIN." |
| Fingerprint can't be used now, because the phone's setting for apps is off | "Fingerprint can't be used in apps right now. Use your PIN. In your phone's settings, check that fingerprint may be used to verify it's you in apps." |
| Fingerprint can't be used now, because no fingerprint is saved (or there is no screen lock) | "No fingerprint is saved on this phone now. Use your PIN. To use fingerprint unlock again, add a fingerprint in your phone's settings." |
| Fingerprint can't be used now, because the phone needs a security update | "Your phone needs a security update before fingerprint can be used. Use your PIN." |
| Fingerprint can't be checked right now (any other fingerprint error) | The fingerprint key is hidden until the next visit, with "Fingerprint can't be checked right now. Use your PIN. If this keeps happening, turn fingerprint unlock off and on again in Settings." The option stays on [E: V-C6] |
| Debug and staging builds only | "TEST BUILD: PIN keys are not protected" |

On the fingerprint-only screen the fingerprint lines say "Use your master password" in place of "Use your PIN". A fingerprint that cannot be used now leaves its key alone: nothing is removed, and it works again when the phone allows it (lock.md §13.1).
| **File damaged**: no saved copy opens, although the PIN or master password is right [E: V-S3] | Title "Your passwords can't be opened". Body: "The file with your passwords is damaged. Nothing was deleted. Restart your phone and try again. If that doesn't help, restore from a backup file." `Button` "Try again", `OutlinedButton` "Restore from a backup file". The backup is opened and checked before anything changes, and the damaged files are kept (§5.10) |

**After unlock**, notices appear one at a time, in the order of lock.md §13.4:
- **Older copy opened** (storage fell back a generation): `AlertDialog` "The newest copy of your passwords could not be read. We opened the copy from 5 October, 18:42. Changes after that time are missing." Buttons "Make a backup now" and "OK". A copy that cannot be read can be a sign of storage trouble [E: storage.md §5.4].
- **Older than last time** (rollback [E: V-C8]): "These passwords are older than the ones this app opened on 6 October, 09:10. Changes after that time are missing. If you didn't restore anything, someone may have replaced the app's files." Button "OK".
- **A new master password is pending, or was replaced**: the notices of §5.24.
- **Wrong tries nobody has been told about** (lock.md §13.4 item 4): "A wrong PIN was typed 3 times before you opened the app (last time today at 14:32). If it wasn't you, keep your phone with you and change your PIN." Buttons "Change PIN" and "OK". It adds "One entry was interrupted and counted as a try." when that happened, and, from 6 tries that were not forgiven, "After 4 more wrong PINs that are not put right at once, the PIN turns off. Type your master password once to start the count again." with "Type master password". Wrong master passwords are reported the same way: "A wrong master password was typed 2 times before you opened the app."
- **A shortcut needs attention:** "Choose a new PIN", "Turn fingerprint unlock on again?", or "Part of the app's storage could not be read. Make a backup now."
- **After an unlock through "Forgot PIN?", or after the PIN was turned off**: the "Choose a new PIN" screen (§5.6) with `TextButton` "Not now". This takes 16 taps and one passphrase in all, instead of a trip to Settings with the passphrase typed twice (21 taps) [E: critique task 7].
- **"Make a backup now?"** when the master password was just typed and a backup is due (§5.19).

**Never** a "wipe after N attempts" message (no wipe exists) [E: account-based.md finding 4], and never a hint of how many accounts are stored.

**What this document asked of the lock design, and the answers** (lock.md §16.1):
1. Fingerprint unlock stays usable after the PIN has been turned off (lock.md §6.5). The sensor has its own hardware lockout, so a child pressing keys cannot force the master password, and an owner who has forgotten the master password still gets in and can choose a new one (§5.24).
2. A practice grace period: 1, 2 and 4 days in the first week, then 7 days (lock.md §8.3; §5.10).
3. The forgotten-master-password route through the PIN is kept (lock.md F11; §5.24).

### 5.10 Lock screen — master password

**Layout**:
- title "Type your master password";
- an `OutlinedSecureTextField` (keyboard type password, no auto-capitalisation, marked sensitive);
- `Button` "Unlock";
- `TextButton` "Use PIN" when the PIN can be used now (labelled "Not now: use PIN" on a practice day);
- `TextButton` "Forgot master password?".

The **subtitle** says why the master password is needed now. When several reasons apply, the one highest in lock.md §13.3's table is shown:
- practice (below);
- **"Welcome back"** (no lock records for this vault; §5.9);
- **a vault from before a replacement**: title "Your passwords from before 7 Oct 2026", "Type the master password you used then." (§5.24);
- the PIN states of §5.9: turned off, stopped working (its key is gone or its record is damaged), set up by a newer version of the app, can't be checked right now, storage full;
- "You changed your master password. Type it once, then choose a PIN again." when a change did not keep the PIN;
- "The newest copy of your passwords can't be read. An older copy from 5 Oct opens with the master password you used then." (an older copy with another key, lock.md F16a);
- the first time only, on a phone that cannot keep keys in secure hardware: "This phone can't keep a PIN or fingerprint key in protected hardware, so the app opens with your master password only.";
- none when the user chose it with "Forgot PIN?", or when no PIN was ever set.

If fingerprint unlock is on and cannot be used now, the matching fingerprint line of §5.9 is shown under the reason, with "Use your master password".

**Practice** (lock.md §8.3). The app asks on days 1, 3 and 7 after the master password was set, then every 14 days.
- **On a practice day**, the lock screen opens on this screen instead of the PIN pad, and the fingerprint prompt does not open by itself. Subtitle: "Time to practise your master password. We ask now and then so you don't forget it. You need it to open a backup and to move to a new phone."
- **"Not now: use PIN"** (or "Not now: use fingerprint") opens the PIN pad with its fingerprint key and is not asked again for **20 hours**, so the app asks at most once a day. Aegis works the same way: when its reminder is due it focuses the password field, but its fingerprint button still unlocks after one dialog [V: Aegis 3.4.3 `ui/AuthActivity.java:180-193, 211-224`].
- **Grace period.** "Not now" is offered for 1, 2 and 4 days at the three asks of the first week, then for 7 days: the grace is never longer than the interval, so that the early repetitions that fix a new password in memory are not all skipped.
- **After the grace period**, the master password is required. "I can't remember it" stays and leads to §5.24, which opens the app with the PIN or fingerprint, so nobody is locked out of their passwords because the paper is at home. Until a master password has been typed, the list shows the standing banner of §5.11.
- **Why not skippable forever.** A reminder that can always be skipped stops finding out that the password was forgotten, and only while the PIN still works can a forgotten password be replaced. lock.md §8.3 replaces crypto.md §11 step 6 (architecture.md D-6f).
- **What counts as practice.** Any correct master password: unlock, practice, export, backup or change. Practice is not asked while a new master password is pending (§5.24), nor on a phone without a PIN or fingerprint.

**Wrong master password** (lock.md §13.3).
- One neutral hint, whatever was typed: "That's not the right master password. Check the keyboard language (Latin or Cyrillic) and capital letters."
- After 2 wrong tries, when the PIN or fingerprint still works, it adds "Can't remember it? Open the app with your PIN, then choose a new master password." with `TextButton` "I forgot it", which opens the sheet below.
- When the passwords from before a replacement are on the phone (§5.24), a wrong master password adds "The master password was replaced on 7 Oct using the PIN. If you didn't do that, open your passwords from before." with the button "Open my passwords from before 7 Oct". If the master password was replaced more than once, the button opens the list of the sheet below.
- While a new master password is pending and not ready, under the field: "Your new master password starts working in about 20 hours."
- After several wrong master passwords the field waits (lock.md §6.8): it is disabled, with a countdown from 0:30 and "Wait 30 seconds, then try again. Take your time and look at your paper."

**Not enough memory** (the master-password check needs about 64 MiB) [E: V-C2]: "Your phone doesn't have enough free memory to open your passwords. Close other apps or restart the phone, then try again." `Button` "Try again".

**Master-password reuse check** (architecture.md D-9b). After every correct master-password entry (unlock, practice, backup, export, change), the app compares the typed master password, through `MasterPasswordBytes`, with every entry's password in memory. It stores the ids of the matches inside the locked file (§2, §5.16). Nothing about the master password itself is stored [I]. **There is no check when an account is saved**: it would cost one key derivation (64 MiB and a second) per save, and it would be a way to test guesses at the master password outside the lock's limits (lock.md §6.8). A password saved in the meantime is found at the next master-password entry.

**"Forgot master password?"** opens a `ModalBottomSheet` (lock.md F17 step 1).
- **Title**: "Forgot your master password?" Body: "Nobody can recover it: it is not stored anywhere."
- **The options**, only those that apply, in this order:
  1. **When a PIN exists**: "Your PIN still opens the app on this phone. Open the app with it, then choose a new master password." `Button` "Use my PIN" → §5.24. **When no PIN exists and fingerprint works**: the same with "fingerprint" and `Button` "Use my fingerprint". A fingerprint is not accepted where a PIN exists (lock.md F11 step 3).
  2. "If you remember the master password of an older backup, you can open that backup instead. Your current passwords stay on this phone, locked." `OutlinedButton` "Restore from a backup file". This follows §5.2 steps 1–4: the backup is opened and checked with its password, and its summary card adds "Your current passwords stay on this phone, locked." with the button "Use this backup". Only then is the current vault archived and the backup installed. Setup continues at the PIN step.
  3. One entry for each vault that was put aside: "Open my previous passwords (locked on 6 Oct 2026)"; for a vault put aside by a replacement, "Open my passwords from before 7 Oct 2026". Each shows that vault's own lock screen (§5.1).
  4. **Whenever no PIN or fingerprint is usable now**: "Start with an empty app. Your current passwords stay on this phone, locked. If you remember the master password later, you can open them again." `TextButton` "Start with an empty app…" opens a dialog:
     - title "Start with an empty app?";
     - body "Your passwords stay on this phone, locked with your old master password. You can open them again from the first screen or from Settings › Backup & transfer.";
     - buttons "Start with an empty app" and "Cancel".
  5. `TextButton` "Try again" closes the sheet.
- **No path that starts at the lock screen deletes anything, and none uses the word "Delete"** [E: V-S3: "Never offer a destructive 'reset vault' in response to a wrong password"].

**Archived vaults** (owners: storage and lock designs).
- "Start with an empty app" and a restore over an existing vault move the vault's files to an archive name that clean-up never touches, like the quarantine of V-S4. Then setup starts.
- Archives appear:
  - on Welcome as "Open my previous passwords (locked on 6 Oct 2026)" (§5.1);
  - in Settings › Backup & transfer as "Old passwords (locked on 6 Oct 2026)", with "Open" and "Delete".

  "Open" in Settings adds the archive's accounts to the current vault, like a backup import (§5.21). It asks for that archive's master password, with that archive's own waits after wrong tries (lock.md §6.8), as its lock screen from Welcome or the sheet does.
- **Deleting an archive needs that archive's master password**, and nothing else: not the current master password, not a pending one. Otherwise anyone holding the phone could start over and then delete the owner's passwords from the new vault's Settings. An archive whose password is forgotten simply stays; it is small.

### 5.11 Passwords (home list) and search

**Layout** (`Scaffold`):
- **`TopAppBar`**: title "Passwords"; action `IconButton` "Lock now" (lock icon).
- **Search field**, directly below: `SearchBarDefaults.InputField` from the Material 3 `SearchBar` family, used as an in-place filter (the full-screen expanded mode is not used). Placeholder "Search accounts".
- **Banners** (`OutlinedCard`), **at most two at a time**, in this priority order (the others wait):
  1. the lock's banner (`LockStatus`, architecture.md §3.5), one of two that never show together:
     - a new master password waiting to start working (§5.24): who asked, when, and about how many hours are left, with "Cancel it";
     - otherwise, when practice is past its grace period (§5.10), the standing banner of lock.md §13.4 item 6: "You have not typed your master password since 12 Sep. Without it you cannot open a backup or move to a new phone." Buttons "Type it now" and "I forgot it" (§5.24). It stays whenever the app is open, until a master password has been typed;
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
- **Two additions from the critique** (architecture.md D-11; search.md §1 and §2 specify them):
  - **Accent-free ş and ç.** The second tier also turns ş into s and ç into c, so "besiktas" finds "Beşiktaş" and "garcon" finds "Garçon", which keeps accent-free Turkish, Azerbaijani and French queries working [E: critique m1].
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
- **No search results** (after the layout retry): 'No accounts match "gmal".' and `TextButton` 'Add "gmal" as a new account', which opens Add with the title filled in. The name is where that form starts, not a change: Close or Back with nothing else typed asks nothing and keeps no draft.
- **Many entries**: tested with 5,000; stable keys; no images; frame time within 16 ms on a mid-range phone.
- **Long content**: titles wrap to 2 lines, then end with "…"; usernames take 1 line.
- **Error**: a failed save shows the snackbar "Couldn't save. Your phone's storage may be full." with "Try again"; the screen keeps the user's data.
- **Backup reminder banner**: "Your newest passwords are not in a backup yet." Buttons "Back up now" and "Later" (snooze 3 days; "Later" on "Make a backup now?" after a master password snoozes it as well, and the banner's "Later" holds that offer back: lock.md F12 step 2). It shows when all of these hold:
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
  - It **closes after 2 minutes without a touch**; any tap restarts the time. Reason: §3's "render secrets rarely and briefly" also applies here. Showing a whole password large is an accepted exception (architecture.md D-9d).
- **Closing.** It closes with back or "Close", and it is never kept after the screen stops.
- **Font** (architecture.md D-22): no bundled font in version 1. `FontFamily.Monospace`, the coloured digits and symbols, and one cell per character tell similar characters apart. No font was checked for U+02BB, Uzbek Cyrillic and its licence; Android's DroidSansMono lacks U+02BB [E: i18n.md §2.6].

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
- **Same as the master password** (architecture.md D-9b): Save does not compare the password with the master password, and nothing is compared while the user types.
  - The comparison runs only at each correct master-password entry, with the typed master password in memory (§5.10). A password saved now is therefore found at the next master-password entry, and then shows the banner of §5.12.
  - Reason: a check at Save would be one key derivation (64 MiB, about a second) per saved password with no waiting time, which is a way to test guesses at the master password outside the lock's limits (lock.md §6.8). Keeping the master password, or a fast hash of it, in memory for the purpose would be worse.
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
| **App lock** | Change PIN (when a PIN is set) or Set a PIN (when none is) | screen. There is no switch to turn the PIN off (lock.md F6) | — |
| | Fingerprint unlock | `Switch`, from Android 7 (lock.md §9.4). Under it: "Anyone whose fingerprint is saved in this phone's settings can open the app." When unavailable, the reason: "Set a screen lock and add a fingerprint in your phone's settings first" · "This phone has no fingerprint sensor that apps can use" · "Fingerprint can't be used in apps right now. In your phone's settings, check that fingerprint may be used to verify it's you in apps." · "Your phone needs a security update before fingerprint can be used" · "This phone can't keep a fingerprint key in protected hardware." When the phone's answer is "unknown" (Android 9 and 10), the switch stays enabled and turning it on decides. After a master-password change that did not renew it: "Fingerprint unlock: turn on again". On Android 12+ the name comes from the system (§5.7) | off |
| | Lock the app after leaving it | dialog with `RadioButton`s: Immediately · After 30 seconds · After 1 minute · After 5 minutes. Time in a system file picker or share sheet the app opened counts as time in the app (§4) | **30 seconds**, written at setup; a setting that is missing or cannot be read counts as "Immediately" (architecture.md D-7) |
| | (always) | Lock at once when the screen turns off; lock after 5 minutes without a touch while open | not configurable |
| | Clear copied text after | 30 seconds · 1 minute · 2 minutes · 5 minutes (no "Never") | **1 minute**, written at setup; 30 seconds when the setting is missing (D-7) |
| | Change master password | screen; includes "I don't remember it" (§5.24) | — |
| Backup & transfer | Make a backup | screen; supporting text "Last backup: 3 days ago, Google Drive" | — |
| | Backup reminders | `Switch` | on |
| | Export | screen | — |
| | Import | screen | — |
| | Old passwords (locked on 6 Oct 2026) | only when an archived vault exists: "Open" · "Delete" (§5.10) | — |
| Appearance & language | Language | dialog: System language · Oʻzbekcha · Ўзбекча · English · Русский | System language |
| | Appearance | dialog with two choices of `RadioButton`s ([looks/README.md](looks/README.md)). **Colours**: Bank blue · Emerald · Night vault · Phone's colours (the colours of the wallpaper; on Android 12 and later only), each with a small preview in its own colours. **Light or dark**: System · Light · Dark. A choice is applied at once, to the dialog too, which stays open until "Done". The row shows both choices ("Bank blue · System") | **Bank blue** and **System**; a stored style that is missing or unknown counts as Bank blue |
| Help | How your passwords are protected · If you lose your phone or move to a new one · If you forget your master password · If you forget your PIN · Open-source licences · Version | screens | — |

The group is called "App lock" (glossary term 69 in i18n.md), so that "Security" means only the tab.

**Defaults compared** [E: ux.txt]:
- lock: Bitwarden 15 min, Proton 2 min, KeePassDX 5 min, Keyguard 5 min;
- clipboard: Bitwarden and Keyguard never, Proton 60 s, KeePassDX 20 s.

The app is stricter on locking because the user asked for a bank-like lock. It keeps a short grace period because frequent unlocking is the classic complaint. In SOUPS 2025, 72 % of improvement wishes were about convenience: entering the primary password less often, and easier import. CCS 2019 participants turned off lock-on-exit ("it is annoying to constantly have to be unlocking the app when I'm going back and forth") and clipboard clearing [E].

**Change master password** (lock.md F8, in its order).
1. **Current master password.** Or `TextButton` "I don't remember it" → §5.24.
2. The new one (§5.3), written down (§5.4) and typed again (§5.5); the screen is kept on for the last two (lock.md §10.1).
3. **"Enter your app PIN to keep using it."** Only when a PIN is usable. The vault key changes, so the PIN must be confirmed to keep working [E: V-C5, V-S5]. It is a counted PIN check (lock.md §6.3). "Not now" is allowed; if the PIN cannot be checked right now, the flow goes on as after "Not now". Whenever the vault would end up without a PIN for its new key, "Choose a PIN" (skippable) follows the save.
4. "Saving…": the new copy is written, read back and opened with the second typing before anything else changes. If it does not prove: "Couldn't change your master password. Nothing was changed. Your old master password, PIN and fingerprint still work." If even that cannot be tidied up: "Saving may not have finished. Next time, try your new master password first, then your old one."
5. **"Touch the sensor to keep fingerprint unlock on."** Only when it was on. "Not now" or a closed prompt leaves Settings showing "Fingerprint unlock: turn on again".
6. **"Make a new backup now. Your old backups still open only with your old master password."** [Back up now] [Later] [E: crypto.md §11]. "Back up now" uses the master password just typed (§5.19).
   - If the change was started from the security check's card 1 (§5.16), the old password may be known to others. This screen then also lists the places where this app saved backups, with "Delete" where the place allows it, and says "Also delete copies you sent to Telegram or by email."

**Change PIN and Set a PIN** (lock.md F6, §8.2).
- **Change PIN** takes the current PIN ("Enter your current PIN", a counted check) or, with "Use master password instead", the master password → new PIN → confirm. The new PIN must differ from the current one when the current one was typed.
- **Set a PIN** (no PIN exists) needs the master password.
- A fingerprint touch never stands in for either.
- **There is no switch to turn the PIN off.** It can be skipped at setup and set later.
- After "Forgot PIN?" the app goes straight to "Choose a new PIN" instead (§5.9).

### 5.19 Make a backup

1. **Explanation** (first time only): "A backup is a locked copy of all your passwords. It opens only with your master password, on any phone."
2. **Master password, always** (lock.md F12, architecture.md D-6e).
   - The screen says: "Type your master password. This checks that you still know the password this backup will need." It is checked against the vault's own password slot, so a typo can never make a backup that the real password does not open, and it counts as practice. Each backup gets its own key from it (crypto.md §10–11, architecture.md §4.12).
   - **The one exception:** right after the master password was typed in the same flow (practice, a master-password unlock, a change, the end of §5.24) and a backup is due, the app offers "Make a backup now?" and uses that entry. Nothing else skips the step: a backup made by someone who has forgotten the password would be worthless and look safe.
   - No backup is made while a new master password is pending (§5.24): "You can make a backup when your new master password has started working." Such a backup would need a password nobody remembers.
   - The cost: the paper is kept at home, and typing a long passphrase for every backup makes backups rarer. The offer after each master-password entry is how the app makes up for it.
3. **File picker** (`CreateDocument`, suggested name `PasswordVault-backup-2026-10-06.pvault`). Google Drive appears in the picker when its app is installed. Time in the picker counts as time in the app (§4).
4. **"Saving…" then "Checking the saved file…"** The backup was made and opened with the master password before the picker opened (lock.md F12 step 4); the saved file is read back and must be that backup, byte for byte, before success is shown [E: storage.md §5.4; V-S6].
5. **Result**: "Backup saved: PasswordVault-backup-2026-10-06.pvault (52 accounts)." One line follows, which depends on the place, recognised by the document's provider:
   - **Phone storage** (the Downloads provider, or the main storage root of the external-storage provider): "This copy is on this phone. If the phone is lost, this copy is lost too. Keep a copy somewhere else." `Button` "Send to another app…".
   - **Every place that is not the phone**, Google Drive (Drive's document provider [I: check its authority on a device]) and anywhere else (SD card, USB drive, other apps): "This place may upload the file later. Open it there once to check that it arrived." That is the only line: the check after saving reads the copy the place keeps on this phone, and whether the place has sent it on cannot be seen from here (build-plan §12, decisions 8 and 20). Anywhere else has `OutlinedButton` "Send to another app…"; Google Drive has none.
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
  - the file picker (`CreateDocument`; time there counts as time in the app, §4). The vault notes the file just before it is written, so that the banner below also covers a file cut off by a crash;
  - "Saving and checking the file…": one line, because the read-back happens inside the write.
- **While a new master password is pending** (lock.md F12 step 2, formats.md §2 rule 1) nothing is exported: "You can export your passwords when your new master password has started working."
- **The Google card** on the CSV warning is a list: "Google Password Manager will skip:", one counted line per reason ("2 accounts with notes too long for Google": its limit is 1,000 bytes, about 500 Cyrillic letters, not 1,000 characters), then "They are still in the file. Other apps may accept them."
- **A CSV cut into parts** (formats.md §2): each part has its own "Save as", and one master password covers the whole export unless the app locks. After a part: "Saved file 1 of 2: passwords-2026-10-06-1.csv. When you're done with it, delete it." with the main button "Save file 2"; after the last, as below.
- **A failed write whose file the place cannot delete**: "The file passwords-2026-10-06.csv may hold some of your passwords. Delete it yourself in your Files app." (or "in Google Drive", or the place's name). The vault's note of the file then stays, so the banner keeps reminding.
- Plain exports are **not offered to the share sheet**; the user can still save and move them [I].
- **During a pending change** the exported password is the new, unconfirmed one, and the old one is kept (architecture.md D-10d, formats.md §2 rule 12): `pendingOldPassword` in the JSON, the hidden line "Old password" in the CSV block and in Excel's extra fields. Importing our own file brings both back.
- **Deleted accounts** (Recently deleted) are in no plain export (D-10e); the backup holds them.

**After export**:
- The screen says "Saved: passwords-2026-10-06.xlsx. When you're done with it, delete it."
  - `Button` "Done" (primary).
  - `OutlinedButton` "Delete it now" (`DocumentsContract.deleteDocument`; possible when the provider reports `FLAG_SUPPORTS_DELETE` [V: Android "documents and files" guide]). Afterwards the screen says only "passwords-2026-10-06.xlsx was deleted.", with "Done".

  The primary button is "Done" because the user just asked for this file.
- **A place that is not the phone** (Google Drive or any other) adds one line, that of §5.19 step 5: "This place may upload the file later. Open it there once to check that it arrived." (build-plan §12, decisions 8 and 20).
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
  - Extra fields go in a marked block at the end of the note (`--- PasswordVault (N lines) ---`, one line per field), which this app reads back. Its grammar is fixed (architecture.md D-10a, formats.md §4.4), so that every later version reads what version 1 wrote, and a block the user pasted into a note stays part of the note.
  - UTF-8 without BOM; rows end in CRLF.
- **The account's name.** Google ignores the `name` column and shows its own site name [E: V-A6; formats.md §4.2]. So when an account's name differs from its website's host, the block starts with a line `Title: <name>` (formats.md §4.4), and the importer turns the line back into the title. "Title" rather than "Name" avoids a clash with a user's own field called "Name"; a user's own field called "Title" is written so that it reads back as an ordinary field.
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
- **Text decoding** (formats.md §8.2, formats-review P2 and P12). The app never switches a file's encoding silently.
  - Text that is mostly UTF-8 is read as UTF-8, with or without a byte-order mark; a few damaged bytes become "�" and the preview lists those lines. Chrome writes UTF-8 without a BOM.
  - UTF-16 is recognised by its byte-order mark, or by a NUL in every other byte.
  - A file with bytes that are not UTF-8 is read as UTF-8 only when its valid UTF-8 letters (multi-byte sequences) outnumber those bytes, and otherwise as Windows-1251, which Russian-language Excel writes [I] (build-plan §12, decision 12): "Пётр" or "Тўлов" written by Excel must not come out as "�". The preview says which was chosen and why.
- The delimiter is detected among `,`, `;` and tab; Excel's first line `sep=;` is honoured and skipped.
- Header aliases cover Chrome, Bitwarden, KeePassXC (whatever the case) and Firefox, plus Russian and Uzbek headers in both scripts; apostrophes and unusual spaces in a header are ignored, so "Oʻzgartirilgan" and "O‘zgartirilgan" are one header.
- **The column-mapping screen** opens whenever no password column is recognised, and when a column is ambiguous. Each column is shown with its first value and an `ExposedDropdownMenuBox`: Name · Website · Username · Password · Notes · Skip.
- **Rows with the wrong number of values** are listed on the preview ("Line 2 has 7 values; the header has 5. Is there a comma inside a password? Put that value in quotes.") and left out unless the user confirms them (formats-review P4).
- Limits: 10 MB and 10,000 rows per file. An import that would make the vault too large for one file is refused before anything is saved: "Your passwords no longer fit in one file. Delete accounts you don't need, or shorten very long notes." (architecture.md §3.5).

**Preview** (before anything is saved):
- **Sample**: the names and usernames (never the passwords) of the first three accounts, preferring ones with non-Latin letters, so garbled text is visible. Below them, "Text looks wrong? Read as UTF-8".
- **Counts**:
  - "Found 48 accounts."
  - "3 are already here (same website, username and password) and will be skipped."
  - "2 have the same website and username but a different password; they will be added so you can compare."
  - "1 row has no password."
  - "1 row was skipped: password longer than 1,000 characters." Only a password over the limit leaves an account out (architecture.md D-10f).
  - "1 note was longer than 10,000 characters and was shortened." Other values over a limit move into the notes or the extra fields and are listed ("Gmail: 3 websites moved to notes").
  - Passwords are never changed: an account whose password holds a tab or another invisible character is imported as it is and listed ("Gmail: the password contains a tab") (formats-review P14).
  - A hidden value without a name is kept as a hidden field named "Hidden value 1", "Hidden value 2", in the file's order; it is never moved into the notes, where it would show (build-plan §12, decision 11).
  - A hidden value that a limit would move into the notes (a value over 1,000 characters, or a field beyond the 20 an account holds) is not imported either, and is listed: "Gmail: 1 hidden value was too long to keep hidden and was not imported" (build-plan §12, decision 20).
  - Our own JSON from a newer version of the app, with data this version does not know at the top of the file: "Some data from a newer version of the app will not be imported." That data is not kept (build-plan §12, decision 9).
- **Exact repeats.** Rows that repeat exactly (same website, username and password) are imported once.
- **Same login on related websites** [E: critique M6; V-A6]. Chrome writes one row per affiliated domain, so one login can arrive as several rows. Rows with the same username and password are kept as **one account with all its websites** only when:
  - (a) they are adjacent rows equal except the website (formats.md §8.3);
  - (b) one is an `android://` row and the other an `https://` row, and the app's package matches the site (com.instagram.android ↔ instagram.com);
  - (c) both sites are in one fixed sign-on family: Google (google.com, youtube.com, gmail.com), Microsoft (live.com, outlook.com, hotmail.com, microsoft.com), Yandex (yandex.ru, yandex.uz, yandex.com, ya.ru), Apple (apple.com, icloud.com).

  Nothing else is joined: Facebook and Instagram stay apart, because that kind of sharing is the reuse this app exists to fix. The preview says "6 rows were the same login on related websites and were kept together as 2 accounts." with `TextButton` "Keep them separate".
- **Tidy names** (`Switch`): on for other apps' files, off for this app's own files (our JSON, and our CSV with its fields block), which already hold the names the user chose (build-plan §12, decision 10; `ImportFile.fromThisApp`). Chrome makes up names such as "accounts.google.com" [E: V-A6].
  - The rule: strip "www.", "m.", "mobile.", "accounts.", "account.", "login." and "passport."; then drop ".com", ".ru", ".uz", ".org" or ".net" when one word is left, and capitalise that word.
  - Examples: accounts.google.com → Google, m.facebook.com → Facebook, passport.yandex.ru → Yandex. my.gov.uz stays my.gov.uz.
  - Two examples from the file are shown before and after.
- `Button` "Import 45 accounts".

**Result**: "45 accounts imported." `TextButton` "Undo this import" puts the vault back exactly as it was before the import (architecture.md D-15); it is available until the screen is left. It does not remove entries by id, because an imported file can carry the id of an account that was already here (formats-review P1); such an account keeps its own id, and the imported one gets a new id and is added as "Gmail (from file)" when it differs. Then:
- **For CSV and JSON**: "The file Chrome Passwords.csv still contains all these passwords unprotected. Delete it now?" `Button` "Delete file" (when supported) and "Keep".
- **If many imported passwords are shared**: "Many of these accounts share a password. Open Security to fix them one by one." `TextButton` "Open Security".
- **Master-password check.** Imported passwords are compared with the master password the next time it is typed (§5.10). Practice on day 1 usually comes soon after setup.

**Backup file into an existing vault**: the user types that backup's master password (a backup file's password never waits after wrong tries, lock.md §6.8). Entries with new ids are added; identical entries are skipped; an entry with the same id but different content is added as "Gmail (from backup)". Nothing is overwritten [I]. "Old passwords" › "Open" in Settings merges a vault that was put aside the same way, but under that vault's own waits (§5.10).

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
- **Where deleted accounts are.** They are left out of search, the list, the security check and plain exports. They stay inside the locked file, and in backups made meanwhile, until 30 days have passed.
- **When they are removed** (architecture.md D-24), in two steps, so that one unlock with the phone's date set a year ahead cannot empty Recently deleted for good:
  1. An unlock that finds accounts deleted 30 days or more ago (by the phone's clock) only arms the removal: it notes the time, and from then on counts the hours the phone is switched on, as the lock counts them (lock.md §6.9).
  2. A later unlock, once 24 such hours have been counted, removes the accounts that were already due when it was armed. Switching to another vault cancels the removal.

  The cost: an account is removed a day or more after its 30 days, never earlier.
- **Reason.** A lock within the snackbar's 10 seconds dropped the only undo, and the older copies the storage keeps are not reachable from the screens. Bitwarden and Proton Pass keep a trash for the same reason [E: critique m9].

### 5.24 Set a new master password (the old one is forgotten)

This is for a user who has forgotten the master password while the PIN or fingerprint still opens the app. Without this route, every backup becomes unopenable, and a later PIN lockout or a lost phone loses everything. lock.md F11 is the flow and its texts; this section is how it looks. In short: **choose the new master password first, then wait 24 hours with the phone switched on, then type it once more to finish.** Finishing makes a new vault and puts the old one aside, locked with the old master password, so that whoever asked cannot destroy the passwords as they were (lock.md F11 steps 15 to 18, architecture.md D-5).

**Entry points**:
- the "Forgot master password?" sheet (§5.10, the first option);
- the hint after 2 wrong master-password tries (§5.10);
- "I can't remember it" on a practice screen, and "I forgot it" on the banner of §5.11;
- Settings › App lock › Change master password › "I don't remember it".

**Steps**:
1. **Explanation**: "You can choose a new master password because your PIN still opens the app on this phone. For your safety it starts working after 24 hours with the phone switched on. Until then nothing changes. Backups you made before will still need your old master password." `Button` "Continue", `TextButton` "Cancel". ("PIN" reads "fingerprint" when no PIN exists.)
2. **Proof now**: the PIN typed now, whenever a PIN exists; it is a counted check, and on the lock screen it also opens the app. **The fingerprint only when no PIN exists** (lock.md F11 step 3): a sleeping owner's finger, or another person's saved finger, must not be enough to start a replacement. When fingerprint is usable the PIN prompt adds "Forgot your PIN as well? After 5 wrong tries the PIN is turned off, and you can then continue with your fingerprint." If the PIN cannot be checked right now: "Your PIN can't be checked right now. Try again later.", and the flow stops.
3. **Choose it**: §5.3, §5.4 and §5.5; the screen is kept on for the last two (§5.4).
4. **The wait.** "Your new master password starts working after about 24 hours with the phone switched on. Keep the paper. Until then, open the app with your PIN or fingerprint as usual. The countdown stops while the phone is switched off. To keep it moving, open this app in the morning and again in the evening." No date or clock time is ever promised, because only hours with the phone on count (lock.md §6.9).
   - After every unlock (lock.md §13.4 item 2): "A new master password was chosen on 6 Oct, 14:02, using the PIN. It starts working in about 20 hours. The countdown stops while the phone is switched off; open this app in the morning and again in the evening to keep it moving. Not you? Cancel it, then change your PIN." Buttons "Cancel it" and "OK". The list shows the same as a banner (§5.11). Cancelling needs nothing, because it is the safe direction.
   - A correct current master password typed anywhere cancels it: "You typed your master password, so the new one was cancelled."
   - Meanwhile practice is not asked, no backup can be made ("You can make a backup when your new master password has started working."), the last PIN try carries the warning of §5.9, and the master-password field says "Your new master password starts working in about 20 hours."
5. **Finish.** When the hours are counted, the notice and the banner become "Your new master password is ready. Type it to finish." Buttons "Finish now", "Later", "Cancel it". "Finish now" opens a screen where the new master password is typed once; the app stays open (lock.md F11 step 13). The lock screen's master-password field accepts it as well. No PIN and no fingerprint is asked for here, so an owner who lost both during the wait still gets in. If finishing fails: "Couldn't finish now. Nothing was changed. Try again later."
6. **New ways in** (lock.md F11 step 19). The new vault starts without a PIN or a fingerprint key: "Choose a PIN" and the fingerprint offer of §5.6 and §5.7 follow, as after a restore. The PIN typed in step 2 is not carried over.
7. **Back up**: "Make a new backup now. Your old backups still open only with your old master password." [Back up now] [Later].

**After a replacement** (lock.md §13.4 item 3, F16b). Until 7 days with the phone on have been counted, every unlock shows: "Your master password was replaced on 7 Oct using the PIN. If you didn't do this, your passwords from before are still on this phone." Buttons "OK" and "It wasn't me".
- "It wasn't me", the sheet's "Open my passwords from before 7 Oct 2026" (§5.10), and the line under a wrong master password (§5.10) all lead to the old vault's own lock screen: title "Your passwords from before 7 Oct 2026", "Type the master password you used then." Only its master-password field: it has no PIN or fingerprint, and its own waits apply.
- Once that password opens it, the app asks: "These are your passwords as they were on 7 Oct. Passwords added or changed after that are in the newer set, which stays on this phone. Open the older set instead?" Buttons "Open my passwords from 7 Oct" and "Cancel". Cancel changes nothing. If the two sets cannot change places (a storage error), the master-password field comes back with "Couldn't open your passwords from 7 Oct. Nothing was changed."
- After the swap: "Choose a new PIN. Someone else may know the old one.", then the fingerprint offer with its sentence about whose fingers are saved on the phone. The newer set stays on the phone, put aside, and "Open my previous passwords" brings it back (§5.1).

**The wait** is lock.md's decision (§6.9, F11; architecture.md D-5): 24 hours counted while the phone is switched on, which the date setting and reboots cannot shorten. Whether 24 hours suit a phone that is switched off at night is a question for the user (lock.md §18.7, architecture.md §8.6 item 12).

## 6. The copy flow

**Where**: the list row button (password), the detail screen (each field), the long-press menu, the guided change, previous passwords, extra fields, and text selected in notes.

**What happens on a copy** [E: platform.md §3.2, V-P2; V: Android "Copy and paste" guide]:
1. **The clip.** `ClipData.newPlainText("", value)` with an empty label.
   - The `ClipDescription` extras carry `android.content.extra.IS_SENSITIVE = true` for passwords, previous passwords, secret field values and anything copied from notes, which often hold security answers. The constant exists from API 33; the string works on all versions. The extra is false for usernames, websites and non-secret fields, so the Android 13+ preview confirms the right one was copied.
   - The same extras (`setExtras`, API 24 [V]) carry **a random 128-bit token** that identifies this copy.
2. **The clear job.** Any earlier clear job is replaced, and a new one is scheduled for the chosen delay (default 1 minute). The mechanism is architecture.md D-14, measured in the clipboard lab (`docs/lab/clipboard.md`): an alarm to a receiver that is not exported, plus a timer in the process; the alarm is exact where no permission is needed, and otherwise aimed so that its delivery window ends at the deadline. No exact-alarm permission and no WorkManager. The token and the deadline are also kept in the app's settings, so that an overdue clip of the app's own is cleared when the app next comes to the front. The job clears **only the app's own clip**. It reads the clip's description, never its content:
   - **Description readable, token differs or missing**: the user has copied something else since, so nothing is cleared.
   - **Description readable, token matches**: the clip is cleared (`clearPrimaryClip()` on API 28+; an empty clip below).
   - **Description not readable**: on Android 10+ a background app can neither read the clipboard nor learn that it changed [E: V-P2], so the clip is cleared anyway, as Proton fails closed [E: platform_code.md:34]. On Android 7–9 the check works while the phone is unlocked. While it is locked, Android 9 lets no app read the clipboard, so a clear that falls due then clears whatever is on it, as on Android 10+; Android 7 still answers then (measured by build plan 3.8's review; Android 8 not tested).

   When the app comes back to the front with a clear pending, it checks the same way. If the clipboard holds something else, it cancels the pending clear and hides the status row.
3. **Confirmation.** The row's icon becomes a check mark for 1.5 s, with `HapticFeedbackType.Confirm`.
4. **Feedback.** Android 13 and higher show their own confirmation, and Google "strongly recommend[s] removing toasts or snackbars shown after an in-app copy for Android 13 and higher" [V]. So:
   - **Android 13+**: no toast or snackbar. The **clipboard status row** (below) appears without a "copied" headline: "Password for Gmail · clears in 1:00". The system preview hides sensitive content.
   - **Android 7–12L**: the same row, with the headline "Password copied". This is the in-app feedback Google recommends for those versions [V].
5. **One helper.** All in-app copies go through one helper. For text selected in notes (architecture.md D-25), a `SecretClipboardBridge` is provided as `LocalClipboard` at the root (Compose UI 1.12 `Clipboard` [V: ui-android 1.12.1 bytecode]): its `setClipEntry` goes to the same helper, with the sensitive flag and the timed clear, and the status row says "Copied text · clears in 0:52"; its `getClipEntry` is the platform's clipboard unchanged, so pasting into the app's own fields, such as a password into the Add form, still works.

**Clipboard status row** (app-wide; part of the bottom-bar area, so the "Add" button moves above it):
- **Content**: a `Surface` (secondary container) with a key icon, the text "Password for Gmail · clears in 0:52" (or "Username for Gmail · …", or "Copied text · clears in 0:52" for text selected in a note), and `TextButton` "Clear now".
- **Announcements**: the countdown updates every second, visually only. TalkBack hears one polite live-region message when the row appears: "Password copied. It will be removed from the clipboard in 1 minute."
- **When the clear runs while the app is open**: "Clipboard cleared" for 2 s, then the row hides.
- **Late jobs**: a scheduled job may run late. If the planned time has passed and the clear has not run, the row says "Clearing…" instead of a negative countdown.

**When the clipboard is cleared**:
- when the timer ends, on "Clear now", and on "Lock now", each time only if the clip is still the app's own or cannot be checked;
- **never when the app goes to the background or locks automatically.** That would remove the password before the user can paste it, which is PocketVault's mistake [E: offline.md §6]. The timer bounds the exposure instead.

This departs from platform.md §1 item 3 ("clear on lock"), and architecture.md D-9c accepts it.

**What no app can do** (architecture.md D-14, measured in the clipboard lab): after a **force-stop**, Android cancels the app's alarms, and while the app's battery use is set to **"Restricted"** neither the alarm nor the timer runs once the app has been in the background for about a minute. In both cases the copy stays until the app is opened again, which then clears it. The first-copy tip and Help say so.

**First-copy tip** (once; an `AlertDialog` with "OK"):
- The main text: "Now open the app or website, touch and hold the password box, and tap Paste. The password is removed from the clipboard after 1 minute. Anything else you copy in that minute may be removed too. Never pin a password in your keyboard's clipboard: pinned items are never removed."
- Then: "If you force-stop this app, or set its battery use to 'Restricted', a copied password stays on the clipboard until you open the app again."
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
  - "If you force-stop the app, or set its battery use to 'Restricted', a copied password stays on the clipboard until you open the app again." (architecture.md D-14)
  - "Anyone whose fingerprint is saved in this phone's settings can open the app." (lock.md §9.4)
  - "What the app cannot protect you from: a phone that has been hacked or 'rooted', a harmful keyboard app, or an app you allowed to control your phone (accessibility)."
  - "On a rooted phone, someone can try every PIN in a few hours. Your master password still protects your passwords, so make it strong and never use it anywhere else." (lock.md §2.3)
  - "Never use 'Clear storage' to fix a problem with your PIN: it deletes your passwords, and the ones from before a replaced master password with them." (lock.md §16.2 item 12)
  - "Never install an app file (APK) sent to you in Telegram or offered by a website, even if it seems to come from someone you know. Never let an app control your phone (accessibility) unless you need it because of a disability."
  - "The app cannot tell you if a website leaked your password, because it does not use the internet."

  The local threat: Group-IB's Ajina.Banker campaign spread malicious APKs through Telegram channels, mostly aimed at users in Uzbekistan, and abused accessibility services [E: critique m11, group-ib.com/blog/ajina-malware]. "Install apps only from Google Play" was dropped, because this app itself may be installed from a file.
- *If you lose your phone or move to a new one*:
  - "Install PasswordVault on your new phone, tap 'Restore from a backup file', choose your newest backup and type your master password. Without a backup, your passwords cannot be brought back."
  - "If you moved your apps with 'Copy apps and data', open PasswordVault on the new phone and type your master password once. Then choose a PIN for the new phone."
- *If you forget your master password*:
  - "If your PIN or fingerprint still opens the app, open it and choose a new master password: Settings › App lock › Change master password › I don't remember it."
  - "The new master password starts working after 24 hours with the phone switched on. The countdown stops while the phone is switched off, so open the app in the morning and again in the evening. Until then, open the app with your PIN or fingerprint as usual. Then make a new backup." (lock.md F11)
  - "If someone else replaced your master password, your own still opens your passwords as they were: 'Forgot master password?', then 'Open my passwords from before …'." (lock.md F16b)
  - "If you can't open the app at all: try the words on your paper, or restore a backup whose master password you remember. You can also start with an empty app. Your old passwords stay on the phone, locked, in case you remember the password later."
- *If you forget your PIN*: "Tap 'Forgot PIN? Use master password', type your master password, and choose a new PIN. Never use 'Clear storage' to fix a problem with your PIN: it deletes your passwords, and the ones from before a replaced master password with them."

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
  - The app's own styles (Bank blue, Emerald, Night vault; [looks/README.md](looks/README.md)) are fixed colour schemes, each in light and in dark. In each of the six, every pair that text is drawn in has at least 4.5:1, every icon and every outline that carries meaning at least 3:1, and the letter of every avatar tone 4.5:1; a test checks every pair (`SchemeContrastTest`, `LooksTest`). "Phone's colours" is Material 3's dynamic colour, on Android 12 and later.
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
2. **Clearing.** With the app in the background, the clipboard is empty about 1 minute after the copy, also after the app was swiped from Recents or its process was killed. Test on Android 7, 9, 13 and 17, and on one Samsung and one Xiaomi phone if available.
   - Leaving the app or an automatic lock never clears it early.
   - After a force-stop, or with the app's battery use set to "Restricted", the copy stays until the app is opened, which then clears it (architecture.md D-14), as the first-copy tip and Help say.
   - A text copied after the password is not cleared on Android 7–9 (token check; on Android 9 only while the phone is unlocked at clear time, §6 item 2), nor on any version when the app is in front at clear time.
3. **Returning.**
   - Returning within 30 s shows the last screen with secrets masked.
   - Returning later shows the lock screen, and so does returning after the screen turned off while the app was open. When the screen turned off while the app was in the background, the lock applies when Android delivers the screen-off event, which Android 14+ may hold back until the app runs again; that case is best effort, and the lock delay still applies (lock.md §10.2, §2.3).
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
15. **Forgotten master password.** With a working PIN, a user who forgot the master password can choose a new one (§5.24), which starts working after 24 hours with the phone switched on and is finished by typing it once more; then a new backup is offered.
16. **No deletion from the lock screen.** No path that starts at the lock screen deletes a vault file. After "Start with an empty app", the old passwords open again from Welcome with their own PIN, fingerprint or master password, and from Settings with their master password.
17. **Lock-screen states.** Every state of §5.9 and §5.10 can be produced in a debug build and shows its text. A temporary Keystore error never turns fingerprint or PIN unlock off.
18. **Strength rules.** Toshkent2020, Tashkent2020, Ташкент2020, Mustaqillik1991, Samarqand2024!, Oʻzbekiston2024, Shahzoda2003! and "Assalomu alaykum" are refused as master passwords and score 2 or less (so they appear under "Easy to guess"). The suggested passphrase scores 4.
19. **Practice.** "Not now: use PIN" works throughout the grace period and is not asked again for 20 hours. After it, the master password is required, but "I can't remember it" and "Forgot master password?" still offer the PIN route.
20. **Recently deleted.** A deleted account can be restored after a lock and after a restart, and it is removed a day or more after its 30 days, by the two steps of §5.23; moving the phone's date forward does not remove it.

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
  - `BiometricManager.getStrings` 31;
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

Every question below has been decided in architecture.md §6 (D-n) or in lock.md; the answer follows each one. Two stay with the user (2, and the checks of 13).

1. **User.** Did "home lock like banking account" mean the app's own PIN or the phone's screen lock? **Answered:** the app's own PIN, settled by the user (D-2, lock.md §2.4).
2. **User.** Where are your passwords today (Google Password Manager, Chrome, Samsung Pass, Yandex Browser, an Excel file)? This sets the import build order (§5.21). **Still open** (D-3); until answered, the build order of §5.21 stands.
3. **Translations.** Who reviews the Uzbek and Russian translations of the warnings? **Answered:** the user reads the warning, limit and banner texts in their own languages before release (D-4, architecture.md §8.6 item 8). The alphabet question is settled for now in i18n.md §2.4.
4. **Lock design.** **Answered** by lock.md §16.1 (D-5):
   - PIN length and attempts: exactly 6 digits; the PIN turns off after 5 wrong in a row, or 10 that were not forgiven; no waiting times (§5.6, §5.9).
   - Fingerprint after the PIN is turned off: it stays usable (§5.9).
   - Practice grace: 1, 2 and 4 days in the first week, then 7 days; "Not now" lasts 20 hours (§5.10).
   - The wait for a new master password: chosen first, then 24 hours with the phone switched on, then typed again to finish (§5.24).
   - A fingerprint touch approves nothing except asking for a new master password when no PIN exists; "Change PIN" takes the current PIN or the master password (§5.18).
5. **Clipboard lab.** Does clearing from the background work on Android 7–17 and vendor builds? **Answered** by the lab (`docs/lab/clipboard.md`) and D-14: an alarm plus a timer, on time in every condition tested on the emulators, never after a force-stop or under "Restricted" battery use (§6). The status row says "Clearing…" when a clear is late. Vendor builds are checked on the user's phone (architecture.md §8.6 item 3).
6. **Crypto owner.** **Answered:**
   - (a) 6 words from the EFF short list (62 bits): yes (D-6a; §5.3).
   - (b) Spaces as the separator: white space is folded from the first release; the hyphen stays the suggested separator in 1.0 (D-6b; §5.3).
   - (c) Folding apostrophe-like characters in the master password: yes, 17 code points, after NFC (D-6c, lock.md §8.1; §5.3).
   - (d) Score 4 with the Uzbek and Russian dictionary for own master passwords: yes, for the password and for its search key (D-6d; §5.3).
   - (e) Backups without retyping for 14 days: no; a backup may use the entry typed a moment ago in the same flow (D-6e; §5.19).
   - (f) Restating crypto.md §11 step 6 as the practice rule: yes, lock.md §8.3 (D-6f; §5.10).
7. **Lock delay.** Is 30 s the right default? **Answered:** 30 seconds, written at setup; a missing or unreadable setting means "Immediately"; the clipboard delay is 1 minute, 30 seconds when missing (D-7; §5.18).
8. **Storage owner.** **Answered:** the `draft` object, at most one (D-8a); archiving, never pruned (D-8b); `pendingOldPassword` and `deletedAt` in the entry (D-8c); a backup is a new container sealed from the verified open vault, not a copy of the file (D-8d); the rollback data in the header and `state.rec` (D-8e).
9. **Security owner.** **Answered:** "Leave out of the security check", with the count of what is left out always shown (D-9a); the master-password reuse check at each correct master-password entry only, **not at Save** (D-9b; §5.10, §5.14); not clearing the clipboard on an automatic lock (D-9c; §6); "Show in big letters" as an exception, closing after 2 minutes without a touch and never restored (D-9d); the 10-minute grace for system screens (D-9e; §4).
10. **Formats owner.** **Answered** in architecture.md D-10a to D-10f and formats.md: the "Title:" line, in the block's grammar (D-10a; §5.20.2); the related-website rules, merging stopping at 10 websites (D-10b; §5.21); `pendingOldPassword` in JSON, `deletedAt` never exported (D-10c); during a pending change the new password with the old one kept (D-10d; §5.20); deleted accounts in no plain export (D-10e); notes cut on import instead of skipping the account, and only a password over the limit leaves an account out (D-10f; §5.21). formats.md §8.4 no longer says the security check combines accounts.
11. **Search and i18n owners.** **Answered** (D-11): search.md has ş → s and ç → c in the second tier and the wrong-layout retry (§5.11); new terms go into the string files with the screens that use them, and the lock's new terms are in i18n.md §3, section I.
12. **Plain exports.** Should they also be offered to the share sheet? **Answered:** no; the file picker only. The encrypted backup may be shared (D-12; §5.20).
13. **Real-world checks.** Who runs the checks this design depends on? **Answered:** the user, with the list in architecture.md §8.6 (D-13):
    - our CSV imported into Google Password Manager on the user's phone;
    - the `.xlsx` opened in the Excel, Google Sheets or WPS Office the user actually has;
    - the Chrome export path and the Settings route on the user's phone;
    - Google Drive's provider authority;
    - Gboard and Samsung Keyboard typing spaces in a password field.

## 15. Review: the critique of 2026-10-06

*This table records the revision made after the critique, before lock.md and architecture.md §6 were final. Where they decided otherwise (for example the wait of C2, the grace of M1, the fingerprint approval of M13), the sections above and §14 give the decision.*

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
