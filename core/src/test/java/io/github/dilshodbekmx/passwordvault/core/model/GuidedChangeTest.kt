package io.github.dilshodbekmx.passwordvault.core.model

import io.github.dilshodbekmx.passwordvault.core.model.ModelFixtures.MINUTE
import io.github.dilshodbekmx.passwordvault.core.model.ModelFixtures.T0
import io.github.dilshodbekmx.passwordvault.core.model.ModelFixtures.entry
import io.github.dilshodbekmx.passwordvault.core.model.ModelFixtures.id
import io.github.dilshodbekmx.passwordvault.core.model.ModelFixtures.vault
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The guided change of ux.md §5.17, and its acceptance check (ux.md §12 check 5). */
class GuidedChangeTest {
    private val original = "Toshkent2020"
    private val start = vault(
        entry(1, password = original).copy(
            passwordHistory = listOf(HistoryItem("older-1", T0 - 2), HistoryItem("older-2", T0 - 3)),
        ),
        entry(2),
    )
    private val gmail = id(1)

    @Test
    fun sevenRetriesThenPutBackRestoresTheOriginalPassword() {
        // ux.md §12 check 5: "After 7 taps on 'Create another password', 'No, put back the old
        // password' restores the original password."
        var v = VaultEdits.startChange(gmail, "new-0")(start, T0).vault
        assertPending(v, "new-0")
        for (i in 1..7) {
            val result = VaultEdits.retryChange(gmail, "new-$i")(v, T0 + i * MINUTE)
            assertEquals(EntryResult.Done(gmail), result.value)
            v = result.vault
            assertPending(v, "new-$i")
            // The tried passwords are in the history, five at most; the original is never pushed out.
            val e = v.entries.first()
            assertEquals((i - 1 downTo maxOf(0, i - 5)).map { "new-$it" } + listOf("older-1", "older-2").take(maxOf(0, 5 - i)), e.passwordHistory.map { it.password })
        }
        val back = VaultEdits.revertChange(gmail)(v, T0 + 10 * MINUTE)
        assertEquals(EntryResult.Done(gmail), back.value)
        val e = back.vault.entries.first()
        assertEquals(original, e.password)
        assertFalse(e.changePending)
        assertNull(e.pendingOldPassword)
        assertEquals(listOf("new-7", "new-6", "new-5", "new-4", "new-3"), e.passwordHistory.map { it.password })
        assertEquals(T0 + 10 * MINUTE, e.passwordChangedAt)
        assertEquals(T0 + 10 * MINUTE, e.updatedAt)
        assertSame("the other entry is untouched", start.entries[1], back.vault.entries[1])
    }

    @Test
    fun yesItsChangedPutsTheWebsitesOldPasswordInFront() {
        val started = VaultEdits.startChange(gmail, "new-0")(start, T0).vault
        val retried = VaultEdits.retryChange(gmail, "new-1")(started, T0 + MINUTE).vault
        val done = VaultEdits.confirmChange(gmail)(retried, T0 + 2 * MINUTE)
        assertEquals(EntryResult.Done(gmail), done.value)
        val e = done.vault.entries.first()
        assertEquals("new-1", e.password)
        assertFalse(e.changePending)
        assertNull(e.pendingOldPassword)
        assertEquals(listOf(original, "new-0", "older-1", "older-2"), e.passwordHistory.map { it.password })
        assertEquals(T0 + 2 * MINUTE, e.passwordHistory.first().replacedAt)
        assertEquals(T0 + MINUTE, e.passwordChangedAt)
        assertEquals(T0 + 2 * MINUTE, e.updatedAt)
    }

    @Test
    fun theStartKeepsTheWebsitesPasswordApartFromTheHistory() {
        val result = VaultEdits.startChange(gmail, "new-0")(start, T0 + MINUTE)
        val e = result.vault.entries.first()
        assertEquals("new-0", e.password)
        assertTrue(e.changePending)
        assertEquals(original, e.pendingOldPassword)
        assertEquals(listOf("older-1", "older-2"), e.passwordHistory.map { it.password })
        assertEquals(T0 + MINUTE, e.passwordChangedAt)
        assertEquals(T0 + MINUTE, e.updatedAt)
    }

