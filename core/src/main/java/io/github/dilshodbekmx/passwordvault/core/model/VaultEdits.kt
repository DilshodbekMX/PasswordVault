package io.github.dilshodbekmx.passwordvault.core.model

import io.github.dilshodbekmx.passwordvault.core.model.edits.EntryEdits
import io.github.dilshodbekmx.passwordvault.core.model.edits.GuidedChange
import io.github.dilshodbekmx.passwordvault.core.model.edits.Incoming
import io.github.dilshodbekmx.passwordvault.core.model.edits.RecentlyDeleted
import io.github.dilshodbekmx.passwordvault.core.model.edits.VaultData
import io.github.dilshodbekmx.passwordvault.core.util.RandomSource

/**
 * One change to the vault (architecture.md §3.5, §5.4): a pure function of the vault as it is and the
 * wall-clock time, which `VaultRepository.edit` runs under the vault mutex and saves as one
 * generation. [Edited.vault] is the input itself, the same object, when there is nothing to save.
 */
typealias VaultChange<T> = (vault: Vault, nowMs: Long) -> Edited<T>

/**
 * Every change to the vault (architecture.md §2.1, build plan 2.4), each as a [VaultChange]:
 * `repository.edit(VaultEdits.delete(id))`. Nothing here reads a clock or a file: the time is the
 * `nowMs` the repository passes, new ids come from the [RandomSource] given, and the texts an edit
 * stores come from [EditTexts], so that they are in the user's language.
 *
 * What every change keeps:
 * - **One id, one entry.** No change gives two entries one id: new ids are drawn until one is free,
 *   and an incoming id is kept only when it is a valid UUID that no entry has.
 * - **Nothing to save, nothing changed.** A change that changes nothing returns the input vault
 *   itself, so the repository writes nothing.
 * - **`updatedAt`.** A change to an entry sets its `updatedAt` to `nowMs` (ux.md §2, "on any saved
 *   change"). Entries brought in by an import or a merge keep the times they came with.
 * - **Recently deleted.** Changes to an entry act on entries outside Recently deleted
 *   ([Entry.deletedAt] null), except [restore] and [deleteForever], which act on entries inside it.
 * - **No unpaired surrogate is stored** (architecture.md §4.9 step 4): typed text with one is refused
 *   ([EntryRules]); text from a file, another vault or a draft has it replaced with U+FFFD.
 * - **The master-password list.** An entry whose password a change replaces leaves
 *   [Vault.masterPasswordMatches] (the next master-password entry finds it again if it still
 *   matches, D-9b); an entry removed for good leaves it too.
 */
object VaultEdits {
    /** A deleted entry is due for removal 30 days after [Entry.deletedAt] (ux.md §5.23, D-24). */
    const val PURGE_AFTER_MS: Long = 30L * 24 * 60 * 60 * 1000

    /**
     * How much phone-on time the purge waits for after it was armed, counted with `Credit` of
     * lock.md §6.9 by the repository (D-24): 24 hours.
     */
    const val PURGE_WAIT_MS: Long = 24L * 60 * 60 * 1000

    // ---- One entry: Add, Edit, the star, the security check (ux.md §5.12, §5.14) ----

    /**
     * Save of the add form (ux.md §5.14): [form] through [EntryRules]; a blank name is filled from
     * the first website's host or with [EditTexts.untitled]. The entry gets a new id from [random],
     * `createdAt` and `updatedAt` = `nowMs`, and `passwordChangedAt` = `nowMs` when it has a password.
     * A draft of a new account ([Draft.entryId] null) is deleted in the same save.
     *
     * Value: [EntryResult.Done] with the new id, or [EntryResult.Refused] (nothing changed).
     */
    fun add(form: DraftEntry, texts: EditTexts, random: RandomSource): VaultChange<EntryResult> =
        { vault, nowMs -> EntryEdits.add(vault, nowMs, form, texts, random) }

