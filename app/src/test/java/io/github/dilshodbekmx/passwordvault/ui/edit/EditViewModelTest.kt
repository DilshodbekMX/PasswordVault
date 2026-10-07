package io.github.dilshodbekmx.passwordvault.ui.edit

import androidx.compose.foundation.ExperimentalFoundationApi
import io.github.dilshodbekmx.passwordvault.core.model.CustomField
import io.github.dilshodbekmx.passwordvault.core.model.Draft
import io.github.dilshodbekmx.passwordvault.core.model.DraftEntry
import io.github.dilshodbekmx.passwordvault.core.model.EntryField
import io.github.dilshodbekmx.passwordvault.core.model.EntryProblem
import io.github.dilshodbekmx.passwordvault.core.model.EntryRules
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.data.SaveProblem
import io.github.dilshodbekmx.passwordvault.ui.edit.EditKit.Companion.BANK
import io.github.dilshodbekmx.passwordvault.ui.edit.EditKit.Companion.GMAIL
import io.github.dilshodbekmx.passwordvault.ui.edit.EditKit.Companion.OLD
import io.github.dilshodbekmx.passwordvault.ui.edit.EditKit.Companion.entry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Build plan 3.6, [JVM]: the ViewModel of add and edit (ux.md §5.14) against `FakeVaultRepository`,
 * `FakeAppSettings`, `FakeClock` and `FakeRandom`. The tests named `acceptance…` are the lines of the
 * build plan: U §12 check 4, Discard, the duplicate question, the limits with their counters, and the
 * name filled from the host or with `EditTexts.untitled`. A lock is done as `AutoLock` does it
 * (architecture.md §5.5): `flushDraft`, then the vault is dropped (`FakeVaultRepository.close`, which
 * publishes `VaultState.Locked` as `VaultPort.lock()` does).
 */
class EditViewModelTest {

    // ---- U §12 check 4: the draft ------------------------------------------------------------

    @Test
    fun acceptanceCheck4_aGeneratedPasswordIsHeldWithHoldDraftAndSurvivesALockAsADraft() = EditKit.run {
        val repository = repository(Vault(entries = listOf(entry(GMAIL, "Gmail", "aziz@gmail.com", "old-secret"))))
        val form = form(repository)
        assertEquals(Phase.FORM, form.ui.value.phase)
        assertNull("an untouched form holds no draft", repository.heldDraft)

        // "Create a strong password", then "Use this password".
        form.openGenerator()
        settle()
        val generated = form.generator.password()
        assertNotNull("the sheet made a password", generated)
        form.useGenerated()
        settle()
        assertEquals(generated, form.form.password.text.toString())
        assertFalse("the sheet closed", form.generator.state.value.open)
        assertTrue(form.ui.value.dirty)
        val held = repository.heldDraft
        assertNotNull("a generated password counts as a change: it is held", held)
        assertNull("a new account's draft has no entry id", held!!.entryId)
        assertEquals(generated, held.entry.password)
        assertEquals(clock.wallMs(), held.savedAt)

        // The user leaves for the browser; the app locks (AutoLock: the draft first, then the lock).
        repository.flushDraft()
        repository.close()
        settle()
        assertEquals("the draft is in the vault", generated, repository.stored.draft?.entry?.password)
        assertEquals("no entry was saved", 1, repository.stored.entries.size)
        assertEquals("nothing of the form is kept after the lock", "", form.form.password.text.toString())
        assertEquals(Phase.LOADING, form.ui.value.phase)

        // The unlock: the same form (its ViewModel kept, the activity stopped) reopens with the password…
        repository.open()
        settle()
        assertEquals(generated, form.form.password.text.toString())
        assertEquals(EditMessage.Kept, form.ui.value.message)
        assertTrue(form.ui.value.dirty)

        // …and so does a form made after the unlock (the usual case: the ViewModel was cleared with the screen).
        val again = form(repository)
        assertEquals(generated, again.form.password.text.toString())
        assertEquals(EditMessage.Kept, again.ui.value.message)
        assertEquals("no entry until Save", 1, repository.stored.entries.size)

        // Save: the entry exists with the password, and the draft is gone in the same save.
        again.save(texts)
        settle()
        val done = again.ui.value.done
        assertTrue(done is EditDone.Added)
        val added = repository.stored.entries.single { it.id == (done as EditDone.Added).entryId }
        assertEquals(generated, added.password)
        assertNull("Save deletes the draft", repository.stored.draft)
        assertTrue("the detail screen is told to say \"Saved\"", SavedNotice.take(added.id))
    }

