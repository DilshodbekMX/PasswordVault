package io.github.dilshodbekmx.passwordvault.ui.edit

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import io.github.dilshodbekmx.passwordvault.core.model.CustomField
import io.github.dilshodbekmx.passwordvault.core.model.DraftEntry
import io.github.dilshodbekmx.passwordvault.core.model.EntryRules
import io.github.dilshodbekmx.passwordvault.ui.common.clearSecret

/**
 * The fields of the add and edit form (ux.md §5.14) as the user types them: one `TextFieldState`
 * per field, held by [EditViewModel] and never by `rememberTextFieldState()`, so that nothing typed
 * reaches the saved-instance state (architecture.md §5.6). The rows of websites and extra fields are
 * snapshot lists, so that adding or removing one is seen like typing.
 *
 * The form is read as a `DraftEntry` in two ways: [typed], every row as it is (for `EntryRules`,
 * whose problems name rows by their position), and [draft], without empty website rows (for the
 * draft and for knowing whether anything changed). Main thread only.
 */
@OptIn(ExperimentalFoundationApi::class) // undoState: Undo starts from what was loaded
class EditForm {
    /** The last row id handed out; first in the class, because the first website row takes one. */
    private var lastId = 0L

    val title = TextFieldState()
    val username = TextFieldState()
    val password = TextFieldState()
    val notes = TextFieldState()

    /** The website rows, at least one; at most [EntryRules.URLS_MAX]. */
    val websites: SnapshotStateList<WebsiteRow> = mutableStateListOf(WebsiteRow(nextId()))

    /** The extra fields, at most [EntryRules.FIELDS_MAX]. */
    val fields: SnapshotStateList<FieldRow> = mutableStateListOf()

    /** The star: not on the form, carried through so that Save keeps it (`VaultEdits.update` writes it). */
    var favourite: Boolean = false

    /** The form as typed, every row included. */
    fun typed(): DraftEntry = DraftEntry(
        title = title.text.toString(),
        username = username.text.toString(),
        password = password.text.toString(),
        notes = notes.text.toString(),
        urls = websites.map { it.state.text.toString() },
        fields = fields.map { CustomField(it.name, it.value.text.toString(), it.secret) },
        favourite = favourite,
    )

    /** The form as its draft keeps it: as typed, without the website rows that are empty. */
    fun draft(): DraftEntry {
        val typed = typed()
        return typed.copy(urls = typed.urls.filter { it.isNotEmpty() })
    }

    /** Shows [entry]: every text as it is, one website row per website (one empty row when none). */
    fun load(entry: DraftEntry) {
        clear()
        title.setTextAndPlaceCursorAtEnd(entry.title)
        username.setTextAndPlaceCursorAtEnd(entry.username)
        password.setTextAndPlaceCursorAtEnd(entry.password)
        notes.setTextAndPlaceCursorAtEnd(entry.notes)
        websites.clear()
        if (entry.urls.isEmpty()) {
            websites += WebsiteRow(nextId())
        } else {
            for (url in entry.urls) websites += WebsiteRow(nextId()).also { it.state.setTextAndPlaceCursorAtEnd(url) }
        }
        for (field in entry.fields) {
            fields += FieldRow(nextId(), field.name, field.secret).also { it.value.setTextAndPlaceCursorAtEnd(field.value) }
        }
        favourite = entry.favourite
        // Loading is not typing: Undo starts from what was loaded.
        for (state in states()) state.undoState.clearHistory()
    }

    /** Empties every field and its undo history [V-C11] and drops the rows, as after a lock (architecture.md §5.5 step 5). */
    fun clear() {
        for (state in states()) state.clearSecret()
        websites.clear()
        websites += WebsiteRow(nextId())
        fields.clear()
        favourite = false
    }

    /** Adds an empty website row and returns it; null at [EntryRules.URLS_MAX]. */
    fun addWebsite(): WebsiteRow? {
        if (websites.size >= EntryRules.URLS_MAX) return null
        return WebsiteRow(nextId()).also { websites += it }
    }

    /** Removes the website row [id] and empties it; the first row is never removed, only emptied. */
    fun removeWebsite(id: Long) {
        val index = websites.indexOfFirst { it.id == id }
        if (index < 0) return
        websites[index].state.clearSecret()
        if (websites.size > 1) websites.removeAt(index)
    }

    /** Adds the extra field [name] with [value]; null at [EntryRules.FIELDS_MAX]. */
    fun addField(name: String, value: CharSequence, secret: Boolean): FieldRow? {
        if (fields.size >= EntryRules.FIELDS_MAX) return null
        return FieldRow(nextId(), name, secret).also {
            it.value.setTextAndPlaceCursorAtEnd(value.toString())
            it.value.undoState.clearHistory()
            fields += it
        }
    }

    /** Removes the extra field [id] and empties its value. */
    fun removeField(id: Long) {
        val index = fields.indexOfFirst { it.id == id }
        if (index < 0) return
        fields[index].value.clearSecret()
        fields.removeAt(index)
    }

    /** Every text field of the form, rows included. */
    fun states(): List<TextFieldState> =
        listOf(title, username, password, notes) + websites.map { it.state } + fields.map { it.value }

    private fun nextId(): Long = ++lastId
}

/** One website row of the form; [id] stays the same while rows are added and removed. */
class WebsiteRow(val id: Long) {
    val state = TextFieldState()
}

/**
 * One extra field of the form: its label [name] (chosen in the "Add field" dialog), whether its
 * value is [secret] (masked until "Show"), and the value as typed. [id] stays the same while rows
 * are added and removed.
 */
class FieldRow(val id: Long, val name: String, val secret: Boolean) {
    val value = TextFieldState()

    override fun toString(): String = "FieldRow(id=$id, secret=$secret, texts hidden)"
}
