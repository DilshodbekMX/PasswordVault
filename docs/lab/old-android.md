# Old-Android lab: API 24 and API 28 emulators

Date: 2026-10-06. Lab directory: `/tmp/claude-1000/-home-asus-AndroidStudioProjects-PasswordVault/096e95f8-3ae0-41bf-b235-08771bb88852/scratchpad/lab/old-android` (called `LAB` below).

**Two notes before the results:**
- **`LAB/REPORT.md` was not written.** The harness refused the write ("Subagents should return findings as text, not write report files"), so this report exists only in this output.
- **Runbook page:** the boot, unlock, install and stop commands are in `LAB/README.txt` and on the page https://claude.ai/artifact/VRGcpX15hDE2XhGnxYz5Ta. The page is private; it must be shared from its Share menu before anyone else can open it.

Every observation below names the API level it was made on. Anything not run on a device is labelled **not tested** or **static evidence**. Both lab emulators (5566, 5568) are stopped. I never started the user's API 37 device.

## Summary

| # | Question | API 24 (Android 7.0) | API 28 (Android 9) |
|---|---|---|---|
| 1 | Starter app installs and starts | Yes, no crash (`shots/api24-01-starter-10s.png`) | Yes, no crash (`shots/api28-01-starter-10s.png`) |
| 2 | argon2kt native lib loads; argon2kt = Bouncy Castle = expected `853b…b49e` | Yes, all three equal | Yes, all three equal |
| 2 | BC at 64 MiB: OutOfMemoryError without / with largeHeap | No OOM on the stock emulator (512 MiB heap). Simulated 128 MiB device: OOM without largeHeap, OK with it. BC needs about 134 MiB of heap here | No OOM on the stock emulator. Simulated 64 MiB device: OOM without, OK with; at 96 MiB it fits. BC needs about 70 MiB here |
| 3 | biometric 1.1.0 + core 1.19.1 | **Broken.** `canAuthenticate(BIOMETRIC_STRONG)` returns 12 (no hardware). `authenticate()` fails at once with error 12 "This device does not have a fingerprint sensor" and shows no prompt | **Misbehaves.** `canAuthenticate` returns -1 (status unknown), but the system prompt works and the cipher works |
| 3 | biometric 1.4.0-alpha07 + core 1.19.1 | Works: `canAuthenticate` returns 0, the library dialog appears, encrypt and decrypt both succeed | Works: returns 0, system prompt, cipher works |
| 3 | Host theme | AppCompat host: the dialog is correct. Platform-theme host: **the app crashes** (`You need to use a Theme.AppCompat theme`) | Both themes fine (SystemUI draws the prompt) |
| 4 | `fd.sync()`, `Os.rename`, directory fsync in filesDir | All succeed | All succeed |
| 5 | Clipboard | An empty clip replaces the secret (`text=""`). The IS_SENSITIVE extra is harmless. `clearPrimaryClip()` throws `NoSuchMethodError` | `clearPrimaryClip()` works (`hasPrimaryClip=false`). The extra is harmless |
| 6 | AES-GCM with AAD, SecureRandom, NFC, JSON | All OK | All OK |
| 7 | Other | Missing APIs: `java.util.Base64`, `java.time`, `getInstanceStrong`, PBKDF2WithHmacSHA256, ChaCha20-Poly1305, `java.nio.file`. A BC OOM can crash the UI thread. `adb backup` exports the vault file | All of those APIs exist |

The research report's claim about biometric 1.1.0 holds, with two corrections. On API 24 the failure is not silent: the app gets an error callback with code 12. And biometric 1.4.0-alpha05 and alpha06 also carry their own fingerprint code (static evidence only; only alpha07 was run).

## 0. Setup

**Images.** I downloaded the plain AOSP x86_64 images listed in `https://dl.google.com/android/repository/sys-img/android/sys-img2-5.xml` and checked their sha1 sums:

| Archive | sha1 | Build |
|---|---|---|
| `x86_64-24_r08.zip` | `f6559e1949a5879f31a9662f4f0e50ad60181684` | `sdk_phone_x86_64-userdebug 7.0 NYC 4174735`, patch 2017-06-05 |
| `x86_64-28_r04.zip` | `d47a85c8f4e9fd57df97814ad8884eeb0f3a0ef0` | `9 PSR1.180720.012 4923214`, patch 2018-08-05 |

They are unzipped under `LAB/sysimg/android-{24,28}/default/x86_64/`. Both images are userdebug, so `adb root` works on them.

**Devices.** I wrote `LAB/avd/pvlab_api{24,28}.ini` and `.avd/config.ini`, modelled on the user's device. The user's `Medium_Phone_API_37.0.ini` actually points at `Medium_Phone.avd`, which is where I read its config. Settings: 4 cores, 2 GB RAM, 1080x1920 at 420 dpi.
- `image.sysdir.1` holds an absolute path, and the emulator accepted it (`Found systemPath …/sysimg/android-24/default/x86_64/`), so `-sysdir` was not needed.
- Emulator 37.2.12 booted both images headless (`-no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader_indirect`) on ports 5566 and 5568, in about 10 s each.
- Nothing was written to the SDK or to `~/.android`. Without `ANDROID_AVD_HOME`, `emulator -list-avds` still lists only `Medium_Phone_API_37.0`.

**Project.** The project is a copy of the starter (build directories excluded), with namespace and applicationId `com.example.pvlab.old`. Gradle switches select the variant:

| Variant | biometric | largeHeap |
|---|---|---|
| A | 1.1.0 | false |
| B | 1.1.0 | true |
| C | 1.4.0-alpha07 | false |

