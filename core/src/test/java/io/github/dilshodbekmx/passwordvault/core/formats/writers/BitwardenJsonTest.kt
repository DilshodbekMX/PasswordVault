package io.github.dilshodbekmx.passwordvault.core.formats.writers

import io.github.dilshodbekmx.passwordvault.core.formats.BitwardenJson
import io.github.dilshodbekmx.passwordvault.core.formats.BitwardenJson.NotImported.What
import io.github.dilshodbekmx.passwordvault.core.formats.BitwardenJson.Read
import io.github.dilshodbekmx.passwordvault.core.formats.IsoTime
import io.github.dilshodbekmx.passwordvault.core.formats.writers.WriterFixtures.DAY
import io.github.dilshodbekmx.passwordvault.core.formats.writers.WriterFixtures.GENERATOR
import io.github.dilshodbekmx.passwordvault.core.formats.writers.WriterFixtures.HOUR
import io.github.dilshodbekmx.passwordvault.core.formats.writers.WriterFixtures.T0
import io.github.dilshodbekmx.passwordvault.core.formats.writers.WriterFixtures.Texts
import io.github.dilshodbekmx.passwordvault.core.formats.writers.WriterFixtures.entry
import io.github.dilshodbekmx.passwordvault.core.formats.writers.WriterFixtures.hidden
import io.github.dilshodbekmx.passwordvault.core.formats.writers.WriterFixtures.id
import io.github.dilshodbekmx.passwordvault.core.formats.writers.WriterFixtures.visible
import io.github.dilshodbekmx.passwordvault.core.model.Entry
import io.github.dilshodbekmx.passwordvault.core.model.HistoryItem
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.core.model.VaultEdits
import io.github.dilshodbekmx.passwordvault.core.util.StrictJson
import io.github.dilshodbekmx.passwordvault.core.util.Uuid
import io.github.dilshodbekmx.passwordvault.core.vaultfile.BodyCodec
import io.github.dilshodbekmx.passwordvault.core.vaultfile.BodyResult
import io.github.dilshodbekmx.passwordvault.testing.FakeRandom
import java.util.Random
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The JSON export in Bitwarden's layout and its reader (formats.md §5), with the fixes of
 * formats-review P11 and, at the level of this reader, P1, P2, P3 and P7.
 */
class BitwardenJsonTest {
    /** The time of an import, one day after the export: what a missing date becomes. */
    private val now = T0 + DAY

    /** The ids a foreign file's accounts get: from 900,001 on. */
    private fun newIds() = WriterFixtures.Ids(900_001)

    private fun export(entries: List<Entry>, vaultExtras: Map<String, String> = emptyMap()) =
        BitwardenJson.export(entries, T0, GENERATOR, vaultExtras)

    private fun exportText(entries: List<Entry>, vaultExtras: Map<String, String> = emptyMap()) =
        String(export(entries, vaultExtras).bytes, Charsets.UTF_8)

    private fun read(bytes: ByteArray, ids: WriterFixtures.Ids = newIds(), maxItems: Int = BitwardenJson.MAX_ITEMS): Read =
        BitwardenJson.read(bytes, now, Texts, ids::next, maxItems)

    private fun read(json: String): Read = read(json.toByteArray(Charsets.UTF_8))

    private fun ok(json: String): Read.Ok = read(json).let { assertTrue(it.toString(), it is Read.Ok); it as Read.Ok }

    private fun ok(bytes: ByteArray): Read.Ok = read(bytes).let { assertTrue(it.toString(), it is Read.Ok); it as Read.Ok }

    private fun unreadable(bytes: ByteArray): Read.Unreadable =
        read(bytes).let { assertTrue(it.toString(), it is Read.Unreadable); it as Read.Unreadable }

    private fun unreadable(json: String) = unreadable(json.toByteArray(Charsets.UTF_8))

    /** A foreign file with these items. */
    private fun bitwarden(vararg items: String) = """{"encrypted":false,"folders":[],"items":[${items.joinToString(",")}]}"""

    // ------------------------------------------------------------------ the file

    private val gmail = entry(
        1, "Gmail", "aziz@gmail.com", "N3w!pass",
        listOf("https://mail.google.com", "android://AbC123==@com.google.android.gm/"), "Recovery: phone",
        listOf(hidden("PIN code", "4321")),
        favourite = true, changePending = true, checkIgnored = true, createdAt = T0 - 400 * DAY, updatedAt = T0,
        passwordChangedAt = T0 - HOUR, pendingOldPassword = "Old#3",
        history = listOf(HistoryItem("Old#2", T0 - 30 * DAY)),
        extras = linkedMapOf("futureField" to "{\"a\":[1,2]}"),
    )

    @Test
    fun theLayoutIsExactlyTheOneOfFormatsMd() {
        val expected = """
            {
              "encrypted": false,
              "passwordVault": {
                "format": "passwordvault-export",
                "version": 1,
                "exportedAt": "2026-10-06T07:05:09.123Z",
                "generator": "PasswordVault 1.0"
              },
              "folders": [],
              "items": [
                {
                  "id": "00000000-0000-4000-8000-000000000001",
                  "organizationId": null,
                  "folderId": null,
                  "type": 1,
                  "reprompt": 0,
                  "name": "Gmail",
                  "notes": "Recovery: phone",
                  "favorite": true,
                  "fields": [
                    {
                      "name": "PIN code",
                      "value": "4321",
                      "type": 1,
                      "linkedId": null
                    }
                  ],
                  "login": {
                    "fido2Credentials": [],
                    "uris": [
                      {
                        "match": null,
                        "uri": "https://mail.google.com"
                      },
                      {
                        "match": null,
                        "uri": "androidapp://com.google.android.gm"
                      }
                    ],
                    "username": "aziz@gmail.com",
                    "password": "N3w!pass",
                    "totp": null
                  },
                  "passwordHistory": [
                    {
                      "lastUsedDate": "2026-09-06T07:05:09.123Z",
                      "password": "Old#2"
                    }
                  ],
                  "creationDate": "2025-09-01T07:05:09.123Z",
                  "revisionDate": "2026-10-06T07:05:09.123Z",
                  "deletedDate": null,
                  "collectionIds": null,
                  "passwordVault": {
                    "passwordChangedAt": "2026-10-06T06:05:09.123Z",
                    "changePending": true,
                    "checkIgnored": true,
                    "pendingOldPassword": "Old#3",
                    "urls": [
                      "https://mail.google.com",
                      "android://AbC123==@com.google.android.gm/"
                    ],
                    "futureField": {
                      "a": [
                        1,
                        2
                      ]
                    }
                  }
                },
                {
                  "id": "00000000-0000-4000-8000-000000000002",
                  "organizationId": null,
                  "folderId": null,
                  "type": 1,
                  "reprompt": 0,
                  "name": "Empty",
                  "notes": null,
                  "favorite": false,
                  "fields": [],
                  "login": {
                    "fido2Credentials": [],
                    "uris": [],
                    "username": null,
                    "password": null,
                    "totp": null
                  },
                  "passwordHistory": null,
                  "creationDate": "2026-10-06T07:05:09.123Z",
                  "revisionDate": "2026-10-06T07:05:09.123Z",
                  "deletedDate": null,
                  "collectionIds": null,
                  "passwordVault": {
                    "passwordChangedAt": null,
                    "changePending": false,
                    "checkIgnored": false,
                    "pendingOldPassword": null
                  }
                }
              ]
            }
        """.trimIndent() + "\n"
        val written = export(listOf(gmail, entry(2, "Empty")))
        assertEquals(expected, String(written.bytes, Charsets.UTF_8))
        assertEquals(2, written.items)
        assertEquals(0, written.replacedSurrogates)
        // UTF-8 without a byte-order mark; the text above is ASCII, so its bytes are its characters.
        assertArrayEquals(expected.toByteArray(Charsets.US_ASCII), written.bytes)
    }

