package io.github.dilshodbekmx.passwordvault.core.formats.importer

import io.github.dilshodbekmx.passwordvault.core.crypto.JcaAesGcm
import io.github.dilshodbekmx.passwordvault.core.formats.BitwardenJson
import io.github.dilshodbekmx.passwordvault.core.formats.GoogleCsv
import io.github.dilshodbekmx.passwordvault.core.formats.ImportPlanner
import io.github.dilshodbekmx.passwordvault.core.formats.importer.ImportFixtures.DAY
import io.github.dilshodbekmx.passwordvault.core.formats.importer.ImportFixtures.GENERATOR
import io.github.dilshodbekmx.passwordvault.core.formats.importer.ImportFixtures.NOW
import io.github.dilshodbekmx.passwordvault.core.formats.importer.ImportFixtures.Texts
import io.github.dilshodbekmx.passwordvault.core.formats.importer.ImportFixtures.csv
import io.github.dilshodbekmx.passwordvault.core.formats.importer.ImportFixtures.entry
import io.github.dilshodbekmx.passwordvault.core.formats.importer.ImportFixtures.file
import io.github.dilshodbekmx.passwordvault.core.formats.importer.ImportFixtures.hidden
import io.github.dilshodbekmx.passwordvault.core.formats.importer.ImportFixtures.id
import io.github.dilshodbekmx.passwordvault.core.formats.importer.ImportFixtures.plan
import io.github.dilshodbekmx.passwordvault.core.formats.importer.ImportFixtures.visible
import io.github.dilshodbekmx.passwordvault.core.formats.writers.WriterFixtures
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.core.model.VaultEdits
import io.github.dilshodbekmx.passwordvault.core.vaultfile.BuildInfo
import io.github.dilshodbekmx.passwordvault.core.vaultfile.PasswordSlot
import io.github.dilshodbekmx.passwordvault.core.vaultfile.VaultCodecs
import io.github.dilshodbekmx.passwordvault.testing.FakeClock
import io.github.dilshodbekmx.passwordvault.testing.FakeRandom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** `ImportPlanner.plan`: ux.md §5.21's preview and formats.md §8.4's rules against the vault. */
class ImportPlannerTest {
    private val header = "name,url,username,password,note"

    @Test
    fun ruleB_anAppAndTheSiteItsPackageNamesAreOneAccount() {
        val file = file(
            csv(
                header,
                "instagram.com,https://instagram.com/,aziz,In5ta!,",
                "facebook.com,https://facebook.com/,aziz,In5ta!,", // the same password elsewhere: the reuse this app is for, kept apart
                "com.instagram.android,android://AbC==@com.instagram.android/,aziz,In5ta!,",
            ),
        )
        val plan = plan(file)
        assertEquals(listOf("Instagram", "Facebook"), plan.entries.map { it.title })
        assertEquals(listOf("https://instagram.com/", "android://AbC==@com.instagram.android/"), plan.entries[0].urls)
        assertEquals(listOf(2, 4), plan.accounts[0].lines)
        assertEquals(2 to 1, plan.relatedRows to plan.relatedAccounts) // "2 rows … kept together as 1 account"
        assertEquals(2, plan.found)

        val apart = plan(file, ImportPlanner.Choices(keepSeparate = true)) // "Keep them separate"
        assertEquals(listOf("Instagram", "Facebook", "com.instagram.android"), apart.entries.map { it.title })
        assertEquals(0 to 0, apart.relatedRows to apart.relatedAccounts)
    }