`apks/starter-asis-debug.apk` is the starter code, unchanged except for the package.

**Runbook check.** At the end I stopped both devices and ran the `README.txt` commands exactly as written: boot, unlock, install, run. They worked.

**Device facts.**
- API 24 has `feature:android.hardware.fingerprint` and `ro.crypto.state=unsupported`.
- API 28 has the fingerprint feature and full-disk encryption (`ro.crypto.state=encrypted`).
- The emulator's lock screen is disabled (`lockscreen.disabled=1`) until a PIN is set.

## 1. Starter app as it is

**What I did.** Installed `starter-asis-debug.apk`, ran `am start -W`, waited 10 s, took a screenshot and read logcat. I repeated this after a reboot (`logs/starter-api24.txt`, `logs/starter-api28.txt`).

**API 24:**
```
Status: ok
Activity: com.example.pvlab.old/.MainActivity
TotalTime: 417
I KdfBench: round=1 m=65536KiB t=3 native=179ms bouncy=331ms equal=true debuggable=true
I KdfBench: done
```
**API 28:** `TotalTime: 1336`, `round=1 m=65536KiB t=3 native=121ms bouncy=389ms equal=true`, `done`.

On both, the process stayed alive and there were no `AndroidRuntime` errors. API 28 logs dozens of `Accessing hidden method … (light/dark greylist)` warnings from Compose and AppCompat. These are warnings only.

**What the screenshots show.** The edge-to-edge content sits below the status bar, with both Material icons, the NavDisplay entry and the JSON text.
- API 24: the navigation bar is a grey scrim, because light navigation-bar icons need API 26.
- API 28: the navigation bar is light with dark icons.
- On both, the starter's own text already shows `biometric=12` (no hardware) on a device that has a sensor. See check 3.

**Extra UI checks (lab app, API 24 and 28):**
- **Navigation 3.** Tapping "Open detail" gave `found 'Screen: Detail'`. `KEYCODE_BACK` then gave `found 'Screen: Home'`.
- **Compose password field.** `input text Abc123xyz` logged `textfield length=9 value=Abc123xyz`. The field showed dots and the keyboard opened (`mInputShown=true`).
- **FLAG_SECURE.** With the flag on, `screencap` produced a 0-byte file (API 24 rc=0, API 28 rc=1). With it off, a normal PNG.

**Conclusion:** Compose with edge-to-edge, Material 3, the extended icons, Navigation 3 with system back, the AppCompat theme, FragmentActivity and lifecycle-process all work on API 24 and 28.

## 2. Key derivation

**What I did.** `Probes.kdf()` calls `Argon2Kt()`, which loads `libargon2jni.so`. It then runs argon2kt and Bouncy Castle 1.86 twice each with these parameters:
- Argon2id, m=65536 KiB, t=3, p=4
- password "correct horse battery staple" (UTF-8), salt 00..0f, 32-byte output

Both results are compared with the expected hex. A host-JVM unit test (`bouncyCastleArgon2id_labVector`, JDK 25) also passed with that value.

**API 24** (`logs/all-api24.txt`):
```
kdf: argon2kt native library loaded OK
kdf: round=1 argon2kt m=65536 t=3 p=4 -> 853b272a44db1421c02962669a55eb0994f3cab385ed1c4c79253eee19bab49e in 75 ms, matchesExpected=true
kdf: round=1 bouncycastle m=65536 t=3 p=4 -> 853b272a44db1421c02962669a55eb0994f3cab385ed1c4c79253eee19bab49e in 375 ms, matchesExpected=true
kdf: round=1 nativeEqualsBouncy=true
```
**API 28:** the same hex for both, `matchesExpected=true`, `nativeEqualsBouncy=true`.

Single-derivation timings at the default heap. These are a debug build on an x86_64 emulator with KVM, so treat them as an indication only; real phones will be several times slower.

| | argon2kt (native, p=4) | Bouncy Castle (lanes run one after another) |
|---|---|---|
| API 24 | 50–75 ms | 249–375 ms |
| API 28 | 51–135 ms | 310–763 ms |

### Memory and OutOfMemoryError

**Stock emulator (both images).** `maxMemory=512MiB memoryClass=512 largeMemoryClass=512 isLowRamDevice=false`. The images set `dalvik.vm.heapsize=512m` and no `heapgrowthlimit`. Neither variant ran out of memory there, even with two derivations at once.

**Heap growth for one 64 MiB derivation:**
```
API 24 bc64: before … usedHeap=5MiB -> run 1/1 OK in 328 ms … usedHeap=139MiB
API 28 bc64: before … usedHeap=4MiB -> run 1/1 OK in 763 ms … usedHeap=73MiB
```
BC allocates 65,536 blocks of 1,040 bytes each (the OOM message names a "1040 byte allocation"). That came to about 134 MiB on API 24 against about 69 MiB on API 28. A likely cause, not verified in ART source: Android 7 rounds a 1,040-byte object up to a 2 KiB slot, while Android 8+ allocates exact sizes.

**Simulated real devices.** `heapsim.sh` uses `adb root`, then `setprop dalvik.vm.heapgrowthlimit/heapsize`, then `stop; start`. Each row ran in a fresh process; "x2" means two derivations at once.

