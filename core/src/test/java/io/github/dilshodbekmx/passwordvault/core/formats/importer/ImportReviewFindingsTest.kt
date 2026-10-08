package io.github.dilshodbekmx.passwordvault.core.formats.importer

import io.github.dilshodbekmx.passwordvault.core.formats.BitwardenJson
import io.github.dilshodbekmx.passwordvault.core.formats.ColumnMeaning
import io.github.dilshodbekmx.passwordvault.core.formats.GoogleCsv
import io.github.dilshodbekmx.passwordvault.core.formats.ImportChange
import io.github.dilshodbekmx.passwordvault.core.formats.ImportLeftOut
import io.github.dilshodbekmx.passwordvault.core.formats.ImportPlanner
import io.github.dilshodbekmx.passwordvault.core.formats.Importer
import io.github.dilshodbekmx.passwordvault.core.formats.IsoTime
import io.github.dilshodbekmx.passwordvault.core.formats.TextDecoding.Encoding
import io.github.dilshodbekmx.passwordvault.core.formats.TextDecoding.Why
import io.github.dilshodbekmx.passwordvault.core.formats.importer.ImportFixtures.DAY
import io.github.dilshodbekmx.passwordvault.core.formats.importer.ImportFixtures.GENERATOR
import io.github.dilshodbekmx.passwordvault.core.formats.importer.ImportFixtures.HOUR
import io.github.dilshodbekmx.passwordvault.core.formats.importer.ImportFixtures.NOW
import io.github.dilshodbekmx.passwordvault.core.formats.importer.ImportFixtures.Texts
import io.github.dilshodbekmx.passwordvault.core.formats.importer.ImportFixtures.csv
import io.github.dilshodbekmx.passwordvault.core.formats.importer.ImportFixtures.entry
import io.github.dilshodbekmx.passwordvault.core.formats.importer.ImportFixtures.file
import io.github.dilshodbekmx.passwordvault.core.formats.importer.ImportFixtures.hidden
import io.github.dilshodbekmx.passwordvault.core.formats.importer.ImportFixtures.id
import io.github.dilshodbekmx.passwordvault.core.formats.importer.ImportFixtures.plan
import io.github.dilshodbekmx.passwordvault.core.formats.importer.ImportFixtures.read
import io.github.dilshodbekmx.passwordvault.core.formats.importer.ImportFixtures.resource
import io.github.dilshodbekmx.passwordvault.core.formats.importer.ImportFixtures.visible
import io.github.dilshodbekmx.passwordvault.core.model.DraftEntry
import io.github.dilshodbekmx.passwordvault.core.model.EntryResult
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.core.model.VaultEdits
import io.github.dilshodbekmx.passwordvault.core.util.ParseError
import io.github.dilshodbekmx.passwordvault.testing.FakeRandom
import java.nio.charset.Charset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One test per finding of formats-review.md that build-plan package 3.15 fixes (P1 to P6, P10 to
 * P14, P16), each built from the finding's trigger and run end to end: through `Importer.read`, and
 * through `ImportPlanner.plan` and `VaultEdits.addImported` where the finding is about what an import
 * adds (architecture.md D-15, §8.1). The same findings at the level of the primitives are package
 * 1.4's (`formats/primitives/ReviewFindingsTest`).
 */
class ImportReviewFindingsTest {
    private val cp1251 = Charset.forName("windows-1251")

