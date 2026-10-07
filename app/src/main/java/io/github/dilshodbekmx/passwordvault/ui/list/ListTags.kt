package io.github.dilshodbekmx.passwordvault.ui.list

/**
 * The test tags of the list's screens, which the shell exposes as resource ids (architecture.md
 * §3.6), so that tests and the emulator scripts find them whatever the language. A tag holds an
 * entry's id (a UUID) at most, never a title or anything typed.
 */
object ListTags {
    const val SEARCH = "pv.list.search"
    const val SEARCH_CLEAR = "pv.list.searchClear"
    const val LIST = "pv.list.list"
    const val LOCK_NOW = "pv.list.lockNow"
    const val ADD = "pv.list.add"
    const val FAVOURITES = "pv.list.favourites"
    const val SHOW_ALL_FAVOURITES = "pv.list.showAllFavourites"
    const val ALL_ACCOUNTS = "pv.list.allAccounts"
    const val RECENTLY_DELETED = "pv.list.recentlyDeleted"
    const val RETRIED = "pv.list.retried"
    const val NO_RESULTS = "pv.list.noResults"
    const val ADD_AS_NEW = "pv.list.addAsNew"
    const val EMPTY = "pv.list.empty"
    const val EMPTY_ADD = "pv.list.emptyAdd"
    const val EMPTY_IMPORT = "pv.list.emptyImport"
    const val PRACTICE_FIELD = "pv.list.practiceField"
    const val PRACTICE_CHECK = "pv.list.practiceCheck"

    const val DELETED_LIST = "pv.deleted.list"
    const val DELETED_EMPTY = "pv.deleted.empty"
    const val DELETED_NOTHING = "pv.deleted.nothing"
    const val DIALOG_CONFIRM = "pv.list.dialogConfirm"

    /** A row of the list or of the search results: `pv.list.row.<id>`. */
    fun row(id: String): String = "pv.list.row.$id"

    /**
     * A row's copy button, by the row's key in the list: `f.<id>` among the favourites, `e.<id>` A to
     * Z, `r.<id>` in the search results (an account can be listed twice).
     */
    fun copy(key: String): String = "pv.list.copy.$key"

    /** A favourite's row in the Favourites part. */
    fun favourite(id: String): String = "pv.list.fav.$id"

    /** A letter header, by its place among the headers (not its letter, which comes from a title). */
    fun header(index: Int): String = "pv.list.header.$index"

    /** A banner, by its kind: `pv.list.banner.practice`. Named here, not by class, which R8 renames in staging. */
    fun banner(banner: ListBanner): String = "pv.list.banner." + when (banner) {
        is ListBanner.PendingMasterPassword -> "pending"
        ListBanner.PendingReady -> "pendingReady"
        is ListBanner.Replaced -> "replaced"
        is ListBanner.PracticeOverdue -> "practice"
        is ListBanner.UnprotectedExport -> "export"
        ListBanner.BackupDue -> "backup"
        is ListBanner.ChangesPending -> "changes"
    }

    /** A banner's button, by the banner's kind and the button's name: `pv.list.banner.practice.typeItNow`. */
    fun bannerButton(banner: ListBanner, button: String): String = banner(banner) + "." + button

    /** A row of Recently deleted, and its "Restore" and "More options" buttons. */
    fun deletedRow(id: String): String = "pv.deleted.row.$id"

    fun restore(id: String): String = "pv.deleted.restore.$id"

    fun deletedMore(id: String): String = "pv.deleted.more.$id"
}
