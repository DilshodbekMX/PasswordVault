package io.github.dilshodbekmx.passwordvault.core.model

import io.github.dilshodbekmx.passwordvault.core.search.WebAddress
import io.github.dilshodbekmx.passwordvault.core.util.TextHygiene
import java.util.Locale

/**
 * The rules of an entry as the user types it (ux.md §2), applied by Add and Edit (ux.md §5.14) and
 * by every edit of [VaultEdits] that stores typed text. The form is a [DraftEntry]: the fields the
 * draft keeps are exactly the fields the user types.
 *
 * - **Limits** count Unicode code points, not UTF-16 units: an emoji is one character ([length]).
 * - **Control characters** (U+0000 to U+001F and U+007F, `TextHygiene`) and **unpaired surrogates**
 *   are refused, except line feeds in notes and field values. A carriage return there is first
 *   made a line feed (`TextHygiene.normalizeLineBreaks`), so pasted Windows text is kept, not refused.
 * - **Trimmed**: the name, the username, each website and each field name (formats.md §4.4 relies
 *   on the field names). A password is never trimmed or changed in any way; notes and field values
 *   are not trimmed either.
 * - **Websites**: blank rows are dropped, and `https://` is put in front of one that has no scheme
 *   and looks like a host ([normalizeUrl], the importer's rule too: formats.md §8.4).
 * - **Extra fields**: a row whose name is blank and whose value is blank is dropped; a value without
 *   a name is refused.
 * - **The name** may be left blank: Save fills it from the host of the first website, or with
 *   "Untitled" and the time from [EditTexts.untitled] ([title]).
 *
 * [problems] says what breaks a rule, row by row as typed; [prepare] gives what is stored. These are
 * rules for input: a reader never applies them, so a stored entry is never refused for them
 * (architecture.md §4.3).
 */
object EntryRules {
    /** Name: 1 to 200 code points after trimming, one line; filled at Save when blank. */
    const val TITLE_MAX = 200

    /** Username: at most 500, trimmed, one line. */
    const val USERNAME_MAX = 500

    /** Password: at most 1,000, one line, never trimmed (Chromium's importer limit too). */
    const val PASSWORD_MAX = 1_000

    /** At most 10 websites per entry. */
    const val URLS_MAX = 10

    /** Each website: at most 2,048 after [normalizeUrl], one line. */
    const val URL_MAX = 2_048

    /** Notes: at most 10,000, line breaks allowed. */
    const val NOTES_MAX = 10_000

    /** At most 20 extra fields per entry. */
    const val FIELDS_MAX = 20

    /** A field's name: 1 to 100 after trimming, one line. */
    const val FIELD_NAME_MAX = 100

    /** A field's value: at most 1,000, line breaks allowed. */
    const val FIELD_VALUE_MAX = 1_000

    /** Previous passwords kept per entry, newest first (ux.md §2). */
    const val HISTORY_MAX = 5

    private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")
    private val HOST_LIKE = Regex("^[^\\s/:]+\\.\\S+$")

    /** The number of Unicode code points in [text]: how ux.md §2 counts every limit. */
    fun length(text: CharSequence): Int = Character.codePointCount(text, 0, text.length)

    /**
     * [text] cut to its first [max] code points (max ≥ 0), never between the two halves of a
     * surrogate pair: what a field holds when typing stops at its limit ("10,000 / 10,000").
     */
    fun cut(text: String, max: Int): String {
        require(max >= 0) { "negative limit" }
        if (text.length <= max || length(text) <= max) return text
        return text.substring(0, text.offsetByCodePoints(0, max))
    }

    /**
     * The website rule of ux.md §2, shared with the importer (formats.md §8.4, formats-review P10):
     * trimmed, and `https://` put in front when the text has no scheme (`letters://` at its start)
     * and looks like a host: no space, no `/` or `:` before its first dot, and something after it.
     * So `gmail.com` and `mail.google.com/x` get `https://`; `android://…`, `androidapp://…`,
     * `mailto:…`, `localhost` and text such as "my bank" are kept as they are. Nothing else changes:
     * no case, no `www.`, no path.
     */
    fun normalizeUrl(raw: String): String {
        val url = raw.trim()
        if (url.isEmpty() || SCHEME.containsMatchIn(url)) return url
        return if (HOST_LIKE.matches(url)) "https://$url" else url
    }

