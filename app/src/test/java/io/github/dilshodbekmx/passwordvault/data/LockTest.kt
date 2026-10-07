package io.github.dilshodbekmx.passwordvault.data

import io.github.dilshodbekmx.passwordvault.core.lock.flow.VaultRef
import io.github.dilshodbekmx.passwordvault.core.model.CustomField
import io.github.dilshodbekmx.passwordvault.core.model.Draft
import io.github.dilshodbekmx.passwordvault.core.model.DraftEntry
import io.github.dilshodbekmx.passwordvault.core.model.Edited
import io.github.dilshodbekmx.passwordvault.core.model.Entry
import io.github.dilshodbekmx.passwordvault.core.model.HistoryItem
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.core.model.VaultEdits
import io.github.dilshodbekmx.passwordvault.core.storage.OpenedVault
import io.github.dilshodbekmx.passwordvault.core.vaultfile.VaultHeader
import io.github.dilshodbekmx.passwordvault.testing.storage.FakeFiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier
import java.util.IdentityHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Build plan 3.1, [JVM]: after `lock()` no reference to the vault remains in the repository
 * (architecture.md §5.5 step 3; lock.md §11), and a lock waits for a save that is running.
 *
 * The first test opens a vault, holds a form's draft and opens a vault put aside in place, then
 * locks, and walks every object reachable from the repository through its fields (collections of
 * the JDK through their public interfaces). None of them may be a vault, an entry, a draft or an
 * `OpenedVault`, both keys must be zero, and no array may hold a key's bytes.
 */
class LockTest {
    private val files = FakeFiles().apply { mkdirs(FILES) }
    private val phone = Phone(files = files)