    @Test
    fun acceptanceDiscard_leavesNeitherEntryNorDraft() = EditKit.run {
        val repository = repository(Vault(entries = listOf(entry(GMAIL, "Gmail"))))
        val form = form(repository)
        set(form.form.title, "Click")
        form.openGenerator()
        settle()
        form.useGenerated()
        settle()
        // Locked and unlocked: the draft is in the vault and the form shows it.
        repository.flushDraft()
        repository.close()
        settle()
        repository.open()
        settle()
        assertNotNull(repository.stored.draft)
        assertEquals("Click", form.form.title.text.toString())

        // Close: "Save changes?" — Discard.
        form.close()
        assertEquals(EditDialog.SaveChanges, form.ui.value.dialog)
        form.discard()
        settle()
        assertEquals(EditDone.Closed, form.ui.value.done)
        assertNull("Discard deletes the draft", repository.stored.draft)
        assertNull("nothing is held", repository.heldDraft)
        assertEquals("no entry was added", listOf(GMAIL), repository.stored.entries.map { it.id })
        // Nothing comes back: a later lock writes nothing.
        repository.flushDraft()
        assertNull(repository.stored.draft)
    }

    @Test
    fun discardBeforeAnyLockHoldsNothingAndWritesNothing() = EditKit.run {
        val repository = repository()
        val form = form(repository)
        set(form.form.title, "Click")
        assertNotNull(repository.heldDraft)
        form.close()
        form.discard()
        settle()
        assertNull(repository.heldDraft)
        assertNull(repository.stored.draft)
        assertTrue(repository.stored.entries.isEmpty())
        assertEquals("nothing was written", 0, repository.saves)
    }

    @Test
    fun keepEditingClosesTheQuestionAndKeepsTheForm() = EditKit.run {
        val repository = repository()
        val form = form(repository)
        set(form.form.title, "Click")
        form.close()
        assertEquals(EditDialog.SaveChanges, form.ui.value.dialog)
        form.dismissDialog()
        assertNull(form.ui.value.dialog)
        assertNull(form.ui.value.done)
        assertEquals("Click", form.form.title.text.toString())
        assertTrue(form.ui.value.backHandled)
    }

    @Test
    fun anUnchangedFormClosesAtOnceAndBackIsNotItsOwn() = EditKit.run {
        val repository = repository(Vault(entries = listOf(entry(GMAIL, "Gmail", "aziz"))))
        val form = form(repository, GMAIL)
        assertFalse(form.ui.value.dirty)
        assertFalse("the predictive back is not stopped for nothing", form.ui.value.backHandled)
        form.close()
        settle()
        assertNull(form.ui.value.dialog)
        assertEquals(EditDone.Closed, form.ui.value.done)
    }

    @Test
    fun typingAndUndoingIsNoChange() = EditKit.run {
        val repository = repository(Vault(entries = listOf(entry(GMAIL, "Gmail", "aziz"))))
        val form = form(repository, GMAIL)
        set(form.form.title, "Gmail 2")
        assertTrue(form.ui.value.dirty)
        assertEquals("Gmail 2", repository.heldDraft?.entry?.title)
        assertEquals(GMAIL, repository.heldDraft?.entryId)
        set(form.form.title, "Gmail")
        assertFalse(form.ui.value.dirty)
        assertNull("a form back as it was holds nothing", repository.heldDraft)
    }

