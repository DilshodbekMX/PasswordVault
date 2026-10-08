package io.github.dilshodbekmx.passwordvault.ui.transfer.`import`

import io.github.dilshodbekmx.passwordvault.core.formats.BitwardenJson
import io.github.dilshodbekmx.passwordvault.core.formats.ColumnMeaning
import io.github.dilshodbekmx.passwordvault.core.formats.ImportAccount
import io.github.dilshodbekmx.passwordvault.core.formats.ImportFile
import io.github.dilshodbekmx.passwordvault.core.formats.ImportPlan
import io.github.dilshodbekmx.passwordvault.core.formats.ImportPlanner
import io.github.dilshodbekmx.passwordvault.core.model.CustomField
import io.github.dilshodbekmx.passwordvault.core.model.Entry
import io.github.dilshodbekmx.passwordvault.core.model.EntryRules
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.data.SaveProblem
import io.github.dilshodbekmx.passwordvault.files.FileTask
import io.github.dilshodbekmx.passwordvault.ui.transfer.ColumnsScreen
import io.github.dilshodbekmx.passwordvault.ui.transfer.ImportMessage
import io.github.dilshodbekmx.passwordvault.ui.transfer.ImportMove
import io.github.dilshodbekmx.passwordvault.ui.transfer.ImportScreen
import io.github.dilshodbekmx.passwordvault.ui.transfer.MappingReason
import io.github.dilshodbekmx.passwordvault.ui.transfer.`import`.ImportKit.Companion.NOW_MS
import io.github.dilshodbekmx.passwordvault.ui.transfer.`import`.ImportKit.Companion.account
import io.github.dilshodbekmx.passwordvault.ui.transfer.`import`.ImportKit.Companion.utf8
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * Build plan 5.4, [JVM]: the import's five acceptance lines, through its ViewModels against the
 * app's fakes and the real `FileTaskStore` (ux.md §5.21, formats.md §8, architecture.md §3.5, §3.8):
 * 1. the preview never shows a password;
 * 2. rows with a wrong number of values are listed and left out unless confirmed;
 * 3. the mapping screen opens when no password column is found;
 * 4. "Undo this import" restores the vault as it was;
 * 5. an import that would not fit is refused with A §3.5's sentence.
 */
class ImportAcceptanceTest {

    // ---- 1. The preview never shows a password -----------------------------------------------

    @Test
    fun thePreviewNeverShowsAPasswordOfACsvFile() = ImportKit.run {
        recorded(utf8(CHROME_CSV))
        val file = file()
        val shown = Shown.of(file.ui.value)
        assertTrue("the preview is shown: ${file.ui.value.screen}", file.ui.value.screen is ImportScreen.Preview)
        assertEquals("no Entry, Vault or file of the importer in the screen's state", emptyList<String>(), shown.objects)
        for (password in CHROME_PASSWORDS) {
            assertTrue("a password in the preview's texts", shown.strings.none { it.contains(password) })
        }
        // The halves of the password that an unquoted comma split, in the row with 6 values.
        assertTrue(shown.strings.none { it.contains("Pw-Half-One") || it.contains("Pw-Half-Two") })

        // The preview shows names and usernames: the sample prefers non-Latin letters (ux.md §5.21).
        val preview = preview(file)
        assertEquals(listOf("Gmail" to "aziz@gmail.com", "Bank" to "aziz", "Почта" to "азиз"), preview.samples.map { it.title to it.username })

        // With every choice switched, and after the rows with the wrong number were confirmed, still none.
        file.includeWrongRows(true)
        file.tidyNames(false)
        file.keepSeparate(true)
        settle()
        val again = Shown.of(file.ui.value)
        for (password in CHROME_PASSWORDS + listOf("Pw-Half-One", "Pw-Half-Two")) {
            assertTrue("a password in the preview after the choices", again.strings.none { it.contains(password) })
        }
        assertEquals(emptyList<String>(), again.objects)
    }

