package io.github.dilshodbekmx.passwordvault.ui.security.change

import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.clipboard.SecretClipboard
import io.github.dilshodbekmx.passwordvault.core.generator.GeneratorOptions
import io.github.dilshodbekmx.passwordvault.core.model.EntryRules
import io.github.dilshodbekmx.passwordvault.core.model.FileRef
import io.github.dilshodbekmx.passwordvault.core.model.HistoryItem
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.core.model.VaultEdits
import io.github.dilshodbekmx.passwordvault.data.SaveProblem
import io.github.dilshodbekmx.passwordvault.i18n.UiText
import io.github.dilshodbekmx.passwordvault.ui.security.GuidedChangeEvent
import io.github.dilshodbekmx.passwordvault.ui.security.GuidedChangeUiState
import io.github.dilshodbekmx.passwordvault.ui.security.GuidedChangeViewModel
import io.github.dilshodbekmx.passwordvault.ui.security.change.ChangeKit.Companion.BANK
import io.github.dilshodbekmx.passwordvault.ui.security.change.ChangeKit.Companion.EARLIER
import io.github.dilshodbekmx.passwordvault.ui.security.change.ChangeKit.Companion.FACEBOOK
import io.github.dilshodbekmx.passwordvault.ui.security.change.ChangeKit.Companion.GMAIL
import io.github.dilshodbekmx.passwordvault.ui.security.change.ChangeKit.Companion.NOBODY
import io.github.dilshodbekmx.passwordvault.ui.security.change.ChangeKit.Companion.SHARED
import io.github.dilshodbekmx.passwordvault.ui.security.change.ChangeKit.Companion.account
import io.github.dilshodbekmx.passwordvault.ui.security.change.ChangeKit.Companion.done
import io.github.dilshodbekmx.passwordvault.ui.security.change.ChangeKit.Companion.facebook
import io.github.dilshodbekmx.passwordvault.ui.security.change.ChangeKit.Companion.gmail
import io.github.dilshodbekmx.passwordvault.ui.security.change.ChangeKit.Companion.sharing
import io.github.dilshodbekmx.passwordvault.ui.security.change.ChangeKit.Companion.steps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Build plan 7.2, [JVM]: the guided change's ViewModel against the fakes of `APP/fake/` (ux.md
 * §5.17, §12 check 5; architecture.md §5.4, §5.5). The change lives in the account, so each test
 * looks at what the vault holds as well as at what the screen shows; "a lock" and "a new process"
 * are made as the app makes them ([ChangeKit]).
 */
class GuidedChangeViewModelTest {

    /** The account's password, its kept password and its flag, as the vault holds them. */
    private fun ChangeKit.held(id: String = GMAIL) = entry(id).let { Triple(it.password, it.pendingOldPassword, it.changePending) }

    // ---- The steps before a change -------------------------------------------------------------

    @Test
    fun anAccountWithoutAChangeShowsTheStepsFromTheStart() = ChangeKit.run {
        open(sharing())
        val vm = screen()

        val shown = steps(vm)
        assertEquals("Gmail", shown.title)
        assertEquals("aziz.karimov@gmail.com", shown.username)
        assertEquals("the first web address; an app's address is never opened", "https://accounts.google.com/", shown.website)
        assertFalse("no new password yet", shown.started)
        assertTrue(shown.canCopyOld)
        assertFalse("nothing to copy as the new password yet", shown.canCopyNew)
        assertFalse("two other accounts still share the password", shown.lastOne)
        assertFalse(shown.busy)

        // Opening the screen saves nothing.
        assertEquals(0, repository.edits)
    }

    @Test
    fun copyOldPasswordIsTheWebsitesPasswordAndCopyNewWaitsForStep3() = ChangeKit.run {
        open(sharing())
        val vm = screen()

        assertFalse("no new password to copy before step 3", vm.copyNew())
        assertTrue(vm.copyOld())
        val old = clipboard.copies.single()
        assertEquals(SHARED, old.value)
        assertEquals(UiText.of(R.string.change_copied_old, "Gmail"), old.what)
        assertTrue("a password is copied with the sensitive flag (ux.md §6)", old.sensitive)
    }

