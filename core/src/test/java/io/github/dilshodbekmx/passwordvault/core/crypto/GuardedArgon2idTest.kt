package io.github.dilshodbekmx.passwordvault.core.crypto

import io.github.dilshodbekmx.passwordvault.core.util.Bytes
import io.github.dilshodbekmx.passwordvault.testing.FakeRandom
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

/**
 * `GuardedArgon2id` through [CryptoFactory.guardedArgon2id] (architecture.md §3.2, §8.1; [V-C2]):
 * the native implementation when there is one; without it the fallback, but only with enough free
 * heap, asked again after a garbage collection when it looks too small (build-plan.md §12, the
 * owner's decision 22), else `KdfUnavailable` with nothing called; an `OutOfMemoryError` of the
 * fallback tried once more after a collection, and one that stays as `KdfUnavailable`. The
 * implementations are recording fakes, except in the last test.
 */
class GuardedArgon2idTest {

    /** An [Argon2id] that records each call and then does [act]. */
    private class Recording(private val name: String, private val act: () -> Unit = {}) : Argon2id {
        val passwords = mutableListOf<ByteArray>()
        val salts = mutableListOf<ByteArray>()
        val params = mutableListOf<Argon2Params>()
        val calls get() = passwords.size

        override fun derive(password: ByteArray, salt: ByteArray, params: Argon2Params): ByteArray {
            passwords += password
            salts += salt
            this.params += params
            act()
            return name.toByteArray(Charsets.US_ASCII)
        }
    }

    /**
     * The free heap the guard is told, and how often it asked. [garbage] counts as used until a
     * collection ([collect]) frees it, as the work area of a derivation that has returned does in ART.
     */
    private class Heap(var free: Long, var garbage: Long = 0) {
        var asked = 0
        var collections = 0
        fun read(): Long {
            asked++
            return free - garbage
        }

        fun collect() {
            collections++
            garbage = 0
        }
    }

    private val password = "pass".toByteArray(Charsets.US_ASCII)
    private val salt = FakeRandom.sequence(0, 16)
    private val needs = 96L * 1024 * 1024

    private fun guard(native: Argon2id?, fallback: Argon2id, heap: Heap) =
        CryptoFactory.guardedArgon2id(native, fallback, heap::read, needs, heap::collect)

    private fun Argon2id.derive() = String(derive(password, salt, Argon2Params.V1), Charsets.US_ASCII)

    private fun unavailable(guard: Argon2id): KdfUnavailable {
        try {
            guard.derive()
        } catch (e: KdfUnavailable) {
            return e
        }
        fail("KdfUnavailable was not thrown")
        throw AssertionError()
    }

    // --- with a native implementation ---

    @Test
    fun withANativeImplementation_onlyItDerives_andTheHeapIsNotAsked() {
        val native = Recording("native")
        val fallback = Recording("fallback")
        val heap = Heap(0)
        assertEquals("native", guard(native, fallback, heap).derive())
        assertEquals(1, native.calls)
        assertEquals(0, fallback.calls)
        assertEquals(0, heap.asked)
        assertEquals(0, heap.collections)
    }

    @Test
    fun theCallersArraysAndParametersArePassedAsTheyAre() {
        val native = Recording("native")
        val fallback = Recording("fallback")
        val params = Argon2Params(1024, 1, 2, 16)
        guard(native, fallback, Heap(needs)).derive(password, salt, params)
        guard(null, fallback, Heap(needs)).derive(password, salt, params)
        for (recording in listOf(native, fallback)) {
            assertSame(password, recording.passwords.single())
            assertSame(salt, recording.salts.single())
            assertSame(params, recording.params.single())
        }
        assertArrayEquals("pass".toByteArray(Charsets.US_ASCII), password)
    }

    @Test
    fun aKdfUnavailableOfTheNativeImplementationPassesUnchanged_andTheFallbackIsNotTried() {
        val thrown = KdfUnavailable(null)
        val fallback = Recording("fallback")
        val caught = unavailable(guard(Recording("native") { throw thrown }, fallback, Heap(Long.MAX_VALUE)))
        assertSame(thrown, caught)
        assertEquals(0, fallback.calls)
    }

