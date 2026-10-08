package io.github.dilshodbekmx.passwordvault.core.formats

import io.github.dilshodbekmx.passwordvault.core.model.CustomField
import io.github.dilshodbekmx.passwordvault.core.model.EditTexts
import io.github.dilshodbekmx.passwordvault.core.model.Entry
import io.github.dilshodbekmx.passwordvault.core.model.EntryRules
import io.github.dilshodbekmx.passwordvault.core.model.HistoryItem
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.core.util.Bytes
import io.github.dilshodbekmx.passwordvault.core.util.ParseError
import io.github.dilshodbekmx.passwordvault.core.util.StrictJson
import io.github.dilshodbekmx.passwordvault.core.util.TextHygiene
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The JSON export and its reader (formats.md §5): Bitwarden's unencrypted layout
 * (`{"encrypted": false, "folders": [], "items": […]}`), which Bitwarden, Proton Pass and KeePassXC
 * import, with one object `passwordVault` at the top and one in each item for what Bitwarden has no
 * key for. Those readers ignore keys they do not know, so the same file is this app's own JSON,
 * which it reads back without loss.
 *
 * **Writing** ([export]).
 * - UTF-8 without a byte-order mark, two-space indent, LF, a final newline. In strings `"` and `\`
 *   are escaped, U+0000 to U+001F as `\b`, `\t`, `\n`, `\f`, `\r` or `\u00xx`, and everything else is
 *   written as itself. Unpaired surrogates become U+FFFD and are counted (formats.md §2 rule 8).
 * - Every account is a login item (`"type": 1`). Times are ISO 8601 in UTC with milliseconds. An
 *   empty note, username or password is `null`, as Bitwarden writes it. `folders` is always there
 *   and empty.
 * - Websites: an Android app link is written in Bitwarden's form `androidapp://<package>`; when the
 *   list in `login.uris` then differs from the entry's own, the entry's own is kept in
 *   `passwordVault.urls`.
 * - The item's `passwordVault` object: `passwordChangedAt`, `changePending`, `checkIgnored`,
 *   `pendingOldPassword` (always, `null` when there is none; D-10c), `urls` when needed, and then,
 *   unchanged, every key of the entry that this version does not know ([Entry.extras]). The top
 *   object's `passwordVault` carries `format`, `version`, `exportedAt`, `generator` and, unchanged,
 *   the vault's own unknown keys ([Vault.extras]; formats-review P11).
 * - **A pending change** (D-10d): `login.password` is the new password and
 *   `passwordVault.pendingOldPassword` the old one.
 * - **Deleted accounts are not written** (D-10e), so `deletedDate` is always `null`.
 *
 * **Reading** ([read]): Bitwarden's files and our own (formats.md §5.2, formats-review P2, P3, P11).
 * - The bytes must be strict UTF-8; every leading byte-order mark is removed first. They are parsed
 *   by `StrictJson`: no key twice, at most 32 levels deep. A failure is a [ParseError]: where and
 *   why, never a piece of the file (architecture.md §5.6).
 * - Only the boolean `true` marks a file as encrypted. `items` must be an array of at most
 *   [MAX_ITEMS] elements.
 * - `passwordVault.format` identifies our own file: then the item's extension is read, and the ids
 *   are returned as written (`VaultEdits.addImported` keeps one only when it is a valid UUID that is
 *   free, formats-review P1). In a foreign file every id is new, a folder becomes the visible field
 *   "Folder", `login.totp` the hidden field "TOTP", a card, an identity or an SSH key an entry whose
 *   fields are the item's values (hidden when the key looks secret), and the previous passwords are
 *   cut to the newest 5.
 * - An item without a name gets one as on the CSV path ([titleFor]).
 * - Everything not imported is listed ([NotImported]), never dropped silently.
 *
 * The reader applies none of the importer's rules: control characters, limits, the website rule and
 * the trimming of names are `Importer`'s, for every format alike (formats.md §8.4).
 */
object BitwardenJson {
    /** The key of our extension object, at the top and in each item. */
    const val EXTENSION = "passwordVault"

