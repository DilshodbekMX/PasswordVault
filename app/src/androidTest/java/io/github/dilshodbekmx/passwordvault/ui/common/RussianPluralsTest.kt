package io.github.dilshodbekmx.passwordvault.ui.common

import android.content.res.Configuration
import android.content.res.Resources
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.dilshodbekmx.passwordvault.R
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ux.md §12 check 8 (its plural part), [device], on any phone: "Russian plurals are right for 1, 2, 5,
 * 11, 21, 22 and 25". The phone picks a plural's item by CLDR's rules (i18n.md §4.1: one for 1 and 21,
 * few for 2 and 22, many for 5, 11 and 25); every plural of the app has the item the phone picks for
 * each of these numbers, the same item for the numbers of one category, and counted accounts and
 * seconds read as Russian writes them. Uzbek and English take one for 1 and other for the rest.
 */
@RunWith(AndroidJUnit4::class)
class RussianPluralsTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun resources(tag: String): Resources {
        val configuration = Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }
        return context.createConfigurationContext(configuration).resources
    }

    private val plurals = R.plurals::class.java.fields.filter { field -> AREAS.any { field.name.startsWith(it) } }

    @Test
    fun eachPluralTakesTheSameItemForTheNumbersOfOneCategory_inRussian() {
        val ru = resources("ru")
        assertTrue("the app's plurals", plurals.size > 60)
        for (field in plurals) {
            val id = field.getInt(null)
            fun item(n: Int) = ru.getQuantityText(id, n).toString()
            assertEquals("${field.name}: 1 and 21 (one)", item(1), item(21))
            assertEquals("${field.name}: 2 and 22 (few)", item(2), item(22))
            assertEquals("${field.name}: 5 and 11 (many)", item(5), item(11))
            assertEquals("${field.name}: 5 and 25 (many)", item(5), item(25))
        }
    }

    @Test
    fun accountsAndSecondsReadAsRussianWritesThem() {
        val ru = resources("ru")
        val accounts = mapOf(
            1 to "Найден 1 аккаунт.", 2 to "Найдено 2 аккаунта.", 5 to "Найдено 5 аккаунтов.", 11 to "Найдено 11 аккаунтов.",
            21 to "Найден 21 аккаунт.", 22 to "Найдено 22 аккаунта.", 25 to "Найдено 25 аккаунтов.",
        )
        for ((n, expected) in accounts) assertEquals("import_found $n", expected, ru.getQuantityString(R.plurals.import_found, n, n))
        val seconds = mapOf(
            1 to "Через 1 секунду", 2 to "Через 2 секунды", 5 to "Через 5 секунд", 11 to "Через 11 секунд",
            21 to "Через 21 секунду", 22 to "Через 22 секунды", 25 to "Через 25 секунд",
        )
        for ((n, expected) in seconds) assertEquals("settings_lock_delay_seconds $n", expected, ru.getQuantityString(R.plurals.settings_lock_delay_seconds, n, n))
    }

    @Test
    fun uzbekAndEnglishTakeOneForOneAndOtherForTheRest() {
        for (tag in listOf("en", "uz", "uz-Cyrl")) {
            val res = resources(tag)
            for (field in plurals) {
                val id = field.getInt(null)
                val other = res.getQuantityText(id, 2).toString()
                for (n in listOf(5, 11, 21, 22, 25)) assertEquals("$tag ${field.name} $n", other, res.getQuantityText(id, n).toString())
            }
        }
    }

    private companion object {
        val AREAS = listOf(
            "common_", "setup_", "lock_", "pin_", "master_", "clipboard_", "shell_", "data_", "list_", "edit_", "entry_",
            "settings_", "backup_", "export_", "import_", "security_", "change_", "help_", "language_",
        )
    }
}