    @Test
    fun aLockEmptiesEveryFieldAndItsUndoHistoryAndHoldsNothingAfterIt() = EditKit.run {
        val repository = repository()
        val form = form(repository)
        set(form.form.title, "Click")
        set(form.form.username, "aziz")
        set(form.form.password, "secret-1")
        set(form.form.notes, "note")
        set(form.form.websites.first().state, "click.uz")
        form.openAddField()
        form.chooseLabel(FieldLabel.PIN)
        set(form.addField.value, "1234")
        form.confirmAddField("PIN code")
        settle()
        form.openAddField()
        set(form.addField.value, "half-typed")
        val states = form.form.states() + form.addField.value
        repository.close()
        settle()
        for (state in states) {
            assertEquals("", state.text.toString())
            @OptIn(ExperimentalFoundationApi::class)
            assertFalse("undo cannot bring the text back [V-C11]", state.undoState.canUndo)
        }
        assertTrue(form.form.fields.isEmpty())
        assertNull("the dialog is closed", form.ui.value.dialog)
        // Typing into a field still on screen after the lock holds nothing.
        set(form.form.title, "after the lock")
        assertNull(repository.heldDraft)
    }

    @Test
    fun theDraftOfAnotherFormIsNotOpenedAndTheNewestFormWins() = EditKit.run {
        val other = Draft(GMAIL, clock.wallMs(), DraftEntry(title = "Gmail changed"))
        val repository = repository(Vault(entries = listOf(entry(GMAIL, "Gmail")), draft = other))
        val form = form(repository)
        assertEquals("", form.form.title.text.toString())
        assertNull(form.ui.value.message)
        set(form.form.title, "Click")
        repository.flushDraft()
        assertEquals("the newest form's draft replaced the other", Draft(null, clock.wallMs(), DraftEntry(title = "Click")).entry, repository.stored.draft?.entry)
        assertNull(repository.stored.draft?.entryId)
    }

    @Test
    fun theEditFormOpensItsOwnDraftWithTheMessage() = EditKit.run {
        val draft = Draft(GMAIL, clock.wallMs() - 1000L, DraftEntry(title = "Gmail", username = "aziz", password = "new-one"))
        val repository = repository(Vault(entries = listOf(entry(GMAIL, "Gmail", "aziz", "old-one")), draft = draft))
        val form = form(repository, GMAIL)
        assertEquals("new-one", form.form.password.text.toString())
        assertEquals(EditMessage.Kept, form.ui.value.message)
        assertTrue(form.ui.value.dirty)
        form.messageShown(EditMessage.Kept)
        assertNull(form.ui.value.message)
    }

    @Test
    fun aDraftThirtyDaysOldIsOfferedOnceAndOpenDropsItFromTheVault() = EditKit.run {
        val savedAt = clock.wallMs() - EditViewModel.OLD_DRAFT_MS
        val repository = repository(Vault(draft = Draft(null, savedAt, DraftEntry(title = "Click", password = "pw"))))
        val form = form(repository)
        val dialog = form.ui.value.dialog
        assertTrue(dialog is EditDialog.OldDraft)
        assertEquals("Click", (dialog as EditDialog.OldDraft).title)
        assertEquals(savedAt, dialog.savedAt)
        assertEquals("the form waits for the answer", "", form.form.title.text.toString())
        assertNull(form.ui.value.message)

        form.openOldDraft()
        settle()
        assertNull(form.ui.value.dialog)
        assertEquals("Click", form.form.title.text.toString())
        assertNull("the old draft left the vault", repository.stored.draft)
        assertEquals("the form's changes are held again, with today's time", clock.wallMs(), repository.heldDraft?.savedAt)
        repository.flushDraft()
        assertEquals(clock.wallMs(), repository.stored.draft?.savedAt)
    }

    @Test
    fun aDraftThirtyDaysOldThatIsDiscardedLeavesTheVault() = EditKit.run {
        val savedAt = clock.wallMs() - EditViewModel.OLD_DRAFT_MS - 1L
        val repository = repository(Vault(entries = listOf(entry(GMAIL, "Gmail")), draft = Draft(GMAIL, savedAt, DraftEntry(title = ""))))
        val form = form(repository, GMAIL)
        val dialog = form.ui.value.dialog as EditDialog.OldDraft
        assertEquals("a draft without a name is offered under the account's name", "Gmail", dialog.title)
        form.discardOldDraft()
        settle()
        assertNull(repository.stored.draft)
        assertEquals("Gmail", form.form.title.text.toString())
        assertFalse(form.ui.value.dirty)
    }

