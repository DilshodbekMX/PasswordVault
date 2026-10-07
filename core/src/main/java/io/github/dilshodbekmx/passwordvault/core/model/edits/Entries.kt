package io.github.dilshodbekmx.passwordvault.core.model.edits

import io.github.dilshodbekmx.passwordvault.core.model.CustomField
import io.github.dilshodbekmx.passwordvault.core.model.Draft
import io.github.dilshodbekmx.passwordvault.core.model.DraftEntry
import io.github.dilshodbekmx.passwordvault.core.model.Entry
import io.github.dilshodbekmx.passwordvault.core.model.EntryRules
import io.github.dilshodbekmx.passwordvault.core.model.HistoryItem
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.core.util.RandomSource
import io.github.dilshodbekmx.passwordvault.core.util.TextHygiene
import io.github.dilshodbekmx.passwordvault.core.util.Uuid

/** The small steps the edits of `VaultEdits` share. */
internal object Entries {
    /** Draws for a free id at most this often; a working `RandomSource` needs one draw. */
    private const val ID_DRAWS = 100

    /** The position of entry [id] outside Recently deleted, or -1. */
    fun live(vault: Vault, id: String): Int = vault.entries.indexOfFirst { it.id == id && it.deletedAt == null }

    /** The position of entry [id], deleted or not, or -1. */
    fun anywhere(vault: Vault, id: String): Int = vault.entries.indexOfFirst { it.id == id }

    /** [vault]'s entries with the one at [index] replaced by [entry]. */
    fun replacedAt(vault: Vault, index: Int, entry: Entry): List<Entry> {
        val list = vault.entries.toMutableList()
        list[index] = entry
        return list
    }

    /** The ids of every entry, deleted ones included. */
    fun ids(vault: Vault): HashSet<String> = vault.entries.mapTo(HashSet(vault.entries.size * 2)) { it.id }

    /**
     * A new entry id: a valid UUID from [random] that is not in [taken]. Never two entries with one
     * id, whatever the random source returns.
     *
     * @throws IllegalStateException when [random] gives no free id in [ID_DRAWS] draws, which a
     *   working source never does; the message holds no input.
     */
    fun newId(taken: Set<String>, random: RandomSource): String {
        repeat(ID_DRAWS) {
            val id = random.uuid()
            if (Uuid.isValid(id) && id !in taken) return id
        }
        throw IllegalStateException("no free entry id")
    }

    /**
     * [password] put in front of [history] as replaced at [atMs], keeping the newest
     * [EntryRules.HISTORY_MAX]. An empty password is no previous password: [history] stays as it is.
     */
    fun pushHistory(history: List<HistoryItem>, password: String, atMs: Long): List<HistoryItem> {
        if (password.isEmpty()) return history
        val out = ArrayList<HistoryItem>(EntryRules.HISTORY_MAX)
        out += HistoryItem(password, atMs)
        for (item in history) {
            if (out.size >= EntryRules.HISTORY_MAX) break
            out += item
        }
        return out
    }

    /** [matches] without [id]; the same list when it is not there. */
    fun withoutMatch(matches: List<String>, id: String): List<String> =
        if (id in matches) matches.filter { it != id } else matches

    /** [matches] with only the ids of [entries]; the same list when nothing goes. */
    fun onlyOf(matches: List<String>, entries: List<Entry>): List<String> {
        if (matches.isEmpty()) return matches
        val ids = entries.mapTo(HashSet(entries.size * 2)) { it.id }
        return if (matches.all { it in ids }) matches else matches.filter { it in ids }
    }

    /**
     * Whether [a] and [b] are the same record for a merge or an import (ux.md §5.21): equal in
     * everything but the favourite star, "left out of the check", `updatedAt` and `deletedAt`, which
     * say how the account was used here, not what it is.
     */
    fun sameRecord(a: Entry, b: Entry): Boolean = bare(a) == bare(b)

    private fun bare(e: Entry): Entry = e.copy(favourite = false, checkIgnored = false, updatedAt = 0L, deletedAt = null)

    /**
     * A name made from [title] by [text] (`EditTexts.fromBackup`, or the "(from file)" text): when it
     * would pass [EntryRules.TITLE_MAX], [title] is shortened first so that the added words fit; then
     * it is stored as [EntryRules.storedTitle] stores a name.
     */
    fun titled(text: (String) -> String, title: String): String {
        var result = text(title)
        val excess = EntryRules.length(result) - EntryRules.TITLE_MAX
        if (excess > 0) {
            val keep = (EntryRules.length(title) - excess).coerceAtLeast(0)
            result = text(EntryRules.cut(title, keep).trimEnd())
        }
        return EntryRules.storedTitle(result)
    }

    /** [entry] with every unpaired surrogate in its text made U+FFFD; [entry] itself when it has none. */
    fun withoutBrokenText(entry: Entry): Entry {
        if (!hasBrokenText(entry)) return entry
        return entry.copy(
            id = fix(entry.id),
            title = fix(entry.title),
            username = fix(entry.username),
            password = fix(entry.password),
            notes = fix(entry.notes),
            urls = entry.urls.map(::fix),
            fields = entry.fields.map(::fix),
            pendingOldPassword = entry.pendingOldPassword?.let(::fix),
            passwordHistory = entry.passwordHistory.map { HistoryItem(fix(it.password), it.replacedAt) },
            extras = entry.extras.entries.associate { fix(it.key) to fix(it.value) },
        )
    }

    /** [draft] with every unpaired surrogate in its text made U+FFFD; [draft] itself when it has none. */
    fun withoutBrokenText(draft: Draft): Draft {
        val e = draft.entry
        val broken = broken(draft.entryId) || broken(e.title) || broken(e.username) || broken(e.password) ||
            broken(e.notes) || e.urls.any(::broken) || e.fields.any(::broken)
        if (!broken) return draft
        return draft.copy(
            entryId = draft.entryId?.let(::fix),
            entry = DraftEntry(
                title = fix(e.title),
                username = fix(e.username),
                password = fix(e.password),
                notes = fix(e.notes),
                urls = e.urls.map(::fix),
                fields = e.fields.map(::fix),
                favourite = e.favourite,
            ),
        )
    }

    /** [text] with every unpaired surrogate made U+FFFD. */
    fun fix(text: String): String = TextHygiene.replaceUnpairedSurrogates(text)

    private fun fix(field: CustomField): CustomField =
        if (broken(field)) CustomField(fix(field.name), fix(field.value), field.secret) else field

    private fun broken(text: String?): Boolean = text != null && TextHygiene.hasUnpairedSurrogate(text)

    private fun broken(field: CustomField): Boolean = broken(field.name) || broken(field.value)

    private fun hasBrokenText(e: Entry): Boolean =
        broken(e.id) || broken(e.title) || broken(e.username) || broken(e.password) || broken(e.notes) ||
            e.urls.any(::broken) || e.fields.any(::broken) || broken(e.pendingOldPassword) ||
            e.passwordHistory.any { broken(it.password) } || e.extras.any { broken(it.key) || broken(it.value) }
}
