package io.github.dilshodbekmx.passwordvault.core.lock.record

/**
 * One record of one vault (architecture.md §3.4): `FileRecordStore` (package 2.10) is `RecordCodec`
 * over [RecordFiles]. The caller holds the lock mutex (architecture.md §5.2).
 */
interface RecordStore<T> {
    /** The five results of lock.md §5.3. */
    fun read(): RecordRead<T>

    /** Writes the whole record. Throws `IOException`: not written. */
    fun write(record: T)
}
