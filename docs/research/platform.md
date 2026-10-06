# Android platform security checklist: local password vault (minSdk 24, target 37, Compose)

Researched 2026-10-06. **How I know** tags: **[SDK]** local `android-37.0/data/api-versions.xml` (every platform API named below was also found with `javap` in the API 37 `android.jar`); **[AOSP]** `frameworks/base`, branch `android17-release`; **[DAC]** developer.android.com page fetched today (path given); **[AAR]** `javap` on the artifact from Google Maven; **[Repo]** source at HEAD today (Bitwarden 2026.9.1, KeePassDX 4.5.5, Proton Pass 1.41.2); **[M]** memory; **[I]** my inference. Path prefixes: `B/` = bitwarden/android `app/src/main/kotlin/com/x8bit/bitwarden/`, `K/` = KeePassDX `app/src/main/java/com/kunzisoft/keepass/`, `P/` = android-pass `pass/`. "Compose" means BOM 2026.09.00 = ui and foundation 1.12.1, material3 1.4.0 **[Maven POM]**.

## 1. Must-have for version 1

1. `FLAG_SECURE` on the activity window, always on; passwords masked until tapped, re-masked on stop.
2. Every vault text field opted out of autofill, secret fields kept out of saved state, keyboard privacy flags set (3.6).
3. Clipboard: sensitive extra, neutral label, timed clear, clear on lock.
4. Auto-lock from `ProcessLifecycleOwner` plus a monotonic clock; keys only in RAM; locked on every process start.
5. Explicit backup rules in both XML formats; Keystore-bound files in `noBackupFilesDir`; encrypted export and import through the Storage Access Framework.
6. Manifest: no `INTERNET`, one exported activity (`singleTask`, `taskAffinity=""`), no deep links, no WebView, `hasFragileUserData="true"`.
7. `filterTouchesWhenObscured` on the root view and every dialog; `setHideOverlayWindows(true)` on API 31+.
8. Atomic vault writes with a last-good copy; versioned format; never a destructive migration.
9. Release build: not debuggable, R8 on, no secrets in logs, one signing key for the app's whole life.
10. Edge-to-edge, predictive back and rotation handled (all enforced at target 37).

## 2. Surprises

- **Compose text fields are autofill fields by default.** In foundation 1.12.1 every state-based text field sets `ContentDataType.Text` and a fill action; `KeyboardType.Password`, `NumberPassword` and `BasicSecureTextField` add `ContentType.Password` unasked **[AAR: `TextFieldDecoratorModifierNode`, `BasicSecureTextFieldKt`]**. Compose "will automatically save credential information when a user navigates away" **[DAC /develop/ui/compose/text/autofill]**. So without an opt-out the phone's autofill service is offered our master password and entries **[I, untested]**.
- **`rememberTextFieldState()` is `rememberSaveable`**; it saves the text and undo history **[AAR]**, and Android "keeps a serialized copy of the data in memory, outside of your process" **[DAC /topic/libraries/architecture/saving-states]**. The official `SecureTextField` sample uses it.
- **Fingerprint unlock on Android 7 to 9 fails silently with the only stable biometric library.** `biometric` 1.1.0 and 1.2.0-alpha05 call `androidx.core` `FingerprintManagerCompat`, whose methods return `false` from core 1.18.0 **[AAR core 1.17.0 vs 1.18.0 and 1.19.1; DAC core release notes]**. Only 1.4.0-alpha07 carries its own shim **[AAR]**. Expect "no hardware" on API 24 to 27 and "unknown" on 28 **[I from bytecode, untested]**. `FingerprintManager` is removed from the API 37 SDK **[SDK]**.
- **A background app cannot check the clipboard before clearing it.** Reads need focus or the default keyboard; writes and clears do not **[AOSP `ClipboardService.clipboardAccessAllowed`]**. KeePassDX's "clear only if still ours" cannot fire once the user has switched apps **[I from `K/timeout/ClipboardHelper.kt:82-87`]**.
- **Target 37: `setContentCaptureEnabled(false)` no longer works**; `FLAG_SECURE` is the only opt-out **[DAC /about/versions/17/behavior-changes-17]**.
- **`allowBackup="false"` alone does not stop device-to-device transfer** on some manufacturers' devices at target 31+ **[DAC /identity/data/autobackup]**. The system performs backup uploads, so lacking `INTERNET` does not prevent them **[I]**.
- **`FLAG_SECURE` does not stop Pixnapping**, and no app-side mitigation is known (section 4).
- `overridePendingTransition` (deprecated in 34) blocks TapTrap; its replacement `overrideActivityTransition` does not **[TapTrap paper §6.1.2]**.

