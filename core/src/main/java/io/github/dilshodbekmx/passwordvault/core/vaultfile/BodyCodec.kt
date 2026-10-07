package io.github.dilshodbekmx.passwordvault.core.vaultfile

import io.github.dilshodbekmx.passwordvault.core.model.CustomField
import io.github.dilshodbekmx.passwordvault.core.model.Draft
import io.github.dilshodbekmx.passwordvault.core.model.DraftEntry
import io.github.dilshodbekmx.passwordvault.core.model.Entry
import io.github.dilshodbekmx.passwordvault.core.model.FileRef
import io.github.dilshodbekmx.passwordvault.core.model.HistoryItem
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.core.util.Bytes
import io.github.dilshodbekmx.passwordvault.core.util.ParseError
import io.github.dilshodbekmx.passwordvault.core.util.StrictJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The plaintext body of a vault file ↔ [Vault] (architecture.md §4.3).
 *
 * **Reading** is rules R9 and R10 of §4.4. The plaintext must be strict UTF-8 without a byte-order
 * mark; trailing spaces (the padding) are stripped; what is left must be one JSON object, read with
 * [StrictJson] at most [MAX_DEPTH] deep with no key twice in an object, whose `schema` is an integer
 * equal to 1 (an integer above 1 is NEWER). Then every known key must have the type of §4.3 and every
 * required key must be there; that is all R10 checks. Ids, limits and text rules are not checked
 * here, so a stored entry is never refused for them.
 *
 * Which keys are required: at the top level `schema` and `entries`, in an entry `id`, `title`,
 * `createdAt` and `updatedAt`; every other key of those two levels takes the default of §4.3 when it
 * is missing. The draft's `entry` holds keys of the entry table and follows that table (`title`
 * required, the others defaulted). In the other objects below the two levels (the draft itself, a
 * field, a history item, an export or backup reference) §4.3 gives no default, so every key it names
 * is required: a writer always writes them all, and a later version may add keys but never drop one.
 *
 * **Unknown keys** at the top level and in each entry are kept, in the order they were read, each with
 * its value as compact JSON text ([CanonicalJson.text]): `Vault.extras` and `Entry.extras`. Anywhere
 * else they are ignored and not written back.
 *
 * **Writing** gives the writer's canonical form of §4.3, the one byte sequence for a [Vault] that
 * vector B of §4.13 pins: every key of both tables in the order of the tables, the kept unknown keys
 * after them, no white space, and [CanonicalJson]'s escapes.
 *
 * Nothing here puts a piece of the body into an exception or a [ParseError] (§5.6).
 */
internal object BodyCodec {
    /** R9: nesting at most 16 deep, objects and arrays both counted, the top object at depth 1. */
    const val MAX_DEPTH = 16

    /** The plaintext is padded with spaces to a multiple of this (§4.3). */
    const val BLOCK = 4_096

    /** The reasons of a [ParseError] that this codec makes itself; [StrictJson.Reason] gives the rest. */
    enum class Reason(val code: String) {
        /** R10: a required key is missing. */
        MISSING_KEY("missing-key"),

        /** R9 or R10: a known key's value has the wrong JSON type (an integer outside §4.3's form among them). */
        WRONG_TYPE("wrong-type"),

        /** R9: `schema` is an integer below 1. */
        BAD_SCHEMA("bad-schema"),

        /** The JSON reader failed in a way of its own; its message is never kept (§5.6). */
        PARSER_FAILURE("parser-failure"),
    }

    /** Every reason a [ParseError] from [read] can carry: a fixed list, none of it input (§5.6). */
    val REASON_CODES: Set<String> =
        (StrictJson.Reason.entries.map { it.code } + Reason.entries.map { it.code }).toSet()

    /**
     * The offset of a [ParseError] for a rule of the parsed tree (`schema` and R10): the tree keeps no
     * positions, so it names the start of the body.
     */
    private const val TREE_OFFSET = 0L

