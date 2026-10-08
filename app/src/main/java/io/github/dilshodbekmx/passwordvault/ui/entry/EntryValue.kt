package io.github.dilshodbekmx.passwordvault.ui.entry

import io.github.dilshodbekmx.passwordvault.core.model.Entry

/**
 * One value of an account on its screen (ux.md §5.12): what a row shows, hides, copies or hands to
 * "Show in big letters". It names the value by its kind and its place in the account, never by its
 * text, so it can be part of the screen's state and of a route key (architecture.md §5.6).
 *
 * [field] is the value's name in `Route.BigLetters` (architecture.md §3.6): `username`, `password`,
 * `old-password`, `previous-N` and `field-N`, with N counted from 0. A website address and the notes
 * have none: they are never shown in big letters.
 */
sealed interface EntryValue {
    /** The value's name in `Route.BigLetters`; null for a value that is never shown in big letters. */
    val field: String?

    data object Username : EntryValue {
        override val field: String get() = "username"
    }

    /** The account's password; while a change is pending, the new one, which is not confirmed yet (ux.md §5.17). */
    data object Password : EntryValue {
        override val field: String get() = "password"
    }

    /** The password the website still has while a change is pending (`Entry.pendingOldPassword`). */
    data object OldPassword : EntryValue {
        override val field: String get() = "old-password"
    }

    /** The previous password at [index] of `Entry.passwordHistory`, the newest first. */
    data class Previous(val index: Int) : EntryValue {
        override val field: String get() = "previous-$index"
    }

    /** The extra field at [index] of `Entry.fields`. */
    data class Field(val index: Int) : EntryValue {
        override val field: String get() = "field-$index"
    }

    /** The website address at [index] of `Entry.urls`. */
    data class Website(val index: Int) : EntryValue {
        override val field: String? get() = null
    }

    /** The whole notes. */
    data object Notes : EntryValue {
        override val field: String? get() = null
    }

    companion object {
        /** The value that `Route.BigLetters` names [field]; null for any other text. */
        fun of(field: String): EntryValue? = when (field) {
            "username" -> Username
            "password" -> Password
            "old-password" -> OldPassword
            else -> indexAfter(field, "previous-")?.let(::Previous) ?: indexAfter(field, "field-")?.let(::Field)
        }

        /** The number after [prefix] in [field]: digits only, as [EntryValue.field] writes them, so one name per value. */
        private fun indexAfter(field: String, prefix: String): Int? {
            if (!field.startsWith(prefix)) return null
            val digits = field.substring(prefix.length)
            if (digits.isEmpty() || digits.length > MAX_INDEX_DIGITS || digits.any { it !in '0'..'9' }) return null
            if (digits.length > 1 && digits[0] == '0') return null
            return digits.toInt()
        }

        /**
         * An account made here has at most 20 extra fields and 5 previous passwords (ux.md §2); a
         * stored one is never refused for having more (architecture.md §4.3), so four digits are read.
         */
        private const val MAX_INDEX_DIGITS = 4
    }
}

/**
 * The text of [value] in this account, or null when the account has none: an empty username or
 * password, an old password outside a pending change, a place past the end of a list.
 */
fun Entry.textOf(value: EntryValue): String? {
    val text = when (value) {
        EntryValue.Username -> username
        EntryValue.Password -> password
        EntryValue.OldPassword -> pendingOldPassword.takeIf { changePending }
        is EntryValue.Previous -> passwordHistory.getOrNull(value.index)?.password
        is EntryValue.Field -> fields.getOrNull(value.index)?.value
        is EntryValue.Website -> urls.getOrNull(value.index)
        EntryValue.Notes -> notes
    }
    return text?.takeIf { it.isNotEmpty() }
}

/**
 * Whether [value] is a secret of this account: shown as dots until "Show" is tapped (ux.md §3).
 * Passwords of every kind and the values of extra fields marked as hidden.
 */
fun Entry.hides(value: EntryValue): Boolean = when (value) {
    EntryValue.Password, EntryValue.OldPassword, is EntryValue.Previous -> true
    is EntryValue.Field -> fields.getOrNull(value.index)?.secret == true
    EntryValue.Username, is EntryValue.Website, EntryValue.Notes -> false
}

/**
 * Whether a copy of [value] carries the sensitive flag (ux.md §6 item 1): every secret, and
 * anything copied from the notes, which often hold security answers. Usernames, websites and fields
 * that are not hidden do not, so that Android's own preview confirms what was copied.
 */
fun Entry.copiesAsSensitive(value: EntryValue): Boolean = value == EntryValue.Notes || hides(value)