## 3. Checklist

### 3.1 Screen capture and recents

| Item | API | Level | Protects against | Limits | Reference apps |
|---|---|---|---|---|---|
| Secure window | `WindowManager.LayoutParams.FLAG_SECURE` | 1 **[SDK]** | Screenshots, recording, casting, recents thumbnail, Content Capture | Not the accessibility tree **[M]**; not Pixnapping; on API ≤ 30 keyboard taps can still be recorded on some devices **[DAC /security/fraud-prevention/activities]** | `B/MainActivity.kt:253`, `K/utils/AppUtil.kt:167`, `P/common-ui/api/.../ActivitySecureMode.kt`; a user setting in all three |
| Compose dialogs, popups, sheets | `securePolicy = SecureFlagPolicy.Inherit` (the default) **[DAC reference]** | ui 1.0 | Same, in child windows | Read when the window is set up **[androidx `AndroidDialog.android.kt`]** | No app overrides it **[Repo grep]** |
| Recents only | `Activity.setRecentsScreenshotEnabled(false)` | 33 | Thumbnail when screenshots are allowed | Recents only **[AOSP Javadoc]** | Proton, same file |
| Screenshot callback | `registerScreenCaptureCallback` + `DETECT_SCREEN_CAPTURE` | 34 | Lets us warn | Hardware-key screenshots only **[DAC /about/versions/14/features/screenshot-detection]**; "not invoked if the activity window has FLAG_SECURE" **[AOSP `Activity.java`]** | None |
| Recording callback | `WindowManager.addScreenRecordingCallback` + `DETECT_SCREEN_RECORDING` | 35 **[SDK]** | Lets us warn or re-mask | Detection only | None |
| Content sensitivity | `View.setContentSensitivity`; Compose `Modifier.sensitiveContent()` (ui 1.8) | 35 | Hides the window from app screen-sharing | Projection only; "default system screen recorder is exempted" **[DAC /about/versions/15/behavior-changes-all]** | None |

### 3.2 Clipboard

| Item | API | Level | Protects against | Limits | Reference apps |
|---|---|---|---|---|---|
| Sensitive flag | Boolean extra `"android.content.extra.IS_SENSITIVE"` in `ClipDescription.extras` | Constant `EXTRA_IS_SENSITIVE` 33; `setExtras` 24; the literal string works at every level **[SDK; DAC /privacy-and-security/risks/secure-clipboard-handling]** | Content in the Android 13+ copy preview | "Does not change clipboard behavior"; a rendering hint **[DAC reference]** | `B/data/platform/manager/clipboard/BitwardenClipboardManagerImpl.kt:46`, `K/timeout/ClipboardHelper.kt:121`, `P/clipboard/impl/.../ClipboardManagerImpl.kt:119` |
| Timed clear | `clearPrimaryClip()`; below 28 set an empty clip | 28 | Later paste elsewhere | Blind from the background; an in-process timer dies with the process, a scheduled job does not | Bitwarden: WorkManager one-shot, default "never" (`.../clipboard/ClearClipboardWorker.kt`). Proton: inexact `AlarmManager.set` + receiver (`ClearClipboardScheduler.kt`). KeePassDX: coroutine, 20 s default |
| System auto-clear | None needed | 13+ | Stale clips | One hour by default, re-armed on each read **[AOSP `DEFAULT_CLIPBOARD_TIMEOUT_MILLIS`; absent in the android11/12 branches]** | n/a |
| Read restriction | None needed | 10+ | Background sniffing | "The default IME is always allowed" **[AOSP]**; keyboards keep their own clip history **[Gboard help]** | n/a |

