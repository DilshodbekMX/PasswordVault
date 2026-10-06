package pv.formats

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * Bitwarden's unencrypted JSON export ({"encrypted":false,"folders":[...],"items":[...]}), which
 * Bitwarden, Proton Pass (web, desktop, extension) and KeePassXC 2.7.7+ import, plus one extension
 * object "passwordVault" at the top level and in each item for what Bitwarden has no field for.
 * Other importers ignore unknown keys (checked in their sources), so the same file is also our own
 * lossless JSON. Field names follow bitwarden/clients libs/common/src/models/export (the *Export classes).
 */
object BitwardenJson {
    const val EXT = "passwordVault"
    const val FORMAT = "passwordvault-export"
    const val VERSION = 1

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private val pretty = Json { prettyPrint = true; prettyPrintIndent = "  " }

    private fun str(s: String?): JsonElement = if (s.isNullOrEmpty()) JsonNull else JsonPrimitive(s)

    /** Chrome's "android://<cert hash>@<package>/" becomes Bitwarden's "androidapp://<package>". */
    fun toBitwardenUri(u: String): String =
        if (u.startsWith("android://") && u.contains('@')) "androidapp://" + u.substringAfter('@').trimEnd('/') else u

    fun export(entries: List<Entry>, exportedAt: Long, generator: String): String {
        val root = buildJsonObject {
            put("encrypted", false)
            put(EXT, buildJsonObject {
                put("format", FORMAT); put("version", VERSION)
                put("exportedAt", IsoTime.format(exportedAt)); put("generator", generator)
            })
            put("folders", JsonArray(emptyList())) // required by Proton (Array.isArray) and KeePassXC (key present)
            put("items", buildJsonArray { entries.forEach { add(item(it)) } })
        }
        return pretty.encodeToString(JsonObject.serializer(), root) + "\n"
    }

    private fun item(e: Entry): JsonObject = buildJsonObject {
        val bwUris = e.urls.map(::toBitwardenUri)
        put("id", e.id)
        put("organizationId", JsonNull)
        put("folderId", JsonNull)
        put("type", 1) // login
        put("reprompt", 0)
        put("name", e.title)
        put("notes", str(e.notes))
        put("favorite", e.favourite)
        put("fields", buildJsonArray {
            e.fields.forEach { f -> add(buildJsonObject {
                put("name", f.name); put("value", f.value); put("type", if (f.secret) 1 else 0); put("linkedId", JsonNull)
            }) }
        })
        put("login", buildJsonObject {
            put("fido2Credentials", JsonArray(emptyList()))
            put("uris", buildJsonArray { bwUris.forEach { u -> add(buildJsonObject { put("match", JsonNull); put("uri", u) }) } })
            put("username", str(e.username))
            put("password", str(e.password))
            put("totp", JsonNull)
        })
        put("passwordHistory", if (e.history.isEmpty()) JsonNull else buildJsonArray {
            e.history.forEach { h -> add(buildJsonObject { put("lastUsedDate", IsoTime.format(h.replacedAt)); put("password", h.password) }) }
        })
        put("creationDate", IsoTime.format(e.createdAt))
        put("revisionDate", IsoTime.format(e.updatedAt))
        put("deletedDate", JsonNull)
        put("collectionIds", JsonNull)
        put(EXT, buildJsonObject {
            if (e.passwordChangedAt != null) put("passwordChangedAt", IsoTime.format(e.passwordChangedAt))
            if (e.changePending) put("changePending", true)
            if (e.checkIgnored) put("checkIgnored", true)
            if (bwUris != e.urls) put("urls", buildJsonArray { e.urls.forEach { add(JsonPrimitive(it)) } })
            e.extras.forEach { (k, raw) -> put(k, Json.parseToJsonElement(raw)) }
        })
    }

    // ---------------------------------------------------------------- import

    class Result(val entries: List<Entry>, val skipped: List<String>, val ownFormat: Boolean, val newerVersion: Boolean)
    class EncryptedFile(message: String) : Exception(message)

    private fun JsonElement?.s(): String? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
    private fun JsonElement?.b(): Boolean = (this as? JsonPrimitive)?.booleanOrNull ?: false
    private fun JsonObject.o(k: String): JsonObject? = this[k] as? JsonObject
    private fun JsonObject.a(k: String): JsonArray? = this[k] as? JsonArray

    private val KNOWN_EXT = setOf("passwordChangedAt", "changePending", "checkIgnored", "urls")
    private val SECRETISH = Regex("(?i)(number|code|cvv|ssn|passport|license|licence|private|pin|secret|password|key)")

