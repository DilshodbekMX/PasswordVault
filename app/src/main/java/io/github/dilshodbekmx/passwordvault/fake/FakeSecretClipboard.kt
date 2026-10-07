package io.github.dilshodbekmx.passwordvault.fake

import io.github.dilshodbekmx.passwordvault.clipboard.ClipStatus
import io.github.dilshodbekmx.passwordvault.clipboard.SecretClipboard
import io.github.dilshodbekmx.passwordvault.i18n.UiText
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * A [SecretClipboard] that touches no clipboard (architecture.md §2.2 `fake`): it records each copy
 * in [copies] and shows the status row as the real one would, until [clearNow]. Nothing is cleared
 * by time; a test sets [status] to [ClipStatus.cleared] or null itself. Single-threaded.
 *
 * @param elapsedMs the `elapsedRealtime()` from which [ClipStatus.clearsAtElapsedMs] is counted.
 */
class FakeSecretClipboard(private val elapsedMs: () -> Long = { 0L }) : SecretClipboard {

    /** One copy: what was copied, as what, and with which flag. [toString] never shows [value]. */
    class Copy(val value: String, val what: UiText, val sensitive: Boolean) {
        override fun toString(): String = "Copy(what=$what, sensitive=$sensitive)"
    }

    override val status = MutableStateFlow<ClipStatus?>(null)

    /** Every copy, in order. */
    val copies = mutableListOf<Copy>()

    /** The delay the status row counts down. */
    var delayMs: Long = 60_000L

    /** How many times [clearNow] and [appCameToFront] were called. */
    var clears: Int = 0
        private set
    var cameToFront: Int = 0
        private set

    override fun copy(value: CharSequence, what: UiText, sensitive: Boolean) {
        copies += Copy(value.toString(), what, sensitive)
        status.value = ClipStatus(what, sensitive, elapsedMs() + delayMs, delayMs)
    }

    override fun clearNow() {
        clears++
        status.value = null
    }

    override fun appCameToFront() {
        cameToFront++
    }
}