    @Test
    fun ruleC_theSitesOfOneSignOnFamilyAreOneAccount() {
        val file = file(
            csv(
                header, // Chrome's order: by name
                "accounts.google.com,https://accounts.google.com/,aziz@gmail.com,G00gle!,",
                "facebook.com,https://facebook.com/,aziz@gmail.com,Fb!,",
                "gmail.com,https://gmail.com/,aziz@gmail.com,G00gle!,",
                "google.com,https://google.com/,aziz@gmail.com,0ther!,", // another password: another account
                "login.live.com,https://login.live.com/,aziz@outlook.com,M1cro!,",
                "outlook.com,https://outlook.com/,aziz@outlook.com,M1cro!,",
                "passport.yandex.ru,https://passport.yandex.ru/,aziz,Ya!,",
                "ya.ru,https://ya.ru/,aziz,Ya!,",
                "youtube.com,https://youtube.com/,aziz@gmail.com,G00gle!,",
                "appleid.apple.com,https://appleid.apple.com/,aziz@icloud.com,Ap!,",
                "icloud.com,https://www.icloud.com/,aziz@icloud.com,Ap!,",
            ),
        )
        val plan = plan(file)
        assertEquals(listOf("Google", "Facebook", "Google", "Live", "Yandex", "appleid.apple.com"), plan.entries.map { it.title })
        assertEquals(
            listOf(
                listOf("https://accounts.google.com/", "https://gmail.com/", "https://youtube.com/"),
                listOf("https://facebook.com/"),
                listOf("https://google.com/"),
                listOf("https://login.live.com/", "https://outlook.com/"),
                listOf("https://passport.yandex.ru/", "https://ya.ru/"),
                listOf("https://appleid.apple.com/", "https://www.icloud.com/"),
            ),
            plan.entries.map { it.urls },
        )
        assertEquals(9 to 4, plan.relatedRows to plan.relatedAccounts)
    }

    @Test
    fun aNameTheUserGaveIsNeverLostToAJoin() {
        val own = plan(file(csv(header, "Work Gmail,https://mail.google.com/,aziz@gmail.com,G00gle!,", "My YouTube,https://youtube.com/,aziz@gmail.com,G00gle!,")))
        assertEquals(listOf("Work Gmail", "My YouTube"), own.entries.map { it.title })
        assertEquals(0, own.relatedAccounts)
        // A name that is only its website's gives way; the first account's name stays.
        val site = plan(file(csv(header, "Work Gmail,https://mail.google.com/,aziz@gmail.com,G00gle!,", "youtube.com,https://youtube.com/,aziz@gmail.com,G00gle!,")))
        assertEquals(listOf("Work Gmail"), site.entries.map { it.title })
        assertEquals(listOf("https://mail.google.com/", "https://youtube.com/"), site.entries.single().urls)
        // Other notes or fields: not the same login, never joined.
        val notes = plan(file(csv(header, "gmail.com,https://gmail.com/,aziz@gmail.com,G00gle!,work", "youtube.com,https://youtube.com/,aziz@gmail.com,G00gle!,")))
        assertEquals(2, notes.entries.size)
    }

    @Test
    fun aJoinTakesAtMostTenWebsites() {
        val rows = (1..12).map { "s$it.google.com,https://s$it.google.com/,aziz@gmail.com,G00gle!," }
        val plan = plan(file(csv(header, *rows.toTypedArray())))
        assertEquals(listOf(10, 2), plan.entries.map { it.urls.size })
        assertEquals(12 to 2, plan.relatedRows to plan.relatedAccounts)
    }

    @Test
    fun tenThousandRowsOfOneLoginArePlannedQuickly() {
        // Rows named after their site: accounts of 10 websites each.
        val sites = (1..10_000).map { "s$it.google.com,https://s$it.google.com/,aziz@gmail.com,G00gle!," }
        val joined = file(csv(header, *sites.toTypedArray()))
        var start = System.nanoTime()
        val many = plan(joined)
        val joinMs = (System.nanoTime() - start) / 1_000_000
        assertEquals(1_000, many.accounts.size)
        assertTrue(many.accounts.all { it.entry.urls.size == 10 })
        assertEquals(10_000 to 1_000, many.relatedRows to many.relatedAccounts)
        assertTrue("$joinMs ms", joinMs < 1_000)
        // Rows the user named: none is joined, and none is a repeat (each has its own website).
        val named = file(csv(header, *(1..10_000).map { "Account $it,https://s$it.google.com/,aziz@gmail.com,G00gle!," }.toTypedArray()))
        start = System.nanoTime()
        val apart = plan(named)
        val apartMs = (System.nanoTime() - start) / 1_000_000
        assertEquals(10_000, apart.accounts.size)
        assertEquals(0, apart.relatedAccounts)
        assertTrue("$apartMs ms", apartMs < 1_000)
        println("10,000 rows of one login: planned in $joinMs ms joined, $apartMs ms apart")
    }