Scheduling facts: exact alarms need a special access that is denied by default since Android 14 **[DAC /about/versions/14/behavior-changes-all]**; inexact alarms may fire up to an hour late on 12+ **[DAC /develop/background-work/services/alarms/schedule]**; WorkManager 2.12.0 merges `WAKE_LOCK`, `ACCESS_NETWORK_STATE`, `RECEIVE_BOOT_COMPLETED`, `FOREGROUND_SERVICE` **[AAR manifest]**.

### 3.3 Auto-lock

| Item | API | Level | Protects against | Limits | Reference apps |
|---|---|---|---|---|---|
| Background | `ProcessLifecycleOwner`, `ON_STOP` | lifecycle-process 2.11.0 | Unlocked vault left behind; also fires at screen-off | 700 ms late by design **[androidx source]** | Bitwarden `data/src/main/kotlin/com/bitwarden/data/manager/appstate/AppStateManagerImpl.kt`; `K/app/App.kt`; `P/common-ui/impl/.../PassAppLifecycleObserverImpl.kt` |
| Timeout | `SystemClock.elapsedRealtime()` stamped at stop, checked at start | 1 | Long absence | In-process timers are unreliable: Android 14 disallows background work "shortly after" a process is cached **[DAC 14 all-apps]** | Bitwarden: `delay` plus an `ACTION_SCREEN_ON` receiver (`B/data/vault/manager/VaultLockManagerImpl.kt:753`). Proton adds `Settings.Global.BOOT_COUNT` (`P/biometry/api/.../NeedsAuthChecker.kt`). KeePassDX: alarms and wall clock (`K/timeout/TimeoutHelper.kt`) |
| Screen off | `ACTION_SCREEN_OFF`, context-registered | 1 | Immediate lock | Queued while the app is cached on 14+ **[DAC]** | `K/utils/BroadcastAction.kt:64` |
| Process death | Keys never persisted | n/a | Everything in RAM | Restored navigation must land on the lock screen **[I]** | Bitwarden locks on first creation |
| Key wiping | `ByteArray.fill(0)`, `TextFieldState.clearText()` | n/a | Memory scraping | Best effort on a garbage-collected heap **[M]**; `mlock` capped at 64 KB on 14+ **[DAC]** | Cure53 2024, BWN-11-006: Bitwarden's master password was not cleared |

### 3.4 Backup and transfer

| Item | API | Level | Protects against | Limits | Reference apps |
|---|---|---|---|---|---|
| Cloud and transfer rules | `android:dataExtractionRules` (`<cloud-backup>`, `<device-transfer>`) | 31 | Vault leaving the phone unintentionally | A missing section means "fully enabled" **[DAC]** | Bitwarden excludes all nine domains in both (`app/src/main/res/xml/data_extraction_rules.xml`) |
| Legacy rules | `android:fullBackupContent` | Devices on 23 to 30 | Same on Android 7 to 11 | Still required as a second file **[DAC]** | Bitwarden `backup_rules.xml`; KeePassDX `old_backup_rules.xml` |
| Never-backed-up directory | `Context.getNoBackupFilesDir()` | 21 | Restoring files that cannot be decrypted | n/a | n/a |
| Encrypted-only cloud | `disableIfNoEncryptionCapabilities="true"` | 31 | Unencrypted cloud copy | Cloud only | KeePassDX `res/xml/backup_rules.xml:21` |

Keystore keys are not restored: "When restoring the file it is likely the key used to encrypt it will no longer be present" **[DAC reference, `EncryptedSharedPreferences`; that library is deprecated since 1.1.0]**. KeePassDX backs up settings but excludes the database holding its Keystore-encrypted credentials. Proton sets only `allowBackup="false"`. **Right setting for us [I]:** encrypt the vault file only under the master-password key so it stays portable; exclude everything from cloud backup; include only the vault file in `<device-transfer>`; make the legacy rules exclude everything; keep the biometric blob in `noBackupFilesDir`.

### 3.5 Overlays, tapjacking, accessibility