    private const val SPACE = 0x20.toByte()

    private val TOP_KEYS = setOf(
        "schema", "entries", "draft", "plainExports", "backups", "masterPasswordMatches", "securitySeenAt",
    )
    private val ENTRY_KEYS = setOf(
        "id", "title", "username", "password", "notes", "urls", "fields", "favourite", "changePending",
        "checkIgnored", "createdAt", "updatedAt", "passwordChangedAt", "deletedAt", "pendingOldPassword",
        "passwordHistory",
    )

    /** The depth of the containers whose unknown keys are kept: the top object, and an entry inside `entries`. */
    private const val TOP_DEPTH = 1
    private const val ENTRY_DEPTH = 3

    // ---------------------------------------------------------------- reading

    /** Rules R9 and R10 over a decrypted [plaintext], padding included. Never throws for any input. */
    fun read(plaintext: ByteArray): BodyResult {
        var end = plaintext.size
        while (end > 0 && plaintext[end - 1] == SPACE) end--
        val text = if (end == plaintext.size) plaintext else plaintext.copyOf(end)
        try {
            val root = try {
                StrictJson.parseUtf8Object(text, MAX_DEPTH)
            } catch (f: StrictJson.Failure) {
                return BodyResult.Damaged(ParseError(f.offset, f.reason.code))
            } catch (_: RuntimeException) { // any other failure of the reader, without its message
                return damaged(Reason.PARSER_FAILURE)
            }
            val schemaValue = root["schema"] ?: return damaged(Reason.MISSING_KEY)
            val schema = integerOrNull(schemaValue) ?: return damaged(Reason.WRONG_TYPE)
            if (schema > Vault.SCHEMA) return BodyResult.Newer(schema)
            if (schema != Vault.SCHEMA) return damaged(Reason.BAD_SCHEMA)
            return try {
                BodyResult.Opened(vault(root))
            } catch (r: Refused) {
                damaged(r.reason)
            }
        } finally {
            if (text !== plaintext) Bytes.wipe(text)
        }
    }

    private fun damaged(reason: Reason) = BodyResult.Damaged(ParseError(TREE_OFFSET, reason.code))

    /** A rule of R10 failed; caught in [read] and never seen outside this object. */
    private class Refused(val reason: Reason) : Exception(reason.code)

    private fun refuse(reason: Reason): Nothing = throw Refused(reason)

    private fun vault(o: JsonObject): Vault = Vault(
        schema = Vault.SCHEMA,
        entries = list(required(o, "entries"), ::entry),
        draft = o["draft"]?.let { if (it is JsonNull) null else draft(it) },
        plainExports = o["plainExports"]?.let { list(it, ::fileRef) } ?: emptyList(),
        backups = o["backups"]?.let { list(it, ::fileRef) } ?: emptyList(),
        masterPasswordMatches = o["masterPasswordMatches"]?.let { list(it, ::string) } ?: emptyList(),
        securitySeenAt = o["securitySeenAt"]?.let(::integerOrNullValue),
        extras = extras(o, TOP_KEYS),
    )

    private fun entry(e: JsonElement): Entry {
        val o = obj(e)
        return Entry(
            id = string(required(o, "id")),
            title = string(required(o, "title")),
            username = o["username"]?.let(::string) ?: "",
            password = o["password"]?.let(::string) ?: "",
            notes = o["notes"]?.let(::string) ?: "",
            urls = o["urls"]?.let { list(it, ::string) } ?: emptyList(),
            fields = o["fields"]?.let { list(it, ::field) } ?: emptyList(),
            favourite = o["favourite"]?.let(::boolean) ?: false,
            changePending = o["changePending"]?.let(::boolean) ?: false,
            checkIgnored = o["checkIgnored"]?.let(::boolean) ?: false,
            createdAt = integer(required(o, "createdAt")),
            updatedAt = integer(required(o, "updatedAt")),
            passwordChangedAt = o["passwordChangedAt"]?.let(::integerOrNullValue),
            deletedAt = o["deletedAt"]?.let(::integerOrNullValue),
            pendingOldPassword = o["pendingOldPassword"]?.let(::stringOrNull),
            passwordHistory = o["passwordHistory"]?.let { list(it, ::historyItem) } ?: emptyList(),
            extras = extras(o, ENTRY_KEYS),
        )
    }

