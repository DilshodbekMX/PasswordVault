package io.github.dilshodbekmx.passwordvault.clipboard

import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.i18n.UiText
import io.github.dilshodbekmx.passwordvault.settings.ThemeChoice
import io.github.dilshodbekmx.passwordvault.ui.theme.PasswordVaultTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Build plan 3.8: the clipboard status row (ux.md §6 "Clipboard status row", §8): what it shows and
 * says in each state, its one button, its countdown, and its place above the system's navigation
 * bar. `ClipboardRow` (ui/common) keeps the shown text out of the accessibility tree on purpose
 * (the countdown is for the eyes only), so the texts are checked as [clipRowTexts] gives them.
 */
@RunWith(AndroidJUnit4::class)
class ClipStatusRowTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val password: UiText = UiText.of(R.string.list_copied_password, "Gmail")
    private val username: UiText = UiText.of(R.string.list_copied_username, "Gmail")

    private fun status(what: UiText = password, sensitive: Boolean = true, delayMs: Long = 60_000L, cleared: Boolean = false) =
        ClipStatus(what, sensitive, clearsAtElapsedMs = CLEARS_AT, delayMs = delayMs, cleared = cleared)

    private class Case(val status: ClipStatus, val now: Long, val sdk: Int)

    /** [clipRowTexts] for one case after another, in one composition. */
    private inner class Texts {
        private var case by mutableStateOf(Case(status(), 0L, 33))
        private var result: ClipRowTexts? = null

        init {
            compose.setContent { result = clipRowTexts(case.status, case.now, case.sdk) }
        }

        fun of(status: ClipStatus, leftMs: Long = 52_000L, sdk: Int = 33): ClipRowTexts {
            case = Case(status, CLEARS_AT - leftMs, sdk)
            compose.waitForIdle()
            return result!!
        }
    }

    @Test
    fun aWaitingPasswordSaysWhatWasCopiedAndTheTimeLeft() {
        val texts = Texts()
        val now = texts.of(status())
        assertEquals("Password for Gmail · clears in 0:52", now.text)
        assertEquals("Password copied. It will be removed from the clipboard in 1${NBSP}minute.", now.announcement)
        assertEquals("Clear now", now.action)
        assertEquals(false, now.cleared)

        assertEquals("the time left is rounded up", "Password for Gmail · clears in 1:00", texts.of(status(), leftMs = 59_001).text)
        assertEquals("Password for Gmail · clears in 0:01", texts.of(status(), leftMs = 1).text)
        assertEquals("Password for Gmail · clears in 5:00", texts.of(status(delayMs = 300_000), leftMs = 300_000).text)
    }

    @Test
    fun theHeadlineIsForAndroid7To12Only() {
        val texts = Texts()
        assertNull("Android 13 and later show their own confirmation", texts.of(status(), sdk = 33).headline)
        assertNull(texts.of(status(), sdk = 37).headline)
        assertEquals("Password copied", texts.of(status(), sdk = 32).headline)
        assertEquals("Password copied", texts.of(status(), sdk = 24).headline)
    }

    @Test
    fun theAnnouncementNamesTheChosenDelay() {
        val texts = Texts()
        fun removal(delayMs: Long) = texts.of(status(delayMs = delayMs)).announcement.removePrefix("Password copied. ")
        assertEquals("It will be removed from the clipboard in 30${NBSP}seconds.", removal(30_000))
        assertEquals("It will be removed from the clipboard in 1${NBSP}minute.", removal(60_000))
        assertEquals("It will be removed from the clipboard in 2${NBSP}minutes.", removal(120_000))
        assertEquals("It will be removed from the clipboard in 5${NBSP}minutes.", removal(300_000))
        // A clip found by a new process has the time that was left, whatever it is.
        assertEquals("It will be removed from the clipboard in 24${NBSP}seconds.", removal(23_456))
        assertEquals("It will be removed from the clipboard in 1${NBSP}second.", removal(1_000))
        assertEquals("It will be removed from the clipboard in 95${NBSP}seconds.", removal(95_000))
    }

    @Test
    fun onlyASecretOfAnAccountIsCalledAPassword() {
        val texts = Texts()
        val name = texts.of(status(what = username, sensitive = false), sdk = 28)
        assertEquals("Username for Gmail · clears in 0:52", name.text)
        assertEquals("Copied", name.headline)
        assertEquals("Copied. It will be removed from the clipboard in 1${NBSP}minute.", name.announcement)

        // Text selected in a note is sensitive too, and is not a password (D-25).
        val selected = texts.of(status(what = AndroidSecretClipboard.COPIED_TEXT, sensitive = true), sdk = 28)
        assertEquals("Copied text · clears in 0:52", selected.text)
        assertEquals("Copied", selected.headline)
        assertEquals("Copied. It will be removed from the clipboard in 1${NBSP}minute.", selected.announcement)
    }

    @Test
    fun pastItsTimeTheRowSaysClearingAndNeverANegativeTime() {
        val texts = Texts()
        val counting = texts.of(status(), sdk = 28)
        for (left in listOf(0L, -1L, -45_000L)) {
            val late = texts.of(status(), leftMs = left, sdk = 28)
            assertEquals("Clearing…", late.text)
            assertEquals("TalkBack is told nothing new", counting.announcement, late.announcement)
            assertEquals("the copy can still be cleared by hand", "Clear now", late.action)
            assertEquals("Password copied", late.headline)
        }
        // A clip that was overdue when a new process found it has no time to announce.
        assertEquals("Clearing…", texts.of(status(what = AndroidSecretClipboard.COPIED_TEXT, delayMs = 0), leftMs = 0).announcement)
    }

    @Test
    fun afterTheClearTheRowSaysClipboardCleared() {
        val texts = Texts()
        for (sdk in listOf(24, 37)) {
            val cleared = texts.of(status(cleared = true), leftMs = 0, sdk = sdk)
            assertEquals("Clipboard cleared", cleared.text)
            assertEquals("Clipboard cleared", cleared.announcement)
            assertNull(cleared.headline)
            assertNull(cleared.action)
            assertEquals(true, cleared.cleared)
        }
    }

    @Test
    fun theStateFollowsTheStatusAndTheClock() {
        assertEquals(ClipRowState.Counting(1), clipRowState(status(), CLEARS_AT - 1))
        assertEquals(ClipRowState.Clearing, clipRowState(status(), CLEARS_AT))
        assertEquals(ClipRowState.Clearing, clipRowState(status(), CLEARS_AT + 1))
        assertEquals("cleared, whatever the clock says", ClipRowState.Cleared, clipRowState(status(cleared = true), CLEARS_AT - 30_000))
    }

    @Test
    fun theRowIsOneLiveRegionWithOneButton() {
        var shown by mutableStateOf(status())
        var clears = 0
        compose.setContent {
            PasswordVaultTheme(ThemeChoice.LIGHT) {
                ClipStatusRow(shown, nowElapsedMs = CLEARS_AT - 52_000, onClear = { clears++ }, modifier = Modifier.testTag(ClipboardTags.ROW))
            }
        }
        val announcement = "Password copied. It will be removed from the clipboard in 1${NBSP}minute."
        val region = compose.onNodeWithContentDescription(announcement).fetchSemanticsNode().config
        assertEquals("a polite live region, read once (ux.md §8)", LiveRegionMode.Polite, region.getOrNull(SemanticsProperties.LiveRegion))
        assertEquals("the countdown is not in the accessibility tree", 0, compose.onAllNodesWithText("clears in", substring = true).fetchSemanticsNodes().size)
        compose.onNodeWithTag(ClipboardTags.ROW).assertIsDisplayed()

        compose.onNodeWithText("Clear now").performClick()
        assertEquals(1, clears)

        shown = status(cleared = true)
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Clipboard cleared").assertIsDisplayed()
        assertEquals("no button once the clear has run", 0, compose.onAllNodesWithText("Clear now").fetchSemanticsNodes().size)
    }

    @Test
    fun theSlotTakesNoRoomWhileNothingIsPending() {
        var shown by mutableStateOf<ClipStatus?>(null)
        compose.setContent {
            PasswordVaultTheme(ThemeChoice.LIGHT) {
                ClipStatusSlot(shown, elapsedMs = { CLEARS_AT - 52_000 }, onClear = {}, modifier = Modifier.testTag(SLOT))
            }
        }
        assertEquals(0, compose.onNodeWithTag(SLOT).fetchSemanticsNode().size.height)
        assertEquals(0, compose.onAllNodesWithTag(ClipboardTags.ROW).fetchSemanticsNodes().size)

        shown = status()
        compose.waitForIdle()
        compose.onNodeWithTag(ClipboardTags.ROW).assertIsDisplayed()
        assertTrue(compose.onNodeWithTag(SLOT).fetchSemanticsNode().size.height > 0)
    }

    @Test
    fun theCountdownReadsTheClockWhenTheShownSecondChanges() {
        var clock = CLEARS_AT - 2_500
        var seen = -1L
        var reads = 0
        compose.mainClock.autoAdvance = false
        val counting = status()
        val read = { reads++; clock }
        compose.setContent {
            val now by elapsedNow(counting, read)
            seen = now
        }
        compose.mainClock.advanceTimeByFrame()
        assertEquals(CLEARS_AT - 2_500, seen)

        // 2.5 s are left: "0:03" is shown until 2.0 s are left, half a second from now.
        clock = CLEARS_AT - 2_000
        compose.mainClock.advanceTimeBy(400)
        assertEquals("not read again before the second changes", CLEARS_AT - 2_500, seen)
        compose.mainClock.advanceTimeBy(200)
        assertEquals(CLEARS_AT - 2_000, seen)

        clock = CLEARS_AT - 1_000
        compose.mainClock.advanceTimeBy(1_000)
        assertEquals(CLEARS_AT - 1_000, seen)

        clock = CLEARS_AT
        compose.mainClock.advanceTimeBy(1_000)
        assertEquals("at its time the row stops counting", CLEARS_AT, seen)
        val before = reads
        clock = CLEARS_AT + 5_000
        compose.mainClock.advanceTimeBy(5_000)
        assertEquals(CLEARS_AT, seen)
        assertEquals("and the clock is left alone", before, reads)
    }

    /**
     * Edge to edge, as the app draws: as the lowest thing on the screen the row ends above the
     * system's navigation bar; with a bar of the app's under it, it ends at that bar.
     */
    @Test
    fun theRowKeepsClearOfTheSystemsNavigationBar() {
        compose.runOnUiThread { compose.activity.enableEdgeToEdge() }
        var barBelow by mutableStateOf(false)
        var inset = -1
        compose.setContent {
            inset = WindowInsets.navigationBars.getBottom(LocalDensity.current)
            PasswordVaultTheme(ThemeChoice.LIGHT) {
                Column(Modifier.fillMaxSize()) {
                    Spacer(Modifier.weight(1f))
                    ClipStatusSlot(status(), elapsedMs = { CLEARS_AT - 52_000 }, onClear = {}, modifier = Modifier.testTag(SLOT))
                    if (barBelow) Box(Modifier.fillMaxWidth().height(80.dp).testTag(BAR))
                }
            }
        }
        compose.waitUntil(5_000) { inset > 0 }
        compose.waitForIdle()
        val screenBottom = compose.onRoot().fetchSemanticsNode().boundsInRoot.bottom
        fun slot() = compose.onNodeWithTag(SLOT).fetchSemanticsNode().boundsInRoot
        fun row() = compose.onNodeWithTag(ClipboardTags.ROW).fetchSemanticsNode().boundsInRoot

        val lowest = slot()
        assertEquals("its colour goes on behind the system's bar", screenBottom, lowest.bottom, 1f)
        assertEquals("the row itself ends above the system's bar", screenBottom - inset, row().bottom, 1f)
        val rowHeight = row().height

        barBelow = true
        compose.waitForIdle()
        val barTop = compose.onNodeWithTag(BAR).fetchSemanticsNode().boundsInRoot.top
        assertEquals("with a bar under it the row ends at the bar", barTop, row().bottom, 1f)
        assertEquals("and leaves no gap for the system's bar", rowHeight, slot().height, 1f)

        barBelow = false
        compose.waitForIdle()
        assertEquals(screenBottom - inset, row().bottom, 1f)
    }

    private companion object {
        const val CLEARS_AT = 500_000L
        const val NBSP = " "
        const val SLOT = "slot"
        const val BAR = "bar"
    }
}
