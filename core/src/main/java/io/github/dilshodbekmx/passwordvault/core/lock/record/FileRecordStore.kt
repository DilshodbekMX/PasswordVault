package io.github.dilshodbekmx.passwordvault.core.lock.record

import io.github.dilshodbekmx.passwordvault.core.util.Uuid

/**
 * One record of one vault, [RecordCodec] over [RecordFiles] (architecture.md §3.4): `pin.rec`,
 * `fingerprint.rec` or `state.rec` in the vault's lock directory [dir],
 * `<noBackupFilesDir>/lock/<vault_id>/` (lock.md §5.2). The caller holds the lock mutex
 * (architecture.md §5.2) and runs it on `Dispatchers.IO`.
 *
 * [read] gives the five results of lock.md §5.3: no file is MISSING; any failure to read the file
 * is UNREADABLE (it may read fine next time, so it is not rewritten automatically); the codec says
 * OK, DAMAGED or NEWER. The record's `vault_id` must be [vaultId], the last element of [dir].
 *
 * [write] replaces the whole record by the routine of lock.md §5.9 ([RecordFiles.replace]). Any
 * exception means "not written" (§5.9); the lock controller keeps the record it means to have on
 * disk in memory, and the next write that succeeds makes it good (lock.md §6.3).
 */
class FileRecordStore<T>(
    private val files: RecordFiles,
    /** The vault's lock directory, `<root>/<vault_id>`. */
    val dir: String,
    /** Which of the three records this is. */
    val format: RecordCodec.Format<T>,
) : RecordStore<T> {
    /** The vault whose record this is: the name of [dir], which every record in it must carry as `vault_id`. */
    val vaultId: String = dir.substring(dir.lastIndexOf('/') + 1)

    /**
     * The record as it is on disk now: OK, MISSING, DAMAGED, NEWER or UNREADABLE (lock.md §5.3).
     * Never throws an `Exception`.
     */
    override fun read(): RecordRead<T> {
        val bytes = try {
            files.read(dir, format.fileName)
        } catch (_: Exception) { // any I/O error, and anything else the file layer throws: it may read fine next time
            return RecordRead.Unreadable
        } ?: return RecordRead.Missing
        return format.decode(bytes, vaultId)
    }

    /**
     * Replaces the record with [record].
     *
     * @throws java.io.IOException when the record was not written; after some failures (a failed
     *   directory fsync) the new record may still be read back, which is why the controller writes
     *   the whole record it means to have every time.
     * @throws IllegalArgumentException when [record] belongs to another vault or breaks a rule of
     *   lock.md §5.4 to §5.6 ([RecordCodec.Format.brokenRule]): a bug of the caller. Nothing was
     *   written.
     */
    override fun write(record: T) {
        require(format.vaultIdOf(record) == vaultId) { "the ${format.kind} record of another vault" }
        files.replace(dir, format.fileName, format.encode(record))
    }

    override fun toString(): String = "FileRecordStore(${format.fileName} of $vaultId)"

    companion object {
        /**
         * The lock directory of the vault [vaultId] under [root] (`<noBackupFilesDir>/lock`). The id
         * becomes a directory name, so it must be a lower-case UUID of version 4 (lock.md §5.1).
         *
         * @throws IllegalArgumentException when it is not; the message holds no input.
         */
        fun dir(root: String, vaultId: String): String {
            require(Uuid.isValid(vaultId)) { "a vault_id must be a lower-case UUID of version 4" }
            return "$root/$vaultId"
        }

        /** `pin.rec` of the vault [vaultId] under [root]. */
        fun pin(files: RecordFiles, root: String, vaultId: String): FileRecordStore<PinRecord> =
            FileRecordStore(files, dir(root, vaultId), RecordCodec.Pin)

        /** `fingerprint.rec` of the vault [vaultId] under [root]. */
        fun fingerprint(files: RecordFiles, root: String, vaultId: String): FileRecordStore<FingerprintRecord> =
            FileRecordStore(files, dir(root, vaultId), RecordCodec.Fingerprint)

        /** `state.rec` of the vault [vaultId] under [root]. */
        fun state(files: RecordFiles, root: String, vaultId: String): FileRecordStore<StateRecord> =
            FileRecordStore(files, dir(root, vaultId), RecordCodec.State)
    }
}