    @Test
    fun aDraftJustUnderThirtyDaysOpensAtOnce() = EditKit.run {
        val savedAt = clock.wallMs() - EditViewModel.OLD_DRAFT_MS + 1L
        val repository = repository(Vault(draft = Draft(null, savedAt, DraftEntry(title = "Click"))))
        val form = form(repository)
        assertNull(form.ui.value.dialog)
        assertEquals("Click", form.form.title.text.toString())
        assertEquals(EditMessage.Kept, form.ui.value.message)
    }

    @Test
    fun closingAFormThatIsBackAsItWasDeletesItsStoredDraft() = EditKit.run {
        val draft = Draft(GMAIL, clock.wallMs(), DraftEntry(title = "Gmail 2"))
        val repository = repository(Vault(entries = listOf(entry(GMAIL, "Gmail")), draft = draft))
        val form = form(repository, GMAIL)
        set(form.form.title, "Gmail")
        assertFalse(form.ui.value.dirty)
        assertTrue("Back still belongs to the form: its stored draft must go", form.ui.value.backHandled)
        form.close()
        settle()
        assertNull(form.ui.value.dialog)
        assertNull(repository.stored.draft)
        assertEquals(EditDone.Closed, form.ui.value.done)
    }

    // ---- The duplicate question ----------------------------------------------------------------

    @Test
    fun acceptanceDuplicate_theSameNameAndLoginAsks() = EditKit.run {
        val repository = repository(Vault(entries = listOf(entry(GMAIL, "Gmail", "aziz@gmail.com"))))
        val form = form(repository)
        set(form.form.title, " gmail ")
        set(form.form.username, "AZIZ@gmail.com")
        set(form.form.password, "another")
        form.save(texts)
        settle()
        val dialog = form.ui.value.dialog
        assertTrue(dialog is EditDialog.SameLogin)
        assertEquals("the question names the account the user has", "Gmail", (dialog as EditDialog.SameLogin).title)
        assertEquals("nothing saved yet", 1, repository.stored.entries.size)
        assertFalse(form.ui.value.saving)

        // Cancel: nothing saved, the form stays.
        form.dismissDialog()
        settle()
        assertEquals(1, repository.stored.entries.size)
        assertNull(form.ui.value.done)

        // Save again asks again; "Save anyway" saves.
        form.save(texts)
        settle()
        assertTrue(form.ui.value.dialog is EditDialog.SameLogin)
        form.saveAnyway(texts)
        settle()
        assertEquals(2, repository.stored.entries.size)
        assertTrue(form.ui.value.done is EditDone.Added)
    }

    @Test
    fun theSameLoginIsNotAskedForTheEntryItselfNorForADeletedOneNorForAnotherLogin() = EditKit.run {
        val repository = repository(
            Vault(
                entries = listOf(
                    entry(GMAIL, "Gmail", "aziz@gmail.com"),
                    entry(OLD, "Click", "aziz", deletedAt = 5L),
                ),
            ),
        )
        // The entry itself.
        val edit = form(repository, GMAIL)
        set(edit.form.notes, "work mail")
        edit.save(texts)
        settle()
        assertNull(edit.ui.value.dialog)
        assertTrue(edit.ui.value.done is EditDone.Updated)
        // An account in Recently deleted.
        val click = form(repository)
        set(click.form.title, "Click")
        set(click.form.username, "aziz")
        click.save(texts)
        settle()
        assertNull(click.ui.value.dialog)
        assertTrue(click.ui.value.done is EditDone.Added)
        // The same name with another login.
        val other = form(repository)
        set(other.form.title, "Gmail")
        set(other.form.username, "dilshod@gmail.com")
        other.save(texts)
        settle()
        assertNull(other.ui.value.dialog)
        assertTrue(other.ui.value.done is EditDone.Added)
    }

