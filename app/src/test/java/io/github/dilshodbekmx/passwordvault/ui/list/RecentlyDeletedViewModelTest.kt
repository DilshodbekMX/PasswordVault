package io.github.dilshodbekmx.passwordvault.ui.list

import io.github.dilshodbekmx.passwordvault.core.model.Entry
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.data.SaveProblem
import io.github.dilshodbekmx.passwordvault.fake.FakeVaultRepository
import io.github.dilshodbekmx.passwordvault.platform.AppDispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Build plan 3.5, [JVM]: Recently deleted (ux.md §5.23): the deleted accounts newest first, Restore,
 * "Delete forever" and "Empty" after their questions, and nothing left once the vault locks.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecentlyDeletedViewModelTest {
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

    private val now = 1_791_244_800_000L
    private val day = 86_400_000L

    private fun entry(id: String, title: String, deletedAt: Long?) =
        Entry(id = id, title = title, password = "pw", createdAt = now - 40 * day, updatedAt = now - 40 * day, deletedAt = deletedAt)

    private val vault = Vault(
        entries = listOf(
            entry("tg", "Telegram", deletedAt = now - 3 * day),
            entry("gm", "Gmail", deletedAt = null),
            entry("fb", "Facebook", deletedAt = now - day),
            entry("bk", "Bank", deletedAt = now - 3 * day),
        ),
    )

    private fun viewModel(repository: FakeVaultRepository) = RecentlyDeletedViewModel(repository, AppDispatchers.single(dispatcher))

    @Test
    fun deletedAccountsNewestFirstThenByTitle() = runTest(dispatcher) {
        val vm = viewModel(FakeVaultRepository(vault, clock = { now }))
        advanceUntilIdle()
        assertEquals(
            listOf(DeletedRow("fb", "Facebook", now - day), DeletedRow("bk", "Bank", now - 3 * day), DeletedRow("tg", "Telegram", now - 3 * day)),
            vm.rows.value,
        )
    }

    @Test
    fun restoreBringsTheAccountBack() = runTest(dispatcher) {
        val repository = FakeVaultRepository(vault, clock = { now })
        val vm = viewModel(repository)
        val events = mutableListOf<DeletedEvent>()
        scope.launch { vm.events.toList(events) }
        advanceUntilIdle()

        vm.restore("fb")
        advanceUntilIdle()
        assertNull(repository.stored.entries.first { it.id == "fb" }.deletedAt)
        assertEquals(listOf("bk", "tg"), vm.rows.value?.map { it.id })
        assertEquals("Restored", events.single().toString())
        assertEquals("Facebook", (events.single() as DeletedEvent.Restored).title)
    }

    @Test
    fun deleteForeverAsksFirstAndRemovesOnlyThatAccount() = runTest(dispatcher) {
        val repository = FakeVaultRepository(vault, clock = { now })
        val vm = viewModel(repository)
        advanceUntilIdle()

        vm.askDeleteForever("tg")
        assertEquals(DeletedDialog.Forever("tg", "Telegram"), vm.dialog.value)
        vm.dismiss()
        assertNull(vm.dialog.value)
        assertEquals("Cancel changes nothing", 4, repository.stored.entries.size)

        vm.askDeleteForever("tg")
        vm.confirm()
        advanceUntilIdle()
        assertNull(vm.dialog.value)
        assertEquals(listOf("gm", "fb", "bk"), repository.stored.entries.map { it.id })
        assertEquals(listOf("fb", "bk"), vm.rows.value?.map { it.id })

        vm.askDeleteForever("gm")
        assertNull("an account outside Recently deleted is not offered", vm.dialog.value)
    }

    @Test
    fun emptyAsksWithTheCountAndRemovesEveryDeletedAccount() = runTest(dispatcher) {
        val repository = FakeVaultRepository(vault, clock = { now })
        val vm = viewModel(repository)
        advanceUntilIdle()

        vm.askEmpty()
        assertEquals(DeletedDialog.Empty(3), vm.dialog.value)
        vm.confirm()
        advanceUntilIdle()
        assertEquals(listOf("gm"), repository.stored.entries.map { it.id })
        assertEquals(emptyList<DeletedRow>(), vm.rows.value)

        vm.askEmpty()
        assertNull("nothing to empty, no question", vm.dialog.value)
    }

    @Test
    fun aFailedSaveIsToldAndChangesNothing() = runTest(dispatcher) {
        val repository = FakeVaultRepository(vault, clock = { now })
        val vm = viewModel(repository)
        val events = mutableListOf<DeletedEvent>()
        scope.launch { vm.events.toList(events) }
        advanceUntilIdle()

        repository.failures += SaveProblem.TOO_LARGE
        vm.restore("fb")
        advanceUntilIdle()
        assertEquals(listOf<DeletedEvent>(DeletedEvent.NotSaved(SaveProblem.TOO_LARGE)), events)
        assertEquals(now - day, repository.stored.entries.first { it.id == "fb" }.deletedAt)
    }

    @Test
    fun aLockDropsTheRowsAndTheQuestion() = runTest(dispatcher) {
        val repository = FakeVaultRepository(vault, clock = { now })
        val vm = viewModel(repository)
        advanceUntilIdle()
        vm.askEmpty()

        repository.close()
        advanceUntilIdle()
        assertNull(vm.rows.value)
        assertNull(vm.dialog.value)
        vm.confirm()
        advanceUntilIdle()
        assertEquals("nothing was deleted after the lock", 4, repository.stored.entries.size)
    }
}
