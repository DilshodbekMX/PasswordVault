# PasswordVault: build plan

Date 2026-10-06. The order of work for [architecture.md](architecture.md) ("A" below; "A §4.9" is its section 4.9). Other inputs are named as in that file: lock.md ("L"), ux.md ("U"), formats.md ("F") with formats-review.md ("FR", findings P1 to P18), i18n.md ("I"), search.md ("S"), VERIFICATION.md items [V-xx], and the lab reports. Revised the same day after a coverage review and a buildability review and after the audit of lock.md; A's section "Review" lists what changed in both files.

## 1. How to use this plan

**Waves, sub-waves and packages.** A package is one agent's work in its own git worktree. A sub-wave is a set of packages built at the same time: at most six, because this machine has reset under larger fan-outs. A package depends only on sub-waves that are already merged. Within a sub-wave no two packages own the same file, so their branches merge in any order without conflicts.

| Wave | Packages | Result |
|---|---|---|
| 0 | 0.1 to 0.3 | Ground: the build and its checks, private devices, and the inputs brought into the repository and frozen |
| 1 | 1.1 to 1.4 | The `:core` contracts; search; the pure lock and password pieces; text and CSV primitives |
| 2A | 2.1 to 2.6 | Engines that need only wave 1 (crypto, vault file, storage, entry rules and edits, generator) and the app's contracts and shell |
| 2B | 2.7 to 2.12 | Android adapters, window and UI kit, settings and formatting, lock records, guards, strength |
| 3A | 3.1 to 3.6 | **Walking skeleton:** set up with a master password, an encrypted vault file on disk, the list, add and edit, lock and unlock with the master password |
| 3B | 3.7 to 3.12 | Entry detail, clipboard, Keystore adapters, the lock engine's core, format writers, window checks |
| 3C | 3.13 to 3.15 | The lock's remaining flows of part I, the importer |
| 4A, 4B | 4.1 to 4.5 | PIN and fingerprint, then the lock's emulator scripts |
| 5 | 5.1 to 5.5 | Backup, restore, import and export; changing and replacing the master password |
| 6A, 6B | 6.1 to 6.4 | The four languages |
| 7 | 7.1 to 7.3 | The security check, the guided change, help |
| 8A, 8B | 8.1 to 8.4 | Release |

**Schedule.** From sub-wave 3B on, section 12 says when each package is built and how a sub-wave is integrated: packages run in *rounds*, and the integration is split into a merge and a device check. What a package owns, its inputs and its acceptance lines stay as sections 5 to 10 give them; where section 12 differs from them on order, tags or the branch a package starts from, section 12 wins.

**Which document wins.** VERIFICATION.md over the research. lock.md for the lock. A for everything else it covers; A §6 decides the cross-document questions. ux.md, formats.md, i18n.md and search.md give screens, wording and plain formats except where A §6 or lock.md overrule them. Package 0.3 brings those four documents, and the app's name in lock.md, in line before wave 1; until it is merged, its list of edits says which input is stale.

**Rules for every package.**

1. **Branch** `w<sub-wave>-<id>` from the tag `wave-<previous sub-wave>-ok`, in a worktree under `/home/asus/pvwork/<branch>/` (outside the repository and outside `/tmp`, which a reboot empties). One package, one branch, one worktree.
2. **Own files only.** Create and change only what "Owns" and "May also edit" list. Everything else is read-only. If another file must change, report an *interface request* to the integrator, who makes it on `main` at once as a small tagged commit (`iface-<n>`); merge that tag and go on. Nobody waits for the end of the wave.
3. **Contracts are fixed.** The signatures of A §3, the formats of A §4 and the records of L §5 change only through the documents first (A §3). A signature names only types of its own package or of an earlier wave.
4. **Seams.** A §3.6 lists them: one `…Module.kt` per area, the activity hooks, one route file per package, the debug command list. Package 2.5 creates every one with a stub; the package named in A §3.6 replaces the body of *its* file. Nobody else edits `AppGraph.kt`, `MainActivity.kt`, `AppNavHost.kt` or `Route.kt`; the integrator adds a route key on request.
5. **Strings.** One file per screen area, `RES/values/strings_<area>.xml`, owned with the screens. Every name starts with its area (`setup_…`); shared words live only in `strings_common.xml`. No literal text in `Text(` or `contentDescription` (R-13). English only until wave 6; from wave 7 every new string comes in all four languages in the same package (I §5). Each UI package walks its screens once in the `en-XA` pseudo-locale.
6. **Tests come with the code**, in the test directory that mirrors the package. Every acceptance line is marked **[JVM]**, **[device]** (an instrumented test) or **[script]** (a script under `tools/emu/`). Port fakes go in `:testing`, fakes of the app's interfaces in `APP/fake/`, `@Preview` functions in `app/src/debug/`. A [device] test that applies only to some API levels is filtered with `@SdkSuppress(minSdkVersion = …)` or `maxSdkVersion` (`androidx.test.filters`, in `androidx.test:runner`), never with `org.junit.Assume`: AGP 9.4.1 writes a connected test that ends in `AssumptionViolatedException` into the JUnit XML as a failure, so `tools/emu/adb.sh --connected` fails that device, and the integration checks run every connected test on all three API levels. A package is accepted when its lines pass on its branch *and* the integration check passes after the merge.
7. **Build environment.** Call Gradle only through `tools/gradle.sh …` (made in 0.1): it sets `JAVA_HOME=/home/asus/.jdks/jdk-25` and `ANDROID_HOME=/home/asus/Android/Sdk` (a new worktree has no `local.properties`), takes one of two machine-wide build slots and waits for one, and runs a build once more if it died with "daemon disappeared" or left an `hs_err_pid` file. `tools/check.sh` uses it too.
8. **Devices.** Only private emulators started by `tools/emu/boot.sh <api>` (0.2), from the persistent base under `/home/asus/pvlab/`, read-only. `boot.sh` hands out the even ports 5556 to 5578 through a lock file and lets at most six package devices run at once; 5554 is left to the user's own device, which nobody touches; 5580 (API 37), 5582 (API 28) and 5584 (API 24) are the integrator's. Every `adb` call names its device with `-s`; every connected test run sets `ANDROID_SERIAL` to the package's own serials; a script refuses a serial it did not start. Connected tests run through `tools/emu/adb.sh --connected SERIAL...` (or are followed by `tools/emu/adb.sh --connected-results app/build/outputs/androidTest-results/connected/debug SERIAL...`), which passes only when every serial has a passing report: AGP 9.4.1 ends with BUILD SUCCESSFUL when it skips a device it could not install on, so BUILD SUCCESSFUL alone does not count. Never `adb kill-server`. Android Studio sometimes restarts the adb server, so scripts wait up to 60 s for their device to come back instead of failing. Stop your devices when done.
9. **Commits** after each working step, with a message that says what is done and what is next, so that another agent can continue a package that was cut off from its branch alone. No push unless the user asks.

**The integrator.** One agent per sub-wave, working alone on `main`. It merges the branches in any order; applies interface requests during the sub-wave; owns, between sub-waves, the Gradle files, `tools/check.sh`, `tools/gradle.sh`, the shared parts of `tools/emu/` (`boot.sh`, `adb.sh`, `app.sh`, `uidump/`, `smoke.sh`, `checks/`), the source-rule tests and their scanner (`CORE-T/arch/`, `APP-T/arch/`, `TEST/arch/`; an allow-list there changes only through an interface request that says why), `debug/DebugCommands.kt` and the route keys; writes each integration check as `tools/emu/checks/<wave>.sh`; may change any file to make a check pass, and lists every such change in the merge commit's message; tags `wave-<id>-ok`. It regenerates the dependency locks and verification metadata whenever a version changes, flips `ResourcesTest` to failing at wave 6 (and lint's `MissingTranslation`, a warning from the start of 6A until then), and moves `APP/fake/` to `app/src/debug/` as soon as no file under `src/main` names a fake (expected after wave 7).

**Path names used below.**

| Short | Path |
|---|---|
| `CORE/` | `core/src/main/java/io/github/dilshodbekmx/passwordvault/core/` |
| `CORE-T/` | `core/src/test/java/io/github/dilshodbekmx/passwordvault/core/` |
| `CORE-R/` | `core/src/test/resources/` |
| `TEST/` | `testing/src/main/java/io/github/dilshodbekmx/passwordvault/testing/` |
| `APP/` | `app/src/main/java/io/github/dilshodbekmx/passwordvault/` |
| `APP-T/` | `app/src/test/java/io/github/dilshodbekmx/passwordvault/` |
| `APP-AT/` | `app/src/androidTest/java/io/github/dilshodbekmx/passwordvault/` |
| `APP-D/` | `app/src/debug/java/io/github/dilshodbekmx/passwordvault/` |
| `RES/` | `app/src/main/res/` |

**The check script.** `tools/check.sh` (made in 0.1) has these parts; "the full check" means `tools/check.sh all`.

| Part | Runs | Passes when |
|---|---|---|
| `unit` | `:core:testDebugUnitTest` under `tr-TR` and again under `fa-IR`, `:app:testDebugUnitTest` | No failure |
| `lint` | `:core:lintDebug :app:lintDebug` | No error (the list of I §1.1 is set to error) |
| `build` | `:app:assembleDebug :app:assembleStaging :app:assembleRelease` | All three build; the checks of A §8.5 that are Gradle tasks pass (the source scans run in `unit`; R-3 is a device test) |
| `offline` | The merged-manifest check for every variant; `aapt2 dump permissions` on the three APKs; the class scan of A §8.5 "Offline" (comments ignored) | No `INTERNET`, no permission outside R-9's list, no match |

Device checks are not in `check.sh`; they are the `[device]` and `[script]` lines and the integration checks.

---

## 2. Wave 0: ground

Three packages, side by side.

