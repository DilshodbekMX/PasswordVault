package io.github.dilshodbekmx.passwordvault.ui.lock

import android.content.Context
import android.content.res.Configuration
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.core.lock.flow.AsideLine
import io.github.dilshodbekmx.passwordvault.core.lock.flow.FingerprintLine
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockScreen
import io.github.dilshodbekmx.passwordvault.core.lock.flow.MpError
import io.github.dilshodbekmx.passwordvault.core.lock.flow.MpReason
import io.github.dilshodbekmx.passwordvault.core.lock.flow.Practice
import io.github.dilshodbekmx.passwordvault.core.lock.flow.ReplacedLine
import io.github.dilshodbekmx.passwordvault.core.lock.record.Shortcut
import io.github.dilshodbekmx.passwordvault.fake.FakeLockManager
import io.github.dilshodbekmx.passwordvault.lock.LockUiState
import java.util.Locale
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Build plan §1 rule 5: the master-password screen walked once in the `en-XA` pseudo-locale (the
 * debug build makes it, i18n.md §1.1): every `lock_` string and plural comes out pseudo-localized,
 * so none is plain English or marked untranslatable; and the screen, drawn in `en-XA` in states that
 * between them show every line and button, has no text and no label that stayed plain English, so
 * none is written into the code.
 */
@RunWith(AndroidJUnit4::class)
class LockPseudoLocaleTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun pseudo(base: Context): Context {
        val configuration = Configuration(base.resources.configuration).apply { setLocale(Locale.forLanguageTag("en-XA")) }
        return base.createConfigurationContext(configuration)
    }

    @Test
    fun everyLockStringIsPseudoLocalized() {
        val english = compose.activity
        val pseudo = pseudo(english)
        val strings = R.string::class.java.fields.filter { it.name.startsWith("lock_") }
        assertTrue("the lock strings are found", strings.size >= 40)
        for (field in strings) {
            val id = field.getInt(null)
            assertNotEquals("lock string ${field.name}", english.getString(id), pseudo.getString(id))
        }
        val plurals = R.plurals::class.java.fields.filter { it.name.startsWith("lock_") }
        assertTrue("the lock plurals are found", plurals.size >= 2)
        for (field in plurals) {
            val id = field.getInt(null)
            for (n in listOf(1, 3)) {
                assertNotEquals(
                    "lock plural ${field.name} ($n)",
                    english.resources.getQuantityString(id, n, n),
                    pseudo.resources.getQuantityString(id, n, n),
                )
            }
        }
    }

    @Test
    fun theScreenDrawsEveryTextFromResources() {
        val pseudo = pseudo(compose.activity)
        val states = listOf(
            screen(
                reason = MpReason.PracticeDue,
                canUsePin = true,
                practice = Practice(Shortcut.PIN),
                error = MpError.Wrong(offerForgot = true, replaced = ReplacedLine(AT, Shortcut.PIN, "00000000-0000-4000-8000-0000000000aa")),
                pendingReadyInMs = 20 * 3_600_000L,
                aside = AsideLine.LockedOn(AT),
                fingerprintLine = FingerprintLine.NoneSaved,
            ),
            screen(reason = MpReason.WelcomeBack, practice = Practice(Shortcut.FINGERPRINT), error = MpError.NoMemory),
            screen(
                reason = MpReason.FromBefore(AT),
                aside = AsideLine.FromBefore(AT),
                error = MpError.Wrong(offerForgot = false, replaced = ReplacedLine(AT, Shortcut.FINGERPRINT, null)),
                waitUntilElapsedMs = SystemClock.elapsedRealtime() + 600_000,
            ),
            screen(reason = MpReason.OlderCopy(AT), opening = true),
        )
        val lock = FakeLockManager(initial = LockUiState.Locked(states.first()))
        compose.setContent {
            CompositionLocalProvider(
                LocalContext provides pseudo,
                LocalConfiguration provides pseudo.resources.configuration,
                LocalResources provides pseudo.resources,
            ) {
                MasterPasswordHost(lock)
            }
        }
        for (state in states) {
            compose.runOnUiThread { lock.state.value = LockUiState.Locked(state) }
            compose.waitForIdle()
            compose.onNodeWithTag(LockTags.TITLE).assertExists()
            val texts = shownTexts()
            assertTrue("the screen shows texts", texts.size >= 4)
            for (text in texts) {
                assertTrue("\"$text\" stayed plain English in en-XA (state $state)", text.any { it.code > 0x7F })
            }
        }
    }

    /** Every text and label of the screen, as an accessibility service reads them. */
    private fun shownTexts(): List<String> {
        val out = mutableListOf<String>()
        fun walk(node: androidx.compose.ui.semantics.SemanticsNode) {
            node.config.getOrNull(SemanticsProperties.Text)?.forEach { if (it.text.isNotBlank()) out += it.text }
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.forEach { if (it.isNotBlank()) out += it }
            node.children.forEach(::walk)
        }
        compose.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes().forEach(::walk)
        return out
    }

    private companion object {
        const val AT = 1_791_331_200_000L

        fun screen(
            reason: MpReason? = null,
            canUsePin: Boolean = false,
            practice: Practice? = null,
            waitUntilElapsedMs: Long? = null,
            error: MpError? = null,
            pendingReadyInMs: Long? = null,
            opening: Boolean = false,
            aside: AsideLine? = null,
            fingerprintLine: FingerprintLine? = null,
        ) = LockScreen.MasterPassword(reason, canUsePin, practice, waitUntilElapsedMs, error, pendingReadyInMs, opening, aside, fingerprintLine)
    }
}
