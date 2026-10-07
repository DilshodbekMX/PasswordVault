package io.github.dilshodbekmx.passwordvault.core.generator

import io.github.dilshodbekmx.passwordvault.core.util.RandomSource
import io.github.dilshodbekmx.passwordvault.testing.FakeRandom
import java.util.Random

/**
 * Passes [int] on to [inner] and records every bound asked for. The generator never needs [bytes]
 * or [uuid], so they fail the test: a choice made from random bytes is where a `% n` bias hides.
 */
internal class RecordingRandom(private val inner: RandomSource = FakeRandom()) : RandomSource {
    val bounds = mutableListOf<Int>()

    override fun bytes(n: Int): ByteArray = throw AssertionError("the generator asked for $n random bytes")

    override fun int(bound: Int): Int {
        bounds += bound
        return inner.int(bound)
    }

    override fun uuid(): String = throw AssertionError("the generator asked for a UUID")
}

/**
 * The mistake crypto.md §9 forbids, for the tests that show the checks find it: a random byte
 * `% bound`, which makes the first `256 % bound` values more likely than the rest.
 */
internal class ModuloRandom(seed: Long) : RandomSource {
    private val stream = Random(seed)

    override fun bytes(n: Int): ByteArray = ByteArray(n).also { stream.nextBytes(it) }

    override fun int(bound: Int): Int = stream.nextInt(256) % bound

    override fun uuid(): String = throw AssertionError("not needed")
}

/** A random source stuck at 0, like a broken one. */
internal class StuckRandom : RandomSource {
    var calls = 0
        private set

    override fun bytes(n: Int): ByteArray = ByteArray(n)

    override fun int(bound: Int): Int {
        calls++
        return 0
    }

    override fun uuid(): String = throw AssertionError("not needed")
}
