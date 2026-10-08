package io.github.dilshodbekmx.passwordvault.data

import io.github.dilshodbekmx.passwordvault.core.lock.flow.VaultOpen
import io.github.dilshodbekmx.passwordvault.core.lock.flow.VaultRef
import io.github.dilshodbekmx.passwordvault.core.lock.flow.VaultWrite
import io.github.dilshodbekmx.passwordvault.core.storage.PasswordResult
import io.github.dilshodbekmx.passwordvault.core.storage.VaultStores
import io.github.dilshodbekmx.passwordvault.testing.storage.FakeFiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import io.github.dilshodbekmx.passwordvault.core.storage.VaultRef as StoreVaultRef

/**
 * The `createdMs` of `VaultOpen.Opened`, `PasswordOpen.Opened` and `VaultWrite.Done`
 * (architecture.md §3.4): the `created` in the header of the generation that opened or was just
 * written, which the older-copy notice names (ux.md §5.9). The repository takes it from
 * `OpenedVault.createdMs` (§3.3) and reads no generation file of its own at an unlock.
 */
@RunWith(Parameterized::class)
class CreatedTimeTest(private val kind: DiskKind) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun kinds(): List<DiskKind> = DiskKind.entries

        private const val T0 = 1_791_244_800_000L
        private const val DAY = 86_400_000L
    }

    @get:Rule
    val temp = TemporaryFolder()

    private val disk: Disk by lazy { Disk.of(kind, temp) }

    private fun createdIn(phone: Phone, path: String): Long = phone.header(path).createdMs

    @Test
    fun everyResultCarriesTheCreatedTimeOfItsGeneration() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        try {
            val phone = Phone(disk)
            phone.clock.setWall(T0)
            var repo = phone.process(scope, dispatcher)
            val created = repo.create()
            assertEquals("createVault", T0, created.createdMs)
            assertEquals(createdIn(phone, phone.pathOf(1)), created.createdMs)
            phone.clock.advance(1_000L)
            repo.add(form("gmail"), phone.random) // generation 2
            val (vkId, vk) = repo.withVaultKey { id, key -> id to key.copyOf() }
            repo.lock()

            // A day later, in a new process: the time the copy was written, never the time it opened.
            phone.clock.advance(DAY)
            repo = phone.process(scope, dispatcher)
            val byPassword = repo.unlock()
            assertEquals(2L, byPassword.generation)
            assertFalse(byPassword.olderCopy)
            assertEquals("openWithPassword", T0 + 1_000L, byPassword.createdMs)
            assertEquals(createdIn(phone, phone.pathOf(2)), byPassword.createdMs)
            repo.lock()
            repo = phone.process(scope, dispatcher)
            val byKey = repo.open(VaultRef.Current, mapOf(vkId to vk)) as VaultOpen.Opened
            assertEquals(2L, byKey.generation)
            assertEquals("open", T0 + 1_000L, byKey.createdMs)

            // F8: the generation under the new key.
            phone.clock.advance(60_000L)
            val (vkId2, vk2) = repo.newKey()
            val changed = repo.changePassword(bytesOf(OTHER_PASSWORD), bytesOf(OTHER_PASSWORD), vkId2, vk2) as VaultWrite.Done
            assertEquals(3L, changed.generation)
            assertEquals("changePassword", T0 + 1_000L + DAY + 60_000L, changed.createdMs)
            assertEquals(createdIn(phone, phone.pathOf(3)), changed.createdMs)

            // F11 step 16: vault N's first generation.
            phone.clock.advance(60_000L)
            val replaced = repo.replaceVault(vaultId(5), bytesOf(PASSWORD)) as VaultWrite.Done
            assertEquals(vaultId(5), replaced.vaultId)
            assertEquals(1L, replaced.generation)
            assertEquals("replaceVault", T0 + 1_000L + DAY + 120_000L, replaced.createdMs)
            assertEquals(createdIn(phone, phone.pathOf(1)), replaced.createdMs)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun anOlderCopyCarriesItsOwnCreatedTime() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        try {
            val phone = Phone(disk)
            phone.clock.setWall(T0)
            var repo = phone.process(scope, dispatcher)
            repo.create()
            phone.clock.advance(1_000L)
            repo.add(form("one"), phone.random) // generation 2
            val (vkId, vk) = repo.withVaultKey { id, key -> id to key.copyOf() }
            phone.clock.advance(1_000L)
            repo.add(form("two"), phone.random) // generation 3, lost below
            repo.lock()
            disk.zeroFill(phone.pathOf(3))

            phone.clock.advance(DAY)
            repo = phone.process(scope, dispatcher)
            val byPassword = repo.unlock()
            assertEquals(2L, byPassword.generation)
            assertTrue(byPassword.olderCopy)
            assertEquals("the copy that opened, not the newest", T0 + 1_000L, byPassword.createdMs)
            repo.lock()
            repo = phone.process(scope, dispatcher)
            val byKey = repo.open(VaultRef.Current, mapOf(vkId to vk)) as VaultOpen.Opened
            assertEquals(2L, byKey.generation)
            assertTrue(byKey.olderCopy)
            assertEquals(T0 + 1_000L, byKey.createdMs)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun aVaultPutAsideCarriesTheCreatedTimeOfItsOwnGeneration() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        try {
            val phone = Phone(disk)
            phone.clock.setWall(T0)
            var repo = phone.process(scope, dispatcher)
            val first = repo.create()
            phone.clock.advance(7_000L)
            repo.add(form("later"), phone.random) // generation 2
            val (vkId, vk) = repo.withVaultKey { id, key -> id to key.copyOf() }
            repo.lock()
            assertTrue(repo.putAside())

            phone.clock.advance(DAY)
            repo = phone.process(scope, dispatcher)
            val byPassword = repo.unlock(PASSWORD, VaultRef.Aside(first.vaultId))
            assertEquals(first.vaultId, byPassword.vaultId)
            assertEquals(2L, byPassword.generation)
            assertEquals(T0 + 7_000L, byPassword.createdMs)
            repo.lock()
            repo = phone.process(scope, dispatcher)
            val byKey = repo.open(VaultRef.Aside(first.vaultId), mapOf(vkId to vk)) as VaultOpen.Opened
            assertEquals(T0 + 7_000L, byKey.createdMs)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun anUnlockReadsNoFileBeyondWhatTheStoreReads() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        try {
            val phone = Phone(disk)
            val first = phone.process(scope, dispatcher)
            first.create()
            first.add(form("one"), phone.random)
            first.add(form("two"), phone.random)
            first.lock()
            val reads = mutableListOf<String>()
            disk.beforeOp = { op, path -> if (op == FakeFiles.Op.READ) synchronized(reads) { reads += path } }

            // The store alone, in a process of its own, as the repository uses it for one slot.
            val store = VaultStores.create(phone.files, phone.codec, phone.clock, phone.vaultsDir, phone.build)
            val slot = store.slots(StoreVaultRef.Current).single()
            val kek = phone.kdf.kek(bytesOf(PASSWORD), slot.salt)
            assertTrue(store.openWithSlot(StoreVaultRef.Current, slot, kek) is PasswordResult.Opened)
            val byStore = synchronized(reads) { reads.toList().also { reads.clear() } }
            assertTrue("the store read the generation that opened", phone.pathOf(3) in byStore)

            // The repository in a new process: nothing more.
            val opened = phone.process(scope, dispatcher).unlock()
            val byRepository = synchronized(reads) { reads.toList() }
            assertEquals(3L, opened.generation)
            assertEquals(byStore, byRepository)
        } finally {
            disk.beforeOp = null
            scope.cancel()
        }
    }
}
