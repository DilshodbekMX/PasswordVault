package io.github.dilshodbekmx.passwordvault.i18n

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.dilshodbekmx.passwordvault.R
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [rememberFormatters] as the screens use it (i18n.md §4.6; build plan §12, `w5-carry`): a time less
 * than a minute away reads `common_just_now` from the strings of the composition ("Password changed
 * just now"), not ICU's own "now"; a minute reads ICU's words again.
 */
@RunWith(AndroidJUnit4::class)
class RememberFormattersTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun lessThanAMinuteReadsTheScreensJustNow() {
        val now = System.currentTimeMillis()
        val said = AtomicReference<String>()
        val minute = AtomicReference<String>()
        val locale = AtomicReference<java.util.Locale>()
        compose.setContent {
            val formatters = rememberFormatters()
            locale.set(formatters.locale)
            said.set(formatters.relative(now - 10_000L, now))
            minute.set(formatters.relative(now - 61_000L, now))
        }
        compose.waitForIdle()
        assertEquals(compose.activity.resources.getString(R.string.common_just_now), said.get())
        // Not ICU's word for the same moment, and a minute is ICU's own again ("1 minute ago").
        val icu = Formatters(locale.get(), hour24 = true)
        assertNotEquals(icu.relative(now - 10_000L, now), said.get())
        assertEquals(icu.relative(now - 61_000L, now), minute.get())
    }
}
