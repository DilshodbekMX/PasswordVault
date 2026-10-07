package io.github.dilshodbekmx.passwordvault.ui.entry

/**
 * The test tags of an account's screen and of "Show in big letters", which the shell exposes as
 * resource ids (architecture.md §3.6), so that tests and the emulator scripts find them whatever
 * the language. A tag holds a place in the account at most (the third website, the first field),
 * never a text of it.
 *
 * A row's tag is on the row itself, the node TalkBack reads; `<row>.value` is the text inside it,
 * `<row>.show` and `<row>.copy` its buttons, `<row>.big` its "Show in big letters" button and
 * `<row>.open` the button that opens a website.
 */
object EntryTags {
    const val BACK = "pv.entry.back"

    /** The account's name, the first line of the content. */
    const val TITLE = "pv.entry.title"
    const val FAVOURITE = "pv.entry.favourite"
    const val EDIT = "pv.entry.edit"
    const val MORE = "pv.entry.more"
    const val MENU_BIG_LETTERS = "pv.entry.menu.bigLetters"
    const val MENU_LEAVE_OUT = "pv.entry.menu.leaveOut"
    const val MENU_DELETE = "pv.entry.menu.delete"

    /** The scrolling content. */
    const val CONTENT = "pv.entry.content"

    const val USERNAME = "pv.entry.username"
    const val PASSWORD = "pv.entry.password"
    const val OLD_PASSWORD = "pv.entry.oldPassword"
    const val NOTES = "pv.entry.notes"
    const val NOTES_MORE = "pv.entry.notes.more"

    /** "Previous passwords (2)". */
    const val PREVIOUS = "pv.entry.previous"
    const val DATES = "pv.entry.dates"

    const val BANNER_CHANGE = "pv.entry.banner.change"
    const val BANNER_CHANGE_YES = "pv.entry.banner.change.yes"
    const val BANNER_CHANGE_NO = "pv.entry.banner.change.no"
    const val BANNER_LEFT_OUT = "pv.entry.banner.leftOut"
    const val BANNER_CHECK_AGAIN = "pv.entry.banner.leftOut.checkAgain"
    const val BANNER_MASTER = "pv.entry.banner.master"
    const val BANNER_MASTER_CHOOSE = "pv.entry.banner.master.choose"

    /** "No details yet" and its "Edit". */
    const val EMPTY = "pv.entry.empty"
    const val EMPTY_EDIT = "pv.entry.empty.edit"

    /** "This account no longer exists." or "This account was deleted. …", and its buttons. */
    const val GONE = "pv.entry.gone"
    const val GONE_BACK = "pv.entry.gone.back"
    const val GONE_OPEN_DELETED = "pv.entry.gone.openDeleted"

    /** The screen after Delete, while "Undo" is offered. */
    const val DELETED = "pv.entry.deleted"

    /** The snackbar. */
    const val MESSAGE = "pv.entry.message"

    /** "Show in big letters": Close, the boxes and "Too long to show in big letters". */
    const val BIG_CLOSE = "pv.big.close"
    const val BIG_CELLS = "pv.big.cells"
    const val BIG_TOO_LONG = "pv.big.tooLong"

    /** The row of the website at [index] of the account. */
    fun website(index: Int): String = "pv.entry.website.$index"

    /** The row of the extra field at [index]. */
    fun field(index: Int): String = "pv.entry.field.$index"

    /** The row of the previous password at [index], the newest first. */
    fun previous(index: Int): String = "pv.entry.previous.$index"

    /** The row of [value]. */
    fun row(value: EntryValue): String = when (value) {
        EntryValue.Username -> USERNAME
        EntryValue.Password -> PASSWORD
        EntryValue.OldPassword -> OLD_PASSWORD
        is EntryValue.Previous -> previous(value.index)
        is EntryValue.Field -> field(value.index)
        is EntryValue.Website -> website(value.index)
        EntryValue.Notes -> NOTES
    }

    /** The text of [value] inside its row: dots while hidden. */
    fun value(value: EntryValue): String = row(value) + ".value"

    /** The show/hide button of [value]'s row. */
    fun show(value: EntryValue): String = row(value) + ".show"

    /** The copy button of [value]'s row. */
    fun copy(value: EntryValue): String = row(value) + ".copy"

    /** The "Show in big letters" button under a password. */
    fun big(value: EntryValue): String = row(value) + ".big"

    /** The button that opens the website at [index] in the browser. */
    fun open(index: Int): String = website(index) + ".open"

    /** An item of a row's menu, by its place in the menu. */
    fun menuItem(value: EntryValue, index: Int): String = row(value) + ".menu.$index"

    /** The box of the character at [index] of "Show in big letters", from 0. */
    fun bigCell(index: Int): String = "pv.big.cell.$index"
}