    /**
     * What Save stores of [form], before the name is filled ([title]): the name, the username, every
     * website and every field name trimmed; websites through [normalizeUrl], blank ones dropped; line
     * breaks of the notes and field values made line feeds; field rows with a blank name and a blank
     * value dropped. The password and [DraftEntry.favourite] are kept as they are. Checks nothing:
     * [problems] does.
     */
    fun prepare(form: DraftEntry): DraftEntry = DraftEntry(
        title = form.title.trim(),
        username = form.username.trim(),
        password = form.password,
        notes = TextHygiene.normalizeLineBreaks(form.notes),
        urls = form.urls.map(::normalizeUrl).filter { it.isNotEmpty() },
        fields = form.fields.mapNotNull(::prepareField),
        favourite = form.favourite,
    )

    /**
     * Everything in [form] that breaks a rule of ux.md §2, in the order of the form: name, username,
     * password, websites, notes, extra fields. Each row is checked as [prepare] would store it, and
     * [EntryProblem.index] is its position in [DraftEntry.urls] or [DraftEntry.fields] as typed,
     * blank rows included, so that the screen can mark the row. Empty: Save may go ahead.
     */
    fun problems(form: DraftEntry): List<EntryProblem> {
        val out = ArrayList<EntryProblem>()
        check(out, EntryField.TITLE, -1, form.title.trim(), TITLE_MAX, lineBreaks = false)
        check(out, EntryField.USERNAME, -1, form.username.trim(), USERNAME_MAX, lineBreaks = false)
        check(out, EntryField.PASSWORD, -1, form.password, PASSWORD_MAX, lineBreaks = false)
        val websites = form.urls.withIndex().filter { normalizeUrl(it.value).isNotEmpty() }
        if (websites.size > URLS_MAX) out += EntryProblem(EntryField.WEBSITES, EntryProblem.Kind.TOO_MANY, -1, URLS_MAX)
        for ((index, raw) in websites) check(out, EntryField.WEBSITE, index, normalizeUrl(raw), URL_MAX, lineBreaks = false)
        check(out, EntryField.NOTES, -1, TextHygiene.normalizeLineBreaks(form.notes), NOTES_MAX, lineBreaks = true)
        val fields = form.fields.withIndex().mapNotNull { (index, field) -> prepareField(field)?.let { IndexedValue(index, it) } }
        if (fields.size > FIELDS_MAX) out += EntryProblem(EntryField.FIELDS, EntryProblem.Kind.TOO_MANY, -1, FIELDS_MAX)
        for ((index, field) in fields) {
            if (field.name.isEmpty()) {
                out += EntryProblem(EntryField.FIELD_NAME, EntryProblem.Kind.BLANK, index)
            } else {
                check(out, EntryField.FIELD_NAME, index, field.name, FIELD_NAME_MAX, lineBreaks = false)
            }
            check(out, EntryField.FIELD_VALUE, index, field.value, FIELD_VALUE_MAX, lineBreaks = true)
        }
        return out
    }

    /** Whether [form] breaks no rule: [problems] is empty. */
    fun isValid(form: DraftEntry): Boolean = problems(form).isEmpty()

    /**
     * The name Save stores for [form] (ux.md §2): the name as typed, trimmed; when that is blank,
     * the host of the first website ([fromWebsites]); when there is none, [EditTexts.untitled] of
     * [nowMs], trimmed and cut to [TITLE_MAX].
     */
    fun title(form: DraftEntry, nowMs: Long, texts: EditTexts): String {
        val typed = form.title.trim()
        if (typed.isNotEmpty()) return typed
        return fromWebsites(form.urls) ?: storedTitle(texts.untitled(nowMs))
    }