    @Test
    fun theSameLoginIsCheckedAgainstTheNameFilledFromTheHost() = EditKit.run {
        val repository = repository(Vault(entries = listOf(entry(GMAIL, "mail.google.com", "aziz"))))
        val form = form(repository)
        set(form.form.username, "aziz")
        set(form.form.websites.first().state, "https://mail.google.com/mail/u/0")
        form.save(texts)
        settle()
        assertTrue(form.ui.value.dialog is EditDialog.SameLogin)
    }

    // ---- Limits and counters ---------------------------------------------------------------

    @Test
    fun acceptanceLimits_typingStopsAtTheLimitInCodePointsWithACounter() = EditKit.run {
        val repository = repository()
        val form = form(repository)
        val notes = form.form.notes
        val input = form.inputOf(FieldKey.Notes)

        assertNull("no counter for an empty field", form.counter(FieldKey.Notes))
        type(notes, input, "a".repeat(8_999))
        assertNull("no counter below nine tenths", form.counter(FieldKey.Notes))
        type(notes, input, "a")
        assertEquals(Counter(9_000, EntryRules.NOTES_MAX), form.counter(FieldKey.Notes))
        // A paste that would go past the limit is cut at the limit.
        type(notes, input, "b".repeat(1_500))
        assertEquals(EntryRules.NOTES_MAX, notes.text.length)
        assertEquals("10,000 / 10,000", Counter(10_000, 10_000), form.counter(FieldKey.Notes))
        assertTrue(form.counter(FieldKey.Notes)!!.full)
        // Typing at the limit adds nothing.
        type(notes, input, "c")
        assertEquals(EntryRules.NOTES_MAX, notes.text.length)
        assertFalse(notes.text.contains('c'))

        // The limit is in code points: 199 letters and an emoji (two UTF-16 units) fill the name.
        val title = form.form.title
        type(title, form.inputOf(FieldKey.Title), "x".repeat(199) + "😀")
        assertEquals(201, title.text.length)
        assertEquals(Counter(200, EntryRules.TITLE_MAX), form.counter(FieldKey.Title))
        type(title, form.inputOf(FieldKey.Title), "y")
        assertFalse("nothing past the limit", title.text.endsWith("y"))
        // An emoji that does not fit is not cut in half.
        set(title, "x".repeat(199))
        type(title, form.inputOf(FieldKey.Title), "😀😀")
        assertEquals("x".repeat(199) + "😀", title.text.toString())

        // A form at every limit saves.
        set(form.form.username, "u".repeat(EntryRules.USERNAME_MAX))
        set(form.form.password, "p".repeat(EntryRules.PASSWORD_MAX))
        form.save(texts)
        settle()
        assertTrue(form.ui.value.done is EditDone.Added)
        val saved = repository.stored.entries.single()
        assertEquals(EntryRules.NOTES_MAX, saved.notes.length)
        assertEquals(EntryRules.PASSWORD_MAX, saved.password.length)
    }

    @Test
    fun textOverTheLimitFromElsewhereIsNamedUnderItsFieldAndGetsTheFocus() = EditKit.run {
        val repository = repository()
        val form = form(repository)
        set(form.form.title, "Click")
        // Not typed (no input rules), as from an imported entry or a draft.
        set(form.form.password, "p".repeat(EntryRules.PASSWORD_MAX + 1))
        form.save(texts)
        settle()
        val problems = form.problemsOf(form.ui.value.problems, FieldKey.Password)
        assertEquals(listOf(EntryProblem(EntryField.PASSWORD, EntryProblem.Kind.TOO_LONG, -1, EntryRules.PASSWORD_MAX)), problems)
        assertEquals("a failed save moves the focus to the first invalid field", FieldKey.Password, form.ui.value.focusRequest?.key)
        assertTrue("nothing saved", repository.stored.entries.isEmpty())
        assertNull(form.ui.value.done)
        assertEquals("the form keeps holding its draft", "Click", repository.heldDraft?.entry?.title)
        // The counter says how far over it is.
        assertEquals(Counter(EntryRules.PASSWORD_MAX + 1, EntryRules.PASSWORD_MAX), form.counter(FieldKey.Password))
        // A field already over its limit may shrink but not grow.
        type(form.form.password, form.inputOf(FieldKey.Password), "q")
        assertEquals(EntryRules.PASSWORD_MAX + 1, form.form.password.text.length)
    }

