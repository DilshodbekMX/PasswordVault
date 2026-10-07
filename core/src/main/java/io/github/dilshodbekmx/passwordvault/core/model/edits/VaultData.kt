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

    fun notePlainExport(vault: Vault, nowMs: Long, uri: String, name: String): Edited<Unit> =
        Edited(vault.copy(plainExports = noted(vault.plainExports, nowMs, uri, name)), Unit)

    fun forgetPlainExport(vault: Vault, uri: String): Edited<Boolean> {
        val kept = vault.plainExports.filter { it.uri != uri }
        if (kept.size == vault.plainExports.size) return Edited(vault, false)
        return Edited(vault.copy(plainExports = kept), true)
    }

    fun noteBackup(vault: Vault, nowMs: Long, uri: String, name: String): Edited<Unit> =
        Edited(vault.copy(backups = noted(vault.backups, nowMs, uri, name)), Unit)

    fun forgetBackup(vault: Vault, uri: String): Edited<Boolean> {
        val kept = vault.backups.filter { it.uri != uri }
        if (kept.size == vault.backups.size) return Edited(vault, false)
        return Edited(vault.copy(backups = kept), true)
    }

    fun setMasterPasswordMatches(vault: Vault, entryIds: Collection<String>): Edited<Unit> {
        val wanted = entryIds.toHashSet()
        val matches = vault.entries.asSequence().map { it.id }.filter { it in wanted }.distinct().toList()
        if (matches == vault.masterPasswordMatches) return Edited(vault, Unit)
        return Edited(vault.copy(masterPasswordMatches = matches), Unit)
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
        return list.filter { it.uri != ref.uri } + ref
    }
}
