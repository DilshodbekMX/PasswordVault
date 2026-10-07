package io.github.dilshodbekmx.passwordvault.data

import io.github.dilshodbekmx.passwordvault.core.lock.flow.CurrentVault
import io.github.dilshodbekmx.passwordvault.core.lock.flow.MasterProof
import io.github.dilshodbekmx.passwordvault.core.lock.flow.VaultRef
import io.github.dilshodbekmx.passwordvault.core.lock.flow.VaultWrite
import io.github.dilshodbekmx.passwordvault.core.model.Draft
import io.github.dilshodbekmx.passwordvault.core.model.DraftEntry
import io.github.dilshodbekmx.passwordvault.core.model.Edited
import io.github.dilshodbekmx.passwordvault.core.model.FileRef
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.core.vaultfile.BodyResult
import io.github.dilshodbekmx.passwordvault.core.vaultfile.FileCheck
import io.github.dilshodbekmx.passwordvault.testing.FakeRandom
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.security.MessageDigest

/**
 * Build plan 3.1, [JVM]: `sealBackup`, `openBackup` and a restore (architecture.md §4.4, §4.12;
 * lock.md F12, F13, F17 step 3). A backup is a new container with its own key, the vault's id and
 * the current generation; opening one tells "wrong", "damaged or wrong", "damaged", "newer", "not
 * ours" and "not enough memory" apart; a restore keeps and resets the keys of §4.12.
 */
