package io.github.dilshodbekmx.passwordvault.core.lock.flow

/**
 * The proof that the master password was typed right in this flow (lock.md §5.8, §8.2;
 * architecture.md §3.4): the verified master-password bytes and the vault they opened. It is not a
 * time window. Made only by `LockEngine`, after a correct master password; handed to the next step
 * of the same flow (set a PIN, turn fingerprint on, make a backup, check the matches of D-9b); and
 * dropped when that flow ends, at the process-level `ON_STOP` and on lock.
 *
 * The constructor takes its own copy of [bytes]; the caller wipes its array. [useBytes] lends a
 * fresh copy that is wiped when [use] returns, so a [drop] from another thread (an `ON_STOP` while
 * a backup is being sealed) never changes the bytes a running step is reading.
 */
class MasterProof internal constructor(bytes: ByteArray, val vaultId: String) {
    private val lock = Any()
    private var bytes: ByteArray? = bytes.copyOf()

    /**
     * Runs [use] with a copy of the verified bytes and wipes the copy afterwards.
     *
     * @throws IllegalStateException when the proof was dropped.
     */
    fun <T> useBytes(use: (ByteArray) -> T): T {
        val copy = synchronized(lock) { bytes?.copyOf() } ?: throw IllegalStateException("the master-password proof was dropped")
        try {
            return use(copy)
        } finally {
            copy.fill(0)
        }
    }

    /** Zeroes the bytes; every later [useBytes] throws. Calling it again does nothing. */
    fun drop() {
        synchronized(lock) {
            bytes?.fill(0)
            bytes = null
        }
    }

    override fun toString(): String = "MasterProof(vaultId=$vaultId)"
}
