package io.github.dilshodbekmx.passwordvault.core.model

import io.github.dilshodbekmx.passwordvault.core.model.ModelFixtures.KEY
import io.github.dilshodbekmx.passwordvault.core.model.ModelFixtures.T0
import io.github.dilshodbekmx.passwordvault.core.model.ModelFixtures.Texts
import io.github.dilshodbekmx.passwordvault.core.model.ModelFixtures.entry
import io.github.dilshodbekmx.passwordvault.core.model.ModelFixtures.id
import io.github.dilshodbekmx.passwordvault.core.model.ModelFixtures.random
import io.github.dilshodbekmx.passwordvault.core.model.ModelFixtures.vault
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Build plan 2.4: "the title filled from the host" and "stored titles come from `EditTexts`"
 * (ux.md §2, architecture.md §3.7, §5.4).
 */
class TitleTest {
    private fun savedTitle(form: DraftEntry, texts: EditTexts = Texts): String =
        VaultEdits.add(form, texts, random())(Vault(), T0).vault.entries.single().title

    @Test
    fun aTypedNameIsKeptTrimmed() {
        assertEquals("Gmail", savedTitle(DraftEntry(title = "  Gmail ", urls = listOf("https://mail.google.com"))))
    }

    @Test
    fun aBlankNameIsFilledFromTheHostOfTheFirstWebsite() {
        val cases = listOf(
            listOf("https://mail.google.com/mail/u/0") to "mail.google.com",
            listOf("gmail.com") to "gmail.com",
            listOf("https://www.Google.com:443/accounts?x=1") to "google.com",
            listOf("http://user:pass@example.com:8080/path") to "example.com",
            listOf("", "  ", "https://my.gov.uz/", "https://second.example") to "my.gov.uz",
            listOf("https://first.example", "https://second.example") to "first.example",
            listOf("android://Xw0Fd5Jx/Ng+K/GtH0==@com.google.android.gm/") to "com.google.android.gm",
            listOf("https://xn--80aswg.xn--p1ai/") to "сайт.рф",
        )
        for ((urls, title) in cases) assertEquals(urls.toString(), title, savedTitle(DraftEntry(title = " ", urls = urls)))
    }

    @Test
    fun withoutAWebsiteOrAHostTheNameIsUntitledFromEditTexts() {
        assertEquals("Nomsiz $T0", savedTitle(DraftEntry()))
        assertEquals("Nomsiz $T0", savedTitle(DraftEntry(username = "aziz", urls = listOf("   "))))
        assertEquals("Nomsiz $T0", savedTitle(DraftEntry(urls = listOf("https://"))))
        assertNull(EntryRules.fromWebsites(listOf("https://")))
        assertNull(EntryRules.fromWebsites(emptyList()))
    }

    @Test
    fun anEditWithTheNameClearedFillsItTheSameWay() {
        val gmail = entry(1, title = "Gmail").copy(urls = listOf("https://mail.google.com"))
        val edited = VaultEdits.update(gmail.id, DraftEntry(title = "", urls = gmail.urls, password = gmail.password, username = gmail.username), Texts)(vault(gmail), T0 + 5)
        assertEquals("mail.google.com", edited.vault.entries.single().title)
        val noSite = entry(2)
        val cleared = VaultEdits.update(noSite.id, DraftEntry(password = noSite.password, username = noSite.username), Texts)(vault(noSite), T0 + 7)
        assertEquals("Nomsiz ${T0 + 7}", cleared.vault.entries.single().title)
    }

    @Test
    fun aFilledNameKeepsToTheLimit() {
        val longHost = "a".repeat(150) + "." + "b".repeat(150)
        val title = savedTitle(DraftEntry(urls = listOf(longHost)))
        assertEquals(200, EntryRules.length(title))
        assertTrue(title.startsWith("aaa"))
        val wordy = object : EditTexts {
            override fun untitled(nowMs: Long) = " \tUntitled ${KEY.repeat(250)}\n"
            override fun fromBackup(title: String) = "$title (from backup)"
        }
        val untitled = savedTitle(DraftEntry(), wordy)
        assertEquals(200, EntryRules.length(untitled))
        assertTrue(untitled.startsWith("Untitled $KEY"))
    }

    @Test
    fun storedNamesComeFromEditTexts() {
        val russian = object : EditTexts {
            override fun untitled(nowMs: Long) = "Без названия $nowMs"
            override fun fromBackup(title: String) = "$title (из резервной копии)"
        }
        // Add and edit.
        assertEquals("Без названия $T0", savedTitle(DraftEntry(), russian))
        // A merge: an entry with the id of an account with other content.
        val here = entry(1, title = "Gmail")
        val other = here.copy(password = "another")
        val merged = VaultEdits.mergeEntries(listOf(other), russian, random())(vault(here), T0)
        assertEquals(listOf("Gmail", "Gmail (из резервной копии)"), merged.vault.entries.map { it.title })
        val mergedUz = VaultEdits.mergeEntries(listOf(other), Texts, random())(vault(here), T0)
        assertEquals("Gmail (zaxiradan)", mergedUz.vault.entries.last().title)
        // An import: the same, with the "(from file)" text the caller passes.
        val imported = VaultEdits.addImported(listOf(other), random(), Texts::fromFile)(vault(here), T0)
        assertEquals("Gmail (fayldan)", imported.vault.entries.last().title)
    }

    @Test
    fun aNameWithAddedWordsIsShortenedSoThatTheWordsFit() {
        val long = entry(1, title = "G".repeat(200))
        val merged = VaultEdits.mergeEntries(listOf(long.copy(password = "x")), Texts, random())(vault(long), T0)
        val title = merged.vault.entries.last().title
        assertEquals(200, EntryRules.length(title))
        assertTrue(title, title.endsWith(" (zaxiradan)"))
        assertEquals(id(1), merged.vault.entries.first().id)
    }
}
