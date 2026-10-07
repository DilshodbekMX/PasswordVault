package io.github.dilshodbekmx.passwordvault.files

/**
 * An unfinished backup, export, import or restore (ux.md §4, "Unfinished file tasks"; lock.md
 * §10.3): kept in the settings, outside the vault, so that it survives a lock and a new process.
 * Nothing secret: no password, no file content.
 *
 * @property kind which task.
 * @property step the task's own step, the `step` of its route (`Route.Export(step)`,
 *   `Route.Import(step)`); the task's screens give the numbers their meaning.
 * @property doc the chosen document, once the picker has returned one; recorded with a persistable
 *   permission before anything is written (ux.md §4 item 1).
 */
data class FileTask(val kind: Kind, val step: Int, val doc: DocRef?) {
    /** The tasks that open the file picker. [stored] is the value kept in the settings. */
    enum class Kind(val stored: String) {
        BACKUP("backup"),
        EXPORT("export"),
        IMPORT("import"),
        RESTORE("restore"),
    }
}
