package io.github.dilshodbekmx.passwordvault.core.lock.record

/**
 * The files of the lock records (lock.md §5.2, §5.9; architecture.md §3.4): one directory per
 * vault, `<noBackupFilesDir>/lock/<vault_id>/`, holding `pin.rec`, `fingerprint.rec` and
 * `state.rec`. `OsFiles` (package 2.7) implements it with the routine of lock.md §5.9;
 * `FakeRecordFiles` of :testing (package 2.10) keeps "handed over" and "durable" apart.
 */
interface RecordFiles {
    /** The bytes of [name] in [dir], or null when there is no such file; any other error throws `IOException`. */
    fun read(dir: String, name: String): ByteArray?

    /**
     * Replaces [name] in [dir] with [bytes]: a temporary file, fsync, rename, then an fsync of the
     * directory. Any exception, a failed directory fsync included, means "not written" [V-S2].
     *
     * When [dir] does not exist it is made first, with any missing parent (`<noBackupFilesDir>/lock`),
     * and each directory made is fsynced into its parent before the record is written, so that the new
     * directory survives a power cut with its record. This is how the first record of a vault makes
     * its lock directory (lock.md F1 step 4, F11 step 17, F13 step 5, F14 step 2).
     */
    fun replace(dir: String, name: String, bytes: ByteArray)

    /** The paths of the directories in [root]: one per vault that has lock records. */
    fun dirs(root: String): List<String>

    /** Deletes [dir] with its records. Only after that vault's own files were deleted (lock.md F17 step 5). */
    fun deleteDir(dir: String)

    /** Deletes the stale `*.tmp` files under [root], at process start (lock.md §5.9, §5.11 step 1). */
    fun deleteStaleTmp(root: String)
}
