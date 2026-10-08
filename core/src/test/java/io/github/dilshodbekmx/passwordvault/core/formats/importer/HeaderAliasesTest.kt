package io.github.dilshodbekmx.passwordvault.core.formats.importer

import io.github.dilshodbekmx.passwordvault.core.formats.ColumnMeaning
import io.github.dilshodbekmx.passwordvault.core.formats.HeaderAliases
import io.github.dilshodbekmx.passwordvault.core.formats.importer.ImportFixtures.csv
import io.github.dilshodbekmx.passwordvault.core.formats.importer.ImportFixtures.file
import io.github.dilshodbekmx.passwordvault.core.formats.importer.ImportFixtures.hidden
import io.github.dilshodbekmx.passwordvault.core.formats.importer.ImportFixtures.visible
import java.text.Normalizer
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** formats.md §8.3's header words, and how a header is compared (formats-review P6). */
class HeaderAliasesTest {
    /** Every word formats.md §8.3 lists, with its meaning. */
    private val formatsMd: Map<ColumnMeaning, List<String>> = mapOf(
        ColumnMeaning.WEBSITE to listOf(
            "url", "website", "origin", "hostname", "login_uri", "uri", "web site", "site", "address", "link", "сайт", "адрес",
            "ссылка", "веб-сайт", "sayt", "manzil", "havola", "veb-sayt", "манзил", "ҳавола",
        ),
        ColumnMeaning.USERNAME to listOf(
            "username", "user", "login", "account", "login_username", "user name", "email", "e-mail", "логин", "имя пользователя",
            "пользователь", "почта", "электронная почта", "foydalanuvchi", "pochta", "elektron pochta", "фойдаланувчи",
            "фойдаланувчи номи", "электрон почта",
        ),
        ColumnMeaning.PASSWORD to listOf("password", "login_password", "pass", "passwd", "pwd", "пароль", "parol", "парол"),
        ColumnMeaning.NOTES to listOf(
            "note", "notes", "comment", "comments", "extra", "description", "заметка", "заметки", "примечание", "комментарий",
            "описание", "izoh", "eslatma", "qayd", "sharh", "изоҳ", "эслатма",
        ),
        ColumnMeaning.TITLE to listOf("name", "title", "item name", "название", "наименование", "nomi", "nom", "sarlavha", "номи", "сарлавҳа"),
        ColumnMeaning.TOTP to listOf("totp"),
        ColumnMeaning.FOLDER to listOf("folder", "group", "папка", "группа", "papka", "guruh", "гуруҳ"),
        ColumnMeaning.FAVOURITE to listOf("favourite", "favorite"),
        ColumnMeaning.CREATED to listOf("created", "creationdate", "creation time", "created at", "создано", "yaratilgan"),
        ColumnMeaning.MODIFIED to listOf("modified", "last modified", "revisiondate", "updated", "lastmodified", "изменено", "oʻzgartirilgan"),
    )

    @Test
    fun everyWordOfFormatsMdHasItsMeaning() {
        for ((meaning, words) in formatsMd) {
            for (word in words) {
                assertEquals(word, meaning, HeaderAliases.meaning(word))
                // Whatever the case, and with a byte-order mark in front.
                assertEquals(word, meaning, HeaderAliases.meaning("\uFEFF" + word.uppercase(Locale.ROOT)))
            }
        }
        assertNull(HeaderAliases.meaning("Telefon"))
        assertNull(HeaderAliases.meaning(""))
    }

    @Test
    fun aHeaderIsComparedWithoutCaseSpacesPunctuationApostrophesOrInvisibleCharacters() {
        val modified = listOf("Oʻzgartirilgan", "O‘zgartirilgan", "O'zgartirilgan", "O`zgartirilgan", "O’zgartirilgan", "Ozgartirilgan")
        for (h in modified) assertEquals(h, ColumnMeaning.MODIFIED, HeaderAliases.meaning(h))
        assertEquals("username", HeaderAliases.key("User\u00A0Name"))
        assertEquals("username", HeaderAliases.key(" USER_NAME "))
        assertEquals("username", HeaderAliases.key("user\u202Fname")) // a narrow no-break space
        assertEquals("username", HeaderAliases.key("user\u200Bname")) // a zero-width space
        assertEquals(ColumnMeaning.USERNAME, HeaderAliases.meaning("E\u2011mail")) // a non-breaking hyphen
        assertEquals(ColumnMeaning.WEBSITE, HeaderAliases.meaning("Login.URI"))
        assertEquals(ColumnMeaning.WEBSITE, HeaderAliases.meaning("Веб\u2013сайт")) // an en dash
        // A letter typed as a base letter and a combining mark is the same letter (NFKC).
        val decomposed = Normalizer.normalize("Фойдаланувчи номи", Normalizer.Form.NFD)
        assertTrue(decomposed.length > "Фойдаланувчи номи".length)
        assertEquals(ColumnMeaning.USERNAME, HeaderAliases.meaning(decomposed))
        assertEquals(ColumnMeaning.TITLE, HeaderAliases.meaning("ＮＡＭＥ")) // full-width letters
    }

