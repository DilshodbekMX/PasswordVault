package io.github.dilshodbekmx.passwordvault.ui.entry

import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.clipboard.ClipStatus
import io.github.dilshodbekmx.passwordvault.clipboard.SecretClipboard
import io.github.dilshodbekmx.passwordvault.core.model.Edited
import io.github.dilshodbekmx.passwordvault.core.model.Entry
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.core.model.VaultEdits
import io.github.dilshodbekmx.passwordvault.data.SaveProblem
import io.github.dilshodbekmx.passwordvault.fake.FakeVaultRepository
import io.github.dilshodbekmx.passwordvault.i18n.UiText
import io.github.dilshodbekmx.passwordvault.ui.entry.EntryKit.Companion.ADDED_MS
import io.github.dilshodbekmx.passwordvault.ui.entry.EntryKit.Companion.BANK
import io.github.dilshodbekmx.passwordvault.ui.entry.EntryKit.Companion.DAY_MS
import io.github.dilshodbekmx.passwordvault.ui.entry.EntryKit.Companion.GMAIL
import io.github.dilshodbekmx.passwordvault.ui.entry.EntryKit.Companion.NOBODY
import io.github.dilshodbekmx.passwordvault.ui.entry.EntryKit.Companion.NOTE
import io.github.dilshodbekmx.passwordvault.ui.entry.EntryKit.Companion.OLD_PASSWORD
import io.github.dilshodbekmx.passwordvault.ui.entry.EntryKit.Companion.PASSWORD
import io.github.dilshodbekmx.passwordvault.ui.entry.EntryKit.Companion.PIN
import io.github.dilshodbekmx.passwordvault.ui.entry.EntryKit.Companion.bare
import io.github.dilshodbekmx.passwordvault.ui.entry.EntryKit.Companion.entryOf
import io.github.dilshodbekmx.passwordvault.ui.entry.EntryKit.Companion.gmail
import io.github.dilshodbekmx.passwordvault.ui.entry.EntryKit.Companion.shown
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Build plan 3.7, [JVM]: the account screen's ViewModel against the fakes of `APP/fake/` (ux.md
 * §5.12, §3, §6; architecture.md §5.4, §5.5). The acceptance line's four cases are the first four
 * tests: a shown value hides again 30 seconds after the last touch; both passwords are shown while
 * a change is pending; delete with undo; a copy goes to `SecretClipboard` with the sensitive flag.
 */
class EntryViewModelTest {

    private fun vaultOf(vararg entries: Entry, matches: List<String> = emptyList()) =
        Vault(entries = entries.toList(), masterPasswordMatches = matches)

    private fun password(vm: EntryViewModel): SecretRow = shown(vm).passwords.single()

    // ---- The acceptance line -----------------------------------------------------------------

    @Test
    fun aShownPasswordHidesAgain30SecondsAfterTheLastTouch() = EntryKit.run {
        val vm = screen(repository(vaultOf(gmail())))
        assertNull("hidden when the screen opens", password(vm).shown)

        vm.setShown(EntryValue.Password, true)
        assertEquals(PASSWORD, password(vm).shown)
        pass(EntryViewModel.REHIDE_AFTER_MS - 1)
        assertEquals("still shown one millisecond before the 30 seconds", PASSWORD, password(vm).shown)
        pass(1)
        assertNull("hidden 30 seconds after the last touch", password(vm).shown)

        // A touch restarts the time: 30 seconds from the touch, not from "Show" (ux.md §3).
        vm.setShown(EntryValue.Password, true)
        pass(20_000)
        vm.touched()
        pass(EntryViewModel.REHIDE_AFTER_MS - 1)
        assertEquals("49.999 s after Show, 29.999 s after the touch", PASSWORD, password(vm).shown)
        pass(1)
        assertNull(password(vm).shown)

        // Several touches in a row: only the last one counts.
        vm.setShown(EntryValue.Password, true)
        repeat(5) {
            pass(25_000)
            vm.touched()
            assertEquals(PASSWORD, password(vm).shown)
        }
        pass(EntryViewModel.REHIDE_AFTER_MS)
        assertNull(password(vm).shown)
        assertEquals("thirty seconds", 30_000L, EntryViewModel.REHIDE_AFTER_MS)
    }

