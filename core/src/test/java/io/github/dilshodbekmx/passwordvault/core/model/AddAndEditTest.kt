package io.github.dilshodbekmx.passwordvault.core.model

import io.github.dilshodbekmx.passwordvault.core.model.ModelFixtures.MINUTE
import io.github.dilshodbekmx.passwordvault.core.model.ModelFixtures.T0
import io.github.dilshodbekmx.passwordvault.core.model.ModelFixtures.Texts
import io.github.dilshodbekmx.passwordvault.core.model.ModelFixtures.entry
import io.github.dilshodbekmx.passwordvault.core.model.ModelFixtures.id
import io.github.dilshodbekmx.passwordvault.core.model.ModelFixtures.random
import io.github.dilshodbekmx.passwordvault.core.model.ModelFixtures.vault
import io.github.dilshodbekmx.passwordvault.testing.FakeRandom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Save of the add and edit forms (ux.md §5.14), the star and "Leave out of the security check". */
class AddAndEditTest {
    private fun formOf(e: Entry) = DraftEntry(e.title, e.username, e.password, e.notes, e.urls, e.fields, e.favourite)

    @Test
    fun addGivesANewIdAndTheTimes() {
        val random = FakeRandom().queueUuids(id(77))
        val form = DraftEntry(title = "Gmail", username = "aziz", password = "s3cret", urls = listOf("mail.google.com"), favourite = true)
        val saved = VaultEdits.add(form, Texts, random)(vault(entry(1)), T0 + MINUTE)
        assertEquals(EntryResult.Done(id(77)), saved.value)
        val e = saved.vault.entries.last()
        assertEquals(id(77), e.id)
        assertEquals("Gmail", e.title)
        assertEquals("aziz", e.username)
        assertEquals("s3cret", e.password)
        assertEquals(listOf("https://mail.google.com"), e.urls)
        assertTrue(e.favourite)
        assertEquals(T0 + MINUTE, e.createdAt)
        assertEquals(T0 + MINUTE, e.updatedAt)
        assertEquals(T0 + MINUTE, e.passwordChangedAt)
        assertTrue(e.passwordHistory.isEmpty())
        assertEquals(2, saved.vault.entries.size)
        // An account that signs in by SMS code may have no password: no password time then.
        val noPassword = VaultEdits.add(DraftEntry(title = "SMS bank"), Texts, random())(Vault(), T0)
        assertNull(noPassword.vault.entries.single().passwordChangedAt)
    }

    @Test
    fun aNewIdIsNeverOneThatIsTakenOrInvalid() {
        val here = vault(entry(1), entry(2, deletedAt = T0))
        val random = FakeRandom().queueUuids(id(1), id(2), id(3))
        val saved = VaultEdits.add(DraftEntry(title = "New"), Texts, random)(here, T0)
        assertEquals(EntryResult.Done(id(3)), saved.value)
        val broken = object : io.github.dilshodbekmx.passwordvault.core.util.RandomSource by FakeRandom() {
            private var calls = 0
            override fun uuid(): String = if (calls++ < 3) "NOT-A-UUID" else id(9)
        }
        assertEquals(EntryResult.Done(id(9)), VaultEdits.add(DraftEntry(title = "New"), Texts, broken)(here, T0).value)
    }

    @Test
    fun saveDeletesTheDraftOfItsOwnForm() {
        val newDraft = Draft(null, T0, DraftEntry(title = "Yangi"))
        val added = VaultEdits.add(DraftEntry(title = "Yangi"), Texts, random())(Vault(draft = newDraft), T0)
        assertNull(added.vault.draft)
        // A draft of an edit of another entry stays.
        val otherDraft = Draft(id(1), T0, DraftEntry(title = "Edited"))
        val kept = VaultEdits.add(DraftEntry(title = "Yangi"), Texts, random())(vault(entry(1)).copy(draft = otherDraft), T0)
        assertSame(otherDraft, kept.vault.draft)
        // Edit: the draft of this entry goes, even when nothing in the entry changed.
        val e = entry(1)
        val withDraft = vault(e).copy(draft = Draft(e.id, T0, formOf(e)))
        val unchanged = VaultEdits.update(e.id, formOf(e), Texts)(withDraft, T0 + MINUTE)
        assertNull(unchanged.vault.draft)
        assertSame(withDraft.entries, unchanged.vault.entries)
        val another = vault(e, entry(2)).copy(draft = Draft(id(2), T0, DraftEntry(title = "x")))
        assertEquals(id(2), VaultEdits.update(e.id, formOf(e).copy(title = "New name"), Texts)(another, T0).vault.draft?.entryId)
    }

    @Test
    fun anEditThatChangesNothingSavesNothing() {
        val e = entry(1).copy(fields = listOf(CustomField("PIN code", "0042", secret = true)), notes = "n")
        val v = vault(e)
        val result = VaultEdits.update(e.id, formOf(e), Texts)(v, T0 + MINUTE)
        assertSame(v, result.vault)
        assertEquals(EntryResult.Done(e.id), result.value)
        // Spaces the rules trim away are no change either.
        val padded = formOf(e).copy(title = "  ${e.title} ", urls = e.urls + "  ")
        assertSame(v, VaultEdits.update(e.id, padded, Texts)(v, T0 + MINUTE).vault)
    }

