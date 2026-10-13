package io.github.dilshodbekmx.passwordvault.fake

import io.github.dilshodbekmx.passwordvault.clipboard.SecretClipboard
import io.github.dilshodbekmx.passwordvault.data.VaultRepository
import io.github.dilshodbekmx.passwordvault.lock.LockManager
import io.github.dilshodbekmx.passwordvault.lock.LockReason
import io.github.dilshodbekmx.passwordvault.session.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * A [Session] with no automatic lock (architecture.md §2.2 `fake`): it counts touches and external
 * screens, and its [lockNow] does what "Lock now" does, in the order of architecture.md §5.5: clears
 * our clip, flushes the draft in [scope], then locks.
 */
class FakeSession(
    private val scope: CoroutineScope,
    private val lock: LockManager,
    private val clipboard: SecretClipboard,
    private val repository: VaultRepository,
) : Session {
    var touches: Long = 0
        private set

    /** An external screen (file picker, share sheet) is open. */
    var external: Boolean = false
        private set

    var lockNows: Int = 0
        private set

    override fun beginExternal() {
        external = true
    }

    override fun endExternal() {
        external = false
    }

    override fun touched() {
        touches++
    }

    override fun lockNow() {
        lockNows++
        clipboard.clearNow()
        scope.launch {
            repository.flushDraft()
            lock.lock(LockReason.LOCK_NOW)
        }
    }
}