    @Test
    fun anEmptyVaultIsAFileWithNoItems() {
        val text = exportText(emptyList())
        assertTrue(text.endsWith("  \"folders\": [],\n  \"items\": []\n}\n"))
        val back = ok(text)
        assertTrue(back.entries.isEmpty())
        assertTrue(back.ownFormat)
        assertFalse(back.newerVersion)
        assertTrue(back.notImported.isEmpty())
    }

    @Test
    fun stringsAreEscapedAsKotlinxSerializationWritesThemAndEverythingElseIsWrittenAsItself() {
        val awkward = "quote \" backslash \\ slash / tab \t lf \n cr \r bs \b ff \u000C nul \u0000 esc \u001B unit \u001F del \u007F " +
            "nbsp   ls   ps   bom ﻿ oʻzbek o‘zbek Ўзбек 😀 ￾"
        val e = entry(1, awkward, awkward, awkward, listOf("https://x.example/$awkward"), awkward, listOf(hidden(awkward, awkward)))
        val text = exportText(listOf(e))
        val escaped = "quote \\\" backslash \\\\ slash / tab \\t lf \\n cr \\r bs \\b ff \\f nul \\u0000 esc \\u001b unit \\u001f del \u007F " +
            "nbsp   ls   ps   bom ﻿ oʻzbek o‘zbek Ўзбек 😀 ￾"
        assertTrue(text.contains("\"name\": \"$escaped\","))
        assertTrue(text.contains("\"password\": \"$escaped\","))
        // The same escapes as the library's own writer (architecture.md §4.3).
        assertEquals("\"$escaped\"", JsonPrimitive(awkward).toString())
        // Every line break of the file is one of the layout: LF, never CR; the indent is two spaces a level.
        assertFalse(text.contains('\r'))
        assertTrue(text.endsWith("}\n"))
        for (line in text.trimEnd('\n').split('\n')) {
            val indent = line.length - line.trimStart(' ').length
            assertEquals(line, 0, indent % 2)
            assertFalse(line, line.endsWith(" "))
        }
        assertEquals(listOf(e), ok(text).entries)
    }

    @Test
    fun emptyTextsAreWrittenAsNullAsBitwardenDoes() {
        val text = exportText(listOf(entry(1, "Only a name", fields = listOf(visible("Empty value", "")))))
        assertTrue(text.contains("\"notes\": null,"))
        assertTrue(text.contains("\"username\": null,"))
        assertTrue(text.contains("\"password\": null,"))
        assertTrue(text.contains("\"passwordHistory\": null,"))
        assertTrue(text.contains("\"value\": \"\","))
        assertFalse(text.contains("\"notes\": \"\""))
        assertEquals(listOf(entry(1, "Only a name", fields = listOf(visible("Empty value", "")))), ok(text).entries)
    }

    // ------------------------------------------------------------------ without loss

    @Test
    fun ourJsonReadsBackWithoutLoss() {
        // Every special entry but the deleted one and the one with unpaired surrogates: JSON keeps
        // also what breaks the input rules (a tab, a carriage return, a 40,000-character note).
        val entries = WriterFixtures.specialEntries().filter { it.deletedAt == null && !it.title.contains('\uD800') } + gmail.copy(id = id(500))
        assertTrue(entries.size >= 23)
        val vaultExtras = linkedMapOf("futureTopKey" to "{\"n\":1.50,\"list\":[true,null,\"x\"]}", "futureFolders" to "[]")
        val written = export(entries, vaultExtras)
        assertEquals(entries.size, written.items)
        val back = ok(written.bytes)
        assertTrue(back.ownFormat)
        assertFalse(back.newerVersion)
        assertTrue(back.notImported.toString(), back.notImported.isEmpty())
        assertEquals(0, back.byteOrderMarks)
        assertEquals(entries.size, back.entries.size)
        for ((want, got) in entries.zip(back.entries)) assertTrue("entry ${want.id}", want == got)
        assertEquals(vaultExtras, back.vaultExtras)
        assertEquals(vaultExtras.keys.toList(), back.vaultExtras.keys.toList())
        // Written again from what was read: the same bytes.
        assertArrayEquals(written.bytes, export(back.entries, back.vaultExtras).bytes)
    }

    @Test
    fun aVaultIsExportedWithItsAccountsOutsideRecentlyDeletedAndItsUnknownKeys() {
        val vault = Vault(
            entries = listOf(gmail, entry(2, "Deleted", "gone", "Deleted#Pass", deletedAt = T0), entry(3, "Bank", "ali", "B@nk1")),
            extras = linkedMapOf("futureTopKey" to "{\"n\":1.50}"),
        )
        val written = BitwardenJson.export(vault, T0, GENERATOR)
        assertArrayEquals(export(listOf(gmail, vault.entries[2]), vault.extras).bytes, written.bytes)
        assertEquals(2, written.items)
    }

    @Test
    fun deletedAccountsAreNotWritten() {
        val deleted = entry(2, "Deleted account", "gone@example.com", "Deleted#Pass", listOf("https://deleted.example"),
            "must not be exported", listOf(hidden("Deleted PIN", "9753")), deletedAt = T0 - DAY,
            history = listOf(HistoryItem("Deleted#Old", T0 - 2 * DAY)), pendingOldPassword = "Deleted#Pending", changePending = true)
        val text = exportText(listOf(entry(1, "Kept"), deleted, entry(3, "Kept too")))
        for (gone in listOf("Deleted", "gone@example.com", "deleted.example", "must not be exported", "9753", id(2))) {
            assertFalse(gone, text.contains(gone))
        }
        // No item says it is deleted, and deletedAt is never written (D-10c).
        assertFalse(text.contains("deletedAt"))
        assertEquals(2, Regex("\"deletedDate\": null,").findAll(text).count())
        assertEquals(listOf("Kept", "Kept too"), ok(text).entries.map { it.title })
        assertEquals(2, export(listOf(entry(1, "Kept"), deleted, entry(3, "Kept too"))).items)
    }