| Image | growthlimit / heapsize | largeHeap | maxMemory | BC 64 MiB x1 | BC x2 | argon2kt |
|---|---|---|---|---|---|---|
| API 24 | 128m / 256m | false | 128 MiB | **OOM** | **OOM, OOM** | OK |
| API 24 | 128m / 256m | true | 256 MiB | OK (138 MiB used) | **one OOM**, one OK | OK |
| API 24 | 192m / 512m | false | 192 MiB | OK (139 MiB) | **one OOM**, one OK | OK |
| API 24 | 192m / 512m | true | 512 MiB | OK | OK (268 MiB) | OK |
| API 28 | 64m / 256m | false | 64 MiB | **OOM** | **OOM, OOM** | OK |
| API 28 | 64m / 256m | true | 256 MiB | OK (73 MiB) | OK (140 MiB) | OK |
| API 28 | 96m / 256m | false | 96 MiB | OK (73 MiB) | **OOM, OOM** | OK |
| API 28 | 96m / 256m | true | 256 MiB | OK | OK | OK |

Sample, API 24 at 128m without largeHeap:
```
bc64: before maxMemory=128MiB memoryClass=128 largeMemoryClass=256 isLowRamDevice=false manifestLargeHeap=false usedHeap=5MiB
bc64: run 1/1 OutOfMemoryError after 81 ms: Failed to allocate a 1040 byte allocation with 4191816 free bytes and 12MB until OOM; failed due to fragmentation (required continguous free 16384 bytes for a new buffer where largest contiguous free 8192 bytes)
bc64: argon2kt (native) same params OK in 67 ms matchesExpected=true; maxMemory=128MiB …
```
API 28 at 64m: `OutOfMemoryError thrown while trying to throw OutOfMemoryError; no stack trace available`.

**Catching the OOM in the KDF thread is not enough.** In the first API 24 / 128m run, the UI thread also failed to allocate, and the app crashed (dropbox `data_app_crash`, 11:44:21):
```
java.lang.OutOfMemoryError: Failed to allocate a 1036 byte allocation with 4191736 free bytes and 12MB until OOM; failed due to fragmentation …
	at androidx.compose.ui.node.NodeChain.<init>(NodeChain.kt:35)
	at androidx.compose.ui.node.LayoutNode.<init>(LayoutNode.kt:864)
	at androidx.compose.ui.platform.AndroidComposeView.<init>(AndroidComposeView.android.kt:556)
	…
	at android.view.Choreographer.doFrame(Choreographer.java:619)
```
An identical second run did not crash, so this depends on timing.

**Conclusions:**
- argon2kt loads and is correct on API 24 and 28. Its work area is native memory, so the Java heap limit never affected it.
- Whether BC at 64 MiB runs out of memory depends on the device's heap limit, not just the API level. Android 7 needs about twice the heap that Android 9 does.
- With `largeHeap="true"`, a single BC derivation passed in every configuration. Two at once still failed on API 24 with a 256 MiB heap.
- If BC is kept as a fallback, run one derivation at a time and treat an OOM as possibly fatal to the whole process.
- Not tested: real devices, ARM ABIs (only the x86_64 `.so` loaded), release builds.

## 3. Biometrics

### Static evidence (not run on a device)

**androidx.core stubs.** `androidx.core:core:1.19.1` `FingerprintManagerCompat`, from javap:
```
public boolean hasEnrolledFingerprints();  Code: 0: iconst_0  1: ireturn
public boolean isHardwareDetected();       Code: 0: iconst_0  1: ireturn
public void authenticate(…CryptoObject, int, androidx.core.os.CancellationSignal, …);  Code: 0: return
```
core 1.16.0 and 1.17.0 still check `SDK_INT >= 23` and call the framework. **1.18.0 is the first version with the stubs.**

**biometric 1.1.0.** It calls androidx.core's `FingerprintManagerCompat` from `BiometricManager`, `BiometricFragment`, `AuthenticationCallbackProvider` and `CryptoObjectUtils`.
- On API 28 its `canAuthenticateCompat` returns STATUS_UNKNOWN when the device is secure but the fingerprint check fails.
- Its `crypto_fingerprint_fallback_vendors` list is `samsung`, so Samsung API 28 devices use 1.1.0's own fingerprint dialog for crypto. That path should fail the way API 24 does. **Not tested**: the emulator reports manufacturer "unknown".

**Newer biometric versions.** 1.4.0-alpha07 has its own `androidx.biometric.internal.FingerprintManagerCompat`, which calls `android.hardware.fingerprint.FingerprintManager` directly. A scan of the published versions:
- 1.2.0-alpha05 and 1.4.0-alpha01…04 still use androidx.core's class.
- **1.4.0-alpha05, 06 and 07** call the framework directly.

**The framework class is gone from SDK 37.** `FingerprintManager` and `Context.FINGERPRINT_SERVICE` are `removed="37.0"` in `api-versions.xml`. The first lab build failed with `Unresolved reference 'fingerprint'`; the lab now uses reflection.

**Pinning core 1.17.0 is not a workaround.** That variant does not compile:
```
Cannot access 'androidx.core.app.PictureInPictureProvider' which is a supertype of 'androidx.activity.ComponentActivity'
```
activity 1.13.0 needs core 1.18 or newer.

**Cost of switching.** Moving to 1.4.0-alpha07 changes only one other resolved dependency: fragment 1.5.4 → 1.6.2. The AAR declares `minCompileSdk=36`.

### Device setup

**API 28.** `adb shell locksettings set-pin 1111` printed `Pin set to '1111'`. Then Settings → Security & location → Fingerprint → re-enter PIN → three `emu finger touch 1` → "Fingerprint added".

This had a side effect: on this encrypted image the PIN also became the start-up password. After a reboot the device says "To start Android, enter your PIN", and `sys.boot_completed` is already 1 on that screen.