    @Test
    fun startingAgainNeverOverwritesTheKeptPassword() {
        val started = VaultEdits.startChange(gmail, "new-0")(start, T0).vault
        // A second "Create new password" (another screen, a stale button) is a retry.
        val again = VaultEdits.startChange(gmail, "new-1")(started, T0 + MINUTE).vault
        assertPending(again, "new-1")
        assertEquals("new-0", again.entries.first().passwordHistory.first().password)
        // A retry on an entry without a change starts one.
        val fresh = VaultEdits.retryChange(gmail, "new-0")(start, T0).vault
        assertPending(fresh, "new-0")
        // The same password again changes nothing.
        assertSame(again, VaultEdits.retryChange(gmail, "new-1")(again, T0 + 2 * MINUTE).vault)
    }

    @Test
    fun aKeptPasswordWithoutAPendingChangeIsNotLost() {
        val odd = vault(entry(1, password = "current").copy(pendingOldPassword = "left-over"))
        val e = VaultEdits.startChange(gmail, "new-0")(odd, T0).vault.entries.single()
        assertEquals("current", e.pendingOldPassword)
        assertEquals(listOf("left-over"), e.passwordHistory.map { it.password })
    }

    @Test
    fun aPendingChangeWithoutAKeptPasswordJustEnds() {
        val odd = vault(entry(1, password = "current").copy(changePending = true))
        val back = VaultEdits.revertChange(gmail)(odd, T0).vault.entries.single()
        assertEquals("current", back.password)
        assertFalse(back.changePending)
        val done = VaultEdits.confirmChange(gmail)(odd, T0).vault.entries.single()
        assertFalse(done.changePending)
        assertTrue(done.passwordHistory.isEmpty())
    }

    @Test
    fun confirmAndPutBackNeedAPendingChange() {
        for (change in listOf(VaultEdits.confirmChange(gmail), VaultEdits.revertChange(gmail))) {
            val result = change(start, T0)
            assertSame(start, result.vault)
            assertEquals(EntryResult.NotPending, result.value)
        }
    }

    @Test
    fun aGeneratedPasswordFollowsThePasswordRules() {
        for (bad in listOf("a\tb", "x".repeat(1_001), "\uD800")) {
            val result = VaultEdits.startChange(gmail, bad)(start, T0)
            assertSame(start, result.vault)
            assertTrue(result.value is EntryResult.Refused)
            assertEquals(EntryField.PASSWORD, (result.value as EntryResult.Refused).problems.single().field)
        }
    }

    @Test
    fun anEntryInRecentlyDeletedOrMissingIsNotChanged() {
        val deleted = vault(entry(1, deletedAt = T0))
        for (change in listOf(
            VaultEdits.startChange(gmail, "n"),
            VaultEdits.retryChange(gmail, "n"),
            VaultEdits.confirmChange(gmail),
            VaultEdits.revertChange(gmail),
        )) {
            assertEquals(EntryResult.NotFound, change(deleted, T0).value)
            assertSame(deleted, change(deleted, T0).vault)
            assertEquals(EntryResult.NotFound, change(Vault(), T0).value)
        }
    }

    @Test
    fun aNewPasswordLeavesTheMasterPasswordListAndConfirmDoesNot() {
        val listed = start.copy(masterPasswordMatches = listOf(gmail))
        val started = VaultEdits.startChange(gmail, "new-0")(listed, T0).vault
        assertTrue(started.masterPasswordMatches.isEmpty())
        val relisted = started.copy(masterPasswordMatches = listOf(gmail))
        assertEquals(listOf(gmail), VaultEdits.confirmChange(gmail)(relisted, T0).vault.masterPasswordMatches)
        assertTrue(VaultEdits.revertChange(gmail)(relisted, T0).vault.masterPasswordMatches.isEmpty())
    }

    private fun assertPending(v: Vault, password: String) {
        val e = v.entries.first()
        assertEquals(password, e.password)
        assertTrue(e.changePending)
        assertEquals("the website's password is kept", original, e.pendingOldPassword)
    }
}