    @Test
    fun bothPasswordsAreShownWhileAChangeIsPending() = EntryKit.run {
        val repository = repository(vaultOf(gmail(changePending = true, pendingOldPassword = OLD_PASSWORD)))
        val vm = screen(repository)
        val details = shown(vm)

        // Two rows in place of one, the old password first (ux.md §5.12), and the banner's date.
        assertEquals(listOf(EntryValue.OldPassword, EntryValue.Password), details.passwords.map { it.value })
        assertTrue(details.changePending)
        assertEquals("when the new password was made", ADDED_MS + 2 * DAY_MS, details.changeStartedAtMs)
        assertNull("no \"Password changed …\" while the website may still have the old one", details.passwordChangedAtMs)
        assertTrue("both hidden at first", details.passwords.all { it.shown == null })

        // Each has its own show: one, then both at once.
        vm.setShown(EntryValue.OldPassword, true)
        assertEquals(listOf(OLD_PASSWORD, null), shown(vm).passwords.map { it.shown })
        vm.setShown(EntryValue.Password, true)
        assertEquals(listOf(OLD_PASSWORD, PASSWORD), shown(vm).passwords.map { it.shown })
        vm.setShown(EntryValue.OldPassword, false)
        assertEquals(listOf(null, PASSWORD), shown(vm).passwords.map { it.shown })

        // Each has its own copy, named for what it is, both sensitive.
        assertTrue(vm.copy(EntryValue.OldPassword))
        assertTrue(vm.copy(EntryValue.Password))
        val (old, new) = clipboard.copies
        assertEquals(OLD_PASSWORD, old.value)
        assertEquals(UiText.of(R.string.entry_copied_old_password, "Gmail"), old.what)
        assertTrue(old.sensitive)
        assertEquals(PASSWORD, new.value)
        assertEquals(UiText.of(R.string.entry_copied_new_password, "Gmail"), new.what)
        assertTrue(new.sensitive)

        // "Yes, it's changed": one password again, the old one among the previous, nothing shown.
        vm.confirmChange()
        settle()
        val after = shown(vm)
        assertFalse(after.changePending)
        assertEquals(listOf(EntryValue.Password), after.passwords.map { it.value })
        assertNull("the passwords changed places: nothing stays shown", after.passwords.single().shown)
        assertEquals(PASSWORD, entryOf(repository).password)
        assertEquals(OLD_PASSWORD, entryOf(repository).passwordHistory.first().password)
        assertEquals(3, after.previousCount)
    }

    @Test
    fun deleteWithUndo() = EntryKit.run {
        val repository = repository(vaultOf(gmail(), bare()))
        val vm = screen(repository)
        val events = eventsOf(vm)
        vm.setShown(EntryValue.Password, true)

        // Delete: into Recently deleted at once, with no question; the screen offers Undo.
        vm.delete()
        settle()
        assertEquals(EntryUiState.Deleted("Gmail"), vm.state.value)
        assertEquals("deleted at the time of the tap", clock.wallMs(), entryOf(repository).deletedAt)
        assertEquals("the other account is untouched", null, entryOf(repository, BANK).deletedAt)
        assertFalse("nothing of the account is left in the state", PASSWORD in vm.state.value.toString())

        // Undo: the account is back as it was, with its secrets hidden.
        vm.undoDelete()
        settle()
        val back = shown(vm)
        assertEquals("Gmail", back.title)
        assertNull(entryOf(repository).deletedAt)
        assertNull("hidden after it came back", back.passwords.single().shown)
        assertEquals(PASSWORD, entryOf(repository).password)
        assertEquals(2, back.previousCount)
        assertTrue("no message other than the screen's own", events.isEmpty())

        // Deleted again and not undone: after a lock the undo is gone, and the account is said to
        // wait in Recently deleted (ux.md §5.23), from where it can be restored.
        vm.delete()
        settle()
        assertEquals(EntryUiState.Deleted("Gmail"), vm.state.value)
        repository.close()
        settle()
        assertEquals(EntryUiState.Loading, vm.state.value)
        repository.open()
        settle()
        assertEquals(EntryUiState.Gone(inRecentlyDeleted = true), vm.state.value)
        assertNotNull(entryOf(repository).deletedAt)
    }