    @Test
    fun aPendingChangeWritesTheNewPasswordAndKeepsTheOldOne() {
        val pending = entry(1, "Gmail", "aziz@gmail.com", "N3w!pass", listOf("https://mail.google.com"),
            changePending = true, pendingOldPassword = "Old#2")
        val text = exportText(listOf(pending))
        assertTrue(text.contains("\"password\": \"N3w!pass\","))
        assertTrue(text.contains("\"changePending\": true,"))
        assertTrue(text.contains("\"pendingOldPassword\": \"Old#2\""))
        val back = ok(text).entries.single()
        assertEquals("N3w!pass", back.password)
        assertEquals("Old#2", back.pendingOldPassword)
        assertTrue(back.changePending)

        // A file that keeps an old password and says nothing else: a change is pending (D-10d).
        val silent = text.replace("        \"changePending\": true,\n", "")
        assertNotEquals(text, silent)
        assertTrue(ok(silent).entries.single().changePending)
        // The website had no password before the change: the empty old password is kept as such.
        val empty = pending.copy(pendingOldPassword = "")
        assertTrue(exportText(listOf(empty)).contains("\"pendingOldPassword\": \"\""))
        assertEquals(empty, ok(exportText(listOf(empty))).entries.single())
        // States the app never writes come back as they were all the same.
        for (odd in listOf(pending.copy(pendingOldPassword = null), pending.copy(changePending = false))) {
            assertEquals(odd, ok(exportText(listOf(odd))).entries.single())
        }
    }

    @Test
    fun androidAppLinksAreWrittenInBitwardensFormAndTheOriginalsAreKept() {
        assertEquals("androidapp://com.example.app", BitwardenJson.toBitwardenUri("android://AbC123==@com.example.app/"))
        assertEquals("androidapp://com.example.app", BitwardenJson.toBitwardenUri("ANDROID://AbC123==@com.example.app"))
        assertEquals("https://example.com/a@b", BitwardenJson.toBitwardenUri("https://example.com/a@b"))
        assertEquals("androidapp://com.example.app", BitwardenJson.toBitwardenUri("androidapp://com.example.app"))
        assertEquals("android://no-hash/", BitwardenJson.toBitwardenUri("android://no-hash/"))

        val app = entry(1, "App", "u", "p", listOf("https://a.example", "android://AbC123==@com.example.app/"))
        val text = exportText(listOf(app))
        assertTrue(text.contains("\"uri\": \"androidapp://com.example.app\""))
        assertTrue(text.contains("\"urls\": [\n          \"https://a.example\",\n          \"android://AbC123==@com.example.app/\"\n        ]"))
        assertEquals(app.urls, ok(text).entries.single().urls)
        // Nothing to keep when the two lists are the same.
        assertFalse(exportText(listOf(entry(1, "Web", "u", "p", listOf("https://a.example", "androidapp://com.example.app")))).contains("\"urls\""))
        // A blank website (never stored by the app) is no uri; the entry's own list still comes back.
        val blank = entry(1, "Blank", "u", "p", listOf("https://a.example", " ", "https://b.example"))
        val blankText = exportText(listOf(blank))
        assertEquals(2, Regex("\"uri\": ").findAll(blankText).count())
        assertEquals(blank.urls, ok(blankText).entries.single().urls)
    }

    // ------------------------------------------------------------------ unknown keys (formats-review P11)

    @Test
    fun p11_unknownKeysOfOurExtensionAreKeptAtBothLevelsAndOnlyThere() {
        val own = exportText(listOf(entry(1, "Own", "u", "p", listOf("https://o.example"))))
            .replace("\"format\": \"passwordvault-export\",", "\"format\": \"passwordvault-export\",\n    \"rootFuture\": 7,")
            .replace("\"collectionIds\": null,", "\"collectionIds\": null,\n      \"itemFuture\": 5,")
            .replace("\"pendingOldPassword\": null", "\"pendingOldPassword\": null,\n        \"extFuture\": {\"a\": 1.50, \"b\": [\"x\", null]}")
            .replace("\"encrypted\": false,", "\"encrypted\": false,\n  \"topFuture\": true,")
        val back = ok(own)
        assertTrue(back.ownFormat)
        assertEquals(mapOf("rootFuture" to "7"), back.vaultExtras)
        assertEquals(mapOf("extFuture" to "{\"a\":1.50,\"b\":[\"x\",null]}"), back.entries.single().extras)
        // Written again, both are there; keys outside our two objects are not ours to keep.
        val again = exportText(back.entries, back.vaultExtras)
        assertTrue(again.contains("    \"generator\": \"PasswordVault 1.0\",\n    \"rootFuture\": 7\n  },"))
        assertTrue(again.contains("\"extFuture\": {\n          \"a\": 1.50,\n          \"b\": [\n            \"x\",\n            null\n          ]\n        }"))
        assertFalse(again.contains("itemFuture"))
        assertFalse(again.contains("topFuture"))
        assertEquals(back.entries, ok(again).entries)
        assertEquals(back.vaultExtras, ok(again).vaultExtras)
    }

    @Test
    fun p11_aFileOfALaterVersionIsReadAndSaysSo() {
        val later = exportText(listOf(entry(1, "Own"))).replace("\"version\": 1,", "\"version\": 2,\n    \"folders\": [{\"id\": \"f\", \"name\": \"Ish\"}],")
        val back = ok(later)
        assertTrue(back.ownFormat)
        assertTrue(back.newerVersion)
        assertEquals(mapOf("folders" to "[{\"id\":\"f\",\"name\":\"Ish\"}]"), back.vaultExtras)
        assertEquals("Own", back.entries.single().title)
    }

