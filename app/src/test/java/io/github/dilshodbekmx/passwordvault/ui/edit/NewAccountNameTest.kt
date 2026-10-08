package io.github.dilshodbekmx.passwordvault.ui.edit

import io.github.dilshodbekmx.passwordvault.core.model.Draft
import io.github.dilshodbekmx.passwordvault.core.model.DraftEntry
import io.github.dilshodbekmx.passwordvault.core.model.EntryRules
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.ui.edit.EditKit.Companion.GMAIL
import io.github.dilshodbekmx.passwordvault.ui.edit.EditKit.Companion.entry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 'Add "gmal" as a new account' on the form's side (ux.md §5.11; architecture.md §3.6): the name
 * handed over through [NewAccountName] is where a new form starts. It is taken once, when the form
 * is made, and only by a new account's form; a form with nothing else typed has no change, so Back
 * asks nothing and no draft is held or written; anything else typed is a change as usual; a lock
 * drops the name with the rest of the form.
 */
class NewAccountNameTest {
    @Before
    @After
    fun nothingHandedOver() = NewAccountName.clear()

    @Test
    fun aHandedOverNameIsWhereTheNewFormStarts() = EditKit.run {
        val repository = repository(Vault(entries = listOf(entry(GMAIL, "Gmail"))))
        NewAccountName.offer("gmal")
        val form = form(repository)
        assertEquals(Phase.FORM, form.ui.value.phase)
        assertTrue(form.ui.value.isNew)
        assertEquals("gmal", form.form.title.text.toString())
        assertNull("taken once", NewAccountName.take())

        // Nothing else typed: no change, nothing held, and Back is Navigation's.
        assertFalse(form.ui.value.dirty)
        assertFalse(form.ui.value.backHandled)
        assertNull(repository.heldDraft)
        repository.flushDraft() // the app is left: AutoLock's flush writes nothing
        assertNull(repository.stored.draft)

        // Close asks nothing and leaves; nothing was written.
        form.close()
        settle()
        assertNull("no \"Save changes?\"", form.ui.value.dialog)
        assertEquals(EditDone.Closed, form.ui.value.done)
        assertNull(repository.stored.draft)
        assertEquals("nothing was saved", 0, repository.saves)
    }

    @Test
    fun anythingElseTypedIsAChangeAndItsDraftKeepsTheName() = EditKit.run {
        val repository = repository()
        NewAccountName.offer("gmal")
        val form = form(repository)
        type(form.form.username, FieldInput.USERNAME, "aziz@gmail.com")
        assertTrue(form.ui.value.dirty)
        assertTrue(form.ui.value.backHandled)
        val held = repository.heldDraft
        assertNotNull(held)
        assertNull(held!!.entryId)
        assertEquals("gmal", held.entry.title)
        assertEquals("aziz@gmail.com", held.entry.username)
        form.close()
        assertEquals(EditDialog.SaveChanges, form.ui.value.dialog)

        // Typing the username away again: back where the form started, which is no change.
        form.dismissDialog()
        set(form.form.username, "")
        assertFalse(form.ui.value.dirty)
        assertNull(repository.heldDraft)

        // A changed name is a change too.
        set(form.form.title, "Gmail")
        assertTrue(form.ui.value.dirty)
        assertEquals("Gmail", repository.heldDraft?.entry?.title)
    }

    @Test
    fun saveStoresTheHandedOverName() = EditKit.run {
        val repository = repository()
        NewAccountName.offer("gmal")
        val form = form(repository)
        set(form.form.password, "hunter2-very-long")
        form.save(texts)
        settle()
        val done = form.ui.value.done as EditDone.Added
        assertEquals("gmal", repository.stored.entries.single { it.id == done.entryId }.title)
        assertTrue(SavedNotice.take(done.entryId))
    }

    @Test
    fun onlyTheNextNewAccountsFormTakesTheName() = EditKit.run {
        val repository = repository(Vault(entries = listOf(entry(GMAIL, "Gmail"))))
        NewAccountName.offer("gmal")
        val edit = form(repository, GMAIL)
        assertEquals("an account's own form takes nothing", "Gmail", edit.form.title.text.toString())
        val first = form(repository)
        assertEquals("gmal", first.form.title.text.toString())
        val second = form(repository)
        assertEquals("used up by the first", "", second.form.title.text.toString())
        assertFalse(second.ui.value.dirty)
    }

    @Test
    fun aLockDropsTheNameWithTheRestOfTheForm() = EditKit.run {
        val repository = repository()
        NewAccountName.offer("gmal")
        val form = form(repository)
        // The screen turns off: AutoLock flushes (nothing to write), then the vault locks.
        repository.flushDraft()
        repository.close()
        settle()
        assertEquals(Phase.LOADING, form.ui.value.phase)
        assertEquals("", form.form.title.text.toString())

        // After the unlock the same form starts empty: no draft was kept, and the name is gone.
        repository.open()
        settle()
        assertEquals(Phase.FORM, form.ui.value.phase)
        assertEquals("", form.form.title.text.toString())
        assertFalse(form.ui.value.dirty)
        assertNull(form.ui.value.message)
        assertNull(repository.stored.draft)
    }

    @Test
    fun aStoredDraftOfANewAccountStillWins() = EditKit.run {
        // The newest form's draft is never lost: it opens over the name, as it opens over an empty form.
        val draft = Draft(null, clock.wallMs(), DraftEntry(title = "Bank", password = "kept-secret"))
        val repository = repository(Vault(draft = draft))
        NewAccountName.offer("gmal")
        val form = form(repository)
        assertEquals("Bank", form.form.title.text.toString())
        assertEquals("kept-secret", form.form.password.text.toString())
        assertEquals(EditMessage.Kept, form.ui.value.message)
        assertTrue(form.ui.value.dirty)

        // "Discard" on the question: the draft leaves the vault, and nothing was saved.
        form.close()
        form.discard()
        settle()
        assertNull(repository.stored.draft)
        assertTrue(repository.stored.entries.isEmpty())
    }

    @Test
    fun theNameIsWhatTheNameFieldWouldKeep() = EditKit.run {
        assertEquals("gmal", EditViewModel.nameOf("gmal"))
        assertEquals("no white space at either end", "gmail com", EditViewModel.nameOf("  gmail com  "))
        assertEquals("no control characters or line breaks", "gmal", EditViewModel.nameOf("g\tm\u0000a\nl\r\u007f"))
        assertEquals("Oʻzbekiston", EditViewModel.nameOf("Oʻzbekiston"))
        val long = "😀".repeat(EntryRules.TITLE_MAX + 5)
        val cut = EditViewModel.nameOf(long)
        assertEquals("at most the name's limit, in code points", EntryRules.TITLE_MAX, EntryRules.length(cut))
        assertEquals("never half a pair", "😀".repeat(EntryRules.TITLE_MAX), cut)

        // Through the hand-over: the form starts with the cleaned name, which is no change.
        val repository = repository()
        NewAccountName.offer(" gm\tal\n")
        val form = form(repository)
        assertEquals("gmal", form.form.title.text.toString())
        assertFalse(form.ui.value.dirty)
    }
}