    /**
     * P1 (critical), the first trigger: the vault holds Gmail and Bank; the JSON is exported; Gmail's
     * password is changed in the app; the JSON is imported. The prototype added Gmail with the id the
     * vault's Gmail has, so the vault held two entries with one id, and "Undo" by ids would have deleted
     * the user's current Gmail.
     */
    @Test
    fun p1_exportEditOneEntryThenImportGivesNoDuplicateId() {
        val gmail = entry(140, "Gmail", "aziz@gmail.com", "G00gle#2025", listOf("https://mail.google.com"), notes = "personal")
        val bank = entry(141, "Bank", "ali", "B@nk#2025", listOf("https://bank.uz"))
        val exported = BitwardenJson.export(Vault(entries = listOf(gmail, bank)), NOW - DAY, GENERATOR).bytes

        val edit = VaultEdits.update(gmail.id, DraftEntry("Gmail", "aziz@gmail.com", "N3w#Gmail", "personal", gmail.urls), Texts)(
            Vault(entries = listOf(gmail, bank)), NOW - HOUR,
        )
        assertEquals(EntryResult.Done(gmail.id), edit.value)
        val vault = edit.vault

        val file = file(exported)
        assertEquals(Importer.Format.OWN_JSON, file.format)
        assertEquals(listOf(gmail.id, bank.id), file.accounts.map { it.entry.id }) // our own ids, as written

        val plan = plan(vault, file)
        assertEquals(2, plan.found)
        assertEquals(1, plan.alreadyHere) // Bank
        assertEquals(listOf("G00gle#2025"), plan.entries.map { it.password }) // the old Gmail, added to compare
        assertEquals(gmail.id, plan.conflicts.single().existingId)
        assertTrue(plan.fits)

        val imported = VaultEdits.addImported(plan.entries, FakeRandom(11), Texts::fromFile)(vault, NOW)
        val entries = imported.vault.entries
        assertEquals(3, entries.size)
        assertEquals(entries.size, entries.map { it.id }.toSet().size) // never two entries with one id
        assertEquals("N3w#Gmail", entries.single { it.id == gmail.id }.password) // the vault's Gmail keeps its id
        val added = entries.last()
        assertNotEquals(gmail.id, added.id)
        assertEquals("Gmail (fayldan)", added.title)
        assertEquals("G00gle#2025", added.password)

        // "Undo this import" saves the vault as it was before (D-15): the Gmail that was here is untouched.
        val undone = VaultEdits.replaceVault(imported.value.before)(imported.vault, NOW + HOUR)
        assertEquals(true, undone.value)
        assertEquals(vault, undone.vault)

        // The same file once more: both of its accounts are already here now.
        val again = plan(imported.vault, file)
        assertEquals(2, again.alreadyHere)
        assertTrue(again.accounts.isEmpty())
    }

    /** P1, the second trigger: two items of our own JSON with one id were both imported with that id. */
    @Test
    fun p1_twoItemsOfOurJsonWithOneIdAreImportedWithTwoIds() {
        val one = entry(140, "Gmail", "aziz@gmail.com", "G00gle#1", listOf("https://mail.google.com"))
        val two = entry(140, "Gmail", "vali@gmail.com", "G00gle#2", listOf("https://mail.google.com"))
        val file = file(BitwardenJson.export(listOf(one, two), NOW, GENERATOR).bytes)
        assertEquals(listOf(id(140), id(140)), file.accounts.map { it.entry.id })
        val plan = plan(file)
        assertEquals(2, plan.accounts.size)
        val vault = VaultEdits.addImported(plan.entries, FakeRandom(5), Texts::fromFile)(Vault(), NOW).vault
        assertEquals(id(140), vault.entries[0].id)
        assertNotEquals(id(140), vault.entries[1].id)
        // A second account of the same file is not "the one holding that id": no "(from file)".
        assertEquals(listOf("Gmail", "Gmail"), vault.entries.map { it.title })
        assertEquals(listOf("aziz@gmail.com", "vali@gmail.com"), vault.entries.map { it.username })
    }