    // ---- Step 3: the new password is saved at once -----------------------------------------------

    @Test
    fun createNewPasswordSavesItAtOnceAndKeepsTheWebsitesPassword() = ChangeKit.run {
        open(sharing())
        val vm = screen()
        val events = eventsOf(vm)

        vm.create()
        settle()

        val (password, kept, pending) = held()
        assertTrue("the change waits for its answer", pending)
        assertEquals("the website's password is kept", SHARED, kept)
        assertNotEquals(SHARED, password)
        assertEquals("the generator's default length (ux.md §5.15)", GeneratorOptions().length, password.length)
        assertEquals(listOf(GuidedChangeEvent.Created(again = false)), events)
        assertEquals("one save", 1, repository.saves)

        val shown = steps(vm)
        assertTrue("\"Create another password\" and \"Saved. …\"", shown.started)
        assertTrue(shown.canCopyNew)
        assertFalse(shown.busy)

        // Step 4: both passwords, each named for what it is.
        assertTrue(vm.copyOld())
        assertTrue(vm.copyNew())
        val (old, new) = clipboard.copies
        assertEquals(SHARED, old.value)
        assertEquals(UiText.of(R.string.change_copied_old, "Gmail"), old.what)
        assertEquals(password, new.value)
        assertEquals(UiText.of(R.string.change_copied_new, "Gmail"), new.what)
        assertTrue(old.sensitive && new.sensitive)
    }

    @Test
    fun sevenRetriesThenNoPutsBackTheOriginalPassword() = ChangeKit.run {
        // ux.md §12 check 5, through the screen: after 7 taps on "Create another password", "No, put
        // back the old password" restores the original password.
        open(sharing())
        val vm = screen()
        val events = eventsOf(vm)

        vm.create()
        settle()
        val tried = mutableListOf(entry().password)
        repeat(7) {
            later()
            vm.create()
            settle()
            val (password, kept, pending) = held()
            assertTrue(pending)
            assertEquals("the website's password stays kept at try ${it + 2}", SHARED, kept)
            assertTrue("a new password each time", password !in tried)
            tried += password
        }
        assertEquals(8, tried.size)
        assertEquals("one save per tap", 8, repository.saves)
        assertEquals(listOf(GuidedChangeEvent.Created(again = false)) + List(7) { GuidedChangeEvent.Created(again = true) }, events)
        // The tried passwords went to the previous passwords, newest first, at most five.
        assertEquals(tried.dropLast(1).reversed().take(EntryRules.HISTORY_MAX), entry().passwordHistory.map { it.password })

        later()
        vm.revert()
        settle()
        val (password, kept, pending) = held()
        assertEquals("the original password is back", SHARED, password)
        assertNull(kept)
        assertFalse(pending)
        assertEquals("the rejected new one is the newest previous password", tried.last(), entry().passwordHistory.first().password)
        assertEquals(GuidedChangeEvent.Reverted, events.last())

        // The steps start again from the top.
        val shown = steps(vm)
        assertFalse(shown.started)
        assertTrue(vm.copyOld())
        assertEquals(SHARED, clipboard.copies.last().value)
    }

    @Test
    fun oneTapIsOneChange() = ChangeKit.run {
        open(sharing())
        val vm = screen()

        vm.create()
        assertTrue("the buttons wait while the save runs", steps(vm).busy)
        vm.create()
        vm.create()
        settle()
        assertEquals(1, repository.saves)
        assertFalse(steps(vm).busy)

        vm.confirm()
        vm.confirm()
        vm.revert()
        settle()
        assertEquals("one answer saved", 2, repository.saves)
        assertTrue(vm.state.value is GuidedChangeUiState.Done)
    }

    // ---- "Password rules" ---------------------------------------------------------------------------