    @Test
    fun aCopyGoesToSecretClipboardWithTheSensitiveFlag() = EntryKit.run {
        val vm = screen(repository(vaultOf(gmail())))
        vm.setPreviousExpanded(true)

        // The password, hidden as it is: copied without being shown, and flagged.
        assertTrue(vm.copy(EntryValue.Password))
        val copy = clipboard.copies.single()
        assertEquals(PASSWORD, copy.value)
        assertTrue("a password is sensitive", copy.sensitive)
        assertEquals(UiText.of(R.string.entry_copied_password, "Gmail"), copy.what)
        assertNull("copying does not show it", password(vm).shown)
        assertEquals(ClipStatus(copy.what, true, clock.elapsedMs() + 60_000L, 60_000L), clipboard.status.value)

        // Every other value: what is flagged and what is not (ux.md §6 item 1).
        val expected = listOf(
            Triple(EntryValue.Username, "aziz@gmail.com", false) to UiText.of(R.string.entry_copied_username, "Gmail"),
            Triple(EntryValue.Website(0), "https://mail.google.com/mail/u/0", false) to UiText.of(R.string.entry_copied_website, "Gmail"),
            Triple(EntryValue.Field(0), PIN, true) to UiText.of(R.string.entry_copied_field, "PIN code", "Gmail"),
            Triple(EntryValue.Field(1), "+998901234567", false) to UiText.of(R.string.entry_copied_field, "Phone number", "Gmail"),
            Triple(EntryValue.Previous(0), "winter-2025", true) to UiText.of(R.string.entry_copied_previous, "Gmail"),
            Triple(EntryValue.Previous(1), "autumn-2024", true) to UiText.of(R.string.entry_copied_previous, "Gmail"),
            Triple(EntryValue.Notes, NOTE, true) to UiText.of(R.string.entry_copied_notes, "Gmail"),
        )
        for ((case, what) in expected) {
            val (value, text, sensitive) = case
            assertTrue("copy of $value", vm.copy(value))
            val last = clipboard.copies.last()
            assertEquals("the text of $value", text, last.value)
            assertEquals("the flag of $value", sensitive, last.sensitive)
            assertEquals("the name of $value", what, last.what)
        }
        assertEquals(1 + expected.size, clipboard.copies.size)

        // Nothing to copy: no clip, and the button's check mark does not show.
        assertFalse(vm.copy(EntryValue.OldPassword))
        assertFalse(vm.copy(EntryValue.Field(7)))
        assertFalse(vm.copy(EntryValue.Website(9)))
        assertEquals(1 + expected.size, clipboard.copies.size)
    }

    // ---- Showing and hiding ------------------------------------------------------------------

    @Test
    fun aHiddenSecretIsNotInTheState() = EntryKit.run {
        val vm = screen(repository(vaultOf(gmail(changePending = true, pendingOldPassword = OLD_PASSWORD))))
        vm.setPreviousExpanded(true)
        val secrets = listOf(PASSWORD, OLD_PASSWORD, PIN, "winter-2025", "autumn-2024")

        fun held(details: EntryDetails): List<String?> =
            details.passwords.map { it.shown } + details.fields.filter { it.secret }.map { it.shown } + details.previous.map { it.shown }

        assertTrue("every secret is null while hidden", held(shown(vm)).all { it == null })
        assertEquals("the plain field is always there", "+998901234567", shown(vm).fields[1].shown)

        // Shown one at a time, only that one is held; and no toString ever prints one.
        val values = listOf(EntryValue.OldPassword, EntryValue.Password, EntryValue.Field(0), EntryValue.Previous(0), EntryValue.Previous(1))
        for ((value, secret) in values.zip(listOf(OLD_PASSWORD, PASSWORD, PIN, "winter-2025", "autumn-2024"))) {
            vm.setShown(value, true)
            assertEquals("only $value is held", listOf(secret), held(shown(vm)).filterNotNull())
            val printed = vm.state.value.toString()
            for (text in secrets + listOf("Gmail", "aziz@gmail.com", "Samarqand", "PIN code")) {
                assertFalse("\"$text\" is printed by toString: $printed", text in printed)
            }
            vm.setShown(value, false)
        }
        assertTrue(held(shown(vm)).all { it == null })

        // What is not a secret cannot be "shown": the username, a website, the plain field, the notes.
        for (value in listOf(EntryValue.Username, EntryValue.Website(0), EntryValue.Field(1), EntryValue.Notes, EntryValue.Field(9))) {
            vm.setShown(value, true)
        }
        assertTrue(held(shown(vm)).all { it == null })
    }

