package io.github.dilshodbekmx.passwordvault.testing

import io.github.dilshodbekmx.passwordvault.core.util.RandomSource
import io.github.dilshodbekmx.passwordvault.core.util.Uuid
import java.util.Random

/**
 * A [RandomSource] that gives the tests fixed values (architecture.md §1 item 10).
 *
 * Values queued with [queueBytes], [queueInts] and [queueUuids] come first, in order, each from its
 * own queue; when a queue is empty the values come from a `java.util.Random` seeded with [seed], so
 * a run is the same every time. [bytes] checks that a queued array has the length asked for, so a
 * test that queues a key, a salt and a nonce fails loudly when the code asks in another order.
 * [byteRequests] keeps the length of every [bytes] call.
 */
class FakeRandom(seed: Long = 1L) : RandomSource {
    private val stream = Random(seed)
    private val queuedBytes = ArrayDeque<ByteArray>()
    private val queuedInts = ArrayDeque<Int>()
    private val queuedUuids = ArrayDeque<String>()
    private val requests = mutableListOf<Int>()

    /** The `n` of every [bytes] call so far, in order. */
    val byteRequests: List<Int> get() = synchronized(this) { requests.toList() }

    /** The next [bytes] calls return copies of [arrays], in order. */
    fun queueBytes(vararg arrays: ByteArray): FakeRandom = apply {
        synchronized(this) { arrays.forEach { queuedBytes.addLast(it.copyOf()) } }
    }

    /** The next [int] calls return [values], in order; each must lie below the bound asked for. */
    fun queueInts(vararg values: Int): FakeRandom = apply {
        synchronized(this) { values.forEach { queuedInts.addLast(it) } }
    }

    /** The next [uuid] calls return [values], in order; each must be a valid id. */
    fun queueUuids(vararg values: String): FakeRandom = apply {
        values.forEach { require(Uuid.isValid(it)) { "not a lower-case UUID of version 4" } }
        synchronized(this) { values.forEach { queuedUuids.addLast(it) } }
    }

    override fun bytes(n: Int): ByteArray {
        require(n >= 0) { "negative length" }
        synchronized(this) {
            requests += n
            val next = queuedBytes.removeFirstOrNull() ?: return ByteArray(n).also { stream.nextBytes(it) }
            check(next.size == n) { "queued ${next.size} bytes, but $n were asked for" }
            return next
        }
    }

    override fun int(bound: Int): Int {
        require(bound > 0) { "bound must be positive" }
        synchronized(this) {
            val next = queuedInts.removeFirstOrNull() ?: return stream.nextInt(bound)
            check(next in 0 until bound) { "queued $next, but the bound is $bound" }
            return next
        }
    }

    override fun uuid(): String = synchronized(this) {
        queuedUuids.removeFirstOrNull() ?: Uuid.make(ByteArray(16).also { stream.nextBytes(it) })
    }

    companion object {
        /** [size] bytes counting up from [first] (mod 256): `sequence(0xa0, 32)` is VK `a0 a1 … bf` of the vectors. */
        fun sequence(first: Int, size: Int): ByteArray = ByteArray(size) { (first + it).toByte() }
    }
}