| Item | API | Level | Protects against | Limits | Reference apps |
|---|---|---|---|---|---|
| Touch filter | `View.setFilterTouchesWhenObscured(true)`; Compose: set it on `LocalView.current` **[DAC blog, "Stop malware from snooping on your app data"]** | 9 | Taps passed through an overlay | Only `FLAG_WINDOW_IS_OBSCURED`; a parent filters its whole subtree **[AOSP `ViewGroup.dispatchTouchEvent`]**; dialogs are separate windows **[I]** | `B/MainActivity.kt:114` (decor view); KeePassDX in 23 layouts |
| Partial occlusion | `MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED`, via `pointerInteropFilter` | 29 | Overlay beside the target | "No default protections"; can break benign overlays **[DAC /privacy-and-security/risks/tapjacking]** | `P/autofill/impl/.../ObscuredTouchModifiers.kt` |
| Hide overlays | `Window.setHideOverlayWindows(true)` + `HIDE_OVERLAY_WINDOWS` (normal) | 31 | Fake prompts drawn over us | Throws without the permission **[AOSP `Window.java`]**; also hides benign overlays **[I]** | `P/autofill/impl/.../AutofillActivity.kt:79` only |
| Accessibility restriction | `android:accessibilityDataSensitive`; Compose `semantics { isSensitiveData = true }` **[AAR ui 1.12.1]** | 34 **[SDK]**; Google presents it as Android 16 | Non-tool accessibility services reading or clicking | Implied by `filterTouchesWhenObscured` **[AOSP `View.calculateAccessibilityDataSensitive`]**; a service declaring itself a tool passes | None |
| TapTrap | `launchMode="singleTask"` **[I]** | 1 | Animation-hidden launch (needs a same-task launch) **[paper §6.1]** | Fixed in the December 2025 patch for Android 13 to 16 **[taptrap.click]**; older devices stay exposed | Bitwarden: `singleTask` below API 30 only; Proton and KeePassDX launchers are `singleTop` |

### 3.6 Text input in Compose

| Item | API | Level | Protects against | Limits | Reference apps |
|---|---|---|---|---|---|
| Password field | `SecureTextField`, `OutlinedSecureTextField`; `BasicSecureTextField` | material3 1.4.0 | Disables cut, copy, drag; no autocorrect; `KeyboardType.Password` **[DAC reference]** | Pass `textObfuscationMode` explicitly: the 1.4.0 default is `RevealLastTyped` **[AAR material3 1.4.0]** | Bitwarden uses its own field with a visual transformation |
| No learning | `EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING`, via `InterceptPlatformTextInput` | 26 | Keyboard learning names and notes | `KeyboardOptions` has no such option **[androidx `EditorInfo.android.kt`]**; the keyboard may ignore it **[M]** | Bitwarden `ui/src/main/kotlin/com/bitwarden/ui/platform/components/field/interceptor/NoPersonalizedLearningInterceptor.kt`, applied app-wide; `K/view/TextEditFieldView.kt:50` |
| Autofill opt-out | `semantics { contentDataType = ContentDataType.None }` | ui 1.12 **[DAC release notes; AAR]** | Fill and save offers | The node still appears if a structure is collected **[androidx source]** | No Compose app does it |
| Window opt-out | `decorView.importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS` | 26 | Structure collection | A parent overrides Compose's forced `YES` **[AOSP `View.isImportantForAutofill`]**; untested with Compose **[I]** | KeePassDX layouts, e.g. `res/layout/activity_entry_edit.xml:25` |
| Secret state | `remember { TextFieldState() }` | n/a | Password in saved state | Lost on rotation unless held in a ViewModel | n/a |

### 3.7 Manifest

| Item | API | Level | Protects against | Limits | Reference apps |
|---|---|---|---|---|---|
| No network | Omit `android.permission.INTERNET` ("open network sockets", gid `inet`) **[AOSP]** | 1 | Exfiltration by our code or a library | A library can merge it in, so inspect the merged manifest; intents and Auto Backup remain **[I]** | KeePassDX declares no `INTERNET` |
| Exported | Explicit `android:exported`; launcher only | Required at 31 **[DAC 12]** | Authentication bypass through components (TeamSIK SIK-2016-025) | n/a | Bitwarden adds `intentMatchingFlags="enforceIntentFilter"` (36) |
| Task hijacking | `taskAffinity=""`, `singleTask` | 1 | StrandHogg | Google's stated fix is "minSdkVersion 30"; app settings only partly mitigate **[DAC /privacy-and-security/risks/strandhogg]** | Proton `app/src/main/AndroidManifest.xml:17` |
| Keep data on uninstall | `android:hasFragileUserData="true"` | 29 | Accidental wipe | A prompt only **[DAC /guide/topics/manifest/application-element]** | None use it |
| Debuggable | `android:debuggable` false | 1 | Debugger; `adb backup` of app data **[DAC 12]** | n/a | All |