    @Test
    fun thePreviewNeverShowsAPasswordOfAJsonFile() = ImportKit.run {
        recorded(utf8(BITWARDEN_JSON), name = "bitwarden_export.json")
        val vm = file()
        assertTrue(vm.ui.value.screen is ImportScreen.Preview)
        val shown = Shown.of(vm.ui.value)
        for (password in listOf("Pw-Json-GitHub-1", "Pw-Json-Card-Code", "Pw-Json-Hidden-Field")) {
            assertTrue("a password in the preview of a JSON file", shown.strings.none { it.contains(password) })
        }
        assertEquals(emptyList<String>(), shown.objects)
        assertEquals(listOf("GitHub" to "octocat"), preview(vm).samples.map { it.title to it.username }.take(1))
    }

    @Test
    fun aPasswordThatMovedIntoAShownColumnIsNeverShown() = ImportKit.run {
        // A layout with the password before the name: an unquoted comma in the password moves its
        // second half into the name column. The row is named by its line, not by its values.
        recorded(utf8("password,name,username,url\nSecret-A1,Mail,ali,https://mail.uz\nPart-One,Part-Two,Shop,bob,https://shop.uz\n"))
        val vm = file()
        val shown = Shown.of(vm.ui.value)
        assertTrue(shown.strings.none { it.contains("Part-Two") || it.contains("Part-One") || it.contains("Secret-A1") })
        val preview = preview(vm)
        assertEquals(listOf(3), preview.wrongRows.map { it.line })
        vm.includeWrongRows(true)
        settle()
        assertTrue(Shown.of(vm.ui.value).strings.none { it.contains("Part-Two") || it.contains("Part-One") })
    }

    // ---- 2. Rows with a wrong number of values ---------------------------------------------

    @Test
    fun rowsWithAWrongNumberOfValuesAreListedAndLeftOutUnlessConfirmed() = ImportKit.run(VAULT) {
        recorded(utf8(CHROME_CSV))
        val vm = file()
        val preview = preview(vm)
        // Listed: "Line 6 has 6 values; the header has 5."
        assertEquals(listOf(Triple(6, 6, 5)), preview.wrongRows.map { Triple(it.line, it.values, it.expected) })
        assertFalse(preview.wrongRowsIncluded)
        val without = preview.toAdd

        // Left out: an import now does not add it.
        vm.importNow()
        settle()
        assertNull("the shifted row was not imported", vault().entries.firstOrNull { it.username == "buyer" })
        vm.undo()
        settle()
        assertEquals(VAULT, vault())

        // Confirmed: it is in the plan and in the import, as it is.
        recorded(utf8(CHROME_CSV))
        val again = file()
        again.includeWrongRows(true)
        settle()
        val confirmed = preview(again)
        assertTrue(confirmed.wrongRowsIncluded)
        assertEquals(without + 1, confirmed.toAdd)
        again.importNow()
        settle()
        val shifted = vault().entries.single { it.username == "buyer" }
        assertEquals("the first half, as the file has it", "Pw-Half-One", shifted.password)
    }

    // ---- 3. The column mapping opens when no password column is found ----------------------

