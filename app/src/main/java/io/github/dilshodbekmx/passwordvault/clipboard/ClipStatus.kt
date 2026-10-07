package io.github.dilshodbekmx.passwordvault.clipboard

import io.github.dilshodbekmx.passwordvault.i18n.UiText

/**
 * The clipboard status row (ux.md §6): "Password for Gmail · clears in 0:52" with "Clear now".
 *
 * @property what what was copied: "Password for Gmail", "Username for Gmail", "Copied text".
 * @property sensitive the clip carries the sensitive flag.
 * @property clearsAtElapsedMs `elapsedRealtime()` at which the clear is planned; the row counts down
 *   to it every second, visually only, and says "Clearing…" once it has passed without the clear.
 * @property delayMs the chosen delay, for the one polite announcement when the row appears
 *   ("Password copied. It will be removed from the clipboard in 1 minute.").
 * @property cleared the clear ran while the app was open: the row says "Clipboard cleared" for 2 s,
 *   then the status becomes null.
 */
data class ClipStatus(
    val what: UiText,
    val sensitive: Boolean,
    val clearsAtElapsedMs: Long,
    val delayMs: Long,
    val cleared: Boolean = false,
)