    @Test
    fun anOutOfMemoryErrorOfTheNativeImplementation_isKdfUnavailable_andTheFallbackIsNotTried() {
        val error = OutOfMemoryError("Direct buffer memory")
        val fallback = Recording("fallback")
        val heap = Heap(Long.MAX_VALUE)
        val caught = unavailable(guard(Recording("native") { throw error }, fallback, heap))
        assertSame(error, caught.cause)
        assertEquals(0, fallback.calls)
        assertEquals("its work area is not on the Java heap", 0, heap.collections)
    }

    @Test
    fun aLinkageErrorAtTheFirstNativeCall_meansNoNativeImplementation_fromThenOn() {
        val native = Recording("native") { throw UnsatisfiedLinkError("No implementation found") }
        val fallback = Recording("fallback")
        val guard = guard(native, fallback, Heap(needs))
        assertEquals("fallback", guard.derive())
        assertEquals("fallback", guard.derive())
        assertEquals(1, native.calls)
        assertEquals(2, fallback.calls)
    }

    @Test
    fun aLinkageErrorAtTheFirstNativeCall_withTooLittleHeap_isKdfUnavailable() {
        val fallback = Recording("fallback")
        val heap = Heap(needs - 1)
        val guard = guard(Recording("native") { throw NoClassDefFoundError("argon2kt") }, fallback, heap)
        unavailable(guard)
        assertEquals(0, fallback.calls)
        heap.free = needs
        assertEquals("fallback", guard.derive())
    }

    @Test
    fun otherExceptionsOfTheNativeImplementationPassUnchanged_andItIsTriedAgainNextTime() {
        val thrown = IllegalStateException("native")
        var fail = true
        val native = Recording("native") { if (fail) throw thrown }
        val fallback = Recording("fallback")
        val guard = guard(native, fallback, Heap(Long.MAX_VALUE))
        try {
            guard.derive()
            fail("the exception was swallowed")
        } catch (e: IllegalStateException) {
            assertSame(thrown, e)
        }
        fail = false
        assertEquals("native", guard.derive())
        assertEquals(0, fallback.calls)
    }

    // --- without a native implementation ---

    @Test
    fun withoutANativeImplementation_andEnoughHeap_theFallbackDerives_withoutACollection() {
        val fallback = Recording("fallback")
        val heap = Heap(needs + 1)
        assertEquals("fallback", guard(null, fallback, heap).derive())
        assertEquals(1, fallback.calls)
        assertEquals(1, heap.asked)
        assertEquals(0, heap.collections)
    }

    @Test
    fun exactlyTheHeapTheFallbackNeedsIsEnough() {
        val fallback = Recording("fallback")
        assertEquals("fallback", guard(null, fallback, Heap(needs)).derive())
    }

    @Test
    fun withTooLittleFreeHeapEvenAfterACollection_itIsKdfUnavailable_andNothingIsCalled() {
        val fallback = Recording("fallback")
        val heap = Heap(needs - 1)
        val caught = unavailable(guard(null, fallback, heap))
        assertNull(caught.cause)
        assertEquals(0, fallback.calls)
        assertEquals("asked, then asked again after the collection", 2, heap.asked)
        assertEquals(1, heap.collections)
    }

    /**
     * lock.md E16 on API 24 (heap 256 MiB, argon2kt missing): setup derives twice, one after the
     * other (the vault is made, then opened from disk), and the first derivation's work area, about
     * 134 MiB on Android 7, is garbage that counts as used until a collection. Without one the second
     * derivation was refused: "Your phone doesn't have enough free memory to finish setup." (4 runs
     * of 4 on 2026-10-09 and once more on 2026-10-13).
     */
    @Test
    fun theWorkAreaOfTheDerivationBefore_isCollected_beforeTheHeapIsCalledTooSmall() {
        val mib = 1024L * 1024
        val heap = Heap(free = 245 * mib)
        val fallback = Recording("fallback") { heap.garbage += 134 * mib }
        val guard = CryptoFactory.guardedArgon2id(null, fallback, heap::read, 160 * mib, heap::collect)
        assertEquals("fallback", guard.derive())
        assertEquals("no collection while the heap is large enough", 0, heap.collections)
        assertEquals("the second derivation at once", "fallback", guard.derive())
        assertEquals(1, heap.collections)
        assertEquals(2, fallback.calls)
    }