**API 24.** There is no `locksettings` command (`cmd lock_settings` → `No shell command implementation.`). I drove Settings with uiautomator:
1. Security → Fingerprint → Continue
2. "Fingerprint + PIN" → 1111 → Continue → 1111 → OK
3. Notifications → Done → Next
4. One `emu finger touch 1` → "Fingerprint added!"

`dumpsys fingerprint` then showed `"count":1`. Later I enrolled extra fingers: ids 2 and 3 on API 24, id 2 on API 28.

### Key and prompt

The key is AndroidKeyStore AES-256-GCM, NoPadding, with `setUserAuthenticationRequired(true)` and `setInvalidatedByBiometricEnrollment(true)`.

`setUserAuthenticationParameters(0, AUTH_BIOMETRIC_STRONG)` needs API 30, so on API 24–29 I **did not call `setUserAuthenticationValidityDurationSeconds`**. Its default of -1 means "authentication required for every use", which before API 30 only a fingerprint through a CryptoObject can satisfy.

`KeyInfo` on both images:
```
keySize=256 insideSecureHardware=false userAuthRequired=true validitySeconds=-1 invalidatedByBiometricEnrollment=true authEnforcedBySecureHw=false
```
The keystore on these images is software, so TEE enforcement is **not tested**.

The prompt is created in `onCreate` and called as `authenticate(promptInfo, CryptoObject(cipher))`, with BIOMETRIC_STRONG and the negative button "Use master password". On success the app calls `cipher.doFinal`. Encrypt and decrypt each needed their own authentication. Evidence: `logs/bio-*.txt` and `shots/api{24,28}-{A,C}-{appcompat,platform}-*.png`.

| | API 24, 1.1.0 | API 24, 1.4.0-alpha07 | API 28, 1.1.0 | API 28, 1.4.0-alpha07 |
|---|---|---|---|---|
| canAuthenticate(STRONG), no PIN, no finger | 12 NO_HARDWARE | 11 NONE_ENROLLED | 12 NO_HARDWARE | 11 NONE_ENROLLED |
| canAuthenticate(STRONG), PIN + finger | **12 NO_HARDWARE** | 0 SUCCESS | **-1 STATUS_UNKNOWN** | 0 SUCCESS |
| authenticate + CryptoObject, AppCompat host | **error 12 at once, no UI** | dialog, success, encrypt and decrypt OK | SystemUI prompt, success, OK | SystemUI prompt, success, OK |
| same, platform-theme host | **error 12 at once** | **crash** | OK | OK |

In every case the framework itself was fine: by reflection, `FingerprintManager` reported `isHardwareDetected=true hasEnrolledFingerprints=true`. androidx.core's `FingerprintManagerCompat` reported `false false` on both images.

**API 24, 1.1.0:**
```
bio-status: canAuthenticate(BIOMETRIC_STRONG)=12 BIOMETRIC_ERROR_NO_HARDWARE
bio-status: framework FingerprintManager (reflection) isHardwareDetected=true hasEnrolledFingerprints=true
bio-status: androidx.core FingerprintManagerCompat isHardwareDetected=false hasEnrolledFingerprints=false
bio-status: KeyguardManager isDeviceSecure=true isKeyguardSecure=true
bio-enc: authenticate(promptInfo, CryptoObject(cipher)) called
bio: onAuthenticationError code=12 msg="This device does not have a fingerprint sensor"
```

**API 24, 1.4.0-alpha07, AppCompat host.** The dialog showed the title, subtitle, fingerprint icon, "Touch the fingerprint sensor" and "USE MASTER PASSWORD" (`shots/api24-C-appcompat-enc-prompt.png`):
```
bio-status: canAuthenticate(BIOMETRIC_STRONG)=0 BIOMETRIC_SUCCESS
bio: onAuthenticationSucceeded type=2 hasCipher=true
bio-enc: cipher.doFinal after auth OK ivLen=12 ctLen=42
bio: onAuthenticationSucceeded type=2 hasCipher=true
bio-dec: cipher.doFinal after auth OK plaintextMatches=true
```

**API 24, 1.4.0-alpha07, host theme `@android:style/Theme.Material.Light.NoActionBar`:**
```
E AndroidRuntime: FATAL EXCEPTION: main
E AndroidRuntime: java.lang.IllegalStateException: You need to use a Theme.AppCompat theme (or descendant) with this activity.
E AndroidRuntime: 	at androidx.appcompat.app.AppCompatDelegateImpl.createSubDecor(AppCompatDelegateImpl.java:852)
E AndroidRuntime: 	at androidx.appcompat.app.AppCompatDialog.setContentView(AppCompatDialog.java:97)
E AndroidRuntime: 	at androidx.appcompat.app.AlertDialog.onCreate(AlertDialog.java:279)
E AndroidRuntime: 	at androidx.fragment.app.DialogFragment.onStart(DialogFragment.java:993)
E AndroidRuntime: 	at androidx.fragment.app.FragmentActivity.onResume(FragmentActivity.java:311)
```
The system then showed "PV Lab Old has stopped". The library draws an AppCompat AlertDialog inside the host activity; it did not use the `FingerprintDialogActivity` that the AAR also declares. With 1.1.0 the dialog was never reached on API 24, so its theme behaviour could **not be observed**.

**API 28, 1.1.0.** `canAuthenticate(BIOMETRIC_STRONG)=-1 BIOMETRIC_STATUS_UNKNOWN`. The SystemUI `FingerprintDialogView` appeared, followed by `onAuthenticationSucceeded type=2 hasCipher=true`, `bio-enc: cipher.doFinal after auth OK` and `bio-dec: … plaintextMatches=true`. The platform-theme host behaved the same.

