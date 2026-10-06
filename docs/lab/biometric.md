# Biometric unlock lab: fingerprint-only Keystore key on Android 17 (API 37)

Date: 2026-10-06. **Every device observation in this report was made on API 37**: the `Medium_Phone_API_37.0` emulator started with `-read-only` on port 5564 (build `google/sdk_gphone64_x86_64/emu64xa:17/CE2A.260420.019/15611780:user/release-keys`, x86_64, Google Play image). It was cold-booted three times: boot A for the androidx.biometric 1.1.0 experiments, boot B for 1.4.0-alpha07 and the final class, and boot C for one extra check. Nothing ran on API 24–36, and anything said about those levels is labelled **not tested**. The emulator on port 5564 is stopped.

**REPORT.md was not saved.** The environment refused the write with `Subagents should return findings as text, not write report files`, so this text is the report. Please save it to `lab/biometric/REPORT.md` yourself.

Lab app: a copy of the starter project in `lab/biometric/project`, with namespace and applicationId `com.example.pvlab.bio`. Passing `-PbiometricVersion=1.4.0-alpha07` builds a second app, `com.example.pvlab.bio.b14`, so both can be installed side by side. The host is a `FragmentActivity` that calls `enableEdgeToEdge()` and `setContent {}`. Commands arrive as `adb shell am broadcast -a com.example.pvlab.bio.CMD -p <pkg> --es cmd <name>` on a receiver registered in `onCreate`, which drives the app without disturbing the activity lifecycle. Every event is logged under the tag `PVBIO`, with the activity instance id so recreation is visible. Logs are in `lab/biometric/logs/`, screenshots in `lab/biometric/shots/`, and APKs in `lab/biometric/apks/`. All paths are under `/tmp/claude-1000/-home-asus-AndroidStudioProjects-PasswordVault/096e95f8-3ae0-41bf-b235-08771bb88852/scratchpad/`.

## Answers at a glance (API 37 emulator)