    @Test
    fun everyActionOnTheScreenCountsAsATouch() = EntryKit.run {
        val repository = repository(vaultOf(gmail()))
        val vm = screen(repository)
        vm.setShown(EntryValue.Password, true)

        // A copy, the star, "Show more", another Show: each restarts the 30 seconds.
        val actions = listOf<() -> Unit>(
            { vm.copy(EntryValue.Username) },
            { vm.setFavourite(true) },
            { vm.setNotesExpanded(true) },
            { vm.setShown(EntryValue.Field(0), true) },
            { vm.setPreviousExpanded(true) },
            { vm.setCheckIgnored(true) },
        )
        for (action in actions) {
            pass(25_000)
            action()
            settle()
            assertEquals("still shown after an action at 25 s", PASSWORD, password(vm).shown)
        }
        pass(EntryViewModel.REHIDE_AFTER_MS - 1)
        assertEquals(PASSWORD, password(vm).shown)
        pass(1)
        assertNull("the password", password(vm).shown)
        assertNull("and the field shown with it", shown(vm).fields[0].shown)
        assertTrue(entryOf(repository).favourite)
    }

    @Test
    fun theScreenStoppingHidesEverythingAtOnce() = EntryKit.run {
        val vm = screen(repository(vaultOf(gmail())))
        vm.setPreviousExpanded(true)
        vm.setShown(EntryValue.Password, true)
        vm.setShown(EntryValue.Field(0), true)
        vm.setShown(EntryValue.Previous(1), true)
        assertEquals(PIN, shown(vm).fields[0].shown)

        vm.screenStopped()
        val after = shown(vm)
        assertNull(after.passwords.single().shown)
        assertNull(after.fields[0].shown)
        assertTrue(after.previous.all { it.shown == null })
        assertTrue("what was open stays open", after.previousExpanded)

        // The old timer is gone with it: a value shown later gets its own 30 seconds.
        pass(20_000)
        vm.setShown(EntryValue.Password, true)
        pass(EntryViewModel.REHIDE_AFTER_MS - 1)
        assertEquals(PASSWORD, password(vm).shown)
        pass(1)
        assertNull(password(vm).shown)

        // With nothing shown, a stop changes nothing.
        val before = vm.state.value
        vm.screenStopped()
        assertEquals(before, vm.state.value)
    }

    @Test
    fun hidingTheLastShownValueStopsTheTimer() = EntryKit.run {
        val vm = screen(repository(vaultOf(gmail())))
        vm.setShown(EntryValue.Password, true)
        pass(10_000)
        vm.setShown(EntryValue.Password, false)
        assertNull(password(vm).shown)

        // Shown again 25 s later: it has its own 30 seconds, not the 5 that were left.
        pass(25_000)
        vm.setShown(EntryValue.Password, true)
        pass(EntryViewModel.REHIDE_AFTER_MS - 1)
        assertEquals(PASSWORD, password(vm).shown)
        pass(1)
        assertNull(password(vm).shown)
    }