    /**
     * P2: one invalid byte made the importer read a whole UTF-8 file as Windows-1251 and garble every
     * non-ASCII password, silently (CSV and JSON).
     */
    @Test
    fun p2_oneStrayByteNeitherGarblesACsvNorPassesInAJson() {
        val csv = (
            "name,url,username,password,note\n" +
                "Pochta,https://pochta.uz,ali,Пароль2026,izoh\n" +
                "Bank,https://bank.uz,ali,Oʻzbek‘1,\"ok\"\n" +
                "Last,https://x.example,u,p,trailing "
            ).toByteArray(Charsets.UTF_8) + byteArrayOf(0xC3.toByte())
        val file = file(csv)
        val d = file.decoding!!
        assertEquals(Encoding.UTF_8, d.encoding)
        assertEquals(Why.UTF8_SEQUENCES, d.why) // the preview says why
        assertEquals(1, d.replacedBytes)
        assertEquals(listOf(4), d.replacedLines) // and lists the line
        assertEquals(Encoding.WINDOWS_1251, d.alternative) // "Text looks wrong? Read as Windows-1251"
        assertEquals(listOf("Пароль2026", "Oʻzbek‘1", "p"), file.accounts.map { it.entry.password })
        assertEquals("trailing \uFFFD", file.accounts[2].entry.notes)

        // Our own JSON with one byte of a Cyrillic letter in a note changed to 0xFF: refused, with where and why.
        val json = BitwardenJson.export(listOf(entry(1, "Почта", "ali", "Пароль1", notes = "Иккинчи қатор")), NOW, GENERATOR).bytes
        val at = indexOf(json, "Иккинчи".toByteArray(Charsets.UTF_8)) + 2 // the lead byte of "к"
        json[at] = 0xFF.toByte()
        val result = read(json)
        assertTrue(result.toString(), result is Importer.Result.Unreadable)
        assertEquals(ParseError(at.toLong(), "malformed-utf8"), (result as Importer.Result.Unreadable).error)
    }

    /**
     * P3: `{"items":` with about 2,000 `[` and the matching `]` and `}`, a 4,076-byte file, overflowed
     * the stack of a thread with 1 MB or 512 KB, and the `StackOverflowError` escaped the importer.
     */
    @Test
    fun p3_deeplyNestedJsonIsRefusedAndNeverOverflowsTheStack() {
        val n = 2_033
        val text = "{\"items\":" + "[".repeat(n) + "]".repeat(n) + "}"
        assertEquals(4_076, text.length)
        for (stack in listOf(1024L * 1024, 512L * 1024, 256L * 1024)) {
            val result = onThread(stack) { read(text) }
            assertTrue(result.toString(), result is Importer.Result.Unreadable)
            assertEquals(ParseError(40, "too-deep"), (result as Importer.Result.Unreadable).error)
        }
        // Nesting that fills almost all of the 10 MB an import may have, on the test's own thread.
        val big = "{\"items\":" + "[".repeat(5_000_000) + "]".repeat(5_000_000) + "}"
        assertTrue(big.length < Importer.MAX_BYTES)
        val result = read(big)
        assertEquals(ParseError(40, "too-deep"), (result as Importer.Result.Unreadable).error)
    }

    /**
     * P4: under the header `name,url,username,password,note`, a row with an unquoted comma inside a
     * password and a short row were imported silently, with a shifted and a missing password.
     */
    @Test
    fun p4_rowsWithTheWrongNumberOfValuesAreListedAndLeftOutUnlessConfirmed() {
        val file = file(
            csv(
                "name,url,username,password,note",
                "Gmail,https://mail.google.com,aziz,pa,ss,word,my note",
                "Bank,https://bank.uz,ali",
                "Mail,https://mail.ru,ivan,\"pa,ss\",ok",
            ),
        )
        val wrong = file.accounts.filter { it.wrongLength != null }
        assertEquals(listOf(listOf(2), listOf(3)), wrong.map { it.lines })
        assertEquals(listOf(7 to 5, 3 to 5), wrong.map { it.wrongLength!!.values to it.wrongLength!!.expected })
        assertEquals(listOf("Gmail", "Bank"), wrong.map { it.entry.title }) // the preview can name them

        val plan = plan(file)
        assertEquals(listOf("Mail"), plan.entries.map { it.title })
        assertEquals(2, plan.wrongLengthLeftOut)
        assertEquals("pa,ss", plan.entries.single().password)

        val confirmed = plan(file, ImportPlanner.Choices(includeWrongLength = true))
        assertEquals(0, confirmed.wrongLengthLeftOut)
        assertEquals(listOf("Gmail", "Bank", "Mail"), confirmed.entries.map { it.title })
        assertEquals(listOf("pa", "", "pa,ss"), confirmed.entries.map { it.password })
        assertEquals("ss\nword\nmy note", confirmed.entries[0].notes) // nothing of the row is lost
    }