    @Test
    fun passwordRulesSaveTheSheetsPasswordAndTheirOptionsHoldForTheNextTry() = ChangeKit.run {
        open(sharing())
        val vm = screen()

        vm.openRules()
        settle()
        assertTrue(vm.generator.state.value.open)
        vm.generator.setSymbols(false)
        vm.generator.setLength(20)
        settle()
        val made = vm.generator.password()!!
        assertEquals(20, made.length)

        vm.useRules()
        settle()
        assertFalse("the sheet closes", vm.generator.state.value.open)
        val (password, kept, pending) = held()
        assertEquals("the sheet's password is saved as the new one", made, password)
        assertEquals(SHARED, kept)
        assertTrue(pending)
        assertEquals("the rules are remembered (ux.md §5.15)", 20, settings.generator.value.length)
        assertFalse(settings.generator.value.symbols)

        // "Create another password" keeps to them: a website that refused symbols gets none again.
        later()
        vm.create()
        settle()
        val again = entry().password
        assertNotEquals(made, again)
        assertEquals(20, again.length)
        assertTrue("no symbols", again.all { it.isLetterOrDigit() })
        assertEquals(SHARED, entry().pendingOldPassword)
    }

    @Test
    fun closingTheRulesSavesNothing() = ChangeKit.run {
        open(sharing())
        val vm = screen()
        vm.openRules()
        settle()
        vm.closeRules()
        settle()
        assertEquals(0, repository.saves)
        assertFalse(held().third)
    }

    // ---- Step 5: the answer --------------------------------------------------------------------------

    @Test
    fun yesEndsTheChangeAndOffersTheNextAccountOfTheGroup() = ChangeKit.run {
        open(sharing())
        val vm = screen()
        vm.create()
        settle()
        val new = entry().password

        later()
        vm.confirm()
        settle()
        val (password, kept, pending) = held()
        assertEquals(new, password)
        assertNull(kept)
        assertFalse(pending)
        assertEquals("the website's old password is the newest previous one", listOf(SHARED, EARLIER), entry().passwordHistory.map { it.password })

        // "Gmail now has its own password." with "Next: Facebook": Facebook and Kapitalbank still share
        // the old password; neither looks like email or is a favourite, so A to Z decides.
        val end = done(vm)
        assertEquals("Gmail", end.title)
        assertEquals(FACEBOOK, end.nextId)
        assertEquals("Facebook", end.nextTitle)

        assertEquals("\"Next\" opens Facebook's change", FACEBOOK, vm.next())
    }

    @Test
    fun theLastAccountOfAGroupSaysSoAndEndsWithDoneForNow() = ChangeKit.run {
        // Facebook changed its password already; Gmail is the last one with the old password.
        open(Vault(entries = listOf(gmail(), facebook(password = "Fb-own-9!", history = listOf(HistoryItem(SHARED, ChangeKit.ADDED_MS))))))
        val vm = screen()
        assertTrue("Last one! After this, your old password is not used anywhere.", steps(vm).lastOne)

        vm.create()
        settle()
        assertTrue("still the last one while the website may have the old password", steps(vm).lastOne)

        vm.confirm()
        settle()
        val end = done(vm)
        assertNull("nobody has the old password any more: \"Done for now\"", end.nextId)
        assertNull(end.nextTitle)
    }

    @Test
    fun noAndYesNeedAChangeThatWaits() = ChangeKit.run {
        open(sharing())
        val vm = screen()
        vm.confirm()
        vm.revert()
        settle()
        assertEquals("nothing to answer before step 3", 0, repository.edits)
        assertFalse(steps(vm).started)
    }

    @Test
    fun illFinishLaterKeepsTheChange() = ChangeKit.run {
        open(sharing())
        val vm = screen()
        vm.create()
        settle()
        val new = entry().password

        // "I'll finish later" only goes back: the screen is left with nothing saved.
        leave()
        assertEquals(1, repository.saves)
        assertEquals(Triple(new, SHARED, true), held())

        val again = screen()
        assertTrue(steps(again).started)
    }