    @Test
    fun bitwardensRowsAndJsonItemsAreNeverJoined() {
        val bitwarden = file(
            csv(
                "folder,favorite,type,name,notes,fields,reprompt,login_uri,login_username,login_password,login_totp",
                ",,login,Google,,,0,https://accounts.google.com,aziz,G00gle!,",
                ",,login,YouTube,,,0,https://youtube.com,aziz,G00gle!,",
            ),
        )
        assertEquals(2, plan(bitwarden).entries.size)
        val json = file(
            BitwardenJson.export(
                listOf(entry(1, "gmail.com", "aziz", "G00gle!", listOf("https://gmail.com")),
                    entry(2, "youtube.com", "aziz", "G00gle!", listOf("https://youtube.com"))),
                NOW, GENERATOR,
            ).bytes,
        )
        assertEquals(2, plan(json).entries.size)
    }

    @Test
    fun tidyNames() {
        val cases = mapOf(
            "accounts.google.com" to "Google", "m.facebook.com" to "Facebook", "passport.yandex.ru" to "Yandex",
            "my.gov.uz" to "my.gov.uz", "www.my.gov.uz" to "my.gov.uz", "mail.ru" to "Mail", "WWW.Example.COM" to "Example",
            "login.example.co.uk" to "example.co.uk", "mobile.twitter.com" to "Twitter", "account.live.com" to "Live",
            "Gmail" to "Gmail", "my bank" to "my bank", "Bank.uz (old)" to "Bank.uz (old)", "proton.me" to "proton.me",
            "почта.рф" to "почта.рф",
        )
        for ((before, after) in cases) assertEquals(before, after, ImportPlanner.tidyName(before))

        val file = file(csv(header, "accounts.google.com,https://accounts.google.com/,a,p1,", "Bank,https://bank.uz,b,p2,",
            "m.facebook.com,https://m.facebook.com/,c,p3,", "passport.yandex.ru,https://passport.yandex.ru/,d,p4,"))
        val plan = plan(file)
        assertEquals(listOf("Google", "Bank", "Facebook", "Yandex"), plan.entries.map { it.title })
        assertEquals(3, plan.tidied)
        assertEquals(listOf("accounts.google.com" to "Google", "m.facebook.com" to "Facebook"), plan.tidyExamples.map { it.before to it.after })
        val off = plan(file, ImportPlanner.Choices(tidyNames = false))
        assertEquals(listOf("accounts.google.com", "Bank", "m.facebook.com", "passport.yandex.ru"), off.entries.map { it.title })
        assertEquals(0, off.tidied)
    }

    @Test
    fun repeatsAreSkippedAndAnotherPasswordIsAddedToCompare() {
        val gmail = entry(1, "Gmail", "aziz@gmail.com", "G00gle!", listOf("https://mail.google.com"), "personal\nsecond line",
            listOf(hidden("PIN", "1234")))
        val bank = entry(2, "Bank", "ali", "B@nk", listOf("https://bank.uz"), deletedAt = NOW - DAY)
        val wifi = entry(3, "Home Wi-Fi", "", "W1fi-pass")
        val vault = Vault(entries = listOf(gmail, bank, wifi))

        val incoming = listOf(
            entry(11, "Gmail", "aziz@gmail.com", "G00gle!", listOf("https://mail.google.com/mail/u/0"), "personal"), // already here
            entry(12, "Gmail", "aziz@gmail.com", "G00gle!", listOf("https://mail.google.com"), "personal",
                listOf(hidden("PIN", "1234"), visible("Recovery", "+998901112233"))), // brings a field: added
            entry(13, "Gmail", "aziz@gmail.com", "N3w!", listOf("https://mail.google.com")), // another password: added to compare
            entry(14, "Bank", "ali", "B@nk", listOf("https://bank.uz")), // only in Recently deleted: not "here"
            entry(15, "home wi-fi", " ", "W1fi-pass"), // no website: the same name, whatever its case
            entry(16, "Gmail", "aziz@gmail.com", "N3w!", listOf("https://mail.google.com")), // repeats #13 in the file
            entry(17, "Gmail", "aziz@gmail.com", "G00gle!", listOf("https://mail.google.com"), changePending = true,
                pendingOldPassword = "Old#1"), // brings a pending change's old password: added
        )
        val plan = plan(vault, file(BitwardenJson.export(incoming, NOW, GENERATOR).bytes))
        assertEquals(7, plan.found)
        assertEquals(2, plan.alreadyHere)
        assertEquals(1, plan.repeatedInFile)
        assertEquals(listOf(id(12), id(13), id(14), id(17)), plan.entries.map { it.id })
        assertEquals(listOf(1 to gmail.id), plan.conflicts.map { it.index to it.existingId })
        assertEquals(0, plan.withoutPassword)
    }