    @Test
    fun whatTheReaderKeepsTheVaultCanStore() {
        // Every name a vault file uses itself, as a key of our extension: none may come back as an
        // unknown key, or the vault's writer would refuse the entry or the vault.
        val entryKeys = listOf("id", "title", "username", "password", "notes", "urls", "fields", "favourite", "changePending",
            "checkIgnored", "createdAt", "updatedAt", "passwordChangedAt", "deletedAt", "pendingOldPassword", "passwordHistory")
        val topKeys = listOf("schema", "entries", "draft", "plainExports", "backups", "masterPasswordMatches", "securitySeenAt")
        val crafted = exportText(listOf(entry(1, "Crafted", "u", "p")))
            .replace("\"pendingOldPassword\": null", "\"pendingOldPassword\": null,\n" +
                listOf("id", "title", "username", "password", "notes", "fields", "favourite", "createdAt", "updatedAt", "deletedAt", "passwordHistory")
                    .joinToString(",\n") { "\"$it\": 12345" } + ",\n\"kept\": {\"deep\": [[[1]]]}")
            .replace("\"generator\": \"PasswordVault 1.0\"", "\"generator\": \"PasswordVault 1.0\",\n" +
                topKeys.joinToString(",\n") { "\"$it\": [1]" } + ",\n\"keptTop\": \"x\"")
        val back = ok(crafted)
        val e = back.entries.single()
        assertEquals(mapOf("kept" to "{\"deep\":[[[1]]]}"), e.extras)
        assertEquals(mapOf("keptTop" to "\"x\""), back.vaultExtras)
        assertEquals("Crafted", e.title) // the extension's "title" is not the title
        assertNull(e.deletedAt)
        assertTrue(back.notImported.isEmpty())

        // And the lists above are the vault codec's own: each of those names is refused there as an unknown key.
        for (key in entryKeys) {
            val bad = Vault(entries = listOf(e.copy(extras = mapOf(key to "1"))))
            assertTrue(key, runCatching { BodyCodec.write(bad) }.exceptionOrNull() is IllegalArgumentException)
        }
        for (key in topKeys) {
            assertTrue(key, runCatching { BodyCodec.write(Vault(extras = mapOf(key to "1"))) }.exceptionOrNull() is IllegalArgumentException)
        }
        // What was read goes through the vault's own codec and comes back the same, text for text.
        val vault = Vault(entries = back.entries, extras = back.vaultExtras)
        val stored = (BodyCodec.read(BodyCodec.write(vault)) as BodyResult.Opened).vault
        assertEquals(vault, stored)
    }

    @Test
    fun anUnknownValueThatNestsDeeperThanAVaultFileHoldsIsListedAndNotKept() {
        fun nested(levels: Int) = "[".repeat(levels) + "1" + "]".repeat(levels)
        val base = exportText(listOf(entry(1, "Deep", "u", "p")))
        fun file(entryLevels: Int, topLevels: Int) = base
            .replace("\"pendingOldPassword\": null", "\"pendingOldPassword\": null,\n\"deep\": ${nested(entryLevels)},\n\"flat\": 1")
            .replace("\"generator\": \"PasswordVault 1.0\"", "\"generator\": \"PasswordVault 1.0\",\n\"deepTop\": ${nested(topLevels)}")

        // 13 levels inside an entry and 15 at the top still fit the 16 levels of a vault file.
        val fits = ok(file(13, 15))
        assertEquals(mapOf("deep" to nested(13), "flat" to "1"), fits.entries.single().extras)
        assertEquals(mapOf("deepTop" to nested(15)), fits.vaultExtras)
        assertTrue(fits.notImported.isEmpty())
        val vault = Vault(entries = fits.entries, extras = fits.vaultExtras)
        assertEquals(vault, (BodyCodec.read(BodyCodec.write(vault)) as BodyResult.Opened).vault)

        // One level more: not kept, and listed, by position for the entry and with 0 for the top object.
        val tooDeep = ok(file(14, 16))
        assertEquals(mapOf("flat" to "1"), tooDeep.entries.single().extras)
        assertTrue(tooDeep.vaultExtras.isEmpty())
        assertEquals(listOf(0 to What.UNKNOWN_DATA, 1 to What.UNKNOWN_DATA), tooDeep.notImported.map { it.position to it.what })
        assertEquals(listOf(null, "Deep"), tooDeep.notImported.map { it.name })
        BodyCodec.write(Vault(entries = tooDeep.entries, extras = tooDeep.vaultExtras)) // does not throw
    }

    @Test
    fun unknownKeysThatCannotBeWrittenAreLeftOutOfTheExport() {
        val e = entry(1, "Odd extras", extras = linkedMapOf(
            "good" to "{\"a\":1}",
            "notJson" to "{broken",
            "urls" to "[\"https://evil.example\"]", // the name of one of our own keys
            "title" to "\"other\"",
            "alsoGood" to "\"x\"",
        ))
        val text = exportText(listOf(e), linkedMapOf("format" to "\"other-format\"", "version" to "99", "keptTop" to "1", "broken" to "]"))
        val back = ok(text) // no key twice, and still our own file
        assertTrue(back.ownFormat)
        assertFalse(back.newerVersion)
        assertEquals(mapOf("good" to "{\"a\":1}", "alsoGood" to "\"x\""), back.entries.single().extras)
        assertEquals(mapOf("keptTop" to "1"), back.vaultExtras)
        assertFalse(text.contains("evil.example"))

        // Two unknown keys that would be one name in the file (an unpaired surrogate is written as
        // U+FFFD): the first is written, and the file has no key twice.
        val twins = entry(1, "Twins", extras = linkedMapOf("k\uD800" to "1", "k\uFFFD" to "2", "k\uDC00" to "3", "other" to "4"))
        val twinsBack = ok(exportText(listOf(twins), linkedMapOf("t\uD800" to "true", "t\uFFFD" to "false")))
        assertEquals(mapOf("k\uFFFD" to "1", "other" to "4"), twinsBack.entries.single().extras)
        assertEquals(mapOf("t\uFFFD" to "true"), twinsBack.vaultExtras)
    }

    // ------------------------------------------------------------------ foreign files

    private fun fixture(): ByteArray =
        javaClass.getResourceAsStream("/formats/proton/bitwarden.json")!!.use { it.readBytes() }