**API 28, 1.4.0-alpha07.** `canAuthenticate` returned 0. Same SystemUI prompt; encrypt and decrypt OK in both hosts.

**More behaviour with 1.4.0-alpha07 (identical on API 24 and 28):**
- **No authentication:** `cipher.init OK without auth … refused as expected: javax.crypto.IllegalBlockSizeException cause=android.security.KeyStoreException: Key user not authenticated`.
- **Wrong finger:** `onAuthenticationFailed`, and the dialog said "Not recognized". The right finger then succeeded with `plaintextMatches=true`.
- **Negative button:** `onAuthenticationError code=13 msg="Use master password"`.
- **Back key:** `code=10 msg="Fingerprint operation canceled by user."`.
- **After a reboot,** decrypt still worked on both.
- **Key creation before any PIN or fingerprint:**
  - API 28 refused: `java.security.InvalidAlgorithmParameterException: java.lang.IllegalStateException: At least one fingerprint must be enrolled to create keys requiring user authentication for every use`.
  - API 24 created the key anyway. That is probably the emulator HAL reporting a non-zero authenticator id with nothing enrolled.
- **Invalidation on new enrollment could not be tested.** The hidden `FingerprintManager.getAuthenticatorId()` returned the same constant, `7749363893351949254`, on both images: before and after enrolling, and across a reboot. After enrolling finger 3 (API 24) or finger 2 (API 28), `cipher.init` did not throw `KeyPermanentlyInvalidatedException`, and the new finger decrypted the old data. The emulator HAL never rotates the id, so this says nothing about real phones.

**Conclusions:**
- **API 24:** with biometric 1.1.0 and core 1.18 or newer, fingerprint unlock is impossible. The app is told (error 12, "no hardware"), but the user simply loses the feature.
- **API 28:** 1.1.0 returns -1, so an app that offers fingerprint unlock only on SUCCESS will hide it. Authentication itself works on non-Samsung devices.
- **1.4.0-alpha07 fixes both.**
- **Every activity that shows BiometricPrompt needs an AppCompat theme.** Below API 28 the library draws an AppCompat AlertDialog in the host, and a platform theme crashes the app. The starter's `Theme.AppCompat.DayNight.NoActionBar` is fine.
- **API 25–27 not run.** They use the same dialog path as API 24.

## 4. Storage

**What I did.** `AtomicFiles.write()` ran twice in filesDir, so the second rename replaced an existing file. Then each primitive ran on its own.

**API 24 and API 28 (identical):**
```
storage: gen=1 write+fd.sync()+Os.rename+dir fsync OK; readBack=true size=832 tmpLeft=false
storage: gen=2 write+fd.sync()+Os.rename+dir fsync OK; readBack=true size=832 tmpLeft=false
storage: FileOutputStream.fd.sync() OK
storage: Os.rename OK (exists=true)
storage: Os.open(dir, O_RDONLY) OK valid=true
storage: Os.fsync(dirFd) OK
storage: Os.close(dirFd) OK
```

**Conclusion:** all calls succeed on both, with no `ErrnoException` and no SELinux denial. `android.util.AtomicFile` works on both too; `java.nio.file` does not exist on API 24. Surviving a power cut was **not tested**.

## 5. Clipboard

**API 24:**
```
clip: after plain copy -> hasPrimaryClip=true text="plain-secret-1" label="pw" extras=null
clip: after empty-clip overwrite -> hasPrimaryClip=true text="" label="" extras=null
clip: after copy with IS_SENSITIVE extra -> hasPrimaryClip=true text="sensitive-secret-2" label="pw" extras=PersistableBundle[mParcelledData.dataSize=88]
clip: after SensitiveClipboard.clear (sdk 24) -> hasPrimaryClip=true text="" label="" extras=null
clip: unguarded clearPrimaryClip() threw java.lang.NoSuchMethodError: No virtual method clearPrimaryClip()V in class Landroid/content/ClipboardManager; …
```
**API 28:** identical up to the clear, then `after SensitiveClipboard.clear (sdk 28) -> hasPrimaryClip=false text=null`. The unguarded `clearPrimaryClip()` also works there.

**Clearing from the background** (copy, press HOME, clear after 8 s):
- API 24: `clip-bg: cleared while lifecycle=CREATED; text now=""`.
- API 28: `… text now=null`.

**Conclusion:**
- On API 24, an empty plain-text clip replaces the secret.
- The `android.content.extra.IS_SENSITIVE` boolean is accepted and survives the round trip on both, with no exception.
- `clearPrimaryClip()` must stay behind `SDK_INT >= 28`.
- Keyboard clipboard histories were **not tested**; the AOSP images have none.

## 6. Platform crypto and text

**API 24 and API 28 (identical):**
```
crypto: SecureRandom algorithm=SHA1PRNG provider=AndroidOpenSSL sample=…
crypto: AES-256-GCM+AAD provider=AndroidOpenSSL ctLen=35 (plain 19 + 16 tag) roundTrip=true
crypto: wrong AAD rejected with AEADBadTagException (good)
crypto: Normalizer NFC ok=true codepoints=0043,0061,0066,00e9,0020,00c5
crypto: kotlinx-serialization json={"version":1,"entries":[{"id":"1","title":"Mail \"work\"","username":"me@example.com","password":"pä$$\\w0rd\n✓","tags":["a","b"]}]} roundTrip=true
```
**Conclusion:** all four work on both. The JSON decode also tolerated an unknown field.

## 7. Other findings

