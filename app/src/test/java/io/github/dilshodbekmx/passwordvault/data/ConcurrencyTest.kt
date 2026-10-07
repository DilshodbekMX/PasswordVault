package io.github.dilshodbekmx.passwordvault.data

import io.github.dilshodbekmx.passwordvault.core.crypto.MasterKdf
import io.github.dilshodbekmx.passwordvault.core.lock.flow.MasterProof
import io.github.dilshodbekmx.passwordvault.core.lock.flow.PasswordOpen
import io.github.dilshodbekmx.passwordvault.core.lock.flow.VaultRef
import io.github.dilshodbekmx.passwordvault.core.model.Draft
import io.github.dilshodbekmx.passwordvault.core.model.DraftEntry
import io.github.dilshodbekmx.passwordvault.core.model.Edited
import io.github.dilshodbekmx.passwordvault.core.model.VaultEdits
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Build plan 3.1, [JVM]: what happens when the lock, a save and a derivation meet (architecture.md
 * §5.2, §5.3; lock.md I11), against a real `VaultStore` on `FakeFiles` and on a temporary directory.
 * Argon2id runs outside the vault mutex, so a save and the draft flush do not wait for it; an open
 * whose visit ends while its session starts is undone; `checkPassword` and `matchesMasterPassword`
 * give nothing for a session that ended meanwhile.
 */
@RunWith(Parameterized::class)
class ConcurrencyTest(private val kind: DiskKind) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun kinds(): List<DiskKind> = DiskKind.entries
    }

    @get:Rule
    val temp = TemporaryFolder()

    private val disk: Disk by lazy { Disk.of(kind, temp) }

    /** Argon2id that waits at [gate] while one is set: a derivation the test can hold open. */
    private class GatedKdf : MasterKdf {
        private val fast = FastKdf()

        @Volatile var gate: CompletableDeferred<Unit>? = null

        override suspend fun kek(passwordBytes: ByteArray, salt: ByteArray): ByteArray {
            gate?.await()
            return fast.kek(passwordBytes, salt)
        }
    }

    private val kdf = GatedKdf()
    private val phone: Phone by lazy { Phone(disk, kdf = kdf) }

    private fun TestScope.started(): Pair<DefaultVaultRepository, CoroutineScope> {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        return phone.process(scope, dispatcher) to scope
    }

    @Test
    fun aDerivationDoesNotHoldTheVaultMutex() = runTest {
        val (repo, scope) = started()
        try {
            // Vault 1 put aside, vault 2 open: the lock opens vault 1 in place (F17 step 5).
            repo.create(vaultId(1), password = OTHER_PASSWORD)
            repo.lock()
            assertTrue(repo.putAside())
            repo.create(vaultId(2))
            val gate = CompletableDeferred<Unit>()
            kdf.gate = gate
            val opening = async { repo.openWithPassword(VaultRef.Aside(vaultId(1)), bytesOf(OTHER_PASSWORD)) }
            val checking = async { repo.checkPassword(bytesOf(PASSWORD)) }
            testScheduler.advanceUntilIdle()
            assertFalse("both derivations are held open", opening.isCompleted || checking.isCompleted)

            // Meanwhile a save and the draft flush go through.
            repo.add(form("saved meanwhile"), phone.random)
            repo.holdDraft(Draft(null, 1L, DraftEntry(title = "flushed meanwhile")))
            repo.flushDraft()
            assertEquals("flushed meanwhile", repo.shown().vault.draft?.entry?.title)
            assertEquals(listOf("saved meanwhile"), repo.shown().vault.entries.map { it.title })

            kdf.gate = null
            gate.complete(Unit)
            assertTrue(opening.await() is PasswordOpen.Opened)
            assertEquals(true, checking.await())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun anOpenWhoseVisitEndsWhileItsSessionStartsIsUndone() = runTest {
        val (repo, scope) = started()
        try {
            repo.create()
            repo.lock()
            // The visit ends at the very moment the vault is shown (lock.md I11).
            var visit: Job? = null
            val watcher = scope.launch(Dispatchers.Unconfined) {
                repo.state.collect { if (it is VaultState.Unlocked) visit?.cancel() }
            }
            visit = launch { repo.openWithPassword(VaultRef.Current, bytesOf(PASSWORD)) }
            visit.join()
            watcher.cancel()
            assertTrue(visit.isCancelled)
            assertEquals("the session started for it was ended", VaultState.Locked, repo.state.value)
            try {
                repo.withVaultKey { _, _ -> Unit }
                fail("a session is open")
            } catch (e: IllegalStateException) {
                // No vault is open.
            }
            // A visit that goes on opens it.
            repo.unlock()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun anOpenWhoseAnswerIsLostToACancelledCallerIsUndone() = runTest {
        val (repo, scope) = started()
        try {
            repo.create()
            val (vkId, vk) = repo.withVaultKey { id, key -> id to key.copyOf() }
            repo.lock()
            // The open finishes and starts its session; then, before its caller resumes with the
            // answer, the caller is cancelled (a save queued for the mutex runs in between and ends
            // the visit). withContext drops the answer; the session must not stay open.
            var visit: Job? = null
            visit = launch { repo.open(VaultRef.Current, mapOf(vkId to vk)) }
            val between = launch {
                repo.edit { vault, _ ->
                    visit.cancel()
                    Edited(vault, Unit)
                }
            }
            visit.join()
            between.join()
            assertTrue(visit.isCancelled)
            assertEquals("nothing left open for a visit that ended", VaultState.Locked, repo.state.value)
            try {
                repo.withVaultKey { _, _ -> Unit }
                fail("a session is open")
            } catch (e: IllegalStateException) {
                // No vault is open.
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun checkPasswordGivesNothingForASessionThatEndedMeanwhile() = runTest {
        val (repo, scope) = started()
        try {
            repo.create()
            val gate = CompletableDeferred<Unit>()
            kdf.gate = gate
            val checking = async { repo.checkPassword(bytesOf(PASSWORD)) }
            testScheduler.advanceUntilIdle()
            repo.lock()
            kdf.gate = null
            gate.complete(Unit)
            assertNull("no answer for a vault that is no longer open", checking.await())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun matchesGiveNothingForASessionThatEndedMeanwhile() = runTest {
        val (repo, scope) = started()
        try {
            val created = repo.create()
            repo.edit { vault, _ -> Edited(vault.copy(entries = listOf(entry(1, password = PASSWORD))), Unit) }
            val proof = MasterProof.forFakes(bytesOf(PASSWORD), created.vaultId)
            val matching = async { repo.matchesMasterPassword(proof) }
            repo.lock() // queued behind nothing: it runs before the match's save does
            assertEquals(emptySet<String>(), matching.await())
            repo.unlock()
            assertEquals(emptyList<String>(), repo.shown().vault.masterPasswordMatches)
            assertEquals(setOf(id(1)), repo.matchesMasterPassword(proof))
            assertEquals(listOf(id(1)), repo.shown().vault.masterPasswordMatches)
            repo.edit(VaultEdits.markSecuritySeen())
        } finally {
            scope.cancel()
        }
    }
}
