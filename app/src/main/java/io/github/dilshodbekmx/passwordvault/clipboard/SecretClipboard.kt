package io.github.dilshodbekmx.passwordvault.clipboard

import io.github.dilshodbekmx.passwordvault.i18n.UiText
import kotlinx.coroutines.flow.StateFlow

/**
 * The one helper every in-app copy goes through (ux.md §6, architecture.md D-14, D-25):
 * `AndroidSecretClipboard` (build plan 3.8). The clip carries the sensitive flag where it applies and
 * a random token in its extras; a clear removes only a clip of ours, found by its token, and never
 * reads the clipboard's content.
 */
interface SecretClipboard {
    /** The clipboard status row (ux.md §6); null when nothing of ours is pending. */
    val status: StateFlow<ClipStatus?>

    /**
     * Copies [value], described as [what] ("Password for Gmail", "Copied text"), with the sensitive
     * flag when [sensitive] (passwords, previous passwords, secret field values, text from notes),
     * and schedules its clear after the chosen delay. Main thread.
     */
    fun copy(value: CharSequence, what: UiText, sensitive: Boolean)

    /** "Clear now" and "Lock now": clears a clip of ours at once. Never called by an automatic lock (D-9c). */
    fun clearNow()

    /**
     * The activity came to the front (`onResume`): cancels the pending clear if the clipboard now
     * holds something else, and clears an overdue clip of ours (D-14, after a force-stop).
     */
    fun appCameToFront()
}