    @Test
    fun controlCharactersAreRefusedOnInputButLineBreaksStayInNotes() = EditKit.run {
        val form = form(repository())
        type(form.form.title, form.inputOf(FieldKey.Title), "Cli\tck\n")
        assertEquals("Click", form.form.title.text.toString())
        type(form.form.password, form.inputOf(FieldKey.Password), "pass\u0000word\r\n")
        assertEquals("password", form.form.password.text.toString())
        type(form.form.notes, form.inputOf(FieldKey.Notes), "line 1\r\nline 2\rline 3\u0007")
        assertEquals("line 1\nline 2\nline 3", form.form.notes.text.toString())
    }

    @Test
    fun aControlCharacterAlreadyInAStoredPasswordIsKeptAndNamedAtSave() = EditKit.run {
        val repository = repository(Vault(entries = listOf(entry(GMAIL, "Gmail", "aziz", "pass\tword"))))
        val form = form(repository, GMAIL)
        type(form.form.notes, form.inputOf(FieldKey.Notes), "note")
        type(form.form.password, form.inputOf(FieldKey.Password), "!")
        assertEquals("the stored text is not changed behind the user's back", "pass\tword!", form.form.password.text.toString())
        form.save(texts)
        settle()
        val problems = form.problemsOf(form.ui.value.problems, FieldKey.Password)
        assertEquals(EntryProblem.Kind.CONTROL_CHARACTER, problems.single().kind)
        assertEquals("pass\tword", repository.stored.entries.single().password)
    }

    // ---- The name ----------------------------------------------------------------------------

    @Test
    fun acceptanceName_aBlankNameIsFilledFromTheHost() = EditKit.run {
        val repository = repository()
        val form = form(repository)
        set(form.form.title, "   ")
        set(form.form.username, "aziz")
        set(form.form.websites.first().state, "https://mail.google.com/mail/u/0")
        form.save(texts)
        settle()
        val saved = repository.stored.entries.single()
        assertEquals("mail.google.com", saved.title)
        assertEquals(listOf("https://mail.google.com/mail/u/0"), saved.urls)
    }

    @Test
    fun acceptanceName_aBlankNameWithoutAWebsiteIsUntitledAndTheTime() = EditKit.run {
        val repository = repository()
        val form = form(repository)
        set(form.form.password, "secret-1")
        form.save(texts)
        settle()
        val saved = repository.stored.entries.single()
        assertEquals("EditTexts.untitled of the save's time", texts.untitled(clock.wallMs()), saved.title)
        assertEquals("Untitled ${clock.wallMs()}", saved.title)
    }

    @Test
    fun aHostLikeWebsiteGetsHttpsAndBlankRowsAreDropped() = EditKit.run {
        val repository = repository()
        val form = form(repository)
        set(form.form.title, "Click")
        set(form.form.websites.first().state, "click.uz")
        form.addWebsite()
        form.addWebsite()
        set(form.form.websites[2].state, " my.click.uz ")
        settle()
        assertEquals("a new row gets the focus", FieldKey.Website(form.form.websites[2].id), form.ui.value.focusRequest?.key)
        form.save(texts)
        settle()
        assertEquals(listOf("https://click.uz", "https://my.click.uz"), repository.stored.entries.single().urls)
    }

    // ---- Saving --------------------------------------------------------------------------------

    @Test
    fun saveOfANewAccountStoresEveryFieldAndOpensItsDetail() = EditKit.run {
        val repository = repository()
        val form = form(repository)
        set(form.form.title, "Click")
        set(form.form.username, "+998901234567")
        set(form.form.password, "Toshkent2020!")
        set(form.form.websites.first().state, "https://click.uz")
        set(form.form.notes, "PIN on the card")
        form.openAddField()
        form.chooseLabel(FieldLabel.PHONE)
        assertFalse("a phone number is not hidden by default", form.addField.secret)
        set(form.addField.value, "+998 71 200 00 00")
        form.confirmAddField("Phone number")
        settle()
        form.save(texts)
        settle()
        val saved = repository.stored.entries.single()
        assertEquals(EditDone.Added(saved.id), form.ui.value.done)
        assertEquals("Click", saved.title)
        assertEquals("+998901234567", saved.username)
        assertEquals("Toshkent2020!", saved.password)
        assertEquals(listOf("https://click.uz"), saved.urls)
        assertEquals("PIN on the card", saved.notes)
        assertEquals(listOf(CustomField("Phone number", "+998 71 200 00 00", false)), saved.fields)
        assertEquals(clock.wallMs(), saved.createdAt)
        assertEquals(clock.wallMs(), saved.passwordChangedAt)
        assertNull(repository.heldDraft)
        assertFalse(form.ui.value.backHandled)
    }

