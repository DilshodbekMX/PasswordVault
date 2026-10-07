package io.github.dilshodbekmx.passwordvault.platform

import io.github.dilshodbekmx.passwordvault.core.util.RandomSource
import io.github.dilshodbekmx.passwordvault.core.util.Uuid
import java.security.SecureRandom

/**
 * Every random value of the app on a phone (architecture.md §3.1; lock.md §4.2): keys, salts,
 * nonces, ids and generated passwords. One `SecureRandom()` with no arguments, which is the
 * platform's OpenSSL generator ("SHA1PRNG" from AndroidOpenSSL on API 24 and 28 [L-old §6]);
 * `SecureRandom.getInstanceStrong()` does not exist on API 24 [L-old §7]. `SecureRandom` is safe
 * to share between threads, so `PlatformModule` makes one for the process.
 */
class SystemRandom : RandomSource {
    private val random = SecureRandom()

    override fun bytes(n: Int): ByteArray {
        require(n >= 0) { "negative length" }
        val out = ByteArray(n)
        if (n > 0) random.nextBytes(out)
        return out
    }

    /**
     * `nextInt(bound)`, which draws again whenever a value would make the result uneven: unbiased,
     * never a random number `% bound` (crypto.md §9).
     */
    override fun int(bound: Int): Int {
        require(bound > 0) { "bound must be positive" }
        return random.nextInt(bound)
    }

    /** A version 4 UUID in lower case from 16 random bytes ([Uuid.make]); the bytes are zeroed after. */
    override fun uuid(): String {
        val random16 = bytes(16)
        try {
            return Uuid.make(random16)
        } finally {
            random16.fill(0)
        }
    }
}