### 3.8 Files

| Item | API | Level | Protects against | Limits | Reference apps |
|---|---|---|---|---|---|
| Internal storage | `Context.getFilesDir()` | 1 | Other apps | Not root **[DAC /privacy-and-security/risks/sensitive-data-external-storage]** | All |
| Export, import | `ActivityResultContracts.CreateDocument`, `OpenDocument` | 19 | Needing a storage permission: "doesn't require any system permissions" **[DAC /training/data-storage/shared/documents-files]** | The file outlives the app, so it must be encrypted | Bitwarden `ui/.../platform/manager/IntentManagerImpl.kt:192,221`; `K/activities/helpers/ExternalFileHelper.kt:148` |
| Temporary files | Stream straight to the picked URI; otherwise `cacheDir`, then delete | n/a | Plaintext residue | n/a | KeePassDX saves through `cacheDir` (`K/database/action/SaveDatabaseRunnable.kt:68`) |
| Atomic write | `android.util.AtomicFile` | 17 **[SDK]** | Torn writes | n/a | n/a |

### 3.9 Behaviour changes, Android 14 to 17 [DAC /about/versions/N/behavior-changes-all and -N]

| Version | Change | Effect on us |
|---|---|---|
| 14 | Exact alarms denied by default; cached apps stop background work; context-registered broadcasts queued; foreground-service types required | Do not build lock or clipboard timing on exact alarms or services |
| 15 | Edge-to-edge enforced at target 35; 16 KB page devices; force-stop cancels pending intents | Handle insets; any native library must be 16 KB aligned (pure Kotlin already is) |
| 16 | Edge-to-edge opt-out removed; predictive back on and `onBackPressed` no longer called; orientation locks ignored on large screens | Use `BackHandler`; survive rotation without saving secrets |
| 17 | Content Capture opt-out removed; large-screen opt-out removed; app memory limits; keyboard not restored after rotation; password characters hidden with physical keyboards | Test the key-derivation memory setting against the limiter; re-request focus after rotation |

New in API 37 **[SDK]**: `Activity.setHandoffEnabled` (cross-device handoff, opt-in: never enable it); `AlarmManager.setExactAndAllowWhileIdle` with a listener; `StrictMode` `detectImplicitUriPermissionGrant()`. No new screen-capture or clipboard defence.

### 3.10 Biometrics

Versions **[Maven; DAC release notes]**: stable 1.1.0 (January 2021); latest 1.4.0-alpha07 (22 April 2026). The 1.4 line adds `registerForAuthenticationResult()`, `AuthenticationRequest` and `biometric-compose` `rememberAuthenticationLauncher`. The classic `BiometricPrompt(FragmentActivity, …)`, `PromptInfo` and `CryptoObject` exist in every version and need a `FragmentActivity` host. Bitwarden and Proton pin 1.2.0-alpha05; KeePassDX 1.1.0.

| Rule | Source |
|---|---|
| `BIOMETRIC_STRONG` = Class 3, `BIOMETRIC_WEAK` = Class 2, `DEVICE_CREDENTIAL` = PIN, pattern, password | DAC reference |
| `DEVICE_CREDENTIAL` alone is unsupported below API 30; `BIOMETRIC_STRONG or DEVICE_CREDENTIAL` is unsupported on 28 and 29 | DAC reference |
| `CryptoObject` cannot be used with Class 2, nor with device credential below API 30 | DAC reference |
| `setNegativeButtonText` and `DEVICE_CREDENTIAL` are mutually exclusive | DAC guide |
| Auth-bound keys die when the lock screen is removed; per-use keys also on a new enrolment unless `setInvalidatedByBiometricEnrollment(false)` | AOSP `KeyGenParameterSpec` Javadoc |

DAC reference: `/reference/androidx/biometric/BiometricPrompt.PromptInfo.Builder` and `BiometricPrompt`; guide: `/identity/sign-in/biometric-auth`.

