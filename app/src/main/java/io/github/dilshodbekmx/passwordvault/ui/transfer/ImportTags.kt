package io.github.dilshodbekmx.passwordvault.ui.transfer

import io.github.dilshodbekmx.passwordvault.core.formats.ColumnMeaning
import java.util.Locale

/**
 * The test tags of the import's screens, exposed as resource ids (`testTagsAsResourceId`), so that
 * the device tests and the emulator scripts find them whatever the language (build plan §1 rule 6;
 * tools/emu/app.sh).
 */
internal object ImportTags {
    // The sources and the Google help.
    const val SOURCES = "pv.import.sources"
    const val HELP = "pv.import.help"
    const val HELP_CANT_FIND = "pv.import.help.cantFind"
    const val HELP_SETTINGS = "pv.import.help.settings"
    const val CHOOSE_FILE = "pv.import.chooseFile"
    const val NO_PICKER = "pv.import.noPicker"
    const val BACK = "pv.import.back"

    fun source(source: ImportSource): String = "pv.import.source." + source.name.lowercase(Locale.ROOT)

    // The file's screen.
    const val READING = "pv.import.reading"
    const val PAUSED = "pv.import.paused"
    const val FILE_NAME = "pv.import.fileName"
    const val PREVIEW = "pv.import.preview"
    const val SAMPLE = "pv.import.sample"
    const val ENCODING = "pv.import.encoding"
    const val READ_AS = "pv.import.readAs"
    const val FOUND = "pv.import.found"
    const val ALREADY_HERE = "pv.import.alreadyHere"
    const val CONFLICTS = "pv.import.conflicts"
    const val WRONG_ROWS = "pv.import.wrongRows"
    const val INCLUDE_WRONG = "pv.import.includeWrong"
    const val LEFT_OUT = "pv.import.leftOut"
    const val CHANGES = "pv.import.changes"
    const val RELATED = "pv.import.related"
    const val KEEP_SEPARATE = "pv.import.keepSeparate"
    const val TIDY = "pv.import.tidy"
    const val COLUMNS = "pv.import.columns"
    const val TOO_LARGE = "pv.import.tooLarge"
    const val IMPORT = "pv.import.import"
    const val NOTHING_NEW = "pv.import.nothingNew"
    const val DONE = "pv.import.done"
    const val UNDO = "pv.import.undo"
    const val FILE_QUESTION = "pv.import.fileQuestion"
    const val DELETE_FILE = "pv.import.deleteFile"
    const val KEEP_FILE = "pv.import.keepFile"
    const val FILE_OUTCOME = "pv.import.fileOutcome"
    const val SHARED = "pv.import.shared"
    const val OPEN_SECURITY = "pv.import.openSecurity"
    const val FINISH = "pv.import.finish"
    const val PROBLEM = "pv.import.problem"
    const val CHOOSE_ANOTHER = "pv.import.chooseAnother"
    const val NEEDS_COLUMNS = "pv.import.needsColumns"
    const val MESSAGE = "pv.import.message"

    // A backup file.
    const val BACKUP_PASSWORD = "pv.import.backup.password"
    const val BACKUP_OPEN = "pv.import.backup.open"
    const val BACKUP_ERROR = "pv.import.backup.error"
    const val BACKUP_SUMMARY = "pv.import.backup.summary"
    const val BACKUP_ADD = "pv.import.backup.add"
    const val BACKUP_ADDED = "pv.import.backup.added"

    // The column mapping.
    const val MAPPING = "pv.import.mapping"
    const val MAPPING_CONTINUE = "pv.import.mapping.continue"
    const val MAPPING_NO_PASSWORD = "pv.import.mapping.noPassword"

    /** The drop-down list of the column at [index], from 0. */
    fun column(index: Int): String = "pv.import.column.$index"

    /** The first value shown under the column at [index]. */
    fun sample(index: Int): String = "pv.import.column.$index.sample"

    /** The item of [meaning] in an open drop-down list. */
    fun meaning(meaning: ColumnMeaning): String = "pv.import.meaning." + meaning.name.lowercase(Locale.ROOT)
}
