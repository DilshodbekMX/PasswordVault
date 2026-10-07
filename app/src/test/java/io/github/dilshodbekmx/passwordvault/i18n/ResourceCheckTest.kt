package io.github.dilshodbekmx.passwordvault.i18n

import io.github.dilshodbekmx.passwordvault.i18n.ResourceCheck.Language
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Build plan 2.9, [JVM]: [ResourceCheck] finds what i18n.md §5 item 2 says it must, and nothing
 * else, on small resource trees written here: missing translations (the warning of [ResourcesTest]),
 * placeholders, plural quantities, the apostrophe, script and quote rules, and extra names.
 */
class ResourceCheckTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val res: File by lazy { folder.newFolder("res") }

    /** Writes `<resources>` with [body] into [dir]/[file]. */
    private fun write(dir: String, body: String, file: String = "strings_test.xml") {
        File(res, dir).apply { mkdirs() }.resolve(file).writeText(
            """<?xml version="1.0" encoding="utf-8"?>
            |<resources xmlns:tools="http://schemas.android.com/tools" xmlns:xliff="urn:oasis:names:tc:xliff:document:1.2">
            |$body
            |</resources>
            """.trimMargin(),
        )
    }

    private fun check(language: Language) = ResourceCheck.check(res, language)

    private fun assertFailures(language: Language, vararg expected: String) {
        val failures = check(language).failures
        assertEquals(failures.joinToString("\n"), expected.size, failures.size)
        for ((i, part) in expected.withIndex()) {
            assertTrue("failure $i should mention \"$part\":\n${failures.joinToString("\n")}", part in failures[i])
        }
    }

    private val english = """
        <string name="test_save">Save</string>
        <string name="test_copied">%1${'$'}s copied</string>
        <plurals name="test_accounts">
            <item quantity="one">%d&#160;account</item>
            <item quantity="other">%d&#160;accounts</item>
        </plurals>
        <string name="test_brand" translatable="false">Google Password Manager</string>
    """.trimIndent()

    @Test
    fun missingTranslationsAreReportedAsMissingNotAsFailures() {
        write("values", english)
        write("values", """<string name="i18n_language_uz" translatable="false">Oʻzbekcha</string>""", "donottranslate.xml")
        for (language in Language.entries - Language.EN) {
            val report = check(language)
            assertEquals(listOf("test_save", "test_copied", "test_accounts"), report.missing)
            assertEquals(emptyList<String>(), report.failures)
            assertEquals(3, report.checked)
        }
        write("values-ru", """<string name="test_save">Сохранить</string>""")
        assertEquals(listOf("test_copied", "test_accounts"), check(Language.RU).missing)
        assertEquals(emptyList<String>(), check(Language.EN).missing)
    }

    @Test
    fun aCompleteTranslationPasses() {
        write("values", english)
        write(
            "values-ru",
            """
            <string name="test_save">Сохранить</string>
            <string name="test_copied">Скопировано: %1${'$'}s</string>
            <plurals name="test_accounts">
                <item quantity="one">%d&#160;аккаунт</item>
                <item quantity="few">%d&#160;аккаунта</item>
                <item quantity="many">%d&#160;аккаунтов</item>
                <item quantity="other">%d&#160;аккаунта</item>
            </plurals>
            """.trimIndent(),
        )
        write(
            "values-uz",
            """
            <string name="test_save">Saqlash</string>
            <string name="test_copied">“%1${'$'}s” nusxalandi. Maʼlumot oʻchirilmadi, gʻalaba!</string>
            <plurals name="test_accounts">
                <item quantity="one">%d&#160;ta hisob</item>
                <item quantity="other">%d&#160;ta hisob</item>
            </plurals>
            """.trimIndent(),
        )
        write(
            "values-b+uz+Cyrl",
            """
            <string name="test_save">Сақлаш</string>
            <string name="test_copied">“%1${'$'}s” нусхаланди. PIN код ва Google Password Manager, A–Z</string>
            <plurals name="test_accounts">
                <item quantity="one">%d&#160;та ҳисоб</item>
                <item quantity="other">%d&#160;та ҳисоб</item>
            </plurals>
            """.trimIndent(),
        )
        for (language in Language.entries) {
            val report = check(language)
            assertEquals("$report: ${report.failures}", emptyList<String>(), report.failures)
            assertEquals(emptyList<String>(), report.missing)
        }
    }

    @Test
    fun placeholdersMustBeTheSame() {
        write("values", english)
        write(
            "values-ru",
            """
            <string name="test_save">Сохранить %1${'$'}s</string>
            <string name="test_copied">Скопировано: %s</string>
            <plurals name="test_accounts">
                <item quantity="one">один аккаунт</item>
                <item quantity="few">%d&#160;аккаунта</item>
                <item quantity="many">%d&#160;аккаунтов</item>
                <item quantity="other">%1${'$'}s&#160;аккаунта</item>
            </plurals>
            """.trimIndent(),
        )
        assertFailures(
            Language.RU,
            "values-ru/strings_test.xml test_save: placeholders [%1\$s], values/ has []",
            "test_copied: placeholders [%s], values/ has [%1\$s]",
            "test_accounts: placeholders [%1\$s, %d], values/ has [%d]",
            "test_accounts (other): placeholders [%1\$s] are not in values/",
        )
    }

    @Test
    fun pluralsNeedTheQuantitiesOfTheirLanguage() {
        write(
            "values",
            """
            <plurals name="test_tries"><item quantity="one">%d try</item></plurals>
            <plurals name="test_days"><item quantity="one">%d day</item><item quantity="other">%d days</item></plurals>
            """.trimIndent(),
        )
        assertFailures(Language.EN, "test_tries: <plurals> without [other]")
        write("values-ru", """<plurals name="test_days"><item quantity="one">%d день</item><item quantity="other">%d дня</item></plurals>""")
        write("values-uz", """<plurals name="test_days"><item quantity="other">%d kun</item></plurals>""")
        assertFailures(Language.RU, "test_days: <plurals> without [few, many]")
        assertFailures(Language.UZ_LATN, "test_days: <plurals> without [one]")
    }

    @Test
    fun uzbekLatinWritesOnlyTheTwoModifierLetters() {
        write(
            "values",
            """
            <string name="test_1">Delete</string>
            <string name="test_2">Delete</string>
            <string name="test_3">Information</string>
            <string name="test_4">Victory</string>
            <string name="test_5">Doctor</string>
            <string name="test_6">Fine</string>
            """.trimIndent(),
        )
        write(
            "values-uz",
            """
            <string name="test_1">O\'chirish</string>
            <string name="test_2">O‘chirish</string>
            <string name="test_3">Ma’lumot</string>
            <string name="test_4">G`alaba</string>
            <string name="test_5">Şifokor</string>
            <string name="test_6">Oʻchirish, maʼlumot, gʻalaba, “Saqlash”</string>
            """.trimIndent(),
        )
        assertFailures(
            Language.UZ_LATN,
            "test_1: \"O'\": write oʻ and gʻ with ʻ (U+02BB)",
            "test_2: \"O‘\"",
            "test_3: \"a’l\": write the tutuq belgisi with ʼ (U+02BC)",
            "test_4: \"G`\"",
            "test_5: \"Ş\": the letters of the 2026 reform",
        )
    }

    @Test
    fun uzbekCyrillicHasNoLatinLettersOutsideTheAllowList() {
        write(
            "values",
            """
            <string name="test_1">Save</string>
            <string name="test_2">Add</string>
            <string name="test_3">Open %1${'$'}s in Telegram</string>
            <string name="test_4">OK</string>
            """.trimIndent(),
        )
        write(
            "values-b+uz+Cyrl",
            """
            <string name="test_1">Save</string>
            <string name="test_2">Ҳисоб qoʻshish</string>
            <string name="test_3">%1${'$'}s ни Telegram да очиш</string>
            <string name="test_4">OK</string>
            """.trimIndent(),
        )
        assertFailures(Language.UZ_CYRL, "test_1: Latin letters \"Save\"", "test_2: Latin letters \"q")
    }

    @Test
    fun russianUsesGuillemetsAndALowerCaseVy() {
        write(
            "values",
            """
            <string name="test_1">"Password"</string>
            <string name="test_2">If you forgot</string>
            <string name="test_3">Did you forget?</string>
            <string name="test_4">Done. You can go on.</string>
            <string name="test_5">Choose a file</string>
            <string name="test_6">Your password</string>
            """.trimIndent(),
        )
        write(
            "values-ru",
            """
            <string name="test_1">\"Пароль\"</string>
            <string name="test_2">Если Вы забыли</string>
            <string name="test_3">Вы забыли пароль?</string>
            <string name="test_4">Готово. Вы можете продолжить, «Ваш» выбор.\nВаш пароль</string>
            <string name="test_5">Выберите файл</string>
            <string name="test_6">Это Ваш пароль</string>
            """.trimIndent(),
        )
        assertFailures(
            Language.RU,
            "test_1: \\\" in Russian: use « »",
            "test_2: \"Вы\" inside a sentence",
            "test_4: \"Ваш\" inside a sentence",
            "test_6: \"Ваш\" inside a sentence",
        )
    }

    @Test
    fun threeDotsAreAnEllipsisInEveryLanguage() {
        write("values", """<string name="test_1">Saving...</string><string name="test_2">Loading…</string>""")
        write("values-ru", """<string name="test_1">Сохранение…</string><string name="test_2">Загрузка...</string>""")
        assertFailures(Language.EN, "test_1: \"...\" instead of \"…\"")
        assertFailures(Language.RU, "test_2: \"...\" instead of \"…\"")
    }

    @Test
    fun extraNamesKindsAndDuplicatesAreFailures() {
        write("values", english)
        write(
            "values-ru",
            """
            <plurals name="test_save"><item quantity="one">a</item><item quantity="few">b</item><item quantity="many">c</item><item quantity="other">d</item></plurals>
            <string name="test_gone">Больше не нужно</string>
            <string name="test_brand">Менеджер паролей Google</string>
            <string name="abc_action_bar_up_description" tools:override="true">Перейти вверх</string>
            """.trimIndent(),
        )
        write("values-ru", """<string name="test_gone">Ещё раз</string>""", "strings_other.xml")
        assertFailures(
            Language.RU,
            "values-ru/strings_test.xml test_gone: defined twice in values-ru",
            "test_save: a PLURALS here, a STRING in values/",
            "test_gone: values/ has no test_gone",
            "test_brand: test_brand is not translated",
        )
    }

    @Test
    fun unescapeAndPlaceholdersReadAndroidsSyntax() {
        assertEquals("O'chirish \"x\"\n\t@?", ResourceCheck.unescape("""O\'chirish \"x\"\n\t\@\?"""))
        assertEquals("a b", ResourceCheck.unescape("\"a b\""))
        assertEquals("ʻ", ResourceCheck.unescape("\\u02bb"))
        assertEquals(listOf("%1\$s", "%d", "%2\$.1f"), ResourceCheck.placeholdersOf("%1\$s and %d and %2\$.1f, 100%% and %n"))
        assertEquals(emptyList<String>(), ResourceCheck.placeholdersOf("85 % of them, 100%"))
    }
}
