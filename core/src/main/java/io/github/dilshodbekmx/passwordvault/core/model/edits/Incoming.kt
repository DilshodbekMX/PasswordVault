package io.github.dilshodbekmx.passwordvault.core.model.edits

import io.github.dilshodbekmx.passwordvault.core.model.EditTexts
import io.github.dilshodbekmx.passwordvault.core.model.Edited
import io.github.dilshodbekmx.passwordvault.core.model.Entry
import io.github.dilshodbekmx.passwordvault.core.model.Imported
import io.github.dilshodbekmx.passwordvault.core.model.Merged
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.core.util.RandomSource
import io.github.dilshodbekmx.passwordvault.core.util.Uuid

/**
 * Entries that come from outside this vault: an import (formats.md §8.4, formats-review P1) and
 * another vault's entries (ux.md §5.21, architecture.md §4.11). Both keep an incoming id only when it
 * is free, so neither can give two entries one id, and both make unpaired surrogates U+FFFD so that
 * the save's write-time check (architecture.md §4.9 step 4) cannot fail on them.
 */
internal object Incoming {
    fun addImported(vault: Vault, entries: List<Entry>, random: RandomSource, fromFile: (String) -> String): Edited<Imported> {
        if (entries.isEmpty()) return Edited(vault, Imported(emptyList(), 0, 0, vault))
        val holders = holders(vault)
        val taken = HashSet<String>(holders.keys)
        val added = ArrayList<Entry>(entries.size)
        var newIds = 0
        var renamed = 0
        for (incoming in entries) {
            val e = Entries.withoutBrokenText(incoming)
            val entry = if (Uuid.isValid(e.id) && e.id !in taken) {
                e
            } else {
                newIds++
                val id = Entries.newId(taken, random)
                // Only an account that was here before is "the one holding that id" (formats.md §5.2);
                // a second entry of the file with one id just gets a new one.
                val holder = holders[e.id]
                if (holder != null && !Entries.sameRecord(holder, e)) {
                    renamed++
                    e.copy(id = id, title = Entries.titled(fromFile, e.title))
                } else {
                    e.copy(id = id)
                }
            }
            taken += entry.id
            added += entry
        }
        return Edited(vault.copy(entries = vault.entries + added), Imported(added.map { it.id }, newIds, renamed, vault))
    }

    fun merge(vault: Vault, entries: List<Entry>, texts: EditTexts, random: RandomSource): Edited<Merged> {
        val inVault = holders(vault)
        val taken = HashSet<String>(inVault.keys)
        val fromOther = HashMap<String, Entry>()
        val added = ArrayList<Entry>()
        var skipped = 0
        var renamed = 0
        for (incoming in entries) {
            val e = Entries.withoutBrokenText(incoming)
            val here = inVault[e.id]
            val earlier = fromOther[e.id]
            val entry = when {
                here != null && Entries.sameRecord(here, e) -> null
                here != null -> {
                    renamed++
                    e.copy(id = Entries.newId(taken, random), title = Entries.titled(texts::fromBackup, e.title))
                }
                earlier != null && Entries.sameRecord(earlier, e) -> null
                Uuid.isValid(e.id) && e.id !in taken -> e.also { fromOther[e.id] = it }
                else -> e.copy(id = Entries.newId(taken, random))
            }
            if (entry == null) {
                skipped++
            } else {
                taken += entry.id
                added += entry
            }
        }
        if (added.isEmpty()) return Edited(vault, Merged(emptyList(), skipped, 0))
        return Edited(vault.copy(entries = vault.entries + added), Merged(added.map { it.id }, skipped, renamed))
    }

    /** The entries of [vault] by id; with a repeated id (never written by this app), the first. */
    private fun holders(vault: Vault): HashMap<String, Entry> {
        val map = HashMap<String, Entry>(vault.entries.size * 2)
        for (e in vault.entries) map.putIfAbsent(e.id, e)
        return map
    }
}