    @Test
    fun theBitwardenFixtureOfFormatsMdIsMapped() {
        val back = ok(fixture())
        assertFalse(back.ownFormat)
        assertFalse(back.newerVersion)
        assertTrue(back.vaultExtras.isEmpty())
        val entries = back.entries
        assertEquals(
            listOf("LoginItemMultipleWebsites", "Credit Card Y", "IdentityItem", "NoteItem", "LoginItemEmptyFields", "LoginItemBrokenUrl",
                "Credit Card Y", "LoginItemMultipleWebsites", "LoginWithCustomFields", "test ssh", "DeepLogin", "ArchivedLogin"),
            entries.map { it.title },
        )
        // Ids of a foreign file are new ones, each once (the fixture repeats one of its own).
        assertEquals((900_001..900_012).map(::id), entries.map { it.id })

        val login = entries[0]
        assertEquals("username", login.username)
        assertEquals("password", login.password)
        assertEquals("login note", login.notes)
        assertEquals(listOf("https://test.url1/", "https://test.url2", "androidapp://ch.protonmail.android"), login.urls)
        assertEquals(
            listOf(visible("Text 1", "Text 1 content"), hidden("Hidden 1", "Hidden 1 content"),
                hidden("TOTP", "otpauth://totp/test?secret=proton333&issuer=proton")),
            login.fields,
        )
        assertFalse(login.favourite)
        assertEquals(now, login.createdAt) // the fixture's logins have no dates
        assertEquals(now, login.updatedAt)
        assertNull(login.passwordChangedAt)
        assertTrue(login.passwordHistory.isEmpty())

        // A card: its values as fields, the number and the code hidden; its folder as a visible field.
        val card = entries[1]
        assertEquals("", card.password)
        assertEquals("Credit Card Y AMEX note", card.notes)
        assertEquals(
            listOf(visible("cardholderName", "A B"), visible("brand", "Amex"), hidden("number", "374242424242424"),
                visible("expMonth", "1"), visible("expYear", "2025"), hidden("code", "123"), visible("Folder", "custom folder")),
            card.fields,
        )
        assertEquals(listOf(visible("firstName", "John"), visible("middleName", "F"), visible("lastName", "Kennedy")), entries[2].fields)
        assertEquals("note content", entries[3].notes)
        assertTrue(entries[3].fields.isEmpty())
        assertEquals(listOf("htt:/test.url1/"), entries[5].urls) // kept as written: the website rule is the importer's
        assertEquals("", entries[5].notes)

        // Custom fields: text, hidden, a checkbox as text; the linked one is not imported and is listed.
        val custom = entries[8]
        assertEquals(
            listOf(visible("[TEXT]", "hello"), hidden("[HIDDEN]", "foobar"), visible("[CHECKBOX]", "true"),
                hidden("TOTP", "otpauth://totp/test?secret=proton333&issuer=proton"), visible("Folder", "custom folder")),
            custom.fields,
        )
        assertEquals(listOf(9 to What.LINKED_FIELD), back.notImported.map { it.position to it.what })
        assertEquals("LoginWithCustomFields", back.notImported.single().name)

        // An SSH key: three hidden fields, the private key with its line breaks; its dates are read.
        val ssh = entries[9]
        assertEquals(listOf("privateKey", "publicKey", "keyFingerprint"), ssh.fields.map { it.name })
        assertTrue(ssh.fields.all { it.secret })
        assertEquals("-----BEGIN TEST -----\n00000000000000\n-----END TEST-----\n", ssh.fields[0].value)
        assertEquals(IsoTime.parse("2025-02-17T09:30:27.266Z"), ssh.createdAt)
        assertEquals(ssh.createdAt, ssh.updatedAt)
        assertEquals(listOf(visible("Folder", "custom folder/nested/deep")), entries[10].fields)
        assertEquals(listOf(visible("Folder", "Archive/2024")), entries[11].fields)
    }

    @Test
    fun ourOwnFileWithoutItsExtensionIsReadAsAForeignOne() {
        // The prototype's check: the same items, the two passwordVault objects removed.
        val full = WriterFixtures.specialEntries().first { it.title == "Full entry" }
        val root = StrictJson.parseObject(exportText(listOf(full)), 32)
        val stripped = JsonObject(
            root.filterKeys { it != "passwordVault" } +
                ("items" to JsonArray((root["items"] as JsonArray).map { JsonObject((it as JsonObject).filterKeys { k -> k != "passwordVault" }) })),
        )
        val back = ok(stripped.toString())
        assertFalse(back.ownFormat)
        val e = back.entries.single()
        assertEquals(id(900_001), e.id)
        assertEquals(full.title, e.title)
        assertEquals(full.username, e.username)
        assertEquals(full.password, e.password)
        assertEquals(full.notes, e.notes)
        assertEquals(full.fields, e.fields)
        assertEquals(full.favourite, e.favourite)
        assertEquals(full.passwordHistory, e.passwordHistory)
        assertEquals(full.createdAt, e.createdAt)
        assertEquals(full.updatedAt, e.updatedAt)
        // What only the extension carries is gone: Bitwarden has no key for it.
        assertNull(e.passwordChangedAt)
        assertFalse(e.checkIgnored)
        assertEquals(listOf("https://a.example/login", "https://b.example", "androidapp://com.example.app"), e.urls)
    }

    @Test
    fun aForeignFilesExtensionObjectsAreNotRead() {
        // Only passwordVault.format at the top makes a file ours; an item's extension alone does not.
        val foreign = bitwarden("""{"type":1,"id":"${id(7)}","name":"A","login":{"password":"p","uris":[{"uri":"https://a.example"}]},
            "passwordVault":{"changePending":true,"checkIgnored":true,"pendingOldPassword":"x","urls":["https://evil.example"],"future":1}}""")
        val e = ok(foreign).entries.single()
        assertEquals(id(900_001), e.id)
        assertEquals(listOf("https://a.example"), e.urls)
        assertFalse(e.changePending)
        assertFalse(e.checkIgnored)
        assertNull(e.pendingOldPassword)
        assertTrue(e.extras.isEmpty())
    }

    @Test
    fun foldersCollectionsOneTimeCodesAndOtherItemTypes() {
        val file = """{"encrypted":false,
            "collections":[{"id":"c1","name":"Ish"},{"id":"c2","name":"  "},{"id":3,"name":"Raqam"},"x"],
            "items":[
              {"type":1,"name":"In a collection","collectionIds":["c1","c2"],"login":{"username":"u","password":"p","totp":"JBSWY3DP"}},
              {"type":1,"name":"Unnamed folder","folderId":"c2","login":{"password":"p","totp":"  "}},
              {"type":"1","name":"Type as text","folderId":"3","login":{"password":"p"}},
              {"type":99,"name":"Unknown type","futureObject":{"secretCode":"S1","label":"L","count":3,"on":true,"nested":{"x":1},"none":null,"blank":" "},"card":{"number":"4111"}},
              {"type":2,"name":"Note","notes":"n","secureNote":{"type":0},"login":{"password":"ignored"}},
              {"name":"No type","login":{"username":"u2","password":"p2"}}
            ]}"""
        val back = ok(file)
        val e = back.entries
        assertEquals(listOf(hidden("TOTP", "JBSWY3DP"), visible("Folder", "Ish")), e[0].fields)
        assertTrue(e[1].fields.isEmpty()) // a folder without a name, a blank one-time code
        assertEquals(listOf(visible("Folder", "Raqam")), e[2].fields)
        assertEquals("p", e[2].password)
        // The first object of an unknown type is its body: every value that is text, a number or a boolean.
        assertEquals(listOf(hidden("secretCode", "S1"), visible("label", "L"), visible("count", "3"), visible("on", "true")), e[3].fields)
        assertEquals("", e[4].password)
        assertEquals("n", e[4].notes)
        assertEquals("p2", e[5].password)
        assertTrue(back.notImported.isEmpty())
    }

