package io.github.dilshodbekmx.passwordvault.ui.lock

import android.content.Context
import android.content.res.Configuration
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.core.lock.flow.AsideLine
import io.github.dilshodbekmx.passwordvault.core.lock.flow.AsideVault
import io.github.dilshodbekmx.passwordvault.core.lock.flow.ChoosePinReason
import io.github.dilshodbekmx.passwordvault.core.lock.flow.CurrentVault
import io.github.dilshodbekmx.passwordvault.core.lock.flow.FingerprintLine
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockScreen
import io.github.dilshodbekmx.passwordvault.core.lock.flow.MpError
import io.github.dilshodbekmx.passwordvault.core.lock.flow.MpReason
import io.github.dilshodbekmx.passwordvault.core.lock.flow.Notice
import io.github.dilshodbekmx.passwordvault.core.lock.flow.NoticeAnswer
import io.github.dilshodbekmx.passwordvault.core.lock.flow.PadStatus
import io.github.dilshodbekmx.passwordvault.core.lock.flow.PinReason
import io.github.dilshodbekmx.passwordvault.core.lock.flow.Practice
import io.github.dilshodbekmx.passwordvault.core.lock.flow.ReplacedLine
import io.github.dilshodbekmx.passwordvault.core.lock.record.Shortcut
import io.github.dilshodbekmx.passwordvault.fake.FakeLockManager
import io.github.dilshodbekmx.passwordvault.lock.LockStatus
import io.github.dilshodbekmx.passwordvault.lock.LockUiState
import io.github.dilshodbekmx.passwordvault.lock.PinStatus
import io.github.dilshodbekmx.passwordvault.nav.Navigator
import io.github.dilshodbekmx.passwordvault.nav.Route
import io.github.dilshodbekmx.passwordvault.platform.AndroidClock
import io.github.dilshodbekmx.passwordvault.settings.ThemeChoice
import io.github.dilshodbekmx.passwordvault.ui.layout.LayoutHook
import io.github.dilshodbekmx.passwordvault.ui.layout.press
import io.github.dilshodbekmx.passwordvault.ui.layout.revealTag
import io.github.dilshodbekmx.passwordvault.ui.theme.PasswordVaultTheme
import io.github.dilshodbekmx.passwordvault.window.WindowPolicy
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Build plan §1 rule 5: the lock screens walked once in the `en-XA` pseudo-locale (the debug build
 * makes it, i18n.md §1.1): every `lock_` string and plural comes out pseudo-localized, so none is
 * plain English or marked untranslatable; and each screen (the master password, the pad, fingerprint
 * only, an older master password, "Open the older set instead?", "can't be opened"), the sheet, the
 * questions, every notice and the prompt, drawn in `en-XA` in states that between them show every
 * line and button, has no text and no label that stayed plain English, so none is written into the
 * code. The app's name and the keys' digits are the same in every language.
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
        assertTrue("the lock strings are found", strings.size >= 39)
        for (field in strings) {
            val id = field.getInt(null)
            assertNotEquals("lock string ${field.name}", english.getString(id), pseudo.getString(id))
        }
        val plurals = R.plurals::class.java.fields.filter { it.name.startsWith("lock_") }
        assertTrue("the lock plurals are found", plurals.size >= 2)
        for (field in plurals) {
            val id = field.getInt(null)
            for (n in listOf(1, 3)) {
                // A second argument (a date) for the plurals that take one; Formatter ignores it elsewhere.
                assertNotEquals(
                    "lock plural ${field.name} ($n)",
                    english.resources.getQuantityString(id, n, n, "x"),
                    pseudo.resources.getQuantityString(id, n, n, "x"),
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
            screen(reason = MpReason.FromBefore(AT), aside = AsideLine.FromBefore(AT), error = MpError.OlderSetNotOpened(AT)),
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
        for ((index, state) in states.withIndex()) {
            compose.runOnUiThread { lock.state.value = LockUiState.Locked(state) }
            compose.waitForIdle()
            LayoutHook.look(compose, "lock", "master-password-$index-en-XA")
            compose.revealTag(LockTags.TITLE).assertExists()
            val texts = shownTexts()
            assertTrue("the screen shows texts", texts.size >= 4)
            for (text in texts) {
                assertTrue("\"$text\" stayed plain English in en-XA (state $state)", text.any { it.code > 0x7F })
            }
        }
    }

    @Test
    fun theOtherLockScreensDrawEveryTextFromResources() {
        val states = listOf(
            LockUiState.Locked(LockScreen.Pad(3, PadStatus.LastTry, fingerprintKey = true, opening = false, aside = AsideLine.LockedOn(AT))),
            LockUiState.Locked(LockScreen.Pad(6, PadStatus.Interrupted(3), fingerprintKey = false, opening = true, aside = null)),
            LockUiState.Locked(LockScreen.FingerprintOnly(FingerprintLine.CannotCheck, PinReason.STOPPED_WORKING, AsideLine.FromBefore(AT))),
            LockUiState.Locked(LockScreen.OlderPassword(AT, unfinishedChange = true)),
            LockUiState.Locked(LockScreen.OlderPassword(AT, unfinishedChange = false, aside = AsideLine.LockedOn(AT))),
            LockUiState.Locked(LockScreen.OpenOlderSet(AT, AsideLine.FromBefore(AT))),
            LockUiState.Locked(LockScreen.OpenOlderSet(AT, AsideLine.LockedOn(AT))),
            LockUiState.Locked(LockScreen.CannotOpen(AsideLine.LockedOn(AT))),
            LockUiState.Locked(LockScreen.CannotOpen(AsideLine.FromBefore(AT))),
            LockUiState.Unreadable(CurrentVault.Why.IO, listOf(AsideVault("00000000-0000-4000-8000-0000000000aa", AT, null))),
            LockUiState.Unreadable(CurrentVault.Why.DAMAGED, emptyList()),
            LockUiState.Unreadable(CurrentVault.Why.NEWER, emptyList()),
        )
        val lock = FakeLockManager(initial = states.first())
        inPseudo { LockRouteHost(lock) }
        for ((index, state) in states.withIndex()) {
            compose.runOnUiThread { lock.state.value = state }
            compose.waitForIdle()
            val screen = (state as? LockUiState.Locked)?.screen?.let { it::class.simpleName } ?: state::class.simpleName
            LayoutHook.look(compose, "lock", "$screen-$index-en-XA")
            assertAllPseudo("$state", shownTexts(), atLeast = 3)
        }
    }

    @Test
    fun theSheetAndTheQuestionsDrawEveryTextFromResources() {
        val lock = FakeLockManager(initial = LockUiState.Locked(screen()))
        lock.status.value = LockStatus.NONE.copy(pin = PinStatus.CannotCheck, aside = listOf(AsideVault("00000000-0000-4000-8000-0000000000aa", AT, null)))
        inPseudo { LockRouteHost(lock) }
        compose.revealTag(LockTags.FORGOT).press()
        compose.waitForIdle()
        compose.revealTag(LockTags.SHEET).assertExists()
        LayoutHook.look(compose, "lock", "forgot-sheet-en-XA")
        assertAllPseudo("the sheet", shownTexts(), atLeast = 12)
        compose.revealTag(LockTags.SHEET_EMPTY).press()
        compose.waitForIdle()
        compose.revealTag(LockTags.CONFIRM).assertExists()
        LayoutHook.look(compose, "lock", "start-empty-question-en-XA")
        assertAllPseudo("Start with an empty app?", shownTexts(), atLeast = 4)
        compose.revealTag(LockTags.CONFIRM_CANCEL).press()
        compose.revealTag(LockTags.SHEET_TRY_AGAIN).press()
        compose.runOnUiThread { lock.state.value = LockUiState.Locked(LockScreen.OlderPassword(AT, unfinishedChange = true)) }
        compose.waitForIdle()
        compose.revealTag(LockTags.OLDER_FROM_BEFORE).press()
        compose.waitForIdle()
        compose.revealTag(LockTags.CONFIRM).assertExists()
        LayoutHook.look(compose, "lock", "from-before-question-en-XA")
        assertAllPseudo("Open your passwords as they were before …?", shownTexts(), atLeast = 4)
    }

    @Test
    fun theNoticesAndThePromptDrawEveryTextFromResources() {
        val notices = listOf(
            Notice.OlderCopy(1, AT),
            Notice.OlderThanLastTime(2, AT),
            Notice.Pending(3, AT, Shortcut.PIN, 20 * 3_600_000L),
            Notice.Pending(4, AT, Shortcut.FINGERPRINT, 3_600_000L),
            Notice.PendingReady(5, AT, Shortcut.PIN),
            Notice.PendingCancelled(6),
            Notice.Replaced(7, AT, Shortcut.PIN, "00000000-0000-4000-8000-0000000000aa"),
            Notice.Replaced(8, AT, Shortcut.FINGERPRINT, "00000000-0000-4000-8000-0000000000aa"),
            Notice.WrongTries(9, 3, AT, true, 4, true, 2),
            Notice.WrongTries(10, 1, System.currentTimeMillis(), false, null, false, 0),
            Notice.WrongTries(11, 2, null, false, null, false, 0),
            Notice.ChoosePin(12, ChoosePinReason.TURNED_OFF, needsMasterPassword = true),
            Notice.FingerprintAgain(13),
            Notice.StorageUnreadable(14),
            Notice.BackupNow(15),
        )
        val lock = FakeLockManager(initial = LockUiState.Unlocked(notices, null))
        val nav = object : Navigator {
            override fun go(route: Route) = true
            override fun back() = false
            override fun replace(route: Route) = false
            override fun tab(root: Route) = false
        }
        inPseudo { NoticesInTheme(lock, nav) }
        for (notice in notices) {
            compose.waitForIdle()
            compose.revealTag(NoticeTags.NOTICE).assertExists()
            LayoutHook.look(compose, "lock", "notice-${notice::class.simpleName}-${notice.id}-en-XA")
            assertAllPseudo("$notice", shownTexts(), atLeast = 2)
            if (notice is Notice.WrongTries && notice.pinsBeforeOff != null) {
                // "Type master password": the prompt, then back to the notices.
                compose.revealTag(NoticeTags.answer(NoticeAnswer.TYPE_MASTER_PASSWORD)).press()
                compose.waitForIdle()
                compose.revealTag(NoticeTags.PROMPT).assertExists()
                LayoutHook.look(compose, "lock", "prompt-en-XA")
                assertAllPseudo("the prompt", shownTexts(), atLeast = 4)
                compose.revealTag(NoticeTags.PROMPT_CANCEL).press()
            } else {
                compose.runOnUiThread { lock.send(io.github.dilshodbekmx.passwordvault.lock.LockIntent.NoticeAnswered(notice.id, NoticeAnswer.OK)) }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag(NoticeTags.NOTICE).assertDoesNotExist()
    }

    @Composable
    private fun NoticesInTheme(lock: FakeLockManager, nav: Navigator) {
        WindowPolicy.Root {
            PasswordVaultTheme(ThemeChoice.LIGHT) {
                val vm = viewModel { NoticesViewModel(lock, AndroidClock, NOTICE_SCOPE) }
                NoticesHost(vm, nav)
            }
        }
    }

    /** [content] in `en-XA`, as the screens draw it there. */
    private fun inPseudo(content: @Composable () -> Unit) {
        val pseudo = pseudo(compose.activity)
        compose.setContent {
            CompositionLocalProvider(
                LocalContext provides pseudo,
                LocalConfiguration provides pseudo.resources.configuration,
                LocalResources provides pseudo.resources,
            ) {
                content()
            }
        }
        compose.waitForIdle()
    }

    /**
     * Every text in [texts] has a pseudo-localized (non-ASCII) character, except the app's name and
     * the keys' digits, which are the same in every language.
     */
    private fun assertAllPseudo(what: String, texts: List<String>, atLeast: Int) {
        assertTrue("$what shows texts: $texts", texts.size >= atLeast)
        val appName = compose.activity.getString(R.string.app_name)
        for (text in texts) {
            if (text == appName || text.all(Char::isDigit) || text in materialLabels) continue
            assertTrue("\"$text\" stayed plain English in en-XA ($what)", text.any { it.code > 0x7F })
        }
    }

    /**
     * Compose's and Material 3's own labels (the sheet's "Close sheet" and the like), in English: the
     * libraries' resources, which the app neither writes nor pseudo-localizes.
     */
    private val materialLabels: Set<String> by lazy {
        listOf("androidx.compose.ui.R\$string", "androidx.compose.material3.R\$string", "androidx.compose.foundation.R\$string")
            .mapNotNull { runCatching { Class.forName(it) }.getOrNull() }
            .flatMap { it.fields.toList() }
            .mapNotNull { field -> runCatching { compose.activity.getString(field.getInt(null)) }.getOrNull() }
            .toSet()
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
        val NOTICE_SCOPE = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

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