    private fun draft(e: JsonElement): Draft {
        val o = obj(e)
        return Draft(
            entryId = stringOrNull(required(o, "entryId")),
            savedAt = integer(required(o, "savedAt")),
            entry = draftEntry(required(o, "entry")),
        )
    }

    private fun draftEntry(e: JsonElement): DraftEntry {
        val o = obj(e)
        return DraftEntry(
            title = string(required(o, "title")),
            username = o["username"]?.let(::string) ?: "",
            password = o["password"]?.let(::string) ?: "",
            notes = o["notes"]?.let(::string) ?: "",
            urls = o["urls"]?.let { list(it, ::string) } ?: emptyList(),
            fields = o["fields"]?.let { list(it, ::field) } ?: emptyList(),
            favourite = o["favourite"]?.let(::boolean) ?: false,
        )
    }

    private fun field(e: JsonElement): CustomField {
        val o = obj(e)
        return CustomField(string(required(o, "name")), string(required(o, "value")), boolean(required(o, "secret")))
    }

    private fun historyItem(e: JsonElement): HistoryItem {
        val o = obj(e)
        return HistoryItem(string(required(o, "password")), integer(required(o, "replacedAt")))
    }

    private fun fileRef(e: JsonElement): FileRef {
        val o = obj(e)
        return FileRef(string(required(o, "uri")), string(required(o, "name")), integer(required(o, "savedAt")))
    }

    /** The unknown keys of [o] in the order they were read, each with its value as compact JSON text. */
    private fun extras(o: JsonObject, known: Set<String>): Map<String, String> {
        var kept: LinkedHashMap<String, String>? = null
        for ((key, value) in o) {
            if (key in known) continue
            val map = kept ?: LinkedHashMap<String, String>().also { kept = it }
            map[key] = CanonicalJson.text(value)
        }
        return kept ?: emptyMap()
    }

    private fun required(o: JsonObject, key: String): JsonElement = o[key] ?: refuse(Reason.MISSING_KEY)

    private fun obj(e: JsonElement): JsonObject = e as? JsonObject ?: refuse(Reason.WRONG_TYPE)

    private fun <T> list(e: JsonElement, item: (JsonElement) -> T): List<T> {
        val array = e as? JsonArray ?: refuse(Reason.WRONG_TYPE)
        val out = ArrayList<T>(array.size)
        for (element in array) out.add(item(element))
        return out
    }

    private fun string(e: JsonElement): String =
        if (e is JsonPrimitive && e.isString) e.content else refuse(Reason.WRONG_TYPE)

    private fun stringOrNull(e: JsonElement): String? = if (e is JsonNull) null else string(e)

    private fun boolean(e: JsonElement): Boolean {
        if (e is JsonPrimitive && !e.isString && e !is JsonNull) {
            when (e.content) {
                "true" -> return true
                "false" -> return false
            }
        }
        refuse(Reason.WRONG_TYPE)
    }

    private fun integer(e: JsonElement): Long = integerOrNull(e) ?: refuse(Reason.WRONG_TYPE)

    /** An "integer or null" key: null for JSON null, else an integer as [integer] reads it. */
    private fun integerOrNullValue(e: JsonElement): Long? = if (e is JsonNull) null else integer(e)

    /**
     * The value of [e] when it is an integer of §4.3: a number that matches `-?(0|[1-9][0-9]{0,18})`
     * and fits a signed 64-bit value. A fraction, an exponent or a longer number is the wrong type.
     */
    private fun integerOrNull(e: JsonElement): Long? {
        if (e !is JsonPrimitive || e.isString || e is JsonNull) return null
        val s = e.content
        val start = if (s.startsWith('-')) 1 else 0
        val digits = s.length - start
        if (digits < 1 || digits > 19) return null
        if (s[start] == '0' && digits > 1) return null
        for (i in start until s.length) if (s[i] !in '0'..'9') return null
        return s.toLongOrNull()
    }

