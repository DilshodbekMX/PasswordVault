package io.github.dilshodbekmx.passwordvault.core.lock.flow

import io.github.dilshodbekmx.passwordvault.core.lock.record.RecordRead
import io.github.dilshodbekmx.passwordvault.core.util.Uuid

/**
 * What process start does, in order (lock.md §5.11), under the lock mutex, before any lock screen is
 * drawn and before any flow can make a key:
 *
 * 1. Stale `*.tmp` files in the lock directories are deleted (§5.9).
 * 2. Every record of every `lock/<vault_id>/` directory is read, and storage is asked which vaults
 *    are on the phone: the current one and those put aside.
 * 3. **Replaced vaults** (F11, F16b), for every `state.rec` that reads OK and has `replaced` set,
 *    N being its `new_vault_id`. Skipped when storage could not say which vaults are there, and
 *    when the current vault cannot be read: its `vault_id` is then unknown, and a vault N that is
 *    there would be taken for one that is not (the third row would clear a mark that is right).
 *
 *    | This vault is | Vault N is | The lock |
 *    |---|---|---|
 *    | put aside | on the phone | removes this vault's two slots (`off = replaced`), sets `pending_mp = null`, deletes the two aliases; `replaced` stays |
 *    | current | on the phone, put aside | does the same, and sets `replaced = null` once neither slot can come back (the owner went back, F16b) |
 *    | either | not on the phone | sets `replaced = null`; slots, keys and `pending_mp` are kept (the replacement did not take place) |
 *
 *    A write that fails here is tried again at the next start; until then the app behaves as if it
 *    had succeeded, because the keepers hold the records the lock means to have on disk.
 * 4. The key sweep (§5.7, [Sweep]), over the records as they were read in step 2.
 * 5. The counter's start-up step (§6.3 S) for the current vault. A vault put aside gets it when its
 *    own lock screen is opened (F17 step 4).
 * 6. The credit (§6.9) of every `pending_mp` and every `replaced` is observed.
 *
 * Then the first screen is decided (§13.1): Welcome, "Your passwords can't be opened", or a lock
 * screen. A lock directory whose vault is no longer on the phone is left alone. A vault without a
 * lock directory reads as three MISSING records: the master-password screen titled "Welcome back"
 * (F14), and the unlock that follows writes its `state.rec` (`UnlockFlows`).
 *
 * Nothing here writes, renames or deletes vault data (I1): the vault port is only asked to list.
 */
internal class StartUp(private val core: LockCore) {

    /** Holds the mutex. Runs the six steps and shows the first screen. */
    fun run() {
        try {
            core.files.deleteStaleTmp(core.root) // step 1
        } catch (e: Exception) {
            // What stays is deleted at the next start; a `.tmp` file is never read. No failure here
            // may keep the lock screen from being drawn, so any is let go, not only an I/O error.
        }
        val dirs = readDirs() // step 2
        val scan = core.rescan()
        if (scan != null && scan.current !is CurrentVault.Unreadable) settleReplaced(scan) // step 3
        Sweep.run(dirs, core.deviceKeys) // step 4
        core.retarget() // step 5
        core.observeCredits() // step 6
        core.startVisit()
        core.showLockScreen()
        core.publishSummary()
    }

    /**
     * Step 2: the three records of every lock directory, as they are on disk now; null when the
     * directories cannot be listed, for whatever reason, so that the sweep deletes nothing.
     */
    private fun readDirs(): List<DirRecords>? {
        val paths = try {
            core.files.dirs(core.root)
        } catch (e: Exception) {
            return null
        }
        return paths.map { path ->
            val name = path.substring(path.lastIndexOf('/') + 1)
            if (!Uuid.isValid(name)) {
                DirRecords(null, RecordRead.Missing, RecordRead.Missing, RecordRead.Missing)
            } else {
                val lock = core.lockOf(name)
                DirRecords(name, lock.pinRead, lock.fingerprint.read, lock.stateRead)
            }
        }
    }

    /** Step 3: the table of the replaced vaults. */
    private fun settleReplaced(scan: VaultScan) {
        val currentId = (scan.current as? CurrentVault.Present)?.vaultId
        val asideIds = scan.aside.mapTo(HashSet()) { it.vaultId }
        for (lock in core.knownLocks()) {
            val replaced = lock.state?.replaced ?: continue
            val isCurrent = lock.vaultId == currentId
            if (!isCurrent && lock.vaultId !in asideIds) continue // its vault is no longer on the phone: left alone
            val n = replaced.newVaultId
            when {
                n != currentId && n !in asideIds -> lock.master.update { it.copy(replaced = null) } // the replacement did not take place
                isCurrent -> if (n in asideIds) removeShortcuts(lock, clearMark = true) // the owner went back, and F16b step 5 did not finish
                else -> removeShortcuts(lock, clearMark = false) // the replacement was carried out
            }
        }
    }

    /**
     * The two slots of a replaced vault go (`off = replaced`), each key at once (§5.7), and its
     * pending password with them (`VaultLock.removeShortcutsOfAReplacement`). With [clearMark] (row 2,
     * the owner went back) the mark goes only once neither slot can come back. A record that this
     * start could not read may still hold its slot, which nothing would remove once the mark was gone,
     * and the PIN or the finger of whoever replaced the master password would open these passwords
     * again: the mark stays, and the next start tries again (lock.md §5.11 step 3), as F16b step 5
     * keeps it (`OlderCopies`).
     */
    private fun removeShortcuts(lock: VaultLock, clearMark: Boolean) {
        val gone = lock.removeShortcutsOfAReplacement()
        val record = lock.state ?: return
        val clear = clearMark && gone && record.replaced != null
        if (record.pendingMp == null && !clear) return
        lock.master.update { it.copy(pendingMp = null, replaced = if (clear) null else it.replaced) }
    }
}