    @Test
    fun afterLockNoReferenceToTheVaultRemainsInTheRepository() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        try {
            val repo = phone.process(scope, dispatcher)
            // A vault put aside ("Start with an empty app"), and a new current one.
            repo.create(vaultId(1), password = OTHER_PASSWORD)
            repo.lock()
            assertTrue(repo.putAside())
            repo.create(vaultId(2))
            repo.edit { vault, _ ->
                val history = listOf(HistoryItem("old-secret", 1L))
                Edited(vault.copy(entries = listOf(entry(1).copy(passwordHistory = history), entry(2, deletedAt = 3L))), Unit)
            }
            repo.holdDraft(Draft(null, 1L, DraftEntry(title = "Typed", password = "typed-secret", fields = listOf(CustomField("PIN", "0042", true)))))
            repo.flushDraft()
            // The vault put aside opened in place, held beside the current one.
            repo.unlock(OTHER_PASSWORD, VaultRef.Aside(vaultId(1)))
            testScheduler.advanceUntilIdle()

            val keys = openedVaultsOf(repo).map { vkOf(it) }
            assertEquals("the current vault and the one put aside", 2, keys.size)
            val keyBytes = keys.map { it.copyOf() }
            assertTrue(keyBytes.all { bytes -> bytes.any { it != 0.toByte() } })

            repo.lock()

            assertEquals(VaultState.Locked, repo.state.value)
            for (key in keys) assertTrue("the key is zeroed", key.all { it == 0.toByte() })
            val reached = reachableFrom(repo)
            // The walk goes all the way into the store: it reaches the headers the store remembers.
            assertTrue("the walk reached the store's memory", reached.any { it is VaultHeader })
            val forbidden = reached.filter {
                it is Vault || it is Entry || it is Draft || it is DraftEntry || it is OpenedVault || it is CustomField || it is HistoryItem
            }
            assertEquals("vault objects reachable after lock: ${forbidden.map { it.javaClass.simpleName }}", 0, forbidden.size)
            val copies = reached.filterIsInstance<ByteArray>().filter { array -> keyBytes.any { it.contentEquals(array) } }
            assertEquals("arrays holding a key's bytes", 0, copies.size)
            val texts = reached.filterIsInstance<String>().filter { it.contains("secret") || it.startsWith("Typed") }
            assertEquals("account texts reachable after lock: $texts", 0, texts.size)
            // And it opens again, all of it.
            repo.unlock()
            assertEquals(listOf(id(1), id(2)), repo.shown().vault.entries.map { it.id })
            assertEquals("Typed", repo.shown().vault.draft?.entry?.title)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun aLockWaitsForASaveThatIsRunning() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val repo = phone.process(scope, Dispatchers.IO)
            repo.create()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            files.beforeOp = { op, path ->
                if (op == FakeFiles.Op.WRITE_NEW && path.endsWith(".tmp") && entered.count > 0) {
                    entered.countDown()
                    release.await(20, TimeUnit.SECONDS)
                }
            }
            val saving = async(Dispatchers.IO) { repo.edit(VaultEdits.add(form("in flight"), TestTexts, phone.random)) }
            assertTrue(entered.await(20, TimeUnit.SECONDS))
            val locking = async(Dispatchers.IO) { repo.lock() }
            delay(300)
            assertFalse("the lock waits for the save", locking.isCompleted)
            assertTrue(repo.state.value is VaultState.Unlocked)
            release.countDown()
            assertTrue(saving.await() is SaveResult.Saved)
            locking.await()
            assertEquals(VaultState.Locked, repo.state.value)
            files.beforeOp = null
            repo.unlock()
            assertEquals(listOf("in flight"), repo.shown().vault.entries.map { it.title })
        } finally {
            files.beforeOp = null
            scope.cancel()
        }
    }

    /** The `OpenedVault`s the repository holds now: its session's and its vault put aside's. */
    private fun openedVaultsOf(repo: DefaultVaultRepository): List<OpenedVault> =
        reachableFrom(repo).filterIsInstance<OpenedVault>().distinct()

    /** The VK array inside [opened] (internal to `:core`, read here by reflection). */
    private fun vkOf(opened: OpenedVault): ByteArray {
        val field = OpenedVault::class.java.getDeclaredField("vk")
        field.isAccessible = true
        return field.get(opened) as ByteArray
    }

    /**
     * Every object reachable from [root] through fields. Classes of the JDK are not opened by
     * reflection (their modules do not allow it); their maps, collections and arrays are followed
     * through their public interfaces, and the rest of them end the walk. Threads, class loaders,
     * classes and the test library's dispatchers end it too: they hold nothing of the repository's.
     */
    private fun reachableFrom(root: Any): List<Any> {
        val seen = IdentityHashMap<Any, Boolean>()
        val queue = ArrayDeque<Any>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val next = queue.removeFirst()
            if (seen.put(next, true) != null) continue
            val type = next.javaClass
            val name = type.name
            when {
                next is Class<*> || next is Thread || next is ClassLoader -> Unit
                name.startsWith("kotlinx.coroutines.test.") -> Unit
                type.isArray -> if (!type.componentType.isPrimitive) (next as Array<*>).filterNotNull().forEach(queue::add)
                isJdk(name) -> when (next) {
                    is Map<*, *> -> next.entries.toList().forEach { (k, v) ->
                        k?.let(queue::add)
                        v?.let(queue::add)
                    }
                    is Collection<*> -> next.toList().filterNotNull().forEach(queue::add)
                    is java.util.concurrent.atomic.AtomicReference<*> -> next.get()?.let(queue::add)
                    else -> Unit
                }
                else -> {
                    var c: Class<*>? = type
                    while (c != null && !isJdk(c.name)) {
                        for (field in c.declaredFields) {
                            if (Modifier.isStatic(field.modifiers) || field.type.isPrimitive) continue
                            field.isAccessible = true
                            field.get(next)?.let(queue::add)
                        }
                        c = c.superclass
                    }
                }
            }
        }
        return seen.keys.toList()
    }

    private fun isJdk(name: String) = name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("jdk.") || name.startsWith("sun.")
}
