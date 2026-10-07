package io.github.dilshodbekmx.passwordvault.core.storage

/**
 * The file operations the vault store is built on (architecture.md §3.3). Paths are absolute
 * strings. `OsFiles` (package 2.7) implements them with `Os.rename` and `Os.fsync`, never
 * `AtomicFile`, DataStore or `Files.move` [V-S7]; `FakeFiles` of :testing (package 2.3) keeps
 * "handed over" and "durable" apart and can stop at every boundary.
 *
 * Every failure throws `IOException`; nothing is logged and swallowed.
 */
interface FilePrimitives {
    /** The names in [dir], or null when the directory does not exist. Throws when it exists but cannot be listed. */
    fun list(dir: String): List<String>?

    /**
     * At most [maxBytes] + 1 bytes of the file at [path]: a result longer than [maxBytes] means
     * "too long", not an error, so a reader never reads more than the cap plus one byte.
     */
    fun read(path: String, maxBytes: Int): ByteArray

    /** Creates [path] exclusively (it fails if the name exists), writes [bytes], flushes, fsyncs and closes. */
    fun writeNew(path: String, bytes: ByteArray)

    fun exists(path: String): Boolean

    /** rename(2): replaces an existing target, so callers check first [V-S1]. */
    fun rename(from: String, to: String)

    /** Makes the entries of [dir] durable: open(O_RDONLY), fsync, close. */
    fun syncDir(dir: String)

    fun delete(path: String)

    fun mkdir(dir: String)

    /** Deletes [dir] and everything in it. */
    fun deleteTree(dir: String)
}