@RunWith(Parameterized::class)
class BackupAndRestoreTest(private val kind: DiskKind) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun kinds(): List<DiskKind> = DiskKind.entries
    }

    @get:Rule
    val temp = TemporaryFolder()

    private val disk: Disk by lazy { Disk.of(kind, temp) }
    /** A vault with every key of §4.3 set, as it was on the old phone. */
    private val everything = Vault(
        entries = listOf(
            entry(1).copy(extras = mapOf("futureEntryKey" to "\"kept\"")),
            entry(2, deletedAt = 5_000L), // in Recently deleted: it goes into backups too
            entry(3, favourite = true),
        ),
        draft = Draft(entryId = id(1), savedAt = 9_000L, entry = DraftEntry(title = "Account 1", password = "half-typed")),
        plainExports = listOf(FileRef("content://downloads/document/17", "passwords-2026-10-07.csv", 7_000L)),
        backups = listOf(FileRef("content://externalstorage/document/backup", "PasswordVault-backup-2026-10-07.pvault", 8_000L)),
        masterPasswordMatches = listOf(id(3)),
        securitySeenAt = 6_000L,
        extras = mapOf("futureTopKey" to "{\"list\":[true,null,\"x\"]}"),
    )

    private class Open(val phone: Phone, val repo: DefaultVaultRepository, val scope: CoroutineScope)

    private fun TestScope.started(phone: Phone = Phone(disk)): Open {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        return Open(phone, phone.process(scope, dispatcher), scope)
    }

    /** The old phone: a vault holding [everything], and its backup made with the master password. */
    private suspend fun backupOf(open: Open): Pair<VaultWrite.Done, ByteArray> {
        val created = open.repo.create(vaultId(1))
        open.repo.edit { _, _ -> Edited(everything, Unit) }
        val backup = open.repo.sealBackup(MasterProof.forFakes(bytesOf(PASSWORD), created.vaultId))
        return created to backup
    }

    @Test
    fun aBackupHasItsOwnKeyTheVaultsIdAndTheCurrentGeneration() = runTest {
        val open = started()
        try {
            val (created, backup) = backupOf(open)
            val file = (open.phone.codec.check(backup) as FileCheck.Ok).file
            assertEquals(created.vaultId, file.header.vaultId)
            assertEquals("the generation it was made from", 2L, file.header.generation)
            assertNotEquals("its own key", created.vkId, file.header.vkId)
            val vaultSlot = open.phone.header(open.phone.pathOf(2)).slot
            assertFalse("its own salt", vaultSlot.salt.contentEquals(file.header.slot.salt))
            assertTrue(file.trailerOk)
            // The vault's key does not open it; the master password does, with Recently deleted.
            val vk = open.repo.withVaultKey { _, key -> key.copyOf() }
            assertEquals(BodyResult.TagFailed, open.phone.codec.open(file, vk))
            val opened = open.repo.openBackup(backup, bytesOf(PASSWORD)) as BackupResult.Opened
            assertEquals(everything, opened.vault)
            assertEquals(open.phone.clock.wallMs(), opened.createdMs)
            assertEquals("nothing on the phone changed", listOf(1L, 2L), open.phone.liveNumbers())
        } finally {
            open.scope.cancel()
        }
    }

    @Test
    fun openBackupTellsItsAnswersApart() = runTest {
        val open = started()
        try {
            val (_, backup) = backupOf(open)
            val repo = open.repo
            assertEquals(BackupResult.Wrong, repo.openBackup(backup, bytesOf(OTHER_PASSWORD)))

            // Damage confined to the trailer: with a wrong password it cannot be told from a typo.
            val badTrailer = backup.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
            assertEquals(BackupResult.WrongOrDamaged, repo.openBackup(badTrailer, bytesOf(OTHER_PASSWORD)))
            assertTrue("a body that opens is good even when the trailer is wrong", repo.openBackup(badTrailer, bytesOf(PASSWORD)) is BackupResult.Opened)

            // Damage in the body: the slot opens with the right password, the body does not.
            val badBody = backup.copyOf().also { it[it.size - 100] = (it[it.size - 100].toInt() xor 1).toByte() }
            assertEquals(BackupResult.Damaged, repo.openBackup(badBody, bytesOf(PASSWORD)))
            assertEquals(BackupResult.WrongOrDamaged, repo.openBackup(badBody, bytesOf(OTHER_PASSWORD)))

            assertEquals(BackupResult.Damaged, repo.openBackup(backup.copyOf(100), bytesOf(PASSWORD)))
            assertEquals(BackupResult.NotOurs, repo.openBackup("a photo, not a backup".encodeToByteArray(), bytesOf(PASSWORD)))

            // A newer framing with a good trailer (R1).
            val newer = backup.copyOf().also { it[7] = '2'.code.toByte() }
            val digest = MessageDigest.getInstance("SHA-256").digest(newer.copyOf(newer.size - 32))
            digest.copyInto(newer, newer.size - 32)
            assertEquals(BackupResult.Newer, repo.openBackup(newer, bytesOf(PASSWORD)))

            (open.phone.kdf as FastKdf).unavailable = true
            assertEquals(BackupResult.NoMemory, repo.openBackup(backup, bytesOf(PASSWORD)))
        } finally {
            open.scope.cancel()
        }
    }

    @Test
    fun aRestoreKeepsAndResetsTheKeysOfSection412() = runTest {
        val old = started()
        val new = started(Phone(Disk.of(kind, temp), random = FakeRandom(77L))) // another phone draws other numbers
        try {
            val (created, backup) = backupOf(old)
            val backupKey = (old.phone.codec.check(backup) as FileCheck.Ok).file.header.vkId

            // The new phone: the backup is opened and checked first (F13 steps 2 and 3).
            val opened = new.repo.openBackup(backup, bytesOf(PASSWORD)) as BackupResult.Opened
            assertEquals(CurrentVault.NoVault, new.repo.vaults().current)
            // Written as a new vault: a new vault_id, key and slot, generation 1 (F13 step 4).
            val restored = new.repo.createVault(vaultId(7), bytesOf(PASSWORD), bytesOf(PASSWORD), opened.vault, putCurrentAside = false)
            restored as VaultWrite.Done
            assertEquals(vaultId(7), restored.vaultId)
            assertEquals(1L, restored.generation)
            assertNotEquals(created.vkId, restored.vkId)
            assertNotEquals(backupKey, restored.vkId)

            // In a new process, with the master password: what was kept and what was reset (§4.12).
            new.repo.lock()
            val again = new.phone.process(new.scope, StandardTestDispatcher(testScheduler))
            again.unlock()
            val vault = again.shown().vault
            assertEquals("entries, Recently deleted included", everything.entries, vault.entries)
            assertEquals("the draft", everything.draft, vault.draft)
            assertEquals("the master-password matches", everything.masterPasswordMatches, vault.masterPasswordMatches)
            assertEquals("the unknown top-level keys", everything.extras, vault.extras)
            assertEquals("plain exports reset", emptyList<FileRef>(), vault.plainExports)
            assertEquals("backups reset", emptyList<FileRef>(), vault.backups)
            assertNull("securitySeenAt reset", vault.securitySeenAt)
            val header = new.phone.header(new.phone.pathOf(1))
            assertEquals(vaultId(7), header.vaultId)
            assertEquals(restored.vkId, header.vkId)
        } finally {
            old.scope.cancel()
            new.scope.cancel()
        }
    }

    @Test
    fun aRestoreOverAVaultPutsThatVaultAside() = runTest {
        val old = started()
        val phone = Phone(Disk.of(kind, temp), random = FakeRandom(78L))
        val here = started(phone)
        try {
            val (_, backup) = backupOf(old)
            val current = here.repo.create(vaultId(3), password = OTHER_PASSWORD)
            here.repo.add(form("only here"), phone.random)
            val opened = here.repo.openBackup(backup, bytesOf(PASSWORD)) as BackupResult.Opened
            val done = here.repo.createVault(vaultId(8), bytesOf(PASSWORD), bytesOf(PASSWORD), opened.vault, putCurrentAside = true)
            assertTrue("restored: $done", done is VaultWrite.Done)
            assertEquals(everything.entries, here.repo.shown().vault.entries)
            val scan = here.repo.vaults()
            assertEquals(vaultId(8), (scan.current as CurrentVault.Present).vaultId)
            assertEquals(listOf(current.vaultId), scan.aside.map { it.vaultId })
            // The vault put aside opens in place with its own master password, as it was.
            here.repo.lock()
            here.repo.unlock(OTHER_PASSWORD, VaultRef.Aside(current.vaultId))
            assertEquals("the current vault is not shown while locked", VaultState.Locked, here.repo.state.value)
        } finally {
            old.scope.cancel()
            here.scope.cancel()
        }
    }

    @Test
    fun sealBackupNeedsAProofForTheOpenVault() = runTest {
        val open = started()
        try {
            try {
                open.repo.sealBackup(MasterProof.forFakes(bytesOf(PASSWORD), vaultId(1)))
                fail("sealed while locked")
            } catch (e: IllegalStateException) {
                // No vault is open.
            }
            open.repo.create(vaultId(1))
            try {
                open.repo.sealBackup(MasterProof.forFakes(bytesOf(PASSWORD), vaultId(2)))
                fail("sealed with another vault's proof")
            } catch (e: IllegalStateException) {
                // The proof is for another vault.
            }
            // A proof that was dropped (ON_STOP, a lock) seals nothing.
            val proof = MasterProof.forFakes(bytesOf(PASSWORD), vaultId(1)).also { it.drop() }
            try {
                open.repo.sealBackup(proof)
                fail("sealed with a dropped proof")
            } catch (e: IllegalStateException) {
                // Dropped.
            }
        } finally {
            open.scope.cancel()
        }
    }
}