    // ---- The change survives a lock and the end of the process at every step ------------------------

    @Test
    fun theChangeSurvivesALockAtEveryStep() = ChangeKit.run {
        open(sharing())

        // Step 1, before anything is saved.
        var vm = screen()
        lock()
        assertEquals("a lock leaves nothing of the account on the screen", GuidedChangeUiState.Loading, vm.state.value)
        assertFalse(vm.copyOld())
        unlock()
        vm = screen()
        assertFalse(steps(vm).started)

        // Step 3: a new password.
        vm.create()
        settle()
        val first = entry().password
        lock()
        unlock()
        vm = screen()
        assertTrue(steps(vm).started)
        assertEquals(Triple(first, SHARED, true), held())
        vm.copyOld()
        vm.copyNew()
        assertEquals(listOf(SHARED, first), clipboard.copies.takeLast(2).map { it.value })

        // Another try, then a lock.
        later()
        vm.create()
        settle()
        val second = entry().password
        lock()
        unlock()
        vm = screen()
        assertTrue(steps(vm).started)
        assertEquals(Triple(second, SHARED, true), held())

        // Step 5: "Yes, it's changed", then a lock: the end shows again.
        later()
        vm.confirm()
        settle()
        assertTrue(vm.state.value is GuidedChangeUiState.Done)
        lock()
        unlock()
        vm = screen()
        assertEquals("Gmail now has its own password, after the lock too", FACEBOOK, done(vm).nextId)
        assertEquals(Triple(second, null, false), held())
    }

    @Test
    fun theChangeSurvivesTheEndOfTheProcessAtEveryStep() = ChangeKit.run {
        open(sharing())
        var vm = screen()

        newProcess()
        vm = screen()
        assertFalse(steps(vm).started)

        vm.create()
        settle()
        val first = entry().password
        newProcess()
        vm = screen()
        assertTrue("the change waits for its answer in a new process", steps(vm).started)
        assertEquals(Triple(first, SHARED, true), held())
        assertTrue(vm.copyOld())
        assertTrue(vm.copyNew())
        assertEquals(listOf(SHARED, first), clipboard.copies.takeLast(2).map { it.value })

        repeat(3) {
            later()
            vm.create()
            settle()
            newProcess()
            vm = screen()
            assertEquals("the website's password survives every try", SHARED, entry().pendingOldPassword)
        }

        later()
        vm.revert()
        settle()
        newProcess()
        vm = screen()
        assertEquals(Triple(SHARED, null, false), held())
        assertFalse(steps(vm).started)

        vm.create()
        settle()
        later()
        vm.confirm()
        settle()
        val confirmed = entry().password
        newProcess()
        vm = screen()
        // A new process has no memory of the end: the account simply has its own password now.
        assertEquals(Triple(confirmed, null, false), held())
        assertFalse(steps(vm).started)
        assertEquals(SHARED, entry().passwordHistory.first().password)
    }

    @Test
    fun theEndShowsAgainAfterALockButNotAfterTheScreenWasLeft() = ChangeKit.run {
        open(sharing())
        var vm = screen()
        vm.create()
        settle()
        vm.confirm()
        settle()
        assertTrue(vm.state.value is GuidedChangeUiState.Done)

        lock()
        unlock()
        vm = screen()
        assertTrue("after a lock: the end again", vm.state.value is GuidedChangeUiState.Done)

        leave()
        vm = screen()
        assertTrue("left with Back: the steps of a new change", vm.state.value is GuidedChangeUiState.Steps)
        assertFalse(steps(vm).started)
    }