    @Test
    fun theHeapIsAskedAgainForEveryDerivation() {
        val fallback = Recording("fallback")
        val heap = Heap(needs)
        val guard = guard(null, fallback, heap)
        assertEquals("fallback", guard.derive())
        heap.free = needs - 1
        unavailable(guard)
        heap.free = needs
        assertEquals("fallback", guard.derive())
        assertEquals(4, heap.asked)
        assertEquals(2, fallback.calls)
    }

    @Test
    fun anOutOfMemoryErrorOfTheFallbackThatStays_isKdfUnavailable_withTheFirstErrorAsItsCause() {
        val errors = ArrayDeque(listOf(OutOfMemoryError("Failed to allocate a 1040 byte allocation"), OutOfMemoryError("again")))
        val first = errors.first()
        val heap = Heap(Long.MAX_VALUE)
        val fallback = Recording("fallback") { errors.removeFirstOrNull()?.let { throw it } }
        val guard = guard(null, fallback, heap)
        assertSame(first, unavailable(guard).cause)
        assertEquals("tried once more after a collection", 2, fallback.calls)
        assertEquals(1, heap.collections)
        assertEquals("the next derivation runs", "fallback", guard.derive())
    }

    @Test
    fun anOutOfMemoryErrorOfTheFallback_isTriedOnceMoreAfterACollection() {
        val error = OutOfMemoryError("Failed to allocate a 1040 byte allocation")
        var fail = true
        val heap = Heap(Long.MAX_VALUE)
        val fallback = Recording("fallback") {
            if (fail) {
                fail = false
                throw error
            }
        }
        assertEquals("fallback", guard(null, fallback, heap).derive())
        assertEquals(2, fallback.calls)
        assertEquals(1, heap.collections)
    }

    @Test
    fun anOutOfMemoryErrorOfTheFallback_withTooLittleHeapAfterTheCollection_isKdfUnavailable_withoutASecondTry() {
        val error = OutOfMemoryError("Failed to allocate a 1040 byte allocation")
        val heap = Heap(needs)
        val fallback = Recording("fallback") {
            heap.free = needs - 1 // something else holds the heap now
            throw error
        }
        assertSame(error, unavailable(guard(null, fallback, heap)).cause)
        assertEquals(1, fallback.calls)
        assertEquals(1, heap.collections)
    }

    @Test
    fun otherExceptionsOfTheFallbackPassUnchanged() {
        val thrown = IllegalArgumentException("fallback")
        try {
            guard(null, Recording("fallback") { throw thrown }, Heap(Long.MAX_VALUE)).derive()
            fail("the exception was swallowed")
        } catch (e: IllegalArgumentException) {
            assertSame(thrown, e)
        }
    }

    @Test
    fun aNegativeNeedIsRefused() {
        try {
            CryptoFactory.guardedArgon2id(null, Recording("fallback"), { 0L }, -1, {})
            fail("a negative need was accepted")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    // --- with the real fallback ---

    @Test
    fun theBouncyCastleFallbackGivesTheVaultVector() {
        val guard = CryptoFactory.guardedArgon2id(null, CryptoFactory.bouncyCastleArgon2id(), { Long.MAX_VALUE }, needs, {})
        val key = guard.derive("correct horse battery staple".toByteArray(Charsets.UTF_8), FakeRandom.sequence(0, 16), Argon2Params.V1)
        assertEquals("853b272a44db1421c02962669a55eb0994f3cab385ed1c4c79253eee19bab49e", Bytes.hex(key))
    }
}
