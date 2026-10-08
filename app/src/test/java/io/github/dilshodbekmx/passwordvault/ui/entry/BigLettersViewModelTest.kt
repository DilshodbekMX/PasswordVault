package io.github.dilshodbekmx.passwordvault.ui.entry

import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.fake.FakeVaultRepository
import io.github.dilshodbekmx.passwordvault.ui.entry.EntryKit.Companion.GMAIL
import io.github.dilshodbekmx.passwordvault.ui.entry.EntryKit.Companion.NOBODY
import io.github.dilshodbekmx.passwordvault.ui.entry.EntryKit.Companion.OLD_PASSWORD
import io.github.dilshodbekmx.passwordvault.ui.entry.EntryKit.Companion.PASSWORD
import io.github.dilshodbekmx.passwordvault.ui.entry.EntryKit.Companion.PIN
import io.github.dilshodbekmx.passwordvault.ui.entry.EntryKit.Companion.gmail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Build plan 3.7, [JVM]: "Show in big letters" (ux.md §5.13; architecture.md D-9d): the value the
 * route names, cut into boxes; the mark that keeps the place; and every way the screen ends: two
 * minutes without a touch, Close, a lock, nothing to show. It leaves once.
 */
class BigLettersViewModelTest {

    private fun text(vm: BigLettersViewModel) = vm.state.value.cells.joinToString("") { it.text }

    @Test
    fun itShowsTheValueTheRouteNames() = EntryKit.run {
        val repository = repository(Vault(entries = listOf(gmail(changePending = true, pendingOldPassword = OLD_PASSWORD))))
        val cases = listOf(
            Triple("password", EntryValue.Password, PASSWORD),
            Triple("old-password", EntryValue.OldPassword, OLD_PASSWORD),
            Triple("username", EntryValue.Username, "aziz@gmail.com"),
            Triple("previous-0", EntryValue.Previous(0), "winter-2025"),
            Triple("previous-1", EntryValue.Previous(1), "autumn-2024"),
            Triple("field-0", EntryValue.Field(0), PIN),
            Triple("field-1", EntryValue.Field(1), "+998901234567"),
        )
        for ((field, value, expected) in cases) {
            val state = bigLetters(repository, field = field).state.value
            assertFalse("$field is shown", state.closed)
            assertEquals(field, value, state.value)
            assertEquals(field, expected, state.cells.joinToString("") { it.text })
            assertEquals("Gmail", state.title)
            assertTrue(state.changePending)
            assertFalse(state.tooLong)
            assertEquals("nothing is marked at first", BigLettersViewModel.NOT_MARKED, state.markedUpTo)
        }
        assertEquals("the label of an extra field is its title", "PIN code", bigLetters(repository, field = "field-0").state.value.fieldName)
        assertNull(bigLetters(repository, field = "password").state.value.fieldName)
    }

    @Test
    fun withNothingToShowItIsClosedAtOnce() = EntryKit.run {
        val repository = repository(Vault(entries = listOf(gmail(), gmail(deletedAt = 5L).copy(id = NOBODY))))
        // No such value, a value the account does not have, a name that is none, another account, a deleted one.
        val closed = listOf(
            bigLetters(repository, field = "old-password"),
            bigLetters(repository, field = "previous-2"),
            bigLetters(repository, field = "field-2"),
            bigLetters(repository, field = "website-0"),
            bigLetters(repository, field = "notes"),
            bigLetters(repository, field = ""),
            bigLetters(repository, id = "00000000-0000-4000-8000-000000000000"),
            bigLetters(repository, id = NOBODY),
        )
        for (vm in closed) {
            assertEquals(BigLettersUiState.CLOSED, vm.state.value)
            assertTrue("the screen leaves", vm.takeClose())
        }

        // A locked vault: nothing is read.
        repository.close()
        settle()
        assertEquals(BigLettersUiState.CLOSED, bigLetters(repository).state.value)
    }

    @Test
    fun itClosesAfterTwoMinutesWithoutATouch() = EntryKit.run {
        val vm = bigLetters(repository(Vault(entries = listOf(gmail()))))
        assertEquals(PASSWORD, text(vm))

        pass(BigLettersViewModel.IDLE_CLOSE_MS - 1)
        assertFalse("still open one millisecond before the two minutes", vm.state.value.closed)
        assertFalse("and the screen stays", vm.takeClose())
        pass(1)
        assertEquals("closed, with nothing of the value left", BigLettersUiState.CLOSED, vm.state.value)
        assertEquals(120_000L, BigLettersViewModel.IDLE_CLOSE_MS)
    }

