package io.github.dilshodbekmx.passwordvault.ui.list

import io.github.dilshodbekmx.passwordvault.core.model.Entry
import io.github.dilshodbekmx.passwordvault.core.search.WebAddress
import io.github.dilshodbekmx.passwordvault.data.ListModel
import java.text.BreakIterator
import java.util.Locale

/**
 * One account as a row of the Passwords list (ux.md §5.11): what the row shows and what its
 * actions need to know. It never holds a password: a copy reads the entry from the vault when it
 * is tapped ([PasswordsViewModel]), so no secret is ever part of the screen's state.
 *
 * @property detail the second line: the username, or the first website's host when there is no
 *   username (ux.md §5.11); "" when there is neither.
 * @property website the first `http` or `https` address, for "Open website"; null when there is
 *   none (an `android://` address is never opened, ux.md §2).
 * @property initial the round badge's letter: the first letter or digit of the title as one
 *   grapheme, in upper case, or the title's first grapheme when it has neither.
 * @property tone which of the badge's [BADGE_TONES] colours, from a hash of the title.
 *
 * A data class, so that an unchanged row is not drawn again; [toString] shows no text of the
 * account (architecture.md §5.6).
 */
data class EntryRow(
    val id: String,
    val title: String,
    val detail: String,
    val hasUsername: Boolean,
    val hasPassword: Boolean,
    val changePending: Boolean,
    val favourite: Boolean,
    val website: String?,
    val initial: String,
    val tone: Int,
) {
    override fun toString(): String =
        "EntryRow(id=$id, hasUsername=$hasUsername, hasPassword=$hasPassword, changePending=$changePending, " +
            "favourite=$favourite, website=${website != null}, texts hidden)"

    companion object {
        /** How many colours the round badge has (ux.md §5.11: 8 tones). */
        const val BADGE_TONES = 8

        /** The row of [entry]. */
        fun of(entry: Entry): EntryRow {
            val title = entry.title
            return EntryRow(
                id = entry.id,
                title = title,
                detail = entry.username.ifEmpty { entry.urls.firstOrNull()?.let(WebAddress::hostOf).orEmpty() },
                hasUsername = entry.username.isNotEmpty(),
                hasPassword = entry.password.isNotEmpty(),
                changePending = entry.changePending,
                favourite = entry.favourite,
                website = entry.urls.firstOrNull(::isWebAddress)?.trim(),
                initial = initialOf(title),
                tone = toneOf(title),
            )
        }

        /** Whether [address] can be opened in a browser: `http` or `https` only (ux.md §5.12). */
        fun isWebAddress(address: String): Boolean {
            val a = address.trim()
            return a.startsWith("https://", ignoreCase = true) || a.startsWith("http://", ignoreCase = true)
        }

        /**
         * The badge's letter for [title]: the grapheme of its first letter or digit, so that
         * "«Ipak Yoʻli»" shows "I" and "Ўзбектелеком" "Ў", in upper case without a locale (a
         * Turkish phone must not turn "i" into "İ"); the first grapheme when there is no letter or
         * digit; "#" for an empty title.
         */
        fun initialOf(title: String): String {
            if (title.isEmpty()) return "#"
            var start = 0
            while (start < title.length) {
                val cp = title.codePointAt(start)
                if (Character.isLetterOrDigit(cp)) break
                start += Character.charCount(cp)
            }
            if (start >= title.length) start = 0
            val graphemes = BreakIterator.getCharacterInstance(Locale.ROOT)
            graphemes.setText(title)
            val end = graphemes.following(start).let { if (it == BreakIterator.DONE) title.length else it }
            return title.substring(start, end).uppercase(Locale.ROOT)
        }

        /** The badge's tone for [title], 0 until [BADGE_TONES]: the same title always gets the same colour. */
        fun toneOf(title: String): Int = Math.floorMod(title.hashCode(), BADGE_TONES)
    }
}

/** One letter header of the list and its rows (ux.md §5.11, "Letter headers"). */
data class RowSection(val header: String, val rows: List<EntryRow>) {
    override fun toString(): String = "RowSection(rows=${rows.size})"
}

/**
 * The rows of one [ListModel], made once per model on a background thread: [all] in the order of
 * `ListModel.entries`, which is the order of its search index, so that a hit's index is a position
 * in [all] (search.md §7); [favourites] and [sections] as the model cuts them.
 */
class PreparedList(val model: ListModel, val all: List<EntryRow>, val favourites: List<EntryRow>, val sections: List<RowSection>) {
    override fun toString(): String = "PreparedList(rows=${all.size}, favourites=${favourites.size}, sections=${sections.size})"

    companion object {
        fun of(model: ListModel): PreparedList {
            val all = ArrayList<EntryRow>(model.entries.size)
            val sections = model.sections.map { section ->
                val rows = section.entries.map(EntryRow::of)
                all.addAll(rows)
                RowSection(section.header, rows)
            }
            return PreparedList(model, all, model.favourites.map(EntryRow::of), sections)
        }
    }
}
