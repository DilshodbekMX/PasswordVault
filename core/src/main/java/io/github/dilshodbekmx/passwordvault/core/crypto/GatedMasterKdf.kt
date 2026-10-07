package io.github.dilshodbekmx.passwordvault.core.crypto

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * [MasterKdf] (architecture.md §3.2, §5.2): Argon2id with [Argon2Params.V1] over [argon2], one
 * derivation at a time in the whole process (lock.md §6.8 step 3), each run on [dispatcher].
 * `CryptoFactory.masterKdf` makes it; `:app` passes the `GuardedArgon2id` and `Dispatchers.Default`.
 *
 * - **The KDF gate** is one fair `Mutex` for the process, shared by every instance: a derivation
 *   needs 64 MiB, and two at once ran out of memory on the Bouncy Castle fallback even with
 *   `largeHeap` [L-old §2]. Callers derive in the order they came. It is the innermost lock (the
 *   lock mutex, then the vault mutex, then this gate; architecture.md §5.2), and nothing else is
 *   taken while it is held.
 * - **Closed until Argon2id returns.** A derivation that has started cannot be stopped. When its
 *   caller is cancelled meanwhile, the gate stays closed until the derivation has ended, the key it
 *   gave is zeroed, and the caller gets the `CancellationException`. A caller cancelled while it
 *   waits derives nothing.
 * - The salt must have 16 bytes, the size of every salt of version 1 (architecture.md §4.6); another
 *   size is a programming error (`IllegalArgumentException`) and derives nothing. The password bytes
 *   are passed on as they are: the caller made them with `MasterPasswordBytes.of` and zeroes them when
 *   [kek] has returned, which is after the derivation has ended.
 * - An `OutOfMemoryError` becomes `KdfUnavailable` here too, whatever [argon2] is; a `KdfUnavailable`
 *   passes unchanged. A key of another size than 32 bytes is zeroed and refused
 *   (`IllegalStateException`): it never reaches a slot.
 */
internal class GatedMasterKdf(
    private val argon2: Argon2id,
    private val dispatcher: CoroutineDispatcher,
) : MasterKdf {

    override suspend fun kek(passwordBytes: ByteArray, salt: ByteArray): ByteArray {
        require(salt.size == SALT_BYTES) { "the salt of a password slot has 16 bytes" }
        return GATE.withLock {
            var made: ByteArray? = null
            try {
                withContext(dispatcher) { derive(passwordBytes, salt).also { made = it } }
            } catch (e: CancellationException) {
                made?.fill(0) // the caller left while Argon2id ran: nobody receives this key
                throw e
            }
        }
    }

    private fun derive(passwordBytes: ByteArray, salt: ByteArray): ByteArray {
        val key = try {
            argon2.derive(passwordBytes, salt, Argon2Params.V1)
        } catch (e: OutOfMemoryError) {
            throw KdfUnavailable(e)
        }
        if (key.size != Argon2Params.V1.outLen) {
            key.fill(0)
            throw IllegalStateException("Argon2id gave a key of the wrong size")
        }
        return key
    }

    private companion object {
        const val SALT_BYTES = 16

        /** The KDF gate of architecture.md §5.2: one for the whole process. */
        val GATE = Mutex()
    }
}