    /** `passwordVault.format` of our own files. */
    const val FORMAT = "passwordvault-export"

    /** `passwordVault.version` that this version writes. */
    const val VERSION = 1

    /** A file may nest this deep (formats.md §5.2); our own needs far less. */
    const val MAX_DEPTH = 32

    /** The row limit of formats.md §8.1, applied to `items` (formats-review P11). */
    const val MAX_ITEMS = 10_000

    /** The name of the visible field a foreign file's folder becomes. */
    const val FOLDER_FIELD = "Folder"

    /** The name of the hidden field a foreign file's one-time-code secret becomes. */
    const val TOTP_FIELD = "TOTP"

    private const val ANDROID_APP = "androidapp://"
    private const val CHROME_APP = "android://"

    /**
     * The keys of a vault file's entry object (architecture.md §4.3). None of them is ever kept as an
     * unknown key of an entry: the vault's writer refuses such an entry.
     */
    private val VAULT_ENTRY_KEYS = setOf(
        "id", "title", "username", "password", "notes", "urls", "fields", "favourite", "changePending",
        "checkIgnored", "createdAt", "updatedAt", "passwordChangedAt", "deletedAt", "pendingOldPassword",
        "passwordHistory",
    )

    /** The top-level keys of a vault file (architecture.md §4.3): never kept as unknown keys of a vault. */
    private val VAULT_TOP_KEYS = setOf(
        "schema", "entries", "draft", "plainExports", "backups", "masterPasswordMatches", "securitySeenAt",
    )

    /** The keys this version writes into the top object's extension. */
    private val ROOT_EXTENSION_KEYS = setOf("format", "version", "exportedAt", "generator")

    /**
     * How deep an unknown value may nest and still fit a vault file, whose body is at most 16 levels
     * deep (architecture.md §4.4 R9): 13 inside an entry (itself at level 3), 15 at the top.
     */
    private const val ENTRY_EXTRA_DEPTH = 13
    private const val VAULT_EXTRA_DEPTH = 15

    /** Keys of a card, an identity or an SSH key whose values are hidden fields (formats.md §5.2). */
    private val LOOKS_SECRET = Regex("(?i)(number|code|cvv|ssn|passport|license|licence|private|pin|secret|password|key)")

    // ------------------------------------------------------------------ writing

    /** A JSON export: the file's bytes and what the writer counted. */
    class Written(
        val bytes: ByteArray,
        /** The accounts written: every account outside Recently deleted. */
        val items: Int,
        /** Unpaired surrogates written as U+FFFD. */
        val replacedSurrogates: Int,
    ) {
        override fun toString(): String = "Written(bytes=${bytes.size}, items=$items, replacedSurrogates=$replacedSurrogates)"
    }

    /** Chrome's `android://<certificate hash>@<package>/` as Bitwarden's `androidapp://<package>`; anything else as it is. */
    fun toBitwardenUri(url: String): String {
        if (!url.startsWith(CHROME_APP, ignoreCase = true)) return url
        val at = url.lastIndexOf('@')
        return if (at < 0) url else ANDROID_APP + url.substring(at + 1).trimEnd('/')
    }

    /** The export of [vault]: its accounts outside Recently deleted and its unknown top-level keys. */
    fun export(vault: Vault, exportedAtMs: Long, generator: String): Written =
        export(vault.entries, exportedAtMs, generator, vault.extras)