    @Test
    fun aChangeOfTheAccountsSecretsHidesThem() = EntryKit.run {
        val repository = repository(vaultOf(gmail()))
        val vm = screen(repository)
        vm.setShown(EntryValue.Field(0), true)
        vm.setShown(EntryValue.Password, true)

        // The star does not touch a secret: what is shown stays.
        vm.setFavourite(true)
        settle()
        assertEquals(PIN, shown(vm).fields[0].shown)
        assertEquals(PASSWORD, password(vm).shown)

        // The form saved the account with its first field removed: the second field is now "field 0".
        // Nothing stays shown, or the phone number would appear in the row that was the PIN's.
        repository.edit { vault, _ ->
            val entry = vault.entries.single()
            Edited(vault.copy(entries = listOf(entry.copy(fields = entry.fields.drop(1)))), Unit)
        }
        settle()
        val after = shown(vm)
        assertEquals(listOf("Phone number"), after.fields.map { it.name })
        assertNull(after.passwords.single().shown)
        assertEquals("a plain field shows its value as always", "+998901234567", after.fields.single().shown)
    }

    @Test
    fun previousPasswordsOpenAndClose() = EntryKit.run {
        val vm = screen(repository(vaultOf(gmail())))
        assertEquals(2, shown(vm).previousCount)
        assertTrue("closed at first: no row", shown(vm).previous.isEmpty())
        assertFalse(shown(vm).previousExpanded)

        vm.setPreviousExpanded(true)
        assertEquals(listOf(0, 1), shown(vm).previous.map { it.index })
        assertEquals(listOf(ADDED_MS + 2 * DAY_MS, ADDED_MS + DAY_MS), shown(vm).previous.map { it.replacedAtMs })
        assertTrue("masked rows", shown(vm).previous.all { it.shown == null })

        vm.setShown(EntryValue.Previous(1), true)
        vm.setShown(EntryValue.Password, true)
        assertEquals(listOf(null, "autumn-2024"), shown(vm).previous.map { it.shown })

        // Closing hides the previous passwords that were shown; the password itself stays.
        vm.setPreviousExpanded(false)
        assertTrue(shown(vm).previous.isEmpty())
        assertEquals(PASSWORD, password(vm).shown)
        vm.setPreviousExpanded(true)
        assertTrue(shown(vm).previous.all { it.shown == null })
    }

    // ---- The vault ---------------------------------------------------------------------------

    @Test
    fun aLockDropsEverythingOfTheAccount() = EntryKit.run {
        val repository = repository(vaultOf(gmail()))
        val vm = screen(repository)
        vm.setShown(EntryValue.Password, true)
        vm.setNotesExpanded(true)
        vm.setPreviousExpanded(true)
        assertTrue(shown(vm).notesExpanded)

        repository.close()
        settle()
        assertEquals(EntryUiState.Loading, vm.state.value)

        // The 30 seconds that were running do nothing to the next session.
        pass(EntryViewModel.REHIDE_AFTER_MS)
        assertEquals(EntryUiState.Loading, vm.state.value)

        // After the unlock the same account, with its secrets masked again and everything closed (ux.md §4).
        repository.open()
        settle()
        val again = shown(vm)
        assertNull(again.passwords.single().shown)
        assertFalse(again.notesExpanded)
        assertFalse(again.previousExpanded)

        // While locked there is nothing to copy.
        repository.close()
        settle()
        assertFalse(vm.copy(EntryValue.Password))
        assertTrue(clipboard.copies.isEmpty())
    }

