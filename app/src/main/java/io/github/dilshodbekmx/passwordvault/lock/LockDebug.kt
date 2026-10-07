package io.github.dilshodbekmx.passwordvault.lock

/**
 * The lock's test hooks (lock.md §15.3), behind the debug commands of the `debug` and `staging`
 * source sets (`debug/lock/`, build plan 4.1). `LockDebugImpl` (4.1) implements them over the real
 * lock; release builds contain no command that reaches them (build check R-2). No method returns
 * or logs a secret: no digit, password, key or entry text.
 */
interface LockDebug {
    /** `status`: the inputs and the result of lock.md §13.1, the counts, the aliases present. */
    suspend fun status(): String

    /** `hold-after-charge`: the next PIN check stops after its charge is durable (checks E2, E3). */
    fun holdAfterCharge()

    /** `hold-before-hmac`: the next PIN check stops just before the Keystore HMAC (checks E2, E13). */
    fun holdBeforeHmac()

    /** `release`: lets a held PIN check go on. */
    fun release()

    /** `fail-next-write`: the next lock record write fails (check E14). */
    fun failNextWrite()

    /** `ks-fault <row>`: the next Keystore call fails as row [row] of lock.md §14.3 (check E10). */
    fun ksFault(row: String)

    /** `drop-keys`: deletes every alias of the app, the canary included (check E10). */
    suspend fun dropKeys()
}