    @Test
    fun anEditKeepsWhatTheFormDoesNotShow() {
        val e = entry(1).copy(
            changePending = true,
            pendingOldPassword = "website-has-this",
            checkIgnored = true,
            extras = mapOf("futureEntryKey" to "\"kept\""),
        )
        val edited = VaultEdits.update(e.id, formOf(e).copy(notes = "new note", favourite = true), Texts)(vault(e), T0 + MINUTE)
        val after = edited.vault.entries.single()
        assertEquals("new note", after.notes)
        assertTrue(after.favourite)
        assertEquals(e.copy(notes = "new note", favourite = true, updatedAt = T0 + MINUTE), after)
    }

    @Test
    fun aChangedPasswordGoesToHistoryWhichKeepsFive() {
        var v = vault(entry(1, password = "p0"))
        val id = id(1)
        for (i in 1..7) {
            val e = v.entries.single()
            v = VaultEdits.update(id, formOf(e).copy(password = "p$i"), Texts)(v, T0 + i * MINUTE).vault
            val after = v.entries.single()
            assertEquals("p$i", after.password)
            assertEquals(T0 + i * MINUTE, after.passwordChangedAt)
            assertEquals(T0 + i * MINUTE, after.updatedAt)
        }
        val history = v.entries.single().passwordHistory
        assertEquals(listOf("p6", "p5", "p4", "p3", "p2"), history.map { it.password })
        assertEquals(listOf(7L, 6L, 5L, 4L, 3L).map { T0 + it * MINUTE }, history.map { it.replacedAt })
    }

    @Test
    fun anUnchangedPasswordKeepsItsTimeAndAnEmptyOneIsNoPreviousPassword() {
        val e = entry(1, password = "same")
        val edited = VaultEdits.update(e.id, formOf(e).copy(username = "new login"), Texts)(vault(e), T0 + MINUTE)
        val after = edited.vault.entries.single()
        assertEquals(e.passwordChangedAt, after.passwordChangedAt)
        assertTrue(after.passwordHistory.isEmpty())
        val none = entry(2, password = "")
        val first = VaultEdits.update(none.id, formOf(none).copy(password = "first"), Texts)(vault(none), T0 + MINUTE)
        assertTrue("an empty password is not kept", first.vault.entries.single().passwordHistory.isEmpty())
        assertEquals(T0 + MINUTE, first.vault.entries.single().passwordChangedAt)
    }

    @Test
    fun aChangedPasswordLeavesTheMasterPasswordList() {
        val a = entry(1)
        val b = entry(2)
        val v = vault(a, b).copy(masterPasswordMatches = listOf(a.id, b.id))
        val renamed = VaultEdits.update(a.id, formOf(a).copy(title = "Renamed"), Texts)(v, T0)
        assertEquals(listOf(a.id, b.id), renamed.vault.masterPasswordMatches)
        val changed = VaultEdits.update(a.id, formOf(a).copy(password = "different"), Texts)(v, T0)
        assertEquals(listOf(b.id), changed.vault.masterPasswordMatches)
    }

    @Test
    fun aRefusedEditOrAMissingEntryChangesNothing() {
        val e = entry(1)
        val v = vault(e, entry(2, deletedAt = T0))
        val refused = VaultEdits.update(e.id, formOf(e).copy(password = "a\tb"), Texts)(v, T0)
        assertSame(v, refused.vault)
        assertEquals(EntryResult.Refused(listOf(EntryProblem(EntryField.PASSWORD, EntryProblem.Kind.CONTROL_CHARACTER))), refused.value)
        for (missing in listOf(id(2), id(3), "not-an-id")) {
            val result = VaultEdits.update(missing, DraftEntry(title = "x"), Texts)(v, T0)
            assertSame(missing, v, result.vault)
            assertEquals(missing, EntryResult.NotFound, result.value)
        }
    }

    @Test
    fun theStarAndTheSecurityCheckMark() {
        val e = entry(1)
        val v = vault(e)
        val starred = VaultEdits.setFavourite(e.id, true)(v, T0 + MINUTE)
        assertEquals(e.copy(favourite = true, updatedAt = T0 + MINUTE), starred.vault.entries.single())
        assertSame(starred.vault, VaultEdits.setFavourite(e.id, true)(starred.vault, T0 + 2 * MINUTE).vault)
        val ignored = VaultEdits.setCheckIgnored(e.id, true)(v, T0 + MINUTE)
        assertEquals(e.copy(checkIgnored = true, updatedAt = T0 + MINUTE), ignored.vault.entries.single())
        val again = VaultEdits.setCheckIgnored(e.id, false)(ignored.vault, T0 + 2 * MINUTE)
        assertEquals(e.copy(updatedAt = T0 + 2 * MINUTE), again.vault.entries.single())
        assertSame(v, VaultEdits.setCheckIgnored(e.id, false)(v, T0).vault)
        val deleted = vault(entry(2, deletedAt = T0))
        assertEquals(EntryResult.NotFound, VaultEdits.setFavourite(id(2), true)(deleted, T0).value)
        assertNotSame(v, starred.vault)
    }
}
