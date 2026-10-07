package io.github.dilshodbekmx.passwordvault.core.model

import io.github.dilshodbekmx.passwordvault.core.util.TextHygiene
import io.github.dilshodbekmx.passwordvault.testing.FakeRandom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/** What the tests of package 2.4 share: times, ids, entries and texts. */
internal object ModelFixtures {
    /** 2026-10-06 00:00 UTC, the `created` of architecture.md §4.13 vector A. */
    const val T0 = 1_791_244_800_000L
    const val MINUTE = 60_000L
    const val HOUR = 60 * MINUTE
    const val DAY = 24 * HOUR

    /** A key, U+1F511: one code point, two UTF-16 units. */
    const val KEY = "🔑"

    /** The id `00000000-0000-4000-8000-<n, 12 digits>`: a valid UUID of version 4. */
    fun id(n: Int): String = "00000000-0000-4000-8000-" + n.toString().padStart(12, '0')

    fun entry(
        n: Int,
        title: String = "Account $n",
        username: String = "user$n",
        password: String = "pass-$n",
        createdAt: Long = T0,
        updatedAt: Long = T0,
        deletedAt: Long? = null,
    ): Entry = Entry(
        id = id(n),
        title = title,
        username = username,
        password = password,
        urls = listOf("https://site$n.example"),
        createdAt = createdAt,
        updatedAt = updatedAt,
        passwordChangedAt = if (password.isEmpty()) null else createdAt,
        deletedAt = deletedAt,
    )

    fun vault(vararg entries: Entry): Vault = Vault(entries = entries.toList())

    /** A seeded source of new ids. */
    fun random(seed: Long = 7L): FakeRandom = FakeRandom(seed)

    /** [n] code points made of [KEY]: 2n UTF-16 units. */
    fun keys(n: Int): String = KEY.repeat(n)

    /** Texts in Uzbek, so that a stored name visibly comes from here and not from :core. */
    object Texts : EditTexts {
        override fun untitled(nowMs: Long): String = "Nomsiz $nowMs"

        override fun fromBackup(title: String): String = "$title (zaxiradan)"

        override fun fromFile(title: String): String = "$title (fayldan)"
    }

    fun EntryResult.id(): String = (this as EntryResult.Done).entryId

    /** The checks every vault this package makes must pass: one entry per id, no unpaired surrogate. */
    fun assertSound(vault: Vault) {
        val ids = vault.entries.map { it.id }
        assertEquals("two entries with one id", ids.size, ids.toSet().size)
        for (e in vault.entries) {
            val texts = listOf(e.id, e.title, e.username, e.password, e.notes) + e.urls +
                e.fields.flatMap { listOf(it.name, it.value) } + listOfNotNull(e.pendingOldPassword) +
                e.passwordHistory.map { it.password }
            assertTrue("an unpaired surrogate is stored", texts.none(TextHygiene::hasUnpairedSurrogate))
        }
        vault.draft?.entry?.let { d ->
            val texts = listOf(d.title, d.username, d.password, d.notes) + d.urls + d.fields.flatMap { listOf(it.name, it.value) }
            assertTrue("an unpaired surrogate is in the draft", texts.none(TextHygiene::hasUnpairedSurrogate))
        }
    }
}
