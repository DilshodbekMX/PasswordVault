package io.github.dilshodbekmx.passwordvault.data

import io.github.dilshodbekmx.passwordvault.core.lock.flow.CurrentVault
import io.github.dilshodbekmx.passwordvault.core.lock.flow.MasterProof
import io.github.dilshodbekmx.passwordvault.core.lock.flow.PasswordOpen
import io.github.dilshodbekmx.passwordvault.core.lock.flow.VaultOpen
import io.github.dilshodbekmx.passwordvault.core.lock.flow.VaultRef
import io.github.dilshodbekmx.passwordvault.core.model.Draft
import io.github.dilshodbekmx.passwordvault.core.model.DraftEntry
import io.github.dilshodbekmx.passwordvault.core.model.Edited
import io.github.dilshodbekmx.passwordvault.core.model.VaultEdits
import io.github.dilshodbekmx.passwordvault.testing.storage.FakeFiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Build plan 3.1, [JVM]: nothing automatic is written before the older-copy notice is answered
 * (architecture.md §4.8, §5.5 "Automatic writes", D-26). The newest generation is zero-filled, as a
 * lost write leaves it; the older copy opens; until [DefaultVaultRepository.olderCopyAnswered] the
 * draft flush, the master-password matches, the purge of D-24 and housekeeping write nothing, while
 * the user's own save goes on. Then each of them runs.
 */
class OlderCopyTest {
    private val files = FakeFiles().apply { mkdirs(FILES) }
    private val phone = Phone(files = files)
    private val dayMs = 24L * 60 * 60 * 1000

    @Test
    fun nothingAutomaticIsWrittenBeforeTheOlderCopyNoticeIsAnswered() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        try {
            // A vault with an account deleted 31 days ago and one whose password is the master password.
            val now = phone.clock.wallMs()
            var repo = phone.process(scope, dispatcher)
            val created = repo.create()
            repo.edit { vault, _ ->
                Edited(vault.copy(entries = listOf(entry(1), entry(2, password = PASSWORD), entry(3, deletedAt = now - 31 * dayMs))), Unit)
            }
            repo.add(form("newest"), phone.random) // generation 3
            repo.lock()
            testScheduler.advanceUntilIdle()
            phone.settings.purgeArm.value = null // what the unlock above armed is not the point here
            files.damage(phone.pathOf(3)) { ByteArray(it.size) } // the newest copy is lost

            // A new process: the older copy opens, and says so.
            repo = phone.process(scope, dispatcher)
            val facts = repo.vaults().current as CurrentVault.Present
            assertTrue("olderCopyFirst before unlock", facts.olderCopyFirst)
            assertEquals(2L, facts.newest.number)
            val opened = repo.unlock()
            assertEquals(PasswordOpen.Opened(created.vaultId, 2L, created.vkId, now, olderCopy = true), opened)
            assertTrue(repo.shown().olderCopy)
            testScheduler.advanceUntilIdle()

            // The purge is not even armed, though an account is due.
            assertNull("purge not armed", phone.settings.purgeArm.value)
            // The draft flush writes nothing.
            val draft = Draft(null, 1L, DraftEntry(title = "Draft", password = "kept"))
            repo.holdDraft(draft)
            repo.flushDraft()
            // The master-password matches are worked out but not written.
            val proof = MasterProof.forFakes(bytesOf(PASSWORD), created.vaultId)
            assertEquals(setOf(id(2)), repo.matchesMasterPassword(proof))
            assertEquals(emptyList<String>(), repo.shown().vault.masterPasswordMatches)
            assertEquals("no generation written", listOf(1L, 2L, 3L), phone.liveNumbers())
            assertNull(repo.shown().vault.draft)

            // The user's own save goes on, numbered above every name; housekeeping does not run, so
            // the damaged newest copy is not put in quarantine.
            repo.add(form("mine"), phone.random)
            assertEquals(listOf(1L, 2L, 3L, 4L), phone.liveNumbers())
            assertFalse("no .bad yet", phone.names(phone.liveDir).any { it.endsWith(".bad") })
            assertNull(phone.settings.purgeArm.value)

            // The notice is answered: the work of the unlock runs, and every automatic write with it.
            repo.olderCopyAnswered()
            testScheduler.advanceUntilIdle()
            val arm = phone.settings.purgeArm.value
            assertNotNull("purge armed", arm)
            assertEquals(created.vaultId, arm!!.vaultId)
            repo.flushDraft()
            assertEquals(draft, repo.shown().vault.draft)
            assertEquals(setOf(id(2)), repo.matchesMasterPassword(proof))
            assertEquals(listOf(id(2)), repo.shown().vault.masterPasswordMatches)
            assertEquals(listOf(1L, 2L, 4L, 5L, 6L), phone.liveNumbers())
            assertTrue("housekeeping put the damaged copy in quarantine", "vault-0000000003.bad" in phone.names(phone.liveDir))
            assertTrue("still shown as an older copy", repo.shown().olderCopy)
            // A proof that was dropped (its flow ended) matches nothing and writes nothing.
            assertEquals(emptySet<String>(), repo.matchesMasterPassword(MasterProof.forFakes(bytesOf(PASSWORD), created.vaultId).also { it.drop() }))
            assertEquals(listOf(1L, 2L, 4L, 5L, 6L), phone.liveNumbers())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun aKeyOpensTheOlderCopyAndHoldsTheAutomaticWritesToo() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        try {
            var repo = phone.process(scope, dispatcher)
            repo.create()
            repo.add(form("one"), phone.random)
            val (vkId, vk) = repo.withVaultKey { id, key -> id to key.copyOf() }
            repo.add(form("two"), phone.random)
            repo.lock()
            files.damage(phone.pathOf(3)) { ByteArray(it.size) }
            repo = phone.process(scope, dispatcher)
            val opened = repo.open(VaultRef.Current, mapOf(vkId to vk)) as VaultOpen.Opened
            assertEquals(2L, opened.generation)
            assertTrue(opened.olderCopy)
            repo.holdDraft(Draft(null, 1L, DraftEntry(title = "x")))
            repo.flushDraft()
            assertEquals(listOf(1L, 2L, 3L), phone.liveNumbers())
            // The answer ends the hold; a lock ends the session, and an unlock that opens the newest copy has none.
            repo.olderCopyAnswered()
            repo.flushDraft()
            // Written, and housekeeping, due after the first durable save, put the damaged copy in quarantine.
            assertEquals(listOf(1L, 2L, 4L), phone.liveNumbers())
            assertTrue("vault-0000000003.bad" in phone.names(phone.liveDir))
            repo.lock()
            repo.unlock()
            assertFalse("the newest copy opens now", repo.shown().olderCopy)
            repo.edit(VaultEdits.markSecuritySeen())
            assertEquals(listOf(1L, 2L, 4L, 5L), phone.liveNumbers())
        } finally {
            scope.cancel()
        }
    }
}
