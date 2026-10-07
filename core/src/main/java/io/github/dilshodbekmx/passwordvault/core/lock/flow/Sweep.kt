package io.github.dilshodbekmx.passwordvault.core.lock.flow

import io.github.dilshodbekmx.passwordvault.core.lock.guard.PinWrap
import io.github.dilshodbekmx.passwordvault.core.lock.record.RecordCodec
import io.github.dilshodbekmx.passwordvault.core.lock.record.RecordRead

/**
 * The key sweep of lock.md §5.7, step 4 of process start (§5.11): under the lock mutex, before any
 * flow can make a key, it deletes the Keystore keys that no lock record names.
 *
 * 1. Every record of every lock directory was read (step 2 of §5.11; [run] is given what was read
 *    then, before start-up changed anything).
 * 2. If any of them is DAMAGED, NEWER or UNREADABLE, nothing is deleted: a record that cannot be
 *    read may name a key, and a NEWER one belongs to a newer app version. The same when the lock
 *    directories could not be listed, or one of them is not a vault's.
 * 3. Each alias that starts with `pv_pin_` or `pv_fp_` and is named by no record is deleted.
 *
 * Why this shape (§5.7): a key that a record write has *replaced* (a new PIN, fingerprint turned on
 * again) is deleted only here, at a later start, after the replacing record was read back from
 * disk; deleted at once, a power cut that lost the record write would leave a record pointing at a
 * deleted key. `aliases()` is empty while the Keystore is failing, so a failing Keystore deletes
 * nothing. Errors from deleting are ignored: the next start tries again. `pv_canary` and the
 * fingerprint library's own `androidxBiometric` never start with either prefix, and are never
 * touched even if the Keystore lists them.
 */
internal object Sweep {
    /**
     * Runs the sweep over [dirs], the lock directories as read at this start (null: they could not
     * be listed), and returns the aliases it asked the Keystore to delete.
     */
    fun run(dirs: List<DirRecords>?, deviceKeys: DeviceKeys): List<String> {
        if (dirs == null) return emptyList()
        val named = HashSet<String>()
        for (dir in dirs) {
            if (dir.vaultId == null) return emptyList()
            if (blocks(dir.pin) || blocks(dir.fingerprint) || blocks(dir.state)) return emptyList()
            (dir.pin as? RecordRead.Ok)?.record?.slot?.let { named += PinWrap.alias(it.id) }
            (dir.fingerprint as? RecordRead.Ok)?.record?.slot?.let { named += FingerprintKeeper.alias(it.id) }
        }
        val aliases = try {
            deviceKeys.aliases()
        } catch (e: RuntimeException) {
            emptyList() // the Keystore is not answering
        }
        val deleted = ArrayList<String>()
        for (alias in aliases) {
            if (!isOurs(alias) || alias in named) continue
            try {
                deviceKeys.delete(alias)
                deleted += alias
            } catch (e: RuntimeException) {
                // Ignored; the next start tries again.
            }
        }
        return deleted
    }

    /** Whether [alias] is one the sweep may delete: `pv_pin_…` or `pv_fp_…`, nothing else. */
    fun isOurs(alias: String): Boolean =
        alias.startsWith(RecordCodec.PIN_ALIAS_PREFIX) || alias.startsWith(RecordCodec.FINGERPRINT_ALIAS_PREFIX)

    private fun blocks(read: RecordRead<*>): Boolean = when (read) {
        is RecordRead.Ok, RecordRead.Missing -> false
        is RecordRead.Damaged, RecordRead.Newer, RecordRead.Unreadable -> true
    }
}
