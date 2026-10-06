package pv.formats

/**
 * Version 1 entry model (docs/design/ux.md §2). Times are epoch milliseconds (UTC).
 * Pure Kotlin: no Android types, so every format class can be unit-tested on the JVM.
 */
data class CustomField(val name: String, val value: String, val secret: Boolean)

/** One earlier password and the time it stopped being the current one; newest first, at most 5 kept. */
data class HistoryItem(val password: String, val replacedAt: Long)

data class Entry(
    val id: String,
    val title: String,
    val username: String = "",
    val password: String = "",
    val urls: List<String> = emptyList(),
    val notes: String = "",
    val fields: List<CustomField> = emptyList(),
    val favourite: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
    val passwordChangedAt: Long? = null,
    val history: List<HistoryItem> = emptyList(),
    val changePending: Boolean = false,
    val checkIgnored: Boolean = false,
    /**
     * Keys this build does not understand, read from our JSON extension object and written back
     * unchanged (raw JSON text per key), so an older app never drops what a newer one wrote.
     */
    val extras: Map<String, String> = emptyMap(),
)

/** ux.md §2 limits, in UTF-16 units here (the app counts code points; the difference only matters for emoji). */
object Limits {
    const val TITLE = 200; const val USERNAME = 500; const val PASSWORD = 1000; const val URLS = 10; const val URL = 2048
    const val NOTES = 10_000; const val FIELDS = 20; const val FIELD_NAME = 100; const val FIELD_VALUE = 1000

    /** Reasons this entry cannot be stored as it is; empty when it fits. */
    fun problems(e: Entry): List<String> = buildList {
        if (e.title.length > TITLE) add("title longer than $TITLE")
        if (e.username.length > USERNAME) add("username longer than $USERNAME")
        if (e.password.length > PASSWORD) add("password longer than $PASSWORD")
        if (e.urls.size > URLS) add("more than $URLS websites")
        if (e.urls.any { it.length > URL }) add("website longer than $URL")
        if (e.notes.length > NOTES) add("notes longer than $NOTES")
        if (e.fields.size > FIELDS) add("more than $FIELDS extra fields")
        if (e.fields.any { it.name.length > FIELD_NAME || it.value.length > FIELD_VALUE }) add("extra field too long")
    }

    private fun isControl(c: Char) = c < ' ' || c == '\u007F'

    /** Removes control characters (line breaks stay where allowed) and unpaired surrogates; counts removals. */
    fun clean(s0: String, keepLineBreaks: Boolean, removed: IntArray): String {
        val s = if (keepLineBreaks && s0.indexOf('\r') >= 0) s0.replace("\r\n", "\n").replace('\r', '\n') else s0
        if (s.none { isControl(it) || Character.isSurrogate(it) }) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                keepLineBreaks && c == '\n' -> sb.append(c)
                Character.isHighSurrogate(c) && i + 1 < s.length && Character.isLowSurrogate(s[i + 1]) -> { sb.append(c).append(s[i + 1]); i++ }
                isControl(c) || Character.isSurrogate(c) -> removed[0]++
                else -> sb.append(c)
            }
            i++
        }
        return sb.toString()
    }

    fun clean(e: Entry, removed: IntArray): Entry = e.copy(
        title = clean(e.title, false, removed), username = clean(e.username, false, removed),
        password = clean(e.password, false, removed), urls = e.urls.map { clean(it, false, removed) },
        notes = clean(e.notes, true, removed),
        fields = e.fields.map { it.copy(name = clean(it.name, false, removed), value = clean(it.value, true, removed)) },
    )
}
