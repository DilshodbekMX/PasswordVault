package io.github.dilshodbekmx.passwordvault.ui.entry

import io.github.dilshodbekmx.passwordvault.core.model.Entry
import io.github.dilshodbekmx.passwordvault.core.search.WebAddress

/**
 * One account as its screen shows it (ux.md §5.12), made from the entry in the open vault and from
 * what the person has asked to see.
 *
 * **Secrets.** A password, a previous password or the value of a hidden field is in here only
 * while it is shown ([SecretRow.shown], [FieldRow.shown], [PreviousRow.shown]); a hidden one is
 * null, so the state of the screen never holds a secret that is not on the screen. The rest is
 * account data all the same: the ViewModel drops the whole of it when the vault locks, and no
 * [toString] here shows a text of the account (architecture.md §5.4, §5.6).
 *
 * @property changeStartedAtMs while a password change is pending, when its new password was made,
 *   for "You started changing this password on 6 Oct"; null otherwise.
 * @property sameAsMasterPassword the password was found equal to the master password at the last
 *   master-password entry (architecture.md D-9b).
 * @property passwords the password; while a change is pending the old one, then the new one; empty
 *   for an account without a password.
 * @property websites the addresses that are not blank, each with its place in the entry.
 * @property previousCount how many previous passwords there are; [previous] holds their rows only
 *   while "Previous passwords" is open.
 * @property passwordChangedAtMs when the password last changed; null without a password, without a
 *   date, and while a change is pending (the banner says when that started).
 * @property editedAtMs when the account was last changed; null when it never was since it was added.
 */
data class EntryDetails(
    val id: String,
    val title: String,
    val favourite: Boolean,
    val changeStartedAtMs: Long?,
    val checkIgnored: Boolean,
    val sameAsMasterPassword: Boolean,
    val username: String,
    val passwords: List<SecretRow>,
    val websites: List<WebsiteRow>,
    val notes: String,
    val notesExpanded: Boolean,
    val fields: List<FieldRow>,
    val previousCount: Int,
    val previous: List<PreviousRow>,
    val passwordChangedAtMs: Long?,
    val createdAtMs: Long,
    val editedAtMs: Long?,
) {
    /** A password change is waiting for its answer (ux.md §5.17). */
    val changePending: Boolean get() = changeStartedAtMs != null

    /** The account has a name and nothing else: "No details yet" (ux.md §5.12 "States"). */
    val empty: Boolean
        get() = username.isEmpty() && passwords.isEmpty() && websites.isEmpty() && notes.isEmpty() && fields.isEmpty()

    /** "Previous passwords" is open. */
    val previousExpanded: Boolean get() = previous.isNotEmpty()

    override fun toString(): String =
        "EntryDetails(id=$id, favourite=$favourite, changePending=$changePending, checkIgnored=$checkIgnored, " +
            "sameAsMasterPassword=$sameAsMasterPassword, passwords=${passwords.size}, websites=${websites.size}, " +
            "notesExpanded=$notesExpanded, fields=${fields.size}, previous=${previous.size} of $previousCount, texts hidden)"

    companion object {
        /**
         * [entry] as the screen shows it.
         *
         * @param sameAsMasterPassword the vault lists the entry among those whose password equals
         *   the master password.
         * @param revealed the secrets the person asked to see; any other stays hidden.
         * @param previousExpanded whether "Previous passwords" is open.
         */
        fun of(
            entry: Entry,
            sameAsMasterPassword: Boolean,
            revealed: Set<EntryValue>,
            notesExpanded: Boolean,
            previousExpanded: Boolean,
        ): EntryDetails {
            fun shown(value: EntryValue): String? = if (value in revealed) entry.textOf(value) else null
            val passwords = buildList {
                if (entry.changePending && entry.textOf(EntryValue.OldPassword) != null) {
                    add(SecretRow(EntryValue.OldPassword, shown(EntryValue.OldPassword)))
                }
                if (entry.password.isNotEmpty()) add(SecretRow(EntryValue.Password, shown(EntryValue.Password)))
            }
            val fields = entry.fields.mapIndexed { index, field ->
                val value = EntryValue.Field(index)
                FieldRow(index, field.name, field.secret, field.value.isEmpty(), if (field.secret) shown(value) else field.value)
            }
            val previous = if (previousExpanded) {
                entry.passwordHistory.mapIndexed { index, item -> PreviousRow(index, item.replacedAt, shown(EntryValue.Previous(index))) }
            } else {
                emptyList()
            }
            return EntryDetails(
                id = entry.id,
                title = entry.title,
                favourite = entry.favourite,
                changeStartedAtMs = if (entry.changePending) entry.passwordChangedAt ?: entry.updatedAt else null,
                checkIgnored = entry.checkIgnored,
                sameAsMasterPassword = sameAsMasterPassword,
                username = entry.username,
                passwords = passwords,
                websites = entry.urls.mapIndexedNotNull { index, address -> WebsiteRow.of(index, address) },
                notes = entry.notes,
                notesExpanded = notesExpanded,
                fields = fields,
                previousCount = entry.passwordHistory.size,
                previous = previous,
                passwordChangedAtMs = entry.passwordChangedAt.takeIf { !entry.changePending && entry.password.isNotEmpty() },
                createdAtMs = entry.createdAt,
                editedAtMs = entry.updatedAt.takeIf { it != entry.createdAt },
            )
        }
    }
}

