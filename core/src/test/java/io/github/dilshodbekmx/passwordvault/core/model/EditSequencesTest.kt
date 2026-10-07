package io.github.dilshodbekmx.passwordvault.core.model

import io.github.dilshodbekmx.passwordvault.core.model.ModelFixtures.DAY
import io.github.dilshodbekmx.passwordvault.core.model.ModelFixtures.MINUTE
import io.github.dilshodbekmx.passwordvault.core.model.ModelFixtures.T0
import io.github.dilshodbekmx.passwordvault.core.model.ModelFixtures.Texts
import io.github.dilshodbekmx.passwordvault.core.model.ModelFixtures.assertSound
import io.github.dilshodbekmx.passwordvault.core.model.ModelFixtures.id
import io.github.dilshodbekmx.passwordvault.testing.FakeRandom
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Build plan 2.4: "no edit yields two entries with one id", over long random sequences of every
 * change, with ids drawn from a pool of twelve so that taken ids are the rule, not the exception.
 * After every step: one entry per id, no unpaired surrogate stored, and a change that changes
 * nothing hands back the input vault itself (`Edited`'s contract: the repository then writes nothing).
 */
class EditSequencesTest {
    private val pool = (1..12).map(::id)

    @Test
    fun noSequenceOfEditsGivesTwoEntriesOneId() {
        val rnd = Random(2026)
        val seen = HashMap<String, Int>()
        fun count(what: String) = seen.merge(what, 1, Int::plus)
        repeat(300) { run ->
            var v = Vault()
            val previous = ArrayList<Vault>()
            repeat(80) { step ->
                // Three ids of the pool first, most of them taken; then fresh ones.
                val ids = FakeRandom(seed = run * 1_000L + step).queueUuids(*Array(3) { pool[rnd.nextInt(pool.size)] })
                val (name, change) = pick(rnd, v, previous, ids)
                val now = T0 + step * MINUTE + rnd.nextInt(3) * 40 * DAY
                val result = change(v, now)
                assertSound(result.vault)
                if (result.vault == v) assertSame("$name: nothing changed, so the same vault", v, result.vault)
                when (val value = result.value) {
                    is Imported -> if (value.newIds > 0) count("import gave new ids") else if (value.addedIds.isNotEmpty()) count("import kept ids")
                    is Merged -> {
                        if (value.renamed > 0) count("merge renamed")
                        if (value.skipped > 0) count("merge skipped")
                    }
                    is EntryResult -> count("$name ${value::class.simpleName}")
                    else -> count(name)
                }
                previous += v
                v = result.vault
            }
        }
        // The sequences reach the cases that matter, so the checks above are not idle.
        for (case in listOf(
            "import gave new ids", "import kept ids", "merge renamed", "merge skipped",
            "add Done", "add Refused", "update Done", "update Refused", "update NotFound",
            "start Done", "retry Done", "confirm Done", "confirm NotPending", "revert Done",
            "delete Done", "restore Done", "deleteForever Done", "undo", "purge",
        )) {
            assertTrue("$case: ${seen[case]}", (seen[case] ?: 0) >= 5)
        }
    }

    @Test
    fun theFixedEditsKeepTheirPromisesOnARandomVault() {
        val rnd = Random(7)
        var v = Vault()
        val ids = FakeRandom(seed = 7)
        repeat(200) { v = VaultEdits.add(form(rnd, valid = true), Texts, ids)(v, T0).vault }
        assertEquals(200, v.entries.size)
        for (e in v.entries.shuffled(rnd).take(50)) {
            val result = VaultEdits.delete(e.id)(v, T0 + DAY)
            assertTrue(result.vault.entries.first { it.id == e.id }.deletedAt != null)
            v = result.vault
        }
        val purged = VaultEdits.purge(T0 + 31 * DAY)(v, T0 + 31 * DAY)
        assertEquals(50, purged.value)
        assertEquals(150, purged.vault.entries.size)
        assertSound(purged.vault)
    }

    private fun pick(rnd: Random, v: Vault, previous: List<Vault>, ids: FakeRandom): Pair<String, VaultChange<*>> {
        val some = if (rnd.nextInt(5) == 0 || v.entries.isEmpty()) pool[rnd.nextInt(pool.size)] else v.entries[rnd.nextInt(v.entries.size)].id
        return when (rnd.nextInt(20)) {
            0, 1 -> "add" to VaultEdits.add(form(rnd, valid = rnd.nextInt(4) > 0), Texts, ids)
            2 -> "update" to VaultEdits.update(some, form(rnd, valid = rnd.nextInt(4) > 0), Texts)
            3 -> "favourite" to VaultEdits.setFavourite(some, rnd.nextBoolean())
            4 -> "ignore" to VaultEdits.setCheckIgnored(some, rnd.nextBoolean())
            5 -> "start" to VaultEdits.startChange(some, "gen-" + rnd.nextInt(5))
            6 -> "retry" to VaultEdits.retryChange(some, "gen-" + rnd.nextInt(5))
            7 -> "confirm" to VaultEdits.confirmChange(some)
            8 -> "revert" to VaultEdits.revertChange(some)
            9 -> "delete" to VaultEdits.delete(some)
            10 -> "restore" to VaultEdits.restore(some)
            11 -> "deleteForever" to VaultEdits.deleteForever(some)
            12 -> "purge" to VaultEdits.purge(T0 + rnd.nextInt(200) * DAY)
            13 -> "draft" to if (rnd.nextBoolean()) VaultEdits.setDraft(Draft(some.takeIf { rnd.nextBoolean() }, T0, form(rnd, valid = false))) else VaultEdits.clearDraft()
            14 -> "import" to VaultEdits.addImported(incoming(rnd, v), ids, Texts::fromFile)
            15 -> "merge" to VaultEdits.mergeEntries(incoming(rnd, v), Texts, ids)
            16 -> "undo" to VaultEdits.replaceVault(if (previous.isEmpty()) v else previous[rnd.nextInt(previous.size)])
            17 -> "files" to when (rnd.nextInt(4)) {
                0 -> VaultEdits.notePlainExport("content://x/" + rnd.nextInt(3), "passwords.csv")
                1 -> VaultEdits.forgetPlainExport("content://x/" + rnd.nextInt(3))
                2 -> VaultEdits.noteBackup("content://b/" + rnd.nextInt(3), "backup.pvault")
                else -> VaultEdits.forgetBackup("content://b/" + rnd.nextInt(3))
            }
            18 -> "matches" to VaultEdits.setMasterPasswordMatches(v.entries.filter { rnd.nextBoolean() }.map { it.id } + pool[0])
            else -> "empty" to if (rnd.nextBoolean()) VaultEdits.emptyRecentlyDeleted() else VaultEdits.markSecuritySeen()
        }
    }

    /** A form that keeps the rules, or now and then one that breaks them (a lone surrogate, a tab, a long name). */
    private fun form(rnd: Random, valid: Boolean): DraftEntry {
        val bad = listOf("\uD800", "a\tb", "x".repeat(201))
        fun text(base: String) = if (!valid && rnd.nextInt(3) == 0) base + bad[rnd.nextInt(bad.size)] else base
        return DraftEntry(
            title = text(listOf("", "Gmail", "  Bank ", "Oʻzbekiston").random(rnd)),
            username = text("user" + rnd.nextInt(4)),
            password = text("pw" + rnd.nextInt(6)),
            notes = text("note"),
            urls = listOf("", "gmail.com", "https://bank.uz/login").shuffled(rnd).take(rnd.nextInt(3)),
            fields = if (rnd.nextBoolean()) listOf(CustomField("PIN code", text("0042"), secret = true)) else emptyList(),
            favourite = rnd.nextBoolean(),
        )
    }

    /** Entries from a file or another vault: ids from the pool, copies of entries here, broken text. */
    private fun incoming(rnd: Random, v: Vault): List<Entry> = (0 until rnd.nextInt(5)).map {
        val copy = if (v.entries.isNotEmpty() && rnd.nextBoolean()) v.entries[rnd.nextInt(v.entries.size)] else null
        when {
            copy != null && rnd.nextBoolean() -> copy
            copy != null -> copy.copy(password = copy.password + "!", title = copy.title + if (rnd.nextInt(4) == 0) "\uDC00" else "")
            else -> Entry(
                id = listOf(pool[rnd.nextInt(pool.size)], "", "not-a-uuid").random(rnd),
                title = "In " + rnd.nextInt(9),
                password = "p" + rnd.nextInt(9),
                createdAt = T0,
                updatedAt = T0,
                deletedAt = if (rnd.nextInt(4) == 0) T0 - 50 * DAY else null,
            )
        }
    }

    private fun <T> List<T>.random(rnd: Random): T = this[rnd.nextInt(size)]
}
