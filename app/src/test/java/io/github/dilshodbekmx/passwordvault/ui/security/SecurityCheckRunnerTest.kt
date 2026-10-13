package io.github.dilshodbekmx.passwordvault.ui.security

import io.github.dilshodbekmx.passwordvault.core.model.Entry
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.core.model.VaultEdits
import io.github.dilshodbekmx.passwordvault.core.strength.SecurityReport
import io.github.dilshodbekmx.passwordvault.data.CheckProgress
import io.github.dilshodbekmx.passwordvault.data.SaveProblem
import io.github.dilshodbekmx.passwordvault.data.SecurityCheckRunner
import io.github.dilshodbekmx.passwordvault.fake.FakeVaultRepository
import io.github.dilshodbekmx.passwordvault.ui.security.SecurityKit.DAY
import io.github.dilshodbekmx.passwordvault.ui.security.SecurityKit.NOW
import io.github.dilshodbekmx.passwordvault.ui.security.SecurityKit.entry
import io.github.dilshodbekmx.passwordvault.ui.security.SecurityKit.id
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * Build plan 7.1, [JVM]: `SecurityCheckRunner` (ux.md §5.16; architecture.md §3.5, §4.3, §5.1). The
 * check runs in the background after unlock and again after a save, scoring only what changed; the
 * dot shows only for results not yet seen; `securitySeenAt` is written only when that clears the dot;
 * a lock drops everything. The 480-account run with the real meter is [SecurityCheckSpeedTest].
 */
class SecurityCheckRunnerTest {
    private val locales = MutableStateFlow<Locale>(Locale.ENGLISH)
    private var clock = NOW

    /** How many times the scorer was asked, by entry title. */
    private val scored = mutableListOf<String>()

    private fun score(entry: Entry): Boolean {
        scored += entry.title
        return SecurityKit.easyToGuess(entry)
    }

    private class Rig(val runner: SecurityCheckRunner, val repository: FakeVaultRepository, val scope: CoroutineScope)

    private fun TestScope.started(vault: Vault? = null, scorer: (Entry) -> Boolean = ::score): Rig {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val repository = FakeVaultRepository(vault, clock = { clock })
        val runner = SecurityCheckRunner(repository, locales, SecurityKit::collationOf, scope, dispatcher, scorer)
        runner.start()
        return Rig(runner, repository, scope)
    }

    private fun Rig.done(test: TestScope) {
        test.testScheduler.advanceUntilIdle()
    }

    /** Gmail and Facebook share a password; "Old" is easy to guess; "Bank" waits for an answer; "PIN" is left out. */
    private val vault = Vault(
        entries = listOf(
            entry(1, "Facebook", password = "Shared-Pw-1"),
            entry(2, "Gmail", password = "Shared-Pw-1", urls = listOf("https://mail.google.com")),
            entry(3, "Old forum", password = "Toshkent2020"),
            entry(4, "Bank", password = "New-Bank-Pw-9", changePending = true, pendingOldPassword = "Old-Bank-Pw-3"),
            entry(5, "PIN", password = "123456", checkIgnored = true),
            entry(6, "Notes", password = ""),
        ),
    )

    @Test
    fun lockedThereIsNoReportNoProgressAndNoDot() = runTest {
        val rig = started()
        try {
            rig.done(this)
            assertNull(rig.runner.report.value)
            assertNull(rig.runner.progress.value)
            assertFalse(rig.runner.unseen.value)
            assertTrue("nothing is scored while locked", scored.isEmpty())
        } finally {
            rig.scope.cancel()
        }
    }

    @Test
    fun afterUnlockTheCheckRunsInTheBackgroundAndPublishesTheReport() = runTest {
        val rig = started()
        try {
            rig.repository.open(vault)
            assertNull("the check has not run yet: it waits for the background dispatcher", rig.runner.report.value)
            rig.done(this)
            val report = rig.runner.report.value!!
            assertEquals(listOf(listOf(id(2), id(1))), report.reused.map { it.entryIds })
            assertEquals(listOf(id(3)), report.weak)
            assertEquals(listOf(id(4)), report.pending)
            assertEquals(listOf(id(5)), report.leftOut)
            assertEquals(emptyList<String>(), report.sameAsMasterPassword)
            assertEquals(
                "every account with a password and not left out is scored once; the left-out PIN and the empty password are not",
                listOf("Bank", "Facebook", "Gmail", "Old forum"),
                scored.sorted(),
            )
            assertNull("no progress once done", rig.runner.progress.value)
        } finally {
            rig.scope.cancel()
        }
    }