    /**
     * The export of the accounts of [entries] outside Recently deleted, in order. [exportedAtMs] is
     * the wall-clock time, [generator] `"PasswordVault " + versionName` (`BuildInfo.generator`), and
     * [vaultExtras] the vault's unknown top-level keys ([Vault.extras]).
     */
    fun export(
        entries: List<Entry>,
        exportedAtMs: Long,
        generator: String,
        vaultExtras: Map<String, String> = emptyMap(),
    ): Written {
        val replaced = TextHygiene.Counter()
        val w = Pretty(replaced)
        var items = 0
        w.beginObject()
        w.key("encrypted").literal("false")
        w.key(EXTENSION).beginObject()
        w.key("format").string(FORMAT)
        w.key("version").literal(VERSION.toString())
        w.key("exportedAt").string(IsoTime.format(exportedAtMs))
        w.key("generator").string(generator)
        writeExtras(w, vaultExtras, ROOT_EXTENSION_KEYS, VAULT_EXTRA_DEPTH)
        w.endObject()
        // Proton Pass refuses a "folders" that is not an array, and KeePassXC needs the key.
        w.key("folders").beginArray().endArray()
        w.key("items").beginArray()
        for (entry in entries) {
            if (entry.deletedAt != null) continue
            w.item()
            writeItem(w, entry)
            items++
        }
        w.endArray()
        w.endObject()
        w.sb.append('\n')
        return Written(w.sb.toString().toByteArray(Charsets.UTF_8), items, replaced.count)
    }

    private fun writeItem(w: Pretty, e: Entry) {
        val uris = e.urls.filter { it.isNotBlank() }.map(::toBitwardenUri)
        w.beginObject()
        w.key("id").string(e.id)
        w.key("organizationId").literal("null")
        w.key("folderId").literal("null")
        w.key("type").literal("1")
        w.key("reprompt").literal("0")
        w.key("name").string(e.title)
        w.key("notes").stringOrNull(e.notes.ifEmpty { null })
        w.key("favorite").literal(if (e.favourite) "true" else "false")
        w.key("fields").beginArray()
        for (field in e.fields) {
            w.item().beginObject()
            w.key("name").string(field.name)
            w.key("value").string(field.value)
            w.key("type").literal(if (field.secret) "1" else "0")
            w.key("linkedId").literal("null")
            w.endObject()
        }
        w.endArray()
        w.key("login").beginObject()
        w.key("fido2Credentials").beginArray().endArray()
        w.key("uris").beginArray()
        for (uri in uris) {
            w.item().beginObject()
            w.key("match").literal("null")
            w.key("uri").string(uri)
            w.endObject()
        }
        w.endArray()
        w.key("username").stringOrNull(e.username.ifEmpty { null })
        w.key("password").stringOrNull(e.password.ifEmpty { null })
        w.key("totp").literal("null")
        w.endObject()
        w.key("passwordHistory")
        if (e.passwordHistory.isEmpty()) {
            w.literal("null")
        } else {
            w.beginArray()
            for (old in e.passwordHistory) {
                w.item().beginObject()
                w.key("lastUsedDate").string(IsoTime.format(old.replacedAt))
                w.key("password").string(old.password)
                w.endObject()
            }
            w.endArray()
        }
        w.key("creationDate").string(IsoTime.format(e.createdAt))
        w.key("revisionDate").string(IsoTime.format(e.updatedAt))
        w.key("deletedDate").literal("null")
        w.key("collectionIds").literal("null")
        w.key(EXTENSION).beginObject()
        w.key("passwordChangedAt").stringOrNull(e.passwordChangedAt?.let(IsoTime::format))
        w.key("changePending").literal(if (e.changePending) "true" else "false")
        w.key("checkIgnored").literal(if (e.checkIgnored) "true" else "false")
        w.key("pendingOldPassword").stringOrNull(e.pendingOldPassword)
        if (uris != e.urls) {
            w.key("urls").beginArray()
            for (url in e.urls) w.item().string(url)
            w.endArray()
        }
        writeExtras(w, e.extras, VAULT_ENTRY_KEYS, ENTRY_EXTRA_DEPTH)
        w.endObject()
        w.endObject()
    }

    /**
     * The kept unknown keys of an entry or of the vault, after our own keys of the same object. Left
     * out: a key that has the name of one in [taken] or of one already written (no key twice in an
     * object, or our own reader would refuse the file), and one whose text is not JSON that fits a
     * vault file. The vault's reader never makes such a key, and an export must not fail over one.
     */
    private fun writeExtras(w: Pretty, extras: Map<String, String>, taken: Set<String>, maxDepth: Int) {
        if (extras.isEmpty()) return
        val written = HashSet<String>()
        for ((key, text) in extras) {
            val name = TextHygiene.replaceUnpairedSurrogates(key) // as it will stand in the file
            if (name in taken || !written.add(name)) continue
            val value = try {
                StrictJson.parse(text, maxDepth)
            } catch (_: StrictJson.Failure) {
                written.remove(name)
                continue
            }
            w.key(key).element(value)
        }
    }