**APIs missing on API 24.** API 28 has all of these:
```
extra: java.util.Base64 -> java.lang.NoClassDefFoundError: Failed resolution of: Ljava/util/Base64;
extra: java.time.Instant.now -> java.lang.NoClassDefFoundError: Failed resolution of: Ljava/time/Instant;
extra: SecureRandom.getInstanceStrong -> java.lang.NoSuchMethodError: No static method getInstanceStrong()…
extra: PBKDF2WithHmacSHA256 -> java.security.NoSuchAlgorithmException: PBKDF2WithHmacSHA256 SecretKeyFactory not available
extra: ChaCha20-Poly1305 -> java.security.NoSuchAlgorithmException: No provider found for ChaCha20/Poly1305/NoPadding
extra: java.nio.file.Files.move ATOMIC_MOVE -> java.lang.NoSuchMethodError: No virtual method toPath()Ljava/nio/file/Path; …
```
These do work on API 24: `android.util.Base64`, `kotlin.io.encoding.Base64`, PBKDF2WithHmacSHA1, AtomicFile, SHA-256, HmacSHA256, `String.repeat` and `List.of`. Core library desugaring is **not tested**.

**Crashes found elsewhere in this report:** the BC OOM that took down the UI thread (check 2) and the platform-theme crash (check 3).

**Backups.** The starter's `allowBackup="true"` lets `adb backup` read the vault on API 24. After I confirmed on the device, the archive contained `apps/com.example.pvlab.old/f/vault.bin` and `sp/bio.xml` (IV and ciphertext); the keystore key is not included. API 28 and Google cloud backup were **not tested**.

**Tooling only, not app bugs:**
- One `adb install` on API 24 hung; killing that client and rerunning worked. `labenv.sh` now uses a timeout and retries.
- On API 28, `logcat -c` sometimes left older lines in place.
- uiautomator once returned a stale SystemUI title; the screenshot was correct.

## Code that worked (verbatim; ran on API 24 and API 28)

### Gradle and theme
```kotlin
// app/build.gradle.kts in the lab, built with -PbiometricVersion=1.4.0-alpha07 (variant C)
implementation("androidx.biometric:biometric:$biometricVersion")
```
In the version catalog this is `biometric = "1.4.0-alpha07"`. core-ktx stays at 1.19.1.
```xml
<!-- res/values/themes.xml (starter, unchanged): any activity that shows BiometricPrompt needs an AppCompat theme -->
<style name="Theme.PasswordVault" parent="Theme.AppCompat.DayNight.NoActionBar" />
```
```xml
<application
    android:allowBackup="true"
    android:largeHeap="${largeHeap}"
    android:theme="@style/Theme.PasswordVault">
    <activity android:name=".MainActivity" android:exported="true" android:launchMode="singleTop">
        <intent-filter>
            <action android:name="android.intent.action.MAIN" />
            <category android:name="android.intent.category.LAUNCHER" />
        </intent-filter>
    </activity>
    <!-- this host crashed on API 24 when the prompt opened: -->
    <activity android:name=".PlatformThemeActivity" android:exported="true" android:launchMode="singleTop"
        android:theme="@android:style/Theme.Material.Light.NoActionBar" />
</application>
```
`largeHeap` was "false" in A and C and "true" in B. The merged manifest gets `USE_BIOMETRIC` and `USE_FINGERPRINT` from the biometric AAR.

### Kdf.kt
```kotlin
package com.example.pvlab.old

import com.lambdapioneer.argon2kt.Argon2Kt
import com.lambdapioneer.argon2kt.Argon2Mode
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters

/** Argon2id cost parameters: m = memKiB (total, in KiB), t = iterations, p = lanes. */
data class Argon2Params(
    val memKiB: Int = 65536,
    val iterations: Int = 3,
    val lanes: Int = 4,
    val outLen: Int = 32,
)

object Kdf {
    /**
     * argon2kt: the reference C implementation over JNI (libargon2jni.so). The m KiB work area is
     * allocated natively, so it does not count against the Java heap limit.
     */
    fun native(password: ByteArray, salt: ByteArray, p: Argon2Params): ByteArray =
        Argon2Kt().hash(
            mode = Argon2Mode.ARGON2_ID,
            password = password,
            salt = salt,
            tCostInIterations = p.iterations,
            mCostInKibibyte = p.memKiB,
            parallelism = p.lanes,
            hashLengthInBytes = p.outLen,
        ).rawHashAsByteArray()

    /**
     * Bouncy Castle (pure Java). The m KiB work area is allocated on the Java heap
     * (65536 KiB is about 66 MiB of long[] blocks), and lanes are computed one after another.
     */
    fun bouncy(password: ByteArray, salt: ByteArray, p: Argon2Params): ByteArray {
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withSalt(salt)
            .withMemoryAsKB(p.memKiB)
            .withIterations(p.iterations)
            .withParallelism(p.lanes)
            .build()
        val out = ByteArray(p.outLen)
        val generator = Argon2BytesGenerator()
        generator.init(params)
        generator.generateBytes(password, out)
        return out
    }
}

fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
```
The "about 66 MiB" comment is what API 28 measured; API 24 measured about 134 MiB.