    @Test
    fun theEndGoesWhenTheAccountChangesAfterIt() = ChangeKit.run {
        open(sharing())
        val vm = screen()
        vm.create()
        settle()
        vm.confirm()
        settle()
        assertTrue(vm.state.value is GuidedChangeUiState.Done)

        // The account is changed elsewhere (the star, here) after the end.
        later()
        repository.edit(VaultEdits.setFavourite(GMAIL, true))
        settle()
        assertTrue(vm.state.value is GuidedChangeUiState.Steps)
    }

    @Test
    fun nextAndDoneForNowForgetTheEnd() = ChangeKit.run {
        open(sharing())
        var vm = screen()
        vm.create()
        settle()
        vm.confirm()
        settle()
        assertEquals(FACEBOOK, vm.next())
        assertNull("\"Next\" works once", vm.next())
        lock()
        unlock()
        vm = screen()
        assertTrue(vm.state.value is GuidedChangeUiState.Steps)

        // Facebook's change, to its end, then "Done for now".
        vm = screen(FACEBOOK)
        vm.create()
        settle()
        vm.confirm()
        settle()
        assertEquals("Kapitalbank is the last one with the old password", BANK, done(vm).nextId)
        vm.finish()
        settle()
        lock()
        unlock()
        vm = screen(FACEBOOK)
        assertTrue(vm.state.value is GuidedChangeUiState.Steps)
    }

    // ---- The backup line, once per session ------------------------------------------------------------

    @Test
    fun theBackupLineShowsOncePerSessionUntilABackupHoldsTheChange() = ChangeKit.run {
        open(sharing())
        var vm = screen()
        vm.create()
        settle()
        vm.confirm()
        settle()
        assertTrue("no backup holds the change: the line", done(vm).backupLine)

        vm = screen(FACEBOOK)
        vm.create()
        settle()
        vm.confirm()
        settle()
        assertFalse("once per session", done(vm).backupLine)

        // A lock ends the session.
        lock()
        unlock()
        vm = screen(BANK)
        vm.create()
        settle()
        later()
        vm.confirm()
        settle()
        assertTrue("a new session: the line again", done(vm).backupLine)

        // "Back up now", and the backup is made: the line goes, the end stays.
        later()
        repository.edit(VaultEdits.noteBackup("content://backups/1", "PasswordVault-backup.pvault"))
        settle()
        assertFalse(done(vm).backupLine)
    }

    @Test
    fun aChangeThatABackupAlreadyHoldsHasNoBackupLine() = ChangeKit.run {
        // The backup's time comes after the change's (a clock set back since): nothing is only here.
        open(sharing().copy(backups = listOf(FileRef("content://b", "b.pvault", Long.MAX_VALUE))))
        val vm = screen()
        vm.create()
        settle()
        vm.confirm()
        settle()
        assertFalse(done(vm).backupLine)
    }

    // ---- Saves that fail -----------------------------------------------------------------------------

    @Test
    fun aSaveThatFailsSaysSoAndTriesAgain() = ChangeKit.run {
        open(sharing())
        val vm = screen()
        val events = eventsOf(vm)

        repository.failures.add(SaveProblem.NOT_DURABLE)
        vm.create()
        settle()
        val failed = events.single() as GuidedChangeEvent.NotSaved
        assertEquals(SaveProblem.NOT_DURABLE, failed.problem)
        assertFalse("nothing saved", held().third)
        assertFalse(steps(vm).busy)

        failed.retry!!.invoke()
        settle()
        assertTrue("\"Try again\" made and saved a password", held().third)
        assertEquals(GuidedChangeEvent.Created(again = false), events.last())

        repository.failures.add(SaveProblem.TOO_LARGE)
        vm.confirm()
        settle()
        val large = events.last() as GuidedChangeEvent.NotSaved
        assertEquals(SaveProblem.TOO_LARGE, large.problem)
        assertNull("trying again cannot make the vault fit", large.retry)
        assertTrue("the change still waits", held().third)

        repository.failures.add(SaveProblem.FAILED)
        vm.revert()
        settle()
        val revertFailed = events.last() as GuidedChangeEvent.NotSaved
        revertFailed.retry!!.invoke()
        settle()
        assertEquals(Triple(SHARED, null, false), held())
    }