/** A password row: which password, and its text while it is [shown]; null while it is hidden. */
data class SecretRow(val value: EntryValue, val shown: String?) {
    override fun toString(): String = "SecretRow($value, shown=${shown != null})"
}

/**
 * An extra field (ux.md §2): its place in the entry, its [name], whether its value is [empty] (then
 * there is nothing to show, hide or copy), and its value in [shown]: always for a field that is not
 * [secret], and only while it is shown for one that is.
 */
data class FieldRow(val index: Int, val name: String, val secret: Boolean, val empty: Boolean, val shown: String?) {
    val value: EntryValue.Field get() = EntryValue.Field(index)

    override fun toString(): String = "FieldRow($index, secret=$secret, empty=$empty, shown=${shown != null}, texts hidden)"
}

/** A previous password: its place in the history, when it stopped being the password, and its text while [shown]. */
data class PreviousRow(val index: Int, val replacedAtMs: Long, val shown: String?) {
    val value: EntryValue.Previous get() = EntryValue.Previous(index)

    override fun toString(): String = "PreviousRow($index, replacedAtMs=$replacedAtMs, shown=${shown != null})"
}

/** What a stored website address is, which decides what its row offers (ux.md §5.12, §2). */
enum class WebsiteKind {
    /** An `http` or `https` address: "Open" starts the browser. */
    WEB,

    /** An `android://` address that Chrome saved for an app: shown as "App: com.example", never opened. */
    APP,

    /** Anything else: shown and copied, never opened. */
    OTHER,
}

/**
 * A website row: the address's place in the entry, its [host] (bold; for an app, the app's package
 * name), the whole [address] as it is stored, and its [kind].
 */
data class WebsiteRow(val index: Int, val host: String, val address: String, val kind: WebsiteKind) {
    val value: EntryValue.Website get() = EntryValue.Website(index)

    override fun toString(): String = "WebsiteRow($index, $kind, texts hidden)"

    companion object {
        /** The row of [address], the entry's website at [index]; null for a blank one. */
        fun of(index: Int, address: String): WebsiteRow? {
            val trimmed = address.trim()
            if (trimmed.isEmpty()) return null
            val kind = kindOf(trimmed)
            return WebsiteRow(index, WebAddress.hostOf(trimmed).ifEmpty { trimmed }, trimmed, kind)
        }

        /** Only `http` and `https` are opened (ux.md §5.12); `android://` and `androidapp://` name an app (ux.md §2). */
        fun kindOf(address: String): WebsiteKind = when {
            address.startsWith("https://", ignoreCase = true) || address.startsWith("http://", ignoreCase = true) -> WebsiteKind.WEB
            address.startsWith("android://", ignoreCase = true) || address.startsWith("androidapp://", ignoreCase = true) -> WebsiteKind.APP
            else -> WebsiteKind.OTHER
        }
    }
}