    @Test
    fun valuesOfTheWrongKindNeverStopTheReader() {
        val file = bitwarden(
            """{"type":1,"name":42,"notes":7,"favorite":"yes","fields":{"a":1},"login":"text","passwordHistory":"none","creationDate":12}""",
            """{"type":1,"name":"Odd parts","fields":[1,null,"x",{"name":null,"value":5,"type":"1"},{"type":0}],
                "login":{"username":true,"password":0,"uris":[1,{"uri":null},{"uri":7},{"match":0}],"fido2Credentials":{}},
                "passwordHistory":[1,{"password":null},{"password":"old","lastUsedDate":"not a date"},{"password":"older","lastUsedDate":5}],
                "revisionDate":"2026-13-45T00:00:00Z","folderId":7,"collectionIds":"c"}""",
            """{"type":{},"name":["x"],"login":{"password":"p"}}""",
        )
        val e = ok(file).entries
        assertEquals("42", e[0].title)
        assertEquals("7", e[0].notes)
        assertFalse(e[0].favourite) // only the boolean true
        assertEquals("", e[0].password)
        assertEquals("Odd parts", e[1].title)
        assertEquals(listOf(hidden("", "5"), visible("", "")), e[1].fields)
        assertEquals("true", e[1].username)
        assertEquals("0", e[1].password)
        assertEquals(listOf("7"), e[1].urls)
        assertEquals(listOf(HistoryItem("old", now), HistoryItem("older", now)), e[1].passwordHistory)
        assertEquals(now, e[1].updatedAt)
        assertEquals("p", e[2].password)
        assertEquals("Nomsiz $now", e[2].title) // a name that is no text is no name
    }

    // ------------------------------------------------------------------ formats-review P11

    @Test
    fun p11_theRowLimitAppliesToItems() {
        fun file(n: Int) = "{\"encrypted\":false,\"folders\":[],\"items\":[" +
            (1..n).joinToString(",") { "{\"type\":1,\"name\":\"N$it\",\"login\":{\"username\":\"u$it\",\"password\":\"p\"}}" } + "]}"
        assertEquals(10_000, BitwardenJson.MAX_ITEMS)
        assertEquals(10_000, ok(file(10_000)).entries.size)
        val tooMany = read(file(10_001))
        assertTrue(tooMany is Read.TooMany)
        assertEquals(10_001, (tooMany as Read.TooMany).items)
        assertEquals(10_000, tooMany.limit)
        // The review's 20,000 items; and the limit counts elements, whatever they are.
        assertTrue(read(file(20_000)) is Read.TooMany)
        val nulls = "{\"items\":[" + "null,".repeat(10_000) + "null]}"
        assertTrue(read(nulls) is Read.TooMany)
        assertEquals(3, (read(file(3).toByteArray(), maxItems = 2) as Read.TooMany).items)
    }

    @Test
    fun p11_anItemWithoutANameGetsATitleAsOnTheCsvPath() {
        val file = bitwarden(
            """{"type":1,"name":null,"login":{"username":"aziz","password":"p","uris":[{"uri":"https://www.Example.com/login"},{"uri":"https://b.example"}]}}""",
            """{"type":1,"login":{"username":"aziz","password":"p","uris":[{"uri":"android://AbC123==@com.example.app/"}]}}""",
            """{"type":1,"name":"","login":{"username":" aziz@example.com ","password":"p"}}""",
            """{"type":1,"name":"   ","login":{"password":"only a password"}}""",
            """{"type":2,"name":null,"notes":"only a note"}""",
        )
        val back = ok(file)
        assertEquals(listOf("example.com", "com.example.app", "aziz@example.com", "Nomsiz $now", "Nomsiz $now"), back.entries.map { it.title })
        assertEquals(" aziz@example.com ", back.entries[2].username) // only the title is made up; nothing is trimmed here
        assertTrue(back.notImported.isEmpty())

        assertEquals("example.com", BitwardenJson.titleFor(listOf(" ", "example.com/x"), "user", now, Texts))
        assertEquals("user", BitwardenJson.titleFor(emptyList(), " user ", now, Texts))
        assertEquals("Nomsiz 5", BitwardenJson.titleFor(emptyList(), "  ", 5, Texts))
        assertEquals(200, BitwardenJson.titleFor(emptyList(), "u".repeat(300), now, Texts).length)
    }

    @Test
    fun p11_everythingNotImportedIsListed() {
        val file = bitwarden(
            "null", "42", "\"text\"", "[1,2]",
            """{"id":"i3","type":1,"login":null,"name":null}""",
            """{"type":1,"name":"Trashed","deletedDate":"2025-01-01T00:00:00.000Z","login":{"password":"p"}}""",
            """{"type":1,"name":"With passkey","login":{"password":"p1","fido2Credentials":[{"credentialId":"c"}]}}""",
            """{"type":1,"name":"Linked","login":{"password":"p2"},"fields":[{"name":"L","value":"username","type":3,"linkedId":100},{"name":"L2","type":3}]}""",
            """{"type":1,"name":"Kept","login":{"password":"p3","fido2Credentials":[]},"deletedDate":null}""",
            """{"type":1,"name":"Only a name"}""",
        )
        val back = ok(file)
        assertEquals(listOf("With passkey", "Linked", "Kept", "Only a name"), back.entries.map { it.title })
        assertEquals("p1", back.entries[0].password) // the item is imported, its passkey is not
        assertTrue(back.entries[1].fields.isEmpty())
        assertEquals(
            listOf(
                1 to What.NOT_AN_ITEM, 2 to What.NOT_AN_ITEM, 3 to What.NOT_AN_ITEM, 4 to What.NOT_AN_ITEM,
                5 to What.EMPTY, 6 to What.IN_TRASH, 7 to What.PASSKEYS, 8 to What.LINKED_FIELD, // once for its two linked fields
            ),
            back.notImported.map { it.position to it.what },
        )
        // By name where there is one, by position otherwise.
        assertEquals(listOf(null, null, null, null, null, "Trashed", "With passkey", "Linked"), back.notImported.map { it.name })
        // A name is account data: the text of a list entry does not show it.
        assertFalse(back.notImported.toString().contains("Trashed"))
    }

    @Test
    fun p11_onlyTheBooleanTrueMarksAFileAsEncrypted() {
        val protected = read("""{"encrypted": true, "passwordProtected": true, "salt": "x", "data": "y"}""")
        assertTrue(protected is Read.Encrypted)
        assertTrue((protected as Read.Encrypted).passwordProtected)
        val account = read("""{"encrypted": true, "encKeyValidation_DO_NOT_EDIT": "2.x", "items": []}""")
        assertFalse((account as Read.Encrypted).passwordProtected)
        // Bitwarden's isUnencrypted is `encrypted !== true`: the string "true" is not refused.
        val text = ok("""{"encrypted":"true","folders":[],"items":[{"type":1,"name":"A","login":{"password":"p"}}]}""")
        assertEquals("p", text.entries.single().password)
        assertEquals(1, ok("""{"encrypted":1,"items":[{"type":1,"name":"A"}]}""").entries.size)
        assertEquals(1, ok("""{"items":[{"type":1,"name":"A"}]}""").entries.size)
    }