    /**
     * P5: an import dropped whole accounts, passwords included, when one value was over a limit of
     * the model: 12 joined rows (more than 10 websites), a website of 2,049 characters, more than 20
     * extra fields, a 40,000-character note.
     */
    @Test
    fun p5_noAccountIsDroppedForAValueOverALimit() {
        // Twelve adjacent rows of one login: one account of 10 websites, then a new one.
        val rows = (1..12).map { "google.com,https://accounts$it.google.com/,aziz@gmail.com,G00gle!," }
        val joined = file(csv("name,url,username,password,note", *rows.toTypedArray()))
        assertEquals(listOf(10, 2), joined.accounts.map { it.entry.urls.size })
        assertEquals(listOf((1..10).toList(), listOf(11, 12)), joined.accounts.map { a -> a.lines.map { it - 1 } })
        assertTrue(joined.leftOut.isEmpty())
        assertEquals(listOf("G00gle!", "G00gle!"), plan(joined).entries.map { it.password })

        // A website of 2,049 characters, in our CSV and in our JSON: the account stays, the website goes to the notes.
        val long = "https://sso.example/" + "a".repeat(2_049 - 20)
        assertEquals(2_049, long.length)
        val sso = entry(1, "SSO", "aziz", "S\$0!", listOf("https://sso.example", long), notes = "work")
        for (bytes in listOf(GoogleCsv.export(listOf(sso)).parts.single(), BitwardenJson.export(listOf(sso), NOW, GENERATOR).bytes)) {
            val a = file(bytes).accounts.single()
            assertEquals(listOf("https://sso.example"), a.entry.urls)
            assertEquals("work\n\n$long", a.entry.notes)
            assertEquals("[LONG_WEBSITES_MOVED×1]", a.changes.toString())
            assertEquals("S\$0!", a.entry.password)
        }

        // A Bitwarden identity's 18 values and 5 custom fields: 20 fields stay, the hidden ones among them.
        val identityKeys = listOf(
            "title", "firstName", "middleName", "lastName", "address1", "address2", "address3", "city", "state", "postalCode",
            "country", "company", "email", "phone", "ssn", "username", "passportNumber", "licenseNumber",
        )
        val identity = identityKeys.joinToString(",", "{", "}") { "\"$it\":\"v-$it\"" }
        val custom = listOf("A" to 0, "B" to 1, "C" to 0, "D" to 1, "E" to 0)
            .joinToString(",", "[", "]") { (name, type) -> "{\"name\":\"$name\",\"value\":\"v-$name\",\"type\":$type,\"linkedId\":null}" }
        val many = file("""{"encrypted":false,"folders":[],"items":[{"type":4,"name":"Me","fields":$custom,"identity":$identity}]}""")
        val me = many.accounts.single().entry
        assertEquals(20, me.fields.size)
        assertEquals(listOf("B", "D", "postalCode", "ssn", "passportNumber", "licenseNumber"), me.fields.filter { it.secret }.map { it.name })
        assertEquals("email: v-email\nphone: v-phone\nusername: v-username", me.notes) // the last visible ones
        assertEquals("[FIELDS_MOVED×3]", many.accounts.single().changes.toString())

        // A 40,000-character note: the account is imported and its note cut to 10,000.
        val note = "o‘zbek matni, русский текст. ".repeat(1_400).substring(0, 40_000)
        val noted = file(BitwardenJson.export(listOf(entry(2, "Long note", "u", "pw", notes = note)), NOW, GENERATOR).bytes)
        assertEquals(note.substring(0, 10_000), noted.accounts.single().entry.notes)
        assertEquals("[NOTES_CUT×1]", noted.accounts.single().changes.toString())

        // Only a password over 1,000 characters leaves an account out, listed by name and line.
        val longPassword = "p".repeat(1_001)
        val pw = file(csv("name,url,username,password", "Too long,https://x.example,u,$longPassword", "Fine,https://y.example,u,${"p".repeat(1_000)}"))
        assertEquals(listOf("Fine"), pw.accounts.map { it.entry.title })
        val out = pw.leftOut.single()
        assertEquals(ImportLeftOut.Kind.PASSWORD_TOO_LONG, out.kind)
        assertEquals("Too long", out.name)
        assertEquals(2, out.line)
    }