| # | Question | Answer | Seen on device? |
|---|---|---|---|
| E1 | Unattended PIN and fingerprint enrolment | `enroll_fingerprint.sh PORT [PIN] [FINGER]` works on a factory-fresh device in about 28 s and exits 0. It exits 1 if enrolment is not confirmed, 2 if there is no device, 3 if the PIN is wrong. | yes |
| E1 | Is `finger remove` or a pause needed? | Yes, one or the other. Three `finger touch` commands sent within 4 ms were merged into one sensor event. Touches 1 s apart, or `touch` followed by `remove` with no pause, were each counted. The script sends `touch` then `remove`. A first enrolment takes 3 touches; "Add another" takes 2. | yes |
| 1 | Happy path | Enable: init ENCRYPT, prompt, `onAuthenticationSucceeded(type=2)`, `doFinal(32 B)` gives 48 B plus a 12 B IV. Unlock: init DECRYPT, prompt, `doFinal` returns the same 32 bytes (MATCH). | yes |
| 2 | Init before auth, doFinal without auth | `Cipher.init(ENCRYPT_MODE)` succeeds before authentication, and the IV is already available. `doFinal` without auth throws `javax.crypto.IllegalBlockSizeException` caused by `android.security.KeyStoreException: Key user not authenticated` (public code 2, internal -26). **Turning the option on needs a prompt.** | yes |
| 3 | Caller-supplied IV | `java.security.InvalidAlgorithmParameterException: Caller-provided IV not permitted` from `Cipher.init`. | yes |
| 4 | Wrong finger, then 5 wrong | Each wrong finger fires `onAuthenticationFailed()` and the prompt stays open. When the sensor locks out, the prompt switches itself to an "Enter your PIN" screen and **no callback reaches the app**. Cancelling that screen gives 10 `ERROR_USER_CANCELED`. A prompt started during a timed lockout fails at once with 7 `ERROR_LOCKOUT`. **Five failed attempts in a row lock the whole phone** (Android's adaptive auth), and the app gets 10. The emulator HAL's lockouts lasted 1.5–8.5 s (real devices: not tested). | yes |
| 5 | Cancelling | Back → 10. The prompt's X button → 10. Negative button → 13 with the button text as message. `cancelAuthentication()` → 5. **Tapping outside does nothing**: the prompt stays open and no callback arrives. | yes |
| 6 | New finger, all fingers deleted, PIN removed | Enrolling another finger → `KeyPermanentlyInvalidatedException("Key permanently invalidated")` at `Cipher.init`, in both modes. Deleting all fingers → the same exception. Deleting one of two fingers → nothing happens. Removing the PIN → the system deletes the fingerprints and the key, so `getKey()` returns null and `containsAlias` is false. | yes |
| 7 | Hardware backing, StrongBox, UnlockedDeviceRequired | On this emulator `securityLevel=0` (SOFTWARE) and `isInsideSecureHardware=false`. StrongBox → `StrongBoxUnavailableException: Failed to generate key`. `setUnlockedDeviceRequired(true)` works while the device is unlocked. While it is locked, `Cipher.init` throws `InvalidKeyException: Keystore operation failed` caused by `KeyStoreException: Device locked`. | yes |
| 8 | USE_BIOMETRIC | It is merged from the library (`USE_BIOMETRIC` and `USE_FINGERPRINT`), so the app declares nothing. | yes (merged manifest) |
| 9 | FragmentActivity + setContent + edge-to-edge, AppCompat or platform theme | Both work: same system prompt, success and MATCH, no crash. `adb screencap` is black (secure window). `adb emu screenrecord screenshot` captures the prompt. | yes |
| 10 | Rotation while showing | The prompt survives and re-lays out. The callback reaches the **new** activity instance, and the CryptoObject cipher still decrypts (MATCH), as long as `BiometricPrompt` is constructed in `onCreate`. If it is built lazily, the result goes to the old, destroyed activity's callback. | yes |
| 11 | Home while showing | The system closes the prompt and the app gets 10 at once. Coming back shows no prompt and no further callback, and a new unlock works. `authenticate()` while the activity is stopped is **silently dropped**: no prompt and no callback ever. | yes |
| 12 | canAuthenticate(BIOMETRIC_STRONG) | No PIN → 11. PIN but no finger → 11 (`DEVICE_CREDENTIAL` alone → 0). Finger enrolled → 0. PIN removed → 11. "Verify it's you in apps" switched off → 21 with lib 1.1.0, 1 with 1.4.0-alpha07. During a permanent lockout → still 0. | yes |
| — | 1.4.0-alpha07 | Same as 1.1.0 for the happy path, case 6 (a, b, c), cancel codes, rotation, Home, `authenticate()` while stopped, and the lockout-PIN trap. One difference: with fingerprint-for-apps off, 1.4 reports 1 `HW_UNAVAILABLE` where 1.1.0 reports 21 from canAuthenticate and 8 `ERROR_VENDOR` from the prompt. | yes |

Five extra findings change the design (details in their own section):
- (a) Only the **last** `BiometricPrompt` constructed on an activity receives callbacks.
- (b) If the user types the PIN on the prompt's lockout screen and then touches the right finger, `onAuthenticationSucceeded` arrives but `doFinal` fails with "Key user not authenticated". This happened 4 times out of 4.
- (c) Android locks the phone after 5 failed fingers in a row.
- (d) The user can turn off "Use fingerprint to verify it's you in apps" in system settings.
- (e) A second `authenticate()` while a prompt is open is ignored.

---

## Experiment 1: unattended PIN and fingerprint enrolment

**What I did.** I walked the Settings flow by hand with `uiautomator dump`, then wrote `enroll_fingerprint.sh`. The script finds screens from the focused activity and the UI tree, and takes every tap position from node bounds; there are no fixed coordinates. I timed the emulator's finger commands, and ran the script on factory-fresh devices (boots B and C), on a device with a PIN already set, and on its failure paths.

**What I observed.**
- `adb shell locksettings set-pin 1111` printed `Pin set to '1111'`. When a credential already exists, `set-pin` without `--old` prints `User has a lock credential, but old credential was not provided` and exits 255. `locksettings verify --old 1111` prints `Lock credential verified successfully`. A wrong `verify` (`Old password '2222' didn't match`) counts as a failed unlock attempt, so the script only tries `verify` after `set-pin` fails.
- `am start -a android.settings.FINGERPRINT_ENROLL` runs through these screens: `ConfirmLockPassword$InternalActivity` ("Enter your device PIN", EditText `password_entry`), then `FingerprintEnrollmentActivity` ("Set up Pixel Imprint", button `MORE`, then `NO THANKS` / `I AGREE`), then `FingerprintEnrollFindSensor` ("Touch the sensor"), then `FingerprintEnrollEnrolling` ("Lift, then touch again"), then `FingerprintEnrollFinish` ("Fingerprint added", `ADD ANOTHER` / `DONE`). `android.settings.BIOMETRIC_ENROLL` was not tried.
- Touches seen in the HAL log (`fingerprint-service.ranchu`). The first touch on FindSensor counts (`onEnrollmentProgress(fpHash=1, left=1)`); then the screen cancels (`onError(CANCELED)`) and enrolment restarts (`ENROLLING_START`). Two more touches finish it (`left=1`, then `left=0`, then `onAuthenticatorIdRetrieved(...)`). That is 3 touches for a first enrolment; after `ADD ANOTHER` it is 2.
- Remove or pause:
  ```
  == 3 rapid touches, finger 2, no remove, no pause   (all 3 sent within 4 ms)
  onSensorEventOn:369: onAcquired(GOOD, 0)            <- only ONE event
  (a) touch 2, wait 1s, touch 2 (no remove)  -> acquired events: 2  (enrolment finished)
  (b) touch 3, remove, touch 3, remove ... no pauses -> 2 events at 11:34:49.267 and .269 (enrolment finished)
  ```
  Conclusion: after each touch, send `finger remove` or wait about 1 s.
- Enrolling a new finger changes the sensor's authenticatorId (`authId=4860766110026993392` became `4229414302929148201` after finger 2), which is what invalidates keys (case 6).
- The PIN screen and BiometricPrompt are secure windows, so `adb exec-out screencap` returns an all-black PNG. `adb -s emulator-5564 emu screenrecord screenshot <dir>` saves the real screen and was used for every prompt screenshot.
- Run on a factory-fresh device (boot B, no PIN, no fingers):
  ```
  [enroll emulator-5564 11:58:04] Pin set to '1111'
  [enroll emulator-5564 11:58:04] fingerprints enrolled before: 0
  [enroll emulator-5564 11:58:08] PIN screen (Enter your device PIN): entering PIN
  [enroll emulator-5564 11:58:12] tap 'MORE' at 911,2242
  [enroll emulator-5564 11:58:15] tap 'I AGREE' at 911,2242
  [enroll emulator-5564 11:58:19] sensor screen (Touch the sensor): touch #1 with finger 1
  [enroll emulator-5564 11:58:23] sensor screen (Lift, then touch again): touch #2 with finger 1
  [enroll emulator-5564 11:58:26] sensor screen (Lift, then touch again): touch #3 with finger 1
  [enroll emulator-5564 11:58:29] finish screen (Fingerprint added) after 3 touches
  [enroll emulator-5564 11:58:29] tap 'DONE' at 911,2242
  [enroll emulator-5564 11:58:31] fingerprints enrolled after: 1
  [enroll emulator-5564 11:58:31] OK: finger 1 enrolled (0 -> 1)
  real 0m27.766s   exit=0
  ```
  Afterwards `dumpsys fingerprint` showed `"count":1`, and the app's `canAuthenticate(BIOMETRIC_STRONG)` returned `0(SUCCESS)`. The script also succeeded with the PIN already set (fingers 2, 3 and 4 added, `a PIN is already set and 1111 matches it`), right after the PIN had been removed, and on boot C.
- Failure exits: `enroll_fingerprint.sh 5599` printed `device emulator-5599 not reachable or not booted` and exited 2. With PIN 2222 while 1111 was set, it printed `could not set or confirm PIN: User has a lock credential, but old credential was not provided` and exited 3.

**Conclusion.** Unattended enrolment works on this image. The script is reusable for API 37 Pixel-style Settings. It matches English button text (`MORE`, `I AGREE`, `DONE`) and activity names, so another locale or Settings version may need new patterns. Not tested on other images.

---

## Experiment 2: a key only a fingerprint can use

Key specification, as built in `BiometricVaultKey.createKey()` (final code below):
```kotlin
KeyGenParameterSpec.Builder(alias, PURPOSE_ENCRYPT or PURPOSE_DECRYPT)
    .setKeySize(256).setBlockModes(BLOCK_MODE_GCM).setEncryptionPaddings(ENCRYPTION_PADDING_NONE)
    .setUserAuthenticationRequired(true)
    .setInvalidatedByBiometricEnrollment(true)
    // API 30+:
    .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
    // API 24-29 equivalent (NOT TESTED, no image): leave
    // setUserAuthenticationValidityDurationSeconds(-1) (the default): every use needs a fingerprint
    // via a CryptoObject; a PIN cannot authorise such a key there.
```
KeyInfo on API 37: `authRequired=true authType=2 validitySeconds=0 invalidatedByBiometricEnrollment=true keySize=256 origin=1 blockModes=[GCM] paddings=[NoPadding]`.

### Case 1: happy path (1.1.0 boot A; 1.4.0-alpha07 boot B)
```
11:38:31.499 enable: Cipher.init OK (no auth yet), cipher.iv=b3e6f2bd318465af306d2b0a
11:39:03.823 callback onAuthenticationSucceeded type=2 pendingOp=enable prompt of 5049678, destroyed=false
11:39:03.824 enable SUCCESS doFinal -> wrapped=48B iv=b3e6f2bd318465af306d2b0a (12B) secretSha=fca00425a922efc4
11:39:04.978 unlock: Cipher.init OK (no auth yet), cipher.iv=b3e6f2bd318465af306d2b0a
11:39:08.405 callback onAuthenticationSucceeded type=2 pendingOp=unlock prompt of 5049678, destroyed=false
11:39:08.407 unlock doFinal -> 32B MATCH sha=fca00425a922efc4
```
The IV is available right after `init`, before authentication, and equals `cipher.iv` after `doFinal`. `authenticationType` is 2 (BIOMETRIC). 1.4.0-alpha07 gave the same result (`enable SUCCESS doFinal -> wrapped=48B ... unlock doFinal -> 32B MATCH`).

### Case 2: init before auth, doFinal without auth
```
noauth ENCRYPT: Cipher.init OK (no auth yet), cipher.iv=b7cb2b5ae43ee22175d92a7b
noauth ENCRYPT doFinal without auth threw javax.crypto.IllegalBlockSizeException: null <- caused by
  android.security.KeyStoreException: Key user not authenticated (internal Keystore code: -26 ...
  enforcements.rs:95: No operation auth token received. ... Error::Km(r#KEY_USER_NOT_AUTHENTICATED))
  [numericErrorCode=2 transient=false requiresUserAuth=true systemError=false retryPolicy=1]
noauth DECRYPT: Cipher.init OK (no auth yet), cipher.iv=b3e6f2bd318465af306d2b0a
noauth DECRYPT doFinal without auth threw javax.crypto.IllegalBlockSizeException: null <- caused by
  android.security.KeyStoreException: Key user not authenticated ...
```
Conclusion: `init` never needs authentication. `doFinal` always does, so turning biometric unlock on needs a prompt.

### Case 3: caller-supplied IV
`calleriv: init with caller IV threw java.security.InvalidAlgorithmParameterException: Caller-provided IV not permitted` (1.1.0 and 1.4.0-alpha07).

### Case 4: a finger that is not enrolled, and lockout
- `emu finger touch 2` while the prompt is open fires `callback onAuthenticationFailed`. The prompt stays open and shows its own "not recognized" text.
- Five wrong touches in one prompt (boot A, first run): four `onAuthenticationFailed`, then the HAL logged `onLockoutTimed(durationMillis=1494)`. The system logged `handleOnError() ... error: 7` and `onDeviceCredentialPressed`, and the prompt **changed itself into "Enter your PIN — PIN is required to recover biometrics after too many failed attempts"** (`shots/case4_lockout_pin_screen_host.png`). **The app received no callback.**
  - Tapping the X on that screen gave `callback onAuthenticationError code=10 ERROR_USER_CANCELED msg='Fingerprint operation canceled by user'`.
  - Entering the PIN returned the prompt to "Touch the fingerprint sensor" (`handleOnResumeAuthentication`). A correct finger then produced `onAuthenticationSucceeded type=2`, **but `doFinal` failed** (see extra finding b).
- A prompt started while the sensor was in a timed lockout failed at once: `callback onAuthenticationError code=7 ERROR_LOCKOUT msg='Too many attempts. Use screen lock instead.' +22ms`. This was seen three times.
- Lockout length on this emulator: the HAL reported `onLockoutTimed(durationMillis=…)` with values 1494, 1495, 6145, 6156, 6161, 6166, 7374, 8468 and 8474, each followed by `onLockoutCleared` that long afterwards. The emulator HAL also never resets its failure counter: `resetLockout ... hat is invalid: E_HAT_WRONG_CHALLENGE` on every PIN entry, so later lockouts came after 1–3 wrong touches. **Real devices are not tested.** The 30 s figure usually quoted is the CDD minimum, not something seen here.
- Permanent lockout: after about 20 cumulative failures (several of them touches on the lock screen) the HAL logged `onLockoutPermanent`. Afterwards every new prompt opened straight on the "Enter your PIN" screen, and the app never received `ERROR_LOCKOUT_PERMANENT` (9). Cancelling gave 10. Because the emulator's `resetLockout` is broken, even the correct finger kept failing until reboot.
- **The phone locks itself after 5 failed attempts.** Android's adaptive auth counts consecutive failed biometric attempts across prompts until a success:
  ```
  AuthenticationPolicyService: reportAuthAttempt: numFailedAttempts=5, userId=0
  AuthenticationPolicyService: lockDevice: userId=0, parentUserId=0
  KeyguardViewMediator: ... KEYGUARD_TIMEOUT
  PVBIO: MainActivity onPause / callback onAuthenticationError code=10 ERROR_USER_CANCELED / onStop
  ```
  This happened in boot A (1.1.0) and boot B (1.4.0-alpha07). With 1.4 the code-10 error was delivered only when the activity came back to the foreground.
- `canAuthenticate(BIOMETRIC_STRONG)` returned `0(SUCCESS)` 8 s after the HAL reported a permanent lockout, so it does not report lockout.

### Case 5: user cancelling (1.1.0; same codes on 1.4.0-alpha07)
```
Back key                      -> code=10 ERROR_USER_CANCELED msg='Fingerprint operation canceled by user'
"Use password" (negative)     -> code=13 ERROR_NEGATIVE_BUTTON msg='Use password'
X (close_button, top right)   -> code=10 ERROR_USER_CANCELED msg='Fingerprint operation canceled by user'
tap outside (540,300), (540,1050); panel starts at y=1100 -> nothing: prompt stays, no callback
cancelAuthentication()        -> code=5 ERROR_CANCELED msg='Fingerprint operation canceled'
```

### Case 6: biometric changes after the key exists
- **Second finger enrolled** (1.1.0 and 1.4.0-alpha07):
  ```
  status ... canAuthenticate: STRONG=0(SUCCESS) ... hasKey=true stored=true
  unlock: KEY_GONE android.security.keystore.KeyPermanentlyInvalidatedException: Key permanently invalidated
  probe ENCRYPT: KEY_GONE android.security.keystore.KeyPermanentlyInvalidatedException: Key permanently invalidated
  keyinfo securityLevel=0 ... (getKey and KeyInfo still work)
  ```
  The exception is thrown at `Cipher.init`, before any prompt, in both modes. The platform source matches this: `KeyStoreCryptoOperationUtils.canUserAuthorizationSucceed` finds that the key's SIDs no longer contain the current biometric authenticatorId.
- A key created while two fingers were enrolled worked with either finger. **Deleting one of the two fingers did not invalidate it** (init OK, finger 2 gave MATCH).
- **All fingerprints deleted** (Settings > Pixel Imprint > delete each; PIN kept): `canAuthenticate STRONG=11(NONE_ENROLLED)`, and `Cipher.init` threw `KeyPermanentlyInvalidatedException: Key permanently invalidated` in both modes. The key stayed invalid after a finger was enrolled again. 1.4.0-alpha07 behaved the same.
- **Device PIN removed** (`locksettings clear --old 1111` printed `Lock credential cleared`). The system log showed `keystore2: onUserLskfRemoved(AndroidUserId(0))`, `Deleting 2 auth-bound keys for AndroidUserId(0)`, and `RemovalClient: Last biometric removed for user: 0`. The app saw:
  ```
  status ... STRONG=11(NONE_ENROLLED) ... DEVICE_CREDENTIAL=11(NONE_ENROLLED) isDeviceSecure=false hasKey=false
  unlock: KEY_GONE null                          (KeyStore.getKey returned null, containsAlias=false)
  create FAILED ... InvalidAlgorithmParameterException: java.lang.IllegalStateException: At least one
    biometric must be enrolled to create keys requiring user authentication for every use
  ```
  1.4.0-alpha07 behaved the same.

### Case 7: hardware backing, StrongBox, UnlockedDeviceRequired
- KeyInfo: `securityLevel=0` (SECURITY_LEVEL_SOFTWARE), `isInsideSecureHardware=false`, `isUserAuthenticationRequirementEnforcedBySecureHardware=false`, `origin=1` (generated). The device declares `android.hardware.hardware_keystore=400` but no StrongBox feature, and runs `android.hardware.security.keymint-service` and `gatekeeper-service.nonsecure`. On this emulator the key is software-backed; on real phones expect TEE (not tested).
- StrongBox: `create FAILED strongBox=true: android.security.keystore.StrongBoxUnavailableException: Failed to generate key <- caused by android.security.KeyStoreException: No StrongBox available [numericErrorCode=12 systemError=true]`.
- `setUnlockedDeviceRequired(true)`: creation OK (`unlockedDeviceRequired=true`), and enable and unlock both gave MATCH while the device was unlocked. With the screen locked (`KEYCODE_SLEEP`, keyguard showing):
  ```
  probe ENCRYPT: TEMPORARY_FAILURE java.security.InvalidKeyException: Keystore operation failed <- caused by
    android.security.KeyStoreException: Device locked (internal Keystore code: -72 ... enforcements.rs:592:
    device is locked. Error::Km(r#DEVICE_LOCKED)) [numericErrorCode=2 requiresUserAuth=true systemError=true]
  ```
  After unlocking the phone with the PIN, and also after unlocking it with a fingerprint on the keyguard, init and a full unlock worked again (MATCH). The flag causes no failure in the foreground. It adds the "Device locked" case and buys nothing here, because every use already needs a fresh fingerprint. Recommendation: leave it off.

### Case 8: USE_BIOMETRIC
The app manifest declares no permission. The merged manifest has:
```xml
<uses-permission android:name="android.permission.USE_BIOMETRIC" /> <!-- suppress DeprecatedClassUsageInspection -->
<uses-permission android:name="android.permission.USE_FINGERPRINT" />
```
`manifest-merger-debug-report.txt` says `uses-permission#android.permission.USE_BIOMETRIC ADDED from [androidx.biometric:biometric:1.1.0] .../AndroidManifest.xml:24:5-72`. `aapt2 dump permissions` on the APK lists `USE_BIOMETRIC` and `USE_FINGERPRINT`. The 1.4.0-alpha07 AAR declares the same two permissions, plus an `androidx.biometric.internal.ui.FingerprintDialogActivity` themed `Theme.AppCompat.Dialog.Alert`, and minSdk 23.

### Case 9: host activity and theme
- `MainActivity : FragmentActivity` with `enableEdgeToEdge()`, `setContent`, and theme `Theme.AppCompat.DayNight.NoActionBar`: the prompt appeared as the system bottom sheet (app icon, label, title, subtitle, X, fingerprint icon, "Use password"), and enable and unlock succeeded. See `shots/case9_prompt_appcompat_host.png`. The UI tree shows the `com.android.systemui` nodes `title`, `subtitle`, `close_button`, `biometric_icon`, `indicator` and `button_negative`, and the focused window is `BiometricPrompt`.
- `PlatformThemeActivity` (same code, `android:theme="@android:style/Theme.Material.NoActionBar"`): the same prompt, `unlock doFinal -> 32B MATCH`, nothing in the crash buffer. See `shots/case9_prompt_platformtheme_host.png`.
- Nothing looked wrong. The app content is dimmed behind the sheet. The prompt is drawn by SystemUI, so the app theme does not affect it on API 37.
- **Not tested, from reading the 1.1.0 source:** on API 24–27 the library draws its own `FingerprintDialogFragment` with `androidx.appcompat.app.AlertDialog` in the host activity. That most likely needs an AppCompat theme, so keep `Theme.AppCompat.*`.

### Case 10: rotation while the prompt is showing
```
11:40:55.364 unlock: authenticate() called
11:40:56.958 MainActivity onDestroy instance=5049678 changingConfigurations=true
11:40:56.968 MainActivity onCreate instance=cf032b4 restored=true landscape pendingOp=unlock
11:41:13.058 callback onAuthenticationSucceeded type=2 pendingOp=unlock prompt of cf032b4, destroyed=false
11:41:13.060 unlock doFinal -> 32B MATCH
```
The prompt stayed up and re-laid out for landscape (`shots/case10_after_rotation_host.png`; the host screenshot is not rotated). The callback reached the new instance's `BiometricPrompt`, the one built in `onCreate`, and the CryptoObject cipher still decrypted. 1.4.0-alpha07 behaved the same (`prompt of 6cf0fa5, destroyed=false ... MATCH`).

When the prompt was instead built lazily, at click time and not in `onCreate`: `callback onAuthenticationSucceeded ... prompt of cf032b4, destroyed=true`. **The result went to the destroyed activity's callback.** The harness keeps its state process-wide, so it still got MATCH. A real app that updates UI or navigation through the old activity would silently do nothing.

### Case 11: Home while the prompt is showing
```
11:41:47.144 BiometricService/AuthSession: Cancelling sensorId: 0
11:41:47.144 callback onAuthenticationError code=10 ERROR_USER_CANCELED msg='Fingerprint operation canceled by user'
11:41:47.190 MainActivity onPause   11:41:48.251 MainActivity onStop
(back)       MainActivity onStart / onResume          <- no prompt, no further callback
11:42:03.140 unlock doFinal -> 32B MATCH               <- a new prompt works normally
```
Calling `authenticate()` while the activity is stopped:
```
>> cmd=unlock on MainActivity lifecycle=CREATED
E/BiometricPromptCompat: Unable to start authentication. Called after onSaveInstanceState().
```
No prompt appears and **no callback ever arrives**, even after returning to the app. 1.1.0 and 1.4.0-alpha07 behave the same. The final launcher therefore refuses to launch unless the activity is RESUMED, and returns `false`.

### Case 12: canAuthenticate
| State | STRONG | WEAK | STRONG\|DEVICE_CREDENTIAL | DEVICE_CREDENTIAL | isDeviceSecure |
|---|---|---|---|---|---|
| fresh device, no PIN | 11 | 11 | 11 | 11 | false |
| PIN, no fingerprint | 11 | 11 | 0 | 0 | true |
| PIN + fingerprint | 0 | 0 | 0 | 0 | true |
| all fingerprints deleted, PIN kept | 11 | 11 | 0 | 0 | true |
| PIN removed (fingerprints removed by system) | 11 | 11 | 11 | 11 | false |
| "Verify it's you in apps" off, lib 1.1.0 | **21** | 21 | 0 | 0 | true |
| "Verify it's you in apps" off, lib 1.4.0-alpha07 | **1** | 1 | 0 | 0 | true |
| permanent lockout (lib 1.1.0) | 0 | 0 | 0 | 0 | true |

Also with no fingerprint, key creation fails at `KeyGenerator.init(spec)` with `InvalidAlgorithmParameterException: java.lang.IllegalStateException: At least one biometric must be enrolled to create keys requiring user authentication for every use`. This happens with no PIN too.

### androidx.biometric 1.4.0-alpha07 compared with 1.1.0 (API 37)
These were the same: happy path; init and doFinal without auth; caller IV; case 6a, 6b and 6c; Back → 10, negative → 13, X → 10; rotation; Home → 10; `authenticate()` while stopped (same log line); the lockout-PIN trap; the "last-constructed prompt gets the callbacks" behaviour.

Different: with fingerprint-for-apps off, canAuthenticate returns 1 and the prompt error is `code=1 ERROR_HW_UNAVAILABLE msg='Error authenticating'`. With 1.1.0 they were 21 and `code=8 ERROR_VENDOR msg='Error authenticating'`. The 1.4 source documents that it converts `ERROR_NOT_ENABLED_FOR_APPS` (21) to `HW_UNAVAILABLE` on purpose. 1.4.0-alpha07 also raises the library minSdk to 23 and pulls in kotlin-stdlib 2.1.20, activity 1.9.0, fragment 1.6.2 and lifecycle-process 2.8.7. None of this argues for leaving 1.1.0.

---

## Extra findings that change the design (API 37, both library versions)

**(a) One BiometricPrompt per activity.** The harness built two `BiometricPrompt` objects in `onCreate`: first the legacy one, then the final launcher. A prompt started through the first one delivered its result to the second one's callback:
```
enable: authenticate() called                                   (through the FIRST BiometricPrompt)
final launcher Authenticated with no pending op (enable)        (callback of the SECOND one)
```
This was seen on boot C with 1.1.0 and with 1.4.0-alpha07. The callback lives in an activity-scoped ViewModel, and the last constructor wins. In a single-activity Compose app, build **exactly one** launcher in `onCreate` and route results by a pending operation kept in a ViewModel.

**(b) A PIN typed on the prompt's lockout screen breaks the CryptoObject.** Seen 4 times out of 4: boot A twice, boot B with 1.4.0-alpha07, and boot B with the final class.
```
keystore2: add_auth_token(challenge=-8427076343261086794, ..., authType=0x1)          <- the PIN, bound to OUR operation's challenge
keystore2: enforcements.rs:220 - Per-op HardwareAuthToken { challenge: ..., authenticatorType: r#PASSWORD ... }
           doesn't match auth type r#FINGERPRINT required for key!
BiometricService: handleOnResumeAuthentication
PVBIO: callback onAuthenticationSucceeded type=2 ...
PVBIO: unlock doFinal FAILED: javax.crypto.IllegalBlockSizeException: null <- caused by
       android.security.KeyStoreException: Key user not authenticated (... operation.rs:415: Update failed ...)
```
A fresh prompt with a fresh cipher works straight afterwards (MATCH), so this failure is temporary. The app must never treat `onAuthenticationSucceeded` as "unlocked". Only a successful `doFinal` counts.

**(c) Five wrong fingers lock the phone** (adaptive auth, see case 4). The app sees only code 10. Do not ask the user to retry the fingerprint after a 10 that followed failed attempts; offer the password.

**(d) "Use fingerprint to: Verify it's you in apps"** (Settings > Pixel Imprint) can be switched off; it is stored as secure setting `fingerptint_app_enabled=0`, typo included. The key is **not** invalidated. canAuthenticate returns 21 or 1 and the prompt fails within about 25 ms (code 8 with 1.1.0, code 1 with 1.4.0-alpha07). Switching it back on restores unlock with the same key (MATCH). Treat it as temporary, and possibly show a hint that fingerprint for apps is off in system settings.

**(e) A second `authenticate()` while a prompt is open is ignored.** The system logged no new `handleAuthenticate`. The open prompt's success carried the *first* cipher, and `doFinal` on `result.cryptoObject.cipher` gave MATCH. Always use `result.cryptoObject.cipher`, never a cipher reference you kept yourself.

---

## Final code (compiled with 1.1.0 and 1.4.0-alpha07; exercised on the API 37 emulator)

File `lab/biometric/project/app/src/main/java/com/example/pvlab/bio/lift/BiometricVaultKey.kt`, verbatim (the package name is the lab's; change it in the app):

```kotlin
package com.example.pvlab.bio.lift

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Biometric unlock key for the vault.
 *
 * An AndroidKeyStore AES-256-GCM key that only a strong biometric can use, and only for one
 * operation per authentication. It wraps (encrypts) the vault's 32-byte key when the user turns
 * fingerprint unlock on, and unwraps it at unlock time. The password always remains the primary
 * way in; every failure below ends in either [Gone] or [Temporary].
 *
 * Every failure listed in the comments was observed on an Android 17 (API 37) emulator with
 * androidx.biometric 1.1.0 and 1.4.0-alpha07.
 */
class BiometricVaultKey(private val alias: String = "vault_biometric_key_v1") {

    /** Outcome of every step. [Gone] means: delete the key and the wrapped blob, turn the option off. */
    sealed interface Result<out T> {
        class Ok<T>(val value: T) : Result<T>

        /** The key or the wrapped blob can never be used again. Remove the biometric option. */
        class Gone(val reason: String, val cause: Throwable?) : Result<Nothing>

        /** It did not work this time. Use the password; keep the biometric option. */
        class Temporary(val reason: String, val cause: Throwable?) : Result<Nothing>
    }

    class Wrapped(val ciphertext: ByteArray, val iv: ByteArray)

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    /**
     * Creates (replacing any old one) the key. Call it when the user turns fingerprint unlock on.
     * Seen failing with InvalidAlgorithmParameterException("...At least one biometric must be
     * enrolled to create keys requiring user authentication for every use") when there is no
     * fingerprint or no screen lock: tell the user to set up a fingerprint first.
     */
    fun createKey(): Result<Unit> = try {
        deleteKey()
        val builder = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Strong biometrics only; 0 = an authentication for every single use.
            builder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        } else {
            // API 24-29 equivalent: validity -1 (the default) = every use needs a fingerprint
            // through a CryptoObject. The PIN cannot authorise such a key there.
            @Suppress("DEPRECATION")
            builder.setUserAuthenticationValidityDurationSeconds(-1)
        }
        // Deliberately not used: setIsStrongBoxBacked(true) (StrongBoxUnavailableException on
        // devices without StrongBox) and setUnlockedDeviceRequired(true) (works, but adds a
        // "Device locked" failure and nothing this flow needs).
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(builder.build())
            generateKey()
        }
        Result.Ok(Unit)
    } catch (e: Exception) {
        Result.Temporary("cannot create the key: ${e.message}", e)
    }

    /** Cipher to hand to the prompt when turning the option on. Needs no IV; the key makes one. */
    fun cipherForEnable(): Result<Cipher> = initCipher { init(Cipher.ENCRYPT_MODE, it) }

    /** Cipher to hand to the prompt at unlock time. */
    fun cipherForUnlock(iv: ByteArray): Result<Cipher> =
        initCipher { init(Cipher.DECRYPT_MODE, it, GCMParameterSpec(GCM_TAG_BITS, iv)) }

    /**
     * After the prompt succeeded: encrypt the vault key with the cipher from
     * `result.cryptoObject.cipher` (never with your own reference). Store both fields.
     */
    fun wrap(authenticatedCipher: Cipher, vaultKey: ByteArray): Result<Wrapped> = try {
        val ciphertext = authenticatedCipher.doFinal(vaultKey)
        Result.Ok(Wrapped(ciphertext, authenticatedCipher.iv))
    } catch (e: Exception) {
        classifyDoFinal(e)
    }

    /** After the prompt succeeded: decrypt the stored blob with `result.cryptoObject.cipher`. */
    fun unwrap(authenticatedCipher: Cipher, ciphertext: ByteArray): Result<ByteArray> = try {
        Result.Ok(authenticatedCipher.doFinal(ciphertext))
    } catch (e: Exception) {
        classifyDoFinal(e)
    }

    fun deleteKey() {
        try {
            keyStore().deleteEntry(alias)
        } catch (_: Exception) {
        }
    }

    private inline fun initCipher(init: Cipher.(SecretKey) -> Unit): Result<Cipher> {
        val key = try {
            keyStore().getKey(alias, null) as SecretKey?
        } catch (e: UnrecoverableKeyException) {
            // Not seen on the emulator; the platform throws it for a key it reports as permanently
            // invalidated while loading it.
            return Result.Gone("key cannot be loaded", e)
        } catch (e: Exception) {
            return Result.Temporary("keystore unavailable", e)
        }
            // Seen after the screen lock was removed: the system deleted the key (and all fingerprints).
            ?: return Result.Gone("key does not exist", null)
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(key)
            Result.Ok(cipher)
        } catch (e: KeyPermanentlyInvalidatedException) {
            // Seen after a new fingerprint was enrolled and after all fingerprints were deleted.
            Result.Gone("key invalidated by a fingerprint change", e)
        } catch (e: Exception) {
            // Seen: InvalidKeyException("Keystore operation failed") caused by
            // KeyStoreException("Device locked") for a setUnlockedDeviceRequired key while locked.
            Result.Temporary("cipher init failed", e)
        }
    }

    private fun classifyDoFinal(e: Exception): Result<Nothing> = when (e) {
        // Seen when the stored blob was altered: the blob can never decrypt; enable again.
        is AEADBadTagException -> Result.Gone("stored data does not match the key", e)
        // Seen: IllegalBlockSizeException caused by KeyStoreException("Key user not
        // authenticated") after onAuthenticationSucceeded when the user had entered the PIN on
        // the prompt's "too many attempts" screen first. A fresh prompt works.
        else -> Result.Temporary("operation not authorised this time", e)
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128

        /**
         * True when a strong biometric can be used right now. Seen: 11 with no fingerprint (or no
         * screen lock), 21 (lib 1.1.0) or 1 (lib 1.4.0-alpha07) when "Use fingerprint to verify
         * it's you in apps" is off, and 0 even while the sensor is locked out.
         */
        fun canUseBiometrics(activity: FragmentActivity): Boolean =
            BiometricManager.from(activity).canAuthenticate(BIOMETRIC_STRONG) ==
                BiometricManager.BIOMETRIC_SUCCESS
    }
}

/**
 * The prompt. Construct it in the activity's onCreate, every time (also after a rotation), so the
 * library delivers the result to the live activity. Keep "what was I doing" (enable or unlock) in
 * a ViewModel, not in the activity.
 */
class BiometricPromptLauncher(
    private val activity: FragmentActivity,
    private val onResult: (PromptResult) -> Unit,
) {
    sealed interface PromptResult {
        /** Use [cipher] (it came from result.cryptoObject) for wrap/unwrap. */
        class Authenticated(val cipher: Cipher) : PromptResult

        /**
         * The prompt closed without a fingerprint. Temporary: show the password field.
         * [userChoice] is true for 13 ("Use password") and 10 (Back, the X, Home). Code 10 also
         * arrives when the phone locked itself after five wrong fingers.
         */
        class Closed(val code: Int, val message: CharSequence, val userChoice: Boolean) : PromptResult
    }

    private val prompt = BiometricPrompt(
        activity,
        ContextCompat.getMainExecutor(activity),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                val cipher = result.cryptoObject?.cipher
                if (cipher != null) {
                    onResult(PromptResult.Authenticated(cipher))
                } else {
                    onResult(PromptResult.Closed(-1, "no cipher in result", userChoice = false))
                }
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                // Seen: 10 USER_CANCELED (Back, the X, Home, the phone locking itself after five
                // wrong fingers), 13 NEGATIVE_BUTTON, 5 CANCELED (cancel()), 7 LOCKOUT (prompt
                // started during a timed lockout), 8 VENDOR (lib 1.1.0) or 1 HW_UNAVAILABLE
                // (lib 1.4.0-alpha07) with fingerprint for apps turned off. All temporary.
                val userChoice = errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                    errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON
                onResult(PromptResult.Closed(errorCode, errString, userChoice))
            }

            // A finger that is not enrolled: the prompt stays open and says so itself. Nothing to do.
            override fun onAuthenticationFailed() = Unit
        },
    )

    /**
     * Shows the prompt. Returns false, and shows nothing, when the activity is not resumed:
     * the library silently ignores authenticate() after onSaveInstanceState and no callback
     * would ever arrive. Call it once per attempt; a second call while a prompt is open is
     * ignored by the library.
     */
    fun launch(
        cipher: Cipher,
        title: CharSequence,
        subtitle: CharSequence?,
        negativeButtonText: CharSequence,
    ): Boolean {
        if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return false
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .apply { if (subtitle != null) setSubtitle(subtitle) }
            .setNegativeButtonText(negativeButtonText)
            .setAllowedAuthenticators(BIOMETRIC_STRONG)
            .setConfirmationRequired(false)
            .build()
        prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
        return true
    }

    fun cancel() = prompt.cancelAuthentication()
}
```
After the device runs I changed only the KDoc of `PromptResult.Closed`, then rebuilt; the build succeeded.

**How it was exercised on the device** (harness `f*` commands; `LabActivity.kt` in the artifacts holds the calling code). With 1.1.0 on boot B:
```
final createKey Ok canUseBiometrics=true
final fenable cipher Ok, launch()=true
final fenable wrap Ok 48B iv=901b47baf0d4795918ed3c5d
final funlock unwrap Ok MATCH
final funlock_tampered unwrap GONE(stored data does not match the key) javax.crypto.AEADBadTagException: null <- caused by android.security.KeyStoreException: Signature/MAC verification failed ...
final launcher Closed code=13 ERROR_NEGATIVE_BUTTON userChoice=true msg='Use password'
final launcher Closed code=10 ERROR_USER_CANCELED userChoice=true msg='Fingerprint operation canceled by user'
final funlock cipher Ok, launch()=false                      (sent while the app was in the background)
final funlock unwrap TEMPORARY(operation not authorised this time) javax.crypto.IllegalBlockSizeException ... Key user not authenticated   (PIN typed on the lockout screen)
final funlock cipher GONE(key invalidated by a fingerprint change) android.security.keystore.KeyPermanentlyInvalidatedException
final funlock cipher GONE(key does not exist) null            (after locksettings clear)
final createKey TEMPORARY(cannot create the key: java.lang.IllegalStateException: At least one biometric must be enrolled ...)
```
With 1.4.0-alpha07: `final createKey Ok`, `final fenable wrap Ok 48B`, `final funlock unwrap Ok MATCH`, and `Closed code=10` for the X.

**Intended app flow** (built on the tested pieces):
- Turn on, after a password unlock:
  1. `canUseBiometrics`.
  2. `createKey()`. On `Temporary`, tell the user to add a fingerprint in system settings first.
  3. `cipherForEnable()`, then `launcher.launch(...)`. If `launch` returns false, try again when resumed.
  4. On `Authenticated`, call `wrap(cipher, vaultKey)` and store `ciphertext` and `iv`. The option is on only after `wrap` returns `Ok`.
- Unlock:
  1. Call `cipherForUnlock(iv)`. On `Gone`: `deleteKey()`, delete the blob, turn the option off, and show a one-time note that fingerprints or the screen lock changed. On `Temporary`, show the password field. On `Ok`, call `launch`.
  2. On `Authenticated`, call `unwrap`, and treat its result the same way.
  3. On `Closed`, show the password field.
- Build exactly one `BiometricPromptLauncher` per activity, in `onCreate`. Keep the pending operation in a ViewModel.

**Gradle and manifest that worked.** The only dependency is `implementation(libs.androidx.biometric)` with `biometric = "1.1.0"` (the lab's `app/build.gradle.kts` uses `implementation("androidx.biometric:biometric:$biometricVersion")` to switch versions). The app manifest needs **no** permission. The library merges in `USE_BIOMETRIC` and `USE_FINGERPRINT` (see case 8). The host activities tested:
```xml
<application ... android:theme="@style/Theme.PvLabBio">   <!-- parent="Theme.AppCompat.DayNight.NoActionBar" -->
    <activity android:name=".MainActivity" android:exported="true"> ... LAUNCHER ... </activity>
    <activity android:name=".PlatformThemeActivity" android:exported="true"
        android:theme="@android:style/Theme.Material.NoActionBar" />   <!-- lab only -->
</application>
```

## Every exception or callback seen, classified

| Seen where | Exception / callback (API 37) | Group |
|---|---|---|
| `KeyStore.getKey` returned **null** (`containsAlias=false`) | key deleted by the system after the screen lock was removed | **Gone** |
| `Cipher.init` (both modes) | `KeyPermanentlyInvalidatedException: Key permanently invalidated`, after a new finger was enrolled, or after all fingers were deleted (it stays invalid after re-enrolling) | **Gone** |
| `doFinal` (decrypt) | `AEADBadTagException` ← `KeyStoreException: Signature/MAC verification failed` (code 10, internal -30); stored blob altered | **Gone** |
| `doFinal` | `IllegalBlockSizeException` ← `KeyStoreException: Key user not authenticated` (code 2, internal -26): after `onAuthenticationSucceeded` when the PIN was typed on the prompt's lockout screen; also when `doFinal` runs with no prompt (a programming error) | Temporary |
| `Cipher.init` | `InvalidKeyException: Keystore operation failed` ← `KeyStoreException: Device locked` (internal -72), only for `setUnlockedDeviceRequired` keys while the screen is locked | Temporary |
| `KeyGenerator.init` | `InvalidAlgorithmParameterException` ← `IllegalStateException: At least one biometric must be enrolled to create keys requiring user authentication for every use` (no fingerprint, with or without PIN) | Temporary (cannot turn on yet) |
| `KeyGenerator.generateKey` | `StrongBoxUnavailableException: Failed to generate key` ← `KeyStoreException: No StrongBox available` (only when StrongBox is requested; do not request it) | Temporary / not used |
| `Cipher.init` | `InvalidAlgorithmParameterException: Caller-provided IV not permitted` (programming error; never pass an IV for ENCRYPT) | n/a (never in shipped code) |
| prompt | 10 USER_CANCELED (Back, X, Home, phone auto-locked after 5 failures, lockout PIN screen cancelled) | Temporary |
| prompt | 13 NEGATIVE_BUTTON, 5 CANCELED (`cancelAuthentication()`) | Temporary |
| prompt | 7 LOCKOUT `Too many attempts. Use screen lock instead.` (prompt started during a timed lockout) | Temporary |
| prompt | 8 VENDOR `Error authenticating` (1.1.0) or 1 HW_UNAVAILABLE (1.4.0-alpha07): fingerprint for apps turned off | Temporary |
| prompt | `onAuthenticationFailed()` (finger not enrolled; prompt stays open) | nothing to do |
| `authenticate()` | dropped with `E/BiometricPromptCompat: Unable to start authentication. Called after onSaveInstanceState().`, no callback | avoid: launch only when RESUMED |

These were not seen, and the code's classification for them is untested: `UnrecoverableKeyException` from `getKey` (classified Gone), `ERROR_LOCKOUT_PERMANENT` 9 (API 37 shows the PIN screen instead), `ERROR_NO_BIOMETRICS` 11 at prompt time (`init` already threw), `ERROR_HW_UNAVAILABLE` from a real hardware fault.

## Not tested / unresolved
- **API 24–36**: no other system image is installed and there is no sdkmanager. That leaves untested the API 24–29 key spec (`setUserAuthenticationValidityDurationSeconds(-1)`), the library's own fingerprint dialog on API 24–27 (which likely needs an AppCompat theme), and whether lockout and PIN behave differently there.
- **Real hardware**: this emulator's keys are software-backed. Its fingerprint HAL locks out for only 1.5–8.5 s, and its `resetLockout` always fails (`E_HAT_WRONG_CHALLENGE`), so a permanent lockout lasted until reboot. Real devices should reset the lockout after the PIN and lock out for at least 30 s; not verified.
- Whether finding (b) happens on real API 35–37 devices is unverified. The cause is keystore2 logic visible in the log, so it is likely.
- Behaviour after process death while the prompt is open: not tested.
- `android.settings.BIOMETRIC_ENROLL`: not tried; `FINGERPRINT_ENROLL` was enough.
- The enrolment script matches English Settings strings on the API 37 Pixel UI; other images or locales are untested.
- `canAuthenticate` during a *timed* lockout was not captured cleanly; the 0 was seen during a permanent lockout.

## Files (all under `lab/biometric/`)
- `enroll_fingerprint.sh`: Experiment 1 script. `uitap.py`, `uidump.sh`: UI-tree tap and dump helpers. `lab.sh`: sends one harness command and prints its log. `delete_all_fingerprints.sh`: deletes fingerprints through Settings.
- `project/`: lab app. `app/src/main/java/com/example/pvlab/bio/lift/BiometricVaultKey.kt` is the final class. `LabActivity.kt` and `BiometricKeyVault.kt` are the harness. `apks/` holds both builds.
- `logs/`: `bootA_PVBIO_lib1.1.0.log`, `bootB_PVBIO_lib1.4.0-alpha07.log`, `bootB_PVBIO_both_libs.log`, `bootC_PVBIO_two_prompts.log`, and the full logcats.
- `shots/`: `case9_prompt_appcompat_host.png`, `case9_prompt_platformtheme_host.png`, `case10_after_rotation_host.png`, `case4_lockout_pin_screen_host.png`, `case4_lockout_pin_screen2_host.png`, `case4_after_5_wrong_host.png`, `b14_prompt_host.png`, and `case9_prompt_appcompat.png` (the all-black adb screencap).
- `libsrc/`: androidx.biometric 1.1.0 and 1.4.0-alpha07 sources and AARs used for reading.

Note on the harness: in its final state `LabActivity` builds both the legacy `BiometricUnlockPrompt` and the final `BiometricPromptLauncher` in `onCreate`. Because of finding (a), legacy commands (`enable`, `unlock`) then report through the final launcher. All legacy-command results above came from the first build, which did not yet contain the launcher.

## Unresolved

- REPORT.md was not saved to lab/biometric/REPORT.md. The environment refused the write ('Subagents should return findings as text, not write report files'), so the full report is in the report field; the orchestrator needs to save it.
- API 24-36 were not tested: only the android-37.0 system image is installed and there is no sdkmanager. That leaves unverified the API 24-29 key spec (setUserAuthenticationValidityDurationSeconds(-1)), the library's own FingerprintDialogFragment on API 24-27 (which likely needs an AppCompat theme, per the 1.1.0 source), and lockout behaviour on older versions.
- Real hardware was not tested. This emulator's keys are software-backed (securityLevel 0). Its fingerprint HAL locks out for only 1.5-8.5 s and its resetLockout always fails (E_HAT_WRONG_CHALLENGE), so a permanent lockout lasted until reboot. Real lockout duration (CDD says at least 30 s), TEE or StrongBox backing, and lockout reset after the PIN are unverified.
- ERROR_LOCKOUT_PERMANENT (9), ERROR_NO_BIOMETRICS (11) at prompt time and UnrecoverableKeyException from KeyStore.getKey never occurred, so their classification in the final class (temporary, temporary, Gone) is based on the platform source, not observation.
- Whether 'PIN typed on the prompt's lockout screen makes the CryptoObject unusable' also happens on real API 35-37 devices is unverified. It was seen 4 of 4 times on the emulator, and keystore2 logs the cause.
- Process death while the prompt is open was not tested.
- android.settings.BIOMETRIC_ENROLL was not tried; FINGERPRINT_ENROLL was enough. The enrolment script matches English strings of the API 37 Pixel Settings UI (MORE, I AGREE, DONE) and is untested on other images or locales.
- canAuthenticate(BIOMETRIC_STRONG) was captured as 0 during a permanent lockout, but not cleanly during a timed lockout.
