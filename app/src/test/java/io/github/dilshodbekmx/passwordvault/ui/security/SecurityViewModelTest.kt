package io.github.dilshodbekmx.passwordvault.ui.security

import io.github.dilshodbekmx.passwordvault.core.lock.flow.Notice
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.core.strength.ReuseGroup
import io.github.dilshodbekmx.passwordvault.core.strength.SecurityCheck
import io.github.dilshodbekmx.passwordvault.core.strength.SecurityReport
import io.github.dilshodbekmx.passwordvault.data.CheckProgress
import io.github.dilshodbekmx.passwordvault.data.VaultState
import io.github.dilshodbekmx.passwordvault.fake.FakeSecurityReports
import io.github.dilshodbekmx.passwordvault.lock.LockUiState
import io.github.dilshodbekmx.passwordvault.platform.AppDispatchers
import io.github.dilshodbekmx.passwordvault.ui.security.SecurityKit.DAY
import io.github.dilshodbekmx.passwordvault.ui.security.SecurityKit.NOW
import io.github.dilshodbekmx.passwordvault.ui.security.SecurityKit.entry
import io.github.dilshodbekmx.passwordvault.ui.security.SecurityKit.id
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Build plan 7.1, [JVM]: the Security tab's ViewModel (ux.md §5.16). The cards in their order, each
 * only when it counts something; the "left out" count; "all clear" and "no accounts yet"; "Why this
 * matters" open on the first visit only; and the visit that clears the dot only while the tab is in
 * view with no notice over it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SecurityViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    /** The tests' collectors; not `backgroundScope`, which `advanceUntilIdle` does not wait for. */
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    @Before
    fun setMain() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() {
        scope.cancel()
        Dispatchers.resetMain()
    }

    private val reports = FakeSecurityReports()
    private val progress = MutableStateFlow<CheckProgress?>(null)
    private val vaults = MutableStateFlow<VaultState>(VaultState.Locked)
    private val lock = MutableStateFlow<LockUiState>(LockUiState.Unlocked(emptyList(), null))
    private var marks = 0

    private fun viewModel() = SecurityViewModel(
        reports = reports,
        progress = progress,
        markSeen = {
            marks++
            true
        },
        vaults = vaults,
        lockState = lock,
        dispatchers = AppDispatchers.single(dispatcher),
    )

    private fun open(vault: Vault) {
        vaults.value = VaultState.Unlocked(vault, 1L, null, false)
    }

    /** Every card: Gmail is the master password's twin; three share a password; two are easy; one waits; two are left out. */
    private val everything = Vault(
        entries = listOf(
            entry(1, "Gmail", password = "Shared-Pw-1", urls = listOf("https://mail.google.com")),
            entry(2, "Facebook", password = "Shared-Pw-1"),
            entry(3, "Instagram", password = "Shared-Pw-1"),
            entry(4, "Old forum", password = "Toshkent2020"),
            entry(5, "Kundalik", password = "Mustaqillik1991"),
            entry(6, "Bank", password = "New-Bank-Pw-9", changePending = true, pendingOldPassword = "Old-Bank-Pw-3"),
            entry(7, "Card PIN", password = "123456", checkIgnored = true),
            entry(8, "Door code", password = "qwerty", checkIgnored = true),
            entry(9, "Fine", password = "Unique-Pw-9#k"),
        ),
        masterPasswordMatches = listOf(id(1)),
    )

    private fun reportOf(vault: Vault): SecurityReport = SecurityCheck.run(vault, SecurityKit::easyToGuess)

    @Test
    fun theCardsComeInTheOrderOfUx516() = runTest(dispatcher) {
        val vm = viewModel()
        open(everything)
        reports.report.value = reportOf(everything)
        advanceUntilIdle()
        val cards = (vm.state.value!!.results as SecurityResults.Cards).cards
        assertEquals(
            listOf(
                SecurityCard.MasterPassword(1, listOf("Gmail")),
                SecurityCard.Reused(GroupLine(id(1), 3, 0), emptyList()),
                SecurityCard.Weak(2),
                SecurityCard.Pending(1, listOf("Bank"), id(6)),
                SecurityCard.LeftOut(2),
            ),
            cards,
        )
        assertFalse((vm.state.value!!.results as SecurityResults.Cards).allClear)
    }

    @Test
    fun aCardIsThereOnlyWhenItCountsSomething() {
        fun cards(vault: Vault) = (SecurityViewModel.resultsOf(reportOf(vault), vault) as SecurityResults.Cards).cards.map { it.javaClass.simpleName }
        assertEquals(listOf("Reused"), cards(Vault(entries = listOf(entry(1, "A", password = "Same-Pw-1"), entry(2, "B", password = "Same-Pw-1")))))
        assertEquals(listOf("Weak", "LeftOut"), cards(Vault(entries = listOf(entry(1, "A", password = "Toshkent2020"), entry(2, "B", checkIgnored = true)))))
        assertEquals(listOf("MasterPassword", "Pending"), cards(Vault(entries = listOf(entry(1, "A", changePending = true, pendingOldPassword = "Old-Pw-x1")), masterPasswordMatches = listOf(id(1)))))
    }

    @Test
    fun theLeftOutCountIsAlwaysShownAndLeftOutAccountsAreInNoOtherCount() {
        val vault = Vault(
            entries = listOf(
                entry(1, "Gmail", password = "Same-Pw-1"),
                entry(2, "Facebook", password = "Same-Pw-1"),
                entry(3, "Instagram", password = "Same-Pw-1", checkIgnored = true),
                entry(4, "PIN", password = "123456", checkIgnored = true),
                entry(5, "Gone", password = "qwerty", checkIgnored = true, deletedAt = NOW - DAY),
            ),
        )
        val results = SecurityViewModel.resultsOf(reportOf(vault), vault) as SecurityResults.Cards
        assertEquals(
            "the group counts the two that are checked; the weak PIN is left out; the deleted one is nowhere",
            listOf(SecurityCard.Reused(GroupLine(id(1), 2, 0), emptyList()), SecurityCard.LeftOut(2)),
            results.cards,
        )
        // All clear apart from what is left out: the tab says so and still shows the count (D-9a).
        val clear = Vault(entries = listOf(entry(1, "Fine"), entry(2, "PIN", password = "123456", checkIgnored = true)))
        val clearResults = SecurityViewModel.resultsOf(reportOf(clear), clear) as SecurityResults.Cards
        assertTrue(clearResults.allClear)
        assertEquals(listOf(SecurityCard.LeftOut(1)), clearResults.cards)
    }

    @Test
    fun allClearAndNoAccountsYet() {
        val fine = Vault(entries = listOf(entry(1, "Fine"), entry(2, "Also fine")))
        val results = SecurityViewModel.resultsOf(reportOf(fine), fine) as SecurityResults.Cards
        assertTrue(results.allClear)
        assertEquals(emptyList<SecurityCard>(), results.cards)

        val onlyDeleted = Vault(entries = listOf(entry(1, "Gone", deletedAt = NOW)))
        assertEquals(SecurityResults.NoAccounts, SecurityViewModel.resultsOf(reportOf(onlyDeleted), onlyDeleted))
        assertEquals(SecurityResults.NoAccounts, SecurityViewModel.resultsOf(reportOf(Vault()), Vault()))
    }

    @Test
    fun cardsNameTheFirstAccountsAndTheOtherGroups() {
        val vault = Vault(
            entries = listOf(
                entry(1, "Yandex Pochta", password = "Shared-Pw-1"),
                entry(2, "Facebook", password = "Shared-Pw-1"),
                entry(3, "Gmail", password = "Shared-Pw-1", urls = listOf("https://mail.google.com")),
                entry(4, "Click", password = "Other-Pw-2", changePending = true, pendingOldPassword = "a"),
                entry(5, "Payme", password = "Other-Pw-2", changePending = true, pendingOldPassword = "b"),
                entry(6, "Apelsin", password = "Other-Pw-2", changePending = true, pendingOldPassword = "c"),
                entry(7, "Uzum", password = "Third-Pw-3", history = listOf("Shared-Pw-1")),
                entry(8, "OLX", password = "Third-Pw-3"),
            ),
            masterPasswordMatches = listOf(id(2), id(1), id(3)),
        )
        val cards = (SecurityViewModel.resultsOf(reportOf(vault), vault) as SecurityResults.Cards).cards
        assertEquals("email first, then A to Z", SecurityCard.MasterPassword(3, listOf("Gmail", "Yandex Pochta")), cards[0])
        val reused = cards[1] as SecurityCard.Reused
        assertEquals("Uzum had that password and left the group by a change", GroupLine(id(1), 3, 1), reused.biggest)
        // Click, Payme and Apelsin judge their old passwords while their changes wait: each its own.
        assertEquals(listOf(GroupLine(id(7), 2, 0)), reused.others)
        assertEquals(SecurityCard.Pending(3, listOf("Apelsin", "Click"), null), cards[2])
    }

    @Test
    fun aReportAMomentOlderThanTheVaultCountsOnlyAccountsTheVaultStillHas() {
        val report = SecurityReport(
            sameAsMasterPassword = listOf(id(9)),
            reused = listOf(ReuseGroup(id(1), listOf(id(1), id(2)), changed = 1), ReuseGroup(id(3), listOf(id(3), id(4), id(5)), changed = 0)),
            weak = listOf(id(4), id(9)),
            pending = emptyList(),
            leftOut = emptyList(),
        )
        // Account 2 was deleted and account 9 removed since the report was made.
        val vault = Vault(entries = listOf(entry(1, "A"), entry(2, "B", deletedAt = NOW), entry(3, "C"), entry(4, "D"), entry(5, "E")))
        val cards = (SecurityViewModel.resultsOf(report, vault) as SecurityResults.Cards).cards
        assertEquals(listOf(SecurityCard.Reused(GroupLine(id(3), 3, 0), emptyList()), SecurityCard.Weak(1)), cards)
    }

    @Test
    fun whileTheFirstCheckRunsTheTabShowsItsProgress() = runTest(dispatcher) {
        val vm = viewModel()
        open(everything)
        progress.value = CheckProgress(120, 480)
        advanceUntilIdle()
        assertEquals(SecurityUi(whyOpen = true, checking = CheckProgress(120, 480), results = null), vm.state.value)
        reports.report.value = reportOf(everything)
        progress.value = null
        advanceUntilIdle()
        assertNull(vm.state.value!!.checking)
    }

    @Test
    fun lockedTheTabShowsNothingAndDropsTheNames() = runTest(dispatcher) {
        val vm = viewModel()
        open(everything)
        reports.report.value = reportOf(everything)
        advanceUntilIdle()
        assertTrue(vm.state.value!!.results is SecurityResults.Cards)
        vaults.value = VaultState.Locked
        reports.report.value = null
        advanceUntilIdle()
        assertNull(vm.state.value)
    }

    // ---- "Why this matters": open on the first visit, then folded ----

    @Test
    fun whyThisMattersIsOpenOnTheFirstVisitAndStaysOpenWhenTheVisitIsWritten() = runTest(dispatcher) {
        open(everything.copy(securitySeenAt = null))
        val vm = viewModel()
        reports.report.value = reportOf(everything)
        advanceUntilIdle()
        assertTrue(vm.state.value!!.whyOpen)
        open(everything.copy(securitySeenAt = NOW)) // the visit is written while the screen is open
        advanceUntilIdle()
        assertTrue("it does not fold under the user's eyes", vm.state.value!!.whyOpen)
        vm.toggleWhy()
        advanceUntilIdle()
        assertFalse(vm.state.value!!.whyOpen)
        vm.toggleWhy()
        advanceUntilIdle()
        assertTrue(vm.state.value!!.whyOpen)
    }

    @Test
    fun whyThisMattersIsFoldedOnLaterVisits() = runTest(dispatcher) {
        open(everything.copy(securitySeenAt = NOW - DAY))
        val vm = viewModel()
        reports.report.value = reportOf(everything)
        advanceUntilIdle()
        assertFalse(vm.state.value!!.whyOpen)
    }

    // ---- The visit that clears the dot ----

    @Test
    fun theVisitMarksTheResultsSeenOnlyWhenTheDotIsOnAndNoNoticeIsOverTheTab() = runTest(dispatcher) {
        open(everything)
        val vm = viewModel()
        lock.value = LockUiState.Unlocked(listOf(Notice.OlderCopy(1L, NOW - DAY)), null)
        reports.unseen.value = true
        val shown = scope.launch { vm.whileShown() }
        advanceUntilIdle()
        assertEquals("the older-copy notice is over the tab: nothing is written before it is answered", 0, marks)

        lock.value = LockUiState.Unlocked(emptyList(), null)
        advanceUntilIdle()
        assertEquals("answered: the results are seen", 1, marks)

        reports.unseen.value = false // the runner saw the write
        advanceUntilIdle()
        assertEquals(1, marks)

        reports.unseen.value = true // a new result while the tab is in view
        advanceUntilIdle()
        assertEquals(2, marks)

        shown.cancel()
        reports.unseen.value = false
        advanceUntilIdle()
        reports.unseen.value = true // the tab is no longer shown
        advanceUntilIdle()
        assertEquals("not shown, not seen", 2, marks)
    }

    @Test
    fun withoutADotTheVisitWritesNothing() = runTest(dispatcher) {
        open(everything)
        val vm = viewModel()
        val shown = scope.launch { vm.whileShown() }
        advanceUntilIdle()
        assertEquals(0, marks)
        shown.cancel()
    }

    @Test
    fun theLockScreenIsNoVisit() = runTest(dispatcher) {
        open(everything)
        val vm = viewModel()
        lock.value = LockUiState.Starting
        reports.unseen.value = true
        val shown = scope.launch { vm.whileShown() }
        advanceUntilIdle()
        assertEquals(0, marks)
        shown.cancel()
    }
}
