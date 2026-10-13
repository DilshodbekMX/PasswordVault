package io.github.dilshodbekmx.passwordvault.core.model.edits

import io.github.dilshodbekmx.passwordvault.core.model.Draft
import io.github.dilshodbekmx.passwordvault.core.model.Edited
import io.github.dilshodbekmx.passwordvault.core.model.FileRef
import io.github.dilshodbekmx.passwordvault.core.model.Vault

/** The vault-level data of architecture.md §4.3: the draft, the files saved, the security marks, undo and restore. */
internal object VaultData {
    fun setDraft(vault: Vault, draft: Draft): Edited<Unit> {
        val clean = Entries.withoutBrokenText(draft)
        val stored = vault.draft
        if (stored != null && stored.entryId == clean.entryId && stored.entry == clean.entry) return Edited(vault, Unit)
        return Edited(vault.copy(draft = clean), Unit)
    }

    fun clearDraft(vault: Vault): Edited<Unit> =
        if (vault.draft == null) Edited(vault, Unit) else Edited(vault.copy(draft = null), Unit)

    fun notePlainExport(vault: Vault, nowMs: Long, uri: String, name: String): Edited<Unit> {
        val refs = noted(vault.plainExports, nowMs, uri, name)
        return if (refs == vault.plainExports) Edited(vault, Unit) else Edited(vault.copy(plainExports = refs), Unit)
    }

    fun forgetPlainExport(vault: Vault, uri: String): Edited<Boolean> {
        val kept = without(vault.plainExports, uri)
        if (kept.size == vault.plainExports.size) return Edited(vault, false)
        return Edited(vault.copy(plainExports = kept), true)
    }

    fun noteBackup(vault: Vault, nowMs: Long, uri: String, name: String): Edited<Unit> {
        val refs = noted(vault.backups, nowMs, uri, name)
        return if (refs == vault.backups) Edited(vault, Unit) else Edited(vault.copy(backups = refs), Unit)
    }

    fun forgetBackup(vault: Vault, uri: String): Edited<Boolean> {
        val kept = without(vault.backups, uri)
        if (kept.size == vault.backups.size) return Edited(vault, false)
        return Edited(vault.copy(backups = kept), true)
    }

    fun setMasterPasswordMatches(vault: Vault, entryIds: Collection<String>): Edited<Unit> {
        val wanted = entryIds.toHashSet()
        val matches = vault.entries.asSequence().map { it.id }.filter { it in wanted }.distinct().toList()
        if (matches == vault.masterPasswordMatches) return Edited(vault, Unit)
        // An account newly found to share the master password is a new result even when it was last
        // edited before the last visit of the Security tab: the dot shows again (decision 23).
        val known = vault.masterPasswordMatches.toHashSet()
        val seenAt = if (matches.any { it !in known }) null else vault.securitySeenAt
        return Edited(vault.copy(masterPasswordMatches = matches, securitySeenAt = seenAt), Unit)
    }

    fun markSecuritySeen(vault: Vault, nowMs: Long): Edited<Unit> =
        if (vault.securitySeenAt == nowMs) Edited(vault, Unit) else Edited(vault.copy(securitySeenAt = nowMs), Unit)

    fun replaceVault(vault: Vault, replacement: Vault): Edited<Boolean> {
        val ids = HashSet<String>(replacement.entries.size * 2)
        if (!replacement.entries.all { ids.add(it.id) }) return Edited(vault, false)
        if (replacement == vault) return Edited(vault, true)
        return Edited(replacement, true)
    }

    fun forRestore(vault: Vault): Vault = vault.copy(plainExports = emptyList(), backups = emptyList(), securitySeenAt = null)

    /** [list] without a note of the same document, then the new note last. */
    private fun noted(list: List<FileRef>, nowMs: Long, uri: String, name: String): List<FileRef> {
        val ref = FileRef(Entries.fix(uri), Entries.fix(name), nowMs)
        return without(list, ref.uri) + ref
    }

    /** [list] without the notes of the document [uri], compared as it is stored. */
    private fun without(list: List<FileRef>, uri: String): List<FileRef> {
        val stored = Entries.fix(uri)
        return list.filter { it.uri != stored }
    }
}
