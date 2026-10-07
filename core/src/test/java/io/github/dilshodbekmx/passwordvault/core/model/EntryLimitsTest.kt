package io.github.dilshodbekmx.passwordvault.core.model

import io.github.dilshodbekmx.passwordvault.core.model.ModelFixtures.T0
import io.github.dilshodbekmx.passwordvault.core.model.ModelFixtures.Texts
import io.github.dilshodbekmx.passwordvault.core.model.ModelFixtures.keys
import io.github.dilshodbekmx.passwordvault.testing.FakeRandom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Build plan 2.4: "every limit of U §2 at, below and above its bound, in code points, not UTF-16
 * units". Each text is made of U+1F511, one code point and two UTF-16 units, so a text at the bound
 * has twice as many UTF-16 units as the limit and must still be accepted; one code point more must
 * be refused, through [EntryRules.problems] and through [VaultEdits.add], which then changes nothing.
 */
class EntryLimitsTest {
    /** One text limit of ux.md §2: where it is typed and where Save stores it. */
    private class Limit(
        val name: String,
        val field: EntryField,
        val max: Int,
        val text: (Int) -> String,
        val form: (String) -> DraftEntry,
        val stored: (Entry) -> String,
    )

    private val website = "https://x.example/"

    private val limits = listOf(
        Limit("name", EntryField.TITLE, 200, ::keys, { DraftEntry(title = it) }, { it.title }),
        Limit("username", EntryField.USERNAME, 500, ::keys, { DraftEntry(title = "T", username = it) }, { it.username }),
        Limit("password", EntryField.PASSWORD, 1_000, ::keys, { DraftEntry(title = "T", password = it) }, { it.password }),
        Limit(
            "website",
            EntryField.WEBSITE,
            2_048,
            { n -> website + keys(n - website.length) },
            { DraftEntry(title = "T", urls = listOf(it)) },
            { it.urls.single() },
        ),
        Limit("notes", EntryField.NOTES, 10_000, ::keys, { DraftEntry(title = "T", notes = it) }, { it.notes }),
        Limit(
            "field name",
            EntryField.FIELD_NAME,
            100,
            ::keys,
            { DraftEntry(title = "T", fields = listOf(CustomField(it, "v", secret = true))) },
            { it.fields.single().name },
        ),
        Limit(
            "field value",
            EntryField.FIELD_VALUE,
            1_000,
            ::keys,
            { DraftEntry(title = "T", fields = listOf(CustomField("PIN code", it, secret = true))) },
            { it.fields.single().value },
        ),
    )

    @Test
    fun theLimitsAreThoseOfUx2() {
        assertEquals(200, EntryRules.TITLE_MAX)
        assertEquals(500, EntryRules.USERNAME_MAX)
        assertEquals(1_000, EntryRules.PASSWORD_MAX)
        assertEquals(10, EntryRules.URLS_MAX)
        assertEquals(2_048, EntryRules.URL_MAX)
        assertEquals(10_000, EntryRules.NOTES_MAX)
        assertEquals(20, EntryRules.FIELDS_MAX)
        assertEquals(100, EntryRules.FIELD_NAME_MAX)
        assertEquals(1_000, EntryRules.FIELD_VALUE_MAX)
        assertEquals(5, EntryRules.HISTORY_MAX)
        assertEquals(limits.map { it.max }, listOf(200, 500, 1_000, 2_048, 10_000, 100, 1_000))
    }

    @Test
    fun atTheLimitInCodePointsIsAcceptedThoughItHasTwiceAsManyUtf16Units() {
        for (limit in limits) {
            val text = limit.text(limit.max)
            assertEquals(limit.name, limit.max, text.codePointCount(0, text.length))
            assertTrue("${limit.name}: more UTF-16 units than the limit", text.length > limit.max)
            assertAccepted(limit, text)
        }
    }

    @Test
    fun belowTheLimitIsAccepted() {
        for (limit in limits) assertAccepted(limit, limit.text(limit.max - 1))
    }

    @Test
    fun oneCodePointAboveTheLimitIsRefused() {
        for (limit in limits) assertRefused(limit, limit.text(limit.max + 1))
    }

    @Test
    fun withOneUnitPerCodePointTheBoundIsTheSame() {
        for (limit in limits) {
            val ascii = { n: Int -> if (limit.field == EntryField.WEBSITE) website + "a".repeat(n - website.length) else "a".repeat(n) }
            assertAccepted(limit, ascii(limit.max))
            assertRefused(limit, ascii(limit.max + 1))
        }
    }