    /**
     * Save of the edit form of entry [entryId] (ux.md §5.14): the typed fields replace the entry's,
     * through [EntryRules] as in [add]. A changed password goes to the front of the previous
     * passwords (at most [EntryRules.HISTORY_MAX] kept; an empty one is not kept) and sets
     * `passwordChangedAt`. The flags, the dates and a guided change in progress stay. The draft of
     * this entry is deleted in the same save.
     *
     * Value: [EntryResult.Done], [EntryResult.Refused] or [EntryResult.NotFound] (no such entry
     * outside Recently deleted; nothing changed).
     */
    fun update(entryId: String, form: DraftEntry, texts: EditTexts): VaultChange<EntryResult> =
        { vault, nowMs -> EntryEdits.update(vault, nowMs, entryId, form, texts) }

    /** "Add to favourites" or "Remove from favourites". Value: [EntryResult.Done] or [EntryResult.NotFound]. */
    fun setFavourite(entryId: String, favourite: Boolean): VaultChange<EntryResult> =
        { vault, nowMs -> EntryEdits.mark(vault, nowMs, entryId) { it.copy(favourite = favourite) } }

    /**
     * "Leave out of the security check" ([ignored] true) or "Check it again" (D-9a).
     * Value: [EntryResult.Done] or [EntryResult.NotFound].
     */
    fun setCheckIgnored(entryId: String, ignored: Boolean): VaultChange<EntryResult> =
        { vault, nowMs -> EntryEdits.mark(vault, nowMs, entryId) { it.copy(checkIgnored = ignored) } }

    // ---- The guided change (ux.md §5.17) ----

    /**
     * "Create new password": [newPassword] becomes the entry's password at once, the password the
     * website still has is kept in [Entry.pendingOldPassword], and [Entry.changePending] is set.
     * The kept password is never overwritten: on an entry whose change is already pending this is
     * [retryChange]. [newPassword] follows the password rules of [EntryRules].
     *
     * Value: [EntryResult.Done], [EntryResult.Refused] or [EntryResult.NotFound].
     */
    fun startChange(entryId: String, newPassword: String): VaultChange<EntryResult> =
        { vault, nowMs -> GuidedChange.newPassword(vault, nowMs, entryId, newPassword) }

    /**
     * "Create another password": the password that was tried goes to the front of the previous
     * passwords, in case the website took it after all, and [newPassword] replaces it; the website's
     * password stays in [Entry.pendingOldPassword], however many tries it takes (ux.md §12 check 5).
     * On an entry without a pending change this is [startChange].
     */
    fun retryChange(entryId: String, newPassword: String): VaultChange<EntryResult> =
        { vault, nowMs -> GuidedChange.newPassword(vault, nowMs, entryId, newPassword) }

    /**
     * "Yes, it's changed": the website's old password goes to the front of the previous passwords;
     * [Entry.pendingOldPassword] and [Entry.changePending] are cleared.
     * Value: [EntryResult.Done], [EntryResult.NotPending] or [EntryResult.NotFound].
     */
    fun confirmChange(entryId: String): VaultChange<EntryResult> =
        { vault, nowMs -> GuidedChange.confirm(vault, nowMs, entryId) }

    /**
     * "No, put back the old password": the website's old password becomes the entry's password again,
     * the rejected new one goes to the front of the previous passwords, and the change ends.
     * Value: [EntryResult.Done], [EntryResult.NotPending] or [EntryResult.NotFound].
     */
    fun revertChange(entryId: String): VaultChange<EntryResult> =
        { vault, nowMs -> GuidedChange.revert(vault, nowMs, entryId) }

    // ---- Recently deleted (ux.md §5.23, D-24) ----

    /**
     * Delete: [Entry.deletedAt] = `nowMs`; the entry waits in Recently deleted. Deleting it again
     * keeps the first time. Value: [EntryResult.Done] or [EntryResult.NotFound].
     */
    fun delete(entryId: String): VaultChange<EntryResult> =
        { vault, nowMs -> RecentlyDeleted.delete(vault, nowMs, entryId) }

    /**
     * "Restore", and the snackbar's "Undo": [Entry.deletedAt] is cleared. An entry outside Recently
     * deleted is left as it is. Value: [EntryResult.Done] or [EntryResult.NotFound].
     */
    fun restore(entryId: String): VaultChange<EntryResult> =
        { vault, nowMs -> RecentlyDeleted.restore(vault, nowMs, entryId) }

    /**
     * "Delete forever": the entry is removed. Only an entry in Recently deleted can be; for any other
     * id the value is [EntryResult.NotFound] and nothing changes.
     */
    fun deleteForever(entryId: String): VaultChange<EntryResult> =
        { vault, _ -> RecentlyDeleted.deleteForever(vault, entryId) }

