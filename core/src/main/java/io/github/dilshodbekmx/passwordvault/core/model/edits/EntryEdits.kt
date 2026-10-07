package io.github.dilshodbekmx.passwordvault.core.model.edits

import io.github.dilshodbekmx.passwordvault.core.model.DraftEntry
import io.github.dilshodbekmx.passwordvault.core.model.EditTexts
import io.github.dilshodbekmx.passwordvault.core.model.Edited
import io.github.dilshodbekmx.passwordvault.core.model.Entry
import io.github.dilshodbekmx.passwordvault.core.model.EntryResult
import io.github.dilshodbekmx.passwordvault.core.model.EntryRules
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.core.util.RandomSource

/** Add, Edit and the two marks of one entry (`VaultEdits.add`, `update`, `setFavourite`, `setCheckIgnored`). */
internal object EntryEdits {
    fun add(vault: Vault, nowMs: Long, form: DraftEntry, texts: EditTexts, random: RandomSource): Edited<EntryResult> {
        val problems = EntryRules.problems(form)
        if (problems.isNotEmpty()) return Edited(vault, EntryResult.Refused(problems))
        val ready = EntryRules.prepare(form)
        val id = Entries.newId(Entries.ids(vault), random)
        val entry = Entry(
            id = id,
            title = EntryRules.title(ready, nowMs, texts),
            username = ready.username,
            password = ready.password,
            notes = ready.notes,
            urls = ready.urls,
            fields = ready.fields,
            favourite = ready.favourite,
            createdAt = nowMs,
            updatedAt = nowMs,
            passwordChangedAt = if (ready.password.isEmpty()) null else nowMs,
        )
        // The draft of a new account is this form's: the newest form wins (ux.md §5.14).
        val draft = vault.draft?.takeUnless { it.entryId == null }
        return Edited(vault.copy(entries = vault.entries + entry, draft = draft), EntryResult.Done(id))
    }

    fun update(vault: Vault, nowMs: Long, id: String, form: DraftEntry, texts: EditTexts): Edited<EntryResult> {
        val index = Entries.live(vault, id)
        if (index < 0) return Edited(vault, EntryResult.NotFound)
        val problems = EntryRules.problems(form)
        if (problems.isNotEmpty()) return Edited(vault, EntryResult.Refused(problems))
        val ready = EntryRules.prepare(form)
        val old = vault.entries[index]
        val passwordChanged = ready.password != old.password
        var entry = old.copy(
            title = EntryRules.title(ready, nowMs, texts),
            username = ready.username,
            password = ready.password,
            notes = ready.notes,
            urls = ready.urls,
            fields = ready.fields,
            favourite = ready.favourite,
            passwordHistory = if (passwordChanged) Entries.pushHistory(old.passwordHistory, old.password, nowMs) else old.passwordHistory,
            passwordChangedAt = if (passwordChanged) nowMs else old.passwordChangedAt,
        )
        val entryChanged = entry != old
        if (entryChanged) entry = entry.copy(updatedAt = nowMs)
        val draftDone = vault.draft?.entryId == id
        if (!entryChanged && !draftDone) return Edited(vault, EntryResult.Done(id))
        return Edited(
            vault.copy(
                entries = if (entryChanged) Entries.replacedAt(vault, index, entry) else vault.entries,
                draft = if (draftDone) null else vault.draft,
                masterPasswordMatches = if (passwordChanged) {
                    Entries.withoutMatch(vault.masterPasswordMatches, id)
                } else {
                    vault.masterPasswordMatches
                },
            ),
            EntryResult.Done(id),
        )
    }

    /** A change of the entry's marks only ([change] touches no text and no time). */
    fun mark(vault: Vault, nowMs: Long, id: String, change: (Entry) -> Entry): Edited<EntryResult> {
        val index = Entries.live(vault, id)
        if (index < 0) return Edited(vault, EntryResult.NotFound)
        val old = vault.entries[index]
        val marked = change(old)
        if (marked == old) return Edited(vault, EntryResult.Done(id))
        return Edited(vault.copy(entries = Entries.replacedAt(vault, index, marked.copy(updatedAt = nowMs))), EntryResult.Done(id))
    }
}
