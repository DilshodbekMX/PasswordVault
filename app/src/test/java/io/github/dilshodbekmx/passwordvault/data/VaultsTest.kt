package io.github.dilshodbekmx.passwordvault.data

import io.github.dilshodbekmx.passwordvault.core.lock.flow.CurrentVault
import io.github.dilshodbekmx.passwordvault.core.lock.flow.PasswordOpen
import io.github.dilshodbekmx.passwordvault.core.lock.flow.VaultOpen
import io.github.dilshodbekmx.passwordvault.core.lock.flow.VaultRef
import io.github.dilshodbekmx.passwordvault.core.lock.flow.VaultWrite
import io.github.dilshodbekmx.passwordvault.core.model.Draft
import io.github.dilshodbekmx.passwordvault.core.model.DraftEntry
import io.github.dilshodbekmx.passwordvault.core.model.Edited
import io.github.dilshodbekmx.passwordvault.testing.storage.FakeFiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Build plan 3.1, [JVM]: the vault-level calls of `VaultPort` (architecture.md §3.4, §4.11; lock.md
 * F8, F11, F16b, F17): a vault put aside opened in place, `swapIn`, `mergeAside`, `deleteAside`,
 * `replaceVault`, `changePassword` with `retireOldKeys`, and `putNewerAside`.
 */
class VaultsTest {
    private val files = FakeFiles().apply { mkdirs(FILES) }
    private val phone = Phone(files = files)

    private fun TestScope.started(): Pair<DefaultVaultRepository, CoroutineScope> {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        return phone.process(scope, dispatcher) to scope
    }

    private fun TestScope.newProcess(scope: CoroutineScope) = phone.process(scope, StandardTestDispatcher(testScheduler))

    private fun titles(repo: DefaultVaultRepository) = repo.shown().vault.entries.map { it.title }