    /**
     * The name taken from the websites when none was typed: `WebAddress.hostOf` of the first website
     * that is not blank (after [normalizeUrl]), such as `mail.google.com` for
     * `https://mail.google.com/mail/u/0`, stored as any name this app writes (trimmed, cut to
     * [TITLE_MAX]). Null when there is no website or its host is empty.
     */
    fun fromWebsites(urls: List<String>): String? {
        val first = urls.asSequence().map(::normalizeUrl).firstOrNull { it.isNotEmpty() } ?: return null
        return storedTitle(WebAddress.hostOf(first)).ifEmpty { null }
    }

    /**
     * The duplicate question of ux.md §5.14 ("You already have Gmail with this login. Save
     * anyway?"): the first other account outside Recently deleted with the same name and the same
     * username, both trimmed and compared without regard to case; null when there is none.
     * [exceptId] is the entry being edited, which never counts; a draft is not an account.
     */
    fun sameLogin(vault: Vault, title: String, username: String, exceptId: String?): Entry? {
        val name = fold(title)
        val login = fold(username)
        return vault.entries.firstOrNull {
            it.deletedAt == null && it.id != exceptId && fold(it.title) == name && fold(it.username) == login
        }
    }

    /**
     * A text this app writes into the vault as a name (from [EditTexts], or a host): trimmed, without
     * control characters, with unpaired surrogates made U+FFFD, and cut to [TITLE_MAX].
     */
    internal fun storedTitle(text: String): String {
        val clean = TextHygiene.replaceUnpairedSurrogates(TextHygiene.removeControls(text, lineBreaks = false))
        return cut(clean.trim(), TITLE_MAX)
    }

    private fun prepareField(field: CustomField): CustomField? {
        val name = field.name.trim()
        val value = TextHygiene.normalizeLineBreaks(field.value)
        if (name.isEmpty() && value.isBlank()) return null
        return if (name == field.name && value == field.value) field else CustomField(name, value, field.secret)
    }

    private fun check(out: MutableList<EntryProblem>, field: EntryField, index: Int, text: String, max: Int, lineBreaks: Boolean) {
        if (TextHygiene.firstControl(text, lineBreaks) >= 0) out += EntryProblem(field, EntryProblem.Kind.CONTROL_CHARACTER, index)
        if (TextHygiene.hasUnpairedSurrogate(text)) out += EntryProblem(field, EntryProblem.Kind.UNPAIRED_SURROGATE, index)
        if (length(text) > max) out += EntryProblem(field, EntryProblem.Kind.TOO_LONG, index, max)
    }

    private fun fold(text: String): String = text.trim().lowercase(Locale.ROOT)
}

/** The parts of the add and edit form that a rule of ux.md §2 is about. */
enum class EntryField {
    TITLE,
    USERNAME,
    PASSWORD,

    /** The list of websites as a whole (too many). */
    WEBSITES,

    /** One website. */
    WEBSITE,
    NOTES,

    /** The list of extra fields as a whole (too many). */
    FIELDS,
    FIELD_NAME,
    FIELD_VALUE,
}

/**
 * One rule of ux.md §2 that the form breaks. It names the place and the rule, never the text, so it
 * can be shown in any language and logged nowhere (architecture.md §5.6).
 */
data class EntryProblem(
    val field: EntryField,
    val kind: Kind,
    /**
     * For [EntryField.WEBSITE], [EntryField.FIELD_NAME] and [EntryField.FIELD_VALUE]: the row in
     * [DraftEntry.urls] or [DraftEntry.fields] as typed, blank rows included. Otherwise -1.
     */
    val index: Int = -1,
    /** For [Kind.TOO_LONG] and [Kind.TOO_MANY]: the limit, in code points or rows. Otherwise 0. */
    val limit: Int = 0,
) {
    enum class Kind {
        /** More code points than [limit]. */
        TOO_LONG,

        /** More websites or extra fields than [limit]. */
        TOO_MANY,

        /** A field's value without a name. */
        BLANK,

        /** U+0000 to U+001F or U+007F; a line feed counts only where line breaks are not allowed. */
        CONTROL_CHARACTER,

        /** Half of a surrogate pair without the other half: not a Unicode character. */
        UNPAIRED_SURROGATE,
    }
}
