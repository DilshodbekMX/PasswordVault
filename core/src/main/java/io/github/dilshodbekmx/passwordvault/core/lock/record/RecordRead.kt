package io.github.dilshodbekmx.passwordvault.core.lock.record

import io.github.dilshodbekmx.passwordvault.core.util.ParseError

/**
 * The five results of reading a lock record (lock.md §5.3). Every result other than [Ok] still
 * ends at a screen where the master password works (lock.md I10).
 */
sealed interface RecordRead<out T> {
    data class Ok<T>(val record: T) : RecordRead<T>

    /** The file does not exist (`ENOENT`). */
    data object Missing : RecordRead<Nothing>

    /**
     * The magic, the length, the hash, the JSON or a field rule is wrong. [error] says which rule,
     * at which offset, never with a piece of the record (architecture.md §5.6). Never repaired in place.
     */
    data class Damaged(val error: ParseError) : RecordRead<Nothing>

    /** Framing and hash are right but `"v"` is above 1: a newer app owns it. Never rewritten; it blocks the key sweep. */
    data object Newer : RecordRead<Nothing>

    /** Any other I/O error. It may read fine next time, so it is not rewritten automatically. */
    data object Unreadable : RecordRead<Nothing>
}