    /**
     * P6: the Uzbek Cyrillic header `Номи,Сайт,Фойдаланувчи,Парол,Изоҳ` was not recognised but for
     * `Сайт`, so every account came in without its password; `Oʻzgartirilgan` and `O‘zgartirilgan`,
     * `User Name` and `Web Site` with a no-break space, and KeePassXC's header in lower case were
     * missed; and with no password column the import went on.
     */
    @Test
    fun p6_theUsersOwnHeadersAreRecognisedAndNoPasswordColumnOpensTheMapping() {
        val file = file(resource("uzbek-cyrillic-headers.csv"))
        assertEquals(Importer.Format.GENERIC_CSV, file.format)
        assertEquals(
            listOf(ColumnMeaning.TITLE, ColumnMeaning.WEBSITE, ColumnMeaning.USERNAME, ColumnMeaning.PASSWORD, ColumnMeaning.NOTES),
            file.columns!!.meanings,
        )
        assertEquals(listOf("Парол-2026!", "Oʻzbek‘1!", "Toshkent#2026"), file.accounts.map { it.entry.password })
        assertTrue(file.accounts.all { it.entry.fields.isEmpty() })

        for (header in listOf("Oʻzgartirilgan", "O‘zgartirilgan", "O'zgartirilgan", "Ўзгартирилган")) {
            val f = file(csv("Номи,Парол,$header", "Bank,pw,2026-01-02T03:04:05Z"))
            assertEquals(header, ColumnMeaning.MODIFIED, f.columns!!.meanings[2])
            assertEquals(IsoTime.parse("2026-01-02T03:04:05Z"), f.accounts.single().entry.updatedAt)
            assertTrue(f.accounts.single().entry.fields.isEmpty())
        }

        val spaced = file(csv("Title,User\u00A0Name,Password,Web\u00A0Site", "Bank,ali,pw,bank.uz"))
        assertEquals(
            listOf(ColumnMeaning.TITLE, ColumnMeaning.USERNAME, ColumnMeaning.PASSWORD, ColumnMeaning.WEBSITE),
            spaced.columns!!.meanings,
        )
        assertEquals("ali", spaced.accounts.single().entry.username)

        val keepass = file(csv("group,title,username,password,url,notes", "Root/Social,Telegram,+998901112233,T3l!,https://web.telegram.org,"))
        assertEquals(Importer.Format.KEEPASSXC_CSV, keepass.format)
        assertEquals(listOf(visible("Folder", "Social")), keepass.accounts.single().entry.fields) // the root group is dropped

        // No password column: the mapping screen, pre-filled; nothing is imported without passwords.
        val result = read(csv("Номи,Сайт,Махфий калит", "Bank,bank.uz,Pa55"))
        assertTrue(result.toString(), result is Importer.Result.ChooseColumns)
        val columns = (result as Importer.Result.ChooseColumns).columns
        assertEquals(listOf(ColumnMeaning.TITLE, ColumnMeaning.WEBSITE, ColumnMeaning.FIELD), columns.meanings)
        assertEquals(listOf("Bank", "bank.uz", "Pa55"), columns.samples)
        // The user's answer reads the file.
        val mapped = file(
            csv("Номи,Сайт,Махфий калит", "Bank,bank.uz,Pa55"),
            Importer.Choices(columns = listOf(ColumnMeaning.TITLE, ColumnMeaning.WEBSITE, ColumnMeaning.PASSWORD)),
        )
        assertEquals("Pa55", mapped.accounts.single().entry.password)
        assertTrue(mapped.columns!!.fromUser)
    }