    // ---------------------------------------------------------------- writing

    /**
     * The canonical body of [vault] as UTF-8 bytes, without the padding (§4.3).
     *
     * @throws IllegalArgumentException when [vault] has no canonical form in version 1: its `schema`
     *   is not 1, or a kept unknown key has the name of a known key, or its value is not JSON that
     *   fits the depth of R9 where it stands. The reader never makes such a [Vault]; the message
     *   holds no piece of it.
     */
    fun write(vault: Vault): ByteArray {
        require(vault.schema == Vault.SCHEMA) { "a version 1 writer writes schema 1 only" }
        val w = CanonicalJson()
        try {
            writeVault(w, vault)
            return w.utf8()
        } finally {
            w.wipe()
        }
    }

    /** [json] followed by as many spaces as make its length a multiple of [BLOCK]: 0 to 4,095 (§4.3). */
    fun pad(json: ByteArray): ByteArray {
        val size = (json.size + BLOCK - 1) / BLOCK * BLOCK
        val out = json.copyOf(size)
        out.fill(SPACE, json.size, size)
        return out
    }

    private fun writeVault(w: CanonicalJson, v: Vault) {
        w.raw('{')
        w.key("schema", first = true)
        w.integer(Vault.SCHEMA)
        w.key("entries")
        w.list(v.entries) { writeEntry(w, it) }
        w.key("draft")
        val draft = v.draft
        if (draft == null) w.nul() else writeDraft(w, draft)
        w.key("plainExports")
        w.list(v.plainExports) { writeFileRef(w, it) }
        w.key("backups")
        w.list(v.backups) { writeFileRef(w, it) }
        w.key("masterPasswordMatches")
        w.list(v.masterPasswordMatches) { w.string(it) }
        w.key("securitySeenAt")
        w.integerOrNull(v.securitySeenAt)
        writeExtras(w, v.extras, TOP_KEYS, TOP_DEPTH)
        w.raw('}')
    }

    private fun writeEntry(w: CanonicalJson, e: Entry) {
        w.raw('{')
        w.key("id", first = true)
        w.string(e.id)
        w.key("title")
        w.string(e.title)
        w.key("username")
        w.string(e.username)
        w.key("password")
        w.string(e.password)
        w.key("notes")
        w.string(e.notes)
        w.key("urls")
        w.list(e.urls) { w.string(it) }
        w.key("fields")
        w.list(e.fields) { writeField(w, it) }
        w.key("favourite")
        w.boolean(e.favourite)
        w.key("changePending")
        w.boolean(e.changePending)
        w.key("checkIgnored")
        w.boolean(e.checkIgnored)
        w.key("createdAt")
        w.integer(e.createdAt)
        w.key("updatedAt")
        w.integer(e.updatedAt)
        w.key("passwordChangedAt")
        w.integerOrNull(e.passwordChangedAt)
        w.key("deletedAt")
        w.integerOrNull(e.deletedAt)
        w.key("pendingOldPassword")
        w.stringOrNull(e.pendingOldPassword)
        w.key("passwordHistory")
        w.list(e.passwordHistory) { writeHistoryItem(w, it) }
        writeExtras(w, e.extras, ENTRY_KEYS, ENTRY_DEPTH)
        w.raw('}')
    }

