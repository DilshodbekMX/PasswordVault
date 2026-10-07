package io.github.dilshodbekmx.passwordvault.core.vaultfile

import io.github.dilshodbekmx.passwordvault.core.model.CustomField
import io.github.dilshodbekmx.passwordvault.core.model.Draft
import io.github.dilshodbekmx.passwordvault.core.model.DraftEntry
import io.github.dilshodbekmx.passwordvault.core.model.Entry
import io.github.dilshodbekmx.passwordvault.core.model.FileRef
import io.github.dilshodbekmx.passwordvault.core.model.HistoryItem
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import java.util.Random
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral

/**
 * Random [Vault] values for the round-trip and fuzz tests: texts in the user's scripts with every
 * kind of character the canonical form escapes, surrogate pairs, extreme integers, nulls, and kept
 * unknown values in kotlinx-serialization's own compact form (so that a round trip through this
 * codec also compares its canonical form with kotlinx's). No text holds an unpaired surrogate unless
 * [lone] is set: those come back as U+FFFD, so they do not round-trip by design.
 */
internal class RandomVaults(seed: Long, private val lone: Boolean = false) {
    val random = Random(seed)

    private val pieces = listOf(
        "a", "Z", "0", " ", "-", "_", ".", "@", "/", "\"", "\\", "'", "ʻ", "’", "–",
        "Ж", "қ", "Ў", "ё", "é", "\u00A0", "\u2028", "\u2029", "\uFEFF", "\u007F", "\u00AD",
        "🔑", "🇺🇿", "𐐷", "中", "ا", "א",
        "\u0000", "\u0001", "\b", "\t", "\n", "\u000B", "\u000C", "\r", "\u001B", "\u001F",
        "Toshkent", "Тошкент", "Oʻzbekiston", "parol",
    )

    /**
     * A text of up to [maxPieces] pieces. Keys never hold an unpaired surrogate ([allowLone] false):
     * two keys that differ only there would both be written as U+FFFD, and storage's write-time
     * check (§4.9 step 4) refuses to save such texts anyway.
     */
    fun string(maxPieces: Int = 6, allowLone: Boolean = lone): String {
        val n = random.nextInt(maxPieces + 1)
        val sb = StringBuilder()
        repeat(n) {
            if (allowLone && random.nextInt(20) == 0) sb.append(if (random.nextBoolean()) '\uD800' else '\uDFFF')
            sb.append(pieces[random.nextInt(pieces.size)])
        }
        return sb.toString()
    }

    fun long(): Long = when (random.nextInt(8)) {
        0 -> 0L
        1 -> Long.MAX_VALUE
        2 -> Long.MIN_VALUE
        3 -> -1L - random.nextInt(1000)
        4 -> random.nextLong()
        else -> 1_700_000_000_000L + random.nextInt(Int.MAX_VALUE)
    }

    private fun <T> list(max: Int, item: () -> T): List<T> = List(random.nextInt(max + 1)) { item() }

    private fun <T> maybe(item: () -> T): T? = if (random.nextInt(3) == 0) null else item()

    fun field() = CustomField(string(), string(), random.nextBoolean())

    fun entry(): Entry = Entry(
        id = if (random.nextInt(4) == 0) string() else uuid(),
        title = string(),
        username = string(),
        password = string(),
        notes = string(12),
        urls = list(3) { string() },
        fields = list(3) { field() },
        favourite = random.nextBoolean(),
        changePending = random.nextBoolean(),
        checkIgnored = random.nextBoolean(),
        createdAt = long(),
        updatedAt = long(),
        passwordChangedAt = maybe { long() },
        deletedAt = maybe { long() },
        pendingOldPassword = maybe { string() },
        passwordHistory = list(5) { HistoryItem(string(), long()) },
        extras = extras(setOf("id", "title", "username", "password", "notes", "urls", "fields", "favourite", "changePending",
            "checkIgnored", "createdAt", "updatedAt", "passwordChangedAt", "deletedAt", "pendingOldPassword", "passwordHistory"), 11),
    )

    fun vault(maxEntries: Int = 4): Vault = Vault(
        entries = list(maxEntries) { entry() },
        draft = maybe {
            Draft(
                entryId = maybe { uuid() },
                savedAt = long(),
                entry = DraftEntry(string(), string(), string(), string(), list(2) { string() }, list(2) { field() }, random.nextBoolean()),
            )
        },
        plainExports = list(2) { FileRef(string(), string(), long()) },
        backups = list(2) { FileRef(string(), string(), long()) },
        masterPasswordMatches = list(3) { uuid() },
        securitySeenAt = maybe { long() },
        extras = extras(setOf("schema", "entries", "draft", "plainExports", "backups", "masterPasswordMatches", "securitySeenAt"), 13),
    )

    fun uuid(): String {
        val hex = "0123456789abcdef"
        fun h(n: Int) = String(CharArray(n) { hex[random.nextInt(16)] })
        return h(8) + "-" + h(4) + "-4" + h(3) + "-" + "89ab"[random.nextInt(4)] + h(3) + "-" + h(12)
    }

    /** Up to three kept unknown keys, each value kotlinx's compact text of a tree at most [depth] deep. */
    private fun extras(known: Set<String>, depth: Int): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        repeat(random.nextInt(4)) {
            val key = "future" + string(2, allowLone = false)
            if (key !in known) out[key] = json(random.nextInt(depth + 1)).toString()
        }
        return out
    }

    private val numbers = listOf("0", "-0", "1", "-1", "1.50", "-0.0", "1E+2", "1e-7", "12345678901234567890", "3.14159", "2E10")

    /** A random JSON value with at most [depth] levels of containers. */
    fun json(depth: Int): JsonElement = when (if (depth == 0) random.nextInt(4) else random.nextInt(6)) {
        0 -> JsonPrimitive(string(3))
        1 -> JsonUnquotedLiteral(numbers[random.nextInt(numbers.size)])
        2 -> if (random.nextBoolean()) JsonPrimitive(random.nextBoolean()) else JsonNull
        3 -> JsonPrimitive(string(1))
        4 -> JsonArray(List(random.nextInt(3)) { json(depth - 1) })
        else -> {
            val members = LinkedHashMap<String, JsonElement>()
            repeat(random.nextInt(3)) { members[string(2, allowLone = false)] = json(depth - 1) }
            JsonObject(members)
        }
    }
}