    /** P10: the rows `Mail,mail.ru,ivan,P1` and `Google, gmail.com ,a,P2` kept their bare hosts. */
    @Test
    fun p10_aBareHostGetsHttps() {
        val file = file(csv("name,url,username,password", "Mail,mail.ru,ivan,P1", "Google, gmail.com ,a,P2"))
        assertEquals(listOf(listOf("https://mail.ru"), listOf("https://gmail.com")), file.accounts.map { it.entry.urls })
        // In every format: a Bitwarden item too, and an app link is kept as it is.
        val json = file(
            """{"encrypted":false,"folders":[],"items":[{"type":1,"name":"Mail","login":{"username":"ivan","password":"P1",""" +
                """"uris":[{"uri":" mail.ru "},{"uri":"androidapp://ru.mail.mailapp"}]}}]}""",
        )
        assertEquals(listOf("https://mail.ru", "androidapp://ru.mail.mailapp"), json.accounts.single().entry.urls)
    }

    /**
     * P11: JSON import gaps: no row limit, untitled empty entries, silent drops of `null`, numbers and
     * strings in `items` and of passkeys, lost unknown keys at the top, a file with two byte-order marks
     * read as CSV, and `"encrypted": "true"` refused.
     */
    @Test
    fun p11_theGapsOfTheJsonImport() {
        // 20,000 items: refused, 10,000 at most.
        val item = """{"type":1,"name":"x","login":{"username":"u","password":"p"}}"""
        val many = """{"encrypted":false,"folders":[],"items":[""" + List(20_000) { item }.joinToString(",") + "]}"
        val tooMany = read(many)
        assertTrue(tooMany.toString(), tooMany is Importer.Result.TooManyRows)
        assertEquals(20_000, (tooMany as Importer.Result.TooManyRows).rows)
        assertEquals(Importer.MAX_ROWS, tooMany.limit)

        val file = file(
            """{"encrypted":"true","passwordVault":{"format":"passwordvault-export","version":1,"folders":[{"id":1}]},"folders":[],"items":[""" +
                """{"type":1,"name":null,"login":null},""" +
                """{"type":1,"name":null,"login":{"username":"aziz@gmail.com","password":"pw","uris":[{"uri":"https://accounts.google.com"}]}},""" +
                """{"type":1,"name":null,"login":{"username":"ali","password":"pw2"}},""" +
                """null,42,"text",""" +
                """{"type":1,"name":"Passkey site","login":{"username":"u","password":"p3","fido2Credentials":[{"credentialId":"x"}]}}""" +
                "]}",
        )
        assertEquals(Importer.Format.OWN_JSON, file.format) // "encrypted": "true" is no boolean: the file is read
        // An item with nothing in it is listed, not imported with an empty name; the others take a name as on the CSV path.
        assertEquals(listOf("accounts.google.com", "ali", "Passkey site"), file.accounts.map { it.entry.title })
        assertEquals(
            listOf(ImportLeftOut.Kind.EMPTY_ITEM to 1, ImportLeftOut.Kind.NOT_AN_ITEM to 4, ImportLeftOut.Kind.NOT_AN_ITEM to 5,
                ImportLeftOut.Kind.NOT_AN_ITEM to 6),
            file.leftOut.map { it.kind to it.position },
        )
        assertEquals(listOf(2, 3, 7), file.accounts.map { it.position })
        assertEquals("[PASSKEYS_NOT_IMPORTED×1]", file.accounts[2].changes.toString())
        assertEquals(mapOf("folders" to "[{\"id\":1}]"), file.vaultExtras) // a later version's data at the top is kept

        // Two byte-order marks in front: still JSON.
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val twice = file(bom + bom + """{"encrypted":false,"items":[$item]}""".toByteArray(Charsets.UTF_8))
        assertEquals(Importer.Format.BITWARDEN_JSON, twice.format)
        // Only the boolean true is an encrypted file.
        val encrypted = read("""{"encrypted":true,"passwordProtected":true,"data":"…"}""")
        assertTrue(encrypted is Importer.Result.Encrypted && encrypted.passwordProtected)
    }