    /**
     * An account without a website is known by its name, and the import itself changes names: "Tidy
     * names", and "(from file)" for an account of our own JSON added beside the one that holds its id.
     * Neither may make an exact repeat look new.
     */
    @Test
    fun anAccountWithoutAWebsiteIsAlreadyHereWhateverTheImportDoesToItsName() {
        // The vault's own export, imported into the same vault: nothing to add, with "Tidy names" on as it is by default.
        val own = Vault(entries = listOf(entry(1, "gmail.com", "aziz", "G00gle!"), entry(2, "mail.ru", "ivan", "M@il1", listOf("https://mail.ru"))))
        for (bytes in listOf(BitwardenJson.export(own, NOW, GENERATOR).bytes, GoogleCsv.export(own.entries).parts.single())) {
            val plan = plan(own, file(bytes))
            assertEquals(2, plan.found)
            assertEquals(2, plan.alreadyHere)
            assertTrue(plan.entries.map { it.title }.toString(), plan.accounts.isEmpty())
        }
        // The other password of the same name is still a conflict, found under the name the vault has.
        val other = plan(own, file(BitwardenJson.export(listOf(entry(3, "gmail.com", "aziz", "0ther!")), NOW, GENERATOR).bytes))
        assertEquals(listOf("Gmail"), other.entries.map { it.title })
        assertEquals(listOf(id(1)), other.conflicts.map { it.existingId })

        // formats-review P1 for an account without a website: export, change its password, import, and import once more.
        val wifi = entry(4, "Home Wi-Fi", "", "W1fi-old")
        val exported = BitwardenJson.export(listOf(wifi), NOW - DAY, GENERATOR).bytes
        val edited = Vault(entries = listOf(wifi.copy(password = "W1fi-new")))
        val first = plan(edited, file(exported))
        assertEquals(listOf("W1fi-old"), first.entries.map { it.password }) // added beside it, to compare
        val after = VaultEdits.addImported(first.entries, FakeRandom(11), Texts::fromFile)(edited, NOW).vault
        assertEquals(listOf("Home Wi-Fi", "Home Wi-Fi (fayldan)"), after.entries.map { it.title })
        val again = plan(after, file(exported))
        assertEquals(1, again.alreadyHere) // the copy "(from file)" is this account
        assertTrue(again.accounts.isEmpty())
        // Both changes at once: a host-like name, tidied, then named "(from file)".
        val site = entry(5, "example.org", "ali", "Ex-old")
        val siteFile = file(BitwardenJson.export(listOf(site), NOW - DAY, GENERATOR).bytes)
        val siteVault = Vault(entries = listOf(site.copy(password = "Ex-new")))
        val siteAfter = VaultEdits.addImported(plan(siteVault, siteFile).entries, FakeRandom(12), Texts::fromFile)(siteVault, NOW).vault
        assertEquals(listOf("example.org", "Example (fayldan)"), siteAfter.entries.map { it.title })
        assertTrue(plan(siteAfter, siteFile).accounts.isEmpty())
    }

    @Test
    fun theEstimateIsTheSealedFileAtMost() {
        val vault = Vault(entries = WriterFixtures.bulkEntries(800))
        val incoming = WriterFixtures.bulkEntries(300).map { it.copy(id = id(50_000 + it.id.takeLast(4).toInt()), password = it.password + "!") }
        // Two of them carry the id of an entry of the vault with other content: "(fayldan)" names count too.
        val withIds = incoming.mapIndexed { i, e -> if (i < 2) e.copy(id = vault.entries[i].id) else e }
        val plan = plan(vault, file(BitwardenJson.export(withIds, NOW, GENERATOR).bytes))
        assertEquals(300, plan.accounts.size)
        val after = VaultEdits.addImported(plan.entries, FakeRandom(9), Texts::fromFile)(vault, NOW).vault
        assertEquals(2, after.entries.count { it.title.endsWith(" (fayldan)") })
        val codec = VaultCodecs.create(JcaAesGcm(), FakeRandom(1), FakeClock(), BuildInfo(GENERATOR, 1))
        val sealed = codec.seal(after, ByteArray(32) { 7 }, id(1), id(2), 1, PasswordSlot(ByteArray(16), ByteArray(12), ByteArray(48)))
        assertTrue("${plan.sealedBytes} < ${sealed.size}", plan.sealedBytes >= sealed.size)
        assertTrue("${plan.sealedBytes} - ${sealed.size}", plan.sealedBytes - sealed.size < 128) // only the header's widest numbers
    }

