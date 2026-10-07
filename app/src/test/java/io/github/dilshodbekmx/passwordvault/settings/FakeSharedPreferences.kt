package io.github.dilshodbekmx.passwordvault.settings

import android.content.SharedPreferences

/**
 * The settings file on "disk", for the JVM tests of [PrefsAppSettings]: what the last successful
 * commit wrote. It outlives the [FakeSharedPreferences] made over it, so a new one is a restart of
 * the process. [failWrites] makes every commit fail as a full or broken disk does; [commits] counts
 * the commits that wrote.
 */
class FakePrefsFile(initial: Map<String, Any> = emptyMap()) {
    /** The entries on disk, each value a String, Int, Long, Float, Boolean or Set<String>. */
    var stored: Map<String, Any> = copy(initial)

    var failWrites: Boolean = false

    var commits: Int = 0
        private set

    internal fun write(entries: Map<String, Any>): Boolean {
        if (failWrites) return false
        stored = copy(entries)
        commits++
        return true
    }

    internal companion object {
        fun copy(entries: Map<String, Any>): Map<String, Any> =
            entries.mapValues { (_, v) -> if (v is Set<*>) v.toSet() else v }
    }
}

/**
 * A `SharedPreferences` that behaves as Android's `SharedPreferencesImpl` in what [PrefsAppSettings]
 * relies on: it loads the file when made; `getAll()` returns a copy; a getter of the wrong type
 * throws `ClassCastException`; an editor applies `clear()` first, then its puts and removes, to the
 * in-memory map, and `commit()` changes that map *before* it writes the file, so a failed write
 * leaves memory ahead of the disk (as Android does) and returns false. Not thread-safe: the code
 * under test calls it under its own lock.
 */
class FakeSharedPreferences(private val file: FakePrefsFile) : SharedPreferences {
    private val memory: MutableMap<String, Any> = HashMap(FakePrefsFile.copy(file.stored))

    override fun getAll(): MutableMap<String, *> = HashMap(memory)

    override fun getString(key: String, defValue: String?): String? = memory[key]?.let { it as String } ?: defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
        memory[key]?.let { it as Set<String> }?.toMutableSet() ?: defValues

    override fun getInt(key: String, defValue: Int): Int = memory[key]?.let { it as Int } ?: defValue

    override fun getLong(key: String, defValue: Long): Long = memory[key]?.let { it as Long } ?: defValue

    override fun getFloat(key: String, defValue: Float): Float = memory[key]?.let { it as Float } ?: defValue

    override fun getBoolean(key: String, defValue: Boolean): Boolean = memory[key]?.let { it as Boolean } ?: defValue

    override fun contains(key: String): Boolean = key in memory

    override fun edit(): SharedPreferences.Editor = Editor()

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit

    private inner class Editor : SharedPreferences.Editor {
        private val changes = LinkedHashMap<String, Any?>() // null: remove
        private var clear = false

        private fun put(key: String, value: Any?): SharedPreferences.Editor = apply { changes[key] = value }

        override fun putString(key: String, value: String?) = put(key, value)

        override fun putStringSet(key: String, values: MutableSet<String>?) = put(key, values?.toSet())

        override fun putInt(key: String, value: Int) = put(key, value)

        override fun putLong(key: String, value: Long) = put(key, value)

        override fun putFloat(key: String, value: Float) = put(key, value)

        override fun putBoolean(key: String, value: Boolean) = put(key, value)

        override fun remove(key: String) = put(key, null)

        override fun clear(): SharedPreferences.Editor = apply { clear = true }

        override fun commit(): Boolean {
            if (clear) memory.clear()
            for ((key, value) in changes) if (value == null) memory.remove(key) else memory[key] = value
            return file.write(memory)
        }

        override fun apply() {
            commit()
        }
    }
}