Common mistakes: treating `onAuthenticationSucceeded` as a boolean (Cure53 2024, BWN-11-005: Bitwarden bypassed with Frida); making the Keystore key the only way in; not catching `KeyPermanentlyInvalidatedException` (some manufacturers throw plain `InvalidKeyException`, per `P/biometry/impl/.../BiometricEnrollmentGuard.kt`). Bitwarden's pattern: AES Keystore key, `setUserAuthenticationRequired(true)`, Class 3 only, `CryptoObject` (`B/data/platform/manager/BiometricsEncryptionManagerImpl.kt:108`). Proton's prompt has no `CryptoObject`.

### 3.11 Autofill and Credential Manager

- **Autofill service** (API 26; inline suggestions 30): parse `AssistStructure`, build datasets, unlock through an authentication intent, handle saves. **Credential Manager provider** (API 34): a two-phase service plus passkey cryptography and origin checks **[DAC /identity/autofill/autofill-services, /identity/sign-in/credential-provider]**.
- Pitfalls: the service "must verify" signing certificates and use Digital Asset Links for web domains **[AOSP `AutofillService` Javadoc]**, which needs a network we will not have. Package-name matching was exploited (TeamSIK SIK-2016-033; Aonzo et al., CCS 2018). AutoSpill (Black Hat EU 2023) leaked credentials to the host app when filling a WebView login in most managers tested. KeePassDX matches on package name and web domain; I found no certificate check in `K/credentialprovider/autofill/`.
- Size **[Repo count, main sources]**: autofill 1,874 lines (KeePassDX), 6,696 (Bitwarden), 14,745 (Proton); credential provider 3,271, 3,318, 8,027.
- **Version 1: no.** It is the most attack-exposed code, and its safety rests on verification we cannot do offline.

### 3.12 Root, tamper detection, R8

Theatre: root or debugger checks (none of the three apps has any **[Repo grep]**; Bitwarden marked Cure53's finding "Accepted"), obfuscation as secrecy (Proton's F-Droid build uses `-dontobfuscate`; KeePassDX ships unminified), Play Integrity (needs a server **[M]**). Worth doing: R8 shrinking to strip logging **[DAC /privacy-and-security/risks/log-info-disclosure]**, dependency lockfiles (Bitwarden has three), a debug-only `StrictMode` policy.

## 4. Threat model

**We defend against:** a copy of the vault file (backup, export, transfer) under offline guessing; a thief or finder of the locked phone; someone briefly holding the unlocked phone; ordinary sandboxed apps using the clipboard, overlays, intents or screen capture; onlookers and screen sharing; data loss.

**We do not defend against, and should say so:** root or a compromised OS; a malicious keyboard (it sees every keystroke and the clipboard); accessibility malware the user enabled; coercion; a weak master password against a stolen file; GPU side channels on unpatched phones. Saying so is honest because no app-level control survives these and detection is bypassable. KeePass takes the same position for CVE-2023-24055 **[NVD]**, and TeamSIK's complaint was precisely that vendors advertised "bank-level" security.

**Pixnapping (CVE-2025-48561):** a zero-permission app reads pixels another app renders, through window blur and a GPU timing channel. It recovered Signal messages "even with Signal's Screen Security feature enabled", which is `FLAG_SECURE` **[paper; Signal source]**. "We are not aware of mitigation strategies to protect apps", and "secret information that is not visible … cannot be stolen" **[pixnapping.com, last updated 13 October 2025]**. Our only lever is to render secrets rarely and briefly **[I]**.

**Home-built vault mistakes [TeamSIK 2017 advisories unless noted]:** master password stored in plaintext (SIK-2016-027, -032) or under a hard-coded key (-021, -022); `allowBackup` left on (-021); exported activities that skip authentication (-025, -026); built-in browsers reading private files (-019, -028, -041); unencrypted titles and URLs (-040; LastPass 2022); clipboard never cleared; package-name-only autofill (-033); low or legacy key-derivation settings (LastPass accounts at 1 to 5,000 iterations **[palant.info]**; press reports tie a $150M theft to cracked vaults **[secondary]**); a predictable password generator (CVE-2020-27020); typed master password recoverable from memory (CVE-2023-32784).

**Data-loss failure modes**