    @Test
    fun theFirstCheckShowsItsProgressAndAnOrdinarySaveDoesNot() = runTest {
        val seen = mutableListOf<CheckProgress?>()
        val rig = started()
        try {
            val watch = rig.scope.launch(kotlinx.coroutines.Dispatchers.Unconfined) { rig.runner.progress.collect { seen += it } }
            rig.repository.open(vault)
            rig.done(this)
            assertEquals(
                "Checking 0 of 4… to 4 of 4…, then none",
                listOf(null, CheckProgress(0, 4), CheckProgress(1, 4), CheckProgress(2, 4), CheckProgress(3, 4), CheckProgress(4, 4), null),
                seen,
            )
            seen.clear()
            rig.repository.edit(VaultEdits.update(id(3), io.github.dilshodbekmx.passwordvault.core.model.DraftEntry(title = "Old forum", password = "Better-Pw-77#x"), NoTexts))
            rig.done(this)
            assertEquals("a save that scores one account shows no progress", emptyList<CheckProgress?>(), seen)
            assertEquals(emptyList<String>(), rig.runner.report.value!!.weak)
            watch.cancel()
        } finally {
            rig.scope.cancel()
        }
    }

    @Test
    fun anImportOfManyAccountsShowsItsProgressAgain() = runTest {
        val seen = mutableListOf<CheckProgress?>()
        val rig = started(vault)
        try {
            rig.done(this)
            val watch = rig.scope.launch(kotlinx.coroutines.Dispatchers.Unconfined) { rig.runner.progress.collect { seen += it } }
            val imported = (100 until 100 + SecurityCheckRunner.PROGRESS_FROM).map { entry(it, "Imported $it") }
            rig.repository.edit { v, _ -> io.github.dilshodbekmx.passwordvault.core.model.Edited(v.copy(entries = v.entries + imported), Unit) }
            rig.done(this)
            val total = 4 + SecurityCheckRunner.PROGRESS_FROM
            assertEquals(CheckProgress(0, total), seen[1])
            assertEquals(CheckProgress(total, total), seen[seen.size - 2])
            assertNull(seen.last())
            watch.cancel()
        } finally {
            rig.scope.cancel()
        }
    }

    @Test
    fun aSaveScoresOnlyTheAccountsThatChanged() = runTest {
        val rig = started(vault)
        try {
            rig.done(this)
            scored.clear()
            rig.repository.edit(VaultEdits.setFavourite(id(1), true))
            rig.done(this)
            assertEquals("only Facebook, whose entry is new, is scored again", listOf("Facebook"), scored)
            assertEquals(listOf(id(1), id(2)), rig.runner.report.value!!.reused.single().entryIds.sorted())
        } finally {
            rig.scope.cancel()
        }
    }

    @Test
    fun aSaveOfVaultLevelDataDoesNotCheckAgain() = runTest {
        val rig = started(vault)
        try {
            rig.done(this)
            val report = rig.runner.report.value
            scored.clear()
            rig.repository.edit(VaultEdits.noteBackup("content://backup/1", "PasswordVault-backup.pvault"))
            rig.done(this)
            assertTrue(scored.isEmpty())
            assertSame("the same report: nothing was checked again", report, rig.runner.report.value)
        } finally {
            rig.scope.cancel()
        }
    }

    @Test
    fun newMasterPasswordMatchesAreCheckedAgainWithoutScoring() = runTest {
        val rig = started(vault)
        try {
            rig.done(this)
            scored.clear()
            rig.repository.edit(VaultEdits.setMasterPasswordMatches(listOf(id(1))))
            rig.done(this)
            assertEquals(listOf(id(1)), rig.runner.report.value!!.sameAsMasterPassword)
            assertTrue("the verdicts are cached for the session", scored.isEmpty())
        } finally {
            rig.scope.cancel()
        }
    }

    @Test
    fun aChangeOfLanguageReordersTheListsWithoutScoring() = runTest {
        val rig = started(Vault(entries = listOf(entry(1, "Apple", password = "Toshkent2020"), entry(2, "Zoom", password = "Mustaqillik1991"))))
        try {
            rig.done(this)
            assertEquals(listOf(id(1), id(2)), rig.runner.report.value!!.weak)
            scored.clear()
            locales.value = Locale.forLanguageTag("xx")
            rig.done(this)
            assertEquals("Z to A in the language \"xx\"", listOf(id(2), id(1)), rig.runner.report.value!!.weak)
            assertTrue(scored.isEmpty())
        } finally {
            rig.scope.cancel()
        }
    }

