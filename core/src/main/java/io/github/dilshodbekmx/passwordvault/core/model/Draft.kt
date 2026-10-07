package io.github.dilshodbekmx.passwordvault.core.model

/**
 * The one unsaved add or edit form, kept inside the vault so that it survives a lock (ux.md
 * §5.14, architecture.md D-8a): `{"entryId": string or null, "savedAt": integer, "entry": {…}}`.
 * The entry it edits is not changed until Save.
 */
data class Draft(
    /** The entry being edited, or null for a new account. */
    val entryId: String?,
    /** When the draft was held. */
    val savedAt: Long,
    val entry: DraftEntry,
)

/**
 * The fields of the form as typed, `{title, username, password, notes, urls, fields, favourite}`
 * in that order (architecture.md §4.3). Unknown keys inside a draft are not kept. [toString] shows
 * no text (§5.6).
 */
data class DraftEntry(
    val title: String = "",
    val username: String = "",
    val password: String = "",
    val notes: String = "",
    val urls: List<String> = emptyList(),
    val fields: List<CustomField> = emptyList(),
    val favourite: Boolean = false,
) {
    override fun toString(): String =
        "DraftEntry(favourite=$favourite, urls=${urls.size}, fields=${fields.size}, texts hidden)"
}