    @Test
    fun theMappingScreenOpensWhenNoPasswordColumnIsFound() = ImportKit.run(VAULT) {
        recorded(utf8("Sayt,Login,Kalit\nhttps://my.gov.uz,aziz,Kalit-1-Pw\nhttps://bank.uz,aziz,Kalit-2-Pw\n"))
        val vm = file()
        assertEquals(ImportScreen.NeedsColumns, vm.ui.value.screen)
        assertEquals("the mapping opens by itself", ImportMove.COLUMNS, vm.ui.value.move)
        vm.moved()

        val columns = columns()
        val shown = columns.ui.value.screen as ColumnsScreen.Shown
        assertEquals(MappingReason.NO_PASSWORD, shown.reason)
        assertEquals(listOf("Sayt", "Login", "Kalit"), shown.columns.map { it.header })
        assertEquals("pre-filled from the aliases", listOf(ColumnMeaning.WEBSITE, ColumnMeaning.USERNAME, ColumnMeaning.FIELD), shown.columns.map { it.meaning })
        assertFalse(shown.hasPassword)

        columns.choose(2, ColumnMeaning.PASSWORD)
        assertTrue((columns.ui.value.screen as ColumnsScreen.Shown).hasPassword)
        columns.confirm()
        settle()
        assertTrue("the mapping goes back", columns.ui.value.leave)

        // The file's screen reads the file again with those columns.
        val preview = preview(vm)
        assertEquals(2, preview.toAdd)
        vm.importNow()
        settle()
        assertEquals(listOf("Kalit-1-Pw", "Kalit-2-Pw"), vault().entries.drop(VAULT.entries.size).map { it.password })

        // Left without an answer, the mapping does not open by itself again; the screen offers it.
        val other = ImportKit.utf8("A,B\nx,y\n")
        recorded(other, uri = ImportKit.OTHER_DOC)
        val second = file()
        assertEquals(ImportScreen.NeedsColumns, second.ui.value.screen)
        assertEquals(ImportMove.COLUMNS, second.ui.value.move)
        second.moved()
        clear()
        val third = file()
        assertEquals(ImportScreen.NeedsColumns, third.ui.value.screen)
        assertNull("not again by itself", third.ui.value.move)
        third.openColumns()
        assertEquals(ImportMove.COLUMNS, third.ui.value.move)
    }

    // ---- 4. "Undo this import" restores the vault as it was ---------------------------------

    @Test
    fun undoThisImportRestoresTheVaultAsItWas() = ImportKit.run(VAULT) {
        // Our own JSON, with an account that has the id of an account here and other content: it is
        // added as "Gmail (from file)" beside it, and the undo must not touch the one that was here.
        val ownGmail = VAULT.entries[0].copy(password = "Pw-Changed-Elsewhere", updatedAt = NOW_MS)
        val newOne = account("33333333-3333-4333-8333-333333333333", "Payme", "aziz", "Pw-Payme-4", "https://payme.uz")
        val json = BitwardenJson.export(listOf(ownGmail, newOne), NOW_MS, "PasswordVault 1.0").bytes
        recorded(json, name = "passwords.json")
        val vm = file()
        assertEquals(2, preview(vm).toAdd)
        vm.importNow()
        settle()
        val done = (vm.ui.value.screen as ImportScreen.Done).done
        assertEquals(2, done.added)
        assertTrue(done.canUndo)
        val imported = vault()
        assertEquals(VAULT.entries.size + 2, imported.entries.size)
        assertEquals("Gmail (from file)", imported.entries.single { it.password == "Pw-Changed-Elsewhere" }.title)

        vm.undo()
        settle()
        assertEquals("exactly as before the import", VAULT, vault())
        val after = (vm.ui.value.screen as ImportScreen.Done).done
        assertTrue(after.undone)
        assertFalse(after.canUndo)
        assertFalse("nothing to ask: the passwords are only in the file now", after.askFile)
        assertNull("the task is finished", settings.fileTask.value)
        assertTrue("the file stays", ImportKit.DOC in fake.files)
    }

    // ---- 5. An import that would not fit is refused with A §3.5's sentence -------------------

    @Test
    fun anImportThatWouldNotFitIsRefusedBeforeAnythingIsSaved() = ImportKit.run(bigVault()) {
        recorded(bigCsv(rows = 40))
        val vm = file()
        val preview = preview(vm)
        assertFalse("the vault would not fit in one file", preview.fits)
        assertTrue(preview.toAdd > 0)
        val edits = repository.edits
        vm.importNow()
        settle()
        assertEquals("refused before anything is saved", edits, repository.edits)
        assertEquals(ImportMessage.TOO_LARGE, vm.ui.value.message)
        assertTrue(vm.ui.value.screen is ImportScreen.Preview)

        // The sentence the preview and the message show is A §3.5's.
        assertEquals(TOO_LARGE_SENTENCE, englishString("import_too_large"))
    }