    private fun writeDraft(w: CanonicalJson, d: Draft) {
        w.raw('{')
        w.key("entryId", first = true)
        w.stringOrNull(d.entryId)
        w.key("savedAt")
        w.integer(d.savedAt)
        w.key("entry")
        val e = d.entry
        w.raw('{')
        w.key("title", first = true)
        w.string(e.title)
        w.key("username")
        w.string(e.username)
        w.key("password")
        w.string(e.password)
        w.key("notes")
        w.string(e.notes)
        w.key("urls")
        w.list(e.urls) { w.string(it) }
        w.key("fields")
        w.list(e.fields) { writeField(w, it) }
        w.key("favourite")
        w.boolean(e.favourite)
        w.raw('}')
        w.raw('}')
    }

    private fun writeField(w: CanonicalJson, f: CustomField) {
        w.raw('{')
        w.key("name", first = true)
        w.string(f.name)
        w.key("value")
        w.string(f.value)
        w.key("secret")
        w.boolean(f.secret)
        w.raw('}')
    }

    private fun writeHistoryItem(w: CanonicalJson, h: HistoryItem) {
        w.raw('{')
        w.key("password", first = true)
        w.string(h.password)
        w.key("replacedAt")
        w.integer(h.replacedAt)
        w.raw('}')
    }

    private fun writeFileRef(w: CanonicalJson, f: FileRef) {
        w.raw('{')
        w.key("uri", first = true)
        w.string(f.uri)
        w.key("name")
        w.string(f.name)
        w.key("savedAt")
        w.integer(f.savedAt)
        w.raw('}')
    }

    /**
     * The kept unknown keys of an object at [depth], after its known keys, as they were read. Each
     * value is read again and written in its compact form, so the body stays canonical and within
     * the depth of R9 whatever text a caller put into `extras`.
     */
    private fun writeExtras(w: CanonicalJson, extras: Map<String, String>, known: Set<String>, depth: Int) {
        for ((key, text) in extras) {
            require(key !in known) { "a kept unknown key has the name of a known key" }
            val value = try {
                StrictJson.parse(text, MAX_DEPTH - depth)
            } catch (f: StrictJson.Failure) {
                throw IllegalArgumentException("a kept unknown value is not JSON that fits there: " + f.reason.code)
            }
            w.key(key)
            w.element(value)
        }
    }
}

/**
 * The canonical JSON text of architecture.md §4.3, written into a char buffer that can be wiped:
 * no white space outside strings; in strings `"` and `\` escaped with a backslash, U+0008, U+0009,
 * U+000A, U+000C and U+000D as `\b`, `\t`, `\n`, `\f`, `\r`, every other code point below U+0020 as
 * `\u00xx` with lower-case hex digits, everything else as itself; integers in plain decimal; `true`,
 * `false`, `null`; a value read by [StrictJson] written with the same tokens and the same spelling
 * of every number. This is what kotlinx-serialization 1.11.0's `JsonElement.toString()` writes.
 *
 * [utf8] encodes the text as UTF-8, an unpaired surrogate as the UTF-8 of U+FFFD (EF BF BD), as
 * every writer does (formats-review P7); a UTF-8 encoder of the JDK would write `?` instead.
 */
internal class CanonicalJson(capacity: Int = 8_192) {
    private var buf = CharArray(capacity)
    private var size = 0

    fun raw(c: Char) {
        ensure(1)
        buf[size++] = c
    }

    private fun raw(s: String) {
        ensure(s.length)
        s.toCharArray(buf, size, 0, s.length)
        size += s.length
    }

    /** `"key":`, after a comma unless [first]. */
    fun key(name: String, first: Boolean = false) {
        if (!first) raw(',')
        string(name)
        raw(':')
    }

    fun string(s: String) {
        ensure(s.length + 2)
        raw('"')
        for (c in s) {
            when {
                c == '"' -> raw("\\\"")
                c == '\\' -> raw("\\\\")
                c >= ' ' -> raw(c)
                c == '\b' -> raw("\\b")
                c == '\t' -> raw("\\t")
                c == '\n' -> raw("\\n")
                c == '\u000C' -> raw("\\f")
                c == '\r' -> raw("\\r")
                else -> {
                    raw("\\u00")
                    raw(HEX[c.code shr 4])
                    raw(HEX[c.code and 0xF])
                }
            }
        }
        raw('"')
    }