### 0.1 Build and checks
- **Owns:** `.gitignore`, `core/.gitignore`, `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`, `gradle/verification-metadata.xml`, the lock files, `core/build.gradle.kts`, `app/build.gradle.kts`, `testing/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `app/src/{debug,staging,backupCheck}/AndroidManifest.xml`, `app/src/main/keepRules/rules.keep`, `RES/xml/file_paths.xml`, `RES/resources.properties`, `tools/check.sh`, `tools/gradle.sh`, the source-rule tests `CORE-T/arch/` and `APP-T/arch/` and the scanner they share, `TEST/arch/`, and the classes the manifests name, empty: `APP/PasswordVaultApp.kt`, `APP/clipboard/ClipboardClearReceiver.kt`, `APP-D/debug/{DebugCommandReceiver,DebugCommands}.kt` (the receiver dispatches to the list, which is empty).
- **Inputs:** A §7 (dependencies, build types, manifest), A §8.5, I §1.1 and §1.4.2, L §15.5 and §16.3 "Build", the existing `CheckNoInternetPermission` task, today's merged manifests under `app/build/intermediates/merged_manifests/`.
- **Does:**
  1. **Stops tracking build output.** `core/build/` is in git today (214 files) because only the root `/build` is ignored. Add `build/` for every module and run `git rm -r --cached core/build`.
  2. Adds the module `:testing` (an Android library like `:core`, no Android calls) that depends on `:core` and is a test dependency of `:core` and `:app`; proves the wiring with one fake used from both modules' tests. If Gradle refuses the `:core` ↔ `:testing` edge, it uses AGP test fixtures of `:core` instead and says so in A §2 through an interface request.
  3. Adds every dependency of A §7.1 to the catalog and the modules, test libraries included, the serialization plugin to `:app` (navigation keys), the build types `staging` (it also compiles `src/debug/java`) and `backupCheck`, the ABI filters, `BuildConfig.ALLOW_SOFTWARE_KEYSTORE` and `SECURE_WINDOW`, the locale configuration and lint errors of I §1.1, the two JVM locales of the `unit` part, and a pass-through of every Gradle property starting with `pv.` into the unit tests' system properties (search's three programs run as tests behind such a property; see 1.2).
  4. Sets `org.gradle.caching=true`, turns on dependency locking and writes `gradle/verification-metadata.xml` with SHA-256 sums.
  5. Writes the manifests of A §7.2, including `DebugCommandReceiver` exported behind `android.permission.DUMP` in `debug` and `staging`.
  6. Adds the build checks R-1, R-2, R-5, R-7, R-8, R-9 (per variant), R-12 and R-14 as Gradle tasks tied to `assemble`, and R-4, R-6, R-10, R-11, R-13 as unit tests that scan the sources with comments ignored. (R-3, the place of the lock directory, is a device test of 2.7.)
- **Accepted when:** `tools/check.sh all` passes [JVM]; the source scans, run once against `prototypes/` and `docs/lab/*-artifacts/`, report the matches they should and no false ones (`java.net.IDN` in `WebAddress.kt` and `java.time` in comments are allowed) [JVM]; `git ls-files | grep -c '/build/'` prints 0; the release APK's `lib/` has only `arm64-v8a` and `armeabi-v7a` and the staging APK also has `x86_64`; R-9 passes for all four variants against their merged manifests.

### 0.2 Devices
- **Owns:** `tools/emu/{boot.sh,adb.sh,stop.sh,smoke.sh,README.md}`, `tools/emu/checks/` (empty; the integrator's from here on), and the persistent directory `/home/asus/pvlab/` outside the repository.
- **Inputs:** `docs/lab/old-android.md` §0 (the images and their sha1 sums), `docs/lab/old-android-artifacts/README.txt`, the project memory's emulator recipe (a private AVD made from a copy of the user's AVD *config*, never the user's AVD or its files).
- **Does:** downloads the Android 7.0 and 9 AOSP images into `/home/asus/pvlab/sysimg/` and checks their sha1 sums; makes the base AVDs for API 24, 28 and 37 in `/home/asus/pvlab/avd/` and provisions each once (screen-lock PIN 1111, two enrolled fingers, animations off, stay awake), so that later runs can be read-only; writes `boot.sh` (port lock file, at most six package devices, waits for boot, prints the serial), `adb.sh` (every call with `-s`, waits for the device after an adb server restart, marks steps `needs-debuggable` and `needs-root` and prints SKIPPED for them where they cannot run), `stop.sh`, and `smoke.sh <variant> <serial>` at its first stage (install and start).
- **Accepted when:** [script] `boot.sh` starts API 24, 28 and 37 read-only, each on a port it handed out; the debug app installs on each; the template's `ExampleInstrumentedTest` passes on exactly those three with `ANDROID_SERIAL` set while another emulator is running; `smoke.sh staging` installs and starts the app on API 24 and 37; nothing under `~/.android/avd/Medium_Phone.avd` was touched.

### 0.3 Inputs into the repository
**Its first job, before anything else in this plan:** several inputs exist only under `/tmp/claude-1000/…/scratchpad/`, which a reboot deletes (this machine reset today).
- **Owns:** `docs/lab/clipboard.md`, `docs/lab/clipboard-artifacts/`, `tools/validate/`, `CORE-R/formats/`, and the edits listed below to `docs/design/ux.md`, `formats.md`, `search.md`, `i18n.md` and `lock.md`.
- **Does:**
  1. **Copies.** The clipboard lab's report if it has reached `docs/lab/` by then, otherwise its working tables (`lab/clipboard-storage/results/report-tables.md`), `clipexp.py`, its `SensitiveClipboard.kt` and the kit (`clipboard-lab-kit.tar.gz`, unpacked), marked "provisional", into `docs/lab/clipboard.md` and `docs/lab/clipboard-artifacts/`. The plain-export checks of `prototypes/formats/validate/` (`check_csv.py`, `check_json.py` with FR P18's fix, `check_xlsx.py`, `compare_cells.py`, the LibreOffice conversion) into `tools/validate/` with a `requirements.txt`, `setup.sh` (makes the Python venv under `/home/asus/pvlab/`) and `run.sh <dir>` (checks the three exported files in a directory). The fixtures of F §8.5 (the Chrome CSV, the Bitwarden JSON mock) and the Uzbek Cyrillic header file of FR P6 into `CORE-R/formats/`, with a `SOURCES.md` that names each one's origin and licence. The checks of protected Excel files stay in `prototypes/formats/` with `OfficeCrypto.kt` (A D-16).
  2. **Edits the documents** so that no package receives overruled text:
     - ux.md: the twelve changes of L §16.2; A D-9b (no master-password check at Save: §5.14 "Same as the master password", §5.10); A D-6c (§5.3 allows apostrophes); §4 and §12 check 3 lose `getLastAuthenticationTime` (L §10.2 dropped it); A D-14's two "never" cases in §6's tip and §7.6 Help; A D-23 ("PasswordVault"); A D-24 at §5.23; A D-25 at §6 item 5; A D-7's two defaults; U §5.24 in L F11's order.
     - formats.md: the grammar of A D-10a into §4.4; A D-10c, D-10d, D-15, D-16, D-17; FR P1 to P17 where they change the text.
     - search.md: A D-11.
     - i18n.md: the terms of L §16.3 "i18n" and A D-25's "Copied text" into the glossary of §3; §1.4.6 gains the plan for library string overrides (6.3).
     - lock.md: "Password Vault" becomes "PasswordVault" (A D-23); nothing else.
- **Accepted when:** the copied files exist and `tools/validate/setup.sh` and `run.sh` work on the sample files that the prototype wrote (if they survived; otherwise on files written by hand from F §4 to §6); each edit above is made, and none of these phrases is left where it is overruled: "Needs Android 9 or newer", "This PIN is too easy to guess", "Enter your PIN" without "app", "getLastAuthenticationTime", "Password Vault", a check against the master password at Save, the refusal of apostrophes.

**Integration check for wave 0:** the integrator merges the three branches and runs 0.1's and 0.2's acceptance on `main`; tags `wave-0-ok` and `inputs-frozen`. From here on a change to an input document is an interface request.

---

## 3. Wave 1: contracts and self-contained moves

Four packages, side by side. None of them names a type of another.

### 1.1 Core contracts
- **Owns:** `CORE/util/{Clock,RandomSource,Bytes,B64,Uuid,ParseError}.kt`; `CORE/model/{Entry,CustomField,HistoryItem,Vault,Draft,FileRef,Edited,EditTexts}.kt` (types only); `CORE/crypto/{Argon2id,Argon2Params,Aead,KdfUnavailable,MasterKdf,CryptoFactory}.kt`; `CORE/vaultfile/{VaultCodec,VaultHeader,PasswordSlot,VaultFile,FileCheck,BodyResult,BuildInfo,VaultCodecs}.kt`; `CORE/storage/{FilePrimitives,VaultStorage,StoreTypes,VaultStores}.kt`; `CORE/lock/record/{RecordFiles,RecordStore,PinRecord,FingerprintRecord,StateRecord,RecordRead}.kt` (types only); `CORE/lock/policy/{Ks,KeystoreFailure}.kt`; `CORE/lock/flow/{PinKey,FingerprintKey,DeviceKeys,KeyCreate,VaultPort,VaultTypes,MasterProof,LockScreen,Notice,Outcomes}.kt`; `CORE/generator/GeneratorOptions.kt`; `CORE/strength/SecurityReport.kt`; `TEST/{FakeClock,FakeRandom}.kt`; `CORE-T/util/{Bytes,B64,Uuid}Test.kt`; `CORE-T/ContractsTest.kt` (the source test of its acceptance, and the tests of `MasterProof`, content equality and `toString`).
- **Inputs:** A §3.1 to §3.4 and §3.7 (transcribe; every case of a result comment becomes a class), §4.3 (the shapes of `Vault` and `Entry`); L §5.4 to §5.6 as revised by its audit (the record types), §13.2, §14.1; `prototypes/formats/src/pv/formats/Model.kt` (start of `Entry`).
- **Implements:** the declarations; `Bytes`, `B64`, `Uuid`; the factory objects with bodies that throw `NotImplementedError`.
- **Accepted when:** [JVM] the three util tests pass; a source test shows that no file of this package names a type of `core.search`, `core.lock.pin`, `core.formats`, `StrictJson` or `TextHygiene`; `tools/check.sh all` passes.

### 1.2 Search and script converters
- **Owns:** `CORE/search/`, `CORE/text/`, `CORE-T/search/`, `CORE-T/text/`, `CORE-T/tools/` (the three programs of `prototypes/search/build.gradle.kts` as tests that run only when `-Ppv.bench`, `-Ppv.uzArgs=…` or `-Ppv.evalArgs=…` is given), and the removal of `prototypes/search/`.
- **Inputs:** `prototypes/search` (move with `git mv`; packages become `…core.search` and `…core.text`), S all, A D-11, U §5.11.
- **Implements:** `searchKey`, `SearchIndex`, `matchedRanges`, `WebAddress` (it keeps `java.net.IDN`, which the offline scan allows), `UzbekScript`; new: ş → s and ç → c in the second tier; `KeyboardLayout.otherLayout(query)` for the ЙЦУКЕН ↔ QWERTY retry.
- **Accepted when:** [JVM] all 527 moved tests pass plus: "besiktas" finds "Beşiktaş", "garcon" finds "Garçon", "штые" retried gives the hits of "inst", and no test of S §6 that pins a limit changed without a note; `tools/gradle.sh :core:testDebugUnitTest --tests '*SearchBenchmark*' -Ppv.bench` prints a median under 1 ms per keystroke.

### 1.3 Pure lock and password pieces
- **Owns:** `CORE/lock/pin/`, `CORE/lock/guard/{Credit,PracticeSchedule}.kt`, `CORE/lock/policy/SessionPolicy.kt`, `CORE/crypto/MasterPasswordBytes.kt`, and their tests `CORE-T/lock/pin/`, `CORE-T/lock/guard/{Credit,Practice}Test.kt`, `CORE-T/lock/policy/SessionPolicyTest.kt`, `CORE-T/crypto/MasterPasswordBytesTest.kt`.
- **Inputs:** L appendix A (the blocklist code), §6.9, §8.1, §8.3, §10.1 to §10.3, appendix F; A §4.6 and §4.13.
- **Implements:** `PinBlocklist` over a `ByteArray` of six ASCII digits (the reference takes a `String`; the rules and counts do not change), `Credit`, `PracticeSchedule`, `SessionPolicy` (pure delay arithmetic over elapsed times, the no-touch timer that stands still while stopped or external), `MasterPasswordBytes` with `same`.
- **Accepted when:** [JVM] L T1 (126,337 refused, the nine counts, the SHA-256 of appendix A), T10 (with the three rows of appendix F), T11, T19's arithmetic; `MasterPasswordBytes` gives the bytes of L §4.3 for every spelling there, the Greek line included, and of A §4.13 for the white-space, Greek and white-space-only vectors, is idempotent and NFD-invariant over 10,000 random strings, and refuses an unassigned code point at creation.

### 1.4 Text, CSV and spreadsheet primitives
- **Owns:** `CORE/util/{StrictJson,TextHygiene}.kt`, `CORE/formats/{Csv,TextDecoding,IsoTime,Xlsx}.kt`, `CORE-T/util/{StrictJson,TextHygiene}Test.kt`, `CORE-T/formats/primitives/`.
- **Inputs:** `prototypes/formats/src/pv/formats/{Csv,IsoTime,Xlsx}.kt` (copy; `VaultXlsx` stays for 3.11), F §2 rules 8 and 9, §6, §8.2; FR P2, P3, P4, P7, P9, P12, P13 (the `sep=` line).
- **Implements:** the generic CSV reader and writer with a row-length report (P4); text decoding that never silently switches a UTF-8 file to Windows-1251 (P2, P12); the `.xlsx` writer with a fixed local zip time (P9) and U+FFFD for unpaired surrogates (P7); `StrictJson` (refuses duplicate keys and nesting over a given depth, catches `StackOverflowError` (P3), and fails with its own exception that carries an offset and a reason, never input text); `TextHygiene`.
- **Accepted when:** [JVM] one test per finding above, built at the level of the primitive (the end-to-end tests are 3.15's); the `.xlsx` bytes are identical under the time zones UTC, Asia/Tashkent and America/New_York; no exception message of `StrictJson` contains input text.

**Integration check for wave 1:** merge the four branches; `tools/check.sh all`; tag `wave-1-ok`.

---

## 4. Wave 2: engines, the app's shell, Android foundations

### Sub-wave 2A (after `wave-1-ok`)

#### 2.1 Cryptography
- **Owns:** `CORE/crypto/{JcaAesGcm,BouncyCastleArgon2id,GuardedArgon2id,GatedMasterKdf}.kt`, the body of `CryptoFactory.kt`, `CORE-T/crypto/` except `MasterPasswordBytesTest.kt`, and the move of `Argon2KnownAnswerTest.kt` into it.
- **Inputs:** A §3.2, §4.6; crypto.md §3, §4; [V-C2], [V-C10]; `docs/lab/old-android-artifacts/Kdf.kt`.
- **Accepted when:** [JVM] both Argon2id vectors; the two derived keys of L §4.3; AES-GCM round trips and tag failures; `GuardedArgon2id` with no native implementation, with too little free heap (gives `KdfUnavailable`, calls nothing) and with an `OutOfMemoryError`; `GatedMasterKdf` never runs two derivations at once.

#### 2.2 Vault file codec
- **Owns:** `CORE/vaultfile/{HeaderCodec,BodyCodec,DefaultVaultCodec}.kt`, the body of `VaultCodecs.kt`, `CORE-T/vaultfile/`, `CORE-R/vectors/` (the two files of A §4.13, written by A's appendix script and committed).
- **Inputs:** A §4.1 to §4.6 and §4.13, §5.6 (parser errors); storage.md §5.1 rule 2 (unknown keys).
- **Uses:** `Aead`, `RandomSource`, `Clock` through their interfaces, `StrictJson` (1.4). Its tests call `javax.crypto` through a small helper of their own, because 2.1 is built beside it.
- **Accepted when:** [JVM] everything A §8.1 lists for `vaultfile`, first of all vectors A and B reproduced byte for byte from their inputs and read back.

#### 2.3 Storage engine
- **Owns:** `CORE/storage/{VaultStore,Housekeeping,Names}.kt`, the body of `VaultStores.kt`, `CORE-T/storage/`, `TEST/storage/{FakeFiles,FakeCodec}.kt`, `CORE-T/integration/VaultRoundTripTest.kt`.
- **Inputs:** A §4.7 to §4.12; [V-S1] to [V-S6]; `docs/lab/storage-durability.md`; `docs/research/verification/storage_code.md` (the three pruning scenarios); L §16.3 "Storage".
- **Uses:** `VaultCodec` and `FilePrimitives` as interfaces.
- **Accepted when:** [JVM] everything A §8.1 lists for `storage`: the four file states, every kill point of A §4.9 for `commit`, `stageVault`, `stageRekey` and `publish`, the several-unlocks test of A §4.10, the swap and the merge. `VaultRoundTripTest` (create with a password, reopen with the password, edit, reopen with the key, change the password, reopen with both, put aside and swap back) is skipped while a factory still throws and must pass at the integration check of 2A.

#### 2.4 Entry rules and edits
- **Owns:** `CORE/model/{EntryRules,VaultEdits}.kt`, `CORE/model/edits/`, `CORE-T/model/`.
- **Inputs:** U §2, §5.12, §5.14 (save rules, the draft), §5.17 (guided change), §5.21 ("Undo this import", "Backup file into an existing vault"), §5.23; A §4.3, §4.11 (merge), §4.12, D-24; FR P1.
- **Uses:** `WebAddress.hostOf` (1.2), `TextHygiene` (1.4).
- **Implements:** `EntryRules` (limits in code points, control characters and unpaired surrogates refused, names trimmed, title and URL rules of U §2); every change as `(Vault, nowMs) -> Edited<T>`: add, update (history capped at 5, `passwordChangedAt`), favourite, start, retry, confirm and revert a guided change, ignore in the check, delete, restore, delete for ever, the purge of D-24 as a pure function of a cutoff, set and clear the draft, note an export or backup, add imported entries (ids kept only when free), merge another vault's entries, replace the whole vault (import undo), restore's reset of A §4.12.
- **Accepted when:** [JVM] every limit of U §2 at, below and above its bound, in code points, not UTF-16 units; the title filled from the host; U §12 check 5 (seven retries, then "put back"); no edit yields two entries with one id; the purge removes only entries due at the given cutoff; stored titles come from `EditTexts`.

#### 2.5 App contracts and shell
- **Owns:** `APP/{AppGraph,MainActivity}.kt` and the body of `APP/PasswordVaultApp.kt`; every `…Module.kt` of A §3.6 as a stub; the hook files as stubs (`APP/window/WindowPolicy.kt`, `APP/lock/BiometricHost.kt`, `APP/files/DocumentLaunchers.kt`, `APP/clipboard/ClipboardRoot.kt` with `ClipboardRowSlot`, `APP/ui/shell/FirstCopyTip.kt`, `APP/ui/security/SecurityBadge.kt`); `APP/nav/`; every route file of A §3.6 with placeholder screens; `APP/ui/shell/`; `APP/fake/`; the interface files `APP/data/{VaultRepository,VaultState,ListModel,SecurityReports}.kt`, `APP/lock/{LockManager,LockUiState,LockIntent,LockStatus,LockDebug,BiometricLauncher}.kt`, `APP/session/Session.kt`, `APP/clipboard/{SecretClipboard,ClipStatus}.kt`, `APP/files/{DocumentIo,DocRef,FileTask,Place}.kt`, `APP/settings/{AppSettings,SettingValues}.kt`, `APP/i18n/{UiText,Collation}.kt`, `APP/platform/AppDispatchers.kt`; `RES/values/strings.xml` (`app_name` = "PasswordVault"); `RES/values/strings_shell.xml` (the shell's strings: the tab labels translatable, the placeholder texts `translatable="false"`); `RES/drawable/ic_shell_{passwords,security,settings}.xml` (the bottom bar's icons); `tools/emu/shell/` (`walk.sh`, its [script] line); `APP-T/nav/`, `APP-AT/nav/`. It removes `APP/ui/PlaceholderScreen.kt` and the template's two example tests.
- **Inputs:** A §2.2, §3.5, §3.6, §5.4; U §4 (routes, the three top-level states); L §13.1, §13.2.
- **Implements:** the declarations; scriptable fakes of every app interface (`FakeVaultRepository`, `FakeLockManager`, `FakeAppSettings`, `FakeSecretClipboard`, `FakeDocumentIo`, `FakeListModelSource`, `FakeSecurityReports`, `FakeLauncher`); module stubs that return them; `MainActivity` calling exactly the hooks of A §3.6; `AppRoot`, `UnlockedScaffold`; `NavState` with its three back stacks.
- **Accepted when:** [JVM] `NavState` follows `FakeLockManager`'s states; [device] a lock keeps the route ids and clears the unlocked ViewModels, and a recreation keeps the route; [script] the debug app starts on API 24, 28 and 37 and walks the placeholder routes; `tools/check.sh all` passes.

#### 2.6 Generator
- **Owns:** `CORE/generator/` except `GeneratorOptions.kt`, `core/src/main/resources/wordlists/`, `CORE-T/generator/`.
- **Inputs:** U §5.3 (the suggested passphrase), §5.15; crypto.md §9; A D-6a; [V-C13].
- **Accepted when:** [JVM] everything A §8.1 lists for `generator`; the word list has 1,295 words, none with a hyphen, and its licence note names the change.

**Integration check for 2A:** merge; `tools/check.sh all` with `VaultRoundTripTest` running; the debug app starts on API 37; tag `wave-2A-ok`.

### Sub-wave 2B (after `wave-2A-ok`)

#### 2.7 Android adapters
- **Owns:** `APP/platform/{OsFiles,AndroidClock,SystemRandom,NativeArgon2id}.kt`, the body of `PlatformModule.kt`, `APP-AT/platform/`, `APP-AT/vectors/`, `TEST/storage/VaultRoundTrip.kt` (the steps of `VaultRoundTripTest.roundTrip` moved there unchanged, with Kotlin's `check` and `error` in place of `org.junit.Assert`, because `:testing` has no JUnit; it already takes `FilePrimitives`, the vaults directory, `Argon2id`, `Aead`, `RandomSource` and `Clock`).
- **May also edit:** `CORE-T/integration/VaultRoundTripTest.kt` (2.3's), so that it calls `VaultRoundTrip` and still passes on the JVM. Nobody else in 2B owns it.
- **Inputs:** A §3.2 table, §3.3 `FilePrimitives`, §3.4 `RecordFiles`, §8.3; L §5.9; `docs/lab/old-android-artifacts/{AtomicFiles,Kdf}.kt`; [V-C1], [V-C2], [V-S7], [V-P7].
- **Accepted when,** on API 24, 28 and 37 [device]: the file tests of A §8.3; the vault directory is under `filesDir` and the lock directory under `noBackupFilesDir` (R-3); `NativeArgon2id` gives the two vectors and equals `CryptoFactory.bouncyCastleArgon2id()` for a 3-byte and a 200-byte non-ASCII password (the exact-size buffer rule); a forced load failure selects the fallback; the body of `VaultRoundTripTest` on `OsFiles` and `NativeArgon2id`; the vectors of A §4.13 and L §4.3 on the device (L A5). On API 37 only: a derivation under `adb shell am memory-limiter manual <pid> <PERCENT>` (a whole percentage of total RAM, 1 to 99) succeeds or gives `KdfUnavailable`, and the result, with the limiter's status, is written into the test report [V-P7]. The API 37 emulator image runs the limiter disabled, so there the derivations succeed with no limit enforced; an enforced limit is A §8.6 item 13, on a real phone.

#### 2.8 Window and UI kit
- **Owns:** `APP/window/` (the body of `WindowPolicy.kt` included), `APP/ui/theme/`, `APP/ui/common/`, `RES/values/{strings_common,themes}.xml`, `RES/drawable/ic_common_*`, `APP-AT/window/`, `APP-AT/ui/common/`.
- **May also edit:** `APP/ui/shell/AppRoot.kt` (2.5's), only its one call of the theme and the KDoc sentence about it: the call becomes `PasswordVaultTheme(graph.settings) { … }` as A §3.6 writes it, in the same commit that gives `PasswordVaultTheme` its `settings` parameter, so that the branch always builds. Nobody else in 2B owns it.
- **Inputs:** `docs/lab/window-input-artifacts/VaultSecurity.kt` (copy, with the API 26 gate of L §13.5) and `WindowSafety.kt`; L §13.5; U §3, §5.9 (the pad's look), §8, §10; [V-P4], [V-P5], [V-C11].
- **Implements:** `VaultSecurity`, `VaultWindow`, `Modifier.vaultTextField()`, `NoPersonalizedLearning`, `ObscuredTouchNotice`, the theme (`@Composable fun PasswordVaultTheme(settings: AppSettings, content: @Composable () -> Unit)`, which applies `settings.theme`: `ThemeChoice` SYSTEM, LIGHT or DARK), the wrappers of R-13 (`VaultTextField`, `VaultSecureField`, `VaultDialog`, `VaultSheet`), and the shared parts of A §2.2 (`PinPad` draws keys and dots and reports key presses; it holds no digits).
- **Accepted when,** on API 24, 28 and 37 [device]: no node of the pad or of a masked value exposes a digit or a secret; a `VaultSecureField` leaves nothing in the saved bundle (the method of `e5_state.py`); each wrapper sets the content type, the sensitive flag and the window policy; the activity starts on API 24 (no autofill call there). The checks against a real autofill service and accessibility service run on the real screens in 3.12.

#### 2.9 Settings and formatting
- **Owns:** `APP/settings/PrefsAppSettings.kt`, the body of `SettingsModule.kt`, `APP/i18n/{Formatters,SortKeys,AppLanguages}.kt`, `RES/values/donottranslate.xml`, `APP-T/settings/`, `APP-T/i18n/` (with `ResourcesTest.kt`, one test per language), `APP-AT/i18n/`, `APP-D/debug/i18n/` (a `locale <tag>` command, listed through the integrator).
- **Inputs:** A §3.5 `AppSettings`, D-7, D-19, D-24; I §1.4.5, §2.7 (sort key), §4.6, §5; U §5.11 (sorting and letter headers), §5.18; L §10.1.
- **Accepted when:** [JVM] a missing lock delay reads as "Immediately" and a missing clipboard delay as 30 seconds; `writeDefaults` writes 30 seconds and 1 minute; the one-time flags and the purge arm survive a restart; the language mapping of I §5 item 3; `ResourcesTest` reports missing translations (as a warning until wave 6). [device] on API 24 and 37: Uzbek and Russian order and letter headers through `SortKeys`, ICU 56 against 78; the `locale` command switches the app's language without restarting it.

#### 2.10 Lock records and policies
- **Owns:** `CORE/lock/record/{RecordCodec,FileRecordStore}.kt`, `CORE/lock/policy/{EntryPolicy,KeystoreErrors}.kt`, `CORE-T/lock/record/`, `CORE-T/lock/policy/{EntryPolicy,KeystoreErrors}Test.kt`, `TEST/lock/FakeRecordFiles.kt`.
- **Inputs:** L §5.3 to §5.6, §5.9, §13.1, §14 (as revised by its audit); `StrictJson` (1.4).
- **Accepted when:** [JVM] L T3, T12 and T13 pass.

#### 2.11 PIN and master-password guards
- **Owns:** `CORE/lock/guard/{PinGuard,MasterPasswordGuard}.kt`, `CORE-T/lock/guard/` except the two tests of 1.3, `TEST/lock/{FakeRecordStore,FakeKeys}.kt`.
- **Inputs:** L §6 (all), appendix B (port the model check) and appendix D; A §3.4.
- **Uses:** `RecordStore`, `Clock`, `PinKey`, `Aead` (through fakes).
- **Accepted when:** [JVM] L T2 (the PIN message, `KEK_pin` and the wrap of L §4.3), T4 to T9 and T20 pass.

#### 2.12 Strength and the security check
- **Owns:** `CORE/strength/` except `SecurityReport.kt`, `core/src/main/resources/strength/`, `CORE-T/strength/`.
- **Inputs:** U §5.3 (checklist, dictionary, meter), §5.16 (cards, thresholds, order of a group), §12 check 18; A D-6d, D-9b.
- **Implements:** `StrengthMeter`, `MasterPasswordRules`, `SecurityCheck.run(vault): SecurityReport`, and the Uzbek and Russian word list. **The list:** the names of Uzbekistan's regions, cities and districts in both scripts; at least 500 Uzbek and 500 Russian first names; at least 2,000 common Uzbek words in each script and 2,000 common Russian words; each source with a licence that allows redistribution, named with its licence and the count taken from it in `strength/SOURCES.md`, which the licences screen shows; at most 1 MB.
- **Accepted when:** [JVM] everything A §8.1 lists for `strength`; scoring 5,000 passwords takes under 5 s on this machine.

**Integration check for wave 2:** merge 2B; `tools/check.sh all`; on the integrator's API 24, 28 and 37 devices, `connectedDebugAndroidTest` with `ANDROID_SERIAL` set to those three, through `tools/emu/adb.sh --connected` (every serial has a passing report; BUILD SUCCESSFUL alone does not count); the debug app starts on all three; `smoke.sh staging` (install and start) on API 24 and 37; tag `wave-2B-ok`.

---

## 5. Wave 3: the walking skeleton, then the rest of part I

### Sub-wave 3A: the skeleton (after `wave-2B-ok`)

Six packages that together make an app a person can use with the master password alone.

#### 3.1 Vault repository
- **Owns:** `APP/data/` except the interface files and `SecurityCheckRunner.kt`, the body of `DataModule.kt`, `APP-T/data/`.
- **Inputs:** A §3.3 to §3.5, §4.8 to §4.12, §5 (the draft flush and automatic writes of §5.5), D-24; L §16.3 "Storage"; S §7 (index lifecycle).
- **Implements:** `DefaultVaultRepository : VaultRepository, VaultPort` (the vault mutex, the session, `edit`, `holdDraft` and `flushDraft`, the hold on automatic writes after an older copy, `sealBackup(proof)`, `openBackup`, `matchesMasterPassword(proof)`, the purge of D-24 armed and carried out at unlock, a vault put aside opened in place, `swapIn`, `mergeAside`, `replaceVault`, `createVault` with `stageVault`, `prove` and `publish`), `ListModelProvider` (rebuilt when the vault or the language changes).
- **Accepted when:** [JVM] against a real `VaultStore` on a temporary directory and on `FakeFiles`: create, lock, reopen with the password and with the key; an edit is visible to observers only after the file is on disk; a failing `syncDir` gives `NotSaved` and the old state; two edits at once are applied one after the other; `flushDraft` writes only a changed draft; nothing automatic is written before the older-copy notice is answered; a restore keeps and resets the keys of A §4.12; `TOO_LARGE` gives its sentence; after `lock()` no reference to the vault remains in the repository.

#### 3.2 Master-password gate and auto-lock
- **Owns:** `APP/lock/MasterPasswordGate.kt` (temporary: 4.1 removes it), the body of `LockModule.kt`, `APP/session/` (the body of `SessionModule.kt` included), `APP-T/session/`, `APP-T/lock/gate/`, `tools/emu/session/` (its [script] line).
- **Inputs:** L §10, §11; A §5.5, §3.5 `Session` and `LockManager`; `SessionPolicy` from 1.3.
- **Implements:** a `LockManager` that knows only "no vault", "locked: master password" and "unlocked", with `createVault` (both typings compared before anything is derived, then `AppSettings.writeDefaults`) and `lock(reason)`; `AutoLock` in full (process lifecycle, screen-off, the no-touch timer, the external grace, `lockNow`, and the order of A §5.5 for every trigger).
- **Accepted when:** [JVM] L T19 through the real `AutoLock` with a fake lifecycle and clock; for each trigger (delay, screen-off, no-touch timer, "Lock now") a dirty form's draft is written before `VaultPort.lock()`; [script] on the emulator, with the default written by setup, away for 31 s locks, away for 10 s does not, screen-off locks at once, a rotation does not.

#### 3.3 Setup screens
- **Owns:** `APP/ui/setup/{Welcome,CreateMasterPassword,WriteDown,TypeAgain,SetupViewModel}.kt`, the body of `SetupNav.kt`, `RES/values/strings_setup.xml`, `RES/drawable/ic_setup_*`, `APP-T/ui/setup/`, `APP-AT/ui/setup/`, `tools/emu/setup/` (its [script] line).
- **Inputs:** U §5.1, §5.3 to §5.5, §7.1, §7.2; L F1 steps 1 to 4, §10.1 (the screen kept on in a change or replacement); A §4.6; `Passphrase`, `MasterPasswordRules`.
- **Uses:** `LockManager.createVault`, `LockStatus.aside`, `AppSettings.oneTime` (words shown).
- **Implements:** the four screens. Welcome has from the start its globe button (to the `Language` route, a placeholder until 6.1), "Restore from a backup file" (to the `Restore` route, 5.2's) and one "Open my previous passwords (locked on …)" per vault put aside (`LockIntent.OpenAside`, which 4.2's screens answer).
- **Accepted when:** [JVM] ViewModel tests: mismatch messages, the Cyrillic-keyboard hint, the checklist applied after folding, "Show other words", the interrupted-setup text when words were shown; [device] after a rotation on "Type it again" the field has the keyboard again [V-P7]; [script] a vault can be created with the suggested words and with an own password.

#### 3.4 Master-password lock screen
- **Owns:** `APP/ui/lock/{MasterPasswordScreen,LockViewModel}.kt`, the body of `LockNav.kt`, `RES/values/strings_lock.xml`, `APP-T/ui/lock/`, `APP-AT/ui/lock/`.
- **Inputs:** U §5.10; L §13.2, §13.3 (texts), §5.8 (the field's state).
- **Accepted when:** [JVM] ViewModel tests for every `LockScreen.MasterPassword` state of `FakeLockManager`; the field is empty after a submit; [device] after a rotation nothing of the field is in the saved bundle and the keyboard is back [V-P7].

#### 3.5 List, search and Recently deleted
- **Owns:** `APP/ui/list/`, the body of `ListNav.kt`, `RES/values/strings_list.xml`, `RES/drawable/ic_list_*`, `APP-T/ui/list/`, `APP-AT/ui/list/`.
- **Inputs:** U §5.11, §5.23, §6 (the row's copy button); S §2, §7; I §2.7; L §13.4 items 2, 3 and 6 (the banners, through `LockStatus`).
- **Implements:** the list with favourites, letter headers and banners (all four kinds of U §5.11, fed from the vault, `LockStatus` and the settings even while their sources are empty), in-place search with bold matches and the wrong-layout retry, the long-press menu, Recently deleted.
- **Accepted when:** [JVM] ViewModel tests with a simple `Collation` (order, sections, "latest query wins", at most two banners in priority order); [device] a Compose test with 5,000 entries from `FakeVaultRepository` scrolls, and a keystroke answers within 50 ms on API 37.

#### 3.6 Add and edit, with the generator sheet
- **Owns:** `APP/ui/edit/`, the body of `EditNav.kt`, `RES/values/strings_edit.xml`, `RES/drawable/ic_edit_*`, `APP-T/ui/edit/`, `APP-AT/ui/edit/`.
- **Inputs:** U §5.14 (without the check at Save: A D-9b), §5.15; `VaultEdits`, `EntryRules`, `PasswordGenerator`, `StrengthMeter`.
- **Accepted when:** [JVM] ViewModel tests: U §12 check 4 (a generated password is handed over with `holdDraft` and survives a lock as a draft); Discard leaves neither entry nor draft; the duplicate question; limits with counters; a blank name is filled from the host, or with `EditTexts.untitled`; [device] the keyboard is back after a rotation.

**The skeleton check** (integration check for 3A): merge; `tools/check.sh all`; then on API 24, 28 and 37 with the debug build, and on API 24 and 37 with the staging build (steps that need `run-as` print SKIPPED there):
1. Install. Welcome → Start → master password → write down → type again. The list is empty.
2. Add an account with a name, a login, a password, a website and a note. It is in the list.
3. `run-as … ls files/vaults/live` shows `vault-0000000001.pvault` and a second generation. Copy the newest out with `run-as cat`: it starts with `PVAULT01`, and none of the typed texts can be found in it.
4. Screen off: locked. A wrong master password gives the message; the right one opens the list.
5. `am force-stop`, start again: locked; unlock; the account is there.
6. Leave for 31 s: locked. Rotate on the lock screen and on the edit form: nothing is lost, nothing locks, and the keyboard comes back.
7. U §12 check 4: generate a password in a new form, turn the screen off, unlock: the form reopens with it and "Your unsaved changes were kept".
8. Switch the language with the debug `locale` command (to `ru` and back; `b+uz+Cyrl` shows English until wave 6 brings its texts, because Android chooses among the languages the APK has): the app stays open on the same screen and the list re-sorts.
9. `smoke.sh staging`: set up, add, rotate, kill in the background and come back to the same screen, lock, unlock.

Tag `wave-3A-ok`.

### Sub-wave 3B (after `wave-3A-ok`)

#### 3.7 Entry detail and big letters
- **Owns:** `APP/ui/entry/`, the body of `EntryNav.kt`, `RES/values/strings_entry.xml`, `RES/drawable/ic_entry_*`, `APP-T/ui/entry/`, `APP-AT/ui/entry/`.
- **Inputs:** U §5.12, §5.13, §6, §3 (masking and the 30-second re-hide); A D-9d.
- **Accepted when:** [JVM] ViewModel tests (re-hide after 30 s without a touch, both passwords shown while a change is pending, delete with undo, a copy goes to `SecretClipboard` with the sensitive flag); [device] stored passwords are drawn with `Text`, never a read-only field, and notes are selectable.

#### 3.8 Clipboard
- **Owns:** `APP/clipboard/` except the two interface files (the bodies of `ClipboardClearReceiver.kt`, `ClipboardRoot.kt` and `ClipboardRowSlot` included), the body of `ClipboardModule.kt`, `RES/values/strings_clipboard.xml`, `APP-AT/clipboard/`, `tools/emu/clipboard/`.
- **Inputs:** A D-14, D-25; U §6; `docs/lab/clipboard.md` and `docs/lab/clipboard-artifacts/` (from 0.3); `docs/lab/old-android-artifacts/SensitiveClipboard.kt`; [V-P2], [V-P10].
- **Implements:** `AndroidSecretClipboard` (sensitive flag, random token in the clip's extras, the alarm and the timer, the token check before clearing, the token and deadline kept in the settings so that an overdue clip of ours is cleared when the app comes to the front), `ClipboardClearReceiver`, `SecretClipboardBridge` as `LocalClipboard`, the status row's slot.
- **Accepted when,** on API 24, 28 and 37 [script]: a copy is gone 30 s later with the app in the background, after `am kill` (API 28 and 37), after `run-as kill -9` (API 24, debug only), and after a swipe from Recents; after `am force-stop` the clip stays until the app is brought to the front, which clears it; a text copied afterwards by another app survives on API 24, and on API 28 while the phone is unlocked; `clearNow()` clears at once; no toast is shown. [device] text selected and copied in a note carries the flag and the token; pasting into a field still works.

#### 3.9 Keystore adapters
- **Owns:** `APP/keystore/`, the body of `KeystoreModule.kt`, `APP-AT/keystore/`.
- **Inputs:** L §4.2, §4.5, §14.2, §14.3; `docs/lab/biometric-artifacts/BiometricVaultKey.kt` (start from it, with the two changes L §14.2 names); [V-C6], [V-C7], [V-P10].
- **Uses:** `BiometricLauncher` (declared by 2.5; its fake in tests). The real prompt is 4.1's.
- **Accepted when,** on API 24, 28 and 37 (debug build, software keys allowed) [device]: the PIN vector of L §4.3 through a real HMAC key gives a wrap that opens; create and delete both key kinds; `aliases()` lists both prefixes and nothing else; a missing canary is made again; `getKey` null with the canary present is GONE, with the canary missing and not makeable TEMPORARY, with the canary made again GONE; an injected fault for each row of L §14.3 that can be produced gives the listed class; `probe` classifies a fingerprint key by `Cipher.init`. Using the fingerprint key needs a prompt and a finger from the host: that is E6 in wave 4.

#### 3.10 Lock engine: the core
- **Owns:** `CORE/lock/flow/{LockEngine,LockCore,UnlockFlows,StartUp,Sweep}.kt`, and stubs that throw `NotImplementedError` for `CORE/lock/flow/{ShortcutFlows,VaultFlows,PasswordFlows,OlderCopies}.kt`, which 3.13, 3.14 and 4.4 fill; `TEST/lock/FakeVaultPort.kt` (the fake of L §15.1: vaults current and put aside, a record of every write call, a stop at every boundary); `CORE-T/lock/flow/core/`.
- **Inputs:** L §3 (invariants), §5.7, §5.10, §5.11, §6.3 (visits, I11), §9.2, §12 F1 to F5 and F7, §13.1, §13.4, §14; A §3.4.
- **Uses:** 2.10, 2.11, `MasterKdf`, `Aead`, the ports of A §3.4 through `:testing`'s fakes.
- **Implements:** `LockEngine` with every public entry point of the lock, the visit and prompt bookkeeping, the fingerprint launch numbers, start-up (L §5.11, with the replaced-vault table), the sweep, and F1 to F5 and F7; the entry points of the other flows delegate to the stub files.
- **Accepted when:** [JVM] L T14 for F5 and for the start-up steps of L §5.11, T17, T18, and a scripted run of F1 to F5, F7 and start-up ends in the state L describes.

#### 3.11 Format writers and the JSON reader
- **Owns:** `CORE/formats/{GoogleCsv,FieldsBlock,BitwardenJson,VaultXlsx,ExportNames}.kt`, `CORE-T/formats/writers/`.
- **Inputs:** F §2, §4 (with the grammar of A D-10a), §5, §6; FR P8, P11, P15; A D-10a, D-10c to D-10e, D-17; `prototypes/formats/src/pv/formats/` and `test/FormatsTest.kt`; `tools/validate/`.
- **Implements:** the three writers, the fields block of D-10a in both directions, and the JSON reader with FR P11's fixes (the importer uses it).
- **Accepted when:** [JVM] the prototype's checks; our CSV and JSON read back by our own readers without loss, including the four cases of FR P8 and both reserved labels; deleted entries are in no output; a test writes the sample files into `core/build/samples/`, and `tools/validate/run.sh core/build/samples` passes.

#### 3.12 Window and input checks
- **Owns:** `tools/emu/window/` (the lab's helper app as its own small Gradle build with minSdk 26, built through `tools/gradle.sh -p`, and the lab's scripts adapted to the app).
- **Inputs:** `docs/lab/window-input.md` and `docs/lab/window-input-artifacts/` (`af_scenario.py`, `ime_probe.py`, `e4_overlay.py`, `e5_state.py`, `LabAutofillService.kt`, `LabA11yService.kt`); A §8.4; [V-P4], [V-P5].
- **Accepted when,** on API 28 and 37, with the debug and the staging build, against the real setup, lock and edit screens [script]: no fill and no save request for any field; the keyboard flags of A §8.4; overlay taps dropped; nothing of a secret field in the saved bundle; the non-tool accessibility service sees no secret text. Each script prints PASS or FAIL per step. From here on the integrator runs them at every integration check.

**Integration check for 3B:** merge; `tools/check.sh all`; connected tests on the integrator's three devices through `tools/emu/adb.sh --connected` (every serial has a passing report; BUILD SUCCESSFUL alone does not count); the skeleton check again, plus: open the account, show the password, copy it: the status row counts down, and the clip is gone 30 s later in the background on API 24, 28 and 37; text selected in a note and copied is flagged; `tools/emu/window/` on API 28 and 37; `smoke.sh staging` with the copy and the clear; on staging `adb exec-out screencap -p` is black. Tag `wave-3B-ok`.

### Sub-wave 3C (after `wave-3B-ok`)

#### 3.13 Lock flows: shortcuts and practice
- **Owns:** `CORE/lock/flow/ShortcutFlows.kt` (the body), `CORE-T/lock/flow/shortcuts/`.
- **Inputs:** L F6, F9, F10, F15, §8.2, §8.3, §9.
- **Accepted when:** [JVM] L T14 for F6 and F9, and a scripted run of F6, F9, F10 and each case of F15 ends in the state L describes.

#### 3.14 Lock flows: vaults, backups and restores
- **Owns:** `CORE/lock/flow/VaultFlows.kt` (the body), `CORE-T/lock/flow/vaults/`.
- **Inputs:** L F12, F13, F14, F17, F18, §6.8 (the guard of a vault put aside), §8.2.
- **Accepted when:** [JVM] L T14 for F13, F14 and F17 steps 3 and 4; a scripted run of each flow; "Delete" settles another vault's `replaced` mark first (F17 step 5); a vault put aside is checked under its own guard; F18 shows its text the first time only.

#### 3.15 Importer
- **Owns:** `CORE/formats/{Importer,ImportPlanner,FileKind,HeaderAliases}.kt`, `CORE-T/formats/importer/`, and the removal of `prototypes/formats/` except `OfficeCrypto.kt` and its checks (A D-16).
- **Inputs:** F §8; FR P1 to P6, P10 to P14, P16; U §5.21 (related websites, tidy names, the preview's counts); A D-10b, D-10f, D-15, §3.5 (`TOO_LARGE` in the preview); the fixtures in `CORE-R/formats/`.
- **Accepted when:** [JVM] one test per finding from its trigger in FR, through `Importer.read` (P2, P3, P4 and P12 end to end); the fixtures of F §8.5 and the Uzbek Cyrillic headers of FR P6; export, edit one entry, import gives no duplicate id (P1); a 10,000 × 10,000 plan takes under 1 s on this machine (P16); the preview estimates the sealed size and refuses an import that would not fit.

**Integration check for 3C:** merge; `tools/check.sh all`; tag `wave-3C-ok`.

---

## 6. Wave 4: PIN and fingerprint

### Sub-wave 4A (after `wave-3C-ok`)

#### 4.1 Lock manager
- **Owns:** `APP/lock/{DefaultLockManager,PromptLauncher,LockDebugImpl}.kt`, the body of `APP/lock/BiometricHost.kt`, the body of `LockModule.kt`, the removal of `MasterPasswordGate.kt`, `APP-T/lock/`, `APP-D/debug/lock/` (the commands of L §15.3: `status`, `hold-after-charge`, `hold-before-hmac`, `release`, `fail-next-write`, `ks-fault`, `drop-keys`, listed through the integrator).
- **Inputs:** A §3.5, §5; L §9.2 (the prompt rules, launch numbers included), §10.4, §11, §13.1; `docs/lab/biometric-artifacts/LabActivity.kt` (how the one prompt is built in `onCreate` and results are routed).
- **Implements:** `LockManager` and `LockStatus` over `LockEngine` with the real ports; `BiometricLauncher` over the one `BiometricPrompt`; `createVault` and `changeMasterPassword` with both typings compared first; `matchesMasterPassword` after every correct master password (A D-9b); `openAside` and `deleteAside` under that vault's guard; `LockDebug`.
- **Accepted when:** [JVM] with fake ports: every `LockIntent`, every state of L §13.2, a rotation keeps the digits and the charge, an automatic lock drops the proof, a result that comes after its visit opens nothing; at the integration check, L E1 to E3, E6 and E11 without its language step.

#### 4.2 Lock screens
- **Owns:** `APP/ui/lock/` and `strings_lock.xml` (all of it from here on, `LockNav.kt` included), `RES/drawable/ic_lock_*`, `APP-T/ui/lock/`, `APP-AT/ui/lock/`.
- **Inputs:** U §5.9, §5.10 (the sheet), §5.1 (Welcome's "Open my previous passwords"); L §13 (every state and text), F4, F5, F7, F14 to F17; A §3.5 `LockScreen`, `LockUiState`.
- **Implements:** the PIN pad screen, the fingerprint-only screen, the "Forgot master password?" sheet (its five entries of L F17 step 1), the screen of a vault put aside with its line, the notices after unlock, "This is an older master password" in both forms, "Your passwords can't be opened" (with "Try again" for an I/O error), and the "TEST BUILD" line whenever `ALLOW_SOFTWARE_KEYSTORE` is true.
- **Accepted when:** [JVM] ViewModel tests for every state and every text row of L §13.3 driven by `FakeLockManager` (U §12 check 17); [device] no digit is ever in a node; the accessibility tree reads "PIN, 3 of 6 digits entered", every key is reachable and labelled (E12's assertions); U §12 check 16 at the integration check of wave 5.

#### 4.3 PIN and fingerprint setup, and Settings
- **Owns:** `APP/ui/setup/{CreatePin,ConfirmPin,FingerprintOffer,BackupsExplained}.kt`, the body of `PinSetupNav.kt`, `APP/ui/settings/{SettingsScreen,SettingsViewModel,ChangePin,AppLockPages,ThemeDialog}.kt`, the body of `SettingsNav.kt`, `RES/values/{strings_pin,strings_settings}.xml`, `RES/drawable/ic_settings_*`, `APP-T/ui/settings/`, `APP-AT/ui/settings/`.
- **Inputs:** U §5.6 to §5.8, §5.18; L §7.3 (texts), §8.2 (what each action needs), §9.4 (when fingerprint is offered), F6, F9; A D-7.
- **Implements:** the setup steps; the Settings list with all its groups and rows (the rows of later waves open their routes, placeholders until then; the strings of every row are here); Change PIN and Set a PIN; the fingerprint switch with its reasons from `LockStatus`; the lock-delay and clipboard-delay dialogs; Theme; the "Backup reminders" switch; "Lock now" (`Session.lockNow`).
- **Accepted when:** [JVM] ViewModel tests: the three blocklist messages, three mismatches return to the first entry, the availability table of L §9.4, "Set a PIN" asks for the master password and "Change PIN" for the current PIN, the defaults shown are those `writeDefaults` wrote.

#### 4.4 Lock engine, part II
- **Owns:** `CORE/lock/flow/{PasswordFlows,OlderCopies}.kt` (the bodies), `CORE-T/lock/flow/password/`.
- **May also edit:** `CORE/lock/flow/{LockEngine,UnlockFlows}.kt` (3.10's): the pending wrap at a master-password entry (F11 step 13) and F16 sit in the unlock path. Nobody else in 4A owns them.
- **Inputs:** L F8, F11, F16, §5.4 (the wrap rule), §5.10, §6.9, §13.4 items 2 and 3.
- **Accepted when:** [JVM] L T14 for F8, F11 and both branches of F16b, T15 and T16.

**Integration check for 4A:** merge; `tools/check.sh all`; on the integrator's devices by hand: set up with a PIN and a fingerprint (`adb emu finger touch`), unlock with each, five wrong PINs, then the master password and a new PIN; the window checks; `smoke.sh staging` with the PIN and the fingerprint on API 24 (the library's own dialog) and 37. Tag `wave-4A-ok`.

### Sub-wave 4B (after `wave-4A-ok`)

#### 4.5 The lock's emulator scripts
- **Owns:** `tools/emu/lock/`.
- **Inputs:** L §15.3; `docs/lab/biometric-artifacts/{lab.sh,enroll_fingerprint.sh,delete_all_fingerprints.sh,uitap.py,uidump.sh}` (copied and adapted); `docs/lab/old-android-artifacts/{labenv.sh,heapsim.sh}`.
- **Uses:** the debug commands of 4.1; `tools/emu/adb.sh`.
- **Accepted when:** [script] each script E1 to E17 runs unattended against the merged app, prints PASS or FAIL per step and exits non-zero on a failure; steps that need a debuggable build or root print SKIPPED on staging; E12 checks the accessibility tree (TalkBack itself is A §8.6 item 8); E15 builds and uses the `backupCheck` variant. The steps that need later waves (E4's pending wait and "replaced" notice, E5, E11's language step, E17's picker steps) are written now and marked with the wave that enables them.

**Integration check for wave 4:** merge; `tools/check.sh all`; with `tools/emu/lock/`: E1 to E3, E6, E7, E10 and E11 (without the language step) on API 24, 28 and 37; E4 (without the pending wait), E8, E9, E12 to E14 and E17 (without the picker) on API 37; E15 on API 28 and 37; E16 on API 24. E10 includes `adb shell pm uninstall -k` and a reinstall: the master password opens the vault, and each shortcut that lost its key shows "stopped working" (A D-21). The staging build repeats E1 and E6 on API 24 and 37. Tag `wave-4-ok`.

---

## 7. Wave 5: backup, restore, import, export, and the master password

After `wave-4-ok`; five packages.

### 5.1 File picker plumbing
- **Owns:** `APP/files/` except the four interface files (the body of `DocumentLaunchers.kt` included), the body of `FilesModule.kt`, `APP-AT/files/`, `app/src/androidTest/AndroidManifest.xml` (a test documents provider).
- **Inputs:** A §3.5 `DocumentIo`, §4.12; U §4 (unfinished file tasks, cleaning up, a result that reaches a new process), §5.19 (places); F §2 rules 2 to 6; [V-S6], [V-P3].
- **Accepted when,** on API 24 and 37 [device]: write and read back; a provider that writes 0 bytes or cuts the file is caught; delete where allowed; a file task recorded before the picker opens is still there after the process is killed; a picker result delivered to a new, locked process is recorded and handled after the unlock.

### 5.2 Backup, restore, and old passwords
- **Owns:** `APP/ui/transfer/{Backup*,Restore*,OldPasswords*}.kt`, the body of `BackupNav.kt`, `RES/values/strings_backup.xml`, `APP-T/ui/transfer/backup/`, `APP-AT/ui/transfer/backup/`.
- **Inputs:** U §5.2, §5.18 (Old passwords), §5.19, §5.21 "Backup file into an existing vault"; L F12, F13, F17 steps 3 and 5; A §4.4, §4.11, §4.12.
- **Accepted when:** [JVM] ViewModel tests: the master password is always asked except in the offer after an entry; no backup while a new master password is pending; every error text of U §5.2, "This file may be damaged, or the password is wrong" with another try among them; "Old passwords" opens and deletes only with that vault's master password; at the integration check, U §12 checks 12 (with Downloads), 13, 14 and 16.

### 5.3 Export
- **Owns:** `APP/ui/transfer/Export*.kt`, the body of `ExportNav.kt`, `RES/values/strings_export.xml`, `APP-T/ui/transfer/export/`, `APP-AT/ui/transfer/export/`.
- **Inputs:** U §5.20; F §2 (order, warning texts, names); the writers of 3.11.
- **Accepted when:** [JVM] the warning needs "I understand"; the master password is typed every time; the file is noted in the vault and the banner stays until "Delete" or "I deleted it myself"; "Done" is the primary button.

### 5.4 Import
- **Owns:** `APP/ui/transfer/{Import*,ColumnMapping}.kt`, the body of `ImportNav.kt`, `RES/values/strings_import.xml`, `APP-T/ui/transfer/import/`, `APP-AT/ui/transfer/import/`.
- **Inputs:** U §5.21; F §8.1; the importer of 3.15; A §3.5 (`TOO_LARGE`).
- **Accepted when:** [JVM] the preview never shows a password; rows with a wrong number of values are listed and left out unless confirmed; the mapping screen opens when no password column is found; "Undo this import" restores the vault as it was; an import that would not fit is refused with A §3.5's sentence.

### 5.5 Change and replace the master password
- **Owns:** `APP/ui/settings/{ChangeMasterPassword,NewMasterPassword}*.kt`, the body of `MasterPasswordNav.kt`, `RES/values/strings_master.xml`, `APP-T/ui/settings/master/`, `APP-AT/ui/settings/master/`.
- **May also edit:** `APP/lock/DefaultLockManager.kt` (4.1's), for the F8 and F11 steps that the screens drive. Nobody else in this wave owns it.
- **Inputs:** U §5.18 "Change master password", §5.24 (in L F11's order); L F8, F11, F16, §10.1 (the screen kept on), §13.4; the engine of 4.4.
- **Accepted when:** [JVM] tests for F8 and F11 through `LockManager` with fake ports: the PIN step, the fingerprint step, the banner with its "Cancel it", "Finish now" after 24 credited hours, "It wasn't me" and "Open my passwords from before …"; [device] the keyboard is back after a rotation; U §12 check 15 at the integration check.

**Integration check for wave 5:** merge; `tools/check.sh all`; on API 37 and API 24:
1. Make a backup into Downloads; uninstall; install; "Restore from a backup file"; the accounts are back and a PIN can be set.
2. With the lock delay "Immediately": backup, each export and an import all finish (U §12 check 14); E17's picker steps (90 s in the picker does not lock; screen-off in the picker locks and the task resumes after unlock); a kill while the picker is open (U §12 check 13).
3. Export CSV, JSON and Excel of a vault with Uzbek, Cyrillic, a phone number starting with `+998`, a value starting with `=` and a multi-line note. `tools/validate/run.sh` on the three files; import the CSV and the JSON into an empty vault and compare.
4. Import the Chrome fixture and the Uzbek Cyrillic header file of FR P6.
5. Change the master password with a PIN and a fingerprint set: afterwards the new master password, the PIN and the fingerprint open the vault. The kill points of the change are T15 and A §8.1's storage tests; no debug hold exists for them on a device.
6. Ask for a new master password (F11): E4's pending wait is unchanged by a clock jump, and E5's boot-count edits add nothing.
7. "Start with an empty app", then open the previous passwords from Welcome with their PIN, and from Settings › Old passwords with their master password (U §12 check 16).
8. `smoke.sh staging` with backup, restore, export and import on API 24 and 37.

Tag `wave-5-ok`.

---

## 8. Wave 6: the four languages

### Sub-wave 6A (after `wave-5-ok`)

**Before 6A starts,** the integrator sets lint's `MissingTranslation` to a warning in `app/build.gradle.kts` (I §1.1 makes it an error): as soon as one file exists under `values-uz` or `values-ru`, every English string without a translation there fails `:app:lintDebug`, and 6.1 brings `strings_language.xml` into those directories while the other translations come only in 6B. It becomes an error again at the integration check of wave 6, together with `ResourcesTest`. (Found by package 0.1: a trial `values-ru/strings_language.xml` failed lint with `MissingTranslation`.)

#### 6.1 Language switch
- **Owns:** `APP/ui/settings/LanguageDialog.kt`, the body of `LanguageNav.kt`, `strings_language.xml` in all four `values` directories, `tools/emu/i18n/`.
- **Inputs:** I §1.4; L §11, E11; U §5.1, §5.18. Welcome's globe button already opens the `Language` route (3.3).
- **Accepted when:** [script] on API 24, 28 and 37 the matrix of I §5 passes (API 32 and 33 too once their images are downloaded by the recipe of `docs/lab/old-android-artifacts/README.txt`; they are the two sides of the switch between AppCompat's own storage and the system's); E11's language step: changing the language in Settings with the lock delay "Immediately" keeps the app open on the same screen; the list re-sorts.

#### 6.2 Layout under long text
- **Owns:** `APP/ui/` except the files of 6.1; the English `RES/values/strings_*.xml` except `strings_language.xml`; `docs/screens/`.
- **Inputs:** I §4.5, §5 (pseudo-locales); U §8 (font scaling, touch targets).
- **Accepted when:** [script] every screen walked in `en-XA` and `ar-XB` at 320 dp width and 200 % font size shows no clipped button, no truncated warning and no text that stayed plain English; a screenshot of each screen is kept under `docs/screens/`. **String freeze:** when 6A is merged, no English string changes until 6B is merged.

**Integration check for 6A:** merge; `tools/check.sh all`; tag `wave-6A-ok`. The English strings are frozen from here until 6B is merged.

### Sub-wave 6B (after `wave-6A-ok`)

#### 6.3 Uzbek, Latin and Cyrillic
- **Owns:** `RES/values-uz/`, `RES/values-b+uz+Cyrl/` (except `strings_language.xml`), including `values-b+uz+Cyrl/strings_overrides.xml`. May be split into two packages by string-file group (6.3a: common, setup, lock, pin, master; 6.3b: the rest), each owning its files in both directories.
- **Inputs:** I §2.4, §2.5, §3 (the glossary), §4, §1.4.6; `tools/gradle.sh :core:testDebugUnitTest --tests '*UzbekCyrillicStrings*' -Ppv.uzArgs=…` to draft the Cyrillic files from the Latin ones, then a review of every string against the glossary.
- **Implements:** the translations (among them the three tab labels of `strings_shell.xml`; its placeholder texts are `translatable="false"` and leave with the placeholders), and Uzbek Cyrillic overrides for the library strings that would otherwise show in English (I §1.4.6): the names found in the merged APK's resources for material3's accessibility labels and the fingerprint dialog that androidx.biometric draws on API 24 to 27.
- **Accepted when:** [JVM] `ResourcesTest` passes for both directories (complete, same placeholders, only U+02BB and U+02BC as apostrophe letters, no Latin letters in the Cyrillic files outside the allow-list).

#### 6.4 Russian
- **Owns:** `RES/values-ru/` (except `strings_language.xml`).
- **Inputs:** I §3, §4 (plurals with one, few, many, other; « »; ё; lower-case "вы"). The translations include the three tab labels of `strings_shell.xml`.
- **Accepted when:** [JVM] `ResourcesTest` passes for the directory; lint's `MissingQuantity` is clean.

**Integration check for wave 6:** merge; the integrator turns `ResourcesTest` into a failure for every language and lint's `MissingTranslation` back into an error; `tools/check.sh all`; `tools/emu/i18n/` on API 24 and 37; `smoke.sh staging` with a language switch; one screenshot per screen in each of the four languages for the user to read (A §8.6 item 8). Tag `wave-6-ok`.

---

## 9. Wave 7: the security check, the guided change, help

After `wave-6-ok`. From here every package ships its strings in all four languages.

### 7.1 Security tab
- **Owns:** `APP/ui/security/{Security*,GroupList*}.kt`, the body of `SecurityBadge.kt`, the body of `SecurityNav.kt`, `APP/data/SecurityCheckRunner.kt`, the body of `SecurityModule.kt`, `strings_security.xml` in all four directories, `APP-T/ui/security/`, `APP-AT/ui/security/`, the fixture `app/src/androidTest/assets/fixtures/fifty-accounts.csv`.
- **Inputs:** U §5.16; `SecurityCheck` from 2.12; A D-9a, D-9b, §4.3 (`securitySeenAt`).
- **Accepted when:** [JVM] card order, the dot only for results not yet seen, `securitySeenAt` written only when it clears a dot, groups ordered email first, the "left out" count; the check of 480 entries finishes in the background without blocking the list.

### 7.2 Guided change
- **Owns:** `APP/ui/security/GuidedChange*.kt`, the body of `ChangeNav.kt`, `strings_change.xml` in all four directories, `APP-T/ui/security/change/`, `APP-AT/ui/security/change/`.
- **Inputs:** U §5.17, §12 check 5; the guided-change edits of 2.4.
- **Accepted when:** [JVM] ViewModel tests pass; [script] on the emulator the change survives a lock and `am force-stop` at every step, and both passwords can be copied from the list and the detail screen meanwhile.

### 7.3 Help, licences and tips
- **Owns:** `APP/ui/settings/{Help*,Licences*}.kt`, the body of `HelpNav.kt`, the body of `APP/ui/shell/FirstCopyTip.kt`, `strings_help.xml` in all four directories, `RES/raw/` (licence texts).
- **Inputs:** U §5.22, §6 (first-copy tip), §7; L §2.3 and §16.2 item 12 (what Help must say); A D-14 (the two "never" cases); [V-C13].
- **Accepted when:** every page of U §5.22 exists in four languages; the first-copy tip shows once (`OneTime.FIRST_COPY_TIP`) and says that a force-stop or "Restricted" battery use leaves the copy until the app is opened; the licences page names the EFF list with its licence link and the change made to it, zxcvbn4j, argon2kt, Bouncy Castle, the AndroidX libraries, the sources of the strength word list, and Material Icons (Apache License 2.0), the source of `RES/drawable/ic_common_*` (taken from Android Studio's Vector Asset Studio) and of any icon a later package takes from the same place; the `ic_shell_*` icons were drawn for the app.

**Integration check for wave 7:** merge; `tools/check.sh all`; on API 37 import the 50-account fixture in which 17 share a password: the cards of U §5.16 appear, one guided change runs to "Gmail now has its own password", a password equal to the master password is flagged after the next master-password entry; `smoke.sh staging`. The integrator moves `APP/fake/` to `app/src/debug/` if no file under `src/main` names a fake any more. Tag `wave-7-ok`.

---

## 10. Wave 8: release

### Sub-wave 8A (after `wave-7-ok`)

#### 8.2 Build hardening
- **Owns:** the Gradle files, `gradle/verification-metadata.xml`, the lock files, `app/src/main/keepRules/rules.keep`.
- **Inputs:** A §7.2.
- **Accepted when:** `tools/check.sh all` passes with `--offline` from a warm cache; the release APK contains no class of the `fake` or `debug` packages; the staging build passes the whole of `tools/emu/`.

#### 8.3 Decrypt script
- **Owns:** `tools/pvault_decrypt.py`, `docs/format.md` (A §4 as a public description).
- **Inputs:** A §4, §4.13.
- **Accepted when:** the script opens both vectors of A §4.13 and a backup written by the app on the emulator, given the master password.

#### 8.4 Release notes for the user
- **Owns:** `docs/release/`.
- **Inputs:** A §7.2 (the signing key), §8.6; L §15.4, §18.
- **Accepted when:** it holds the steps to make and back up the release key, to build and sign the APK, and the list of phone checks of A §8.6 as a form the user can fill in, with the two questions of A §8.6 item 12.

**Integration check for 8A:** merge; `tools/check.sh all`; tag `wave-8A-ok`.

### Sub-wave 8B (after `wave-8A-ok`)

#### 8.1 Accessibility and acceptance pass
- **Owns:** `APP/ui/`; **may also edit** `RES/values*/strings_*.xml` for new accessibility labels, always in all four languages.
- **Inputs:** U §8, §12 (all 20 checks); L §15.6 (A1 to A10).
- **Accepted when:** each check has a recorded result; Switch Access and the accessibility-tree checks reach every action on API 37; fixes are in; a second set of four-language screenshots, with the screens of waves 7 and 8, is ready for the user's review.

**Integration check for wave 8:** merge; `tools/check.sh all`; the staging build runs every script under `tools/emu/` on API 24, 28 and 37 (steps that need a debuggable build are run on debug); the signed release APK is installed on the user's phone and the checks of A §8.6 begin. Tag `wave-8-ok`.

---

## 11. What can go wrong with the plan itself

| Risk | What the plan does |
|---|---|
| Inputs under `/tmp` vanish at a reboot | Package 0.3 copies them first, before any other work |
| A contract turns out wrong in a later wave | The owner reports an interface request; the integrator changes the document and makes the change on `main` at once as a tagged commit |
| A signature needs a type of the same wave | Not allowed (rule 3); the shared shapes are declared with the contracts (A §3.7) |
| Two packages need one file | Seams (A §3.6), "May also edit" lists, and the integrator; checked for every sub-wave before it starts |
| The clipboard lab's report differs from A D-14 | Only package 3.8 changes |
| An agent is cut off mid-package | It commits after each working step with a message that says what is next; another agent continues on the same branch |
| The machine resets or a JVM crashes | At most six agents and two Gradle builds at once; `tools/gradle.sh` retries once; worktrees and device bases live outside `/tmp` |
| A test lands on someone else's emulator, or on the user's own | Rule 8: `-s` and `ANDROID_SERIAL` everywhere, ports from a lock file, 5554 left alone |
| R8 breaks something that only release builds run | `staging` on API 24 and 37 at every integration check, with `smoke.sh` growing each wave |

---

## 12. Schedule from sub-wave 3B on

Decided on 2026-10-07, after sub-wave 3A took 2 h 3 min to build and 2 h 19 min to integrate. Nothing about what a package owns or must pass changes. Two things change: an integration is split in two, so that device checks no longer hold up the next packages, and packages that depend only on merged code are built earlier than their wave.

**The integration in two steps,** each one agent working alone on `main`:

1. **Merge.** Merge the round's branches; apply the interface requests (`iface-<n>`); `tools/check.sh all`; tag `wave-<id>-merged`; create the next round's worktrees from that tag; remove the merged worktrees.
2. **Device check.** Starts after the merge and runs while the next round's packages are built: everything else the sub-wave's integration check lists (connected tests, the scripts under `tools/emu/`, the staging build), written as `tools/emu/checks/<id>.sh`. It fixes what it finds on `main` and tags `wave-<id>-ok`.

Only one agent works on `main` at a time: the next merge starts when the device check before it has ended. In rule 1, "from the tag `wave-<previous sub-wave>-ok`" now reads "from the tag the round names", which is a `-merged` tag. A package whose branch is older than `main` is merged as it is; it never rebases.

**Rounds.** At most six packages are built at once, as before.

| Round | Built | Starts from | Merged when the round ends | Device check during the next round |
|---|---|---|---|---|
| R1 | 3.7 to 3.12, and `w3B-carry` (below) | `wave-3A-ok` | all, as `wave-3B-merged` | 3B's |
| R2 | 3.13, 3.14, 3.15, 4.2, 4.3, 5.1 | `wave-3B-merged` | 3.13 to 3.15 and 5.1, as `wave-3C-merged`. 4.2 and 4.3 stay on their branches: their screens need the lock manager of 4.1 | the skeleton check again, and 5.1's device tests |
| R3 | 4.1, 4.4, 5.2, 5.3, 5.4 | `wave-3C-merged` | 4.1 to 4.4 and 5.2 to 5.4, as `wave-4A-merged` | 4A's |
| R4 | 4.5, 5.5, 6.1, 7.1, 7.2, 7.3 | `wave-4A-merged` | all, as `wave-5-merged` | wave 4's, wave 5's and wave 7's |
| R5 | 6.2, 8.3, 8.4 | `wave-5-merged` | all, as `wave-6A-merged`; the string freeze starts | none of its own |
| R6 | 6.3 (in two parts), 6.4, 8.2 | `wave-6A-merged` | all, as `wave-6-merged` | wave 6's and 8A's |
| R7 | 8.1 | `wave-6-merged` | as `wave-8-merged` | wave 8's |

R1 to R3 are fixed. R4 to R7 are the intention and are confirmed when R3 is merged.

**What follows from the order.**
- 4.2, 4.3 and 5.1 need only the contracts and fakes of wave 2 and the screens of 3A, so they are built in R2. 5.2 to 5.4 are built next to 4.1 and test against the fakes, as the plan already has 4.2 and 4.3 do.
- Wave 7's packages are built in R4, before the translations, so they bring English strings only; 6.3 and 6.4 translate their files with all the others. The sentence "from wave 7 every new string comes in all four languages" (rule 5, section 9) then applies only to 8.1.
- Before R4 starts, the integrator sets lint's `MissingTranslation` to a warning (section 8, "Before 6A starts").

**`w3B-carry`: what 3A left open.** One more branch in R1, built after 3.11 has ended so that no more than six agents run at once. It owns nothing of its own; it may edit the files named here, which belong to packages that are merged:
1. `OpenedVault.createdMs` in `:core` storage (A §3.3, `StoreTypes.kt`, `VaultStore.kt`, with their tests), used by `DefaultVaultRepository`; `APP/data/GenerationHeaders.kt` goes.
2. 'Add "gmal" as a new account' opens the form with the name filled in (U §5.11). The name is handed over in memory only, like `SavedNotice`; it is the form's starting value, so a form in which nothing else was typed is left without a question and without a draft. A line in A §3.6 names the holder.
3. `RES/values/strings_data.xml` with `data_from_backup`; `StoredTexts` reads it.
4. On Android 7 the search field takes the focus after an unlock and the keyboard covers the Add button: the list opens without the focus in a field.