    /**
     * P12: a UTF-8 byte-order mark in front of a Windows-1251 body gave a password of six U+FFFD and
     * `1` with nothing reported; UTF-16LE without a mark was refused as binary.
     */
    @Test
    fun p12_damageBehindAMarkIsReportedAndUtf16WithoutOneIsRead() {
        val body = "name,url,username,password,note\r\nPochta,https://pochta.uz,ali,Пароль1,izoh\r\n"
        val trigger = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + body.toByteArray(cp1251)
        val file = file(trigger)
        val d = file.decoding!!
        assertEquals(Encoding.UTF_8, d.encoding)
        assertEquals(Why.BYTE_ORDER_MARK, d.why)
        assertEquals(6, d.replacedBytes)
        assertEquals(listOf(2), d.replacedLines)
        assertEquals("\uFFFD".repeat(6) + "1", file.accounts.single().entry.password)
        // "Text looks wrong? Read as Windows-1251"
        val again = file(trigger, Importer.Choices(encoding = Encoding.WINDOWS_1251))
        assertEquals("Пароль1", again.accounts.single().entry.password)
        assertEquals(Why.CHOSEN, again.decoding!!.why)

        for ((charset, encoding) in listOf(Charsets.UTF_16LE to Encoding.UTF_16LE, Charsets.UTF_16BE to Encoding.UTF_16BE)) {
            val utf16 = file(body.toByteArray(charset))
            assertEquals(encoding, utf16.decoding!!.encoding)
            assertEquals(Why.NUL_BYTES, utf16.decoding!!.why)
            assertEquals("Пароль1", utf16.accounts.single().entry.password)
        }
    }

    /**
     * P13: a Firefox export gave every account five hidden extra fields and lost its dates; Excel's
     * `sep=;` line gave "no known columns".
     */
    @Test
    fun p13_firefoxAndExcelsSepLine() {
        val firefox = file(
            csv(
                "\"url\",\"username\",\"password\",\"httpRealm\",\"formActionOrigin\",\"guid\",\"timeCreated\",\"timeLastUsed\",\"timePasswordChanged\"",
                "\"https://accounts.google.com\",\"aziz@gmail.com\",\"G00gle!\",,\"https://accounts.google.com\",\"{a1}\",\"1696000000000\",\"1700000000000\",\"1698000000000\"",
                "\"https://login.example.com\",\"ali\",\"pw\",,\"https://auth.example.net\",\"{a2}\",\"1696000000001\",\"1700000000000\",\"1698000000001\"",
            ),
        )
        assertEquals(Importer.Format.FIREFOX_CSV, firefox.format)
        assertTrue(firefox.accounts.all { it.entry.fields.isEmpty() }) // guid, httpRealm, timeLastUsed: ignored
        assertEquals(listOf(1_696_000_000_000L, 1_696_000_000_001L), firefox.accounts.map { it.entry.createdAt })
        assertEquals(listOf(1_698_000_000_000L, 1_698_000_000_001L), firefox.accounts.map { it.entry.passwordChangedAt })
        assertEquals(
            listOf(listOf("https://accounts.google.com"), listOf("https://login.example.com", "https://auth.example.net")),
            firefox.accounts.map { it.entry.urls }, // the form's origin only when its host differs
        )
        assertEquals(listOf("accounts.google.com", "login.example.com"), firefox.accounts.map { it.entry.title })

        val sep = file("sep=;\r\nname;url;username;password;note\r\nBank;https://bank.uz;ali;pa,ss;Oʻzbek\r\n")
        assertEquals(';', sep.delimiter)
        assertTrue(sep.delimiterFromSepLine)
        assertEquals("pa,ss", sep.accounts.single().entry.password)
        assertEquals(listOf(3), sep.accounts.single().lines)
    }

