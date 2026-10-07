package io.github.dilshodbekmx.passwordvault.core.crypto

import io.github.dilshodbekmx.passwordvault.testing.FakeRandom
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * `GatedMasterKdf` through [CryptoFactory.masterKdf] (architecture.md §3.2, §5.2; lock.md §6.8 step
 * 3): never two derivations at once in the process, also across instances; each on the dispatcher
 * it was given, with [Argon2Params.V1] and the caller's own arrays; the gate stays closed while a
 * cancelled caller's derivation runs, and opens again after every failure. The Argon2id here is a
 * fake that takes a few milliseconds and counts how many derivations run at the same time; the real
 * one runs in `DerivedKeysTest`.
 */
class GatedMasterKdfTest {

    /** The callers' threads, and the derivations' own: four, so that a gate that let two through would show it. */
    private val callers: ExecutorCoroutineDispatcher = Executors.newFixedThreadPool(8).asCoroutineDispatcher()
    private val kdfThreads: ExecutorCoroutineDispatcher =
        Executors.newFixedThreadPool(4) { task -> Thread(task, "pv-kdf-test") }.asCoroutineDispatcher()

    @After
    fun stopThreads() {
        callers.close()
        kdfThreads.close()
    }

    private val salt = FakeRandom.sequence(0, 16)

    private fun password(n: Int) = "password $n".toByteArray(Charsets.US_ASCII)

