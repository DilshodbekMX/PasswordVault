package io.github.dilshodbekmx.passwordvault.ui.transfer

/**
 * What the import's file screen shows (`Route.Import(ImportStep.FILE)`, ux.md §5.21): one of
 * [ImportScreen]'s states, whether the task was paused by a lock, the file's name, and the one-time
 * [message] and [move] the screen acts on.
 */
internal data class ImportUi(
    val screen: ImportScreen = ImportScreen.Reading,
    /** "This was paused when the app locked." (ux.md §4): the file was chosen before a lock or in another process. */
    val paused: Boolean = false,
    /** The name the place shows for the chosen file, when it can be read. */
    val fileName: String? = null,
    val message: ImportMessage? = null,
    val move: ImportMove? = null,
) {
    override fun toString(): String = "ImportUi($screen, paused=$paused, message=$message, move=$move)"
}

/** The states of the file screen. */
internal sealed interface ImportScreen {
    /** Reading the file (and, for a CSV or a JSON file, comparing it with the vault). */
    data object Reading : ImportScreen

    /**
     * No column of the CSV file is a password column (formats-review P6), and the column-mapping
     * screen was left without an answer: "We couldn't find columns for website, username and
     * password. Choose them yourself."
     */
    data object NeedsColumns : ImportScreen

    /** The preview; [planning] while a choice is being applied, [importing] while the import is saved. */
    data class Preview(val preview: ImportPreview, val planning: Boolean = false, val importing: Boolean = false) : ImportScreen

    /** The import was saved. */
    data class Done(val done: ImportDone) : ImportScreen

    /** The file cannot be imported, for [problem]. */
    data class Problem(val problem: ImportProblem) : ImportScreen

    /** A backup file: "Type the master password you had when you made this backup." */
    data class BackupPassword(val error: BackupError? = null, val opening: Boolean = false) : ImportScreen

    /** The backup opened: "52 accounts, backup made on 3 October 2026." */
    data class BackupSummary(val accounts: Int, val createdMs: Long, val adding: Boolean = false) : ImportScreen

    /** The backup's accounts were added. */
    data class BackupAdded(val added: Int, val skipped: Int, val renamed: Int) : ImportScreen

    /** There is no import task with a file: the screen goes back. */
    data object Gone : ImportScreen

    /** The vault locked: everything read from the file was dropped. */
    data object Locked : ImportScreen
}

/**
 * The result of an import (ux.md §5.21, "Result").
 *
 * @property added "45 accounts imported."; 0 when the file had nothing new.
 * @property alreadyAll the file had nothing new: all its [alreadyAll] accounts are here already (an
 *   import that a lock cut off from its question about the file, or a file imported before); 0 otherwise.
 * @property canUndo "Undo this import" is offered: the vault as it was is still held, and the file
 *   has not been deleted (deleting it and then undoing would lose those passwords).
 * @property undone the import was undone.
 * @property askFile "The file … still contains all these passwords unprotected. Delete it now?":
 *   the question waits for "Delete file" or "Keep".
 * @property file what became of the file once the question was answered.
 * @property manyShared "Many of these accounts share a password. Open Security to fix them one by one."
 */
internal data class ImportDone(
    val added: Int,
    val alreadyAll: Int = 0,
    val canUndo: Boolean,
    val undone: Boolean = false,
    val askFile: Boolean,
    val file: FileOutcome? = null,
    val manyShared: Boolean = false,
    val busy: Boolean = false,
)

/** What became of the imported file. */
internal enum class FileOutcome {
    /** "The file was deleted." */
    DELETED,

    /** The place does not allow deleting it: "Delete … yourself." */
    NOT_DELETED,

    /** "Keep": the file stays where it is. */
    KEPT,
}

/** Why a file cannot be imported; each has its sentence (ux.md §5.21 "Errors", formats.md §8.2). */
internal enum class ImportProblem {
    /** The document could not be opened or read: moved, deleted, or its permission is gone. */
    CANNOT_OPEN,

    /** "This is an Excel file. In Excel choose File › Save As › CSV UTF-8, then import that file." */
    EXCEL,

    /** "This is a KeePass database. In KeePassXC: Database › Export › CSV." */
    KEEPASS,

    /** No bytes, or no row at all. */
    EMPTY,

    /** Larger than `Importer.MAX_BYTES`. */
    TOO_BIG,

    /** More rows or JSON items than `Importer.MAX_ROWS`. */
    TOO_MANY,

    /** Not text, or text the importer cannot read as CSV. */
    UNREADABLE,

    /** JSON that `StrictJson` cannot read. */
    BAD_JSON,

    /** The phone ran out of memory reading it: "This file is too large to read on this phone." */
    NO_MEMORY,

    /** Bitwarden's encrypted JSON. */
    ENCRYPTED,

    /** JSON without an `items` array. */
    NOT_AN_EXPORT,

    /** A backup file that is damaged. */
    BACKUP_DAMAGED,

    /** A backup made by a newer version of the app. */
    BACKUP_NEWER,

    /** A file that starts like a backup but is not one of ours. */
    BACKUP_NOT_OURS,
}

/** What the backup's master password came to, under its field (ux.md §5.2's texts). */
internal enum class BackupError {
    /** "This password does not open this backup. Try the master password you used when you made it." */
    WRONG,

    /** "This file may be damaged, or the password is wrong." Another try is allowed. */
    WRONG_OR_DAMAGED,

    /** "Your phone does not have enough free memory to open the file. Close other apps and try again." */
    NO_MEMORY,
}

/** A short message at the bottom of the screen, shown once. */
internal enum class ImportMessage {
    /** "Couldn't save. Your phone's storage may be full." with "Try again". */
    SAVE_FAILED,

    /** "Your passwords no longer fit in one file. …" (architecture.md §3.5, TOO_LARGE). */
    TOO_LARGE,

    /** The undo could not be saved: the import stays. */
    UNDO_FAILED,
}

/** A move of the screen, made once. */
internal enum class ImportMove {
    /** Opens the column-mapping screen (`Route.ColumnMapping`). */
    COLUMNS,

    /** Leaves the import (Back). */
    LEAVE,

    /** Back to the sources, to choose another file (`Route.Import(ImportStep.SOURCES)` in place of this one). */
    SOURCES,

    /** The Security tab, to fix reused passwords. */
    SECURITY,
}