    @Test
    fun anyTouchRestartsTheTwoMinutes() = EntryKit.run {
        val vm = bigLetters(repository(Vault(entries = listOf(gmail()))))
        // A touch anywhere, and a tap on a box, each give two more minutes.
        pass(100_000)
        vm.touched()
        pass(100_000)
        vm.mark(3)
        pass(BigLettersViewModel.IDLE_CLOSE_MS - 1)
        assertFalse(vm.state.value.closed)
        assertEquals(PASSWORD, text(vm))
        pass(1)
        assertTrue(vm.state.value.closed)
    }

    @Test
    fun aTapMarksTheBoxAndEveryBoxBeforeIt() = EntryKit.run {
        val vm = bigLetters(repository(Vault(entries = listOf(gmail()))))
        vm.mark(4)
        assertEquals("boxes 0 to 4 are highlighted", 4, vm.state.value.markedUpTo)
        vm.mark(9)
        assertEquals(9, vm.state.value.markedUpTo)
        vm.mark(2)
        assertEquals("a tap further back moves the mark back", 2, vm.state.value.markedUpTo)
        vm.mark(2)
        assertEquals("a tap on the last marked box takes the mark away", BigLettersViewModel.NOT_MARKED, vm.state.value.markedUpTo)
        vm.mark(0)
        assertEquals(0, vm.state.value.markedUpTo)

        // No such box: nothing changes.
        vm.mark(PASSWORD.length)
        vm.mark(-1)
        assertEquals(0, vm.state.value.markedUpTo)
        assertEquals(PASSWORD, text(vm))
    }

    @Test
    fun moreThan128CharactersAreTooLong() = EntryKit.run {
        val exactly = "a".repeat(BigCell.MAX)
        val fits = bigLetters(repository(Vault(entries = listOf(gmail(password = exactly))))).state.value
        assertFalse(fits.tooLong)
        assertEquals(128, fits.cells.size)

        val over = bigLetters(repository(Vault(entries = listOf(gmail(password = exactly + "b"))))).state.value
        assertTrue("\"Too long to show in big letters\"", over.tooLong)
        assertTrue("and nothing of it is held", over.cells.isEmpty())
        assertFalse("the screen stays, to say so", over.closed)
    }

    @Test
    fun closeDropsTheValueAndTheScreenLeavesOnce() = EntryKit.run {
        val vm = bigLetters(repository(Vault(entries = listOf(gmail()))))
        vm.mark(5)
        assertFalse("nothing to leave for while it is open", vm.takeClose())

        vm.close()
        assertEquals(BigLettersUiState.CLOSED, vm.state.value)
        assertTrue(vm.state.value.cells.isEmpty())
        assertTrue("the screen goes back", vm.takeClose())
        // Close, Back, the stopped screen and the state can all report the same end.
        assertFalse("but one step only", vm.takeClose())
        vm.close()
        assertFalse(vm.takeClose())

        // The two minutes that were running do nothing more.
        pass(BigLettersViewModel.IDLE_CLOSE_MS * 2)
        assertEquals(BigLettersUiState.CLOSED, vm.state.value)
        vm.mark(1)
        assertEquals(BigLettersUiState.CLOSED, vm.state.value)
    }

    @Test
    fun aLockClosesIt() = EntryKit.run {
        val repository: FakeVaultRepository = repository(Vault(entries = listOf(gmail())))
        val vm = bigLetters(repository)
        assertEquals(PASSWORD, text(vm))

        repository.close()
        settle()
        assertEquals("never kept across a lock (architecture.md D-9d)", BigLettersUiState.CLOSED, vm.state.value)
        assertTrue(vm.takeClose())

        // An unlock brings nothing back.
        repository.open()
        settle()
        assertEquals(BigLettersUiState.CLOSED, vm.state.value)
    }

    @Test
    fun aChangeOfTheVaultDoesNotChangeWhatIsShown() = EntryKit.run {
        val repository = repository(Vault(entries = listOf(gmail())))
        val vm = bigLetters(repository)
        repository.open(Vault(entries = listOf(gmail(password = "another one"))))
        settle()
        assertEquals("what was opened is what is read", PASSWORD, text(vm))
        assertFalse(vm.state.value.closed)
    }

    @Test
    fun toStringShowsNoText() = EntryKit.run {
        val vm = bigLetters(repository(Vault(entries = listOf(gmail()))), field = "field-0")
        val printed = vm.state.value.toString()
        for (text in listOf("Gmail", PIN, "PIN code")) assertFalse("\"$text\" in $printed", text in printed)
    }

    @Test
    fun aClearedScreenIsClosed() = EntryKit.run {
        val vm = bigLetters(repository(Vault(entries = listOf(gmail()))), id = GMAIL)
        clear()
        assertEquals(BigLettersUiState.CLOSED, vm.state.value)
    }
}