    /** P14: passwords `a\tb`, `a\u0001b` and `\tab` in our CSV or JSON came back as `ab`. */
    @Test
    fun p14_aPasswordWithAControlCharacterIsImportedExactlyAndListed() {
        val entries = listOf(
            entry(1, "Tab", "u1", "a\tb", listOf("https://one.example")),
            entry(2, "Ctrl", "u2", "a\u0001b", listOf("https://two.example")),
            entry(3, "Lead", "u3", "\tab", listOf("https://three.example")),
        )
        for (bytes in listOf(GoogleCsv.export(entries).parts.single(), BitwardenJson.export(entries, NOW, GENERATOR).bytes)) {
            val file = file(bytes)
            assertEquals(listOf("a\tb", "a\u0001b", "\tab"), file.accounts.map { it.entry.password })
            assertEquals(
                listOf(ImportChange.Kind.PASSWORD_HAS_TAB, ImportChange.Kind.PASSWORD_HAS_CONTROL, ImportChange.Kind.PASSWORD_HAS_TAB),
                file.accounts.map { it.changes.single().kind },
            )
            assertEquals(0, file.removedControls)
        }
        // Everywhere else a control character is removed and counted ("6 invisible characters were removed").
        val other = file(
            BitwardenJson.export(
                listOf(entry(4, "Ba\u0007nk", "a\u0001li", "pw\u0001", listOf("https://bank.uz\u0001"), "n\u0002o\u0003te",
                    listOf(hidden("PI\u0004N", "12\u000534")))),
                NOW, GENERATOR,
            ).bytes,
        )
        val e = other.accounts.single().entry
        assertEquals(listOf("Bank", "ali", "pw\u0001", "https://bank.uz", "note"), listOf(e.title, e.username, e.password, e.urls.single(), e.notes))
        assertEquals(listOf(hidden("PIN", "1234")), e.fields)
        assertEquals(7, other.removedControls)
    }

    /**
     * P16: planning took 5 s for 10,000 accounts against a vault of 10,000 (all new), because hosts were
     * worked out again at every comparison. Build plan 3.15: under a second on this machine.
     */
    @Test
    fun p16_aPlanOf10000AccountsAgainstAVaultOf10000TakesUnderASecond() {
        val vault = Vault(
            entries = List(10_000) { i ->
                entry(i + 1, "Site $i", "user$i@example.com", "Pw#$i", listOf("https://site$i.example.com/", "android://h@com.site$i.app/"))
            },
        )
        val incoming = List(10_000) { i ->
            entry(20_001 + i, "New $i", "new$i@example.org", "Nw#$i", listOf("https://new$i.example.org/login"), notes = "note $i")
        }
        val file = file(BitwardenJson.export(incoming, NOW, GENERATOR).bytes)
        assertEquals(10_000, file.accounts.size)

        val times = ArrayList<Long>()
        var last = plan(vault, file) // warm-up
        repeat(5) {
            val start = System.nanoTime()
            last = plan(vault, file)
            times.add((System.nanoTime() - start) / 1_000_000)
        }
        assertEquals(10_000, last.accounts.size)
        assertEquals(0, last.alreadyHere)
        assertTrue(last.fits)
        val median = times.sorted()[times.size / 2]
        assertTrue("median ${median} ms of $times", median < 1_000)
    }

    private fun indexOf(bytes: ByteArray, part: ByteArray): Int {
        outer@ for (i in 0..bytes.size - part.size) {
            for (j in part.indices) if (bytes[i + j] != part[j]) continue@outer
            return i
        }
        throw AssertionError("not found")
    }

    /** Runs [block] on a thread with a stack of [stackBytes]; its value, or what it threw. */
    private fun onThread(stackBytes: Long, block: () -> Any?): Any? {
        var out: Any? = null
        val thread = Thread(null, {
            out = try {
                block()
            } catch (t: Throwable) {
                t
            }
        }, "import-finding", stackBytes)
        thread.start()
        thread.join()
        return out
    }
}
