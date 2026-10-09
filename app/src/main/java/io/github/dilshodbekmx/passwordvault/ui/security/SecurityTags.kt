package io.github.dilshodbekmx.passwordvault.ui.security

/**
 * The test tags of the Security tab and its lists (ux.md §5.16): the device tests and the emulator
 * scripts find the parts by these, whatever the language. Each is exposed as a resource id, because
 * the route's root sets `testTagsAsResourceId` (`AppNavHost`). None holds an account's name.
 */
object SecurityTags {
    /** The tab's scrolling content. */
    const val LIST = "pv.security.list"

    /** "Why this matters". */
    const val WHY = "pv.security.why"

    /** The button that folds and unfolds it. */
    const val WHY_TOGGLE = "pv.security.why.toggle"

    /** Its text, there while it is open. */
    const val WHY_TEXT = "pv.security.why.text"

    /** "Checking 120 of 480…", or the bar alone before the first account is scored. */
    const val CHECKING = "pv.security.checking"

    /** "Add some accounts first. …" */
    const val NO_ACCOUNTS = "pv.security.none"

    /** "All your passwords are different and hard to guess. Well done." */
    const val ALL_CLEAR = "pv.security.clear"

    /** The dot on the Security tab of the bottom bar (inside the tab's merged node). */
    const val BADGE = "pv.security.badge"

    /** The card [card] ("master", "reused", "weak", "pending", "left-out"). */
    fun card(card: String): String = "pv.security.card.$card"

    /** The button of the card [card]. */
    fun cardButton(card: String): String = "pv.security.card.$card.open"

    /** The line of another group of reused passwords on card 2. */
    fun otherGroup(id: String): String = "pv.security.group.$id"

    /** The names of the five cards, in their order (ux.md §5.16). */
    const val MASTER = "master"
    const val REUSED = "reused"
    const val WEAK = "weak"
    const val PENDING = "pending"
    const val LEFT_OUT = "left-out"

    // ---- The list behind a card ----

    /** The list's scrolling content. */
    const val GROUP_LIST = "pv.group.list"

    /** Its heading, "These 17 accounts share one password". */
    const val GROUP_HEADER = "pv.group.header"

    /** "3 changed, 14 to go", in a group of reused passwords that some accounts have left. */
    const val GROUP_PROGRESS = "pv.group.progress"

    /** The line that says the list is empty now. */
    const val GROUP_DONE = "pv.group.done"

    /** The bar while the first check after unlock runs. */
    const val GROUP_CHECKING = "pv.group.checking"

    /** "Leave out of the security check" in a row's menu. */
    const val LEAVE_OUT = "pv.group.leave-out"

    /** The row of the account [id]. */
    fun row(id: String): String = "pv.group.row.$id"

    /** The row's button: "Change", "Continue" or "Check it again". */
    fun rowAction(id: String): String = "pv.group.action.$id"

    /** The row's ⋮ menu button. */
    fun rowMore(id: String): String = "pv.group.more.$id"
}