    fun stringOrNull(s: String?) = if (s == null) nul() else string(s)

    fun integer(n: Long) = raw(n.toString())

    fun integerOrNull(n: Long?) = if (n == null) nul() else integer(n)

    fun boolean(b: Boolean) = raw(if (b) "true" else "false")

    fun nul() = raw("null")

    fun <T> list(items: List<T>, item: (T) -> Unit) {
        raw('[')
        for (i in items.indices) {
            if (i > 0) raw(',')
            item(items[i])
        }
        raw(']')
    }

    /** A value read by [StrictJson], in compact form: a number keeps its spelling, an object its order. */
    fun element(e: JsonElement) {
        when (e) {
            is JsonObject -> {
                raw('{')
                var first = true
                for ((k, v) in e) {
                    key(k, first)
                    first = false
                    element(v)
                }
                raw('}')
            }
            is JsonArray -> list(e) { element(it) }
            is JsonNull -> nul()
            is JsonPrimitive -> if (e.isString) string(e.content) else raw(e.content)
        }
    }

    /** The text written so far. */
    fun text(): String = String(buf, 0, size)

    /** The text written so far as UTF-8, with U+FFFD for an unpaired surrogate. */
    fun utf8(): ByteArray {
        var length = 0
        var i = 0
        while (i < size) {
            val c = buf[i]
            length += when {
                c.code < 0x80 -> 1
                c.code < 0x800 -> 2
                Character.isHighSurrogate(c) && i + 1 < size && Character.isLowSurrogate(buf[i + 1]) -> {
                    i++
                    4
                }
                else -> 3 // the rest of the BMP, and an unpaired surrogate written as U+FFFD
            }
            i++
        }
        val out = ByteArray(length)
        var o = 0
        i = 0
        while (i < size) {
            val c = buf[i]
            val code = c.code
            when {
                code < 0x80 -> out[o++] = code.toByte()
                code < 0x800 -> {
                    out[o++] = (0xC0 or (code shr 6)).toByte()
                    out[o++] = (0x80 or (code and 0x3F)).toByte()
                }
                Character.isHighSurrogate(c) && i + 1 < size && Character.isLowSurrogate(buf[i + 1]) -> {
                    val cp = Character.toCodePoint(c, buf[i + 1])
                    out[o++] = (0xF0 or (cp shr 18)).toByte()
                    out[o++] = (0x80 or ((cp shr 12) and 0x3F)).toByte()
                    out[o++] = (0x80 or ((cp shr 6) and 0x3F)).toByte()
                    out[o++] = (0x80 or (cp and 0x3F)).toByte()
                    i++
                }
                else -> {
                    val bmp = if (Character.isSurrogate(c)) REPLACEMENT else code
                    out[o++] = (0xE0 or (bmp shr 12)).toByte()
                    out[o++] = (0x80 or ((bmp shr 6) and 0x3F)).toByte()
                    out[o++] = (0x80 or (bmp and 0x3F)).toByte()
                }
            }
            i++
        }
        return out
    }

    /** Overwrites the buffer: it held account data (best effort, architecture.md §5.6). */
    fun wipe() {
        buf.fill('\u0000')
        size = 0
    }

    private fun ensure(more: Int) {
        if (size + more <= buf.size) return
        var capacity = buf.size * 2
        while (capacity < size + more) capacity *= 2
        val old = buf
        buf = old.copyOf(capacity)
        old.fill('\u0000')
    }

    companion object {
        private val HEX = "0123456789abcdef".toCharArray()
        private const val REPLACEMENT = 0xFFFD

        /** [e] as compact JSON text: the form of a kept unknown value (§4.3, `Entry.extras`). */
        fun text(e: JsonElement): String {
            val w = CanonicalJson(64)
            try {
                w.element(e)
                return w.text()
            } finally {
                w.wipe()
            }
        }
    }
}