### BiometricKey.kt
```kotlin
package com.example.pvlab.old

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec

/**
 * An AndroidKeyStore AES-256-GCM key that can only be used after a strong biometric
 * authentication bound to the individual cipher operation (BiometricPrompt + CryptoObject).
 */
object BiometricKey {
    const val ALIAS = "pvlab_biometric_aes"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    /**
     * Throws (IllegalStateException / InvalidAlgorithmParameterException) when no fingerprint is
     * enrolled, because an auth-per-use key needs an enrolled biometric on API 24-29.
     */
    fun create(alias: String = ALIAS): SecretKey {
        val builder = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true) // API 24+
        if (Build.VERSION.SDK_INT >= 30) {
            // timeout 0 = every use must be authorised, biometric (class 3) only
            builder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        }
        // API 24-29: setUserAuthenticationValidityDurationSeconds is deliberately NOT called. Its
        // default (-1) means "authentication required for every use", which on these releases can
        // only be satisfied by a fingerprint through a CryptoObject - the same meaning as
        // setUserAuthenticationParameters(0, AUTH_BIOMETRIC_STRONG) on API 30+.
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(builder.build())
        return generator.generateKey()
    }

    fun get(alias: String = ALIAS): SecretKey? {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        return ks.getKey(alias, null) as SecretKey?
    }

    fun delete(alias: String = ALIAS) {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (ks.containsAlias(alias)) ks.deleteEntry(alias)
    }

    /** Throws KeyPermanentlyInvalidatedException once a fingerprint was added (or all removed). */
    fun encryptCipher(key: SecretKey): Cipher =
        Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }

    /** Throws KeyPermanentlyInvalidatedException once a fingerprint was added (or all removed). */
    fun decryptCipher(key: SecretKey, iv: ByteArray): Cipher =
        Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        }

    @Suppress("DEPRECATION")
    fun describe(key: SecretKey): String {
        val info = SecretKeyFactory.getInstance(key.algorithm, ANDROID_KEYSTORE)
            .getKeySpec(key, KeyInfo::class.java) as KeyInfo
        return "keySize=${info.keySize} insideSecureHardware=${info.isInsideSecureHardware} " +
            "userAuthRequired=${info.isUserAuthenticationRequired} " +
            "validitySeconds=${info.userAuthenticationValidityDurationSeconds} " +
            "invalidatedByBiometricEnrollment=${info.isInvalidatedByBiometricEnrollment} " +
            "authEnforcedBySecureHw=${info.isUserAuthenticationRequirementEnforcedBySecureHardware}"
    }
}
```
The KeyPermanentlyInvalidatedException comments describe documented behaviour. It never fired on the emulators, for the reason given in check 3.

### BiometricPrompt usage (excerpt of MainActivity.kt)
```kotlin
open class LabActivity : FragmentActivity() {
    private lateinit var prompt: BiometricPrompt
    private var onCipherReady: ((Cipher) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // BiometricPrompt must be created before the activity is started (here in onCreate).
        prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                val cipher = result.cryptoObject?.cipher
                log("bio: onAuthenticationSucceeded type=${result.authenticationType} hasCipher=${cipher != null}")
                val action = onCipherReady
                onCipherReady = null
                if (cipher != null && action != null) {
                    try {
                        action(cipher)
                    } catch (t: Throwable) {
                        log("bio: using the cipher after success FAILED: $t")
                    }
                }
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                log("bio: onAuthenticationError code=$errorCode msg=\"$errString\"")
                onCipherReady = null
            }

            override fun onAuthenticationFailed() {
                log("bio: onAuthenticationFailed (finger not recognised, prompt stays up)")
            }
        })
        // ...
    }

    private fun promptInfo(what: String) = BiometricPrompt.PromptInfo.Builder()
        .setTitle("PV Lab: $what")
        .setSubtitle("API ${Build.VERSION.SDK_INT}, host $hostLabel, biometric ${BuildConfig.BIOMETRIC_VERSION}")
        .setNegativeButtonText("Use master password")
        .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        .setConfirmationRequired(false)
        .build()

    private fun bioEncrypt() {
        val key = try {
            BiometricKey.get()
        } catch (t: Throwable) {
            return log("bio-enc: key lookup FAILED: $t")
        } ?: return log("bio-enc: no key, run bio-keygen first")
        val cipher = try {
            BiometricKey.encryptCipher(key)
        } catch (e: KeyPermanentlyInvalidatedException) {
            return log("bio-enc: KeyPermanentlyInvalidatedException at cipher.init: $e")
        } catch (t: Throwable) {
            return log("bio-enc: cipher.init FAILED: $t")
        }
        onCipherReady = { c ->
            val iv = c.iv
            val ct = c.doFinal(SECRET.toByteArray())
            prefs().edit()
                .putString("iv", Base64.encodeToString(iv, Base64.NO_WRAP))
                .putString("ct", Base64.encodeToString(ct, Base64.NO_WRAP))
                .apply()
            log("bio-enc: cipher.doFinal after auth OK ivLen=${iv.size} ctLen=${ct.size}")
        }
        log("bio-enc: authenticate(promptInfo, CryptoObject(cipher)) called")
        prompt.authenticate(promptInfo("encrypt"), BiometricPrompt.CryptoObject(cipher))
    }

    private fun bioDecrypt() {
        val iv = prefs().getString("iv", null)?.let { Base64.decode(it, Base64.NO_WRAP) }
        val ct = prefs().getString("ct", null)?.let { Base64.decode(it, Base64.NO_WRAP) }
        if (iv == null || ct == null) return log("bio-dec: nothing stored, run bio-enc first")
        val key = try {
            BiometricKey.get()
        } catch (t: Throwable) {
            return log("bio-dec: key lookup FAILED: $t")
        } ?: return log("bio-dec: no key")
        val cipher = try {
            BiometricKey.decryptCipher(key, iv)
        } catch (e: KeyPermanentlyInvalidatedException) {
            return log("bio-dec: KeyPermanentlyInvalidatedException at cipher.init: $e")
        } catch (t: Throwable) {
            return log("bio-dec: cipher.init FAILED: $t")
        }
        onCipherReady = { c ->
            val plain = String(c.doFinal(ct))
            log("bio-dec: cipher.doFinal after auth OK plaintextMatches=${plain == SECRET}")
        }
        log("bio-dec: authenticate(promptInfo, CryptoObject(cipher)) called")
        prompt.authenticate(promptInfo("decrypt"), BiometricPrompt.CryptoObject(cipher))
    }
}
```

