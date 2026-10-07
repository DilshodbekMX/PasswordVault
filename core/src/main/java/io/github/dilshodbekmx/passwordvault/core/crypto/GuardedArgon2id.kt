package io.github.dilshodbekmx.passwordvault.core.crypto

/**
 * The [Argon2id] the app derives with (architecture.md §3.2, [V-C2]): argon2kt when it is there,
 * otherwise Bouncy Castle, but only when the free Java heap can hold it. `CryptoFactory.guardedArgon2id`
 * makes it. `:app` passes its `NativeArgon2id`, or null when loading argon2kt threw any `Throwable`;
 * as [freeHeap], `maxMemory − totalMemory + freeMemory`; and as [fallbackNeeds], 160 MiB below API 28
 * and 96 MiB from API 28 (about 134 and 70 MiB were measured [L-old §2]).
 *
 * A derivation, in order:
 * 1. With a [native] implementation it derives with that alone. Its work area is native memory,
 *    outside the Java heap [L-old §2], so the heap is not asked. A `KdfUnavailable` from it is passed
 *    on: the fallback would need more of this phone's memory, not less.
 * 2. A `LinkageError` from [native] (an `UnsatisfiedLinkError` that came at its first call rather than
 *    at loading) means that it is not there after all [V-C2]: it is not called again in this process,
 *    and the derivation goes on as in step 3. Such an error comes before any native code runs.
 * 3. Without a native implementation, [freeHeap] must be at least [fallbackNeeds] bytes; otherwise it
 *    throws `KdfUnavailable` and calls nothing. Then [fallback] derives.
 * 4. An `OutOfMemoryError` from either becomes `KdfUnavailable`, with the error as its cause: "not
 *    enough memory", never a wrong password, never counted (lock.md §6.8).
 *
 * Every other exception passes unchanged. It never returns a wrong key: both implementations give the
 * same bytes (package 2.7 checks it on the devices), and nothing here touches their inputs or output.
 */
internal class GuardedArgon2id(
    native: Argon2id?,
    private val fallback: Argon2id,
    private val freeHeap: () -> Long,
    private val fallbackNeeds: Long,
) : Argon2id {

    /** [native] until it has thrown a `LinkageError`; null from then on. */
    @Volatile
    private var native: Argon2id? = native

    init {
        require(fallbackNeeds >= 0) { "the heap the fallback needs is not negative" }
    }

    override fun derive(password: ByteArray, salt: ByteArray, params: Argon2Params): ByteArray {
        try {
            val nativeNow = native
            if (nativeNow != null) {
                try {
                    return nativeNow.derive(password, salt, params)
                } catch (e: LinkageError) {
                    native = null
                }
            }
            if (freeHeap() < fallbackNeeds) throw KdfUnavailable(null)
            return fallback.derive(password, salt, params)
        } catch (e: OutOfMemoryError) {
            throw KdfUnavailable(e)
        }
    }
}
