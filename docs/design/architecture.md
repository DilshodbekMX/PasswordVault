# PasswordVault: architecture

Date 2026-10-06. Status: specification for the build ([build-plan.md](build-plan.md) gives the order of work). Revised the same day after two reviews of this file and the build plan (coverage and buildability) and after the audit of lock.md; the section "Review" at the end lists what was raised, what changed and what did not.

**Built from:** [lock.md](lock.md) as revised after its audit (its section "Audit"), [ux.md](ux.md), [formats.md](formats.md) with [formats-review.md](formats-review.md), [i18n.md](i18n.md), [search.md](search.md), `docs/research/VERIFICATION.md` (it overrides the research reports), `docs/research/crypto.md` and `storage.md`, the four lab reports in `docs/lab/`, the clipboard lab's working tables (now in its report, `docs/lab/clipboard.md`, with the lab's kit in `docs/lab/clipboard-artifacts/kit/`, brought in by build-plan package 0.3), and the code in `prototypes/` and `docs/lab/*-artifacts/`.

**Precedence.**
1. VERIFICATION.md over the research reports.
2. For the lock (its rules, records, flows, texts and tests): lock.md.
3. `MasterPasswordBytes` is stated twice, in lock.md §8.1 and in 4.6 here, and the two texts are the same function. If they ever differ, the frozen vectors of 4.13 and lock.md §4.3 decide, and both texts are corrected before any code depends on them.
4. For the vault file, the storage engine, module boundaries, interfaces, threading, dependencies, the build and the test strategy: this file. Section 4 is normative and replaces crypto.md §1 and storage.md §5.
5. ux.md, formats.md, i18n.md and search.md give the screens, the wording and the plain file formats, except where section 6 here or lock.md decides otherwise. Build-plan package 0.3 edits them to match; until that is merged, the deciding item is named next to every input that it overrules.

**Marks.** [V-xx] an item of VERIFICATION.md. [L-old], [L-bio], [L-win], [L-sto] a lab report; [L-clip] the clipboard lab (`docs/lab/clipboard.md`; kit in `docs/lab/clipboard-artifacts/kit/`). [X] computed or run by me today. [I] my judgement.

Short names: **MP** master password, **VK** vault key, **KEK** the key derived from the master password.

---

## 1. The shape on one page

```
 :app   screens (Compose) ── ViewModels ──┬── VaultRepository ── one owner of the open vault, one mutex
        io.github.dilshodbekmx           ├── LockManager ────── lock state, PIN pad digits, flows of lock.md
        .passwordvault                   ├── Session/AutoLock ─ process lifecycle, screen-off, idle timer
                                          ├── SecretClipboard, DocumentIo (file picker), AppSettings
        Android adapters: OsFiles · AndroidClock · NativeArgon2id (argon2kt) · Keystore keys · BiometricPrompt
        MainActivity calls the hooks of 3.6 and nothing else; each area plugs in through its own files
 ───────────────────────────────── only :app touches the Android framework ─────────────────────────────────
 :core  model · crypto · vaultfile · storage · lock (rules, records, flows) · search · text · formats
        .passwordvault.core   · generator · strength      pure Kotlin, JVM tests, lint against minSdk 24
        reaches the outside only through: FilePrimitives, RecordFiles, Clock, RandomSource, Argon2id,
        PinKey, FingerprintKey, DeviceKeys, VaultPort
 :testing  the fakes of every port, shared by the tests of both modules (no Android calls)
```

1. **Two product modules and one test module.** `:core` holds every rule and every byte format and makes no Android framework call. `:app` holds everything that needs a `Context`, the Keystore, a window or a lifecycle. `:testing` holds the fakes and the source scanner of the build checks, and is only ever a test dependency.
2. **One file format.** A vault generation and a backup file are the same container (`PVAULT01`, section 4). Nothing device-bound is ever in it.
3. **One owner.** `DefaultVaultRepository` is the only object that holds VK and the decrypted entries, behind one mutex. Screens see immutable snapshots.
4. **Every save is a new generation file**, written, synced, read back, proven where it carries a new password slot, and only then renamed into place and published (4.9).
5. **The master password alone opens everything** (lock.md requirement 1). PIN and fingerprint are device records in `noBackupFilesDir/lock/<vault_id>/` plus Keystore keys.
6. **The lock's rules live in `:core`**, its devices in `:app` (decision D-1 in section 6).
7. **No network.** No `INTERNET` permission, checked on every build; no library that needs one.
8. **Nothing secret in saved state, logs, toasts, exception messages, or the clipboard without the sensitive flag** (5.6).
9. **No dependency injection framework, no database, no annotation processor.** One hand-written `AppGraph`.
10. **Clocks and randomness are injected**, so every rule can be tested on the JVM with a fake clock, a fake file system and fixed random bytes.
11. **Packages are built side by side.** A package changes only its own files. The seams of 3.6 (one factory file per area, one hook file per activity concern, one route file per package) are how a package plugs into the app without editing anyone else's file.

---

## 2. Modules and packages

Source roots: `core/src/main/java/io/github/dilshodbekmx/passwordvault/core/…`, `app/src/main/java/io/github/dilshodbekmx/passwordvault/…` and `testing/src/main/java/io/github/dilshodbekmx/passwordvault/testing/…`. A package is a directory; the build plan hands whole directories, or named files, to one owner.

### 2.1 `:core` (Android library, namespace `…passwordvault.core`)

It may use: the Kotlin standard library, `kotlinx-coroutines-core`, `kotlinx-serialization-json`, Bouncy Castle (Argon2 only), zxcvbn4j, and JDK classes that exist on API 24 (`javax.crypto`, `java.security`, `java.text.Normalizer`, `java.util.zip`, `java.net.IDN` and `java.net.URI` for host names only). It may not import `android.*` or `androidx.*`; a unit test scans the sources for both (8.5). No `java.time`, `java.util.Base64`, `java.nio.file` (API 26) [V-C12, L-old §7]. No logging of any kind (R-6).

