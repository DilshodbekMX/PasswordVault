package io.github.dilshodbekmx.passwordvault.ui.common

import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.settings.ThemeChoice
import io.github.dilshodbekmx.passwordvault.ui.layout.LayoutCheck
import io.github.dilshodbekmx.passwordvault.ui.layout.LayoutProbe
import io.github.dilshodbekmx.passwordvault.ui.theme.PasswordVaultTheme
import java.util.Locale
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Build plan 8.1 (the open problem of section 12, R6), [device], on any phone: a snackbar whose action
 * would squeeze its message puts the action under it ([VaultSnackbar]). At 320 dp and a font of 200 %
 * the save-failed message with "Try again" and "Olx deleted" with "Undo" keep every word whole in the
 * four languages, the action under the message where beside it would squeeze it; a short message on a
 * phone at its own size keeps Material's one row.
 */
@RunWith(AndroidJUnit4::class)
class SnackbarLayoutTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private class Message(override val message: String, override val actionLabel: String?) : SnackbarData, SnackbarVisuals {
        override val withDismissAction = false
        override val duration = SnackbarDuration.Long
        override val visuals: SnackbarVisuals get() = this

        override fun performAction() = Unit

        override fun dismiss() = Unit
    }

    private var shown by mutableStateOf<Message?>(null)
    private var widthDp by mutableIntStateOf(320)
    private var fontScaleNow by mutableFloatStateOf(2f)
    private var started = false

    /** [message] in a host [width] dp wide at the font scale [fontScale] (the content is set once per test). */
    private fun show(width: Int, fontScale: Float, message: Message) {
        widthDp = width
        fontScaleNow = fontScale
        shown = message
        if (!started) {
            started = true
            compose.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = fontScaleNow)) {
                    PasswordVaultTheme(ThemeChoice.LIGHT) {
                        Box(Modifier.width(widthDp.dp)) { shown?.let { key(it) { VaultSnackbar(it) } } }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    /** The app's text [id] in the language [tag] (`uz-Cyrl` is `values-b+uz+Cyrl`). */
    private fun text(tag: String, id: Int, vararg args: Any): String {
        val configuration = Configuration(compose.activity.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }
        return compose.activity.createConfigurationContext(configuration).getString(id, *args)
    }

    private fun boundsOf(text: String, button: Boolean): Rect {
        val matcher = if (button) hasText(text) and hasClickAction() else hasText(text)
        return compose.onNode(matcher, useUnmergedTree = !button).fetchSemanticsNode().boundsInRoot
    }

    private fun assertClean(what: String) {
        val found = LayoutProbe(compose, compose.activity).apply { checkEnglish = false }.look()
        val problems = LayoutCheck.entries.flatMap { check -> found.of(check).map { "${check.title}: $it" } }
        assertTrue("$what: $problems", problems.isEmpty())
    }

    @Test
    fun at320DpAnd200PercentNoWordOfTheMessageBreaks_andASqueezingActionGoesUnder_inTheFourLanguages() {
        for (tag in listOf("en", "uz", "uz-Cyrl", "ru")) {
            val saveFailed = Message(text(tag, R.string.list_save_failed), text(tag, R.string.common_try_again))
            val deleted = Message(text(tag, R.string.list_deleted_message, "Olx"), text(tag, R.string.common_undo))
            for (message in listOf(saveFailed, deleted)) {
                show(320, 2f, message)
                val what = "$tag: \"${message.message}\" with \"${message.actionLabel}\""
                assertClean(what)
                val said = boundsOf(message.message, button = false)
                val action = boundsOf(checkNotNull(message.actionLabel), button = true)
                // Beside "Try again" the message would be a few letters wide in every language, and beside
                // "Қайтариш" (Undo) "Olx ўчириб ташланди" would break a word (build plan §12, R6).
                val squeezes = message === saveFailed || tag == "uz-Cyrl"
                if (squeezes) assertTrue("$what: the action ($action) lies under the message ($said)", action.top >= said.bottom - 1f)
            }
        }
    }

    @Test
    fun aShortMessageKeepsItsActionBesideIt() {
        val message = Message(text("en", R.string.list_deleted_message, "Gmail"), text("en", R.string.common_undo))
        show(411, 1f, message)
        assertClean("\"${message.message}\" at 411 dp and 100 %")
        val said = boundsOf(message.message, button = false)
        val action = boundsOf(checkNotNull(message.actionLabel), button = true)
        assertTrue("the action ($action) beside the message ($said)", action.top < said.bottom && action.left >= said.right)
    }
}
