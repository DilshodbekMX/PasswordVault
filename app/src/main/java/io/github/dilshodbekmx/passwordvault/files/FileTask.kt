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
 * @property written a write into [doc] has begun ([FileTaskStore.write]): the document may hold what
 *   this task wrote, whole or in part, so a task given up afterwards, in this process or in a new
 *   one, removes it ([FileTaskStore.abandon]). Only a backup or an export writes.
 */
data class FileTask(val kind: Kind, val step: Int, val doc: DocRef?, val written: Boolean = false) {
    /** The tasks that open the file picker. [stored] is the value kept in the settings. */
    enum class Kind(val stored: String) {
        BACKUP("backup"),
        EXPORT("export"),
        IMPORT("import"),
        RESTORE("restore"),
    }
}
