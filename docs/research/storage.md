# How the vault should be stored on disk

Research date 2026-10-06. Provenance tags: **[C]** confirmed today from the named primary source (fetched by me, or taken from this morning's saved fetches where marked "saved"); **[M]** from memory; **[I]** my inference. My downloads are under `scratchpad/storage/` (`src/`, `aar/`, `web/`, `bench/`).

## 1. Recommendation

**Use B for version 1.** One self-describing vault file: a JSON payload (kotlinx-serialization-json 1.11.0) sealed with a single AEAD under a random data key, which the Argon2-derived key wraps. Each save writes a *new generation file* through our own write, fsync, rename, fsync-directory routine. Keep the 5 newest generations plus one per day for 14 days. No Room, SQLCipher or KSP in v1.

Findings that drive this, and would change it:

1. **A's stack is mid-transition on both sides.** Room 3.0.0 went stable on 2026-07-01 and "no longer references SupportSQLite types"; Room 2.x is in maintenance, with patches promised only "until Room 3 becomes stable" [C: Room 3 release notes; Android Developers Blog, 2026-03-13]. SQLCipher's Room 3 driver first shipped in 4.18.0 (2026-08-18) and needed a contract fix in 4.19.1 (2026-09-29, issue #93) [C: releases.atom; issue saved]. SQLCipher 5.0.0-beta (2026-09-15) changes the default format to AES-256-GCM with 8192-byte pages and is "NOT COMPATIBLE with SQLCipher 4 and earlier by default" [C: zetetic.net]. All seven apps I inspected are still on Room 2.x. A today means the maintenance line or a seven-week-old integration, and later a format migration of the user's only copy.
2. **`AtomicFile` is not good enough for a vault.** `finishWrite()` only logs when fsync or rename fails, and never syncs the directory [C: source, section 5.3]. Aegis relies on it.
3. **Jetpack's encrypted options are out.** security-crypto is fully deprecated, datastore-tink has no stable release, and the documented recipe for each roots the key in Android Keystore [C: security-crypto 1.1.0 source; DataStore 1.3.0-alpha07 notes, saved], so the vault could not be restored on another phone.
4. **B is not safer than A at the engine level.** SQLite's commit is far better tested than anything we will write. B wins on everything around the engine. It stops being right once we add a second process (an autofill service in its own process), attachments or sync.

**Main risk:** a bug of ours writes a bad or empty vault, and one save replaces everything. **Mitigation:** generations, verify before commit, never save an unloaded vault, refuse newer major versions, golden-file tests, verified export.

## 2. Today's facts

| | A: Room + SQLCipher | B: sealed file | C: KDBX library | E: Jetpack |
|---|---|---|---|---|
| Latest stable | sqlcipher-android 4.19.1 (2026-09-29); Room 3.0.3 and 2.8.5 (both 2026-09-09); androidx.sqlite 2.7.1 | kotlinx-serialization-json 1.11.0 (2026-04-09) | kotpass 0.13.0 (2025-09-28); KeePassJava2 3.1.0 (2026-10-05) | security-crypto 1.1.0 (2025-07-30); DataStore 1.2.1 (2026-03-11); datastore-tink 1.3.0-alpha11 only |
| Status | Active; v5 beta is "not in production" | JSON is the only stable kotlinx format | kotpass: one visible maintainer, 36 stars, calls it "feature complete" (issue #42). KeePassJava2 needs Java 17, Jackson XML, Guava, BouncyCastle. KeePassDX's module is GPL-3.0 and not on Maven Central | "Deprecated all APIs" since 1.1.0-alpha07 (2025-04-09); `EncryptedFile` cannot overwrite a file |
| Licence | BSD-style; attribution in "a user accessible location". Room: Apache-2.0 | Apache-2.0 | MIT; Apache-2.0 | Apache-2.0 |
| Native code | `libsqlcipher.so` for 4 ABIs, 1.05 to 2.24 MB each (arm64 2.10 MB, 1.03 MB compressed). Every LOAD segment has `p_align 0x4000`, so 16 KB-aligned. minSdk 23 | none | none (pure-Kotlin Argon2, replaceable through `KdfProvider`) | DataStore ships an aligned 8 to 10 KB `.so` |
| Key supply | `byte[]` goes straight to `sqlite3_key`. Unless shaped `x'<64 hex>'`, SQLCipher runs PBKDF2-HMAC-SHA512 x 256,000 on it | Argon2id key unwraps a random data key held in the header | KDF parameters in a plaintext header | Tink keyset wrapped by an `android-keystore://` key |
| Leaks if copied | size in 4096-byte pages; 16-byte salt; which pages changed between two copies [I]; `-wal` and `-shm` files (Room defaults to WAL); page data encrypted, journal header not; temp store in memory (`SQLITE_TEMP_STORE=2`) | size (pad it), KDF parameters, generation numbers and times; old generations still hold deleted entries [I] | cipher, KDF parameters, seed, compressed size | size; keyset in SharedPreferences |

D (plain Room, encrypted fields) shares A's Room facts and leaks most: table and column names, row counts, timestamps, field lengths [I].

Sources [C]: Maven metadata and `Last-Modified` headers; `readelf -lW` on the 4.19.1 AAR (re-run by me); zetetic.net `/sqlcipher/license/`, `/design/`, `/sqlcipher-api/`; `android_database_SQLiteConnection.cpp` and `sqlcipher.c` v4.19.0; security-crypto 1.1.0 sources; `keepass.info/help/kb/kdbx.html`, kotpass and KeePassJava2 repositories (saved). Google Play blocks updates without 16 KB support from 2027-02-01 for apps targeting API 35+ [C: developer.android.com/guide/practices/page-sizes, saved].

**Direct answers.** Current stable Room is 3.0.3 (`androidx.room3`); 2.8.5 is the maintenance line. SQLCipher supports both: `net.zetetic.database.sqlcipher.driver.SQLCipherDriver implements SQLiteDriver` for Room 3, and `SupportOpenHelperFactory` for Room 2 [C: source, README]. With our Argon2 step, A needs the 32-byte result passed as the 67 ASCII bytes `x'...'`, plus a sidecar file for the Argon2 salt and parameters [I]. So A does not remove the small file that must be written atomically. `SQLCipherDriver` holds the passphrase in a final field while the database is open [C: source].

## 3. Judgement

| Criterion | A | B | C | D | E |
|---|---|---|---|---|---|
| Security at rest | Strong (per-page HMAC) | Strong; one tag covers the whole vault | Strong | Weakest: no whole-vault integrity | Device-bound key |
| Easy to get right and audit | Subtle: raw-key syntax, hooks against the connection pool (issue #85), sidecar | About 400 lines of ours on standard primitives [I] | Must preserve fields we never show | Most crypto code | Wrong key model |
| Crash mid-write | Best (journal or WAL) | Good if section 5.2 is implemented and fault-tested | As B; the format does not help | Best | DataStore 1.2.1: temp, fsync, move, throws on failure, no directory sync |
| Corruption | Looks the same as a wrong key ("file is not a database", code 26). The default handler deletes nothing because "It may be that the user has specified an incorrect password" | Checksum separates damage from a wrong password; fall back one generation | Header SHA-256 | Framework default "will delete the database file" | A replace-on-corruption handler would silently reset it [I] |
| Failed migration | Missing migration throws at open [M]; in-place change of the only copy | Pure function in memory; older generations untouched | Fixed format | As A | n/a |
| Backup and export | Close or checkpoint first; a copied `.db` needs its `-wal` | Copy one file | Copy one file; opens in KeePassXC | As A | Not portable |
| Search and list | SQL, FTS5 compiled in; more than we need | In memory; trivial at 5,000 entries [I] | In memory | SQL over plaintext columns only | In memory |
| Build and size | KSP, Room plugin, 4.0 MB AAR, `loadLibrary` | One compiler plugin; 0.7 MB of jars before R8 | 0.5 MB jar plus okio | KSP, Room | Tink jar 3.3 MB before R8 |
| Dependency abandoned | Readable by any SQLCipher 4 build, but forced events keep coming (v5, Room 3) | Documented format; a short script decrypts it, as Aegis's `docs/decrypt.py` does | Other KeePass apps open it | Room 3 move | Already deprecated or alpha |

Sources [C]: `DefaultDatabaseErrorHandler.java` (sqlcipher-android); `SupportSQLiteOpenHelper.android.kt` (androidx.sqlite 2.7.1); DataStore 1.2.1 `FileStorage.kt`, which carries `TODO(b/151635324): fsync the directory`; sqlite.org/howtocorrupt.html (saved).

**Who uses what** [C: build files and sources at HEAD, saved]. Room versions: Bitwarden 2.8.4, Proton Pass 2.7.2, Passbolt 2.8.4, 2FAS Pass 2.8.4, KeyGuard 2.8.5, Aegis 2.7.1, KeePassDX 2.6.1. A: KeyGuard (SQLDelight 2.4.0, sqlcipher 4.19.0), Passbolt (Room 2.8.4, sqlcipher 4.18.0), KeyPass (sqlcipher 4.7.2), and Proton Pass for its search index only. The open calls I read (KeyGuard, Proton Pass, KeyPass) all use `SupportOpenHelperFactory`, the Room 2 path. B: Aegis 3.4.3. C: KeePassDX 4.5.5; Keepass2Android and AuthPass [M]. D: Bitwarden (Room 2.8.4, column `cipher_json`) and Proton Pass (Room 2.7.2, `encrypted_content`); both call `fallbackToDestructiveMigration`; they can re-sync from a server, we cannot [I]. E: Bitwarden keeps tokens in `EncryptedSharedPreferences` behind `destroyEncryptedSharedPreferencesAndRebuild()`, a wipe-and-rebuild path for when the Keystore-wrapped keyset cannot be read.

## 4. Attacking B

| Attack | Verdict |
|---|---|
| Size | Survives. On this desktop (i9-14900K, NVMe ext4, JDK 25) serialise, AES-256-GCM, write, fsync, rename and directory fsync took a median 12 ms at 300 entries (118 KB), 18 ms at 1,000, 37 ms at 5,000 (1.96 MB), 57 ms at 20,000 [C: `bench/VaultBench.java`]. A phone may be ten times slower [I]. |
| Memory | Survives without attachments: roughly 3 to 5 times the plaintext during a save [I]. |
| Partial writes | Survives. A half-truncated file and a single flipped bit were both rejected with `AEADBadTagException` [C: same benchmark]. |
| Concurrent writes | **Breaks** with two writers or two processes: last writer wins, no locking. Needs one in-process owner behind a `Mutex`. |
| Bug blast radius | **Weakest point.** Zero-byte saves cost KeePassDX users whole databases (issues #1594, #1620, #1680; 4.0.7 changelog: "Prevent 0 Byte file with cache during a save exception") [C: saved]. |
| Home-grown format | Mistakes become permanent. Aegis still carries `ui/tasks/PasswordSlotDecryptTask.java:57`: "a bug introduced in afb9e59 caused passwords longer than 64 bytes to produce a different key than before" [C]. |

**Safer than A for one developer?** Yes for this app, but not because of the engine. In 2026 alone A's seam with Room produced `AbstractMethodError` crashes (#81, #89), an unresolved report of a four-times-slower first unlock (#91) and the Room 3 driver bug (#93) [C: github.com/sqlcipher/sqlcipher-android/issues]. B has no such seam.

## 5. Specification for B

### 5.1 Format and evolution

```
"PWV1" | major u16 | headerLen u32 | header JSON | AES-256-GCM ciphertext+tag | SHA-256(all bytes before)
```

- Header: generation number, KDF block, wrapped data key (a "slot", as in Aegis), payload nonce, padding size. The whole header is the AEAD associated data.
- The unkeyed SHA-256 trailer detects truncation before a password is asked for. KDBX's header hash has the same job [C: kdbx.html, saved].
- Payload: `{"schema":1,"entries":[...]}`, padded to 4 KiB.

Rules:

1. Additive changes only within a major. New keys are optional with defaults. Never rename, retype or reuse a key.
2. Preserve unknown keys. `ignoreUnknownKeys = true` discards them, so the next save loses them. Parse to `JsonObject`, map known keys by hand, keep the rest in an `extras` object per entry and at top level, and write them back. The kotlinx guide's "Maintaining custom JSON attributes" shows the pattern [C: `docs/json.md`].
3. A change old apps must not ignore bumps `major`. A reader that sees a higher major refuses to open and never writes. Aegis: `if (obj.getInt("version") > VERSION) { throw new VaultFileException("unsupported version"); }` (`vault/VaultFile.java:64`).
4. Migrations are pure functions on the parsed tree at load. Pin the last pre-upgrade generation.
5. Not protobuf: kotlinx ProtoBuf is "experimental" and keeps unknown fields only from 1.12.0-RC [C: `CHANGELOG.md`, `docs/formats.md`].

### 5.2 Write procedure

Files are `filesDir/vault/vault-0000000057.pwv`. The target name never exists beforehand, so no good file is ever replaced.

```kotlin
// Caller holds the vault Mutex. bytes = sealed file, already re-opened and compared in memory.
fun commit(dir: File, gen: Long, bytes: ByteArray) {
    val name = "vault-%010d".format(Locale.ROOT, gen)   // ROOT: %d digits are locale-sensitive [M]
    val tmp = File(dir, "$name.tmp")
    val dst = File(dir, "$name.pwv")
    try {
        FileOutputStream(tmp).use { it.write(bytes); it.flush(); it.fd.sync() }
        Os.rename(tmp.path, dst.path)                     // throws ErrnoException
    } catch (e: Exception) { tmp.delete(); throw e }
    try {
        val fd = Os.open(dir.path, OsConstants.O_RDONLY, 0)
        try { Os.fsync(fd) } finally { Os.close(fd) }
    } catch (e: ErrnoException) { /* committed; durability unconfirmed: log it */ }
}
```

Then publish the new state and prune. `Os.open`, `Os.fsync` and `Os.rename` exist since API 21 and throw `ErrnoException` [C: developer.android.com/reference/android/system/Os]. `java.nio.file.Files` is unusable at minSdk 24: DataStore guards it with `@RequiresApi(26)` [C].

**Does the directory need an fsync? Yes.** fsync(2): "Calling fsync() does not necessarily ensure that the entry in the directory containing the file has also reached disk. For that an explicit fsync() on a file descriptor for the directory is also needed." rename(2) promises only that an existing target is "atomically replaced". f2fs defaults to `fsync_mode=posix`, a "light operation" [C: man7.org; kernel `Documentation/filesystems/f2fs.rst`]. SQLite syncs its journal's directory as well [C: sqlite.org/atomiccommit.html].

### 5.3 What `AtomicFile` actually guarantees

Read from `androidx/core/util/AtomicFile.java` in core 1.19.1. It is identical to androidx-main, and to 1.16.0 (the version Aegis uses) apart from one comment word [C: diff].

- Guaranteed: writes go to `<name>.new`; a reader of `<name>` sees the old or the new complete file; `openRead()` deletes a stale `.new`.
- Not guaranteed: error reporting. `if (!sync(str)) { Log.e(LOG_TAG, "Failed to sync file output stream"); }` is followed by the rename regardless. `rename()` itself only logs `"Failed to rename "`. `finishWrite` returns `void`.
- Not guaranteed: durability of the rename (no directory sync), or locking ("does not confer any file locking semantics").
- `android.util.AtomicFile` differs by OS release. Up to Android 10 it renames the base file to `.bak` and writes in place; from Android 11 it uses `.new` [C: AOSP tags android-7.0.0_r1, android-10.0.0_r1, android-11.0.0_r1].

Verdict: use neither. DataStore core 1.2.1 is the better ready-made writer because it throws on failure, but it replaces the one file in place and keeps no previous copy.

### 5.4 Generations, detection and recovery

- Keep the 5 newest generations plus the newest of each of the last 14 days: at most 19 files, about 7.5 MB at 1,000 entries [I].
- Open: delete `*.tmp`, then take the newest file whose trailer verifies.
  - Trailer fails: damaged. Try the next generation and tell the user which date they are back to.
  - Trailer passes but the data key will not unwrap: wrong password.
  - Key unwraps but the payload tag fails: damaged. Try the next.
  - Never delete a file that failed.
- Guards copied from KeePassXC's `Database::saveAs` (`src/core/Database.cpp`): "Disallow overlapping save operations" and "Never save an uninitialized database" [C: saved].
- Export is a copy of the newest generation through the system file picker, re-read and checksummed before success is reported. Import reads the same file.

### 5.5 How Aegis does each

Paths are under `app/src/main/java/com/beemdevelopment/aegis/`, v3.4.3 [C: source].

| Topic | Aegis |
|---|---|
| Serialisation | `org.json`. `vault/VaultFile.java:55`: `obj.toString(4)`. Outer object `{version, header, db}`, with `db` as Base64 AES-256-GCM. Optional fields are read with defaults (`obj.optString("note", "")`). `VaultEntry.toJson()` rebuilds from fields, so unknown keys are lost; the version gate is the only protection |
| Atomic write | `vault/VaultRepository.java:76-90`: `outStream = file.startWrite(); IOUtils.copy(inStream, outStream); file.finishWrite(outStream);` on `filesDir/aegis.json`. Saves run synchronously from the activity (`ui/AegisActivity.java:139`) |
| Rolling backups | `vault/VaultManager.java:139-185` exports to a cache file. `vault/VaultBackupManager.java:110-190` creates `aegis-backup-yyyyMMdd-HHmmss.json` in a user-chosen folder, refuses to overwrite, then `enforceVersioning` deletes the oldest. Default 5 versions, but backups are off by default (`Preferences.java:401`, `:469`) |
| Internal previous copy | None |
| Corrupt file | `ui/MainActivity.java:950-961`: error dialog, then `finish()`. No automatic recovery |
| Android backup | `AegisBackupAgent.java:73-92` stages `files/backup/aegis.json`; opt-in |

## Lessons for our app

- Seal the whole file and re-open it in memory before touching the disk.
- Write a new generation; never overwrite the last good file. Make every failure throw.
- Keep internal generations on by default. Aegis's backups are opt-in and external.
- Refuse newer major versions; preserve unknown keys.
- Do not make Android Keystore the only root of the vault key. Use it for an optional biometric slot.
- Fix the password-to-bytes encoding on day one and test it with long and non-ASCII vectors.
- Never copy `fallbackToDestructiveMigration` or a replace-on-corruption handler into a local-only app.
- One process, one owner, one `Mutex`. Reconsider A when that stops being true.

## Not settled

- `Os.fsync` on a directory descriptor inside `filesDir` is untested on API 24 and API 37, because I was told not to start the emulator. The sketch treats failure as non-fatal.
- After a master-password change, older generations still open only with the old password. Re-wrap or delete them once the new file is verified.
- The retention numbers (5 plus 14 daily) and the 4 KiB padding are my proposals.
- Whether Android Auto Backup should include the vault directory.
- KDBX export through kotpass as a later exit path is untested; CSV export is the simpler first exit.
- When SQLCipher 5 becomes final, and whether Room 2.x gets patches after 2.8.5.
- Passbolt's and 2FAS Pass's database open calls were not read, only their version catalogs.