    @Test
    fun aLockDropsEverythingAndTheNextUnlockScoresAgain() = runTest {
        val rig = started(vault)
        try {
            rig.done(this)
            assertTrue(rig.runner.unseen.value)
            rig.repository.close()
            assertNull("dropped in the thread that locked, before the publication returned", rig.runner.report.value)
            assertFalse(rig.runner.unseen.value)
            assertNull(rig.runner.progress.value)
            scored.clear()
            rig.repository.open()
            rig.done(this)
            assertNotNull(rig.runner.report.value)
            assertEquals("the cache went with the lock", 4, scored.size)
        } finally {
            rig.scope.cancel()
        }
    }

    @Test
    fun theNewestVaultWinsAndACheckForAnOlderOneIsNeverPublished() = runTest {
        val rig = started()
        try {
            rig.repository.open(vault)
            // The first check has not run; the vault changes before it does.
            val newer = vault.copy(entries = vault.entries.map { if (it.id == id(3)) it.copy(password = "Better-Pw-77#x", updatedAt = NOW) else it })
            rig.repository.open(newer)
            val published = mutableListOf<SecurityReport?>()
            val watch = rig.scope.launch(kotlinx.coroutines.Dispatchers.Unconfined) { rig.runner.report.collect { published += it } }
            rig.done(this)
            assertEquals(listOf(null, rig.runner.report.value), published)
            assertEquals("the newer vault's report", emptyList<String>(), rig.runner.report.value!!.weak)
            watch.cancel()
        } finally {
            rig.scope.cancel()
        }
    }

    @Test
    fun aLockWhileACheckRunsPublishesNothing() = runTest {
        val rig = started()
        try {
            rig.repository.open(vault)
            rig.repository.close()
            rig.done(this)
            assertNull(rig.runner.report.value)
            assertFalse(rig.runner.unseen.value)
        } finally {
            rig.scope.cancel()
        }
    }

    @Test
    fun aMeterThatFailsCountsTheAccountAsNotEasyToGuessAndTheCheckGoesOn() = runTest {
        val rig = started(vault) { entry -> if (entry.title == "Old forum") throw IllegalStateException("zxcvbn failed") else SecurityKit.easyToGuess(entry) }
        try {
            rig.done(this)
            val report = rig.runner.report.value!!
            assertEquals(emptyList<String>(), report.weak)
            assertEquals(1, report.reused.size)
        } finally {
            rig.scope.cancel()
        }
    }

    // ---- The dot (architecture.md §4.3 securitySeenAt) ----

    @Test
    fun theDotShowsOnlyForResultsNotYetSeen() = runTest {
        val seenAt = NOW - 10 * DAY
        fun dot(vararg entries: Entry, seen: Long? = seenAt, matches: List<String> = emptyList()): Boolean {
            val rig = started(Vault(entries = entries.toList(), securitySeenAt = seen, masterPasswordMatches = matches))
            try {
                rig.done(this)
                return rig.runner.unseen.value
            } finally {
                rig.scope.cancel()
            }
        }
        val before = seenAt - DAY
        val after = seenAt + DAY
        val fb = entry(1, "Facebook", password = "Shared-Pw-1", updatedAt = before)
        val gmail = entry(2, "Gmail", password = "Shared-Pw-1", updatedAt = before)

        assertTrue("never seen: any result is new", dot(fb, gmail, seen = null))
        assertFalse("never seen, but nothing to report", dot(entry(1, "Alone", updatedAt = after), seen = null))
        assertFalse("seen after every change", dot(fb, gmail))
        assertTrue("a shared password changed after the visit", dot(fb, gmail.copy(updatedAt = after)))
        assertTrue("an easy password saved after the visit", dot(fb, entry(3, "Old forum", password = "Toshkent2020", updatedAt = after)))
        assertTrue("a change started after the visit", dot(fb, entry(4, "Bank", password = "New-Bank-Pw-9", changePending = true, pendingOldPassword = "x", updatedAt = after)))
        assertTrue("the master password's twin changed after the visit", dot(fb.copy(updatedAt = after), matches = listOf(id(1))))
        assertFalse("an account that is not flagged changed after the visit", dot(fb, gmail, entry(5, "Fine", updatedAt = after)))
        assertFalse(
            "an account left out is the user's choice: no dot",
            dot(fb, gmail, entry(6, "PIN", password = "123456", checkIgnored = true, updatedAt = after)),
        )
        assertFalse(
            "an account in Recently deleted takes part in nothing",
            dot(fb, gmail, entry(7, "Gone", password = "Toshkent2020", updatedAt = after, deletedAt = after)),
        )
        assertFalse("the same instant as the visit is seen", dot(fb, gmail.copy(updatedAt = seenAt)))
    }