    /** "Empty": every entry in Recently deleted is removed. Value: how many. */
    fun emptyRecentlyDeleted(): VaultChange<Int> =
        { vault, _ -> RecentlyDeleted.empty(vault) }

    /**
     * Whether [entry] is due for removal at the wall-clock time [atMs]: it is in Recently deleted and
     * was deleted [PURGE_AFTER_MS] or more before [atMs].
     */
    fun isDue(entry: Entry, atMs: Long): Boolean = RecentlyDeleted.isDue(entry, atMs)

    /** How many entries of [vault] are due at [atMs]: at an unlock, more than 0 arms the purge (D-24). */
    fun dueCount(vault: Vault, atMs: Long): Int = vault.entries.count { RecentlyDeleted.isDue(it, atMs) }

    /**
     * The purge of D-24, the second of its two steps: removes exactly the entries that were due at
     * [armedAtMs], the wall-clock time at which the purge was armed ([isDue]), and nothing deleted
     * later. The repository runs it once [PURGE_WAIT_MS] are credited since the arming; `nowMs` plays
     * no part, so a clock set forward since the arming removes nothing more. Value: how many.
     */
    fun purge(armedAtMs: Long): VaultChange<Int> =
        { vault, _ -> RecentlyDeleted.purge(vault, armedAtMs) }

    // ---- The draft (ux.md §5.14, D-8a) ----

    /**
     * Keeps [draft] as the vault's one draft, in place of any other (the newest form wins). A draft
     * with the same entry and the same fields as the stored one changes nothing, whatever its
     * [Draft.savedAt]. The text is kept as typed; only unpaired surrogates become U+FFFD.
     */
    fun setDraft(draft: Draft): VaultChange<Unit> =
        { vault, _ -> VaultData.setDraft(vault, draft) }

    /** Deletes the draft: Discard, or a draft older than 30 days that was offered once. */
    fun clearDraft(): VaultChange<Unit> =
        { vault, _ -> VaultData.clearDraft(vault) }

    // ---- Files this app saved (ux.md §5.18 to §5.20) ----

    /**
     * Notes an unprotected export saved at `nowMs` to the document [uri] named [name], for the banner
     * of ux.md §5.20; it replaces a note of the same [uri].
     */
    fun notePlainExport(uri: String, name: String): VaultChange<Unit> =
        { vault, nowMs -> VaultData.notePlainExport(vault, nowMs, uri, name) }

    /** "Delete" or "I deleted it myself" on that banner. Value: whether a note of [uri] was there. */
    fun forgetPlainExport(uri: String): VaultChange<Boolean> =
        { vault, _ -> VaultData.forgetPlainExport(vault, uri) }

    /**
     * Notes a backup saved at `nowMs` to the document [uri] named [name] ("Last backup: …", and when
     * a backup is due, architecture.md §4.3); it replaces a note of the same [uri].
     */
    fun noteBackup(uri: String, name: String): VaultChange<Unit> =
        { vault, nowMs -> VaultData.noteBackup(vault, nowMs, uri, name) }

    /** A backup file deleted through the app (ux.md §5.18 step 6). Value: whether a note of [uri] was there. */
    fun forgetBackup(uri: String): VaultChange<Boolean> =
        { vault, _ -> VaultData.forgetBackup(vault, uri) }

    // ---- The security check (ux.md §5.16, D-9b) ----

    /**
     * After a correct master-password entry: the entries whose password equals it ([entryIds]),
     * kept in the vault's order; ids of no entry are left out.
     */
    fun setMasterPasswordMatches(entryIds: Collection<String>): VaultChange<Unit> =
        { vault, _ -> VaultData.setMasterPasswordMatches(vault, entryIds) }

    /**
     * [Vault.securitySeenAt] = `nowMs`: the Security tab was opened while its dot showed. The caller
     * writes it only when it clears a dot (architecture.md §4.3).
     */
    fun markSecuritySeen(): VaultChange<Unit> =
        { vault, nowMs -> VaultData.markSecuritySeen(vault, nowMs) }