    /** Writes JSON with a two-space indent; every string passes through `TextHygiene`. */
    private class Pretty(private val replaced: TextHygiene.Counter) {
        val sb = StringBuilder(1 shl 14)

        /** How many members the container open at each depth has so far. */
        private var members = IntArray(48)
        private var depth = 0

        private fun open(c: Char): Pretty {
            sb.append(c)
            depth++
            if (depth == members.size) members = members.copyOf(depth * 2)
            members[depth] = 0
            return this
        }

        private fun close(c: Char): Pretty {
            val had = members[depth] > 0
            depth--
            if (had) newLine()
            sb.append(c)
            return this
        }

        private fun newLine() {
            sb.append('\n')
            for (i in 0 until depth) sb.append("  ")
        }

        /** Starts the next member of the open container. */
        fun item(): Pretty {
            if (members[depth]++ > 0) sb.append(',')
            newLine()
            return this
        }

        fun key(name: String): Pretty {
            item()
            string(name)
            sb.append(": ")
            return this
        }

        fun beginObject(): Pretty = open('{')

        fun endObject(): Pretty = close('}')

        fun beginArray(): Pretty = open('[')

        fun endArray(): Pretty = close(']')

        /** A number, `true`, `false` or `null`, as given. */
        fun literal(text: String): Pretty {
            sb.append(text)
            return this
        }

        fun string(s: String): Pretty {
            appendQuoted(sb, TextHygiene.replaceUnpairedSurrogates(s, replaced))
            return this
        }

        fun stringOrNull(s: String?): Pretty = if (s == null) literal("null") else string(s)

        /** A value read by `StrictJson`: a number keeps its spelling, an object the order of its keys. */
        fun element(e: JsonElement): Pretty {
            when (e) {
                is JsonObject -> {
                    beginObject()
                    for ((k, v) in e) key(k).element(v)
                    endObject()
                }
                is JsonArray -> {
                    beginArray()
                    for (v in e) item().element(v)
                    endArray()
                }
                is JsonNull -> literal("null")
                is JsonPrimitive -> if (e.isString) string(e.content) else literal(e.content)
            }
            return this
        }
    }

    private const val HEX = "0123456789abcdef"