    @Test
    fun saveOfAnEditedPasswordKeepsThePreviousOneAndGoesBack() = EditKit.run {
        val gmail = entry(GMAIL, "Gmail", "aziz", "old-secret").copy(favourite = true)
        val repository = repository(Vault(entries = listOf(gmail, entry(BANK, "Bank"))))
        val form = form(repository, GMAIL)
        assertEquals("old-secret", form.form.password.text.toString())
        clock.advance(60_000L)
        set(form.form.password, "new-secret")
        form.save(texts)
        settle()
        assertEquals(EditDone.Updated(GMAIL), form.ui.value.done)
        val saved = repository.stored.entries.single { it.id == GMAIL }
        assertEquals("new-secret", saved.password)
        assertEquals("old-secret", saved.passwordHistory.single().password)
        assertEquals(clock.wallMs(), saved.passwordChangedAt)
        assertTrue("the star is kept", saved.favourite)
        assertFalse("an edit leaves no message for the detail screen", SavedNotice.take(GMAIL))
    }

    @Test
    fun aFailedSaveKeepsTheFormAndHoldsTheDraftAgain() = EditKit.run {
        val repository = repository()
        val form = form(repository)
        set(form.form.title, "Click")
        repository.failures.addLast(SaveProblem.FAILED)
        form.save(texts)
        settle()
        assertEquals(EditMessage.SaveFailed(SaveProblem.FAILED), form.ui.value.message)
        assertEquals(Phase.FORM, form.ui.value.phase)
        assertFalse(form.ui.value.saving)
        assertEquals("Click", form.form.title.text.toString())
        assertEquals("the draft is held again", "Click", repository.heldDraft?.entry?.title)
        assertTrue(repository.stored.entries.isEmpty())
        // "Try again" saves.
        form.save(texts)
        settle()
        assertTrue(form.ui.value.done is EditDone.Added)
    }