### AtomicFiles.kt
```kotlin
package com.example.pvlab.old

import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileOutputStream

/** Crash-safe replacement of a small file: write temp, fsync it, rename over target, fsync dir. */
object AtomicFiles {
    fun write(dir: File, name: String, bytes: ByteArray) {
        val target = File(dir, name)
        val tmp = File(dir, "$name.tmp")
        FileOutputStream(tmp).use { out ->
            out.write(bytes)
            out.flush()
            out.fd.sync() // fsync(2) of the file data
        }
        Os.rename(tmp.path, target.path) // rename(2): atomically replaces an existing target
        fsyncDir(dir) // makes the rename itself durable
    }

    fun fsyncDir(dir: File) {
        val fd = Os.open(dir.path, OsConstants.O_RDONLY, 0)
        try {
            Os.fsync(fd)
        } finally {
            Os.close(fd)
        }
    }
}
```

### SensitiveClipboard.kt
```kotlin
package com.example.pvlab.old

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle

object SensitiveClipboard {
    /** Same string as ClipDescription.EXTRA_IS_SENSITIVE (constant added in API 33). */
    const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"

    fun copy(context: Context, label: String, text: CharSequence) {
        val cm = context.getSystemService(ClipboardManager::class.java)
        val clip = ClipData.newPlainText(label, text)
        // ClipDescription.setExtras exists since API 24; older releases simply ignore the flag.
        clip.description.extras = PersistableBundle().apply { putBoolean(EXTRA_IS_SENSITIVE, true) }
        cm.setPrimaryClip(clip)
    }

    fun clear(context: Context) {
        val cm = context.getSystemService(ClipboardManager::class.java)
        if (Build.VERSION.SDK_INT >= 28) {
            cm.clearPrimaryClip()
        } else {
            // No clearPrimaryClip() before API 28: overwrite with an empty plain-text clip.
            cm.setPrimaryClip(ClipData.newPlainText("", ""))
        }
    }

    /** What any app reading the clipboard now gets as text (null = no clip at all). */
    fun peek(context: Context): String? {
        val cm = context.getSystemService(ClipboardManager::class.java)
        val clip = cm.primaryClip ?: return null
        if (clip.itemCount == 0) return "<clip with 0 items>"
        return clip.getItemAt(0).coerceToText(context)?.toString()
    }
}
```
`Probes.kt`, the full `MainActivity.kt`, the scripts and the device configs are in `artifacts`.

## Not tested or not settled

See `unresolved`. The main gaps:
- Invalidation on new enrollment: the emulator HAL never changes the authenticator id.
- API 25–27 were not run.
- Real ARM phones and TEE keys.
- The Samsung API 28 path of biometric 1.1.0.
- biometric 1.1.0's own dialog under a platform theme was never reached.
- Removing the screen lock.
- The API 37 control was not run.

## Unresolved

- REPORT.md was not saved: the harness refused the Write ('Subagents should return findings as text, not write report files'). The report exists only in this structured output. README.txt, the scripts, logs/ and shots/ are on disk.
- Invalidation on new enrollment (KeyPermanentlyInvalidatedException after adding a finger) cannot be tested on these images. The emulator fingerprint HAL reports a constant authenticator id (7749363893351949254 on both API 24 and 28) that never changes, so the old key kept working and the new finger decrypted. Needs a real phone.
- API 25, 26 and 27 were not run. Only API 24 and API 28 images were used. API 25-27 take the same library-dialog path as API 24 (below 28), so the same 1.1.0 failure and the same theme requirement are expected but not observed.
- No real hardware: argon2kt was loaded only as x86_64 (the APK also ships arm64-v8a, armeabi-v7a and x86, but those were not loaded). Keys were software-backed (insideSecureHardware=false), so TEE/StrongBox enforcement was not tested. KDF timings are debug-build emulator numbers on a fast host.
- The heap limits were simulated with adb root, setprop and a framework restart. Real devices' growth limits, heap fragmentation and low-memory killer behaviour may differ. The UI-thread OOM crash happened once in two identical runs, so it depends on timing.
- biometric 1.1.0 on Samsung API 28 devices (crypto_fingerprint_fallback_vendors=samsung sends crypto auth through 1.1.0's own dialog and the stubbed FingerprintManagerCompat) was not tested: the emulator reports manufacturer 'unknown'.
- biometric 1.1.0's own fingerprint dialog under a platform theme could not be observed: with core 1.19.1 it errors out (code 12) before any dialog is created.
- biometric 1.4.0-alpha05 and alpha06 were checked only statically (they call the framework FingerprintManager directly). 1.4.0-alpha07 is an alpha release. Its FingerprintDialogActivity and the new AuthenticationRequest API were not exercised.
- Not tested: removing the screen lock or all fingerprints (which should delete or invalidate auth-bound keys); rotation or process death while the prompt is shown; the prompt in night mode.
- The optional API 37 control (user's device started read-only on port 5570) was not run.
- Not tested: adb backup on API 28, Google cloud auto-backup, and keyboard clipboard histories (Gboard or Samsung keep their own copies). Core library desugaring as a fix for the API 24 java.time and java.util.Base64 gaps was not tested.