| Package | Holds | Main types |
|---|---|---|
| `core.util` | Small tools every package shares | `Clock`, `RandomSource`, `Bytes` (hex, wipe, constant-time equals), `B64` (`kotlin.io.encoding.Base64`), `Uuid` (make, validate, `uuid16`), `StrictJson` (reader that refuses duplicate keys and deep nesting; used for the vault body, lock records and the import depth check), `ParseError` (an offset and a reason, never input text; 5.6), `TextHygiene` (control characters, unpaired surrogates) |
| `core.model` | The entry model of ux.md §2 and every change to it as a pure function | `Entry`, `CustomField`, `HistoryItem`, `Vault` (entries plus vault-level data, 4.3), `Draft`, `FileRef`, `EntryRules` (limits, validation, title and URL rules), `VaultEdits` (add, update, password change with history, guided change, delete, restore, purge, draft, import, merge), `Edited<T>`, `EditTexts` (the few texts an edit stores, such as "Untitled" and "(from backup)", passed in from `:app`) |
| `core.crypto` | Cryptography behind small interfaces | `Argon2id`, `Argon2Params`, `BouncyCastleArgon2id`, `GuardedArgon2id` (fallback and free-heap check [V-C2]), `MasterKdf` and `GatedMasterKdf` (one derivation at a time), `Aead`, `JcaAesGcm`, `MasterPasswordBytes`, `KdfUnavailable`, `CryptoFactory` |
| `core.vaultfile` | The container of section 4: bytes in, bytes out | `VaultCodec`, `DefaultVaultCodec`, `VaultHeader`, `PasswordSlot`, `VaultFile`, `FileCheck`, `BodyResult`, `HeaderCodec` (canonical form), `BodyCodec` (JSON ↔ `Vault` in the writer's canonical form, unknown keys kept), `BuildInfo`, `VaultCodecs` (factory) |
| `core.storage` | The generation store, on injected file primitives | `FilePrimitives`, `VaultStorage` (interface), `VaultStore` (implementation), `VaultStores` (factory), `VaultRef`, `Scan`, `VaultFacts`, `FileState`, `OpenedVault`, `Staged`, `OpenResult`, `PasswordResult`, `CommitResult`, `Housekeeping` |
| `core.lock.pin` | PIN rules | `PinBlocklist` (lock.md appendix A, ported to a `ByteArray` of six ASCII digits) |
| `core.lock.record` | The three device records of lock.md §5: types and codec, not the files | `PinRecord`, `FingerprintRecord`, `StateRecord` (the fields of lock.md §5.4 to §5.6 as revised by its audit), `RecordCodec`, `RecordRead` (`Ok(record)`, `Missing`, `Damaged(error: ParseError)`, `Newer`, `Unreadable`), `RecordFiles`, `RecordStore` and `FileRecordStore` (one typed record over `RecordFiles`) |
| `core.lock.guard` | Counting and waiting | `PinGuard` (lock.md §6.3), `MasterPasswordGuard` (§6.8), `Credit` (§6.9), `PracticeSchedule` (§8.3) |
| `core.lock.policy` | Decisions without side effects | `EntryPolicy` (§13.1), `SessionPolicy` (delay arithmetic of §10), `KeystoreErrors` (the table of §14.3 over `KeystoreFailure`), `Ks`, `KeystoreFailure` |
| `core.lock.flow` | The flows F1 to F18 and the start-up of lock.md §5.11 over ports | `LockEngine` (with `LockCore`, `UnlockFlows`, `ShortcutFlows`, `PasswordFlows`, `VaultFlows`, `OlderCopies`, `StartUp`, `Sweep`), `PinKey`, `FingerprintKey`, `DeviceKeys`, `KeyCreate`, `VaultPort` and its result types with the lock's own `VaultRef`, `GenerationInfo` and `AsideVault` (3.4), `MasterProof`, `LockScreen`, `Notice`, `PinOutcome` |
| `core.search` | search.md, moved from `prototypes/search` | `searchKey`, `SearchIndex` (with `searchOrRetry`), `SearchFields`, `SearchHit`, `SearchOutcome`, `matchedRanges`, `WebAddress`, `KeyboardLayout` (wrong-layout retry) |
| `core.text` | Uzbek script converters | `UzbekScript`, `UzbekLatinAlphabet` |
| `core.formats` | Every file besides the vault: formats.md, moved from `prototypes/formats` with the review's fixes | `Csv`, `TextDecoding`, `IsoTime`, `GoogleCsv`, `FieldsBlock` (the grammar of D-10a), `BitwardenJson` (writer and reader), `Xlsx`, `VaultXlsx`, `Importer`, `ImportPlanner`, `FileKind` (recognition by first bytes), `ExportNames` |
| `core.generator` | Passwords and passphrases | `PasswordGenerator`, `GeneratorOptions` (declared with the contracts, 3.2), `Passphrase`, `Wordlist` (EFF short list 1, a resource file) |
| `core.strength` | Strength and the security check | `StrengthMeter` (zxcvbn4j plus the Uzbek and Russian word list), `MasterPasswordRules` (ux.md §5.3 checklist), `SecurityCheck`, `SecurityReport` (declared with the contracts: reused groups, weak, pending, left out, same as master password) |

Tests sit beside each package under `core/src/test/java/…/core/<package>/`. Every fake of a `:core` port lives in `:testing` (`FakeClock`, `FakeRandom`, `FakeFiles`, `FakeCodec`, `FakeRecordFiles`, `FakeRecordStore`, `FakeKeys`, `FakeVaultPort`), so that the tests of `:app` use the same fakes as those of `:core`. `:testing` also holds the source scanner of the build checks, `testing/src/main/java/…/testing/arch/SourceScan.kt` (package `…testing.arch`), which the source-rule tests of both modules use (`core/src/test/java/…/core/arch/`, `app/src/test/java/…/arch/`, 8.5); it is the integrator's file. Frozen test files (the vault vectors of 4.13, the format fixtures) are under `core/src/test/resources/`.

The three factory objects (`CryptoFactory`, `VaultCodecs`, `VaultStores`, 3.2 and 3.3) are how one package gets another's implementation without naming its class, so that packages can be built side by side. Their signatures are fixed in section 3; until the owning package is merged their bodies throw `NotImplementedError`.

### 2.2 `:app` (application, namespace and app ID `io.github.dilshodbekmx.passwordvault`)

| Package | Holds | Main types |
|---|---|---|
| (root) | Process and activity | `PasswordVaultApp : Application` (builds `AppGraph`), `AppGraph` (3.6), `MainActivity : AppCompatActivity` (calls the hooks of 3.6 in a fixed order and holds nothing else) |
| `platform` | Android implementations of `:core` ports | `OsFiles : FilePrimitives, RecordFiles` (`Os.rename`, `Os.fsync`; never `AtomicFile`, DataStore or `Files.move` [V-S7]), `AndroidClock`, `SystemRandom`, `NativeArgon2id` (argon2kt [V-C1]), `AppDispatchers`, `PlatformModule` |
| `keystore` | Android Keystore | `KeystoreAccess : DeviceKeys` (load, `pv_canary` made and made again, the hardware check of lock.md §4.5, the alias list), `KeystorePinKey : PinKey`, `KeystoreFingerprintKey : FingerprintKey`, `KeystoreFailureAdapter` (exception → `KeystoreFailure`), `KeystoreModule` |
| `lock` | The lock as the app sees it | `LockManager`, `DefaultLockManager` (owns `LockEngine` and the lock mutex), `LockUiState`, `LockIntent`, `LockStatus`, `BiometricLauncher` (interface) and `BiometricHost` (the activity hook that builds the one `BiometricPrompt`), `LockDebug` (the test hooks of lock.md §15.3), `LockModule` |
| `session` | When the app locks | `Session`, `AutoLock` (`ProcessLifecycleOwner`, screen-off receiver, idle timer, the grace of lock.md §10.3, the draft flush of 5.5), `SessionModule` |
| `data` | The vault as the screens see it | `VaultRepository`, `DefaultVaultRepository : VaultRepository, VaultPort`, `VaultState`, `ListModel` and `ListModelProvider` (sorted list, sections, `SearchIndex`; rebuilt when the language changes), `SecurityReports`, `SecurityCheckRunner` and `SecurityModule`, `DataModule` |
| `clipboard` | Copying | `SecretClipboard`, `AndroidSecretClipboard`, `ClipboardClearReceiver`, `ClipStatus`, `SecretClipboardBridge` (the Compose `Clipboard` provided as `LocalClipboard`, ux.md §6 item 5), `ClipboardRoot` (hook), `ClipboardModule` |
| `window` | Window and input protections, from `docs/lab/window-input-artifacts/VaultSecurity.kt` with the API 26 gate of lock.md §13.5 | `VaultSecurity` (activity window), `VaultWindow` (dialogs and sheets), `Modifier.vaultTextField()`, `NoPersonalizedLearning`, `ObscuredTouchNotice`, `WindowPolicy` (hook) |
| `files` | Storage Access Framework | `DocRef`, `DocumentIo`, `SafDocumentIo`, `FileTask`, `FileTaskStore`, `BackupSharing` (`FileProvider`), `Places`, `DocumentLaunchers` (hook: the picker launchers registered by the activity), `FilesModule` |
| `settings` | Non-secret settings | `AppSettings`, `PrefsAppSettings`, `LockDelay`, `ClipDelay`, `ThemeChoice`, `AppLanguage`, `OneTime`, `PurgeArm`, `SettingsModule` |
| `i18n` | Language-dependent formatting | `UiText` (resource id plus arguments), `Formatters` (`android.icu` dates, relative times, numbers), `SortKeys` (`Collator`, `AlphabeticIndex`, the sort key of i18n.md §2.7, behind the `Collation` interface), `AppLanguages` |
| `nav` | Navigation 3 | `Route` (every key, holding ids only), `NavState` (three back stacks: setup, locked, unlocked), `Navigator`, `AppNavHost` |
| `ui.theme`, `ui.common` | Look and shared parts | `PasswordVaultTheme`, `PinPad`, `SecretText`, `MaskedValue`, `VaultTextField`, `VaultSecureField`, `VaultDialog`, `VaultSheet` (the only ways to make a text field, a dialog or a sheet; R-13), `Banner`, `ClipboardRow`, `StrengthBar` |
| `ui.shell` | The frame around the screens | `AppRoot` (the root content: window policy, clipboard bridge, theme, navigation), `UnlockedScaffold` (bottom bar, the clipboard row's slot, the Security tab's dot) |
| `ui.setup` | ux.md §5.1–5.8 | Welcome, CreateMasterPassword, WriteDown, TypeAgain, CreatePin, FingerprintOffer, BackupsExplained |
| `ui.lock` | ux.md §5.9–5.10, lock.md §13 | PinLockScreen, FingerprintOnlyScreen, MasterPasswordScreen, ForgotSheet, OlderPasswordScreen, AsideLockScreen, Notices |
| `ui.list`, `ui.entry`, `ui.edit` | ux.md §5.11–5.15, §5.23 | PasswordsScreen, RecentlyDeleted, EntryDetail, BigLetters, EditScreen, GeneratorSheet |
| `ui.security` | ux.md §5.16–5.17 | SecurityScreen, GroupList, GuidedChange, SecurityBadge |
| `ui.transfer` | ux.md §5.2, §5.19–5.21, the vaults put aside | RestoreScreens, BackupScreen, ExportScreens, ImportScreens, ColumnMapping, OldPasswords |
| `ui.settings` | ux.md §5.18, §5.22, §5.24 | SettingsScreen, ChangePin, AppLockPages, ChangeMasterPassword, NewMasterPassword, LanguageDialog, Help, Licences |
| `fake` | In-memory stand-ins for the interfaces of 3.5, for the shell of the first waves, previews and ViewModel tests; a proof they hand out comes from `MasterProof.forFakes` (3.4, R-15) | `FakeVaultRepository`, `FakeLockManager`, `FakeAppSettings`, `FakeSecretClipboard`, `FakeDocumentIo`, `FakeListModelSource`, `FakeSecurityReports`. Also `FakeLauncher` for `BiometricLauncher`. In `src/main` while a module file still returns one; the integrator moves the package to `src/debug` once no file under `src/main` names it (expected after wave 7) |
| `debug` (source sets `debug` and `staging` only) | Test hooks | `DebugCommandReceiver` (exported, guarded by `android.permission.DUMP`; dispatches to the commands listed in `DebugCommands`), the lock commands of lock.md §15.3, a language command. Every `@Preview` lives in `src/debug` too, so that release code never names a fake |

Resources: one string file per screen area (`res/values/strings_lock.xml`, `strings_list.xml`, …) with the same file names in `values-uz`, `values-b+uz+Cyrl` and `values-ru`, so that two packages never edit the same file. **Every string name starts with its area** (`lock_…`, `list_…`); shared words live only in `strings_common.xml`. Drawables follow the same rule (`ic_<area>_…`). The app's name is one resource, `app_name` = "PasswordVault", used wherever a text names the app (D-23).

---

## 3. Interfaces

The names, directions and result types below are the contract between packages. Rules:

- A signature names only Kotlin and JDK types, types of its own package, and types that a package of an **earlier** wave of the build plan declares. Types that several waves need early (`GeneratorOptions`, `SecurityReport`, the record shapes) are declared with the contracts (3.7), not by the package that later fills them.
- A comment that lists result cases (`Opened | Wrong | …`) is part of the contract: the declaring package writes each case as a class with the fields named here.
- Adding, changing or removing a member is an *interface request* (build plan §1): this section is changed first, then the integrator makes the change on `main` as a small tagged commit that the packages merge in.

### 3.1 Clock and randomness (`core.util`)

```kotlin
interface Clock {
    fun wallMs(): Long                         // System.currentTimeMillis(): display, the practice schedule and the arming of the purge only (lock.md I8, 4.3)
    fun elapsedMs(): Long                      // SystemClock.elapsedRealtime(): every wait, delay and credit
    suspend fun delayUntilElapsed(elapsedMs: Long)
}
interface RandomSource {
    fun bytes(n: Int): ByteArray               // SecureRandom() with no arguments (getInstanceStrong is API 26)
    fun int(bound: Int): Int                   // unbiased, 0 until bound; never "% n"
    fun uuid(): String                         // version 4, lower case
}
class ParseError(val offset: Long, val reason: String)   // reason is a constant from the parser's own list; never a piece of the input (5.6)
```

There is no boot count: lock.md dropped it with its audit (decision 15).

### 3.2 Cryptography (`core.crypto`)

```kotlin
class Argon2Params(val memoryKiB: Int, val passes: Int, val lanes: Int, val outLen: Int) {
    companion object { val V1 = Argon2Params(65_536, 3, 4, 32) }
}
fun interface Argon2id {
    /** Argon2id v1.3. Blocks for 0.3-1 s and needs 64 MiB. The caller passes exactly the bytes to hash.
     *  @throws KdfUnavailable when it cannot run on this phone now. It never returns a wrong key. */
    fun derive(password: ByteArray, salt: ByteArray, params: Argon2Params): ByteArray
}
class KdfUnavailable(cause: Throwable?) : Exception(cause)       // "not enough memory": not a wrong password, not counted

interface Aead {                                                  // AES-256-GCM, 12-byte nonce, 16-byte tag
    fun seal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray?): ByteArray
    fun open(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, aad: ByteArray?): ByteArray?   // null: the tag failed
}
object MasterPasswordBytes {                                      // package 1.3; frozen before the first release (4.6)
    fun of(chars: CharArray): ByteArray
    fun same(first: CharArray, again: CharArray): Boolean         // of(first) and of(again) compared in constant time, both wiped
    fun firstUnassigned(chars: CharArray): Int?                   // creation only [V-C9]: the first unassigned code point or unpaired
                                                                  // surrogate, as its value (not its index); null: none
}
interface MasterKdf {                                             // one derivation at a time in the process (lock.md §6.8)
    suspend fun kek(passwordBytes: ByteArray, salt: ByteArray): ByteArray     // throws KdfUnavailable
}
object CryptoFactory {                                            // bodies throw NotImplementedError until package 2.1 is merged
    fun aead(): Aead
    fun bouncyCastleArgon2id(): Argon2id
    fun guardedArgon2id(native: Argon2id?, fallback: Argon2id, freeHeap: () -> Long, fallbackNeeds: Long): Argon2id
    fun masterKdf(argon2: Argon2id, dispatcher: CoroutineDispatcher): MasterKdf
}
```

| Implementation | Where | Notes |
|---|---|---|
| `BouncyCastleArgon2id` | `:core` | JVM tests and the fallback on a phone. About 70 MiB of Java heap on Android 9, about 134 MiB on Android 7 [L-old §2] |
| `NativeArgon2id` | `:app` | argon2kt 1.6.0. Direct buffers of exactly the input size, the key copied out once, both returned buffers overwritten, never `rawHashAsByteArray()` or `encodedOutputAsString()` [V-C1] (R-11) |
| `GuardedArgon2id` | `:core` | `native` is null when loading it threw any `Throwable` [V-C2]. A `LinkageError` thrown by a call of `native` (a library that loaded but fails at its first call) counts the same: that derivation and every later one of this `GuardedArgon2id` (the app makes one per process) take the fallback, with its heap check. Before the fallback runs, `freeHeap()` (`maxMemory − totalMemory + freeMemory`) must be at least `fallbackNeeds`, otherwise `KdfUnavailable`. `:app` passes 160 MiB below API 28 (about 134 MiB measured on API 24) and 96 MiB from API 28 (about 70 MiB measured) [L-old §2]; API 25–27 were not measured and take the larger number. `OutOfMemoryError` becomes `KdfUnavailable`. Android 17's memory limiter is tested in 8.3 [V-P7] |
| `GatedMasterKdf` | `:core` | The process-wide gate over a `GuardedArgon2id`, on `Dispatchers.Default` |
| `JcaAesGcm` | `:core` | `Cipher.getInstance("AES/GCM/NoPadding")`, no provider name. `destroy()` is never called [V-C10] |

### 3.3 Vault file and storage (`core.vaultfile`, `core.storage`)

```kotlin
class PasswordSlot(val salt: ByteArray, val nonce: ByteArray, val wrappedKey: ByteArray)        // 16, 12, 48 bytes
class VaultHeader(val vaultId: String, val vkId: String, val generation: Long, val createdMs: Long,
                  val generator: String, val writerVersion: Int, val bodyLen: Int, val slot: PasswordSlot)
class VaultFile(val header: VaultHeader, val aad: ByteArray, val bodyNonce: ByteArray, val bodyCt: ByteArray,
                val trailerOk: Boolean)
sealed interface FileCheck {                           // rules R1-R7 of 4.4: no key, no KDF
    class Ok(val file: VaultFile) : FileCheck
    object NotOurs : FileCheck                         // not of the PVAULT family (R1); DAMAGED inside a vault directory (4.8)
    class Newer(val version: Int) : FileCheck          // made by a newer app (R1, R6): never opened, never overwritten
    class Damaged(val rule: Int) : FileCheck           // the number of the first rule that failed
}
sealed interface BodyResult {                          // rules R8-R10
    class Opened(val vault: Vault) : BodyResult
    object TagFailed : BodyResult                      // R8: this file does not open under this key
    class Damaged(val error: ParseError) : BodyResult  // R9, R10
    class Newer(val schema: Long) : BodyResult         // R9
}
class BuildInfo(val generator: String, val writerVersion: Int)   // "PasswordVault " + versionName, versionCode (R-14)
interface VaultCodec {
    fun check(bytes: ByteArray): FileCheck
    fun unwrap(slot: PasswordSlot, kek: ByteArray): ByteArray?               // VK, or null
    fun open(file: VaultFile, vk: ByteArray): BodyResult
    fun seal(vault: Vault, vk: ByteArray, vaultId: String, vkId: String, generation: Long, slot: PasswordSlot): ByteArray   // 4.5
    fun wrap(vk: ByteArray, kek: ByteArray, salt: ByteArray): PasswordSlot   // a fresh slot nonce
}
object VaultCodecs { fun create(aead: Aead, random: RandomSource, clock: Clock, build: BuildInfo): VaultCodec }

interface FilePrimitives {                             // every failure throws IOException; nothing is logged and swallowed
    fun list(dir: String): List<String>?               // null: the directory does not exist; IOException: it cannot be listed
    fun read(path: String, maxBytes: Int): ByteArray   // reads at most maxBytes + 1 bytes: a longer result means "too long", not an error
    fun writeNew(path: String, bytes: ByteArray)       // create exclusively, write, flush, fsync, close
    fun exists(path: String): Boolean
    fun rename(from: String, to: String)               // rename(2): replaces an existing target, so callers check first [V-S1]
    fun syncDir(dir: String)                           // open(O_RDONLY), fsync, close
    fun delete(path: String)
    fun mkdir(dir: String)
    fun deleteTree(dir: String)
}

sealed interface VaultRef { object Current : VaultRef; class Aside(val vaultId: String) : VaultRef }
enum class FileState { OK, DAMAGED, NEWER, UNREADABLE }                  // one generation file (4.8)

interface VaultStorage {                               // VaultStore; callers hold the vault mutex; nothing here derives a key
    fun scan(): Scan                                                     // 4.8; safe before unlock; reads every vault directory
    fun slots(target: VaultRef): List<SlotRef>                           // distinct password slots among the kept generations, newest first [V-S3]
    fun openWithSlot(target: VaultRef, slot: SlotRef, kek: ByteArray): PasswordResult   // Opened(OpenedVault) | OlderPassword(changedAtMs) | SlotClosed
    fun open(target: VaultRef, keys: Map<String, ByteArray>): OpenResult              // vk_id -> VK: Opened(OpenedVault) | NeedsOtherKey(generation, createdMs) | Damaged | Newer | Unreadable
    fun commit(opened: OpenedVault, vault: Vault): CommitResult          // an ordinary save, 4.9: Saved(OpenedVault) | NotDurable | Failed | ReadOnly | TooLarge
    fun stageVault(vault: Vault, vaultId: String, vk: ByteArray, vkId: String, slot: PasswordSlot): StageResult   // creation, restore, F11: new-<vaultId>/
    fun stageRekey(opened: OpenedVault, vault: Vault, vk: ByteArray, vkId: String, slot: PasswordSlot): StageResult  // F8: vault-N.tmp in live/
                                                                         // StageResult: Staged(Staged) | Failed | TooLarge
    fun prove(staged: Staged, kek: ByteArray): Boolean                   // 4.9 step 7, on the bytes read back from disk; false: renamed to .bad
    fun publish(staged: Staged, putCurrentAside: Boolean): CommitResult  // 4.9 steps 8 to 10: Saved(OpenedVault) | NotDurable | Failed
    fun discard(staged: Staged)                                          // the staged file or directory is removed; nothing live changes
    fun <T> useKey(opened: OpenedVault, use: (vk: ByteArray) -> T): T
    fun housekeeping(opened: OpenedVault)                                // 4.10
    fun retireOldKeys(opened: OpenedVault): Set<String>                  // 4.11; returns the vk_ids still kept
    fun putAside(): Boolean                                              // 4.11, "Start with an empty app" (lock.md F17 step 2)
    fun swapIn(vaultId: String): Boolean                                 // 4.11; only after open(Aside(vaultId)) succeeded in this process
    fun putNewerAside(generation: Long): Boolean                         // 4.11; an unfinished master-password change only (lock.md F16b)
    fun deleteAside(vaultId: String)                                     // 4.11; the caller has checked that vault's own master password
}
class Scan(val current: Current, val aside: List<AsideVault>)            // Current: NoVault | Present(VaultFacts) | Unreadable(why: IO | DAMAGED | NEWER)
class VaultFacts(val vaultId: String, val newest: GenerationInfo, val keptVkIds: Set<String>?, val olderCopyFirst: Boolean)   // keptVkIds null: a live file could not be read
class GenerationInfo(val number: Long, val vkId: String, val createdMs: Long)
class AsideVault(val vaultId: String, val putAsideAtMs: Long, val newest: GenerationInfo?)    // newest null: it cannot be read now
class Staged internal constructor(val vaultId: String, val vkId: String, val generation: Long,
                                  val slotSalt: ByteArray /* as read back from disk */, internal val path: String)
class OpenedVault internal constructor(val vault: Vault, val vaultId: String, val generation: Long, val vkId: String,
                                       val newestOnDisk: Long, internal val vk: ByteArray, internal val slot: PasswordSlot)
class SlotRef internal constructor(val salt: ByteArray, val vkId: String, val generation: Long, internal val slot: PasswordSlot)
                                                       // one distinct slot (4.8 step 1): the caller derives the KEK with `salt`;
                                                       // vkId and generation are those of the newest generation that carries it
object VaultStores {                                   // the body throws NotImplementedError until package 2.3 is merged
    fun create(files: FilePrimitives, codec: VaultCodec, clock: Clock, vaultsDir: String, build: BuildInfo): VaultStorage
}                                                      // vaultsDir: <filesDir>/vaults (4.7); clock: only the wall-clock ms in an
                                                       // aside-<ms>-<vault_id>/ name, for display (retention uses no clock [V-S4]);
                                                       // build: the running app's, for the writer_version rule of 4.10
```

`OpenedVault`, `Staged` and `SlotRef` keep VK, the slot and paths `internal` to `:core`, so `:app` can hold them but reach VK only through `useKey`. `olderCopyFirst` says that the newest generation was DAMAGED and an older one is the newest readable (the notice of ux.md §5.9, and the hold on automatic writes in 5.5).

### 3.4 Lock ports (`core.lock.record`, `core.lock.flow`)

```kotlin
interface RecordFiles {                                // lock.md §5.9
    fun read(dir: String, name: String): ByteArray?    // null: no such file; other errors throw IOException
    fun replace(dir: String, name: String, bytes: ByteArray)     // tmp, fsync, rename, directory fsync; any exception = not written
    fun dirs(root: String): List<String>
    fun deleteDir(dir: String)
    fun deleteStaleTmp(root: String)
}
interface RecordStore<T> {                             // one record of one vault; FileRecordStore = RecordCodec + RecordFiles
    fun read(): RecordRead<T>                          // the five results of lock.md §5.3
    fun write(record: T)                               // throws IOException: not written
}
sealed interface Ks<out T> {                           // lock.md §14
    class Ok<T>(val value: T) : Ks<T>
    class Gone(val why: String) : Ks<Nothing>
    class Temporary(val cause: Throwable?) : Ks<Nothing>
}
sealed interface KeyCreate {                           // making a key: the NOT NOW rows of lock.md §14.3
    object Ok : KeyCreate
    object NotNow : KeyCreate                          // any passing failure; the half-made alias is deleted
    object NoneEnrolled : KeyCreate                    // fingerprint only: "Add a fingerprint in your phone's settings first."
    object NoSecureHardware : KeyCreate                // KeyInfo says software, in a release build: for good (F18)
}
interface DeviceKeys {                                 // KeystoreAccess
    fun ensureCanary(): KeyCreate                      // F1 step 5, F6 step 4, F9 step 4, and lock.md §14.2 when the canary is missing
    fun aliases(): List<String>                        // every alias that starts with pv_pin_ or pv_fp_; empty when the Keystore is not answering
    fun delete(alias: String)                          // errors ignored; the sweep retries
}
interface PinKey {
    fun create(alias: String): KeyCreate               // the HMAC key of lock.md §4.2, checked by §4.5
    fun hmac(alias: String, message: ByteArray): Ks<ByteArray>      // 32 bytes; the only thing the key ever does
}
enum class PromptUse { UNLOCK, TURN_ON, KEEP_AFTER_CHANGE, CONFIRM_REPLACEMENT }    // the four rows of lock.md §9.2 rule 5
interface FingerprintKey {
    fun availability(): Int                            // canAuthenticate(BIOMETRIC_STRONG); once per visit, off the main thread (lock.md §13.1)
    fun create(alias: String): KeyCreate
    fun probe(alias: String, nonce: ByteArray): Ks<Unit>            // Cipher.init for decryption, before any prompt (F3 step 2)
    suspend fun seal(alias: String, vk: ByteArray, use: PromptUse, launch: Int): FingerprintResult
    suspend fun open(alias: String, nonce: ByteArray, ct: ByteArray, use: PromptUse, launch: Int): FingerprintResult
                                                       // FingerprintResult: Ok(bytes, nonce) | Closed(code) | Gone(why) | Temporary(code: Int?) | NotShown;
                                                       // Temporary(null): the prompt succeeded, then the key operation failed or the
                                                       // prompt's cipher was missing (lock.md §9.2 rule 6, §9.3)
    fun cancel()
}
sealed interface VaultRef { object Current : VaultRef; class Aside(val vaultId: String) : VaultRef }
class GenerationInfo(val number: Long, val vkId: String, val createdMs: Long)
class AsideVault(val vaultId: String, val putAsideAtMs: Long, val newest: GenerationInfo?)    // these three: the lock's own (R-10), as in 3.3
class VaultScan(val current: CurrentVault, val aside: List<AsideVault>)
                                                       // CurrentVault: NoVault | Present(vaultId, newest: GenerationInfo, keptVkIds: Set<String>?)
                                                       //   | Unreadable(why: CurrentVault.Why), with enum class Why { IO, DAMAGED, NEWER }
interface VaultPort {                                  // the lock's whole view of the vault; DefaultVaultRepository implements it
    fun vaults(): VaultScan                            // before anything is typed; lock.md §5.11 step 2 and §16.3 "Storage"
    suspend fun open(target: VaultRef, keys: Map<String, ByteArray>): VaultOpen        // PIN, fingerprint, the pending wrap of F11:
                                                       // Opened(vaultId, generation, vkId, createdMs, olderCopy) | NeedsOtherKey(generation, createdMs) | Damaged | Newer | Unreadable
    suspend fun openWithPassword(target: VaultRef, passwordBytes: ByteArray): PasswordOpen   // every distinct slot before "wrong" [V-S3]:
                                                       // Opened(…) | OlderPassword(changedAtMs, generation) | Wrong | NoMemory | Damaged | Newer | Unreadable;
                                                       // generation: the newest generation the typed password opens (lock.md F16b step 2)
    suspend fun checkPassword(passwordBytes: ByteArray): Boolean?        // the open vault's own slot; null: not enough memory
    suspend fun <T> withVaultKey(use: (vkId: String, vk: ByteArray) -> T): T   // to wrap VK for a PIN or a fingerprint
    suspend fun lock()                                 // waits for a save that is running, then drops the session; never writes
    // The only calls that write vault data (lock.md I1): F1, F8, F11, F13, F16b, F17. Each proves a new slot from disk (4.9).
    suspend fun createVault(vaultId: String, first: ByteArray, again: ByteArray, from: Vault?, putCurrentAside: Boolean): VaultWrite
                                                       // F1, F13, F17 step 3: slot from `first`, proof with `again`
    suspend fun replaceVault(newVaultId: String, passwordBytes: ByteArray): VaultWrite      // F11 step 16: vault N from the open one; then the old one goes aside
    suspend fun newKey(): Pair<String, ByteArray>                                    // F8 step 4: vk_id2 and VK2, nothing written
    suspend fun changePassword(first: ByteArray, again: ByteArray, vkId: String, vk: ByteArray): VaultWrite   // F8 step 6
                                                       // VaultWrite: Done(vaultId, vkId, generation, createdMs) | NothingChanged | Quarantined | Unclear
    suspend fun mergeAside(vaultId: String): VaultWrite                              // F17 step 5 "Open": its accounts into the current vault (4.12 merge rules)
    suspend fun swapIn(vaultId: String): Boolean                                     // F16b step 4, F17 step 4
    suspend fun putAside(): Boolean                                                  // F17 step 2
    suspend fun putNewerAside(generation: Long): Boolean                             // F16b, an unfinished change
    suspend fun deleteAside(vaultId: String): Boolean                                // F17 step 5, after the lock has checked that vault's master password
    suspend fun retireOldKeys(): Set<String>                                         // F8 step 10
}
class MasterProof internal constructor(bytes: ByteArray, val vaultId: String) {   // the verified MasterPasswordBytes (copied), the vault they opened
    fun <T> useBytes(use: (ByteArray) -> T): T         // made only by LockEngine after a correct master password (lock.md §5.8, §8.2);
                                                       // lends a copy that is wiped when `use` returns
    fun drop()                                         // zeroes; also when its flow ends, at ON_STOP and on lock
    companion object {
        fun forFakes(bytes: ByteArray, vaultId: String): MasterProof   // no master password behind it: fakes, previews and tests only (R-15)
    }
}
```

- `open` and `openWithPassword` on `VaultRef.Aside(id)` open that vault in place and keep it, still apart from the current one, until `swapIn(id)`, `mergeAside(id)` or a lock. Only one vault put aside is held at a time. The lock checks the master password of a vault put aside under *that* vault's guard and records (lock.md §6.8), so the screens never call storage for it directly.
- `VaultScan`, `VaultOpen`, `PasswordOpen` and `VaultWrite` are declared in `core.lock.flow`, and so are `VaultRef`, `GenerationInfo` and `AsideVault` (`lock/flow/VaultTypes.kt`): the lock's own counterparts of the storage types of the same names (3.3), because R-10 keeps `core.lock` from naming `core.storage`. `DefaultVaultRepository` maps between the two and maps the storage engine's results onto the lock's, and the lock never receives VK except inside `withVaultKey`. The screens' side uses the `core.lock.flow` types too: `LockUiState.NoVault(aside)`, `LockUiState.Unreadable(why, aside)` with `why` a `CurrentVault.Why`, and `LockStatus.aside` (3.5).
- `LockEngine` is built from `RecordFiles`, `Clock`, `RandomSource`, `PinKey`, `FingerprintKey`, `DeviceKeys`, `VaultPort`, `MasterKdf` and `Aead`. An architecture test forbids `core.lock` from importing `core.storage` or `core.vaultfile` (lock.md T17).
- **A proof for fakes.** Code outside `:core` cannot call `MasterProof`'s internal constructor, so `MasterProof.forFakes` is how the fakes of `APP/fake/` script `MpResult.Right(proof)` and `LockUiState.Unlocked(notices, proof)`, and how previews in `app/src/debug/` and tests make one. Build check R-15 (8.5) allows the name only there, in `:testing` and in `MasterProof.kt`, so release code only ever holds proofs that `LockEngine` made after a correct master password.
- Who deletes a lock directory: the lock, after `VaultPort.deleteAside` returned true (lock.md F17 step 5): `RecordFiles.deleteDir`, then `DeviceKeys.delete` for its two aliases. Start-up (lock.md §5.11) never deletes a directory whose vault is gone.

### 3.5 What the screens talk to (`:app`)

```kotlin
sealed interface VaultState {
    object Locked : VaultState                         // also "no vault": the lock decides which screen that is
    class Unlocked(val vault: Vault, val generation: Long, val saveProblem: SaveProblem?, val olderCopy: Boolean) : VaultState
}
interface VaultRepository {
    val state: StateFlow<VaultState>
    /** Applies a pure change from core.model.VaultEdits, writes a new generation and publishes it only
     *  when the write is durable (4.9). Runs to the end even if the caller is cancelled. */
    suspend fun <T> edit(change: (Vault, nowMs: Long) -> Edited<T>): SaveResult<T>      // Saved(value) | NotSaved(SaveProblem)
    fun holdDraft(draft: Draft?)                                                        // the open form's unsaved state, in memory only
    suspend fun flushDraft()                                                            // writes a held draft that differs from the stored one; best effort (5.5)
    suspend fun sealBackup(proof: MasterProof): ByteArray                               // a new container with its own key, from the checked bytes (4.12)
    suspend fun openBackup(bytes: ByteArray, passwordBytes: ByteArray): BackupResult
                                                       // Opened(vault, createdMs) | Wrong | WrongOrDamaged | Damaged | Newer | NotOurs | NoMemory (4.4)
    suspend fun matchesMasterPassword(proof: MasterProof): Set<String>                 // entry ids; after a correct MP entry only (D-9b)
}
enum class SaveProblem { NOT_DURABLE, FAILED, READ_ONLY, TOO_LARGE }
class Edited<T>(val vault: Vault, val value: T)        // `vault` is the input itself when there is nothing to save
class ListModel(val favourites: List<Entry>, val sections: List<Section>, val index: SearchIndex)    // in display order (search.md §7)
interface ListModelSource { val model: StateFlow<ListModel?> }                          // null while locked; rebuilt when the vault or the language changes
interface SecurityReports {                                                             // SecurityCheckRunner; empty until package 7.1
    val report: StateFlow<SecurityReport?>
    val unseen: StateFlow<Boolean>                                                      // the Security tab's dot
}

sealed interface LockUiState {
    object Starting : LockUiState                      // records and files are being read (lock.md §5.11)
    class NoVault(val aside: List<AsideVault>) : LockUiState                 // Welcome, with "Open my previous passwords" for each
    class Unreadable(val why: CurrentVault.Why, val aside: List<AsideVault>) : LockUiState    // "Your passwords can't be opened"; never a delete offer
    class Locked(val screen: LockScreen) : LockUiState                       // chosen by EntryPolicy (lock.md §13.1)
    class Unlocked(val notices: List<Notice>, val proof: MasterProof?) : LockUiState
}
sealed interface LockScreen {                          // lock.md §13.2; declared in core.lock.flow (LockScreen.kt), chosen by EntryPolicy
    val aside: AsideLine?                              // the screen of a vault put aside: "Passwords locked on 6 Oct 2026",
                                                       // or "Your passwords from before 7 Oct 2026" for one marked replaced
    class Pad(val digits: Int, val status: PadStatus?, val fingerprintKey: Boolean, val opening: Boolean, override val aside: AsideLine?) : LockScreen
    class FingerprintOnly(val status: PadStatus?, val pinReason: PinReason?, override val aside: AsideLine?) : LockScreen
    class MasterPassword(val reason: MpReason?, val canUsePin: Boolean, val practice: Practice?, val waitUntilElapsedMs: Long?,
                         val error: MpError?, val pendingReadyInMs: Long?, val opening: Boolean, override val aside: AsideLine?,
                         val fingerprintLine: FingerprintLine? = null) : LockScreen
                                                       // fingerprintLine: fingerprint unlock is on and cannot be used now; its line
                                                       // under the reason (lock.md §13.3, ux.md §5.10)
    class OlderPassword(val changedAtMs: Long, val unfinishedChange: Boolean, override val aside: AsideLine? = null) : LockScreen
                                                       // F16b, the change that did or did not finish
}
// The parts of LockScreen and Notice, declared in core.lock.flow (LockScreen.kt, Notice.kt). They hold facts and numbers, never a
// sentence: the screens pick the texts of lock.md §13.3 and §13.4 from them, so that the language can change under them.
// AsideLine: LockedOn(atMs) | FromBefore(atMs)
// PadStatus: TriesLeft(left) | WrongPin(left) | LastTry | LastTryWhilePending | SameAgain | Interrupted(left) | StorageFull | FingerprintLine
// FingerprintLine, a PadStatus: TurnedOff | Locked | DidNotOpen | NotForApps | NoneSaved | UpdateNeeded | CannotCheck
//                                (also FingerprintOnly's status and MasterPassword's fingerprintLine)
// enum class PinReason { LIMIT, CANNOT_CHECK, STOPPED_WORKING, STORAGE_FULL, NEWER_APP }
// MpReason, highest first: PracticeDue | WelcomeBack | FromBefore(atMs) | Pin(reason: PinReason) | MasterChanged | OlderCopy(createdMs)
//                          | NoSecureHardware
// class Practice(val notNow: Shortcut?)              // "Not now" uses this shortcut; null past the grace. Shortcut { PIN, FINGERPRINT }
//                                                    // is core.lock.record's (StateRecord.kt)
// MpError: Wrong(offerForgot: Boolean, replaced: ReplacedLine?) | NoMemory
// class ReplacedLine(val atMs: Long, val by: Shortcut, val oldVaultId: String?)   // oldVaultId null: replaced more than once; the
//                                                                                  // button opens the "Forgot master password?" sheet
// Notice, each with val id: Long, in the order of lock.md §13.4: OlderCopy(openedCreatedMs) | OlderThanLastTime(lastOpenedAtMs)
//   | Pending(requestedAtMs, by, remainingMs) | PendingReady(requestedAtMs, by) | PendingCancelled | Replaced(atMs, by, oldVaultId)
//   | WrongTries(wrongPins, lastWrongPinAtMs, interrupted, pinsBeforeOff, offerNewMasterPassword, wrongMasterPasswords)
//   | ChoosePin(reason: ChoosePinReason, needsMasterPassword) | FingerprintAgain | StorageUnreadable | BackupNow
// enum class ChoosePinReason { TURNED_OFF, FORGOTTEN, STOPPED_WORKING, MASTER_CHANGED, NONE_HERE, SOMEONE_MAY_KNOW }
// enum class NoticeAnswer { OK, LATER, CANCEL_IT, FINISH_NOW, IT_WASNT_ME, CHANGE_PIN, TYPE_MASTER_PASSWORD, NEW_MASTER_PASSWORD,
//                           CHOOSE_PIN, TURN_ON_FINGERPRINT, SET_UP_AGAIN, BACK_UP_NOW }   // each Notice's KDoc lists the ones it offers
class LockStatus(                                      // Settings › App lock, the list's banners, Welcome and the sheet
    val pin: PinStatus,                                // On | NotSet | Off(reason) | CannotCheck | NoSecureHardware
    val fingerprint: FingerprintStatus,                // On | Off | TurnOnAgain | Unavailable(canAuthenticate) | NoSecureHardware
    val pending: PendingMasterPassword?,               // requested at, by, credited and remaining time, ready (lock.md §13.4 item 2)
    val replaced: ReplacedNotice?,                     // lock.md §13.4 item 3
    val practiceOverdueSinceMs: Long?,                 // the standing banner of lock.md §13.4 item 6
    val aside: List<AsideVault>)
interface LockManager {
    val state: StateFlow<LockUiState>
    val status: StateFlow<LockStatus>
    fun send(intent: LockIntent)                       // main thread, never blocks. LockIntent.kt lists every intent:
                                                       // Digit(d), DeleteDigit, ClearDigits, UsePin, UseMasterPassword, UseFingerprint, NotNow,
                                                       // CantRemember, OpenForgotSheet, CloseSheet, OpenAside(vaultId), StartEmpty, RestoreFromFile,
                                                       // OlderPasswordAnswer(choice), NoticeAnswered(id, answer), CancelPending, FinishPending,
                                                       // ItWasntMe, GoBack
    suspend fun lock(reason: LockReason)               // called only by AutoLock, after the draft flush of 5.5; never writes vault data
    suspend fun submitMasterPassword(chars: CharArray, purpose: MpPurpose): MpResult        // the lock screen and every prompt; the guard of lock.md §6.8
                                                       // MpResult: Right(proof) | Wrong | Wait(untilElapsedMs) | NoMemory | OlderPassword(…) | TooLate
    // Flows started from setup and Settings. Each suspends until the flow ends; prompts and pads appear through `state`.
    suspend fun createVault(first: CharArray, again: CharArray, restoreFrom: Vault?): CreateResult   // F1, F13, F17 step 3; Mismatch before anything is derived
    fun pinProblem(pin: ByteArray): PinBlocklist.Reason?
    suspend fun setPin(pin: ByteArray, proof: MasterProof): Boolean                      // F6
    suspend fun changePin(current: ByteArray, new: ByteArray): PinChange                 // a counted check of `current` in a prompt
    suspend fun fingerprintOn(proof: MasterProof): FingerprintResult                     // F9
    suspend fun fingerprintOff()
    suspend fun changeMasterPassword(proof: MasterProof, first: CharArray, again: CharArray, pin: ByteArray?): ChangeResult   // F8; Mismatch before anything is derived
    suspend fun requestNewMasterPassword(first: CharArray, again: CharArray): RequestResult   // F11 steps 3-5
    fun cancelNewMasterPassword()
    suspend fun openAside(vaultId: String, chars: CharArray): AsideResult                // Settings › Old passwords › Open (F17 step 5)
    suspend fun deleteAside(vaultId: String, chars: CharArray): AsideResult              // F17 step 5: files, then its lock directory and keys
}
interface Session {
    fun beginExternal()                                // just before CreateDocument, OpenDocument or the share sheet (lock.md §10.3)
    fun endExternal()
    fun touched()                                      // every touch and key event the activity dispatches
    fun lockNow()                                      // Settings › "Lock now": clears our clip, flushes the draft, locks (5.5)
}
interface SecretClipboard {
    val status: StateFlow<ClipStatus?>                 // the status row of ux.md §6; null when nothing of ours is pending
    fun copy(value: CharSequence, what: UiText, sensitive: Boolean)          // main thread
    fun clearNow()                                     // "Clear now" and "Lock now"; never called by an automatic lock
    fun appCameToFront()                               // cancel the pending clear if the clipboard now holds something else; clear an overdue clip of ours
}
class DocRef(val uri: String)                          // a document the user chose; converted to android.net.Uri only inside SafDocumentIo
interface DocumentIo {
    suspend fun write(doc: DocRef, bytes: ByteArray): Boolean                // "wt", then reopen, hash and compare [V-S6]
    suspend fun read(doc: DocRef, maxBytes: Int): ByteArray                  // at most maxBytes + 1 bytes
    fun keep(doc: DocRef)                              // persistable permission
    fun delete(doc: DocRef): Boolean                   // false: the place does not allow it
    fun place(doc: DocRef): Place                      // PhoneStorage | GoogleDrive | Other(name)
}
interface AppSettings {                                // SharedPreferences "pv_settings"; a missing value is the strictest one [V-P9]
    val lockDelay: StateFlow<LockDelay>                // missing or unreadable: Immediately. Setup writes 30 seconds
    val clipDelay: StateFlow<ClipDelay>                // missing or unreadable: 30 seconds. Setup writes 1 minute (ux.md §5.18)
    val theme: StateFlow<ThemeChoice>
    val generator: StateFlow<GeneratorOptions>
    val backupReminder: StateFlow<BackupReminder>      // on or off, snoozed until; when a backup is due is computed from the vault (4.3)
    val fileTask: StateFlow<FileTask?>                 // an unfinished backup, export or import: kind, step, DocRef; nothing secret
    val oneTime: StateFlow<Set<OneTime>>               // WORDS_SHOWN (ux.md §5.5), FIRST_COPY_TIP, BACKUP_EXPLAINED, NO_SECURE_HARDWARE_TOLD, …
    val purgeArm: StateFlow<PurgeArm?>                 // the two-step purge of Recently deleted (4.3)
    fun writeDefaults(): Boolean                       // called by createVault after the vault is proven (lock.md §10.1)
    fun update(change: (SettingsEditor) -> Unit): Boolean        // commit(); false = not written
}
```

- `SaveResult.NotSaved(TOO_LARGE)` is shown as "Your passwords no longer fit in one file. Delete accounts you don't need, or shorten very long notes." The import preview estimates the sealed size first and refuses an import that would not fit, with the same sentence.
- `BackupResult.WrongOrDamaged` is the case of 4.4 where the slot fails and the trailer is wrong: "This file may be damaged, or the password is wrong." Another try is allowed.

### 3.6 Composition seams (`:app`)

Every seam is a file that the shell package creates with a stub body and that exactly one later package fills. Nobody else edits it.

**`AppGraph`**, written once by the shell package:

```kotlin
class AppGraph(val app: Application) {
    val dispatchers: AppDispatchers = AppDispatchers.Default
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val build = BuildInfo("PasswordVault " + BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
    val platform: Platform by lazy { PlatformModule.create(this) }       // files, records, clock, random, argon2, the two roots
    val settings: AppSettings by lazy { SettingsModule.create(this) }
    val keys: Keys by lazy { KeystoreModule.create(this) }              // DeviceKeys, PinKey, FingerprintKey, BiometricLauncher
    val data: Data by lazy { DataModule.create(this) }                  // VaultRepository, VaultPort, ListModelSource
    val security: SecurityReports by lazy { SecurityModule.create(this) }
    val lock: LockManager by lazy { LockModule.create(this) }
    val session: Session by lazy { SessionModule.create(this) }
    val clipboard: SecretClipboard by lazy { ClipboardModule.create(this) }
    val files: DocumentIo by lazy { FilesModule.create(this) }
}
```

`Platform`, `Keys` and `Data` are plain holder classes declared next to `AppGraph`. Each `…Module.create(graph)` is one function in its area's directory, returning the fake of `APP/fake/` until its package replaces the body.

**`MainActivity`**, written once by the shell package, calls these and holds no other logic:

| When | Call | File and owner |
|---|---|---|
| `onCreate`, before `setContent` | `WindowPolicy.onCreate(this)`: `VaultSecurity` on the activity window (lock.md §13.5, with its API gates) | `window/WindowPolicy.kt`, the window kit |
| `onCreate` | `BiometricHost.onCreate(this, graph)`: the one `BiometricPrompt`, built every time (lock.md §9.2 rule 1) | `lock/BiometricHost.kt`, the lock manager package |
| `onCreate` | `DocumentLaunchers.onCreate(this, graph)`: the picker launchers, registered by the activity so that a result reaches a new, locked process (ux.md §4 item 4) | `files/DocumentLaunchers.kt`, the file-picker package |
| `onCreate` | `setContent { AppRoot(graph) }` | `ui/shell/AppRoot.kt`, the shell |
| `dispatchTouchEvent`, `dispatchKeyEvent` | `graph.session.touched()` | the interface; `AutoLock` |
| `onResume` | `graph.clipboard.appCameToFront()` | the interface |

`AppRoot` is `WindowPolicy.Root { ClipboardRoot(graph) { PasswordVaultTheme(graph.settings) { AppNavHost(graph) } } }`. `WindowPolicy.Root` (window kit) applies `NoPersonalizedLearning` and `ObscuredTouchNotice` around everything; `ClipboardRoot` (clipboard package) provides `SecretClipboardBridge` as `LocalClipboard`. `UnlockedScaffold` (shell) draws the bottom bar and calls `ClipboardRowSlot(graph)` (clipboard package), `FirstCopyTip(graph)` (`ui/shell/FirstCopyTip.kt`, help package) and `SecurityBadge(graph)` (security tab package), all stubs until then.

**Routes.** `nav/Route.kt` (shell) declares every route key of the app. `AppNavHost` (shell) builds its entries from one registration function per package, each in its own file; the shell creates every file with placeholder screens:

| File | Routes | Filled by |
|---|---|---|
| `ui/setup/SetupNav.kt` | Welcome, CreateMasterPassword, WriteDown, TypeAgain | setup screens |
| `ui/setup/PinSetupNav.kt` | CreatePin, ConfirmPin, FingerprintOffer, BackupsExplained | PIN and fingerprint setup |
| `ui/lock/LockNav.kt` | Locked (one route; `LockUiState` decides what it shows) | master-password lock screen, then the lock screens package |
| `ui/list/ListNav.kt` | Passwords, RecentlyDeleted | list |
| `ui/entry/EntryNav.kt` | Entry(id), BigLetters(id, field) | entry detail |
| `ui/edit/EditNav.kt` | Edit(id?) | add and edit |
| `ui/settings/SettingsNav.kt` | Settings, the App lock pages, ChangePin, Theme | PIN and fingerprint setup |
| `ui/settings/MasterPasswordNav.kt` | ChangeMasterPassword, NewMasterPassword | master-password change |
| `ui/settings/LanguageNav.kt` | Language | language switch |
| `ui/settings/HelpNav.kt` | Help(page), Licences | help |
| `ui/transfer/BackupNav.kt` | Backup, Restore, OldPasswords | backup and restore |
| `ui/transfer/ExportNav.kt` | Export(step) | export |
| `ui/transfer/ImportNav.kt` | Import(step), ColumnMapping | import |
| `ui/security/SecurityNav.kt` | Security, Group(id) | security tab |
| `ui/security/ChangeNav.kt` | GuidedChange(entryId) | guided change |

A screen that needs a new route asks for it as an interface request.

**Debug commands.** `DebugCommandReceiver` (source sets `debug` and `staging`) looks up the command in `debug/DebugCommands.kt`, a list kept by the integrator. Each package that needs a command writes its handler in its own file under `debug/<area>/` and asks for the one line in the list.

### 3.7 Shapes declared with the contracts

Declared by the contracts package (build plan 1.1) so that earlier waves can name them; their behaviour comes later.

| Type | Package | Filled by |
|---|---|---|
| `Entry`, `CustomField`, `HistoryItem`, `Vault`, `Draft`, `FileRef` | `core.model` | 4.3, one field per key; the unknown keys of an entry and of the top level in `extras: Map<String, String>`, each value as compact JSON text (4.3) |
| `GeneratorOptions` (kind, length, character sets, words, separator) | `core.generator` | the generator package |
| `SecurityReport` (groups of reused passwords, weak, pending, left out, same as the master password) | `core.strength` | the strength package |
| `PinRecord`, `FingerprintRecord`, `StateRecord`, `RecordRead` | `core.lock.record` | lock.md §5.3 to §5.6 as revised by its audit: no `trouble_boots`, no `udr`; `unseen` with `count`, `last_at`, `interrupted`; `mp_guard.unseen`; `mp_change`; `pending_mp` and `replaced` with `credited_ms` and `seen_elapsed_ms`. `RecordRead.Damaged` carries the `ParseError` of the rule that failed (5.6) |
| `EditTexts` (`untitled(nowMs)`, `fromBackup(title)`) | `core.model` | `:app` passes the resource texts in, so that stored titles are in the user's language |

---

## 4. The vault file, the reader rules and generation storage (normative)

This section reconciles crypto.md §1, storage.md §5, lock.md §4–§5 and [V-C3], [V-C4], [V-C8], [V-S1] to [V-S6]. The words *must*, *never* and *only* are rules. Test vectors are in 4.13. **Every number this section writes, in a file or a file name, uses the ASCII digits 0–9**, produced by `Long.toString` or a format with `Locale.ROOT`, never by the default locale (R-11).

### 4.1 Container

One layout serves a vault generation on the phone and a backup file outside it.

| Offset | Size | Field |
|---|---|---|
| 0 | 8 | Magic: the ASCII bytes `PVAULT01` |
| 8 | 4 | `header_len`: unsigned 32-bit, big-endian, 2 to 4,096 |
| 12 | `header_len` | Header: ASCII JSON in the canonical form of 4.2 |
| 12 + `header_len` | 12 | `body_nonce` |
| 24 + `header_len` | `body_len` | `body_ct` = AES-256-GCM(VK, `body_nonce`, plaintext of 4.3, AAD), with the 16-byte tag at its end |
| 24 + `header_len` + `body_len` | 32 | Trailer = SHA-256 of every byte before it |

- **AAD** = the first 12 + `header_len` bytes of the file exactly as stored: magic ‖ `header_len` ‖ header. The body nonce is **not** part of it [V-C3].
- **File length** = 56 + `header_len` + `body_len`, at most **16,777,216 bytes** [V-C4]. Nothing follows the trailer.
- **What every later version keeps**, so that version 1 can tell "newer" from "damaged" (R1, R5, R6): a magic of the form `PVAULT` followed by two ASCII digits; the 4-byte big-endian length field after it; a header that starts with `{"format":"passwordvault","version":N,`; and the SHA-256 trailer over everything before it. A change of the framing takes new digits in the magic; a change inside the header or the body takes a new `version`.
- **The trailer detects damage; it authenticates nothing.** It lets a reader say "this file is damaged" before a password is typed, which matters most for a backup file that arrives over Telegram or Drive. A body that opens under VK is good even when the trailer is wrong [V-S3].

### 4.2 Header

The header is **byte-identical** to this text, with nothing added, removed or reordered and no white space, where each `<…>` is replaced as the table says:

```
{"format":"passwordvault","version":1,"cipher":"AES-256-GCM","vault_id":"<vault_id>","vk_id":"<vk_id>","generation":<generation>,"created":<created>,"generator":"<generator>","writer_version":<writer_version>,"body_len":<body_len>,"slots":[{"type":"password","kdf":"argon2id","argon2_version":19,"m_kib":65536,"t":3,"p":4,"salt":"<salt>","nonce":"<nonce>","wrapped_key":"<wrapped_key>"}]}
```

| Value | Form | Meaning |
|---|---|---|
| `vault_id`, `vk_id` | `[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}` | lock.md §5.1. `vault_id` is made when a vault is created, restored or made by a replacement (lock.md F11) and never changes; `vk_id` is made together with every new VK |
| `generation` | `[1-9][0-9]{0,9}` | 1 to 9,999,999,999. In a vault generation it equals the number in the file name [V-C8]. In a backup it is the generation the backup was made from |
| `created` | `0` or `[1-9][0-9]{0,15}` | Wall-clock milliseconds since 1970 UTC when the file was sealed. For display only |
| `generator` | `[A-Za-z0-9 ._()-]{1,64}` | For people: `PasswordVault ` and the build's `versionName`. A build check fails a version name that would break the pattern (R-14) |
| `writer_version` | `[1-9][0-9]{0,9}`, at most 2,147,483,647 | The app's `versionCode`. Retention pins by it (4.10) |
| `body_len` | decimal, no leading zero | Must equal file length − 56 − `header_len`; at least 4,112; (`body_len` − 16) must be a multiple of 4,096 |
| `salt`, `nonce`, `wrapped_key` | Base64, standard alphabet, with padding: exactly 24, 16 and 64 characters, and decoding then encoding must give the same text | 16, 12 and 48 bytes |

- **Exactly one slot, of type `password`.** No biometric or PIN slot exists in this format (lock.md §5.1) [V-C4].
- **The KDF constants are fixed text**, so a file with any other value is not a version 1 file [V-C4, V-C14].
- A reader therefore needs no JSON parser for the header: one anchored pattern with ten captures, then the numeric and Base64 checks. Duplicate keys, unknown keys, other spellings of a number and white space are all refused by the same comparison.
- The stored header bytes are the AAD. A reader never re-serialises them.

### 4.3 Body

**Plaintext** = one JSON object in strict UTF-8 (no byte-order mark, no malformed sequence), followed by as many space bytes (0x20) as make the length a multiple of 4,096: from 0 to 4,095, none when the JSON is already a multiple. The padding hides small changes of size; a reader strips trailing spaces before parsing.

Key names are those of ux.md §2 (camel case; the header keeps the snake case of crypto.md and lock.md). **All times are JSON integers in plain decimal: milliseconds since 1970 UTC.** An integer matches `-?(0|[1-9][0-9]{0,18})` and fits a signed 64-bit value; a number with a fraction or an exponent where an integer belongs is the wrong type. No field is trimmed or normalised when it is written.

| Top-level key | Type | Default | Meaning |
|---|---|---|---|
| `schema` | integer | required | Must be 1 |
| `entries` | array of entry objects | required | |
| `draft` | null or `{"entryId": string or null, "savedAt": integer, "entry": {title, username, password, notes, urls, fields, favourite}}` | null | The one unsaved form (ux.md §5.14) |
| `plainExports` | array of `{"uri", "name", "savedAt"}` | `[]` | Unprotected exports not yet deleted (ux.md §5.20) |
| `backups` | array of `{"uri", "name", "savedAt"}` | `[]` | Backups this app saved (ux.md §5.18). A backup is due when some entry's `updatedAt` or `deletedAt` is later than the newest `savedAt` here; no counter is kept |
| `masterPasswordMatches` | array of entry ids | `[]` | Entries whose password equals the master password (ux.md §5.16) |
| `securitySeenAt` | integer or null | null | When the Security tab was last opened; the dot shows when a flagged entry has a later `updatedAt`. Written only when it would clear a dot, so opening the tab does not make a generation each time |

| Entry key | Type | Default when missing |
|---|---|---|
| `id` | string | required |
| `title` | string | required |
| `username`, `password`, `notes` | string | `""` |
| `urls` | array of strings | `[]` |
| `fields` | array of `{"name": string, "value": string, "secret": boolean}` | `[]` |
| `favourite`, `changePending`, `checkIgnored` | boolean | false |
| `createdAt`, `updatedAt` | integer | required |
| `passwordChangedAt`, `deletedAt` | integer or null | null |
| `pendingOldPassword` | string or null | null |
| `passwordHistory` | array of `{"password": string, "replacedAt": integer}`, newest first | `[]` |

**The writer's canonical form.** A version 1 writer produces exactly one byte sequence for a given `Vault`, which vector B of 4.13 pins:
- every key of both tables for every object, in the order of the tables (the `draft`'s `entry` in the order of the entry table), followed by the kept unknown keys of that object in the order they were read;
- no white space outside strings;
- in strings, `"` and `\` escaped with a backslash, U+0008, U+0009, U+000A, U+000C and U+000D as `\b`, `\t`, `\n`, `\f`, `\r`, every other code point below U+0020 as `\u00xx` with lower-case hex digits, and everything else as itself in UTF-8. This is what kotlinx-serialization 1.11.0's `JsonObject.toString()` writes, byte for byte the same as Python's `json.dumps(…, ensure_ascii=False, separators=(",", ":"))` [X];
- integers in plain decimal; `true`, `false`, `null`;
- a kept unknown value written as the compact form of what was read: the same tokens and the same spelling of each number, no white space.

**Unknown keys.** At the top level and in each entry they are **kept** and written back after the known keys, as they are. `Vault.extras` and `Entry.extras` are a `Map<String, String>`: each kept key with its value as compact JSON text (the same tokens and the same spelling of each number, no white space), in the order they were read. Text, so that the public model names no kotlinx-serialization type. Anywhere else (in `draft`, a field, a history item, an export or backup reference) they are ignored and not written back. So a later version that only adds data must add it at one of the two levels. A change that an old app must not ignore raises the header's `version`. Version 1 writers always write `schema` 1; a reader treats a higher `schema` as NEWER (R9).

**What the reader checks.** A known key with the wrong JSON type, or a required key that is missing, makes the body unreadable (R10); that is all R10 checks. The required keys are those the two tables mark required (`schema` and `entries`; an entry's `id`, `title`, `createdAt` and `updatedAt`) and, below the two levels, every key named for an object: the draft's `entryId`, `savedAt` and `entry`; a field's `name`, `value` and `secret`; a history item's `password` and `replacedAt`; an export's or backup's `uri`, `name` and `savedAt`. The draft's `entry` follows the entry table: `title` required, the other six keys defaulted as there. The `id` format, unique ids, the limits of ux.md §2 and the absence of unpaired surrogates (`TextHygiene`, formats-review P7) are rules for input, import and the write-time check of 4.9 step 4. A reader never enforces them, so no stored entry is ever refused.

**Deleted entries** keep `deletedAt` and stay in the file, in backups and in Recently deleted until the purge of D-24 removes them.

### 4.4 Reader rules

**Without a key** (`VaultCodec.check`). The first rule that fails decides.

| # | Rule | If not |
|---|---|---|
| R1 | At least 8 bytes, and the first 8 are `PVAULT01`. If they are `PVAULT` and two ASCII digits other than `01`: NEWER when the file is at least 40 bytes and its last 32 bytes are the SHA-256 of all bytes before them, otherwise DAMAGED | NOT OURS |
| R2 | Length is 74 to 16,777,216 bytes (a reader never reads more than the cap plus one byte) | DAMAGED |
| R3 | `header_len` is 2 to 4,096 and 56 + `header_len` + 16 ≤ length | DAMAGED |
| R4 | Compute `trailerOk`: SHA-256 of all bytes but the last 32 equals the last 32 | (remember it) |
| R5 | The header starts with `{"format":"passwordvault","version":N,` where N is `[1-9][0-9]{0,8}` | DAMAGED |
| R6 | N is 1. If N > 1 and `trailerOk`: NEWER. If N > 1 and not `trailerOk`: DAMAGED | NEWER or DAMAGED |
| R7 | The header matches 4.2 in full, including every bound and `body_len` against the file length | DAMAGED |

Result: OK with the header values and `trailerOk`. Until the body has opened, the header is not authenticated: `vault_id` and `vk_id` may be used to choose a lock screen and a record directory, nothing more (lock.md §5.1). Inside a vault directory NOT OURS counts as DAMAGED (4.8): a file of zero length or full of zeros is the usual shape of a lost write.

**With a key.**

| # | Rule | If not |
|---|---|---|
| R8 | AES-256-GCM-Open(VK, `body_nonce`, `body_ct`, AAD) verifies | This file does not open under this key |
| R9 | The plaintext is strict UTF-8 without a byte-order mark. After stripping trailing spaces it is one JSON object, read with `StrictJson`: no key repeated inside any object, nesting at most 16 deep (objects and arrays both count; the top object is depth 1), and `schema` an integer equal to 1 | `schema` > 1: NEWER. Otherwise DAMAGED |
| R10 | Every known key has the type of 4.3 and the required keys are present | DAMAGED |

**With a password.** KEK = Argon2id over `MasterPasswordBytes` (4.6) with the slot's salt; VK = AES-256-GCM-Open(KEK, slot nonce, `wrapped_key`, no AAD). If the tag fails, *this slot* does not open with *this password*. What the user is told:

- **A vault on the phone:** "wrong master password" only when no distinct slot among the kept generations opened [V-S3]. A destructive choice is never offered in reply (ux.md §5.10).
- **One backup file:** slot fails and `trailerOk` → "wrong password". Slot fails and not `trailerOk` → "This file may be damaged, or the password is wrong.", and another try is allowed: damage confined to the trailer cannot be told from a wrong password [V-S3]. Slot opens and R8 to R10 fail → "damaged".
- **NEWER**, anywhere: "made by a newer version of the app". The file is never opened and never written over. While a NEWER generation is in the live directory the whole vault is read-only and stays locked (storage.md §5.1 rule 3).
- `KdfUnavailable` is "not enough memory": never "wrong", never counted (lock.md §6.8).

### 4.5 Writer rules

1. Only version 1, only the constants of 4.2, only the canonical header, only the canonical body of 4.3.
2. Every seal uses a fresh 12-byte nonce from `RandomSource`: one for the body, one for a new slot.
3. `created` is `Clock.wallMs()` at sealing; `generator` and `writer_version` come from `BuildInfo`.
4. On an ordinary save the slot is copied **byte for byte** from the generation that was opened: VK and the password have not changed.
5. A file longer than the cap is never written (`TooLarge`).

### 4.6 Keys and password bytes

| Item | Value |
|---|---|
| VK | 32 bytes from `RandomSource`. A new VK and `vk_id` at vault creation, at restore, at a replacement (lock.md F11), at every master-password change [V-C5, V-S5], and for every backup file |
| KEK | Argon2id v1.3, m = 65,536 KiB, t = 3, p = 4, 16-byte random salt, 32-byte output, over the password bytes below |
| Slot | `wrapped_key` = AES-256-GCM(KEK, 12-byte random nonce, VK, no AAD): 32 bytes of ciphertext and the tag |
| Body | 4.1 |

**`MasterPasswordBytes.of(chars)`** is the only way a master password becomes bytes: for the vault, every backup and the pending password of lock.md F11. It is frozen with the vectors of 4.13 and lock.md §4.3 before the first release. It is the function of lock.md §8.1 as revised by its audit, step for step:

1. **NFC** (`java.text.Normalizer`, `Form.NFC`).
2. **White space.** Each of these 25 code points becomes U+0020: U+0009–U+000D, U+0020, U+0085, U+00A0, U+1680, U+2000–U+200A, U+2028, U+2029, U+202F, U+205F, U+3000. Then runs of U+0020 become one, and leading and trailing ones are removed (decision D-6b).
3. **Apostrophes.** The 17 code points of lock.md §8.1 become U+0027.
4. **UTF-8.** Case is kept. An unpaired surrogate, which is not a Unicode character, is written as the UTF-8 of U+FFFD (EF BF BD).

Why this order: NFC turns U+0374, U+1FEF and U+1FFD into members of the apostrophe list, so folding before NFC left three equivalent spellings with different keys. With NFC first, every canonically equivalent spelling gives the same bytes and applying the function twice changes nothing. I checked every code point with Python's `unicodedata` 15.0, alone and between letters: this order gives the same bytes for every code point as the coverage review's alternative (white space, apostrophes, NFC, apostrophes again), and none of the existing vectors changes [X].

- **Typed twice.** At creation, restore and change, both typings go through `MasterPasswordBytes.same` before anything is derived or written; a mismatch stops there.
- **At creation only:** a password with a code point whose `Character.getType` is `UNASSIGNED`, or with an unpaired surrogate, is refused [V-C9], and the checklist of ux.md §5.3 (length, strength) is applied to the characters as they are after steps 1 to 3, so that spaces cannot pad a password. A password of white space only gives zero bytes and fails the length rule.

### 4.7 Directories and names

```
<filesDir>/vaults/live/                               the vault
                  aside-<13 digits>-<vault_id>/       a vault that was put aside; the digits are the wall-clock ms of that moment (display only)
                  new-<vault_id>/                     a vault being created, restored or made by a replacement; removed at process start
<noBackupFilesDir>/lock/<vault_id>/                   pin.rec, fingerprint.rec, state.rec (lock.md §5.2)
```

Inside a vault directory a file name is `vault-` + 10 ASCII digits + a suffix:

| Suffix | Meaning | Who removes it |
|---|---|---|
| `.pvault` | A **live** generation | Housekeeping (4.10) and retirement (4.11) only |
| `.tmp` | Being written, read back or proven (4.9) | Deleted at process start |
| `.bad` | Quarantined: it was read in full and failed its check, its proof or the AEAD | Nobody, ever [V-S4] |
| `.aside` | Put aside when an unfinished master-password change was given up (lock.md F16b) | Nobody, ever |

Any other name is ignored and never touched. "No vault" means `live/` does not exist or holds no file at all; a directory that cannot be listed is a storage error, never "no vault" (lock.md F1). A `new-*` directory is deleted at process start because nothing in it was published; lock.md F11 and F17 count on that (a kill before the publish of 4.9 step 9 leaves the vault as it was).

### 4.8 Opening

**The state of one generation file** (`FileState`). In a vault directory every `.pvault` file is in exactly one of four states:

| State | When |
|---|---|
| OK | `check` gave OK, the header's `generation` equals the number in the file name, and its `vault_id` equals that of the newest OK file |
| DAMAGED | It was read in full and failed R1 to R7 (NOT OURS and over the cap included), or a number or `vault_id` does not match. Later: its body failed R8 under the key of its `vk_id`, or R9 or R10 said DAMAGED |
| NEWER | R1 or R6 said NEWER, or its body opened and R9 said NEWER |
| UNREADABLE | Reading it threw. It may read fine next time |

Only DAMAGED files are ever renamed to `.bad`. An UNREADABLE file is never renamed, never counted as verified, and housekeeping deletes nothing below it (4.10).

`scan()` (no key; safe before unlock):
1. At process start, first delete `*.tmp` in `live/` and every `new-*/` directory.
2. List `live/`. If listing throws: `Unreadable(IO)`. If `live/` does not exist or holds no file: `NoVault`.
3. Read the live generations from the highest number down, each with `read(path, cap)` and `check`, until the newest OK file with `trailerOk` is found, or all are read. Its bytes stay in memory, still encrypted. If no OK file has a good trailer, the newest OK one is used (the AEAD decides later).
4. Decide:
   - a NEWER file anywhere among them → `Unreadable(NEWER)`;
   - an UNREADABLE file above the newest OK one → `Unreadable(IO)`. **The app never falls back past a file it could not read.** A passing read error would otherwise open an older copy, and the first save would bury the newer one. lock.md §13.1 row 2 then shows "Your passwords can't be opened" with "Try again"; a file that stays unreadable is the case for a restore from a backup, and the older generations stay on disk;
   - no OK file → `Unreadable(DAMAGED)`;
   - otherwise `Present(VaultFacts)`: `vault_id`, the newest readable generation (number, `vk_id`, `created`), `keptVkIds` (the `vk_id`s among the live generations; files in quarantine or put aside do not count, lock.md §5.4; null when any live file is UNREADABLE, because its `vk_id` is unknown, and then the lock trims no wrap at that unlock), and `olderCopyFirst` when a DAMAGED file lies above the newest readable one.
5. Each `aside-*` directory is listed and read the same way, but only for its newest readable generation's facts; a failure gives an `AsideVault` with `newest = null`, never an error for the current vault.

`open(target, keys)` (PIN, fingerprint, the pending wrap) walks the live generations that are OK, from the highest number down:
- its `vk_id` has a key and R8 to R10 pass → `Opened`. `newestOnDisk` > `generation` tells the caller that an older copy opened (the notice of ux.md §5.9);
- its `vk_id` has a key and the body does not open (R8 fails, or R9 or R10 says DAMAGED) → it becomes DAMAGED; go on to the next one down;
- its body opens and R9 says NEWER → stop: `Newer`, as in step 4 of `scan()`;
- reading a file now throws → stop: `Unreadable` (never past it);
- its `vk_id` has **no** key → stop with `NeedsOtherKey` (lock.md F16a). So a shortcut never opens an older copy past a newer one that it has no key for (lock.md F8);
- none left → `Damaged`.

Opening with the master password (`VaultPort.openWithPassword`):
1. `slots()`: the distinct slots (by their three byte strings) among OK live generations, newest first. In practice one, two for a while after a change.
2. For each slot in turn: one `MasterKdf.kek`, then `openWithSlot`. `SlotClosed` → next slot.
3. The slot opened and gave VK for its `vk_id`: open the newest generation with that `vk_id`, walking down on failure as above.
   - If a generation with **another** `vk_id` and a **higher** number is OK with `trailerOk`: `OlderPassword(changedAtMs)` (lock.md F16b), where `changedAtMs` is the `created` of the lowest-numbered generation with that newer `vk_id`. Nothing is opened.
   - Otherwise `Opened`.
4. No slot opened: wrong password.

The same three procedures run on a vault put aside (`VaultRef.Aside`), inside its own directory.

**Opening never writes, renames or deletes** (lock.md I1). After an open with `olderCopyFirst`, or one whose `newestOnDisk` is above the generation opened, the repository makes no automatic write until the user has answered the older-copy notice (5.5).

### 4.9 Saving a generation

The caller holds the vault mutex. One sequence serves every write; the table after it says which steps each call runs.

1. **Preconditions.** A vault is open in this process (none for a new vault); no live generation is NEWER; no other save is running.
2. **Number.** `N` = 1 + the highest number among **all** names in the directory that start with `vault-` and ten digits, whatever their suffix [V-S1]. In a new vault's `new-<vault_id>/`, `N` = 1.
3. **Seal** in memory (4.5).
4. **Check in memory.** Run the bytes through `check` and `open` with VK, and compare the result with the vault that was to be saved, and the slot with the intended slot. Check the write-time rules of 4.3: every `id` is a valid UUID v4 and unique, no string holds an unpaired surrogate. A difference aborts the save; nothing was written.
5. **Write** `vault-N.tmp` with `writeNew` (it fails if the name exists).
6. **Read back** `vault-N.tmp` from disk and compare it byte for byte with what was sealed. If it differs: delete it; `Failed`.
7. **Proof with the password**, only when the file carries a new slot (creation, restore, a replacement, a master-password change). The slot was made from the first typing. `prove(staged, kek)` takes a KEK derived afresh from the **second typing** and `staged.slotSalt`, the salt as read back from disk; it unwraps the slot from the bytes read back and opens the body with the VK it got [V-C5, lock.md I3]. If that fails: rename the `.tmp` to `.bad`, `syncDir`, and report the texts of lock.md F8 step 6 (or F1 step 3). A restore and the end of lock.md F11 pass the same bytes twice: there the password was already proved by opening the backup or the pending wrap.
8. **Refuse to replace.** If `vault-N.pvault`, `.bad` or `.aside` exists: delete the `.tmp` and fail [V-S1].
9. **Rename** `.tmp` → `.pvault`, then **`syncDir`**.
   - A failure before the rename: delete the `.tmp`; `Failed`; nothing changed.
   - **A failed `syncDir`: `NotDurable`.** The save is not reported as saved and no housekeeping runs [V-S2]. The screen shows "Couldn't save. Your phone's storage may be full." with "Try again", which starts again at step 2.
   - **A new vault** (`stageVault`) runs steps 2 to 9 inside `new-<vault_id>/`. Then: if `putCurrentAside`, rename `live/` to `aside-<ms>-<old vault_id>/`; rename `new-<vault_id>/` to `live/` (which must not exist); `syncDir` of `vaults/`. So "no vault", or the old vault, becomes "a proven vault" in one step. A kill between the two renames leaves no live vault: Welcome lists the vault put aside, and the next start deletes the unpublished `new-*` directory (lock.md F11 and F17 crash tables).
10. **Publish** the new state to the screens, then run housekeeping when it is due (4.10).

| Call | Steps |
|---|---|
| `commit` (an ordinary save, the draft, an import) | 1 to 6, 8 to 10 |
| `stageVault` (F1, F13, F17 step 3, F11 step 16), `stageRekey` (F8 step 6) | 1 to 6; the result carries `slotSalt` as read back |
| `prove` | 7 |
| `publish` | 8 to 10 |
| `discard` | Deletes the `.tmp` or the `new-*` directory |

So a live generation never carries an unproven slot. For a master-password change a kill during the one-second proof leaves only a `.tmp`, which the next start deletes, and lock.md's `mp_change` mark, which the next unlock clears because no kept generation has the new `vk_id` (lock.md §5.6). Every kill point of this table is a storage test (8.1); lock.md T15 runs the same points through the fake `VaultPort`.

### 4.10 Housekeeping: which generations are kept

It runs after the first durable save of a process and after every 20th one, under the vault mutex, and only after a `syncDir` that succeeded [V-S2]. It uses no clock [V-S4]. It never runs while automatic writes are held (5.5).

- **`L`** is the generation that was opened from disk at the **first** unlock of this vault in this process. A vault that this process created, restored or made by a replacement has no `L`.
- **`W`** is the set of generations this process wrote, in any vault. A generation in `W` is **never** verified in this process, whatever later unlocks load.
- A generation is **verified** when this process opened it from disk (R1 to R10) and it is not in `W`. `L` is verified.

1. **Quarantine what did not open.** Every live generation with a number above `L`, not in `W`, that is DAMAGED is renamed to `.bad`. UNREADABLE files are left alone.
2. **Verify.** Walk the live generations below `L` that carry the current `vk_id`, highest first, opening each, until 5 are verified or none is left. One that is DAMAGED is renamed to `.bad`. One that cannot be read ends the walk.
3. **With fewer than 5 verified generations, delete nothing.**
4. **With 5:** delete the live generations of the current `vk_id` below the lowest of the five, but none below an UNREADABLE file; and delete all but the newest 20 of the generations in `W`. Always keep **the newest generation of each `writer_version` lower than the running app's** [V-S4].
5. Generations with another `vk_id` are not touched here (4.11).

What this gives: the five newest copies that provably open, as found at an earlier app start, are never deleted, however often the app is locked and unlocked in between; a file that failed is never deleted; a file that could not be read stops all pruning below it; a clock change does nothing; the last copy written by each earlier app version stays. The number of files is bounded except during the lifetime of the first processes of a new vault, before five verified generations exist; a save that fails for lack of space changes nothing.

### 4.11 Old keys, vaults put aside, and swapping

- **After a master-password change** (lock.md F8) every older generation still opens with the old password [V-S5]. They are kept until the **new master password, typed, has opened the vault from disk in a later process** [V-C5]. Then the lock engine calls `retireOldKeys` (lock.md F8 step 10): every live generation with another `vk_id` is deleted. It returns the `vk_id`s still kept, so the lock can drop wraps that open nothing (lock.md §5.4). A replacement of a forgotten master password does not use this: it makes a new vault (below).
- **"Go back" from an unfinished change** (lock.md F16b, `mp_change` set): `putNewerAside(g)` renames every live generation above `g` to `.aside`, highest first, then syncs. It can be repeated after an interruption.
- **Putting the vault aside** ("Start with an empty app", lock.md F17 step 2): one `rename` of `live/` to `aside-<ms>-<vault_id>/` and a `syncDir` of `vaults/`. The lock directory and the Keystore keys are not touched.
- **A replacement** (lock.md F11 step 16) and **a restore over an existing vault** (F17 step 3): `stageVault` with the `vault_id` the lock gives, `prove`, then `publish(putCurrentAside = true)` (4.9 step 9).
- **Making one current again** (lock.md F16b step 4, F17 step 4): it is opened in place first, by `open` or `openWithPassword` on `VaultRef.Aside(id)`, with its own lock records. Only then `swapIn(id)`: `live/` → `aside-<ms>-<current vault_id>/`, then `aside-…-<id>/` → `live/`, then sync. An interruption between the two renames leaves no live vault; Welcome then lists both.
- **Adding its accounts** (Settings › Old passwords › "Open", lock.md F17 step 5): the vault opened in place is merged into the current one with the rules of "Backup file into an existing vault" (ux.md §5.21): new ids are added, identical entries skipped, an entry with the same id and other content added with the title from `EditTexts.fromBackup`. One ordinary save.
- **Deleting a vault that was put aside** needs that vault's own master password, checked by the lock under that vault's guard (lock.md F17 step 5); then `deleteTree`; then the lock deletes its lock directory and keys.
- Nothing put aside is ever pruned.

### 4.12 Backup files and other exports

- **A backup** is sealed from the open vault in memory, which is the generation this session opened or last saved and read back [V-S6]. It gets its own VK, `vk_id`, salt and slot from the bytes held by the `MasterProof` of lock.md F12 (checked against the vault's own slot just before; `sealBackup` takes nothing else), the vault's `vault_id`, and the current generation number. It holds the whole `Vault`, Recently deleted included.
- **After writing through the file picker** the file is read back, compared by SHA-256 with what was written, and, for a backup, opened once more with the proof's bytes. Only then "Saved" [V-S6]. On a mismatch the document is deleted (ux.md §4).
- **Restore** (lock.md F13): `check`, then the password, then R8 to R10; nothing on the phone changes before that. The vault is then written as a **new** vault: new `vault_id`, VK, `vk_id`, salt and slot, generation 1 (`stageVault`, `prove`, `publish`). It keeps `entries`, `draft`, `masterPasswordMatches` and the unknown top-level keys. It resets `plainExports` and `backups` to `[]`, because their document permissions belong to the old installation and their banners would ask for deletions this phone cannot make, and `securitySeenAt` to null.
- **A backup file into an existing vault** (ux.md §5.21) takes its `entries` only, by the merge rules of 4.11.
- **Plain exports** are in formats.md. They leave out entries with `deletedAt` set.

### 4.13 Test vectors

Computed with Python `cryptography` 41.0.7 and opened again with the JDK 25 JCA [X]. The script is in the appendix. Both files are committed as `core/src/test/resources/vectors/v1-container.pvault` and `v1-body.pvault`; the codec must produce each byte for byte from its inputs and read each back. Reference copies (`vector-a.pvault`, `vector-b.pvault`), the script, vector B's body and the review's checking programs are in `docs/design/vectors/`, with a README. KEK is the project's pinned Argon2id value for "correct horse battery staple" with salt `00 01 … 0f` (`Argon2KnownAnswerTest`).

**4.13.1 Vector A: the container**, made from given plaintext bytes. It checks the framing, the header and the AAD; its body is not what a writer produces (the writer writes every key), but every reader must open it, since the missing keys have defaults.

```
KEK          853b272a44db1421c02962669a55eb0994f3cab385ed1c4c79253eee19bab49e
VK           a0 a1 … bf                    slot nonce  20 21 … 2b          body nonce  30 31 … 3b
plaintext    {"schema":1,"entries":[]} followed by 4,071 spaces (4,096 bytes)
header       {"format":"passwordvault","version":1,"cipher":"AES-256-GCM","vault_id":"00000000-0000-4000-8000-000000000001","vk_id":"00000000-0000-4000-8000-000000000002","generation":1,"created":1791244800000,"generator":"PasswordVault 1.0","writer_version":1,"body_len":4112,"slots":[{"type":"password","kdf":"argon2id","argon2_version":19,"m_kib":65536,"t":3,"p":4,"salt":"AAECAwQFBgcICQoLDA0ODw==","nonce":"ICEiIyQlJicoKSor","wrapped_key":"TaQ2ZiOElYlVT+MkOjewZKMRc2S0upmTtqfDIHI6gZLrwe2A+48VUWtWHGPeIxcr"}]}
header_len   499
wrapped_key  4da4366623849589554fe3243a37b064a3117364b4ba9993b6a7c320723a8192ebc1ed80fb8f15516b561c63de23172b
SHA-256(AAD) adfd3efa701b6e18938f770551cf86b990d3c651fe7016769fccf2f90fce15f0
body tag     b725d6eb1c28bcaf9cab305f5a362876
trailer      bb6953c8aec55e24dfb00e016dbd49268fbcab8ce5df20854416862ba0ce4e2a
file         4,667 bytes, SHA-256 3210ed2c15bddde2e2d3793aeff65deda2d745d1ac10ed6c6443be1711ee1d2d
```

- With the body nonce added to the AAD the tag fails [X]; a reader that opens the body that way is wrong.

**4.13.2 Vector B: the body as a version 1 writer produces it.** The same key, slot and vault as A; `generation` 7, `created` 1791331200000, body nonce `40 41 … 4b`. The body below (one line, 1,448 bytes of UTF-8, SHA-256 `610a407ad04359ae1fc8787f29f4ed943d46358eae7bdddc6dd7ad2490186cb7`) holds every key of 4.3, Uzbek in both scripts with U+02BB, a surrogate pair, the escapes of 4.3, an unknown key at both levels (one with a number spelled `1.50`), and two history items. It is followed by 2,648 spaces.

```
{"schema":1,"entries":[{"id":"00000000-0000-4000-8000-000000000011","title":"Gmail – Oʻzbekiston Ўзбекистон","username":"aziz@example.com","password":"p\"ss\\w/rd-🔑","notes":"Birinchi qator\nИккинчи қатор\ttab\u0001","urls":["https://mail.google.com","https://accounts.google.com/a?b=c"],"fields":[{"name":"PIN code","value":"0042","secret":true},{"name":"Телефон","value":"+998 90 123-45-67","secret":false}],"favourite":true,"changePending":true,"checkIgnored":false,"createdAt":1791244800000,"updatedAt":1791331200000,"passwordChangedAt":1791331200000,"deletedAt":null,"pendingOldPassword":"eski-parol","passwordHistory":[{"password":"old2","replacedAt":1791331200000},{"password":"old1","replacedAt":1791288000000}],"futureEntryKey":"kept"}],"draft":{"entryId":null,"savedAt":1791331260000,"entry":{"title":"Yangi","username":"","password":"gen-🔑","notes":"","urls":[],"fields":[],"favourite":false}},"plainExports":[{"uri":"content://com.android.providers.downloads.documents/document/17","name":"passwords-2026-10-07.csv","savedAt":1791331300000}],"backups":[{"uri":"content://com.android.externalstorage.documents/document/primary%3ADownload%2FPasswordVault-backup-2026-10-07.pvault","name":"PasswordVault-backup-2026-10-07.pvault","savedAt":1791331400000}],"masterPasswordMatches":["00000000-0000-4000-8000-000000000011"],"securitySeenAt":1791331500000,"futureTopKey":{"n":1.50,"list":[true,null,"x"]}}
```

```
header_len   499 (the header of A with "generation":7 and "created":1791331200000)
body tag     5e76cebd4bd31f0794d5da6ef151ef7b
trailer      a12f31da424719de8cb33cbbad1accaae5ec6e4553dd2b2db1bc7748643afd3d
file         4,667 bytes, SHA-256 1bb8d230c949b197859bbd9fec613bd65f149595b3f9db98b40dd6dc2e5312c7
```

**Master-password bytes** (with lock.md §4.3, which keeps its own vectors, the Greek line among them):
- **White space:** a leading space, `correct`, two spaces, `horse`, one no-break space (U+00A0), `battery`, one space, `staple`, a trailing space → the 28 bytes of `correct horse battery staple` (hex `636f727265637420686f727365206261747465727920737461706c65`), so it derives the KEK above.
- **Greek signs:** `o` U+0374 `g` U+1FEF `li` U+1FFD `m` → `6f2767276c69276d`, the same bytes as with U+0027 [X]. Folding before NFC would give `6fcab967606c69c2b46d`.
- **Only white space:** U+0020 U+00A0 U+0009 U+3000 U+0020 → zero bytes; creation refuses it by the length rule.

---

## 5. State and threading

### 5.1 Who owns what

| State | Owner | Lives |
|---|---|---|
| VK, the opened slot, the decrypted `Vault`; a vault put aside that was opened in place (one at a time) | `DefaultVaultRepository`, behind the **vault mutex** | From unlock to lock; in memory only |
| The held draft of the open form | `DefaultVaultRepository` (`holdDraft`) | Memory, until `flushDraft` writes it into the vault |
| `L` per vault and the set `W` of generations written (4.10) | `VaultStore` | The process |
| The sorted list, its sections, the `SearchIndex`, the security report | `ListModelProvider`, `SecurityCheckRunner`: derived from `VaultState` and, for the order and the letter headers, from the app language. The index never holds a password, a previous password or a secret field value (search.md §7) | Dropped with the vault; rebuilt when the vault or the language changes |
| PIN digits, the charge, the visit and its wrong PINs, the number of the outstanding fingerprint launch, what an open prompt is for, `MasterProof`, the in-memory copy of the lock records | `DefaultLockManager` and its `LockEngine`, behind the **lock mutex** | The process (lock.md §5.8, §11) |
| `stoppedAt`, `externalSince`, the no-touch timer | `AutoLock` | The process; nothing on disk (lock.md §10.2) |
| The clipboard token and the pending clear | `AndroidSecretClipboard`; the alarm carries the token and the deadline | Survives the process, not a force-stop (D-14) |
| Settings, one-time flags, the purge arm, the unfinished file task | `PrefsAppSettings` | Disk |
| Which screen is shown | `NavState` and saved instance state, as route keys holding ids only | Survives the process |
| Text being typed, secret or not | The screen's ViewModel, as `TextFieldState` | Until the screen leaves the back stack; an unsaved form is handed to the repository as a draft as it changes |

All of these except the last are created once by `AppGraph` in `Application.onCreate`. Nothing of the lock or the session lives in the activity, so a rotation or a language change neither locks nor loses input (lock.md §11).

### 5.2 Mutexes

1. **Vault mutex** (`kotlinx.coroutines.sync.Mutex` in `DefaultVaultRepository`): every use of VK and every `VaultStorage` call.
2. **Lock mutex** (in `DefaultLockManager`): every lock record read or write, every Keystore key creation or deletion, the whole PIN protocol (lock.md §6.3).
3. **KDF gate** (in `GatedMasterKdf`): one Argon2id at a time in the process (lock.md §6.8) [L-old §2].

Order: lock mutex, then vault mutex, then KDF gate. The repository never calls the lock while it holds the vault mutex; it answers through return values and `StateFlow`s. No mutex is held while a fingerprint prompt or a file picker is open.

### 5.3 Dispatchers

| Dispatcher | Work |
|---|---|
| `Dispatchers.Main.immediate` | Compose, ViewModels, `LockManager.send` and the publication of lock state, `BiometricPrompt`, setting and clearing the clipboard, `AutoLock` callbacks, navigation |
| `Dispatchers.IO` | `OsFiles` (vault generations, lock records), the file picker's streams, `SharedPreferences.commit()`, Keystore calls (they are binder calls), `FingerprintKey.availability()` (on Android 10 it makes a key each time, lock.md §13.1) |
| `Dispatchers.Default` | Argon2id, sealing and opening bodies, JSON, sorting, building and querying the `SearchIndex`, zxcvbn, import parsing, export writing |

- `AppDispatchers` wraps the three so that tests pass one test dispatcher.
- `AppGraph.appScope` = `SupervisorJob() + Dispatchers.Main.immediate`. A save or a record write that has started always finishes: `edit` runs its commit in `appScope` under `NonCancellable`, so leaving a screen cannot cut a write in half.
- Nothing blocks the main thread: no `runBlocking`, no file or Keystore call there.
- A search runs on `Default` with "latest query wins" in the ViewModel (`collectLatest`); `matchedRanges` only for visible rows (search.md §5).

### 5.4 How screens see state

- `VaultRepository.state`, `LockManager.state`, `LockManager.status`, `SecretClipboard.status`, `SecurityReports` and `AppSettings` are `StateFlow`s of immutable values. `Vault` is immutable; an edit builds a new one.
- A ViewModel maps them to one `StateFlow<UiState>` with `stateIn(viewModelScope, WhileSubscribed(5_000), …)`; a composable reads it with `collectAsStateWithLifecycle()`.
- UI state carries `UiText` (a resource id and arguments), never a finished sentence, so the language can change under it (i18n.md §1.4.4 rule 2). Nothing resolves a string with the application context. The few texts that are stored in the vault ("Untitled" and the time, "… (from backup)") are resolved in `:app` and passed to `VaultEdits` through `EditTexts`.
- Every change to the vault is one call of `VaultRepository.edit` with a pure function from `core.model.VaultEdits`. A screen never holds a mutable entry.
- The top-level state (setup, locked, unlocked) is `LockManager.state`. `NavState` shows the matching back stack and keeps the unlocked one, as ids, while the app is locked (ux.md §4).
- After a rotation the screen asks for the keyboard again on the field that had focus, because Android 17 no longer restores it [V-P7].

### 5.5 What "lock" does, in order

1. **Decide.** `AutoLock` (the lock delay at `ON_START`, the screen-off receiver, the no-touch timer), "Lock now" (`Session.lockNow`, which also clears our clip), or a new process (which starts locked and has nothing to write) decides to lock (lock.md §10).
2. **The draft first.** `AutoLock` calls `VaultRepository.flushDraft()`: if the held draft differs from the one stored, one `edit` writes it as the vault's `draft`. It is best effort and bounded: if the write has not started within 2 seconds (another save is still running), it is skipped; a write that has started always finishes, and step 3 waits for it. A failure does not stop the lock. This call belongs to the session, not to the lock: nothing in `core.lock` or `LockManager` calls it, so lock.md I1 and its test T17 hold as written.
3. **`LockManager.lock(reason)`:** `VaultPort.lock()` waits for a save that is running, then zeroes VK and its other byte arrays, drops the `Vault` and publishes `VaultState.Locked`. The derived list, index and report go with it.
4. `LockManager` cancels an open prompt, drops `MasterProof`, and publishes `Locked(screen)` from `EntryPolicy`.
5. `NavState` shows the locked stack. The ViewModels of the unlocked screens are cleared with their entries; their `TextFieldState`s go with them.
6. Kept: the clipboard and its pending clear, the draft, an unfinished file task, the route ids (lock.md §10.4).

The same flush runs at process `ON_STOP`, while the vault is still open (ux.md §5.14). With the default 30-second delay that flush has written the draft long before the lock; the screen-off lock (about 700 ms before `ON_STOP`) and the no-touch timer (no `ON_STOP` at all) are why step 2 exists. On a screen-off the screen is dark during the write; for the no-touch timer and "Lock now" the form stays visible for the length of one save before the lock screen replaces it.

**Automatic writes.** The purge of Recently deleted (D-24), the master-password match list (D-9b), `securitySeenAt` and the draft flush are writes the user did not ask for. After an unlock that opened an older copy (4.8), none of them runs until the user has answered the older-copy notice (lock.md §13.4 item 1), and housekeeping waits with them; the notice is the first thing shown after such an unlock, so the user's own saves come after it too.

### 5.6 What never reaches saved state, a log, a toast or an error message

- **Saved state** (`rememberSaveable`, `SavedStateHandle`, `onSaveInstanceState`) holds only route keys (an enum and UUIDs), scroll positions and booleans. Never a password, a PIN digit, a username, a note, a field value, a search query or a file's content.
- **Secret text fields** use `TextFieldState` created in a ViewModel, never `rememberTextFieldState()`. After reading: `clearText()` then `undoState.clearHistory()`, or the state object is dropped [V-C11, L-win Experiment 5].
- **Keys and passwords** are `ByteArray` or `CharArray`, zeroed after use; never a `String` where it can be avoided. PIN digits stay in a `ByteArray` from the pad to the blocklist (`PinBlocklist` takes bytes).
- **No `Toast` anywhere**: the system draws toasts outside the secure window [L-win Experiment 1].
- **Exception messages carry no input.** kotlinx-serialization's `JsonDecodingException` quotes a stretch of the text it was reading, which for the vault body or an import file is account data. `StrictJson` fails only with its own exception, `StrictJson.Failure(offset, reason)`, whose reason comes from its fixed list and whose message is the reason's code and the offset, never input. `BodyCodec`, `RecordCodec` and the importers catch it, and every other parser exception, at their boundary and return a `ParseError(offset, reason.code)` (an offset and a reason from a fixed list) instead. No message or stack trace of a caught `Throwable` is logged or shown.
- **Logging.** `:core` contains no logging call at all (no `println`, `System.out`, `System.err`, `printStackTrace`, `java.util.logging`). In the main source set of `:app` the only logging is `AppLog.event(Event)`, which takes a constant of a fixed enum and nothing else; `android.util.Log` and `printStackTrace` appear only in the `debug` and `staging` source sets. This widens lock.md R-6 to both modules.
- These rules are checked by source-scanning unit tests (8.5).

---

## 6. Decisions on the cross-document questions

ux.md §14 lists what was left for other owners. Each is decided here or in lock.md. "User" marks the few that only the user can answer.

| # | Question | Decision | Reason |
|---|---|---|---|
| D-1 | Where does the lock's logic live? (the brief puts the lock in `:app`; lock.md §15.1 puts every decision in `:core`) | Rules, records' codec, counters, policies and flows in `core.lock`; Keystore, record files, `BiometricPrompt`, the session and `LockManager` in `:app` | lock.md's tests T1 to T20 run on the JVM against fakes, and `:core` stays free of Android calls |
| D-2 | ux §14.1: the app's own PIN or the phone's lock? | The app's own PIN | Settled by the user (lock.md §2.4) |
| D-3 | ux §14.2: where are the user's passwords today? | **User.** Until answered, the import build order of ux.md §5.21 stands | Only the user knows |
| D-4 | ux §14.3: who reviews the Uzbek and Russian warnings? | **User.** The user is asked to read the warning, limit and banner texts in their own languages before release (8.6) | Needs a native speaker |
| D-5 | ux §14.4: PIN length and attempts; fingerprint after the PIN is off; practice grace; the wait for a new master password; may a fingerprint approve "Change PIN"? | lock.md §16.1: exactly 6 digits; off after 5 wrong in a row or 10 that were not forgiven (a right PIN on the lock screen forgives at most two wrong PINs of the last 60 s of its visit); fingerprint stays usable; grace 1, 2, 4 then 7 days; 24 phone-on hours with the new password chosen first, and finishing makes a new vault and puts the old one aside; a fingerprint approves nothing except asking for a new master password when no PIN exists | Decided in lock.md |
| D-6a | ux §14.6a: 6 words from the EFF short list 1 instead of 5 from the long list | Yes: 1,295 words (without "yo-yo"), 6 words, 62.0 bits | 2.6 bits below the 64.6 bits of crypto.md's five long-list words, accepted because short common words suit a non-native reader who copies them to paper [I]. V-C13's "EFF recommends six words" is advice for the long list; no screen quotes it |
| D-6b | ux §14.6b: spaces as separator, runs of spaces folded | **Fold now, keep the hyphen.** `MasterPasswordBytes` folds white space from the first release, after NFC (4.6, lock.md §8.1). The suggested passphrase keeps hyphens in 1.0 | The byte rule can never change after release, and folding only makes a password more forgiving. Which separator to suggest is a screen detail that can change later, after the keyboard test of 8.6 |
| D-6c | ux §14.6c: fold apostrophe-like characters | Yes, 17 code points (lock.md §8.1). ux.md §5.3's refusal of apostrophes is dropped | The same password must work on every keyboard |
| D-6d | ux §14.6d: score 4 with the Uzbek and Russian word list for own master passwords | Yes: at least 12 characters, and zxcvbn score 4 for the password and for its `searchKey` | Score 3 falls in 16 hours to 68 days on one GPU against a stolen backup (ux.md §5.3) |
| D-6e | ux §14.6e: backups without retyping for 14 days | No. A backup may use the entry typed a moment ago in the same flow (lock.md F12) | A backup made by someone who has forgotten the password is worthless and looks safe |
| D-6f | ux §14.6f: restate crypto.md §11 step 6 as the practice rule | Yes: lock.md §8.3 | — |
| D-7 | ux §14.7: 30 seconds or "Immediately" | 30 seconds, written at setup; a missing or unreadable setting means "Immediately". The clipboard delay likewise: 1 minute written at setup, 30 seconds when missing | lock.md §10.1; [V-P9] |
| D-8a | ux §14.8: a `draft` object in the vault body | Yes, as in 4.3: at most one | An unsaved form must survive a lock without touching the entry |
| D-8b | Archiving instead of deleting; archives never pruned | Yes: one directory rename (4.11) | Nothing reachable from the lock screen deletes anything |
| D-8c | `pendingOldPassword` and `deletedAt` | Yes, in the entry (4.3) | — |
| D-8d | A backup as a copy of the verified generation | No copy of the file: a new container with its own key, sealed from the verified open vault (4.12) | A copy would share VK with the phone's vault [V-C5]; [V-S6] is met because the open vault *is* the verified generation |
| D-8e | The data behind the rollback notice | `generation` and `created` in the header; the highest generation seen in `state.rec` (lock.md §5.6) | It must not travel with the vault file |
| D-9a | ux §14.9: per-entry "Leave out of the security check" | Yes. The Security tab always shows how many are left out | PINs and passwords the user cannot change would otherwise be reported for ever |
| D-9b | The master-password reuse check: at each master-password entry, and once at Save | **At each correct master-password entry only.** The typed characters are compared in memory, through `MasterPasswordBytes`, with every entry's password. No check at Save | A check at Save would be one Argon2id per saved password with no waiting time: a way to test master-password guesses outside the guard of lock.md §6.8. It would also cost 64 MiB and a second per save |
| D-9c | Not clearing the clipboard on an automatic lock | Accepted. The clipboard is cleared by its timer, by "Clear now" and by "Lock now" | With a 30-second lock the password would be gone before it could be pasted. The timer has no "Never" |
| D-9d | "Show in big letters" | Accepted as an exception to "show secrets briefly": secure window, closes after 2 minutes without a touch, never restored | It is the tool for typing a password on another device |
| D-9e | The 10-minute grace for the app's own file picker and share sheet | Accepted (lock.md §10.3) | Screen-off still locks at once; unfinished file tasks survive a lock |
| D-10a | ux §14.10: the `Title:` line in the CSV block | Yes, with formats-review P8 and the grammar below this table: the marker `--- PasswordVault (N lines) ---`, labels with a backslash escape, two reserved labels, names trimmed at input | Google ignores the `name` column; the block must only be parsed when it is ours, and every later version must read what 1.0 wrote |
| D-10b | Related-website rules at import | ux.md §5.21 rules (a) to (c); nothing else is joined. Merging stops at 10 websites (formats-review P5) | Chrome writes one row per affiliated domain; joining more would hide real reuse |
| D-10c | `pendingOldPassword` and `deletedAt` in JSON | `passwordVault.pendingOldPassword` in our JSON. Deleted accounts are in no plain export, so `deletedAt` is never written there | Our own JSON must round-trip; a deleted account must not reappear from an export |
| D-10d | A plain export during a pending change | `password` is the new one. The old one: `pendingOldPassword` in our JSON; in CSV and Excel the reserved hidden line `Old password`. Importing our own file restores `pendingOldPassword` and sets `changePending` | Neither password may be lost |
| D-10e | Deleted accounts in exports | Left out of CSV, JSON and Excel; included in the encrypted backup | — |
| D-10f | Notes over the limit on import | The entry is imported, the note cut and listed. More generally (P5): an over-limit value never drops an entry; only a password over 1,000 characters does | "Never lose the user's data" |
| D-11 | ux §14.11: ş → s and ç → c; the wrong-keyboard retry; new glossary terms | Yes: both letters in search.md's second tier; when a query finds nothing it is retried through the ЙЦУКЕН ↔ QWERTY map; the terms go into the string files with the screens that use them | Accent-free and wrong-layout typing are common |
| D-12 | ux §14.12: plain exports to the share sheet | No. The file picker only. The encrypted backup may be shared | A readable password file in a messenger is the leak the warning screen describes |
| D-13 | ux §14.13: who runs the real-world checks | **User**, with the list in 8.6 | They need the user's phone and apps |
| D-14 | ux §14.5: how the clipboard is cleared | An alarm to a receiver that is not exported, plus a timer in the process. Exact (`setExactAndAllowWhileIdle`) where no permission is needed (below API 31, or `canScheduleExactAlarms()`); otherwise `setAndAllowWhileIdle` aimed at 1/1.75 of the delay so that its delivery window ends at the deadline, with the receiver waiting out a short rest or aiming again. No exact-alarm permission, no WorkManager. The row says "Clearing…" when the time has passed and the clear has not run. The token and the deadline are also kept in `pv_settings`, so that the app clears an overdue clip of its own when it next comes to the front | WorkManager adds four permissions and exported components to an app whose promise is "no network" [V-P2]. **Measured** [L-clip] (`docs/lab/clipboard.md` §1 and §3): on Android 17 the mechanism without the permission cleared 68 of 68 runs (37 of them with the code as delivered), 1 to 447 ms after the deadline (in the background, frozen, screen off and PIN-locked, Doze, battery saver, `am kill`, a swipe from Recents), and the lab's variant with the permission 25 of 25 with the delivered code (4 to 463 ms); Android 7.0 9 of 9 (at most 42 ms) and Android 9 9 of 9 (at most 904 ms, after SIGKILL) with the delivered code, which needs no permission there. (This row first said 46 of 46, 14 of 14 and 19 of 19 from the working tables: those counts also took in runs of an earlier version of the code and of the bare exact alarm, none of which failed to clear outside a force-stop.) The report recommends declaring `USE_EXACT_ALARM`; this decision does not (R-9) and takes the report's path without the permission. **It never cleared after a force-stop** (Android cancels a stopped app's alarms; Android 7 and 17, with and without the permission) **nor while the app's battery use was "Restricted"** (until the app was opened again). The first-copy tip, Help and 8.6 say so. A timer alone and WorkManager left the secret after a kill or a swipe. The lab's report and kit are in `docs/lab/clipboard.md` and `docs/lab/clipboard-artifacts/` (build-plan package 0.3); a later difference from them changes only `AndroidSecretClipboard` |
| D-15 | formats-review: 18 findings | All accepted. P1 to P6 must be fixed before the import package is accepted, each tested end to end through `Importer.read` from its trigger in formats-review.md (P2, P3, P4 and P12 too; the primitives package tests only the primitives). P1: an incoming id is kept only if it is a valid UUID that is neither in the vault nor earlier in the file; "Undo this import" saves the vault as it was before the import. P14: a password is never altered on import; an entry whose password holds a control character is imported as it is and listed. P17, P18: formats.md and the scripts are corrected by the formats packages | The writers held up; the importer loses or garbles data in the user's own languages |
| D-16 | formats.md §11, §13: Excel with a password | Not in 1.0. The code stays in `prototypes/formats` | Nothing here can run Microsoft Excel, and it adds a second password for the user to keep. First candidate for 1.1, after the user has opened the two sample files in their Excel |
| D-17 | formats.md §13.4, §13.5 | Hidden fields are shown in clear in the Excel export. The backup header carries no entry count; the restore summary shows the count after opening and the date from `created` | The Excel file exists to be read and printed; a count outside the encryption is metadata for anyone holding the file |
| D-18 | Times in the vault body and header | Integers, milliseconds since 1970 UTC. ISO 8601 text only in the JSON export | No parser, no `java.time` (API 26), same as the lock records |
| D-19 | Settings storage | `SharedPreferences` with `commit()` | A lost setting falls back to the strictest value [V-P9], so nothing here must survive a crash; DataStore is not atomic on API 26+ [V-S7] and would be a new dependency |
| D-20 | An unkeyed SHA-256 trailer on the vault file (storage.md has one, crypto.md none) | Kept, as a damage check only | For a single backup file it is the only way to tell "damaged on the way" from "wrong password" [V-S3] |
| D-21 | `android:hasFragileUserData`, `taskAffinity` | `hasFragileUserData="true"`, `taskAffinity=""`, `launchMode="singleTask"` | platform.md §1 item 6. An uninstall then offers to keep the data, and the master password opens a kept vault. **Answered by lock.md** (F15b, §14.2, E10): after such a reinstall the records are there and the keys may be gone; the canary is made again, each shortcut is GONE with the "stopped working" text, and after the master password the app offers to set them up again. E10 runs it in wave 4 |
| D-22 | A bundled monospace font for secrets (ux.md §5.13) | Not in 1.0: `FontFamily.Monospace`, coloured digits and symbols, and one cell per character in "Show in big letters" | No font was checked today for U+02BB, Uzbek Cyrillic and its licence |
| D-23 | The app's name in its own texts (ux.md and lock.md write "Password Vault") | "PasswordVault", one word, in every language and every text, from the one resource `app_name` | The user chose it for the launcher; one spelling is easier to recognise and to translate. Package 0.3 changes the documents |
| D-24 | ux.md §5.23: deleted entries are removed 30 days later at the next unlock, by the wall clock | Two steps. At an unlock that finds entries whose `deletedAt` is 30 days or more before the wall clock, the app only arms the purge (`PurgeArm` in the settings: the vault, the wall time, and a `Credit` of lock.md §6.9). At a later unlock, once 24 hours are credited, it removes the entries that were already due at the arming time, and disarms. A switch of vault disarms | One unlock with the date set a year ahead would otherwise empty Recently deleted for good; storage took the wall clock out of retention for the same reason [V-S4]. The cost: removal comes a day or more after the 30 days |
| D-25 | ux.md §6 item 5: copies of selected text | `SecretClipboardBridge`, provided as `LocalClipboard` at the root: `setClipEntry` goes to `SecretClipboard.copy` with the sensitive flag and the timed clear ("Copied text · clears in 0:52"); `getClipEntry` is the platform's clipboard unchanged, so pasting into the app's own fields still works | Text selected in notes often holds security answers (ux.md §6 item 1); a bridge that also blocked reading would break pasting a password into the Add form |
| D-26 | Generation files the app cannot read (as opposed to read and found damaged) | A fourth state, UNREADABLE (4.8): never fallen back past, never renamed, never counted as verified, and nothing is pruned below it. NOT OURS and over-long files are DAMAGED inside a vault directory. After an older copy opens, automatic writes wait for the user's answer (5.5) | A passing read error must not make the app open an older copy and bury the newest one; one unreadable old file must not lock the user out |

**D-10a in full: the fields block of our CSV.** Every later version must read what 1.0 writes, so the block has a grammar. The formats package copies it into formats.md §4.4 with its vectors: the four cases of formats-review P8 and the two reserved labels.

```
note    = user-notes [ LF LF block ]                 ; written only when the block has at least one line
block   = marker 1*( LF line )
marker  = "--- PasswordVault (" count " lines) ---"  ; count: the lines after the marker, continuation lines included, ASCII digits
line    = label [ " (hidden)" ] ": " text *( LF "  " text )    ; later lines of a value are indented by two spaces
```

- **Order of lines:** `Title: <title>` when the title differs from the host of the first website (ux.md §14.10); one line per extra field, in order; `Old password (hidden): <pendingOldPassword>` while a change is pending (D-10d).
- **Labels.** Field names are trimmed when they are entered or imported, so a label never starts or ends with a space. A backslash makes the next character literal: a label is written with `\` as `\\`, `:` as `\:` and `(` as `\(`. So the first unescaped `: ` ends the label, and an unescaped ` (hidden)` at its end can only be the flag.
- **Reserved labels:** `Title` and `Old password`, only when written with no backslash. A user's own field with one of these names is written with its first letter escaped (`\Title`), and reads back as an ordinary field.
- **Recognised only when it is ours:** the marker must follow a blank line and `count` must equal exactly the number of lines after it, to the end of the note. Otherwise the whole note is text. A note that contains a pasted block therefore stays a note.
- **The user's text never becomes fields.** If the user notes alone would be read as notes and a block (they end with a marker that meets both conditions, followed only by well-formed lines), the writer appends a block of its own even when it has nothing else to write: then with the `Title:` line, whatever the host, and also for an entry without a website. The reader takes the writer's marker, the last one that qualifies, and the user's lines stay in the notes (formats.md §4.4, vector 8). Added at the wave-0 integration, before the inputs were frozen: without it, such notes on an entry with no extra field, no pending change and a title equal to its host would come back as fields.
- **Settled with formats.md §4.4** (wave 0): the host is `WebAddress.hostOf` of the first website, compared exactly, and an entry without a website gets no `Title:` line except by the rule above; the reader takes the last marker line that meets both conditions, and a line after it that is neither a `line` nor a continuation makes the whole note text; the two `LF` are written before the block also when the user notes are empty.
- The marker, `(hidden)` and the two reserved labels stay in English in every language, because the importer reads them.

Lock questions that stay open are in lock.md §18; none of them blocks the build. Two of them are answered here: §18.8, the order and the 17 code points of lock.md §8.1, is accepted (4.6); §18.9, the storage requests of lock.md §16.3, is accepted with the calls of 3.3 and 3.4 and the procedures of 4.9 and 4.11.

---

## 7. Dependencies, release configuration, and what version 1 leaves out

### 7.1 Dependencies

Already in `gradle/libs.versions.toml` and built today: AGP 9.4.1, Kotlin 2.4.20, Compose BOM 2026.09.00 (ui and foundation 1.12.1, material3 1.4.0), activity-compose 1.13.0, appcompat 1.8.0, core-ktx 1.19.1, kotlinx-serialization-json 1.11.0, Bouncy Castle `bcprov-jdk18on` 1.86, JUnit 4.13.2, androidx.test ext-junit 1.3.0, runner 1.7.0, espresso-core 3.7.0.

New:

| Artifact | Version | Module | Where the version comes from |
|---|---|---|---|
| `androidx.biometric:biometric` | **1.4.0-alpha07**, pinned | `:app` | lock.md §9.1; ran on Android 7, 9 and 17 [L-old §3, L-bio] |
| `com.lambdapioneer.argon2kt:argon2kt` | 1.6.0 | `:app` | crypto.md §4; loaded and matched the vector on Android 7 and 9 [L-old §2]; [V-C1, V-C2, V-S9] |
| `androidx.lifecycle:lifecycle-process`, `lifecycle-runtime-compose`, `lifecycle-viewmodel-compose`, `lifecycle-viewmodel-navigation3` | 2.11.0 | `:app` | platform.md §3.3; the lab apps were built with it and ran on Android 7, 9 and 17 [L-old §1] |
| `androidx.navigation3:navigation3-runtime`, `navigation3-ui` | 1.2.0 | `:app` | ux.md §4; navigation and system back ran on Android 7 and 9 [L-old §1] |
| `org.jetbrains.kotlinx:kotlinx-coroutines-core` (`:core`, `:testing`), `-android` (`:app`), `-test` (tests) | 1.11.0 | all | The lab apps' version catalog (in the Gradle cache of this machine) |
| `com.nulab-inc:zxcvbn` | 1.9.0 | `:core` | ux.md §5.3: MIT, no dependencies, jar hash checked against Maven Central. Not yet built into this project: the first build that adds it must run the vectors of ux.md §12 check 18 |
| Test only: `androidx.compose.ui:ui-test-junit4`, `androidx.test:rules` | from the BOM; 1.7.0 | `:app` | Added by package 0.1 with the rest, so that no later package edits a Gradle file to add a test library |

Data files, not libraries: the EFF short word list 1 (CC BY: credit, licence link and the note that "yo-yo" was removed, on the licences screen [V-C13]); the Uzbek and Russian word list for the strength meter, compiled by the strength package (build plan 2.12 states its sources, size and licence).

Not used, on purpose: Hilt or Koin, KSP, Room, SQLCipher, DataStore, WorkManager, `material3-adaptive-navigation-suite` (a `NavigationBar` under 600 dp and a `NavigationRail` from 600 dp are one `if`), `material-icons-extended` (icons are vector drawables), Apache POI, a screenshot-test library, core-library desugaring, Robolectric (code that needs the framework is tested on a device).

### 7.2 Build types

| | `debug` | `staging` | `release` | `backupCheck` |
|---|---|---|---|---|
| Purpose | Daily work, emulator checks | The emulator checks against the optimised code; never given to anyone | What the user installs | lock.md E15 only: a manifest overlay sets `allowBackup="true"` and removes `android:fullBackupContent` and `android:dataExtractionRules` (`tools:remove`), whose rules exclude every domain, so that something is backed up. Built only by that check's script |
| R8 (`optimization { enable = true }`) | off | on | on | off |
| Debuggable | yes | no | no | yes |
| ABIs (`ndk.abiFilters`) | arm64-v8a, armeabi-v7a, x86_64 | the same | **arm64-v8a, armeabi-v7a only** [V-S9] | as `debug` |
| Signing | debug key | debug key | `keystore.properties` and the keystore are outside git (`.gitignore` has both); without them the build gives an unsigned APK | debug key |
| `BuildConfig.ALLOW_SOFTWARE_KEYSTORE` | true | true | **false** (lock.md §4.5) | true |
| `BuildConfig.SECURE_WINDOW` (`FLAG_SECURE`) | false | true | true | false |
| `DebugCommandReceiver`, pseudo-locales | yes | receiver only | no | as `debug` |

- The app ID is `io.github.dilshodbekmx.passwordvault` in every build type, with no suffix, and a build check compares the merged manifest's package with that constant (R-14).
- The lock screen's red line "TEST BUILD: PIN keys are not protected" shows exactly when `ALLOW_SOFTWARE_KEYSTORE` is true.
- An APK that holds native libraries for arm only cannot be installed on the x86_64 emulators, which is why `staging` exists.
- **Manifest:** `android:name=".PasswordVaultApp"`, `allowBackup="false"` with both rule files as today [V-P6], `largeHeap="true"` [V-C2], `hasFragileUserData="true"`, `supportsRtl="true"`; `MainActivity` with `launchMode="singleTask"`, `taskAffinity=""`, no `configChanges`; `uses-permission HIDE_OVERLAY_WINDOWS`; the `AppLocalesMetadataHolderService` entry of i18n.md §1.4.2; `ClipboardClearReceiver` and a `FileProvider` (authority `${applicationId}.files`, cache path `shared/`), both `exported="false"`. In the `debug` and `staging` manifests: `DebugCommandReceiver` with `exported="true"` and `android:permission="android.permission.DUMP"`, which `adb shell` holds and other apps do not (the same guard as `ProfileInstallReceiver`); `adb shell am broadcast` cannot reach a receiver that is not exported, and the API 37 test image has no root.
- **Resources:** `generateLocaleConfig = true`, `localeFilters` en, uz, b+uz+Cyrl, ru, and the lint errors of i18n.md §1.1.
- **R8.** argon2kt marks its JNI members `@Keep`, and kotlinx-serialization ships its own R8 rules (checked in the 1.11.0 jar [X]). **Navigation 3 1.2.0 ships none** (neither AAR has a `proguard.txt` or rules under `META-INF` [X]), and its saved back stack finds each key's serializer by class name. So `rules.keep` holds one rule of our own: keep the route key classes of `nav.Route` and their generated serializers. The `staging` smoke of every integration check proves it, including a back stack restored after the process died in the background (8.4).
- **Dependencies are locked** (`dependencyLocking`) and their SHA-256 sums recorded (`gradle/verification-metadata.xml`) from wave 0; whoever changes a version regenerates both in the same commit.
- **The release key** is made once by the user, kept outside the repository and backed up; losing it means no update can ever be installed over the app.

### 7.3 Left out of version 1

Everything in ux.md §11 and lock.md §1.4, and also:

| Left out | Reason |
|---|---|
| Excel with a password | D-16. The code and its checks stay in `prototypes/formats` |
| A bundled monospace font | D-22 |
| The phone's screen lock as a way in; a switch to turn the PIN off | lock.md §2.4, §18.12 |
| An Android notification while a new master password is pending | A question for the user (lock.md §18.16). It would need `POST_NOTIFICATIONS` on Android 13+ and a change to R-9 |
| A baseline profile | It needs a real phone to generate and measure; search.md §5 says when it will matter |
| A two-pane layout, adaptive navigation library | Phones first |
| A master-password reuse check at Save | D-9b |
| Restoring an older generation from the screens | Generations exist for automatic recovery; Recently deleted, history and import undo cover mistakes (ux.md §11) |
| Android Auto Backup or device transfer of the vault file | The manifest excludes every domain; the user's own backup file is the way to another phone |
| A published decrypt script | Planned with the release wave (`tools/pvault_decrypt.py`); it doubles as the second reader for 4.13 |

---

## 8. Test strategy

### 8.1 JVM unit tests in `:core`

Run with `./gradlew :core:testDebugUnitTest` twice: under a Turkish default locale (`tr-TR`, which breaks case folding) and under a Persian one (`fa-IR`, whose default `String.format("%d")` writes Persian digits on JDK 25 [X]), so that no result depends on the locale. The fakes come from `:testing`.

| Package | What must pass |
|---|---|
| `crypto` | The Argon2id vectors (two exist today); `MasterPasswordBytes` vectors: the apostrophe spellings, the Greek line and the decomposed spelling of lock.md §4.3, the white-space and white-space-only vectors of 4.13, long and non-ASCII passwords, `of(of(x)) = of(x)` and `of(NFD(x)) = of(NFC(x))` for 10,000 random strings, unassigned code points refused at creation; AES-GCM with and without AAD; `GuardedArgon2id` with a missing native library, too little heap, and `OutOfMemoryError`; `GatedMasterKdf` never runs two derivations at once |
| `vaultfile` | Vectors A and B of 4.13 byte for byte, both ways: A from its plaintext bytes through the container, B from its `Vault` value through `BodyCodec` and the container, and both read back. Every reader rule R1 to R10 with one file that breaks exactly that rule, R1's `PVAULT02` with and without a good trailer among them; a byte-order mark, a malformed UTF-8 sequence, `1.0` and `1e3` and a 20-digit number where an integer belongs, depth 16 and 17 counted over objects and arrays; the AAD with the nonce fails; unknown keys kept at both levels and dropped below them; reading and writing again gives the same bytes; 100,000 random and mutated inputs never throw past `check` and `open`; no `ParseError` holds input text |
| `storage` | With `FakeFiles` (which keeps "handed over" and "durable" apart, can stop at every boundary, and can make any one file's read throw): a kill or power cut at each step of 4.9 for `commit`, for `stageVault` with and without `putCurrentAside`, and for `stageRekey` (a kill during the proof leaves only a `.tmp`), leaves the last good generation openable; `rename` never replaces [V-S1]; a failed `syncDir` is `NotDurable` and prunes nothing [V-S2]; "wrong password" only after every slot [V-S3]; the three scenarios of [V-S4] (bad early saves on a new install, a clock jump, a fault lasting 15 days) never lose the last good copy. **Per-file states:** a read error on an old generation (the vault opens, nothing is renamed, nothing below it is pruned); on the newest (`Unreadable(IO)`, no fallback, nothing written); a zero-filled newest file (DAMAGED, the older copy opens with `olderCopyFirst`, and no automatic write runs before the notice is answered); a file over the cap is DAMAGED. **Several unlocks in one process:** unlock, three saves, lock, unlock, thirty saves: the five copies verified before this process are all still on disk. `.bad` and `.aside` are never deleted; retirement after a password change [V-S5]; put aside, `swapIn` with a kill between the renames, `mergeAside`; the number is always above every name on disk |
| `lock.*` | T1 to T20 of lock.md §15.2 as revised by its audit, with their IDs in the test names |
| `model` | Limits and validation of ux.md §2; history capped at 5; the guided change keeps `pendingOldPassword` through 7 retries (ux.md §12 check 5); delete and restore; the purge of D-24 (a clock set a year ahead removes nothing until 24 hours are credited, and then only what was due at arming); the one draft; no edit yields two entries with one id; stored titles come from `EditTexts` |
| `search`, `text` | The 527 tests of `prototypes/search`, plus "besiktas" finds "Beşiktaş", "garcon" finds "Garçon", and "штые" gives the results for "inst" |
| `formats` | `prototypes/formats/test`, moved; one test per finding P1 to P16 of formats-review.md built from its trigger, those of P2, P3, P4 and P12 through `Importer.read`; our own CSV and JSON round-trip; the grammar of D-10a with the four cases of P8 and the two reserved labels; the Excel file is read back with `java.util.zip` and the JDK's XML parser; the fixtures of formats.md §8.5 from `core/src/test/resources/formats/` |
| `generator` | Unbiased choice over 10⁶ draws; at least one character of each chosen kind; 6 words from 1,295 |
| `strength` | The eight weak examples of ux.md §12 check 18 are refused and score 2 or less; the suggested passphrase scores 4; reuse groups; "same as master password" through `MasterPasswordBytes` |

### 8.2 JVM unit tests in `:app`

ViewModels against the fakes of `APP/fake/` and `:testing`; `AutoLock` with a fake lifecycle and clock, including the order of 5.5 for every lock trigger (a dirty form and a no-touch lock: the draft is written before `VaultPort.lock()`); the language mapping of i18n.md §1.4.5; the resource completeness test of i18n.md §5, one test per language (every string and plural in all four languages, the apostrophe and script rules); the source-rule tests of 8.5. Nothing here needs the Android framework: file locations are `DocRef` strings, and `android.icu` is reached through the `Collation` interface, whose real implementation is tested on a device (8.3).

### 8.3 Instrumented tests (`:app:connectedDebugAndroidTest` on emulators with Android 7, 9 and 17; every run sets `ANDROID_SERIAL` to its own devices)

A run is checked per device, through `tools/emu/adb.sh --connected` (or `--connected-results` after a run by hand): AGP 9.4.1 reports success when it skips a device it cannot install on, so a run counts only when each serial has a passing report.

- `OsFiles`: write, sync, rename, directory sync; `rename` onto an existing name replaces it (so the check of 4.9 step 8 is needed); each failure of [L-sto] throws.
- `NativeArgon2id` and `BouncyCastleArgon2id` give the vectors on the device. On API 37 a derivation under Android 17's memory limiter (`adb shell am memory-limiter manual <pid> <MB>`, with the limit just above and just below what Argon2id needs) either succeeds or gives `KdfUnavailable`: never a crash, never "wrong password"; the result is recorded [V-P7].
- The vectors of 4.13 (A reproduced, A and B opened, through the real codec) and lock.md §4.3 (`MasterPasswordBytes` on API 24's ICU 56 against JDK 25's results) pass on the device (lock.md A5).
- `KeystorePinKey` and `KeystoreFingerprintKey`: create and delete both kinds; the PIN key's HMAC gives a wrap that opens; the fingerprint key's `Cipher.init` classified by lock.md §14.3; the adapter rows that can be produced. Using the fingerprint key needs a prompt and a finger from the host, so that is E6 (8.4).
- `SafDocumentIo` against a test documents provider: write, read back, a provider that writes 0 bytes is caught [V-S6], delete.
- `SortKeys` and `Formatters` on API 24 and 37: the Uzbek and Russian order and letter headers of i18n.md §2.7, ICU 56 against ICU 78.
- Compose tests: the pad (no digit in any node), the secure field (nothing in saved state after `onSaveInstanceState`), the masks; the wrappers of R-13 set the content type, the sensitive flag and the window policy; `SecretClipboardBridge` (text selected and copied in notes carries the sensitive flag and the token, and pasting into a field still works).
- `NavState`: a lock keeps the route ids and clears the unlocked ViewModels; a recreation keeps the route.

### 8.4 Emulator scripts (under `tools/emu/`, on private devices made by `tools/emu/boot.sh`; every `adb` call names its device with `-s`; never `adb kill-server`)

- **Lock:** E1 to E17 of lock.md §15.3, through `DebugCommandReceiver` and the scripts in `docs/lab/biometric-artifacts/`, in the waves the build plan gives (the pending wait of E4, E5 and the picker steps of E17 need wave 5, the language step of E11 needs wave 6). E12 runs as assertions on the accessibility tree (every key reachable, its label, its sensitive flag); TalkBack itself is checked on the user's phone (8.6 item 8).
- **Windows and input** (`tools/emu/window/`, with the lab's helper app rebuilt as its own small Gradle build with minSdk 26): on API 28 and 37, debug and staging, against the real screens: no fill and no save request for any field (`af_scenario.py`), the keyboard flags (`ime_probe.py`), overlay taps dropped (`e4_overlay.py`), nothing of a secret field in the saved bundle (`e5_state.py`), and a non-tool accessibility service (`LabA11yService`) sees no secret text. With defaults the window lab saw an autofill service receive the typed master password in plain text [L-win], and even an opted-out field's text is written as its autofill value [V-P4], so these run at every integration check from wave 3B on.
- **Clipboard:** the lab's `clipexp.py` cases against the app, once package 0.3 has committed them: cleared at the deadline in the background, after `am kill` (API 28 and 37) and after `kill -9` through `run-as` (API 24, where `am kill` was refused [L-clip]; debug only), after a swipe from Recents; after `am force-stop`, and with battery use "Restricted", the clip stays until the app next comes to the front, which then clears it; a later copy by another app is not cleared on API 24 and 28.
- **Reinstall with the data kept:** E10's `adb shell pm uninstall -k` (lock.md F15b, D-21).
- **Languages:** the matrix of i18n.md §5 on Android 7, 9 and 17, and on 12L and 13 once their images are downloaded (by the recipe of `docs/lab/old-android-artifacts/README.txt`); the switch does not lock (lock.md E11).
- The acceptance checks of ux.md §12 that need a device (1 to 5, 11 to 17, 19, 20); check 12 with Downloads (Google Drive is 8.6 item 4).
- **`staging` smoke**, `tools/emu/smoke.sh <variant> <serial>`, on API 24 and 37 at every integration check, growing with the waves: install and start (wave 0); set up, add, rotate, kill in the background and come back to the same screen (the saved back stack under R8), lock, unlock (skeleton); copy and the timed clear (wave 3); PIN and fingerprint (wave 4); backup, restore, export, import (wave 5); a language switch (wave 6).
- Steps that need a debuggable build (`run-as`, `kill -9` of the app) or root are marked in the scripts and print SKIPPED on `staging`; the API 37 image has no root.

### 8.5 Build checks (fail the build)

R-1 to R-7 of lock.md §15.5 (R-6 as widened in 5.6), and:

| ID | Check |
|---|---|
| R-8 | The release APK's `lib/` holds exactly `arm64-v8a` and `armeabi-v7a` [V-S9] |
| R-9 | The merged manifest's permissions are within `USE_BIOMETRIC`, `USE_FINGERPRINT`, `HIDE_OVERLAY_WINDOWS` and the app's own `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`. Exported components, per variant: `release` exactly `MainActivity` and `androidx.profileinstaller.ProfileInstallReceiver`; `staging` also `DebugCommandReceiver`, guarded by `android.permission.DUMP`; `debug` and `backupCheck` also `androidx.compose.ui.tooling.PreviewActivity` and `androidx.activity.ComponentActivity`, which `ui-tooling` and `ui-test-manifest` add (both are in today's debug merged manifest [X]) |
| R-10 | No file under `core/src/main` or `testing/src/main` imports `android.` or `androidx.`; `core.lock` imports neither `core.storage` nor `core.vaultfile` |
| R-11 | Not in our sources (comments are ignored): `rememberTextFieldState`, `Toast`, `AtomicFile`, `DataStore`, `Files.move`, `java.time`, `java.util.Base64`, any `.destroy(` call, `rawHashAsByteArray`, `encodedOutputAsString` [V-C1, V-C10]; in `:core`, `String.format` or `.format(` without a `Locale` argument; `rememberSaveable` and `SavedStateHandle` only in `nav` and in files on an allow-list |
| R-12 | No build output is tracked by git (`git ls-files` shows nothing under a `build/` directory) |
| R-13 | `TextField`, `OutlinedTextField`, `BasicTextField`, `SecureTextField`, `OutlinedSecureTextField` and `BasicSecureTextField` are called only inside `ui/common`'s `VaultTextField` and `VaultSecureField`, which apply `vaultTextField()`; `Dialog`, `AlertDialog`, `BasicAlertDialog` and `ModalBottomSheet` only inside `VaultDialog` and `VaultSheet`, which apply `VaultWindow`. Under `APP/ui/`, `Text(` and `contentDescription =` take no string literal |
| R-14 | The merged manifest's package is `io.github.dilshodbekmx.passwordvault`; the generator string `PasswordVault ` + `versionName` matches `[A-Za-z0-9 ._()-]{1,64}` |
| R-15 | `MasterProof.forFakes` (3.4) is named only in `MasterProof.kt`, under `APP/fake/`, in the `debug` source set and in `:testing`; the test source sets are not scanned. `LockEngine` makes the real proofs with the internal constructor |

**Offline** (a part of `tools/check.sh`): no `INTERNET` in any merged manifest, `aapt2 dump permissions` on the APKs, and a source scan for the classes that open connections: `java.net.URL`, `URLConnection`, `HttpURLConnection`, `HttpsURLConnection`, `Socket`, `ServerSocket`, `DatagramSocket`, `InetAddress`, `java.nio.channels.SocketChannel`, `DatagramChannel`, `WebView`. `java.net.IDN` and `java.net.URI` stay allowed: `WebAddress` uses `IDN` for punycode.

**How the checks are run** (package 0.1). R-4, R-6, R-10, R-11, R-13, R-15 and the offline class scan are unit tests (`core/src/test/java/…/core/arch/`, `app/src/test/java/…/arch/`) over the scanner of `:testing` (2.1). They read code this way:
1. Comments, and the text inside string and character literals, are ignored; the code of a template expression (`${…}`) is code again. So `Text("Toast")` breaks R-13 and not R-11.
2. R-11's "`.format(` without a `Locale`" does not flag a call on one of our own types or objects, written in UpperCamelCase, other than `String` and `MessageFormat`: `IsoTime.format(ms)` is not flagged, and whatever such a function formats inside is checked where it does. `String.format`, `MessageFormat.format`, `"…".format(…)`, variables and constants are always checked.
3. R-13's "only inside `VaultTextField`, `VaultSecureField`, `VaultDialog` and `VaultSheet`" is checked per file: a call is allowed in a file of `ui/common` that declares the wrapper.

The checks that are Gradle tasks run with each variant's `assemble`: `buildCheck<Variant>Manifest` (R-1, R-5, R-7, R-9, R-14, and `allowBackup` per variant), `buildCheck<Variant>Apk` (R-2's class in or out of the APK, R-8), `buildCheck<Variant>BuildConfig` (R-2's switches) and `buildCheckNoTrackedBuildOutput` (R-12).

Lint runs with `:core:lintDebug :app:lintDebug`; `:core`'s lint is what checks every JDK call against API 24.

### 8.6 What the user must check on a real phone

An emulator cannot settle these. Each item names what it decides.

1. **Speed and memory** (lock.md D1, A1): the time from the sixth digit to the list; the time of one Argon2id; that the PIN key is in secure hardware; the time of the record write after a right PIN.
2. **Fingerprint** (lock.md D2 to D5): turn it on and unlock; add a fingerprint in the phone's settings and see the app's message; real lockout times.
3. **Clipboard:** copy a password, switch to another app, and see that it is gone after the chosen time, also with battery saver on; whether the keyboard's clipboard list keeps it (Samsung, Gboard). After a force-stop, or with the app's battery use set to "Restricted", the copy stays until the app is opened again, as Help says (D-14).
4. **Files:** a backup to Google Drive and to Downloads, then a restore from each (ux.md §12 check 12 with Drive); the name the picker shows for Drive.
5. **Excel:** `passwords-….xlsx` opens in the Excel, Google Sheets or WPS Office the user has, with phone numbers and leading zeros intact (ux.md §12 check 6).
6. **Google Password Manager:** our CSV imports there, and the rows it skips match the summary card (check 7); the menu path to its export on this phone.
7. **Keyboards:** type the suggested passphrase with Gboard and with Samsung Keyboard; then the same words with spaces, to settle D-6b's separator.
8. **Languages:** read every screen in Uzbek Latin, Uzbek Cyrillic and Russian from the screenshots of the last wave, the warnings, PIN-limit texts and the "new master password" banner first (D-4); TalkBack on the PIN pad (E12's real part).
9. **Uninstall:** whether this phone asks to keep the app's data, and that after a reinstall the master password opens the kept vault and a PIN can be set again (D-21).
10. **A 16 KB-page phone**, if one is at hand: the release APK installs and unlocks [V-S9].
11. **Manufacturers' backup tools** (lock.md D6): a Xiaomi or a Samsung tool does not bring back an older PIN count.
12. **Two questions only the user can answer** (lock.md §18.7, §18.16): whether 24 hours with the phone switched on is the right wait for a new master password, and whether the app should post a notification during that wait.

### 8.7 Test vectors that are frozen

| Vector | Where | Used by |
|---|---|---|
| Argon2id, two cases | `Argon2KnownAnswerTest` (exists) | `crypto`, on the JVM and on the device |
| PIN message, `KEK_pin`, PIN wrap | lock.md §4.3 | `lock.guard`, `keystore` |
| Master-password bytes: apostrophes, NFC, Greek signs, white space, white space only | lock.md §4.3; 4.13 here | `crypto` |
| Vault file A (container) and B (canonical body) | 4.13 here; `core/src/test/resources/vectors/` | `vaultfile`, the device test of 8.3, the decrypt script |
| The blocklist over all 10⁶ PINs (one SHA-256) | lock.md appendix A | `lock.pin` |
| Search keys and ranking | the moved tests of `prototypes/search` | `search` |
| The import fixtures: the Chrome CSV and the Bitwarden JSON mock of formats.md §8.5, the Uzbek Cyrillic headers of formats-review P6 | `core/src/test/resources/formats/` (`SOURCES.md` names each origin and licence) | `formats` |
| The fields-block vectors of D-10a | formats.md §4.4 (the table of vectors) | `formats` |
| The review's attack inputs | formats-review.md, each finding's trigger (the files themselves were not rescued; `prototypes/formats/attack/` can write them again) | `formats` (the importer's tests, P 3.15) |

After the first release, a change to any of the first five rows is a new format version, never an edit. The prototype's sample exports in `tools/validate/samples/prototype/` are not frozen vectors: they predate D-10a and formats-review P9, and serve the checks of `tools/validate/`. The CSV, JSON and Excel files of 1.0 are written by the writers' test into `core/build/samples/` and checked there with `tools/validate/run.sh` (P 3.11).

---

## Appendix: the script behind 4.13

It writes both files of 4.13 into the current directory. Python 3 with `cryptography` (41.0.7 was used).

```python
import base64, hashlib, struct
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
b64 = lambda b: base64.b64encode(b).decode()
KEK = bytes.fromhex("853b272a44db1421c02962669a55eb0994f3cab385ed1c4c79253eee19bab49e")
VK, salt, slot_nonce = bytes(range(0xa0, 0xc0)), bytes(range(0x00, 0x10)), bytes(range(0x20, 0x2c))
wrapped = AESGCM(KEK).encrypt(slot_nonce, VK, None)

def seal(body, generation, created, body_nonce):
    plain = body + b' ' * (-len(body) % 4096)
    header = ('{"format":"passwordvault","version":1,"cipher":"AES-256-GCM",'
              '"vault_id":"00000000-0000-4000-8000-000000000001","vk_id":"00000000-0000-4000-8000-000000000002",'
              '"generation":%d,"created":%d,"generator":"PasswordVault 1.0","writer_version":1,"body_len":%d,'
              '"slots":[{"type":"password","kdf":"argon2id","argon2_version":19,"m_kib":65536,"t":3,"p":4,'
              '"salt":"%s","nonce":"%s","wrapped_key":"%s"}]}') % (
              generation, created, len(plain) + 16, b64(salt), b64(slot_nonce), b64(wrapped))
    aad = b"PVAULT01" + struct.pack(">I", len(header)) + header.encode()
    pre = aad + body_nonce + AESGCM(VK).encrypt(body_nonce, plain, aad)
    return pre + hashlib.sha256(pre).digest()

BODY_B = r'''{"schema":1,"entries":[{"id":"00000000-0000-4000-8000-000000000011","title":"Gmail – Oʻzbekiston Ўзбекистон","username":"aziz@example.com","password":"p\"ss\\w/rd-🔑","notes":"Birinchi qator\nИккинчи қатор\ttab\u0001","urls":["https://mail.google.com","https://accounts.google.com/a?b=c"],"fields":[{"name":"PIN code","value":"0042","secret":true},{"name":"Телефон","value":"+998 90 123-45-67","secret":false}],"favourite":true,"changePending":true,"checkIgnored":false,"createdAt":1791244800000,"updatedAt":1791331200000,"passwordChangedAt":1791331200000,"deletedAt":null,"pendingOldPassword":"eski-parol","passwordHistory":[{"password":"old2","replacedAt":1791331200000},{"password":"old1","replacedAt":1791288000000}],"futureEntryKey":"kept"}],"draft":{"entryId":null,"savedAt":1791331260000,"entry":{"title":"Yangi","username":"","password":"gen-🔑","notes":"","urls":[],"fields":[],"favourite":false}},"plainExports":[{"uri":"content://com.android.providers.downloads.documents/document/17","name":"passwords-2026-10-07.csv","savedAt":1791331300000}],"backups":[{"uri":"content://com.android.externalstorage.documents/document/primary%3ADownload%2FPasswordVault-backup-2026-10-07.pvault","name":"PasswordVault-backup-2026-10-07.pvault","savedAt":1791331400000}],"masterPasswordMatches":["00000000-0000-4000-8000-000000000011"],"securitySeenAt":1791331500000,"futureTopKey":{"n":1.50,"list":[true,null,"x"]}}'''.encode("utf-8")
a = seal(b'{"schema":1,"entries":[]}', 1, 1791244800000, bytes(range(0x30, 0x3c)))
b = seal(BODY_B, 7, 1791331200000, bytes(range(0x40, 0x4c)))
open("v1-container.pvault", "wb").write(a)
open("v1-body.pvault", "wb").write(b)
print(len(a), hashlib.sha256(a).hexdigest())     # 4667 3210ed2c…1d2d
print(len(b), hashlib.sha256(b).hexdigest())     # 4667 1bb8d230…12c7
```

---

## Review

On 2026-10-06 this file and build-plan.md ("P") were reviewed through two lenses: **coverage** (16 major and 11 minor findings, no critical one) and **buildability** (1 critical, 15 major, 6 minor). lock.md was audited and revised at the same time. Every finding was checked again before anything changed; where I ran something it is marked [X] above (the vectors with Python and the JDK, the fold order over every code point, kotlinx-serialization's escaping, digits under `fa-IR`, today's merged manifests, the Navigation 3 AARs, the emulator's port range, the clipboard lab's tables). All 49 findings hold in substance and are fixed; a few are fixed in another way than proposed, and two small parts were declined. "Changed" names where.

**Coverage review**

| # | Raised | What was done |
|---|---|---|
| 1 | An unreadable generation was not told apart from a damaged one; NOT OURS and over-long files undefined in a vault directory | Changed: four file states, never past an unreadable newer file, `.bad` only for files read in full, `read` returns cap + 1 bytes (3.3, 4.8, D-26); automatic writes wait for the older-copy notice (5.5); tests (8.1) |
| 2 | `L` and "verified" contradicted each other after a second unlock | Changed as proposed: `L` from the first unlock, `W` never verified (4.10); the test of 8.1 |
| 3 | No way to express the proof and the publish of creation, restore and a change | Changed: `stageVault`, `stageRekey`, `prove`, `publish`, `discard`, the salt read back (3.3); one save sequence with the proof before the rename, so no live generation carries an unproven slot (4.9); both typings compared first (4.6). The kill points are storage tests (8.1); lock.md's T15 is lock.md's, and its `mp_change` already covers a kill during a change |
| 4 | The draft had no path to disk at a lock | Changed: `flushDraft` and the order of 5.5 for every trigger, `Session.lockNow`; tests (8.2, P 3.2). **Declined:** adding the draft to lock.md I1's exceptions. The session makes the call, not the lock, so I1 and T17 hold as written |
| 5 | The whole-file vector could not come from a writer; no canonical body | Changed: the writer's canonical form, checked against kotlinx-serialization 1.11.0 (4.3); vector A kept as the container vector, vector B added with every key; both committed (4.13, P 2.2) |
| 6 | Two texts for `MasterPasswordBytes`; three Greek code points | Changed: 4.6 follows lock.md §8.1 as audited (NFC first), and the precedence names both texts. **Not the proposed order** (white space, apostrophes, NFC, apostrophes again): it gives the same bytes for every code point [X], and lock.md already has it. New vectors (4.13) |
| 7 | Vaults put aside, the device-key check, the alias list and the lock status had no path through the contracts | Changed: `VaultRef` and the aside calls, `KeyCreate`, `DeviceKeys`, `LockStatus`, the aside line on `LockScreen`, `openAside` and `deleteAside` through the lock's guard (3.3 to 3.5); owners P 3.3, 4.2, 5.2; U §12 check 16 at P's wave 5 |
| 8 | Window and input protections were never applied to the real app, and nothing failed a screen that skipped them | Changed: the hooks of 3.6 with owners, R-13, the window checks on API 28 and 37 at every integration check from P 3.12 on (8.4) |
| 9 | No `LocalClipboard` | Changed: D-25, refined so that reading still reaches the platform and paste keeps working |
| 10 | V-P7 had no home | Changed: the memory-limiter test (8.3, P 2.7), the keyboard after a rotation (5.4, P 3.3, 3.4, 3.6, 5.5) |
| 11 | "Own files only" against one navigation file per area | Changed: one route file per package, hook and module files (3.6), `:testing`, per-area drawables; P's "May also edit" lists |
| 12 | R-9 and the network scan could not pass | Changed: R-9 per variant, the debug receiver behind `DUMP` (7.2), the scan by class (8.5); checked against today's manifests [X] |
| 13 | A clear after a force-stop was required, and never happens | Changed: D-14 with the lab's numbers and both "never" cases, the clear at the next start, Help and 8.6; P 3.8's criteria. Copying the lab's files into `docs/lab/` is P 0.3's, since this revision could change only these two files |
| 14 | Format checks and fixtures existed only under `/tmp`; wave 4 removed what wave 5 ran | Changed in P: `tools/validate/` and the fixtures enter the repository in wave 0 (P 0.3); 8.7 points there |
| 15 | Wave 4's check and the Keystore package asked for later waves | Changed in P as proposed (P 3.9, 4.5, the checks of waves 4 to 6) |
| 16 | Packages would receive overruled text; no rule said which document wins | Changed: the precedence above, P's rule, and P 0.3, which edits ux.md, formats.md, search.md, i18n.md and the app's name in lock.md |
| 17 | Holes in the body rules (nine) | Changed, all nine (4.3, 4.4, the `TOO_LARGE` sentence in 3.5). Unknown keys below the two levels are dropped |
| 18 | A later framing could not be recognised; the generator string was unchecked | Changed: R1's `PVAULTnn` rule, which needs a good trailer so that one flipped bit cannot lock a vault as "newer"; the promise in 4.1; R-14 |
| 19 | Three backup and restore details | Changed: "may be damaged, or the password is wrong" (4.4); what a restore keeps (4.12), the draft included, unlike the proposal, so that a generated password is never lost; `sealBackup(proof)` |
| 20 | Defaults, one-time flags, two Settings rows, the reminder's data | Changed: `AppSettings` (3.5), D-7, the reminder from `backups` and `securitySeenAt` written only to clear a dot (4.3); owners P 2.9, 3.2, 4.3 |
| 21 | The purge ran on the wall clock | Changed: D-24 |
| 22 | Digits that depend on the locale | Changed: section 4's opening rule, R-11, a second test run under `fa-IR` (8.1) |
| 23 | Secrets in exception messages; logging rule only for the lock | Changed: 5.6, R-6 widened |
| 24 | No staging smoke in the middle waves | Changed: `smoke.sh` at every integration check (8.4) |
| 25 | The CSV fields block had no grammar | Changed: written out under D-10a |
| 26 | Five localisation loose ends | Changed: `EditTexts` (5.4), the list rebuilt on a language change (5.1), ICU on a device (8.3), library string overrides and a second screenshot round (P 6.3, 8.1) |
| 27 | Seven small inconsistencies | Changed, all seven: D-6a, D-23, R-14, `PinBlocklist` on bytes, R-11's additions, D-21 answered, the TEST BUILD line tied to the flag (7.2) |

**Buildability review**

| # | Raised | What was done |
|---|---|---|
| 1 (critical) | Wave 1 could not compile: the shell named types of its own and a later wave | Changed: P 1.1 holds the `:core` contracts only, with the early shapes of 3.7; the app's contracts and shell are P 2.5, after wave 1; section 3's rule that a signature names only earlier types |
| 2 | Devices not isolated, ports out of range, images under `/tmp`, no root on API 37 | Changed: P rule 8 and P 0.2 (ports 5556 to 5578 from a lock file, `-s` and `ANDROID_SERIAL`, a persistent base under `/home/asus/pvlab/`), SKIPPED steps on staging (8.4). **Declined:** "recovers after an adb server restart" as an acceptance test, because no agent may restart the adb server; it is a rule for the scripts instead |
| 3 | No SDK location in a new worktree, no enforced build limit, no named worktree root | Changed: `tools/gradle.sh` with `ANDROID_HOME`, two build slots and one retry; `/home/asus/pvwork/`; the build cache; sub-waves of at most six (P §1) |
| 4 | The seams for "own files only" were missing | Changed: 3.6 (`AppGraph`, the activity hooks, route files, debug commands), the factories, `Edited`, `SaveResult`, `OpenedVault`, the full `LockIntent` (3.2 to 3.5) |
| 5 | The fingerprint key could not be used before the prompt existed | Changed: `BiometricLauncher` declared by P 2.5; P 3.9 tests creation, deletion and classification; use is E6 |
| 6 | The validation scripts could not run from the repository | Changed: P 0.3, `tools/validate/` |
| 7 | The force-stop criterion | As coverage 13 |
| 8 | Tests placed where they cannot run | Changed: `:testing`, `DocRef`, the `Collation` interface with a device test, an `androidTest` directory for every UI package, `[JVM]`, `[device]` and `[script]` marks in P |
| 9 | R8 ran on a device too rarely; Navigation 3 ships no rules | Changed: 7.2 corrected [X], one keep rule, `smoke.sh` on API 24 and 37 at every integration check |
| 10 | The skeleton came late, in one large merge, without the risky paths | Changed: real adapters run on devices from wave 2; the skeleton check (P 3A) covers the draft across a lock, a language switch, kill and return, and staging. **Not as proposed:** the skeleton keeps the full screen packages rather than a "minimal setup and one-row list", so that no screen area has two owners; it comes after 25 packages, in five sub-waves of at most six |
| 11 | Wave 6 not disjoint; string names could collide | Changed: P 6A then 6B with a string freeze, area prefixes (P rule 5, 2.2 here), a literal-text rule (R-13), the language switch exercised from the skeleton on |
| 12 | Nobody owned integration | Changed: the integrator (P §1) |
| 13 | `tools/check.sh` failed on today's code | Changed: as coverage 12, comments ignored, the scans tried on `prototypes/` and the lab artifacts first (P 0.1) |
| 14 | Input protections only on API 37, with a helper that needs API 33 | Changed: P 3.12, the helper rebuilt with minSdk 26, API 28 and 37 (8.4) |
| 15 | Some packages too large | Changed: the shell apart from the contracts, the lock engine in four packages (P 3.10, 3.13, 3.14, 4.4), the devices apart from the build, the lock scripts after the screens, E12 on the accessibility tree, the strength word list given sources and a size, Uzbek splittable |
| 16 | Import fixes P2 to P4 tested where their triggers cannot reach | Changed: D-15, P 1.4 (primitives) and P 3.15 (end to end) |
| m1 | Wave 4 and 5 checks needed later waves; no hold point for F8's steps; Drive in a check | Changed: steps split by wave; F8's kill points left to T15 and the storage tests; Downloads in the check, Drive on the phone (8.6 item 4) |
| m2 | The second part of the lock engine did not own the files it wires into | Changed: "May also edit" in P 4.4 |
| m3 | Gradle tasks for classes of a later package, from a JVM-only script | Changed: the three programs run as tests behind `pv.*` properties (P 0.1, 1.2) |
| m4 | The frozen vectors never ran on a device | Changed: 8.3, P 2.7 |
| m5 | Fakes moved while another package used them; locking only at release | Changed: previews in `src/debug` from the start, fakes moved by the integrator when unused, locking and verification from wave 0 (7.2) |
| m6 | Moving inputs; files without an owner | Changed: P 0.3's freeze and the tag `inputs-frozen`; owners for drawables, themes, `docs/screens/`, the clipboard's strings, the util tests, the 50-account fixture and the first-copy flag |

**The lock.md audit.** It finished before this revision, and lock.md wins for the lock, so this file now follows it: no boot count (3.1); the record shapes of its §5 (3.7); `LockScreen.OlderPassword` with "the change did not finish", the fingerprint-only screen and the screen of a vault put aside (3.5); `availability()` once per visit off the main thread (3.4, 5.3); the canary made again (`DeviceKeys`); the storage requests of its §16.3, namely `replaceVault` with a `vault_id` the lock gives, vaults put aside opened in place under their own guard, `swapIn`, `mergeAside` and `deleteAside`, and `retireOldKeys` without `keepNewestOf` (3.3, 3.4, 4.9, 4.11); `.aside` generations only from an unfinished change (4.7); the fold order (4.6); R-6 as lock.md words it, then widened (5.6); the `backupCheck` build type for E15 and the `drop-keys` command (7.2, P 4.1); D-5 and D-21 updated, and lock.md §18.8 and §18.9 answered (section 6).

**Done since this revision** (wave 0, P 0.3). The clipboard lab's report and kit are in `docs/lab/clipboard.md` and `docs/lab/clipboard-artifacts/`; the format fixtures in `core/src/test/resources/formats/` (with `SOURCES.md`); the plain-export checks in `tools/validate/`; the vault vectors' reference copies in `docs/design/vectors/` and lock.md's audit scripts in `docs/design/lock-work/audit/`. ux.md, formats.md, search.md, i18n.md and lock.md were edited to follow this file and lock.md (formats.md §14 lists its changes). Nothing of the design lives under `/tmp` any more.
