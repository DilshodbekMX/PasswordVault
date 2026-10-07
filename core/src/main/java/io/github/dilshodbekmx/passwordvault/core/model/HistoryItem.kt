package io.github.dilshodbekmx.passwordvault.core.model

/**
 * One earlier password of an entry and the time it stopped being the current one (ux.md §2):
 * `{"password": string, "replacedAt": integer}` (architecture.md §4.3). [toString] hides the
 * password (§5.6).
 */
data class HistoryItem(val password: String, val replacedAt: Long) {
    override fun toString(): String = "HistoryItem(replacedAt=$replacedAt, password hidden)"
}