| Failure | Mitigation |
|---|---|
| Forgotten master password | No recovery exists: say so at setup, confirm by retyping, offer a printable recovery key, and re-ask for the master password periodically even with biometrics **[I]** |
| Lost phone, no backup | Encrypted export with reminders; optional device-to-device transfer of the vault file |
| Keystore invalidation | The Keystore key wraps only a convenience copy of the vault key; the master password always works; delete and re-enrol on invalidation |
| Corrupt write | Temp file, fsync, rename; keep the previous generation; verify by decrypting before replacing |
| Failed migration | Version the format; migrate a copy; keep the old file until the new one opens; fixture tests for every released version |
| Uninstall or "clear storage" | Deletes files and Keystore keys **[AOSP `RemovePackageHelper`]**. Use `hasFragileUserData`, export reminders, and a stable signing key so an update never forces a reinstall |

## 5. Priorities

- **Must (version 1):** section 1.
- **Should:** biometric unlock (crypto-bound, Class 3 only, master-password fallback); inactivity lock while in the foreground (KeePassDX does this); partial-occlusion filter; `isSensitiveData`; recovery sheet; export reminder.
- **Later:** autofill service, then Credential Manager; screenshot and recording callbacks; an "allow screenshots" setting with `sensitiveContent`; a non-blocking root warning.

## Lessons for our app

- Compose leaks by default: autofill semantics, saveable state and keyboard learning each need an explicit opt-out.
- `FLAG_SECURE` is necessary and now doubles as the Content Capture switch, but it is one layer.
- The master password must be the only root of trust; Keystore and biometrics are conveniences that can vanish.
- Timers are best-effort on modern Android: decide lock state from a clock when the app resumes.
- Backup configuration is part of the data format: two XML files and a rule for Keystore-bound files.
- "No network permission" is a checkable promise only if we also verify the merged manifest and the backup rules.
- Copy the reference apps' file-level patterns, not their defaults (Bitwarden never clears the clipboard by default).

## Not settled

- Biometric version: 1.4.0-alpha07 (works on API 24 to 28, alpha API) or stable 1.1.0 with biometrics effectively API 29+. Needs an emulator test on API 24 to 29.
- Whether the decor-view opt-out fully hides Compose's virtual autofill nodes; test with Google autofill enabled.
- Cloud Auto Backup of the encrypted vault: an owner decision between "never leaves the phone" and automatic disaster recovery.
- Clipboard-clear scheduling: WorkManager (four extra permissions), inexact alarm (up to an hour late), or in-process only plus the system's one-hour clear.
- Whether keyboards keep `IS_SENSITIVE` clips in their own history: no primary source found.
- Whether the December 2025 follow-up closed the Pixnapping workaround: not confirmed from a primary source.
- Whether manufacturers' uninstall dialogs honour `hasFragileUserData`.

## Sources not given inline

DAC paths are relative to https://developer.android.com (blog post: `/blog/posts/enhancing-android-security-stop-malware-from-snooping-on-your-app-data`). AOSP: https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android17-release/ (`services/core/java/com/android/server/clipboard/ClipboardService.java`, `core/java/android/view/View.java`, `core/java/android/app/Activity.java`, `services/core/java/com/android/server/pm/RemovePackageHelper.java`, `core/java/android/service/autofill/AutofillService.java`). Maven: https://dl.google.com/dl/android/maven2/. TapTrap: https://taptrap.click and https://www.usenix.org/system/files/usenixsecurity25-beer.pdf. Pixnapping: https://www.pixnapping.com. TeamSIK: https://team-sik.org/sik-2016-027/ (same pattern for other numbers). Cure53 report: linked from https://bitwarden.com/help/is-bitwarden-audited/. AutoSpill: https://i.blackhat.com/EU-23/Presentations/EU-23-Gangwal-AutoSpill-Zero-Effort-Credential-Stealing.pdf. Aonzo et al.: https://www.s3.eurecom.fr/projects/modern-android-phishing/. LastPass iterations: https://palant.info/2022/12/28/lastpass-breach-the-significance-of-these-password-iterations/. CVEs: https://nvd.nist.gov/vuln/detail/ plus the CVE number. Gboard: https://support.google.com/gboard/answer/10742542.