    @Test
    fun theKeysDoNotDependOnTheDefaultLocale() {
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR")) // "I".lowercase() would be the dotless ı here
            assertEquals(ColumnMeaning.TITLE, HeaderAliases.meaning("TITLE"))
            assertEquals(ColumnMeaning.WEBSITE, HeaderAliases.meaning("LINK"))
            assertEquals(ColumnMeaning.NOTES, HeaderAliases.meaning("IZOH"))
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test
    fun ofTwoColumnsWithOneMeaningTheStrongerWordWinsAndTheOtherBecomesAField() {
        // Chromium's priority among notes: note › notes › comment › comments; "username" before "email".
        val file = file(csv("Comments,Email,Name,Notes,Username,Password,URL,Website", "c,a@x.uz,Bank,n,ali,pw,bank.uz,www.bank.uz"))
        assertEquals(
            listOf(ColumnMeaning.FIELD, ColumnMeaning.FIELD, ColumnMeaning.TITLE, ColumnMeaning.NOTES, ColumnMeaning.USERNAME,
                ColumnMeaning.PASSWORD, ColumnMeaning.WEBSITE, ColumnMeaning.FIELD),
            file.columns!!.meanings,
        )
        assertEquals(listOf(0, 1, 7), file.columns!!.ambiguous) // the mapping screen may show them
        val e = file.accounts.single().entry
        assertEquals(listOf("n", "ali", "https://bank.uz"), listOf(e.notes, e.username, e.urls.single()))
        assertEquals(listOf(visible("Comments", "c"), visible("Email", "a@x.uz"), visible("Website", "www.bank.uz")), e.fields)
    }

    @Test
    fun anExtraFieldIsHiddenUnlessItsLabelIsHarmless() {
        for (label in listOf("Email", "Recovery e-mail", "Phone number", "Telefon", "Телефон", "Security question", "Savol",
            "Folder", "Guruh", "Address", "Manzil", "Website", "Category", "Created", "Sana", "Description", "Izoh")) {
            assertTrue(label, HeaderAliases.isHarmless(label))
        }
        for (label in listOf("PIN", "PIN code", "Email password", "Answer", "Javob", "Kodli soʻz", "Код", "Secret key", "Kalit",
            "API token", "CVV", "Passport", "Bank word", "Something", "")) {
            assertFalse(label, HeaderAliases.isHarmless(label))
        }
        val file = file(csv("Nomi,Sayt,Login,Parol,Telefon,Kodli soʻz,Izoh", "Bank,bank.uz,ali,pw,+998901112233,Qarshi,eslatma"))
        assertEquals(
            listOf(visible("Telefon", "+998901112233"), hidden("Kodli soʻz", "Qarshi")),
            file.accounts.single().entry.fields,
        )
        assertEquals("eslatma", file.accounts.single().entry.notes)
    }

    /**
     * A label that names a secret is hidden wherever the word stands in it: written as one word after a
     * harmless one ("EmailPassword", "UserPin") it was taken for harmless, and the column's passwords were
     * imported as visible extra fields. And a number is no name: "Номер карты" passed for "ном…".
     */
    @Test
    fun aSecretWordInsideALongerWordHidesTheFieldAndANumberIsNoName() {
        for (label in listOf("EmailPassword", "UserPass", "LoginPassword", "userpassword", "mailpass", "loginPin", "UserPin2", "SiteKey",
            "WebToken", "UserSecret", "TelegramCode", "PochtaParoli", "ПочтаПароль", "ЛогинКод", "Номер карты", "Номер", "Karta nomeri",
            "Nomer")) {
            assertFalse(label, HeaderAliases.isHarmless(label))
        }
        // A phone number is still harmless, by its other word; and the names are what they were.
        for (label in listOf("Номер телефона", "Telefon nomeri", "Nomi", "Номи", "Ном", "Foydalanuvchi nomi", "User name", "E-mail")) {
            assertTrue(label, HeaderAliases.isHarmless(label))
        }
        val file = file(csv("Site,Login,Password,Email,EmailPassword,Номер карты", "bank.uz,ali,pw,a@x.uz,Em@il-Pw1,8600123412341234"))
        assertEquals(
            listOf(visible("Email", "a@x.uz"), hidden("EmailPassword", "Em@il-Pw1"), hidden("Номер карты", "8600123412341234")),
            file.accounts.single().entry.fields,
        )
    }
}