    /** [s] as a JSON string: the escapes of architecture.md §4.3, which kotlinx-serialization writes too. */
    private fun appendQuoted(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c >= ' ' -> sb.append(c)
                c == '\b' -> sb.append("\\b")
                c == '\t' -> sb.append("\\t")
                c == '\n' -> sb.append("\\n")
                c == '\u000C' -> sb.append("\\f")
                c == '\r' -> sb.append("\\r")
                else -> sb.append("\\u00").append(HEX[c.code shr 4]).append(HEX[c.code and 0xF])
            }
        }
        sb.append('"')
    }

    /**
     * [e] as compact JSON text, the form of a kept unknown value ([Entry.extras]): the same tokens and
     * the same spelling of every number, no white space.
     */
    private fun appendCompact(sb: StringBuilder, e: JsonElement) {
        when (e) {
            is JsonObject -> {
                sb.append('{')
                var first = true
                for ((k, v) in e) {
                    if (!first) sb.append(',')
                    first = false
                    appendQuoted(sb, k)
                    sb.append(':')
                    appendCompact(sb, v)
                }
                sb.append('}')
            }
            is JsonArray -> {
                sb.append('[')
                for (i in e.indices) {
                    if (i > 0) sb.append(',')
                    appendCompact(sb, e[i])
                }
                sb.append(']')
            }
            is JsonNull -> sb.append("null")
            is JsonPrimitive -> if (e.isString) appendQuoted(sb, e.content) else sb.append(e.content)
        }
    }

    // ------------------------------------------------------------------ reading

    /** What [read] made of a file. */
    sealed interface Read {
        /** The file was read. */
        class Ok(
            /** The accounts, in the order of the file. Nothing of the importer's rules is applied yet. */
            val entries: List<Entry>,
            /** Everything in the file that is not in [entries], in the order of the file. */
            val notImported: List<NotImported>,
            /** `passwordVault.format` says the file is our own export. */
            val ownFormat: Boolean,
            /** Our own export, written by a later version: its unknown keys are kept as they are. */
            val newerVersion: Boolean,
            /**
             * The unknown keys of our own file's top `passwordVault` object, each with its value as
             * compact JSON text, in the order they were read: what [Vault.extras] holds, so that a
             * later version's vault-level data survives (formats-review P11). Unpaired surrogates in
             * them are U+FFFD already.
             */
            val vaultExtras: Map<String, String>,
            /** Byte-order marks removed from the start of the file. */
            val byteOrderMarks: Int,
        ) : Read {
            override fun toString(): String =
                "Ok(entries=${entries.size}, notImported=${notImported.size}, ownFormat=$ownFormat, " +
                    "newerVersion=$newerVersion, vaultExtras=${vaultExtras.size}, byteOrderMarks=$byteOrderMarks)"
        }

        /**
         * `"encrypted": true`: "This Bitwarden file is encrypted. In Bitwarden, export again and choose
         * '.json', not '.json (Encrypted)'." [passwordProtected] tells Bitwarden's two kinds apart.
         */
        class Encrypted(val passwordProtected: Boolean) : Read

        /** Not strict UTF-8, or not JSON as `StrictJson` reads it: where and why, never what. */
        class Unreadable(val error: ParseError) : Read

        /** JSON, but without an `items` array: not an export this reader knows. */
        data object NotAnExport : Read

        /** More than [limit] elements in `items` (formats.md §8.1). */
        class TooMany(val items: Int, val limit: Int) : Read
    }

    /** One thing in a file that was not imported (formats.md §5.2). */
    class NotImported(
        /** The element's position in `items`, from 1; 0 for something outside `items`. */
        val position: Int,
        /** The item's name, when it has one. */
        val name: String?,
        val what: What,
    ) {
        enum class What {
            /** An element of `items` that is `null`, a number, a string or an array: listed by position. */
            NOT_AN_ITEM,

            /** An item with no name and nothing else in it. */
            EMPTY,

            /** An item in Bitwarden's trash (`deletedDate`). */
            IN_TRASH,

            /** The item was imported, its passkeys were not ("passkeys are not imported"). */
            PASSKEYS,

            /** The item was imported without its linked fields, which only point at another value. */
            LINKED_FIELD,

            /** The item was imported with its newest 5 previous passwords; it had more. */
            PREVIOUS_PASSWORDS,

            /**
             * An unknown key of our own extension could not be kept: its value nests deeper than a
             * vault file holds. [position] is the item's, or 0 for the top object.
             */
            UNKNOWN_DATA,
        }

        override fun toString(): String = "NotImported(position=$position, what=$what, name hidden)"
    }

    /** The reasons of a [Read.Unreadable] that this reader makes itself; `StrictJson.Reason` gives the rest. */
    const val REASON_PARSER_FAILURE = "parser-failure"

    /**
     * The name of an imported account that came without one (formats.md §8.3, formats-review P11):
     * the host of its first website (for an app link the package), else its username, else
     * [EditTexts.untitled] of [nowMs]; stored as any name this app writes (trimmed, cut to 200).
     */
    fun titleFor(urls: List<String>, username: String, nowMs: Long, texts: EditTexts): String =
        EntryRules.fromWebsites(urls)
            ?: EntryRules.storedTitle(username).ifEmpty { null }
            ?: EntryRules.storedTitle(texts.untitled(nowMs))

    /**
     * Reads a Bitwarden JSON export or our own. [nowMs] stands in for a date the file does not have;
     * [texts] names an account without a name, a website and a username; [newId] gives the ids of a
     * foreign file's accounts, and of an account of our own file that has none (`RandomSource::uuid`).
     * Never throws for any [bytes], and leaves them as they are.
     */
    fun read(
        bytes: ByteArray,
        nowMs: Long,
        texts: EditTexts,
        newId: () -> String,
        maxItems: Int = MAX_ITEMS,
    ): Read {
        var start = 0
        while (start + 2 < bytes.size && bytes[start] == 0xEF.toByte() && bytes[start + 1] == 0xBB.toByte() && bytes[start + 2] == 0xBF.toByte()) {
            start += 3
        }
        val marks = start / 3
        val body = if (start == 0) bytes else bytes.copyOfRange(start, bytes.size)
        val root = try {
            StrictJson.parseUtf8Object(body, MAX_DEPTH)
        } catch (f: StrictJson.Failure) {
            return Read.Unreadable(ParseError(f.offset + start, f.reason.code))
        } catch (_: RuntimeException) { // any other failure of the reader, without its message
            return Read.Unreadable(ParseError(start.toLong(), REASON_PARSER_FAILURE))
        } finally {
            if (body !== bytes) Bytes.wipe(body)
        }
        if (isTrue(root["encrypted"])) return Read.Encrypted(isTrue(root["passwordProtected"]))
        val items = root["items"] as? JsonArray ?: return Read.NotAnExport
        if (items.size > maxItems) return Read.TooMany(items.size, maxItems)

        // Our own file says so in the top object's extension; only then is any extension read.
        val extension = (root[EXTENSION] as? JsonObject)?.takeIf { isText(it["format"], FORMAT) }
        val own = extension != null
        val newer = extension != null && (int(extension["version"]) ?: VERSION) > VERSION
        val notImported = ArrayList<NotImported>()
        var vaultExtras: Map<String, String> = emptyMap()
        if (extension != null) {
            var listed = false
            vaultExtras = extras(extension, ROOT_EXTENSION_KEYS, VAULT_TOP_KEYS, VAULT_EXTRA_DEPTH) {
                if (!listed) notImported.add(NotImported(0, null, NotImported.What.UNKNOWN_DATA))
                listed = true
            }
        }
        val folders = folderNames(root)
        val entries = ArrayList<Entry>(items.size)
        for (index in items.indices) {
            val position = index + 1
            val item = items[index] as? JsonObject
            if (item == null) {
                notImported.add(NotImported(position, null, NotImported.What.NOT_AN_ITEM))
                continue
            }
            entryOf(item, position, own, folders, nowMs, texts, newId, notImported)?.let(entries::add)
        }
        return Read.Ok(entries, notImported, own, newer, vaultExtras, marks)
    }

    /** The folders, or an organisation's collections, by id. */
    private fun folderNames(root: JsonObject): Map<String, String> {
        val list = root["folders"] as? JsonArray ?: root["collections"] as? JsonArray ?: return emptyMap()
        val names = HashMap<String, String>()
        for (element in list) {
            val folder = element as? JsonObject ?: continue
            val id = text(folder["id"]) ?: continue
            val name = text(folder["name"])
            if (!name.isNullOrBlank()) names[id] = name
        }
        return names
    }

    /** One element of `items` as an entry, or null when it is not imported (then it is listed). */
    private fun entryOf(
        item: JsonObject,
        position: Int,
        own: Boolean,
        folders: Map<String, String>,
        nowMs: Long,
        texts: EditTexts,
        newId: () -> String,
        notImported: MutableList<NotImported>,
    ): Entry? {
        val name = text(item["name"])?.takeIf { it.isNotBlank() }
        val alreadyListed = HashSet<NotImported.What>()

        /** Lists [what] for this item, once however often it occurs in it. */
        fun listed(what: NotImported.What) {
            if (alreadyListed.add(what)) notImported.add(NotImported(position, name, what))
        }

        val deleted = item["deletedDate"]
        if (deleted != null && deleted !is JsonNull) {
            listed(NotImported.What.IN_TRASH)
            return null
        }
        val fields = ArrayList<CustomField>()
        (item["fields"] as? JsonArray)?.forEach { element ->
            val field = element as? JsonObject ?: return@forEach
            val type = int(field["type"]) ?: FIELD_TEXT
            if (type == FIELD_LINKED) {
                // A linked field has no value of its own: it points at the username or the password.
                listed(NotImported.What.LINKED_FIELD)
            } else {
                // Text, a checkbox ("true" or "false") or a kind this version does not know: visible. Hidden: hidden.
                fields.add(CustomField(text(field["name"]) ?: "", text(field["value"]) ?: "", secret = type == FIELD_HIDDEN))
            }
        }
        var username = ""
        var password = ""
        val urls = ArrayList<String>()
        when (int(item["type"]) ?: TYPE_LOGIN) {
            TYPE_LOGIN -> (item["login"] as? JsonObject)?.let { login ->
                username = text(login["username"]) ?: ""
                password = text(login["password"]) ?: ""
                (login["uris"] as? JsonArray)?.forEach { uri ->
                    text((uri as? JsonObject)?.get("uri"))?.takeIf { it.isNotBlank() }?.let(urls::add)
                }
                // Version 1 has no one-time codes (ux.md §11): the secret is kept as a hidden field.
                text(login["totp"])?.takeIf { it.isNotBlank() }?.let { fields.add(CustomField(TOTP_FIELD, it, true)) }
                if ((login["fido2Credentials"] as? JsonArray)?.isNotEmpty() == true) listed(NotImported.What.PASSKEYS)
            }
            TYPE_NOTE -> Unit // a secure note: its name, notes and fields
            else -> {
                // A card, an identity, an SSH key or a later type: every value of its object is a field.
                val body = item.entries.firstOrNull { (key, value) -> value is JsonObject && key !in NOT_A_BODY }?.value as? JsonObject
                body?.forEach { (key, value) ->
                    text(value)?.takeIf { it.isNotBlank() }?.let { fields.add(CustomField(key, it, LOOKS_SECRET.containsMatchIn(key))) }
                }
            }
        }
        // Version 1 has no folders (ux.md §2): a folder is kept as a visible field.
        val folder = text(item["folderId"])?.let(folders::get)
            ?: text((item["collectionIds"] as? JsonArray)?.firstOrNull())?.let(folders::get)
        if (folder != null) fields.add(CustomField(FOLDER_FIELD, folder, false))

        var history = ArrayList<HistoryItem>()
        (item["passwordHistory"] as? JsonArray)?.forEach { element ->
            val old = element as? JsonObject ?: return@forEach
            val oldPassword = text(old["password"]) ?: return@forEach
            history.add(HistoryItem(oldPassword, IsoTime.parse(text(old["lastUsedDate"])) ?: nowMs))
        }
        if (!own) {
            // A foreign file: newest first, and the newest 5 (Bitwarden itself keeps no more).
            history.sortByDescending { it.replacedAt } // stable: equal times keep the file's order
            if (history.size > EntryRules.HISTORY_MAX) {
                history = ArrayList(history.subList(0, EntryRules.HISTORY_MAX))
                listed(NotImported.What.PREVIOUS_PASSWORDS)
            }
        }

        // Our own file: the extension wins (formats.md §5.2).
        val extension = if (own) item[EXTENSION] as? JsonObject else null
        var passwordChangedAt: Long? = null
        var changePending = false
        var checkIgnored = false
        var pendingOldPassword: String? = null
        var extras: Map<String, String> = emptyMap()
        if (extension != null) {
            val ownUrls = extension["urls"] as? JsonArray
            if (ownUrls != null && ownUrls.all { it is JsonPrimitive && it.isString }) {
                urls.clear()
                ownUrls.mapTo(urls) { (it as JsonPrimitive).content }
            }
            passwordChangedAt = IsoTime.parse(text(extension["passwordChangedAt"]))
            checkIgnored = isTrue(extension["checkIgnored"])
            pendingOldPassword = extension["pendingOldPassword"]?.let { if (it is JsonPrimitive && it.isString) it.content else null }
            // D-10d: an old password that is kept means a change is pending, unless the file says otherwise.
            val pending = extension["changePending"]
            changePending = if (isBoolean(pending)) isTrue(pending) else pendingOldPassword != null
            extras = extras(extension, emptySet(), VAULT_ENTRY_KEYS, ENTRY_EXTRA_DEPTH) { listed(NotImported.What.UNKNOWN_DATA) }
        }

        val notes = text(item["notes"]) ?: ""
        if (name == null && username.isBlank() && password.isEmpty() && urls.isEmpty() && notes.isBlank() &&
            fields.isEmpty() && history.isEmpty() && pendingOldPassword == null && extras.isEmpty()
        ) {
            listed(NotImported.What.EMPTY)
            return null
        }
        val created = IsoTime.parse(text(item["creationDate"])) ?: nowMs
        val id = item["id"]
        return Entry(
            // Ids are kept only from our own files; VaultEdits.addImported decides which of them stay.
            id = if (own && id is JsonPrimitive && id.isString) id.content else newId(),
            title = name ?: titleFor(urls, username, nowMs, texts),
            username = username,
            password = password,
            notes = notes,
            urls = urls,
            fields = fields,
            favourite = isTrue(item["favorite"]),
            changePending = changePending,
            checkIgnored = checkIgnored,
            createdAt = created,
            updatedAt = IsoTime.parse(text(item["revisionDate"])) ?: created,
            passwordChangedAt = passwordChangedAt,
            deletedAt = null,
            pendingOldPassword = pendingOldPassword,
            passwordHistory = history,
            extras = extras,
        )
    }

    private const val TYPE_LOGIN = 1
    private const val TYPE_NOTE = 2
    private const val FIELD_TEXT = 0
    private const val FIELD_HIDDEN = 1
    private const val FIELD_LINKED = 3

    /** Keys of an item whose object is not the body of a card, an identity or an SSH key. */
    private val NOT_A_BODY = setOf("login", "secureNote", EXTENSION)

    /**
     * The unknown keys of our extension object [o], in the order they were read, each with its value
     * as compact JSON text. Left out: our own keys ([known]), the names a vault file uses itself
     * ([vaultKeys]), and a value that nests deeper than [maxDepth], for which [tooDeep] is called.
     */
    private fun extras(
        o: JsonObject,
        known: Set<String>,
        vaultKeys: Set<String>,
        maxDepth: Int,
        tooDeep: () -> Unit,
    ): Map<String, String> {
        var kept: LinkedHashMap<String, String>? = null
        for ((key, value) in o) {
            if (key in known || key in vaultKeys) continue
            if (depth(value) > maxDepth) {
                tooDeep()
                continue
            }
            val map = kept ?: LinkedHashMap<String, String>().also { kept = it }
            val sb = StringBuilder()
            appendCompact(sb, value)
            map[TextHygiene.replaceUnpairedSurrogates(key)] = TextHygiene.replaceUnpairedSurrogates(sb.toString())
        }
        return kept ?: emptyMap()
    }

    /** How deep [e] nests: 0 for a value that is no container, 1 for an object or array of such values. */
    private fun depth(e: JsonElement): Int = when (e) {
        is JsonObject -> 1 + (e.values.maxOfOrNull(::depth) ?: 0)
        is JsonArray -> 1 + (e.maxOfOrNull(::depth) ?: 0)
        else -> 0
    }

    /** The text of a string, a number or a boolean; null for `null`, a container or a missing key. */
    private fun text(e: JsonElement?): String? = if (e is JsonPrimitive && e !is JsonNull) e.content else null

    private fun isText(e: JsonElement?, value: String): Boolean = e is JsonPrimitive && e.isString && e.content == value

    private fun isBoolean(e: JsonElement?): Boolean =
        e is JsonPrimitive && !e.isString && (e.content == "true" || e.content == "false")

    /** Only the boolean `true`: `"true"` is a string (Bitwarden's `encrypted !== true`). */
    private fun isTrue(e: JsonElement?): Boolean = e is JsonPrimitive && !e.isString && e.content == "true"

    /** A whole number, written as a number or inside a string; null for anything else. */
    private fun int(e: JsonElement?): Int? = text(e)?.toIntOrNull()
}