    @Test
    fun tenWebsitesAndTwentyFieldsAtMost() {
        fun websites(n: Int) = DraftEntry(title = "T", urls = (1..n).map { "https://s$it.example" })
        fun fields(n: Int) = DraftEntry(title = "T", fields = (1..n).map { CustomField("F$it", "v", secret = false) })
        for ((n, ok) in listOf(9 to true, 10 to true, 11 to false)) {
            val problems = EntryRules.problems(websites(n))
            assertEquals("$n websites", if (ok) emptyList() else listOf(EntryProblem(EntryField.WEBSITES, EntryProblem.Kind.TOO_MANY, -1, 10)), problems)
        }
        for ((n, ok) in listOf(19 to true, 20 to true, 21 to false)) {
            val problems = EntryRules.problems(fields(n))
            assertEquals("$n fields", if (ok) emptyList() else listOf(EntryProblem(EntryField.FIELDS, EntryProblem.Kind.TOO_MANY, -1, 20)), problems)
        }
        // Blank rows are not counted: they are dropped at Save.
        val withBlanks = websites(10).copy(urls = websites(10).urls + listOf("", "   ")) // 12 rows, 10 websites
        assertTrue(EntryRules.problems(withBlanks).isEmpty())
        assertEquals(10, EntryRules.prepare(withBlanks).urls.size)
        val withBlankFields = fields(20).copy(fields = fields(20).fields + CustomField(" ", "", secret = true))
        assertTrue(EntryRules.problems(withBlankFields).isEmpty())
        assertEquals(20, EntryRules.prepare(withBlankFields).fields.size)
    }

    @Test
    fun aFieldNameNeedsOneCodePointAndANameMayBeOne() {
        val noName = DraftEntry(title = "T", fields = listOf(CustomField("   ", "1234", secret = true)))
        assertEquals(listOf(EntryProblem(EntryField.FIELD_NAME, EntryProblem.Kind.BLANK, 0)), EntryRules.problems(noName))
        val oneKey = DraftEntry(title = "T", fields = listOf(CustomField(ModelFixtures.KEY, "1234", secret = true)))
        assertTrue(EntryRules.problems(oneKey).isEmpty())
    }

    @Test
    fun aBlankNameIsNotRefusedButFilled() {
        // ux.md §2: the name is required, and Save fills a blank one (§5.14: "Save is never disabled").
        assertTrue(EntryRules.problems(DraftEntry(title = "   ")).isEmpty())
        val saved = VaultEdits.add(DraftEntry(title = "  "), Texts, FakeRandom())(Vault(), T0)
        assertEquals("Nomsiz $T0", saved.vault.entries.single().title)
    }

    @Test
    fun lengthAndCutCountCodePoints() {
        assertEquals(3, EntryRules.length("a${ModelFixtures.KEY}b"))
        assertEquals(0, EntryRules.length(""))
        assertEquals("a${ModelFixtures.KEY}", EntryRules.cut("a${ModelFixtures.KEY}b", 2))
        assertEquals("a", EntryRules.cut("a${ModelFixtures.KEY}b", 1)) // never half a pair
        assertEquals(keys(10), EntryRules.cut(keys(12), 10))
        val short = "abc"
        assertSame(short, EntryRules.cut(short, 3))
        assertSame(short, EntryRules.cut(short, 10))
        assertEquals("", EntryRules.cut(short, 0))
    }

    private fun assertAccepted(limit: Limit, text: String) {
        val form = limit.form(text)
        assertTrue("${limit.name} of ${EntryRules.length(text)}: ${EntryRules.problems(form)}", EntryRules.problems(form).isEmpty())
        val saved = VaultEdits.add(form, Texts, FakeRandom())(Vault(), T0)
        assertTrue("${limit.name}: ${saved.value}", saved.value is EntryResult.Done)
        assertEquals("${limit.name} is stored whole", text, limit.stored(saved.vault.entries.single()))
    }

    private fun assertRefused(limit: Limit, text: String) {
        val form = limit.form(text)
        val index = if (limit.field in setOf(EntryField.WEBSITE, EntryField.FIELD_NAME, EntryField.FIELD_VALUE)) 0 else -1
        val expected = listOf(EntryProblem(limit.field, EntryProblem.Kind.TOO_LONG, index, limit.max))
        assertEquals(limit.name, expected, EntryRules.problems(form))
        val vault = Vault()
        val saved = VaultEdits.add(form, Texts, FakeRandom())(vault, T0)
        assertEquals(limit.name, EntryResult.Refused(expected), saved.value)
        assertSame("${limit.name}: nothing changed", vault, saved.vault)
    }
}