    @Test
    fun p11_everyLeadingByteOrderMarkIsRemoved() {
        val json = bitwarden("""{"type":1,"name":"Почта","login":{"password":"Пароль1"}}""").toByteArray(Charsets.UTF_8)
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        for (marks in 0..3) {
            var bytes = json
            repeat(marks) { bytes = bom + bytes }
            val back = ok(bytes)
            assertEquals(marks, back.byteOrderMarks)
            assertEquals("Пароль1", back.entries.single().password)
        }
        // The file is not changed by being read.
        val withMarks = bom + bom + json
        val copy = withMarks.copyOf()
        read(withMarks)
        assertArrayEquals(copy, withMarks)
        // Offsets of a failure count from the start of the file, marks included.
        val broken = bom + bom + "{\"items\":[}".toByteArray()
        assertEquals(6L + 10, unreadable(broken).error.offset)
    }

    @Test
    fun aForeignFilesPreviousPasswordsAreCutToTheNewestFiveAndListed() {
        fun history(n: Int) = (1..n).joinToString(",") { """{"lastUsedDate":"2026-01-${it.toString().padStart(2, '0')}T00:00:00.000Z","password":"old$it"}""" }
        val foreign = ok(bitwarden(
            """{"type":1,"name":"Seven","login":{"password":"p"},"passwordHistory":[${history(7)}]}""",
            """{"type":1,"name":"Five","login":{"password":"p"},"passwordHistory":[${history(5)}]}""",
        ))
        assertEquals(listOf("old7", "old6", "old5", "old4", "old3"), foreign.entries[0].passwordHistory.map { it.password })
        assertEquals(listOf("old5", "old4", "old3", "old2", "old1"), foreign.entries[1].passwordHistory.map { it.password }) // newest first
        assertEquals(listOf(1 to What.PREVIOUS_PASSWORDS), foreign.notImported.map { it.position to it.what })
        // Our own file comes back as it was written.
        val seven = entry(1, "Seven", "u", "p", history = (1..7).map { HistoryItem("old$it", T0 - it * DAY) })
        val own = ok(exportText(listOf(seven)))
        assertEquals(seven, own.entries.single())
        assertTrue(own.notImported.isEmpty())
    }

    // ------------------------------------------------------------------ files that are refused

    @Test
    fun p2_aByteThatIsNotUtf8RefusesTheFileAndNeverSwitchesItsEncoding() {
        // The review's trigger: our own JSON with one byte of a Cyrillic letter changed to 0xFF.
        val bytes = export(listOf(entry(1, "Почта", "иван", "Пароль1", notes = "Иккинчи қатор"))).bytes
        val text = String(bytes, Charsets.UTF_8)
        val at = text.substring(0, text.indexOf("Иккинчи") + 2).toByteArray(Charsets.UTF_8).size
        bytes[at] = 0xFF.toByte()
        val error = unreadable(bytes).error
        assertEquals(StrictJson.Reason.MALFORMED_UTF8.code, error.reason)
        assertEquals(at.toLong(), error.offset)
        // UTF-16, which a JSON file must not be, is refused at its first byte.
        val utf16 = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "{\"items\":[]}".toByteArray(Charsets.UTF_16LE)
        assertEquals(0L, unreadable(utf16).error.offset)
        // Windows-1251 with Cyrillic letters is not UTF-8 either.
        assertEquals(StrictJson.Reason.MALFORMED_UTF8.code, unreadable("{\"items\":[{\"name\":\"Почта\"}]}".toByteArray(charset("windows-1251"))).error.reason)
    }

    @Test
    fun p3_deepNestingIsRefusedWithoutOverflowingTheStack() {
        // The review's 4 KB file: {"items": and about 2,000 [ .
        val n = 2_033
        val file = "{\"items\":" + "[".repeat(n) + "]".repeat(n) + "}"
        var result: Read? = null
        var thrown: Throwable? = null
        val thread = Thread(null, {
            try {
                result = read(file)
            } catch (t: Throwable) {
                thrown = t
            }
        }, "small-stack", 256L * 1024)
        thread.start()
        thread.join()
        assertNull(thrown)
        val error = (result as Read.Unreadable).error
        assertEquals(StrictJson.Reason.TOO_DEEP.code, error.reason)
        // The 33rd container: the object is the first, so it is the 32nd [ , 31 characters after the first.
        assertEquals("{\"items\":".length + 31L, error.offset)
        assertEquals(32, BitwardenJson.MAX_DEPTH)
        // A file that is exactly 32 deep is read (and its odd items are listed).
        val deepest = "{\"items\":[" + "[".repeat(30) + "]".repeat(30) + "]}"
        assertEquals(listOf(What.NOT_AN_ITEM), ok(deepest).notImported.map { it.what })
    }

    @Test
    fun filesThatAreNotJsonOrNotAnExportAreRefusedWithTheirReason() {
        fun reason(json: String) = unreadable(json).error.reason
        assertEquals("duplicate-key", reason("""{"items":[{"name":"Dup keys","login":{"password":"first","password":"second"}}]}"""))
        assertEquals("unexpected-character", reason("""{"encrypted":false, /* c */ "items":[]}"""))
        assertEquals("unexpected-character", reason("""{"encrypted":false,"items":[],}"""))
        assertEquals("not-an-object", reason("""[{"type":1,"name":"A","login":{"password":"p"}}]"""))
        assertEquals("not-an-object", reason("\"text\""))
        assertEquals("end-of-input", reason(""))
        assertEquals("end-of-input", reason("   "))
        assertEquals("end-of-input", reason("""{"items":[{"name":"cut off"""))
        assertEquals("trailing-characters", reason("""{"items":[]} x"""))
        assertEquals("unexpected-character", reason("name,url,username,password,note\nA,https://a.example,u,p,\n"))
        assertEquals("control-character", reason("{\"items\":[{\"name\":\"line\nbreak\"}]}"))
        // JSON, but not an export.
        assertEquals(Read.NotAnExport, read("{}"))
        assertEquals(Read.NotAnExport, read("""{"encrypted":false,"items":{}}"""))
        assertEquals(Read.NotAnExport, read("""{"encrypted":false,"items":null}"""))
        assertEquals(Read.NotAnExport, read("""{"encrypted":false,"folders":[]}"""))
    }