    @Test
    fun aVaultTooLargeIsSaid() = EditKit.run {
        val repository = repository()
        val form = form(repository)
        set(form.form.title, "Click")
        repository.failures.addLast(SaveProblem.TOO_LARGE)
        form.save(texts)
        settle()
        assertEquals(EditMessage.SaveFailed(SaveProblem.TOO_LARGE), form.ui.value.message)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun theSaveShowsItsProgressOnlyAfter300Ms() = EditKit.run {
        val slow = SlowRepository(repository(), saveMs = 1_000L)
        val form = form(slow)
        set(form.form.title, "Click")
        form.save(texts)
        scope.testScheduler.advanceTimeBy(299L)
        scope.testScheduler.runCurrent()
        assertTrue(form.ui.value.saving)
        assertFalse(form.ui.value.showProgress)
        form.save(texts)
        assertEquals("a second Save while one runs does nothing", 0, slow.fake.edits)
        scope.testScheduler.advanceTimeBy(2L)
        scope.testScheduler.runCurrent()
        assertTrue(form.ui.value.showProgress)
        settle()
        assertFalse(form.ui.value.showProgress)
        assertTrue(form.ui.value.done is EditDone.Added)
        assertEquals(1, slow.fake.edits)
    }

    @Test
    fun anEntryThatIsGoneShowsSoAndCloses() = EditKit.run {
        val repository = repository(Vault(entries = listOf(entry(GMAIL, "Gmail", deletedAt = 3L))))
        val form = form(repository, GMAIL)
        assertEquals("an entry in Recently deleted is not edited", Phase.GONE, form.ui.value.phase)
        val missing = form(repository, OLD)
        assertEquals(Phase.GONE, missing.ui.value.phase)
        missing.close()
        assertEquals(EditDone.Closed, missing.ui.value.done)
    }

    // ---- The generator in the form -----------------------------------------------------------

    @Test
    fun usingAPasswordOverATypedOneAsksFirst() = EditKit.run {
        val repository = repository()
        val form = form(repository)
        set(form.form.password, "typed")
        form.openGenerator()
        settle()
        val generated = form.generator.password()!!
        form.useGenerated()
        assertEquals(EditDialog.ReplacePassword, form.ui.value.dialog)
        assertEquals("typed", form.form.password.text.toString())
        // Cancel keeps the typed one and the sheet.
        form.dismissDialog()
        assertEquals("typed", form.form.password.text.toString())
        assertTrue(form.generator.state.value.open)
        form.useGenerated()
        form.replacePassword()
        settle()
        assertEquals(generated, form.form.password.text.toString())
        assertFalse(form.generator.state.value.open)
        assertEquals(generated, repository.heldDraft?.entry?.password)
    }

    // ---- Strength, reuse and the focus ----------------------------------------------------

    @Test
    fun theMeterAndTheReuseNoteFollowThePassword() = EditKit.run {
        val repository = repository(
            Vault(
                entries = listOf(
                    entry(GMAIL, "Gmail", password = "Tashkent2020"),
                    entry(BANK, "Bank", password = "Tashkent2020"),
                    entry(OLD, "Old", password = "Tashkent2020", deletedAt = 1L),
                ),
            ),
        )
        val form = form(repository)
        assertNull(form.ui.value.strength)
        set(form.form.password, "short")
        assertEquals(1, form.ui.value.strength?.score)
        assertEquals(0, form.ui.value.reuse)
        set(form.form.password, "Tashkent2020")
        assertEquals(4, form.ui.value.strength?.score)
        assertEquals("other accounts outside Recently deleted", 2, form.ui.value.reuse)
        // Editing one of them: it does not count itself.
        val gmail = form(repository, GMAIL)
        assertEquals(1, gmail.ui.value.reuse)
        set(form.form.password, "")
        assertNull(form.ui.value.strength)
        assertEquals(0, form.ui.value.reuse)
    }

    @Test
    fun theFocusedFieldIsGivenBackAfterARecreationButNotAfterALock() = EditKit.run {
        val repository = repository()
        val form = form(repository)
        assertNull(form.focusToRestore())
        form.focused(FieldKey.Title)
        form.focused(FieldKey.Password)
        form.unfocused(FieldKey.Title)
        assertEquals("a late loss of another field changes nothing", FieldKey.Password, form.focusToRestore())
        form.unfocused(FieldKey.Password)
        assertNull(form.focusToRestore())
        form.focused(FieldKey.Notes)
        repository.close()
        settle()
        repository.open()
        settle()
        assertNull("after a lock nothing has the focus", form.focusToRestore())
    }

    @Test
    fun anExtraFieldNeedsALabel() = EditKit.run {
        val form = form(repository())
        form.openAddField()
        assertEquals(EditDialog.AddField, form.ui.value.dialog)
        set(form.addField.value, "value")
        form.confirmAddField("")
        assertTrue(form.addField.labelMissing)
        assertTrue(form.form.fields.isEmpty())
        form.chooseLabel(FieldLabel.OTHER)
        assertTrue("an unknown label hides its value by default", form.addField.secret)
        set(form.addField.name, "  Branch code  ")
        form.confirmAddField("Other…")
        settle()
        val row = form.form.fields.single()
        assertEquals("Branch code", row.name)
        assertTrue(row.secret)
        assertEquals("value", row.value.text.toString())
        assertEquals(listOf(CustomField("Branch code", "value", true)), form.form.draft().fields)
        assertEquals("the new field gets the focus", FieldKey.FieldValue(row.id), form.ui.value.focusRequest?.key)
        assertEquals("", form.addField.value.text.toString())
        assertTrue(form.ui.value.dirty)
        form.removeField(row.id)
        settle()
        assertFalse(form.ui.value.dirty)
    }
}