    @Test
    fun aSaveRefusedForItsSizeSaysTheSameSentence() = ImportKit.run(VAULT) {
        recorded(utf8(CHROME_CSV))
        val vm = file()
        repository.failures.add(SaveProblem.TOO_LARGE)
        vm.importNow()
        settle()
        assertEquals(ImportMessage.TOO_LARGE, vm.ui.value.message)
        assertEquals(VAULT, vault())
        assertEquals(FileTask.Kind.IMPORT, settings.fileTask.value?.kind)
    }

    // ---- helpers ------------------------------------------------------------------------------

    /** Every text in a state, found by walking its objects, and every object that should never be there. */
    private class Shown(val strings: List<String>, val objects: List<String>) {
        companion object {
            private val FORBIDDEN = listOf(Entry::class.java, Vault::class.java, ImportFile::class.java, ImportAccount::class.java, ImportPlan::class.java, CustomField::class.java)

            fun of(root: Any?): Shown {
                val strings = ArrayList<String>()
                val objects = ArrayList<String>()
                walk(root, strings, objects, java.util.IdentityHashMap(), 0)
                return Shown(strings, objects)
            }

            private fun walk(x: Any?, strings: MutableList<String>, objects: MutableList<String>, seen: MutableMap<Any, Boolean>, depth: Int) {
                if (x == null || depth > 30) return
                when (x) {
                    is CharSequence -> strings += x.toString()
                    is Number, is Boolean, is Char, is Enum<*> -> Unit
                    is Map<*, *> -> x.forEach { (k, v) ->
                        walk(k, strings, objects, seen, depth + 1)
                        walk(v, strings, objects, seen, depth + 1)
                    }
                    is Iterable<*> -> x.forEach { walk(it, strings, objects, seen, depth + 1) }
                    is Array<*> -> x.forEach { walk(it, strings, objects, seen, depth + 1) }
                    else -> {
                        if (seen.put(x, true) != null) return
                        if (FORBIDDEN.any { it.isInstance(x) }) objects += x.javaClass.simpleName
                        if (!x.javaClass.name.startsWith("io.github.dilshodbekmx")) return
                        var c: Class<*>? = x.javaClass
                        while (c != null && c != Any::class.java) {
                            for (f in c.declaredFields) {
                                if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                                f.isAccessible = true
                                walk(f.get(x), strings, objects, seen, depth + 1)
                            }
                            c = c.superclass
                        }
                    }
                }
            }
        }
    }