    @Test
    fun anAccountThatIsNotThereIsGone() = EntryKit.run {
        // No such account in the vault: "This account no longer exists."
        val nobody = screen(repository(vaultOf(gmail())), id = NOBODY)
        assertEquals(EntryUiState.Gone(inRecentlyDeleted = false), nobody.state.value)
        assertFalse(nobody.copy(EntryValue.Password))
        nobody.delete()
        nobody.setShown(EntryValue.Password, true)
        settle()
        assertEquals(EntryUiState.Gone(inRecentlyDeleted = false), nobody.state.value)

        // An account that waits in Recently deleted, opened by its route after an unlock: not shown,
        // and not "deleted here" either, so there is no Undo, only the way to Recently deleted.
        val repository = repository(vaultOf(gmail(deletedAt = ADDED_MS + 4 * DAY_MS)))
        val deleted = screen(repository)
        assertEquals(EntryUiState.Gone(inRecentlyDeleted = true), deleted.state.value)
        assertFalse(deleted.copy(EntryValue.Password))

        // Restored from Recently deleted while this screen is under it: the account is back.
        repository.edit(VaultEdits.restore(GMAIL))
        settle()
        assertEquals("Gmail", shown(deleted).title)

        // Removed for good while the screen is open ("Undo this import", a purge): gone.
        repository.edit { vault, _ -> Edited(vault.copy(entries = emptyList()), Unit) }
        settle()
        assertEquals(EntryUiState.Gone(inRecentlyDeleted = false), deleted.state.value)
    }

    @Test
    fun theScreenFollowsTheVault() = EntryKit.run {
        val repository = repository(vaultOf(gmail(), bare()))
        val vm = screen(repository)
        assertEquals("aziz@gmail.com", shown(vm).username)

        // The form saved another username and another account's change does nothing here.
        repository.edit { vault, now ->
            Edited(vault.copy(entries = vault.entries.map { if (it.id == GMAIL) it.copy(username = "aziz.k@gmail.com", updatedAt = now) else it.copy(favourite = true) }), Unit)
        }
        settle()
        assertEquals("aziz.k@gmail.com", shown(vm).username)
        assertEquals(clock.wallMs(), shown(vm).editedAtMs)
        assertEquals("Kapitalbank", shown(screen(repository, id = BANK)).title)
    }

    // ---- The marks and the banners -----------------------------------------------------------

    @Test
    fun theStarAndTheSecurityCheck() = EntryKit.run {
        val repository = repository(vaultOf(gmail()))
        val vm = screen(repository)
        assertFalse(shown(vm).favourite)
        assertFalse(shown(vm).checkIgnored)

        vm.setFavourite(true)
        settle()
        assertTrue(shown(vm).favourite)
        assertTrue(entryOf(repository).favourite)
        vm.setFavourite(false)
        settle()
        assertFalse(entryOf(repository).favourite)

        // "Leave out of the security check", then the banner's "Check it again" (architecture.md D-9a).
        vm.setCheckIgnored(true)
        settle()
        assertTrue(shown(vm).checkIgnored)
        assertTrue(entryOf(repository).checkIgnored)
        vm.setCheckIgnored(false)
        settle()
        assertFalse(shown(vm).checkIgnored)
        assertFalse(entryOf(repository).checkIgnored)
    }

    @Test
    fun thePasswordThatIsAlsoTheMasterPasswordIsMarked() = EntryKit.run {
        // The vault lists the accounts found equal at the last master-password entry (D-9b).
        val repository = repository(vaultOf(gmail(), bare(), matches = listOf(GMAIL)))
        assertTrue(shown(screen(repository)).sameAsMasterPassword)
        assertFalse(shown(screen(repository, id = BANK)).sameAsMasterPassword)
    }

    @Test
    fun noPutBackTheOldPassword() = EntryKit.run {
        val repository = repository(vaultOf(gmail(changePending = true, pendingOldPassword = OLD_PASSWORD)))
        val vm = screen(repository)
        vm.setShown(EntryValue.OldPassword, true)

        vm.revertChange()
        settle()
        val after = shown(vm)
        assertFalse(after.changePending)
        assertEquals("the website's password is the account's again", OLD_PASSWORD, entryOf(repository).password)
        assertEquals("the rejected new one is kept among the previous", PASSWORD, entryOf(repository).passwordHistory.first().password)
        assertNull(entryOf(repository).pendingOldPassword)
        assertEquals(listOf(EntryValue.Password), after.passwords.map { it.value })
        assertNull("nothing stays shown under another name", after.passwords.single().shown)
        assertEquals(clock.wallMs(), after.passwordChangedAtMs)

        // Without a pending change the banner's answers do nothing.
        val saves = repository.saves
        vm.confirmChange()
        vm.revertChange()
        settle()
        assertEquals(saves, repository.saves)
    }