    // ---- Import, merge, undo, restore (ux.md §5.21, architecture.md §4.11, §4.12, D-15) ----

    /**
     * Adds the accounts an import chose to add ([entries], from the importer's plan, in order), as
     * one save (formats.md §8.4, formats-review P1). An incoming id is kept only when it is a valid
     * UUID that neither the vault nor an earlier entry of [entries] has; every other entry gets a new
     * one from [random]. An entry whose id an account of the vault holds with other content is added
     * beside it with the name [fromFile] gives ("Gmail (from file)", formats.md §5.2). Nothing is
     * skipped here (the plan has done that) and nothing in the vault changes; the entries keep their
     * times.
     *
     * [fromFile] is [EditTexts.fromFile]: the caller passes `texts::fromFile`.
     *
     * Value: [Imported], whose [Imported.before] is the vault this import was applied to, for
     * "Undo this import" ([replaceVault]).
     */
    fun addImported(entries: List<Entry>, random: RandomSource, fromFile: (title: String) -> String): VaultChange<Imported> =
        { vault, _ -> Incoming.addImported(vault, entries, random, fromFile) }

    /**
     * Adds the accounts of another vault ([entries]: a backup file's, or a vault put aside, opened in
     * place) by the rules of "Backup file into an existing vault" (ux.md §5.21, architecture.md
     * §4.11): an entry with an id the vault does not have is added as it is; one identical to the
     * entry with its id is skipped; one with the id of an entry with other content is added with a
     * new id and the name [EditTexts.fromBackup] gives. Identical means the same in everything but
     * the favourite star, "left out of the check", `updatedAt` and `deletedAt`. Nothing is
     * overwritten; the entries keep their times. One save.
     */
    fun mergeEntries(entries: List<Entry>, texts: EditTexts, random: RandomSource): VaultChange<Merged> =
        { vault, _ -> Incoming.merge(vault, entries, texts, random) }

    /**
     * "Undo this import" (D-15): the vault becomes [replacement], the [Imported.before] of that
     * import, exactly. Refused, with the value false and nothing changed, when [replacement] holds
     * two entries with one id.
     */
    fun replaceVault(replacement: Vault): VaultChange<Boolean> =
        { vault, _ -> VaultData.replaceVault(vault, replacement) }

    /**
     * The vault a restore writes as a new vault (architecture.md §4.12, lock.md F13): [vault] from
     * the backup with its entries, draft, master-password list and unknown top-level keys, and with
     * [Vault.plainExports] and [Vault.backups] empty and [Vault.securitySeenAt] null, because those
     * documents and their permissions belong to the old installation.
     */
    fun forRestore(vault: Vault): Vault = VaultData.forRestore(vault)
}

/** What a change of one entry did. */
sealed interface EntryResult {
    /** Done, or there was nothing to change; [entryId] is the entry's id (for [VaultEdits.add], the new one). */
    data class Done(val entryId: String) : EntryResult

    /** No entry with this id where the change looks for one; nothing changed. */
    data object NotFound : EntryResult

    /** [VaultEdits.confirmChange] or [VaultEdits.revertChange] on an entry without a pending change. */
    data object NotPending : EntryResult

    /** The typed text breaks the rules of [EntryRules]; nothing changed. */
    data class Refused(val problems: List<EntryProblem>) : EntryResult
}

/** What [VaultEdits.addImported] did. */
class Imported(
    /** The ids of the entries added, in the order of the import. */
    val addedIds: List<String>,
    /** How many of them did not keep the id they came with. */
    val newIds: Int,
    /** How many of them were named with "(from file)". */
    val renamed: Int,
    /** The vault as it was just before the import: "Undo this import" puts it back ([VaultEdits.replaceVault]). */
    val before: Vault,
) {
    override fun toString(): String = "Imported(added=${addedIds.size}, newIds=$newIds, renamed=$renamed)"
}

/** What [VaultEdits.mergeEntries] did. */
class Merged(
    /** The ids of the entries added, in the order they came. */
    val addedIds: List<String>,
    /** Entries identical to the one with their id, which were not added. */
    val skipped: Int,
    /** Entries added with a new id and the name "(from backup)". */
    val renamed: Int,
) {
    override fun toString(): String = "Merged(added=${addedIds.size}, skipped=$skipped, renamed=$renamed)"
}