    private companion object {
        /** A Chrome export with what the preview lists: a tab in a password, Cyrillic, a row of 6 values, a long password, a long note. */
        val LONG_PASSWORD = "Pw-Long-" + "x".repeat(EntryRules.PASSWORD_MAX)
        val CHROME_CSV = "name,url,username,password,note\n" +
            "Gmail,https://accounts.google.com,aziz@gmail.com,Pw-Gmail-7731,\n" +
            "Bank,https://kapitalbank.uz,aziz,Pw-Bank\tTab,\n" +
            ",https://m.facebook.com,aziz.k,Pw-Fb-5521,\n" +
            "Почта,https://mail.yandex.ru,азиз,Пароль-Яндекс-91,\n" +
            "Shop,https://shop.uz,buyer,Pw-Half-One,Pw-Half-Two,\n" +
            "Long,https://long.uz,longuser,$LONG_PASSWORD,\n" +
            "Notes,https://notes.uz,nn,Pw-Notes-1,\"" + "n".repeat(EntryRules.NOTES_MAX + 5) + "\"\n"
        val CHROME_PASSWORDS = listOf("Pw-Gmail-7731", "Pw-Bank\tTab", "Pw-Bank", "Pw-Fb-5521", "Пароль-Яндекс-91", "Pw-Notes-1", LONG_PASSWORD, "Pw-Long-xxxx")

        /** Bitwarden's unencrypted JSON: a login, a card with its code, a login with a hidden field. */
        val BITWARDEN_JSON = """
            {"encrypted": false, "folders": [], "items": [
              {"id": "8a1f8b8e-5b9e-4b8e-9f7a-1c2d3e4f5a6b", "type": 1, "name": "GitHub", "favorite": false,
               "login": {"username": "octocat", "password": "Pw-Json-GitHub-1", "uris": [{"uri": "https://github.com"}]}},
              {"id": "9b2f8b8e-5b9e-4b8e-9f7a-1c2d3e4f5a6b", "type": 3, "name": "Visa",
               "card": {"cardholderName": "Aziz", "number": "4111111111111111", "code": "Pw-Json-Card-Code"}},
              {"id": "ab3f8b8e-5b9e-4b8e-9f7a-1c2d3e4f5a6b", "type": 1, "name": "Mail",
               "fields": [{"name": "Recovery", "value": "Pw-Json-Hidden-Field", "type": 1}],
               "login": {"username": "a@mail.uz", "password": "Pw-Json-Mail-2", "uris": [{"uri": "https://mail.uz"}]}}
            ]}
        """.trimIndent()

        /** The vault the tests start from. */
        val VAULT = Vault(
            entries = listOf(
                account("11111111-1111-4111-8111-111111111111", "Gmail", "aziz@gmail.com", "Pw-Old-Gmail", "https://mail.google.com"),
                account("22222222-2222-4222-8222-222222222222", "Kapitalbank", "aziz", "Pw-Old-Bank", "https://kapitalbank.uz"),
            ),
        )

        /** "Your passwords no longer fit in one file. …" (architecture.md §3.5, `SaveProblem.TOO_LARGE`). */
        const val TOO_LARGE_SENTENCE = "Your passwords no longer fit in one file. Delete accounts you don't need, or shorten very long notes."

        /** A note of 10,000 characters of four UTF-8 bytes each. */
        val BIG_NOTE: String = "🔐".repeat(EntryRules.NOTES_MAX)

        /** A vault about 400 KB under the largest vault file: accounts with the largest notes. */
        fun bigVault(): Vault {
            fun entries(n: Int) = List(n) { i ->
                Entry(id = "%08x-0000-4000-8000-%012x".format(i, i), title = "Big $i", notes = BIG_NOTE, createdAt = NOW_MS, updatedAt = NOW_MS)
            }
            val one = sealed(Vault(entries = entries(1)))
            val each = sealed(Vault(entries = entries(2))) - one
            val n = 1 + ((ImportPlanner.MAX_FILE_BYTES - 400_000L - one) / each).toInt()
            val vault = Vault(entries = entries(n))
            val left = ImportPlanner.MAX_FILE_BYTES - sealed(vault)
            check(left in 0..800_000L) { "the big vault leaves $left bytes" }
            return vault
        }

        private fun sealed(vault: Vault): Long = ImportPlanner.plan(vault, emptyFile(), { it }).sealedBytes

        private fun emptyFile(): ImportFile = ImportFile(
            format = io.github.dilshodbekmx.passwordvault.core.formats.Importer.Format.GOOGLE_CSV,
            decoding = null,
            delimiter = ',',
            delimiterFromSepLine = false,
            columns = null,
            accounts = emptyList(),
            leftOut = emptyList(),
            emptyRows = 0,
            unclosedQuoteLine = 0,
            removedControls = 0,
            replacedCharacters = 0,
            newerVersion = false,
            vaultExtras = emptyMap(),
        )

        /** A CSV of [rows] accounts, each with a note as large as a note can be. */
        fun bigCsv(rows: Int): ByteArray {
            val sb = StringBuilder("name,url,username,password,note\n")
            for (r in 0 until rows) sb.append("Site $r,https://site$r.uz,user$r,Pw-Big-$r,\"").append(BIG_NOTE).append("\"\n")
            return utf8(sb.toString())
        }

        /** The English text of the string [name] in the app's `values/` (strings_import.xml), unescaped. */
        fun englishString(name: String): String {
            val dir = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "app/src/main/res/values") }.first { it.isDirectory }
            val xml = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(dir, "strings_import.xml"))
            val nodes = xml.getElementsByTagName("string")
            for (i in 0 until nodes.length) {
                val e = nodes.item(i) as Element
                if (e.getAttribute("name") == name) return e.textContent.replace("\\'", "'")
            }
            throw AssertionError("no string $name")
        }
    }
}
