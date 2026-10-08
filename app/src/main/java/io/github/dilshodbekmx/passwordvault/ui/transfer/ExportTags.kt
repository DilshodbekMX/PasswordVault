package io.github.dilshodbekmx.passwordvault.ui.transfer

/**
 * The test tags of the export screen, which the shell exposes as resource ids (architecture.md
 * §3.6), so that tests and the emulator scripts find each part whatever the language. A tag never
 * holds a text of the vault or a file's name.
 */
object ExportTags {
    const val BACK = "pv.export.back"

    /** The scrolling content of every step. */
    const val CONTENT = "pv.export.content"

    // Choose a format.
    const val CHOOSE = "pv.export.choose"
    const val CONTINUE = "pv.export.continue"

    /** The row of [choice] in "Choose a format": `pv.export.choice.backup`, `.xlsx`, `.csv`, `.json`. */
    fun choice(choice: ExportChoice): String = "pv.export.choice." + choice.name.lowercase(java.util.Locale.ROOT)

    // The warning.
    const val WARNING = "pv.export.warning"
    const val WARNING_FORMAT = "pv.export.warning.format"
    const val WARNING_OLD_ANDROID = "pv.export.warning.oldAndroid"
    const val UNDERSTAND = "pv.export.understand"
    const val WARNING_CONTINUE = "pv.export.warning.continue"
    const val CSV_CARD = "pv.export.csv"
    const val CSV_PARTS = "pv.export.csv.parts"
    const val SEE_WHICH = "pv.export.csv.seeWhich"
    const val WHICH = "pv.export.csv.which"

    // The master password.
    const val PASSWORD_TITLE = "pv.export.password.title"
    const val PASSWORD = "pv.export.password"
    const val PASSWORD_LINE = "pv.export.password.line"
    const val SUBMIT = "pv.export.submit"
    const val CHECKING = "pv.export.checking"
    const val PAUSED = "pv.export.paused"
    const val CANCEL = "pv.export.cancel"

    // The picker and saving.
    const val PICKING = "pv.export.picking"
    const val SAVING = "pv.export.saving"

    // The result.
    const val SAVED = "pv.export.saved"
    const val UPLOAD = "pv.export.upload"
    const val REPLACED = "pv.export.replaced"
    const val TRUNCATED = "pv.export.truncated"
    const val DONE = "pv.export.done"
    const val NEXT_PART = "pv.export.nextPart"
    const val DELETE = "pv.export.delete"
    const val DELETION = "pv.export.deletion"

    // No file.
    const val FAILED = "pv.export.failed"
    const val CLEANUP = "pv.export.cleanup"
    const val TRY_AGAIN = "pv.export.tryAgain"
    const val CLOSE = "pv.export.close"
    const val ENDED = "pv.export.ended"
    const val PENDING = "pv.export.pending"
    const val OK = "pv.export.ok"
}