    @Test
    fun aVaultPutAsideOpensInPlaceAndSwapsIn() = runTest {
        val (repo, scope) = started()
        try {
            // Vault A, then "Start with an empty app" (F17 step 2).
            val a = repo.create(vaultId(1), password = OTHER_PASSWORD)
            repo.add(form("in A"), phone.random)
            repo.lock()
            assertTrue(repo.putAside())
            var scan = repo.vaults()
            assertEquals(CurrentVault.NoVault, scan.current)
            assertEquals(listOf(a.vaultId), scan.aside.map { it.vaultId })
            assertEquals(2L, scan.aside.single().newest!!.number)

            // Vault B is made and opened; A is opened in place and kept apart (F17 step 4).
            val b = repo.create(vaultId(2))
            repo.add(form("in B"), phone.random)
            assertEquals(PasswordOpen.Wrong, repo.openWithPassword(VaultRef.Aside(a.vaultId), bytesOf(PASSWORD)))
            val opened = repo.unlock(OTHER_PASSWORD, VaultRef.Aside(a.vaultId))
            assertEquals(a.vaultId, opened.vaultId)
            assertEquals("B is still the one shown", listOf("in B"), titles(repo))

            // Only then does it become current; B goes aside.
            assertFalse("only the vault opened in place", repo.swapIn(vaultId(9)))
            assertTrue(repo.swapIn(a.vaultId))
            assertEquals(listOf("in A"), titles(repo))
            scan = repo.vaults()
            assertEquals(a.vaultId, (scan.current as CurrentVault.Present).vaultId)
            assertEquals(listOf(b.vaultId), scan.aside.map { it.vaultId })
            // It saves where it now lives, and opens there with its own password.
            repo.add(form("A again"), phone.random)
            repo.lock()
            val again = newProcess(scope)
            again.unlock(OTHER_PASSWORD)
            assertEquals(listOf("in A", "A again"), titles(again))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun theAccountsOfAVaultPutAsideAreMergedIn() = runTest {
        val (repo, scope) = started()
        try {
            val a = repo.create(vaultId(1), password = OTHER_PASSWORD)
            repo.edit { vault, _ -> Edited(vault.copy(entries = listOf(entry(1), entry(2, title = "Changed there"), entry(3))), Unit) }
            repo.lock()
            assertTrue(repo.putAside())
            repo.create(vaultId(2))
            repo.edit { vault, _ -> Edited(vault.copy(entries = listOf(entry(1), entry(2))), Unit) }
            assertEquals(VaultWrite.NothingChanged, repo.mergeAside(a.vaultId)) // not opened in place yet

            repo.unlock(OTHER_PASSWORD, VaultRef.Aside(a.vaultId))
            val merged = repo.mergeAside(a.vaultId)
            assertTrue("merged: $merged", merged is VaultWrite.Done)
            merged as VaultWrite.Done
            assertEquals(vaultId(2), merged.vaultId)
            assertEquals(repo.shown().generation, merged.generation)
            // Identical skipped, new id added, same id with other content added beside it (§4.11, ux.md §5.21).
            val entries = repo.shown().vault.entries
            assertEquals(listOf("Account 1", "Account 2", "Changed there (from backup)", "Account 3"), entries.map { it.title })
            assertEquals(4, entries.map { it.id }.toSet().size)
            assertEquals(id(3), entries.last().id)
            assertNotEquals(id(2), entries[2].id)
            // The vault put aside is no longer held, and stays on the phone.
            assertEquals(VaultWrite.NothingChanged, repo.mergeAside(a.vaultId))
            assertEquals(listOf(a.vaultId), repo.vaults().aside.map { it.vaultId })
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun deleteAsideDeletesThatVaultsFiles() = runTest {
        val (repo, scope) = started()
        try {
            val a = repo.create(vaultId(1), password = OTHER_PASSWORD)
            repo.lock()
            assertTrue(repo.putAside())
            repo.create(vaultId(2))
            repo.unlock(OTHER_PASSWORD, VaultRef.Aside(a.vaultId))
            assertTrue(repo.deleteAside(a.vaultId))
            assertEquals(emptyList<String>(), repo.vaults().aside.map { it.vaultId })
            assertFalse("no longer held", repo.swapIn(a.vaultId))
            assertEquals(listOf("live"), phone.names(VAULTS))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun replaceVaultMakesANewVaultFromTheOpenOneAndPutsTheOldOneAside() = runTest {
        val (repo, scope) = started()
        try {
            val old = repo.create(vaultId(1))
            repo.add(form("kept"), phone.random)
            val draft = Draft(null, 5L, DraftEntry(title = "in the form"))
            repo.holdDraft(draft)
            val (_, oldKey) = repo.withVaultKey { id, key -> id to key.copyOf() }

            // F11 step 16: vault N from the open entries, under the typed new password.
            val done = repo.replaceVault(vaultId(5), bytesOf(OTHER_PASSWORD))
            assertTrue("replaced: $done", done is VaultWrite.Done)
            done as VaultWrite.Done
            assertEquals(vaultId(5), done.vaultId)
            assertEquals(1L, done.generation)
            assertNotEquals(old.vkId, done.vkId)
            assertEquals("the session goes on, on vault N", listOf("kept"), titles(repo))
            repo.flushDraft()
            assertEquals("the form's draft goes on too", draft, repo.shown().vault.draft)
            val scan = repo.vaults()
            assertEquals(vaultId(5), (scan.current as CurrentVault.Present).vaultId)
            assertEquals(listOf(old.vaultId), scan.aside.map { it.vaultId })

            // The old vault opens only with its own password, in place; N with the new one.
            repo.lock()
            assertEquals(PasswordOpen.Wrong, repo.openWithPassword(VaultRef.Current, bytesOf(PASSWORD)))
            assertTrue(repo.open(VaultRef.Aside(old.vaultId), mapOf(old.vkId to oldKey)) is VaultOpen.Opened)
            repo.unlock(PASSWORD, VaultRef.Aside(old.vaultId))
            repo.unlock(OTHER_PASSWORD)
            assertEquals(listOf("kept"), titles(repo))
            assertEquals(VaultWrite.NothingChanged, newProcess(scope).replaceVault(vaultId(6), bytesOf(PASSWORD)))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun aChangedMasterPasswordIsProvenAndTheOldOneIsAnOlderPassword() = runTest {
        val (repo, scope) = started()
        try {
            val created = repo.create()
            repo.add(form("one"), phone.random) // generation 2
            phone.clock.advance(60_000L)
            // F8 steps 4 and 6.
            val (vkId2, vk2) = repo.newKey()
            val changed = repo.changePassword(bytesOf(OTHER_PASSWORD), bytesOf(OTHER_PASSWORD), vkId2, vk2)
            assertTrue("changed: $changed", changed is VaultWrite.Done)
            changed as VaultWrite.Done
            assertEquals(created.vaultId, changed.vaultId)
            assertEquals(vkId2, changed.vkId)
            assertEquals(3L, changed.generation)
            assertEquals(phone.clock.wallMs(), changed.createdMs)
            assertEquals(vkId2, repo.withVaultKey { id, _ -> id })
            repo.add(form("two"), phone.random) // generation 4, under the new key
            assertEquals(true, repo.checkPassword(bytesOf(OTHER_PASSWORD)))
            assertEquals(false, repo.checkPassword(bytesOf(PASSWORD)))
            repo.lock()

            // A later process: the old password opens only an older copy (F16b).
            val again = newProcess(scope)
            val older = again.openWithPassword(VaultRef.Current, bytesOf(PASSWORD))
            assertEquals(PasswordOpen.OlderPassword(changedAtMs = changed.createdMs, generation = 2L), older)
            assertEquals(VaultState.Locked, again.state.value)
            // The new one opens, from disk; then the old key's generations are retired (F8 step 10).
            again.unlock(OTHER_PASSWORD)
            assertEquals(listOf("one", "two"), titles(again))
            assertEquals(setOf(vkId2), again.retireOldKeys())
            assertEquals(listOf(3L, 4L), phone.liveNumbers())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun aChangeThatDidNotFinishGoesBackWithPutNewerAside() = runTest {
        val (repo, scope) = started()
        try {
            repo.create()
            repo.add(form("before the change"), phone.random) // generation 2
            val (vkId2, vk2) = repo.newKey()
            repo.changePassword(bytesOf(OTHER_PASSWORD), bytesOf(OTHER_PASSWORD), vkId2, vk2) // generation 3
            repo.lock()
            // "Open my passwords from before the change" (F16b steps 2 and 3).
            val again = newProcess(scope)
            val older = again.openWithPassword(VaultRef.Current, bytesOf(PASSWORD)) as PasswordOpen.OlderPassword
            assertEquals(2L, older.generation)
            assertTrue(again.putNewerAside(older.generation))
            assertTrue("vault-0000000003.aside" in phone.names(LIVE))
            again.unlock(PASSWORD)
            assertEquals(listOf("before the change"), titles(again))
            assertEquals(PasswordOpen.Wrong, newProcess(scope).openWithPassword(VaultRef.Current, bytesOf(OTHER_PASSWORD)))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun aChangeWhoseProofFailsIsQuarantinedAndTheOldPasswordStillWorks() = runTest {
        val (repo, scope) = started()
        try {
            repo.create()
            val (vkId2, vk2) = repo.newKey()
            assertEquals(VaultWrite.Quarantined, repo.changePassword(bytesOf(OTHER_PASSWORD), bytesOf("a typo"), vkId2, vk2))
            assertTrue("vault-0000000002.bad" in phone.names(LIVE))
            repo.add(form("still under the old key"), phone.random)
            repo.lock()
            newProcess(scope).unlock(PASSWORD)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun retireOldKeysNeedsAnOpenVault() = runTest {
        val (repo, scope) = started()
        try {
            try {
                repo.retireOldKeys()
                fail("an empty answer would let the lock drop every wrap")
            } catch (e: IllegalStateException) {
                // No vault is open.
            }
        } finally {
            scope.cancel()
        }
    }
}
