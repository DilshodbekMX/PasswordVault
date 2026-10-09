package io.github.dilshodbekmx.passwordvault.ui.security

import io.github.dilshodbekmx.passwordvault.core.formats.ImportPlanner
import io.github.dilshodbekmx.passwordvault.core.formats.Importer
import io.github.dilshodbekmx.passwordvault.core.model.EditTexts
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.core.model.VaultEdits
import io.github.dilshodbekmx.passwordvault.core.strength.SecurityCheck
import io.github.dilshodbekmx.passwordvault.testing.FakeRandom
import io.github.dilshodbekmx.passwordvault.testing.arch.SourceTree
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * Build plan 7.1: the fixture `app/src/androidTest/assets/fixtures/fifty-accounts.csv`, which the
 * integration check of wave 7 imports on the emulator ("the 50-account fixture in which 17 share a
 * password"). Read by the real importer, planned into an empty vault and checked with the real meter:
 * 50 accounts, nothing joined, no name tidied; 17 share one password with Gmail first in their group,
 * 3 share another, 6 are easy to guess; the Security tab shows the cards for both.
 *
 * The test task does not track the device tests' assets, so the fixture's SHA-256 is pinned here:
 * change both together, and this test runs again.
 */
class SecurityFixtureTest {
    private val file = File(SourceTree.root(), "app/src/androidTest/assets/fixtures/fifty-accounts.csv")

    private object Texts : EditTexts {
        override fun untitled(nowMs: Long): String = "Untitled"

        override fun fromBackup(title: String): String = "$title (from backup)"

        override fun fromFile(title: String): String = "$title (from file)"
    }

    private fun imported(): Vault {
        val read = Importer.read(file.readBytes(), SecurityKit.NOW, Texts, FakeRandom(7))
        assertTrue("read as a CSV file: $read", read is Importer.Result.Read)
        val plan = ImportPlanner.plan(Vault(), (read as Importer.Result.Read).file, Texts::fromFile)
        assertEquals("found", 50, plan.found)
        assertEquals("nothing joined as the same login on related websites", 0, plan.relatedRows)
        assertEquals("no name tidied: they are names, not websites", 0, plan.tidied)
        assertEquals(50, plan.accounts.size)
        assertTrue(plan.fits)
        return VaultEdits.addImported(plan.entries, FakeRandom(8), Texts::fromFile)(Vault(), SecurityKit.NOW).vault
    }

    @Test
    fun theFixtureIsTheOneThisTestKnows() {
        val sha = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        assertEquals(FIXTURE_SHA256, sha)
    }

    @Test
    fun fiftyAccountsOfWhichSeventeenShareAPasswordWithGmailFirst() {
        val vault = imported()
        assertEquals(50, vault.entries.size)
        val titles = vault.entries.associate { it.id to it.title }
        val report = SecurityCheck.run(vault)

        assertEquals(listOf(17, 3), report.reused.map { it.entryIds.size })
        val group = report.reused[0].entryIds.map { titles.getValue(it) }
        assertEquals(
            "email first (by name or website), then A to Z",
            listOf("Gmail", "Mail.ru Pochta", "Outlook", "Oʻzbekiston pochtasi", "Yandex Pochta"),
            group.take(5),
        )
        assertEquals(
            listOf("Daryo", "Facebook", "HeadHunter", "Instagram", "LinkedIn", "Netflix", "Odnoklassniki", "OLX", "Telegram Web", "Uzum Market", "VKontakte", "X (Twitter)"),
            group.drop(5),
        )
        assertEquals(listOf("Apelsin", "Click", "Payme"), report.reused[1].entryIds.map { titles.getValue(it) })
        assertEquals(
            "the six place-and-year passwords of ux.md §12 check 18, and nothing else",
            setOf("Uy Wi-Fi", "Kutubxona", "Kundalik", "Eski forum", "Router", "Ish kompyuteri"),
            report.weak.map { titles.getValue(it) }.toSet(),
        )
        assertEquals(emptyList<String>(), report.sameAsMasterPassword)
        assertEquals(emptyList<String>(), report.pending)
        assertEquals(emptyList<String>(), report.leftOut)

        val cards = (SecurityViewModel.resultsOf(report, vault) as SecurityResults.Cards).cards
        assertEquals(
            listOf(SecurityCard.Reused(GroupLine(report.reused[0].id, 17, 0), listOf(GroupLine(report.reused[1].id, 3, 0))), SecurityCard.Weak(6)),
            cards,
        )
        assertTrue("a CSV row has no dates: the group's id is one of its accounts'", report.reused[0].id in report.reused[0].entryIds)
    }

    private companion object {
        const val FIXTURE_SHA256 = "b8e7309f6fcbbb1a1df9b5fd412bb85afe1afd219b2ad38b320e92d7adf73e37"
    }
}
