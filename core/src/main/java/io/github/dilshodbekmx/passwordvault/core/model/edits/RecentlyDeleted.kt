package io.github.dilshodbekmx.passwordvault.core.model.edits

import io.github.dilshodbekmx.passwordvault.core.model.Edited
import io.github.dilshodbekmx.passwordvault.core.model.Entry
import io.github.dilshodbekmx.passwordvault.core.model.EntryResult
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.core.model.VaultEdits

/** Delete, restore, delete forever, and the purge of D-24 (ux.md §5.23). */
internal object RecentlyDeleted {
    fun delete(vault: Vault, nowMs: Long, id: String): Edited<EntryResult> {
        val index = Entries.anywhere(vault, id)
        if (index < 0) return Edited(vault, EntryResult.NotFound)
        val old = vault.entries[index]
        if (old.deletedAt != null) return Edited(vault, EntryResult.Done(id))
        val entry = old.copy(deletedAt = nowMs, updatedAt = nowMs)
        return Edited(vault.copy(entries = Entries.replacedAt(vault, index, entry)), EntryResult.Done(id))
    }

    fun restore(vault: Vault, nowMs: Long, id: String): Edited<EntryResult> {
        val index = Entries.anywhere(vault, id)
        if (index < 0) return Edited(vault, EntryResult.NotFound)
        val old = vault.entries[index]
        if (old.deletedAt == null) return Edited(vault, EntryResult.Done(id))
        val entry = old.copy(deletedAt = null, updatedAt = nowMs)
        return Edited(vault.copy(entries = Entries.replacedAt(vault, index, entry)), EntryResult.Done(id))
    }

    fun deleteForever(vault: Vault, id: String): Edited<EntryResult> {
        val index = vault.entries.indexOfFirst { it.id == id && it.deletedAt != null }
        if (index < 0) return Edited(vault, EntryResult.NotFound)
        val entries = vault.entries.filterIndexed { i, _ -> i != index }
        return Edited(removed(vault, entries), EntryResult.Done(id))
    }

    fun empty(vault: Vault): Edited<Int> = removeWhere(vault) { it.deletedAt != null }

    fun purge(vault: Vault, armedAtMs: Long): Edited<Int> = removeWhere(vault) { isDue(it, armedAtMs) }

    /**
     * In Recently deleted since [VaultEdits.PURGE_AFTER_MS] or more before [atMs]. Times from a file
     * can be any 64-bit value, so the subtraction stops at `Long.MIN_VALUE` instead of wrapping.
     */
    fun isDue(entry: Entry, atMs: Long): Boolean {
        val deletedAt = entry.deletedAt ?: return false
        val cutoff = if (atMs < Long.MIN_VALUE + VaultEdits.PURGE_AFTER_MS) Long.MIN_VALUE else atMs - VaultEdits.PURGE_AFTER_MS
        return deletedAt <= cutoff
    }

    private fun removeWhere(vault: Vault, gone: (Entry) -> Boolean): Edited<Int> {
        val kept = vault.entries.filterNot(gone)
        val count = vault.entries.size - kept.size
        if (count == 0) return Edited(vault, 0)
        return Edited(removed(vault, kept), count)
    }

    /** The vault with only [kept]; the master-password list loses the ids that went with the rest. */
    private fun removed(vault: Vault, kept: List<Entry>): Vault =
        vault.copy(entries = kept, masterPasswordMatches = Entries.onlyOf(vault.masterPasswordMatches, kept))
}
