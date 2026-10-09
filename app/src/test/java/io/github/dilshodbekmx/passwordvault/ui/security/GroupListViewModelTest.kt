package io.github.dilshodbekmx.passwordvault.ui.security

import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.core.strength.SecurityCheck
import io.github.dilshodbekmx.passwordvault.data.SaveProblem
import io.github.dilshodbekmx.passwordvault.fake.FakeSecurityReports
import io.github.dilshodbekmx.passwordvault.fake.FakeVaultRepository
import io.github.dilshodbekmx.passwordvault.platform.AppDispatchers
import io.github.dilshodbekmx.passwordvault.ui.security.SecurityKit.NOW
import io.github.dilshodbekmx.passwordvault.ui.security.SecurityKit.entry
import io.github.dilshodbekmx.passwordvault.ui.security.SecurityKit.id
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.toList
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
 * Build plan 7.1, [JVM]: the list behind a card (ux.md §5.16, "Group list"). A group's accounts come
 * email first, then favourites, then A to Z; the lists of passwords easy to guess, of changes not
 * finished and of accounts left out; "Leave out of the security check" with Undo and "Check it
 * again"; a group that no longer exists; nothing left once the vault locks.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GroupListViewModelTest {
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

    /**
     * One password on seven accounts: three look like email (by name or by website), one is a
     * favourite. Two passwords are easy to guess; one change waits; one account is left out.
     */
    private val vault = Vault(
        entries = listOf(
            entry(1, "Telegram", password = "Shared-Pw-1", username = "+998901234567"),
            entry(2, "Facebook", password = "Shared-Pw-1", username = "aziz@gmail.com"),
            entry(3, "Yandex", password = "Shared-Pw-1", urls = listOf("https://passport.yandex.ru")),
            entry(4, "Uzum", password = "Shared-Pw-1", favourite = true),
            entry(5, "Work", password = "Shared-Pw-1", urls = listOf("https://outlook.office.com")),
            entry(6, "Oʻzbekiston pochtasi", password = "Shared-Pw-1"),
            entry(7, "Apelsin", password = "Shared-Pw-1"),
            entry(8, "Old forum", password = "Toshkent2020"),
            entry(9, "Kundalik", password = "Mustaqillik1991", favourite = true),
            entry(10, "Bank", password = "New-Bank-Pw-9", changePending = true, pendingOldPassword = "Old-Bank-Pw-3"),
            entry(11, "Card PIN", password = "123456", checkIgnored = true),
        ),
    )

    private fun reportOf(v: Vault) = SecurityCheck.run(v, SecurityKit::easyToGuess)

    private fun viewModel(listId: String, repository: FakeVaultRepository): GroupListViewModel {
        reports.report.value = reportOf(repository.stored)
        return GroupListViewModel(listId, reports, repository, AppDispatchers.single(dispatcher))
    }

    /** Follows the vault as the runner would: a new report after every save. */
    private fun follow(repository: FakeVaultRepository) {
        scope.launch { repository.state.collect { state -> if (state is io.github.dilshodbekmx.passwordvault.data.VaultState.Unlocked) reports.report.value = reportOf(state.vault) } }
    }

    @Test
    fun aGroupComesEmailFirstThenFavouritesThenAToZ() = runTest(dispatcher) {
        val repository = FakeVaultRepository(vault, clock = { NOW })
        val group = reportOf(vault).reused.single()
        val vm = viewModel(group.id, repository)
        advanceUntilIdle()
        assertEquals(GroupKind.REUSED, vm.kind)
        assertEquals(
            "email by name or website first (Oʻzbekiston pochtasi, Work at outlook, Yandex), then the favourite Uzum, then A to Z",
            listOf("Oʻzbekiston pochtasi", "Work", "Yandex", "Uzum", "Apelsin", "Facebook", "Telegram"),
            vm.state.value!!.rows!!.map { it.title },
        )
        assertEquals("aziz@gmail.com", vm.state.value!!.rows!!.first { it.title == "Facebook" }.username)
        assertFalse("a username at gmail does not make an account email", vm.state.value!!.rows!!.take(3).any { it.title == "Facebook" })
    }

    @Test
    fun theOtherListsBehindTheCards() = runTest(dispatcher) {
        val repository = FakeVaultRepository(vault, clock = { NOW })
        val weak = viewModel(GroupKind.WEAK_ID, repository)
        val pending = viewModel(GroupKind.PENDING_ID, repository)
        val leftOut = viewModel(GroupKind.LEFT_OUT_ID, repository)
        advanceUntilIdle()
        assertEquals(GroupKind.WEAK, weak.kind)
        assertEquals("the favourite first", listOf("Kundalik", "Old forum"), weak.state.value!!.rows!!.map { it.title })
        assertEquals(GroupKind.PENDING, pending.kind)
        assertEquals(listOf("Bank"), pending.state.value!!.rows!!.map { it.title })
        assertEquals(GroupKind.LEFT_OUT, leftOut.kind)
        assertEquals(listOf(GroupRow(id(11), "Card PIN", "")), leftOut.state.value!!.rows)
    }

    @Test
    fun leaveOutTakesTheAccountOutOfItsGroupAndUndoPutsItBack() = runTest(dispatcher) {
        val repository = FakeVaultRepository(vault, clock = { NOW })
        follow(repository)
        val group = reportOf(vault).reused.single()
        val vm = viewModel(group.id, repository)
        val events = mutableListOf<GroupEvent>()
        scope.launch { vm.events.toList(events) }
        advanceUntilIdle()

        vm.leaveOut(id(2))
        advanceUntilIdle()
        assertTrue(repository.stored.entries.first { it.id == id(2) }.checkIgnored)
        assertFalse("Facebook left the group", vm.state.value!!.rows!!.any { it.id == id(2) })
        val left = events.single() as GroupEvent.LeftOut
        assertEquals(id(2), left.id)
        assertEquals("Facebook", left.title)
        assertEquals("LeftOut(id=${id(2)})", left.toString())

        vm.undoLeaveOut(id(2))
        advanceUntilIdle()
        assertFalse(repository.stored.entries.first { it.id == id(2) }.checkIgnored)
        assertTrue(vm.state.value!!.rows!!.any { it.id == id(2) })
    }

    @Test
    fun checkItAgainBringsAnAccountBackIntoTheCheck() = runTest(dispatcher) {
        val repository = FakeVaultRepository(vault, clock = { NOW })
        follow(repository)
        val vm = viewModel(GroupKind.LEFT_OUT_ID, repository)
        val events = mutableListOf<GroupEvent>()
        scope.launch { vm.events.toList(events) }
        advanceUntilIdle()

        vm.checkAgain(id(11))
        advanceUntilIdle()
        assertFalse(repository.stored.entries.first { it.id == id(11) }.checkIgnored)
        assertEquals("the list of accounts left out is empty now", emptyList<GroupRow>(), vm.state.value!!.rows)
        assertEquals("Card PIN", (events.single() as GroupEvent.CheckedAgain).title)
        assertTrue("its PIN is easy to guess: it is reported again", reports.report.value!!.weak.contains(id(11)))
    }

    @Test
    fun aGroupWhoseAccountsAllHaveTheirOwnPasswordNowIsEmpty() = runTest(dispatcher) {
        val two = Vault(entries = listOf(entry(1, "Gmail", password = "Same-Pw-1"), entry(2, "Facebook", password = "Same-Pw-1")))
        val repository = FakeVaultRepository(two, clock = { NOW })
        follow(repository)
        val group = reportOf(two).reused.single()
        val vm = viewModel(group.id, repository)
        advanceUntilIdle()
        assertEquals(2, vm.state.value!!.rows!!.size)

        // Facebook gets its own password; a group of one is no group.
        repository.edit { v, now -> io.github.dilshodbekmx.passwordvault.core.model.Edited(v.copy(entries = v.entries.map { if (it.id == id(2)) it.copy(password = "Own-Pw-2", updatedAt = now) else it }), Unit) }
        advanceUntilIdle()
        assertEquals(GroupUi(emptyList(), changed = 0), vm.state.value)

        val gone = GroupListViewModel(id(42), reports, repository, AppDispatchers.single(dispatcher))
        advanceUntilIdle()
        assertEquals("an id no group has", emptyList<GroupRow>(), gone.state.value!!.rows)
    }

    @Test
    fun aGroupCountsTheAccountsThatLeftItByAChange() = runTest(dispatcher) {
        val v = Vault(
            entries = listOf(
                entry(1, "Gmail", password = "Own-Pw-1", history = listOf("Same-Pw-1")),
                entry(2, "Facebook", password = "Same-Pw-1"),
                entry(3, "Instagram", password = "Same-Pw-1"),
            ),
        )
        val repository = FakeVaultRepository(v, clock = { NOW })
        val group = reportOf(v).reused.single()
        assertEquals("the group keeps the id of its oldest account, Gmail, after Gmail left it", id(1), group.id)
        val vm = viewModel(group.id, repository)
        advanceUntilIdle()
        assertEquals(GroupUi(listOf(GroupRow(id(2), "Facebook", ""), GroupRow(id(3), "Instagram", "")), changed = 1), vm.state.value)
    }

    @Test
    fun beforeTheFirstReportTheListWaits() = runTest(dispatcher) {
        val repository = FakeVaultRepository(vault, clock = { NOW })
        val vm = GroupListViewModel(GroupKind.WEAK_ID, reports, repository, AppDispatchers.single(dispatcher))
        advanceUntilIdle()
        assertEquals(GroupUi(rows = null, changed = 0), vm.state.value)
    }

    @Test
    fun aChangeThatIsNotSavedSaysSoWithTryAgain() = runTest(dispatcher) {
        val repository = FakeVaultRepository(vault, clock = { NOW })
        val vm = viewModel(GroupKind.WEAK_ID, repository)
        val events = mutableListOf<GroupEvent>()
        scope.launch { vm.events.toList(events) }
        advanceUntilIdle()
        repository.failures.add(SaveProblem.FAILED)
        vm.leaveOut(id(8))
        advanceUntilIdle()
        val failed = events.single() as GroupEvent.NotSaved
        assertEquals(SaveProblem.FAILED, failed.problem)
        failed.retry!!.invoke()
        advanceUntilIdle()
        assertTrue("Try again saved it", repository.stored.entries.first { it.id == id(8) }.checkIgnored)

        events.clear()
        repository.failures.add(SaveProblem.TOO_LARGE)
        vm.undoLeaveOut(id(8))
        advanceUntilIdle()
        assertNull("too large: another try cannot help", (events.single() as GroupEvent.NotSaved).retry)
    }

    @Test
    fun lockedTheListShowsNothingAndDropsTheNames() = runTest(dispatcher) {
        val repository = FakeVaultRepository(vault, clock = { NOW })
        val vm = viewModel(GroupKind.WEAK_ID, repository)
        advanceUntilIdle()
        assertEquals(2, vm.state.value!!.rows!!.size)
        repository.close()
        advanceUntilIdle()
        assertNull(vm.state.value)
    }

    @Test
    fun theRowsNameNoAccountInLogs() {
        assertEquals("GroupRow(id=${id(1)})", GroupRow(id(1), "Gmail", "aziz@gmail.com").toString())
        assertEquals("MasterPassword(count=2)", SecurityCard.MasterPassword(2, listOf("Gmail", "Facebook")).toString())
        assertEquals("Pending(count=1, only=true)", SecurityCard.Pending(1, listOf("Bank"), id(10)).toString())
        assertEquals("CheckedAgain", GroupEvent.CheckedAgain("Gmail").toString())
    }
}
