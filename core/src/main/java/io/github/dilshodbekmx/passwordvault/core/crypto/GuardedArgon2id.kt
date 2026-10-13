package io.github.dilshodbekmx.passwordvault.core.crypto

/**
 * The [Argon2id] the app derives with (architecture.md §3.2, [V-C2]): argon2kt when it is there,
 * otherwise Bouncy Castle, but only when the Java heap can hold it. `CryptoFactory.guardedArgon2id`
 * makes it. `:app` passes its `NativeArgon2id`, or null when loading argon2kt threw any `Throwable`;
 * as [freeHeap], `maxMemory − totalMemory + freeMemory`; as [fallbackNeeds], 160 MiB below API 28
 * and 96 MiB from API 28 (about 134 and 70 MiB were measured [L-old §2]); and as [collect],
 * `Runtime.gc()`.
 *
 * A derivation, in order:
 * 1. With a [native] implementation it derives with that alone. Its work area is native memory,
 *    outside the Java heap [L-old §2], so the heap is not asked. A `KdfUnavailable` from it is passed
 *    on: the fallback would need more of this phone's memory, not less.
 * 2. A `LinkageError` from [native] (an `UnsatisfiedLinkError` that came at its first call rather than
 *    at loading) means that it is not there after all [V-C2]: it is not called again in this process,
 *    and the derivation goes on as in step 3. Such an error comes before any native code runs.
 * 3. Without a native implementation, [freeHeap] must be at least [fallbackNeeds]. When it is less,
 *    [collect] runs and [freeHeap] is read once more: the work area of the derivation before (about
 *    134 MiB on Android 7) is garbage once that derivation has returned, but it counts as used until
 *    a collection has run, so a second derivation soon after the first (setup makes the vault and
 *    then opens it from disk, lock.md F1 step 3) would otherwise be refused on a 256 MiB heap
 *    (lock.md E16; build-plan.md §12, the owner's decision 22). Still less: `KdfUnavailable`, and the
 *    fallback is not called. Then [fallback] derives.
 * 4. An `OutOfMemoryError` from [fallback]: [collect] runs, and when [freeHeap] is then at least
 *    [fallbackNeeds] the fallback derives once more. An `OutOfMemoryError` from that try, or from
 *    [native], or too little heap after the collection, becomes `KdfUnavailable` with the first error
 *    as its cause: "not enough memory", never a wrong password, never counted (lock.md §6.8).
 *
 * The parameters are never touched: a derivation that does not fit is refused, never made cheaper
 * (the vault file holds its parameters, lock.md §4.2). Every other exception passes unchanged. It
 * never returns a wrong key: both implementations give the same bytes (package 2.7 checks it on the
 * devices), and nothing here touches their inputs or output.
 */
internal class GuardedArgon2id(
    native: Argon2id?,
    private val fallback: Argon2id,
    private val freeHeap: () -> Long,
    private val fallbackNeeds: Long,
    private val collect: () -> Unit,
) : Argon2id {

    /** [native] until it has thrown a `LinkageError`; null from then on. */
    @Volatile
    private var native: Argon2id? = native

    init {
        require(fallbackNeeds >= 0) { "the heap the fallback needs is not negative" }
    }

    override fun derive(password: ByteArray, salt: ByteArray, params: Argon2Params): ByteArray {
        val nativeNow = native
        if (nativeNow != null) {
            try {
                return nativeNow.derive(password, salt, params)
            } catch (e: LinkageError) {
                native = null
            } catch (e: OutOfMemoryError) {
                throw KdfUnavailable(e)
            }
        }
        if (!fits()) throw KdfUnavailable(null)
        try {
            return fallback.derive(password, salt, params)
        } catch (first: OutOfMemoryError) {
            collect()
            if (freeHeap() < fallbackNeeds) throw KdfUnavailable(first)
            try {
                return fallback.derive(password, salt, params)
            } catch (again: OutOfMemoryError) {
                throw KdfUnavailable(first)
            }
        }
    }

    /** Whether [freeHeap] is at least [fallbackNeeds], asked again after [collect] when it is not. */
    private fun fits(): Boolean {
        if (freeHeap() >= fallbackNeeds) return true
        collect()
        return freeHeap() >= fallbackNeeds
    }
}