    fun import(text: String, now: Long, newId: () -> String = { UUID.randomUUID().toString() }): Result {
        val root = Json.parseToJsonElement(text) as? JsonObject ?: throw IllegalArgumentException("Not a JSON object")
        if (root["encrypted"].b())
            throw EncryptedFile(if (root["passwordProtected"].b()) "password-protected" else "account-encrypted")
        val items = root.a("items") ?: throw IllegalArgumentException("No \"items\" array")
        val ext = root.o(EXT)
        val own = ext?.get("format").s() == FORMAT
        val newer = own && ((ext?.get("version") as? JsonPrimitive)?.intOrNull ?: 1) > VERSION
        val folders = HashMap<String, String>()
        (root.a("folders") ?: root.a("collections"))?.forEach { f ->
            val fo = f as? JsonObject ?: return@forEach
            val id = fo["id"].s(); val name = fo["name"].s()
            if (id != null && !name.isNullOrBlank()) folders[id] = name
        }
        val out = ArrayList<Entry>(); val skipped = ArrayList<String>()
        for (el in items) {
            val item = el as? JsonObject ?: continue
            val name = item["name"].s() ?: ""
            if (item["deletedDate"].s() != null) { skipped.add("$name (in Bitwarden's trash)"); continue }
            val type = (item["type"] as? JsonPrimitive)?.intOrNull ?: 1
            val fields = ArrayList<CustomField>()
            item.a("fields")?.forEach { f ->
                val fo = f as? JsonObject ?: return@forEach
                val ft = (fo["type"] as? JsonPrimitive)?.intOrNull ?: 0
                if (ft == 3) return@forEach // "linked" field: points at username/password, has no value
                fields.add(CustomField(fo["name"].s() ?: "", fo["value"].s() ?: "", ft == 1))
            }
            var username = ""; var password = ""; val urls = ArrayList<String>()
            when (type) {
                1 -> item.o("login")?.let { l ->
                    username = l["username"].s() ?: ""; password = l["password"].s() ?: ""
                    l.a("uris")?.forEach { u -> (u as? JsonObject)?.get("uri").s()?.takeIf { s -> s.isNotBlank() }?.let(urls::add) }
                    // v1 has no one-time codes (ux.md §11): keep the secret as a hidden field.
                    l["totp"].s()?.takeIf { t -> t.isNotBlank() }?.let { t -> fields.add(CustomField("TOTP", t, true)) }
                }
                2 -> Unit // secure note: title, notes and fields only
                else -> {
                    // Card (3), identity (4), SSH key (5) and later types: every string becomes a field.
                    val body = item.entries.firstOrNull { (k, v) -> v is JsonObject && k !in setOf("login", "secureNote", EXT) }?.value as? JsonObject
                    body?.forEach { (k, v) -> v.s()?.takeIf { s -> s.isNotBlank() }?.let { s -> fields.add(CustomField(k, s, SECRETISH.containsMatchIn(k))) } }
                }
            }
            val e = item.o(EXT)
            e?.a("urls")?.let { own -> urls.clear(); own.mapNotNullTo(urls) { u -> u.s() } }
            // v1 has no folders (ux.md §2): a foreign folder is kept as a visible field.
            (item["folderId"].s()?.let(folders::get) ?: item.a("collectionIds")?.firstOrNull()?.s()?.let(folders::get))
                ?.let { fields.add(CustomField("Folder", it, false)) }
            val history = item.a("passwordHistory")?.mapNotNull { h ->
                val ho = h as? JsonObject ?: return@mapNotNull null
                val p = ho["password"].s() ?: return@mapNotNull null
                HistoryItem(p, IsoTime.parse(ho["lastUsedDate"].s()) ?: now)
            }?.sortedByDescending { it.replacedAt }?.take(5) ?: emptyList()
            val extras = e?.filterKeys { k -> k !in KNOWN_EXT }?.mapValues { (_, v) -> v.toString() } ?: emptyMap()
            val created = IsoTime.parse(item["creationDate"].s()) ?: now
            out.add(Entry(
                id = item["id"].s()?.takeIf { own } ?: newId(), // keep ids only from our own files
                title = name, username = username, password = password, urls = urls,
                notes = item["notes"].s() ?: "", fields = fields, favourite = item["favorite"].b(),
                createdAt = created, updatedAt = IsoTime.parse(item["revisionDate"].s()) ?: created,
                passwordChangedAt = IsoTime.parse(e?.get("passwordChangedAt").s()), history = history,
                changePending = e?.get("changePending").b(), checkIgnored = e?.get("checkIgnored").b(), extras = extras,
            ))
        }
        return Result(out, skipped, own, newer)
    }
}