    /** The fake's key: SHA-256 of the password and the salt, 32 bytes like a KEK. */
    private fun keyOf(password: ByteArray, salt: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(password + salt)

    /** Counts the derivations of every [Argon2id] it makes, and how many ran at the same time. */
    private class Overlap {
        val running = AtomicInteger()
        val most = AtomicInteger()
        val done = AtomicInteger()

        fun enter() = most.accumulateAndGet(running.incrementAndGet(), ::maxOf)

        fun leave() {
            done.incrementAndGet()
            running.decrementAndGet()
        }
    }

    private fun waitFor(what: String, condition: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (!condition()) {
            if (System.nanoTime() > until) throw AssertionError("timed out waiting for $what")
            Thread.sleep(1)
        }
    }

    private fun kdf(argon2: Argon2id, dispatcher: CoroutineDispatcher = kdfThreads) = CryptoFactory.masterKdf(argon2, dispatcher)

    /**
     * Whether [original] is [caught] or among its causes. In the tests kotlinx.coroutines runs in its
     * debug mode (assertions are on), where an exception that crosses from one coroutine to another
     * arrives as a copy of the same class with the original as its cause (stack-trace recovery).
     */
    private fun isOrWraps(caught: Throwable, original: Throwable): Boolean =
        generateSequence(caught) { it.cause }.take(4).any { it === original }

    // --- one at a time ---

    /**
     * 16 callers on 8 threads. The first derivation waits until every caller has asked, so that all
     * the others are queued at the gate while it runs.
     */
    @Test
    fun neverTwoDerivationsAtOnce() = runBlocking {
        val overlap = Overlap()
        val asked = AtomicInteger()
        val argon2 = Argon2id { password, salt, _ ->
            overlap.enter()
            try {
                if (overlap.done.get() == 0) waitFor("every caller to ask") { asked.get() == 16 }
                Thread.sleep(2)
                keyOf(password, salt)
            } finally {
                overlap.leave()
            }
        }
        val kdf = kdf(argon2)
        val keys = (1..16).map { n ->
            async(callers) {
                asked.incrementAndGet()
                kdf.kek(password(n), salt)
            }
        }.awaitAll()
        keys.forEachIndexed { i, key -> assertArrayEquals(keyOf(password(i + 1), salt), key) }
        assertEquals(16, overlap.done.get())
        assertEquals("derivations at the same time", 1, overlap.most.get())
    }

    /** The control of the test above: the same callers without the gate run derivations side by side. */
    @Test
    fun withoutTheGate_theSameCallersWouldOverlap() = runBlocking {
        val overlap = Overlap()
        val argon2 = Argon2id { password, salt, _ ->
            overlap.enter()
            try {
                waitFor("a second derivation") { overlap.most.get() >= 2 }
                keyOf(password, salt)
            } finally {
                overlap.leave()
            }
        }
        (1..16).map { n -> async(callers) { argon2.derive(password(n), salt, Argon2Params.V1) } }.awaitAll()
        assertTrue(overlap.most.get() >= 2)
    }

    @Test
    fun theGateIsOneForTheWholeProcess_sharedByEveryInstance() = runBlocking {
        val overlap = Overlap()
        fun counting() = Argon2id { password, salt, _ ->
            overlap.enter()
            try {
                Thread.sleep(5)
                keyOf(password, salt)
            } finally {
                overlap.leave()
            }
        }
        val kdfs = listOf(kdf(counting()), kdf(counting(), callers), kdf(counting(), kdfThreads))
        (1..24).map { n -> async(callers) { kdfs[n % 3].kek(password(n), salt) } }.awaitAll()
        assertEquals(24, overlap.done.get())
        assertEquals("derivations at the same time", 1, overlap.most.get())
    }

    @Test
    fun callersDeriveInTheOrderTheyCame() = runBlocking {
        val order = Collections.synchronizedList(mutableListOf<String>())
        val firstIn = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val argon2 = Argon2id { password, salt, _ ->
            val name = String(password, Charsets.US_ASCII)
            order += name
            if (name == "password 1") {
                firstIn.countDown()
                check(releaseFirst.await(20, TimeUnit.SECONDS))
            }
            keyOf(password, salt)
        }
        val kdf = kdf(argon2)
        // Started undispatched, each caller reaches the gate before the next one is started.
        val keys = (1..6).map { n -> async(start = CoroutineStart.UNDISPATCHED) { kdf.kek(password(n), salt) } }
        assertTrue(firstIn.await(20, TimeUnit.SECONDS))
        releaseFirst.countDown()
        keys.awaitAll()
        assertEquals((1..6).map { "password $it" }, order)
    }

    // --- what it derives, and where ---

    @Test
    fun itDerivesOnTheGivenDispatcher_withV1_andTheCallersOwnArrays() = runBlocking {
        val threads = mutableListOf<String>()
        val seen = mutableListOf<Any>()
        val argon2 = Argon2id { password, salt, params ->
            threads += Thread.currentThread().name
            seen.addAll(listOf(password, salt, params))
            keyOf(password, salt)
        }
        val password = password(1)
        val key = kdf(argon2).kek(password, salt)
        // In the debug mode of kotlinx.coroutines a thread's name gets " @coroutine#<n>" while it runs one.
        assertEquals(1, threads.size)
        assertTrue(threads[0], threads[0].startsWith("pv-kdf-test"))
        assertSame(password, seen[0])
        assertSame(salt, seen[1])
        assertEquals(Argon2Params.V1, seen[2])
        assertArrayEquals(keyOf(password, salt), key)
        assertArrayEquals("the caller's password is not changed", password(1), password)
    }

    @Test
    fun aSaltOfAnotherSizeIsRefused_andNothingIsDerived() = runBlocking {
        val calls = AtomicInteger()
        val kdf = kdf(Argon2id { password, salt, _ -> calls.incrementAndGet(); keyOf(password, salt) })
        for (size in listOf(0, 8, 12, 15, 17, 32)) {
            try {
                kdf.kek(password(1), ByteArray(size))
                fail("a salt of $size bytes was accepted")
            } catch (e: IllegalArgumentException) {
                // expected
            }
        }
        assertEquals(0, calls.get())
    }

    @Test
    fun aKeyOfAnotherSizeIsZeroedAndRefused() = runBlocking {
        val given = ByteArray(16) { 7.toByte() }
        try {
            kdf(Argon2id { _, _, _ -> given }).kek(password(1), salt)
            fail("a 16-byte key was returned")
        } catch (e: IllegalStateException) {
            // expected
        }
        assertArrayEquals(ByteArray(16), given)
    }

    // --- failures open the gate again ---

    @Test
    fun kdfUnavailablePassesUnchanged_andTheGateOpensAgain() = runBlocking {
        val thrown = KdfUnavailable(null)
        val calls = AtomicInteger()
        val kdf = kdf(Argon2id { password, salt, _ -> if (calls.incrementAndGet() == 1) throw thrown else keyOf(password, salt) })
        try {
            kdf.kek(password(1), salt)
            fail("KdfUnavailable was swallowed")
        } catch (e: KdfUnavailable) {
            assertTrue(isOrWraps(e, thrown))
        }
        assertArrayEquals(keyOf(password(2), salt), kdf.kek(password(2), salt))
    }

    @Test
    fun anOutOfMemoryErrorIsKdfUnavailable_andTheGateOpensAgain() = runBlocking {
        val error = OutOfMemoryError("Java heap space")
        val calls = AtomicInteger()
        val kdf = kdf(Argon2id { password, salt, _ -> if (calls.incrementAndGet() == 1) throw error else keyOf(password, salt) })
        try {
            kdf.kek(password(1), salt)
            fail("no KdfUnavailable")
        } catch (e: KdfUnavailable) {
            assertTrue(isOrWraps(e, error))
        }
        assertArrayEquals(keyOf(password(2), salt), kdf.kek(password(2), salt))
    }

    // --- cancellation ---

    @Test
    fun aCallerCancelledWhileItWaits_derivesNothing() = runBlocking {
        val calls = AtomicInteger()
        val firstIn = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val kdf = kdf(
            Argon2id { password, salt, _ ->
                if (calls.incrementAndGet() == 1) {
                    firstIn.countDown()
                    check(releaseFirst.await(20, TimeUnit.SECONDS))
                }
                keyOf(password, salt)
            },
        )
        val first = async(callers) { kdf.kek(password(1), salt) }
        assertTrue(firstIn.await(20, TimeUnit.SECONDS))
        val second = async(callers) { kdf.kek(password(2), salt) }
        delay(100) // time to reach the gate
        second.cancel()
        second.join()
        assertTrue(second.isCancelled)
        releaseFirst.countDown()
        assertArrayEquals(keyOf(password(1), salt), first.await())
        assertEquals("the cancelled caller derived nothing", 1, calls.get())
        assertArrayEquals(keyOf(password(3), salt), kdf.kek(password(3), salt))
    }

    @Test
    fun aCallerCancelledWhileItsDerivationRuns_keepsTheGateClosedUntilTheEnd_andItsKeyIsZeroed() = runBlocking {
        val overlap = Overlap()
        val made = Collections.synchronizedList(mutableListOf<ByteArray>())
        val firstIn = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val kdf = kdf(
            Argon2id { password, salt, _ ->
                overlap.enter()
                try {
                    if (made.isEmpty()) {
                        firstIn.countDown()
                        check(releaseFirst.await(20, TimeUnit.SECONDS))
                    }
                    keyOf(password, salt).also { made += it }
                } finally {
                    overlap.leave()
                }
            },
        )
        val first = launch(callers) { kdf.kek(password(1), salt) }
        assertTrue(firstIn.await(20, TimeUnit.SECONDS))
        val second = async(callers) { kdf.kek(password(2), salt) }
        first.cancel()
        delay(100)
        assertFalse("the cancelled caller waits for its derivation", first.isCompleted)
        assertEquals("the next derivation has not started", 0, overlap.done.get())
        assertEquals(1, overlap.running.get())

        releaseFirst.countDown()
        first.join()
        assertTrue(first.isCancelled)
        assertArrayEquals(keyOf(password(2), salt), second.await())
        assertEquals(2, made.size)
        assertArrayEquals("the key nobody received is zeroed", ByteArray(32), made[0])
        assertEquals("derivations at the same time", 1, overlap.most.get())
    }
}