    @Test
    fun aRefusalNeverCarriesAPieceOfTheFile() {
        val secret = "Tr0ub4dor&3"
        val files = listOf(
            """{"items":[{"name":"$secret","login":{"password":"$secret","password":"$secret"}}]}""",
            """{"items":[{"name":"$secret" "$secret"}]}""",
            """{"items":[{"name":"$secret""",
            """{"$secret":$secret}""",
            "$secret,$secret",
            """{"items":[{"name":"\u$secret"}]}""",
        )
        val allowed = StrictJson.Reason.entries.map { it.code }.toSet() + BitwardenJson.REASON_PARSER_FAILURE
        for (file in files) {
            val result = read(file)
            val error = (result as Read.Unreadable).error
            assertTrue(error.reason, error.reason in allowed)
            assertFalse(file, (result.toString() + error + error.reason).contains(secret))
        }
        // A file that is read says nothing in its text either.
        val read = ok(exportText(listOf(entry(1, secret, secret, secret))))
        assertFalse(read.toString().contains(secret))
        assertFalse(export(listOf(entry(1, secret, secret, secret))).toString().contains(secret))
    }

    @Test
    fun theReaderNeverThrowsWhateverTheBytes() {
        val good = export(WriterFixtures.specialEntries().take(8) + gmail.copy(id = id(600))).bytes
        val random = Random(311)
        var readable = 0
        repeat(3_000) {
            val bytes = good.copyOf(if (random.nextInt(4) == 0) random.nextInt(good.size) else good.size)
            repeat(1 + random.nextInt(3)) {
                if (bytes.isNotEmpty()) {
                    val at = random.nextInt(bytes.size)
                    bytes[at] = when (random.nextInt(4)) {
                        0 -> random.nextInt(256).toByte()
                        1 -> "{}[]\",:\\".random(kotlin.random.Random(random.nextLong())).code.toByte()
                        2 -> bytes[random.nextInt(bytes.size)]
                        else -> (bytes[at] + 1).toByte()
                    }
                }
            }
            when (val result = read(bytes)) {
                is Read.Ok -> {
                    readable++
                    // Whatever was read can be stored: the vault's own codec takes it.
                    BodyCodec.write(Vault(entries = result.entries.mapIndexed { i, e -> e.copy(id = id(i + 1)) }, extras = result.vaultExtras))
                }
                is Read.Unreadable, is Read.Encrypted, is Read.NotAnExport, is Read.TooMany -> Unit
            }
        }
        assertTrue("readable $readable", readable in 100..2_900)
    }

    // ------------------------------------------------------------------ ids (formats-review P1)

    @Test
    fun p1_idsOfOurOwnFileAreReturnedAsWrittenAndTheVaultKeepsOnlyFreeOnes() {
        val gmailEntry = entry(1, "Gmail", "aziz@gmail.com", "Old#1", listOf("https://mail.google.com"))
        val bank = entry(2, "Bank", "aziz", "B@nk1", listOf("https://bank.uz"))
        val exported = export(listOf(gmailEntry, bank)).bytes
        // The user changes Gmail's password after exporting, then imports the file.
        val vault = Vault(entries = listOf(gmailEntry.copy(password = "New#2", updatedAt = T0 + 1_000), bank))
        val incoming = ok(exported).entries
        assertEquals(listOf(id(1), id(2)), incoming.map { it.id }) // as written: the reader decides nothing
        // The importer's plan would skip the unchanged Bank; here the model's rule alone is on trial.
        val result = VaultEdits.addImported(incoming, FakeRandom(5), Texts::fromFile)(vault, now)
        val ids = result.vault.entries.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.all(Uuid::isValid))
        assertEquals(listOf("Gmail", "Bank", "Gmail (fayldan)", "Bank"), result.vault.entries.map { it.title })

        // The second trigger: an own file in which two items share one id.
        val twice = export(listOf(gmailEntry, gmailEntry.copy(title = "Gmail copy", urls = listOf("https://gmail.com")))).bytes
        val shared = ok(twice).entries
        assertEquals(listOf(id(1), id(1)), shared.map { it.id })
        val added = VaultEdits.addImported(shared, FakeRandom(5), Texts::fromFile)(Vault(), now).vault.entries.map { it.id }
        assertEquals(2, added.toSet().size)

        // An id that is not text, or missing, in our own file: a new one.
        val text = String(exported, Charsets.UTF_8)
        val noIds = text.replace("\"id\": \"${id(1)}\",", "\"id\": 17,").replace("      \"id\": \"${id(2)}\",\n", "")
        assertEquals(listOf(id(900_001), id(900_002)), ok(noIds).entries.map { it.id })
    }

    // ------------------------------------------------------------------ unpaired surrogates (formats-review P7)

    @Test
    fun p7_unpairedSurrogatesAreWrittenAsTheReplacementCharacterAndCounted() {
        val lone = entry(1, "T\uD800x", "u\uDC00", "p\uD83D", listOf("https://x.example/a\uD800"), "n\uDE00\uD83Dz",
            listOf(hidden("F\uD800", "v\uDFFF")), pendingOldPassword = "o\uD800", changePending = true,
            history = listOf(HistoryItem("h\uDFFF", T0)), extras = linkedMapOf("k\uD800" to "\"x\uDC00\""))
        val written = export(listOf(lone, entry(2, "fine 😀")), linkedMapOf("top\uD800" to "[\"\uDBFF\"]"))
        val text = String(written.bytes, Charsets.UTF_8)
        // What a UTF-8 encoder writes for an unpaired surrogate is "?": there is none in the file.
        assertFalse(text.contains('?'))
        assertArrayEquals(text.toByteArray(Charsets.UTF_8), written.bytes)
        assertTrue(text.contains("\"name\": \"T�x\","))
        assertTrue(text.contains("\"password\": \"p�\","))
        assertTrue(text.contains("\"fine 😀\""))
        // Title 1, notes 2, field 2, uri 1, username 1, password 1, history 1, old password 1, the
        // unknown key 2 (its name and its value), and the vault's unknown key 2.
        assertEquals(14, written.replacedSurrogates)
        val back = ok(written.bytes)
        assertEquals("p�", back.entries[0].password)
        assertEquals(mapOf("k�" to "\"x�\""), back.entries[0].extras)
        assertEquals(mapOf("top�" to "[\"�\"]"), back.vaultExtras)

        // An escaped lone surrogate in a file that is read: the entry's text is as the file has it (the
        // vault's edits make it sound), but an unknown key must be storable as it comes.
        val escaped = exportText(listOf(entry(1, "Own"))).replace("\"generator\": \"PasswordVault 1.0\"",
            "\"generator\": \"PasswordVault 1.0\",\n\"esc\": \"a\\ud800b\"")
        assertEquals(mapOf("esc" to "\"a�b\""), ok(escaped).vaultExtras)
    }
}