    @Test
    fun theSheetsPasswordIsSavedAgainByTryAgain() = ChangeKit.run {
        open(sharing())
        val vm = screen()
        val events = eventsOf(vm)
        vm.openRules()
        settle()
        val made = vm.generator.password()!!
        repository.failures.add(SaveProblem.NOT_DURABLE)
        vm.useRules()
        settle()
        (events.single() as GuidedChangeEvent.NotSaved).retry!!.invoke()
        settle()
        assertEquals("the same password, not another one", made, entry().password)
    }

    @Test
    fun aCopyThePhoneRefusesSaysSo() = ChangeKit.run {
        open(sharing())
        val refusing = object : SecretClipboard by clipboard {
            override fun copy(value: CharSequence, what: UiText, sensitive: Boolean) = throw SecurityException("blocked")
        }
        val vm = screen(clipboard = refusing)
        val events = eventsOf(vm)
        assertFalse(vm.copyOld())
        settle()
        assertEquals(listOf(GuidedChangeEvent.CopyBlocked), events)
    }

    // ---- Where the account is not ----------------------------------------------------------------------

    @Test
    fun anAccountThatIsNotThereIsGone() = ChangeKit.run {
        open(Vault(entries = listOf(gmail(), account(FACEBOOK, "Facebook", deletedAt = ChangeKit.ADDED_MS + 2 * ChangeKit.DAY_MS))))
        assertEquals(GuidedChangeUiState.Gone(inRecentlyDeleted = false), screen(NOBODY).state.value)
        assertEquals(GuidedChangeUiState.Gone(inRecentlyDeleted = true), screen(FACEBOOK).state.value)

        // Deleted while its change is shown: nothing can be saved or copied any more.
        val vm = screen()
        vm.create()
        settle()
        later()
        repository.edit(VaultEdits.delete(GMAIL))
        settle()
        assertEquals(GuidedChangeUiState.Gone(inRecentlyDeleted = true), vm.state.value)
        val saves = repository.saves
        vm.create()
        vm.confirm()
        settle()
        assertEquals(saves, repository.saves)
        assertFalse(vm.copyOld())
        assertFalse(vm.copyNew())
    }

    @Test
    fun aSaveThatEndsAfterALockShowsNothing() = ChangeKit.run {
        open(sharing())
        val vm = screen()
        val events = eventsOf(vm)
        vm.create()
        // Locked before the password was made and saved.
        repository.close()
        settle()
        assertEquals(GuidedChangeUiState.Loading, vm.state.value)
        assertTrue(events.isEmpty())
        assertFalse("nothing was saved while locked", repository.stored.entries.first { it.id == GMAIL }.changePending)
    }

    // ---- Nothing secret in the state ------------------------------------------------------------------

    @Test
    fun theStateNeverHoldsAPassword() = ChangeKit.run {
        open(sharing())
        val vm = screen()
        val seen = mutableListOf<Any>()
        fun look() {
            seen += vm.state.value
        }
        look()
        vm.create()
        settle()
        look()
        repeat(2) {
            vm.create()
            settle()
            look()
        }
        vm.confirm()
        settle()
        look()

        val passwords = (repository.stored.entries.flatMap { e -> listOf(e.password) + e.passwordHistory.map { it.password } } + SHARED)
            .filter { it.isNotEmpty() }.toSet()
        for (state in seen) {
            for (value in fieldValues(state)) {
                assertFalse("$state holds a password", value is String && value in passwords)
            }
            for (password in passwords) assertFalse("toString shows a password", state.toString().contains(password))
            assertFalse("toString shows the account's name", state.toString().contains("Gmail"))
        }
    }

    /** The values of every field of [state] (its data class properties). */
    private fun fieldValues(state: Any): List<Any?> = state.javaClass.declaredFields
        .filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) }
        .map { field ->
            field.isAccessible = true
            field.get(state)
        }
}