    // ---- Messages ----------------------------------------------------------------------------

    @Test
    fun savedIsSaidOnceForAnAccountTheFormJustAdded() = EntryKit.run {
        val repository = repository(vaultOf(gmail()))
        assertEquals(listOf(EntryEvent.Saved), eventsOf(screen(repository, justSaved = true)))
        assertTrue(eventsOf(screen(repository)).isEmpty())
    }

    @Test
    fun aPhoneThatBlocksCopyingIsSaid() = EntryKit.run {
        val blocked = object : SecretClipboard {
            override val status = MutableStateFlow<ClipStatus?>(null)
            override fun copy(value: CharSequence, what: UiText, sensitive: Boolean) = throw SecurityException("no clipboard for you")
            override fun clearNow() = Unit
            override fun appCameToFront() = Unit
        }
        val vm = screen(repository(vaultOf(gmail())), clipboard = blocked)
        val events = eventsOf(vm)
        assertFalse("no check mark for a copy that did not happen", vm.copy(EntryValue.Password))
        settle()
        assertEquals(listOf<EntryEvent>(EntryEvent.CopyBlocked), events)
    }

    @Test
    fun aChangeThatIsNotSavedIsSaidWithTryAgain() = EntryKit.run {
        val repository = repository(vaultOf(gmail()))
        val vm = screen(repository)
        val events = eventsOf(vm)

        // The star: not saved, said, and "Try again" saves it.
        repository.failures += SaveProblem.FAILED
        vm.setFavourite(true)
        settle()
        val failed = events.single() as EntryEvent.NotSaved
        assertEquals(SaveProblem.FAILED, failed.problem)
        assertFalse(entryOf(repository).favourite)
        failed.retry!!.invoke()
        settle()
        assertTrue(entryOf(repository).favourite)
        assertEquals(1, events.size)

        // A vault that is too large: another try cannot help, so none is offered.
        repository.failures += SaveProblem.TOO_LARGE
        vm.setCheckIgnored(true)
        settle()
        val large = events[1] as EntryEvent.NotSaved
        assertEquals(SaveProblem.TOO_LARGE, large.problem)
        assertNull(large.retry)

        // A delete that is not saved deletes nothing: the account stays on the screen.
        repository.failures += SaveProblem.NOT_DURABLE
        vm.delete()
        settle()
        assertEquals("Gmail", shown(vm).title)
        assertNull(entryOf(repository).deletedAt)
        val notDeleted = events[2] as EntryEvent.NotSaved
        notDeleted.retry!!.invoke()
        settle()
        assertEquals(EntryUiState.Deleted("Gmail"), vm.state.value)

        // An undo that is not saved leaves the account deleted, and can be tried again.
        repository.failures += SaveProblem.FAILED
        vm.undoDelete()
        settle()
        assertEquals(EntryUiState.Deleted("Gmail"), vm.state.value)
        (events[3] as EntryEvent.NotSaved).retry!!.invoke()
        settle()
        assertEquals("Gmail", shown(vm).title)
        assertNull(entryOf(repository).deletedAt)
    }

    @Test
    fun theClockOfTheDatesIsTheWallClock() = EntryKit.run {
        val vm = screen(repository(vaultOf(gmail())))
        assertEquals(clock.wallMs(), vm.wallMs())
        pass(5_000)
        assertEquals(clock.wallMs(), vm.wallMs())
    }

    @Test
    fun aClearedScreenHoldsNothing() = EntryKit.run {
        val repository: FakeVaultRepository = repository(vaultOf(gmail()))
        val vm = screen(repository)
        vm.setShown(EntryValue.Password, true)
        clear()
        // The timer went with the screen: time passing changes nothing and throws nothing.
        clock.advance(EntryViewModel.REHIDE_AFTER_MS * 2)
        scope.testScheduler.advanceUntilIdle()
        assertFalse("nothing is copied from a screen that is gone", vm.copy(EntryValue.Password))
    }
}