    /**
     * The owner's decision 23 (build-plan.md §12): an account found to share the master password gets
     * the dot even when it was last edited before the last visit, as after a master-password change
     * that made the master password equal to an old account's password. Before, the card appeared
     * without a dot, because the rule looks only at `updatedAt` (7.1's open question).
     */
    @Test
    fun anAccountNewlyFoundToShareTheMasterPassword_getsTheDot_evenIfEditedBeforeTheVisit() = runTest {
        val seenAt = NOW - DAY
        val old = entry(1, "Old mail", password = "Tog'-Daryo-Osmon-7", updatedAt = seenAt - 30 * DAY)
        val rig = started(Vault(entries = listOf(old, entry(2, "Other", password = "Other-Own-Pw-8", updatedAt = seenAt - DAY)), securitySeenAt = seenAt))
        try {
            rig.done(this)
            assertFalse("seen after every change", rig.runner.unseen.value)
            rig.repository.edit(VaultEdits.setMasterPasswordMatches(listOf(id(1)))) // the lock found it after the change (D-9b)
            rig.done(this)
            assertEquals(listOf(id(1)), rig.runner.report.value!!.sameAsMasterPassword)
            assertTrue("a new result: the dot", rig.runner.unseen.value)
            assertTrue("the visit clears it", rig.runner.markSeen())
            rig.done(this)
            assertFalse(rig.runner.unseen.value)
        } finally {
            rig.scope.cancel()
        }
    }

    @Test
    fun securitySeenAtIsWrittenOnlyWhenItClearsTheDot() = runTest {
        val quiet = Vault(entries = listOf(entry(1, "Alone"), entry(2, "Other")), securitySeenAt = null)
        val rig = started(quiet)
        try {
            rig.done(this)
            assertFalse(rig.runner.unseen.value)
            assertFalse("no dot: nothing to clear, nothing written", rig.runner.markSeen())
            assertEquals(0, rig.repository.edits)
            assertNull(rig.repository.stored.securitySeenAt)

            // A shared password appears: the dot.
            clock = NOW + 1_000
            rig.repository.edit(VaultEdits.update(id(2), io.github.dilshodbekmx.passwordvault.core.model.DraftEntry(title = "Other", password = rig.repository.stored.entries[0].password), NoTexts))
            rig.done(this)
            assertTrue(rig.runner.unseen.value)
            val saves = rig.repository.saves

            clock = NOW + 2_000
            assertTrue("the visit clears the dot", rig.runner.markSeen())
            rig.done(this)
            assertEquals(saves + 1, rig.repository.saves)
            assertEquals(NOW + 2_000, rig.repository.stored.securitySeenAt)
            assertFalse(rig.runner.unseen.value)

            assertFalse("seen already: nothing written", rig.runner.markSeen())
            assertEquals(saves + 1, rig.repository.saves)
        } finally {
            rig.scope.cancel()
        }
    }

    @Test
    fun aClockBehindTheLatestChangeStillClearsTheDot() = runTest {
        val rig = started(Vault(entries = listOf(entry(1, "Facebook", password = "Shared-Pw-1", updatedAt = NOW + DAY), entry(2, "Gmail", password = "Shared-Pw-1"))))
        try {
            rig.done(this)
            clock = NOW // a day before the change, as after the phone's clock was set back
            assertTrue(rig.runner.markSeen())
            rig.done(this)
            assertEquals(NOW + DAY, rig.repository.stored.securitySeenAt)
            assertFalse(rig.runner.unseen.value)
        } finally {
            rig.scope.cancel()
        }
    }

    @Test
    fun aVisitWhoseSaveFailsLeavesTheDot() = runTest {
        val rig = started(vault)
        try {
            rig.done(this)
            rig.repository.failures.add(SaveProblem.FAILED)
            assertFalse(rig.runner.markSeen())
            rig.done(this)
            assertTrue(rig.runner.unseen.value)
            assertNull(rig.repository.stored.securitySeenAt)
        } finally {
            rig.scope.cancel()
        }
    }

    @Test
    fun noReportYetNothingIsWritten() = runTest {
        val rig = started()
        try {
            rig.repository.open(vault)
            assertFalse("the first check has not ended", rig.runner.markSeen())
            assertEquals(0, rig.repository.edits)
        } finally {
            rig.scope.cancel()
        }
    }

    /** The texts an update stores; the tests always give a name, so none is used. */
    private object NoTexts : io.github.dilshodbekmx.passwordvault.core.model.EditTexts {
        override fun untitled(nowMs: Long): String = "Untitled"

        override fun fromBackup(title: String): String = title

        override fun fromFile(title: String): String = title
    }
}
