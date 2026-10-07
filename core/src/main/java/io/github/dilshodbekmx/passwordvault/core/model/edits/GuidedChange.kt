package io.github.dilshodbekmx.passwordvault.core.model.edits

import io.github.dilshodbekmx.passwordvault.core.model.DraftEntry
import io.github.dilshodbekmx.passwordvault.core.model.Edited
import io.github.dilshodbekmx.passwordvault.core.model.Entry
import io.github.dilshodbekmx.passwordvault.core.model.EntryResult
import io.github.dilshodbekmx.passwordvault.core.model.EntryRules
import io.github.dilshodbekmx.passwordvault.core.model.Vault

/**
 * The guided change of one password (ux.md §5.17). Its state is in the entry: [Entry.changePending]
 * and [Entry.pendingOldPassword], the password the website has. That password is never overwritten
 * while a change is pending, so no number of retries can push it out (ux.md §12 check 5).
 */
internal object GuidedChange {
    /** "Create new password" and "Create another password": the same step, safe in either state. */
    fun newPassword(vault: Vault, nowMs: Long, id: String, password: String): Edited<EntryResult> {
        val index = Entries.live(vault, id)
        if (index < 0) return Edited(vault, EntryResult.NotFound)
        val problems = EntryRules.problems(DraftEntry(password = password))
        if (problems.isNotEmpty()) return Edited(vault, EntryResult.Refused(problems))
        val old = vault.entries[index]
        val entry = if (old.changePending) {
            if (password == old.password) return Edited(vault, EntryResult.Done(id))
            // A retry: the password that was tried may have been taken by the website after all.
            old.copy(
                password = password,
                passwordHistory = Entries.pushHistory(old.passwordHistory, old.password, nowMs),
                passwordChangedAt = nowMs,
                updatedAt = nowMs,
            )
        } else {
            // A start. A kept old password without a pending change (never written by this app) is
            // not dropped: it goes to the previous passwords.
            val stale = old.pendingOldPassword
            val history = if (stale != null && stale != old.password) {
                Entries.pushHistory(old.passwordHistory, stale, nowMs)
            } else {
                old.passwordHistory
            }
            old.copy(
                password = password,
                changePending = true,
                pendingOldPassword = old.password,
                passwordHistory = history,
                passwordChangedAt = if (password != old.password) nowMs else old.passwordChangedAt,
                updatedAt = nowMs,
            )
        }
        return saved(vault, index, entry, passwordChanged = password != old.password)
    }

    /** "Yes, it's changed". */
    fun confirm(vault: Vault, nowMs: Long, id: String): Edited<EntryResult> {
        val index = Entries.live(vault, id)
        if (index < 0) return Edited(vault, EntryResult.NotFound)
        val old = vault.entries[index]
        if (!old.changePending) return Edited(vault, EntryResult.NotPending)
        val websiteHad = old.pendingOldPassword
        val history = if (websiteHad != null && websiteHad != old.password) {
            Entries.pushHistory(old.passwordHistory, websiteHad, nowMs)
        } else {
            old.passwordHistory
        }
        val entry = old.copy(changePending = false, pendingOldPassword = null, passwordHistory = history, updatedAt = nowMs)
        return saved(vault, index, entry, passwordChanged = false)
    }

    /**
     * "No, put back the old password". A pending change without a kept password (never written by
     * this app) has nothing to put back: it just ends, with the password the entry has.
     */
    fun revert(vault: Vault, nowMs: Long, id: String): Edited<EntryResult> {
        val index = Entries.live(vault, id)
        if (index < 0) return Edited(vault, EntryResult.NotFound)
        val old = vault.entries[index]
        if (!old.changePending) return Edited(vault, EntryResult.NotPending)
        val websiteHas = old.pendingOldPassword
        val entry = if (websiteHas == null || websiteHas == old.password) {
            old.copy(changePending = false, pendingOldPassword = null, updatedAt = nowMs)
        } else {
            old.copy(
                password = websiteHas,
                changePending = false,
                pendingOldPassword = null,
                passwordHistory = Entries.pushHistory(old.passwordHistory, old.password, nowMs),
                passwordChangedAt = nowMs,
                updatedAt = nowMs,
            )
        }
        return saved(vault, index, entry, passwordChanged = entry.password != old.password)
    }

    private fun saved(vault: Vault, index: Int, entry: Entry, passwordChanged: Boolean): Edited<EntryResult> = Edited(
        vault.copy(
            entries = Entries.replacedAt(vault, index, entry),
            masterPasswordMatches = if (passwordChanged) {
                Entries.withoutMatch(vault.masterPasswordMatches, entry.id)
            } else {
                vault.masterPasswordMatches
            },
        ),
        EntryResult.Done(entry.id),
    )
}
