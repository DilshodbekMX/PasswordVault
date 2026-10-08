package io.github.dilshodbekmx.passwordvault.clipboard

import android.content.Context
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.i18n.UiText
import io.github.dilshodbekmx.passwordvault.settings.ThemeChoice
import io.github.dilshodbekmx.passwordvault.ui.theme.PasswordVaultTheme
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Build plan §1 rule 5: the clipboard row walked once in the `en-XA` pseudo-locale (the debug build
 * makes it, i18n.md §1.1). Every `clipboard_` string and plural comes out pseudo-localized, so none
 * is plain English or marked untranslatable; and the row drawn in `en-XA` shows and says the
 * pseudo-localized texts, so none is written into the code.
 */
@RunWith(AndroidJUnit4::class)
class ClipboardPseudoLocaleTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun pseudo(base: Context): Context {
        val configuration = Configuration(base.resources.configuration).apply { setLocale(Locale.forLanguageTag("en-XA")) }
        return base.createConfigurationContext(configuration)
    }

    @Test
    fun everyClipboardStringIsPseudoLocalized() {
        val english = compose.activity
        val pseudo = pseudo(english)
        val strings = R.string::class.java.fields.filter { it.name.startsWith("clipboard_") }
        assertTrue("the clipboard strings are found: ${strings.map { it.name }}", strings.size >= 8)
        for (field in strings) {
            val id = field.getInt(null)
            assertNotEquals("string ${field.name}", english.getString(id), pseudo.getString(id))
        }
        val plurals = R.plurals::class.java.fields.filter { it.name.startsWith("clipboard_") }
        assertEquals("seconds and minutes", 2, plurals.size)
        for (field in plurals) {
            val id = field.getInt(null)
            for (count in listOf(1, 30)) {
                assertNotEquals("plural ${field.name} for $count", english.resources.getQuantityString(id, count, count), pseudo.resources.getQuantityString(id, count, count))
            }
        }
    }

    @Test
    fun theRowTakesItsTextsFromResources() {
        val pseudo = pseudo(compose.activity)
        val resources = pseudo.resources
        val status = ClipStatus(UiText.of(R.string.list_copied_password, "Gmail"), sensitive = true, clearsAtElapsedMs = 60_000, delayMs = 60_000)
        var texts: ClipRowTexts? = null
        compose.setContent {
            CompositionLocalProvider(
                LocalContext provides pseudo,
                LocalConfiguration provides resources.configuration,
                LocalResources provides resources,
            ) {
                PasswordVaultTheme(ThemeChoice.LIGHT) {
                    texts = clipRowTexts(status, nowElapsedMs = 8_000, sdk = 28)
                    ClipStatusRow(status, nowElapsedMs = 8_000, onClear = {}, modifier = Modifier.testTag(ClipboardTags.ROW), sdk = 28)
                }
            }
        }
        compose.waitForIdle()
        val copied = resources.getString(R.string.clipboard_copied_password)
        val what = resources.getString(R.string.list_copied_password, "Gmail")
        val announcement = resources.getString(
            R.string.clipboard_announcement,
            copied,
            resources.getQuantityString(R.plurals.clipboard_removed_in_minutes, 1, 1),
        )
        val shown = checkNotNull(texts)
        assertEquals(resources.getString(R.string.clipboard_row_clears_in, what, "0:52"), shown.text)
        assertEquals(copied, shown.headline)
        assertEquals(announcement, shown.announcement)
        assertEquals(resources.getString(R.string.clipboard_clear_now), shown.action)
        compose.onNodeWithContentDescription(announcement).fetchSemanticsNode()
        compose.onNodeWithText(resources.getString(R.string.clipboard_clear_now)).fetchSemanticsNode()

        // And the two other states.
        assertNotEquals("Clearing…", resources.getString(R.string.clipboard_row_clearing))
        assertNotEquals("Clipboard cleared", resources.getString(R.string.clipboard_row_cleared))
    }
}