    /**
     * The size is estimated with ids of the planner's own in place of the new ids an import would draw.
     * They were `ffffffff-ffff-4fff-bfff-000000000000` and up: valid ids, which an earlier import of a
     * file in our own format keeps as they are. With a hundred of them in the vault, no plan could be
     * made any more for a file with an id that is taken (formats-review P1's own case).
     */
    @Test
    fun theIdsOfTheSizeEstimateAreNeverIdsOfTheVault() {
        val planted = List(100) { i -> entry(1, "Planted $i", "u$i", "p$i").copy(id = "ffffffff-ffff-4fff-bfff-" + i.toString(16).padStart(12, '0')) }
        val gmail = entry(140, "Gmail", "aziz@gmail.com", "G00gle#2025", listOf("https://mail.google.com"))
        // The hostile file is an ordinary import: its ids are valid and free, so they are kept.
        val hostile = file(BitwardenJson.export(planted, NOW, GENERATOR).bytes)
        val start = Vault(entries = listOf(gmail.copy(password = "N3w#Gmail")))
        val vault = VaultEdits.addImported(plan(start, hostile).entries, FakeRandom(4), Texts::fromFile)(start, NOW).vault
        assertEquals(planted.map { it.id }, vault.entries.drop(1).map { it.id })

        // The vault's own older export, whose Gmail has the id that the vault's Gmail holds.
        val plan = plan(vault, file(BitwardenJson.export(listOf(gmail), NOW - DAY, GENERATOR).bytes))
        assertEquals(listOf("G00gle#2025"), plan.entries.map { it.password })
        assertTrue(plan.fits)
        val after = VaultEdits.addImported(plan.entries, FakeRandom(9), Texts::fromFile)(vault, NOW).vault
        val codec = VaultCodecs.create(JcaAesGcm(), FakeRandom(1), FakeClock(), BuildInfo(GENERATOR, 1))
        val sealed = codec.seal(after, ByteArray(32) { 7 }, id(1), id(2), 1, PasswordSlot(ByteArray(16), ByteArray(12), ByteArray(48)))
        assertTrue("${plan.sealedBytes} < ${sealed.size}", plan.sealedBytes >= sealed.size)
    }

    @Test
    fun anImportThatWouldNotFitIsRefused() {
        val note = "x".repeat(10_000)
        val big = Vault(entries = List(1_600) { i -> entry(i + 1, "Big $i", "u$i", "p$i", listOf("https://big$i.example"), note) })
        val before = plan(big, file(csv(header, "Small,https://small.example,s,p,")))
        assertTrue(before.toString(), before.fits) // one small account still fits
        val incoming = List(30) { i -> entry(20_000 + i, "More $i", "m$i", "q$i", listOf("https://more$i.example"), note) }
        val tooMuch = plan(big, file(BitwardenJson.export(incoming, NOW, GENERATOR).bytes))
        assertEquals(30, tooMuch.accounts.size)
        assertFalse(tooMuch.toString(), tooMuch.fits)
        assertTrue(tooMuch.sealedBytes > ImportPlanner.MAX_FILE_BYTES)
        // The estimate is right: sealed for real, the vault after the import is over the cap that the store refuses.
        val after = VaultEdits.addImported(tooMuch.entries, FakeRandom(3), Texts::fromFile)(big, NOW).vault
        val codec = VaultCodecs.create(JcaAesGcm(), FakeRandom(1), FakeClock(), BuildInfo(GENERATOR, 1))
        val sealed = codec.seal(after, ByteArray(32) { 7 }, id(1), id(2), 1, PasswordSlot(ByteArray(16), ByteArray(12), ByteArray(48)))
        assertTrue(sealed.size > ImportPlanner.MAX_FILE_BYTES)
    }
}
