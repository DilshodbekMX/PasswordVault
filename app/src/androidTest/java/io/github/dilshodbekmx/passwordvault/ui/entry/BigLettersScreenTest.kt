package io.github.dilshodbekmx.passwordvault.ui.entry

import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.core.model.Entry
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.settings.ThemeChoice
import io.github.dilshodbekmx.passwordvault.ui.entry.EntryRig.Companion.GMAIL
import io.github.dilshodbekmx.passwordvault.ui.entry.EntryRig.Companion.PASSWORD
import io.github.dilshodbekmx.passwordvault.ui.entry.EntryRig.Companion.gmail
import io.github.dilshodbekmx.passwordvault.ui.theme.PasswordVaultTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Build plan 3.7, [device]: "Show in big letters" (ux.md §5.13; architecture.md D-9d): one box per
 * character with its place, a tap that marks the place, the screen kept on, and every way out
 * (Close, Back, the stopped screen) leaving exactly once with nothing of the value left.
 */
@RunWith(AndroidJUnit4::class)
class BigLettersScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var rig: EntryRig
    private lateinit var vm: BigLettersViewModel

    private fun show(entry: Entry = gmail(), field: String = "password") {
        rig = EntryRig(Vault(entries = listOf(entry)))
        compose.setContent {
            PasswordVaultTheme(ThemeChoice.LIGHT) {
                vm = viewModel { rig.bigLettersViewModel(GMAIL, field) }
                BigLettersScreen(vm, rig.nav)
            }
        }
        compose.waitForIdle()
    }

    private fun text(id: Int, vararg args: Any): String = compose.activity.getString(id, *args)

    private fun cell(index: Int) = compose.onNodeWithTag(EntryTags.bigCell(index)).fetchSemanticsNode().config

    /** Whether some view of the activity asks to keep the screen on (`View.setKeepScreenOn`, FLAG_KEEP_SCREEN_ON). */
    private fun keepsScreenOn(): Boolean {
        var found = false
        fun walk(view: View) {
            if (view.keepScreenOn) found = true
            if (view is ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i))
        }
        compose.runOnUiThread { walk(compose.activity.window.decorView) }
        return found
    }

    @Test
    fun everyCharacterHasItsBoxAndItsPlace() {
        show()
        compose.onNodeWithText(text(R.string.entry_password)).assertIsDisplayed()
        compose.onNodeWithText("Gmail").assertIsDisplayed()

        // "Tr0ub4dor&3-xyz!": 16 boxes; TalkBack hears the place, the kind and the character.
        val expected = listOf(
            text(R.string.entry_big_cell_upper, 1, "T"),
            text(R.string.entry_big_cell_lower, 2, "r"),
            text(R.string.entry_big_cell_digit, 3, "0"),
            text(R.string.entry_big_cell_lower, 4, "u"),
        )
        for ((index, description) in expected.withIndex()) {
            val config = cell(index)
            assertEquals(listOf(description), config.getOrNull(SemanticsProperties.ContentDescription))
            assertEquals("a box shows a secret: sensitive", true, config.getOrNull(SemanticsProperties.IsSensitiveData))
            assertEquals(false, config.getOrNull(SemanticsProperties.Selected))
            assertEquals(text(R.string.entry_big_mark), config.getOrNull(SemanticsActions.OnClick)?.label)
        }
        assertEquals(listOf(text(R.string.entry_big_cell_symbol, 10, "&")), cell(9).getOrNull(SemanticsProperties.ContentDescription))
        assertEquals(listOf(text(R.string.entry_big_cell_symbol, 16, "!")), cell(15).getOrNull(SemanticsProperties.ContentDescription))
        compose.onNodeWithTag(EntryTags.bigCell(PASSWORD.length)).assertDoesNotExist()
        // Each character and its place number are drawn.
        compose.onNodeWithText("T", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("16", useUnmergedTree = true).assertExists()
        compose.onNodeWithText(text(R.string.entry_big_hint)).assertExists()
    }

    @Test
    fun aTapMarksTheBoxAndEveryBoxBeforeIt() {
        show()
        compose.onNodeWithTag(EntryTags.bigCell(5)).performClick()
        compose.waitForIdle()
        assertEquals((0..15).map { it <= 5 }, (0..15).map { cell(it).getOrNull(SemanticsProperties.Selected) })
        compose.onNodeWithTag(EntryTags.bigCell(2)).performClick()
        compose.waitForIdle()
        assertEquals((0..15).map { it <= 2 }, (0..15).map { cell(it).getOrNull(SemanticsProperties.Selected) })
        // A tap on the last marked box takes the mark away.
        compose.onNodeWithTag(EntryTags.bigCell(2)).performClick()
        compose.waitForIdle()
        assertTrue((0..15).none { cell(it).getOrNull(SemanticsProperties.Selected) == true })
        assertEquals(0, rig.nav.backs)
    }

    @Test
    fun theScreenStaysOnWhileItIsOpen() {
        assertFalse("not before it is shown", keepsScreenOn())
        show()
        assertTrue("FLAG_KEEP_SCREEN_ON through the view", keepsScreenOn())
    }

    @Test
    fun closeLeavesOnceWithNothingLeft() {
        show()
        compose.onNodeWithContentDescription(text(R.string.common_close)).assertIsDisplayed()
        compose.onNodeWithTag(EntryTags.BIG_CLOSE).performClick()
        compose.waitForIdle()
        assertEquals(1, rig.nav.backs)
        assertEquals(BigLettersUiState.CLOSED, vm.state.value)
        compose.onNodeWithTag(EntryTags.BIG_CELLS).assertDoesNotExist()
        compose.onNodeWithText("T", useUnmergedTree = true).assertDoesNotExist()
        // Whatever reports the end again, the screen has left already.
        compose.onNodeWithTag(EntryTags.BIG_CLOSE).performClick()
        Espresso.pressBack()
        compose.waitForIdle()
        assertEquals(1, rig.nav.backs)
    }

    @Test
    fun backLeavesOnce() {
        show()
        Espresso.pressBack()
        compose.waitForIdle()
        assertEquals(1, rig.nav.backs)
        assertEquals(BigLettersUiState.CLOSED, vm.state.value)
    }

    @Test
    fun itIsNotKeptAfterTheScreenStops() {
        show()
        compose.onNodeWithTag(EntryTags.bigCell(3)).performClick()
        compose.waitForIdle()

        // The app is left: closed at once, with nothing of the value to draw when the person comes back.
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        assertEquals(BigLettersUiState.CLOSED, vm.state.value)
        assertEquals(1, rig.nav.backs)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
        compose.onNodeWithTag(EntryTags.BIG_CELLS).assertDoesNotExist()
        assertEquals("one step back, not two", 1, rig.nav.backs)
    }

    @Test
    fun aRecreationKeepsItAndItsMark() {
        show()
        compose.onNodeWithTag(EntryTags.bigCell(7)).performClick()
        compose.waitForIdle()
        // A rotation recreates the activity; more boxes fit in a row sideways, so the screen stays.
        compose.activityRule.scenario.recreate()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        assertFalse(vm.state.value.closed)
        assertEquals(7, vm.state.value.markedUpTo)
        assertEquals(PASSWORD, vm.state.value.cells.joinToString("") { it.text })
        assertEquals(0, rig.nav.backs)
    }

    @Test
    fun aValueOfMoreThan128CharactersIsTooLong() {
        show(gmail().copy(password = "x".repeat(129)))
        compose.onNodeWithTag(EntryTags.BIG_TOO_LONG).assertTextEquals(text(R.string.entry_big_too_long))
        compose.onNodeWithTag(EntryTags.BIG_CELLS).assertDoesNotExist()
        compose.onNodeWithTag(EntryTags.BIG_CLOSE).performClick()
        compose.waitForIdle()
        assertEquals(1, rig.nav.backs)
    }

    @Test
    fun aSpaceAndAnExtraFieldsLabel() {
        // The hidden field "PIN code" with a space in its value: the box of the space says so.
        show(gmail().let { it.copy(fields = listOf(it.fields[0].copy(value = "40 71"))) }, field = "field-0")
        compose.onNodeWithText("PIN code").assertIsDisplayed()
        assertEquals(listOf(text(R.string.entry_big_cell_space, 3)), cell(2).getOrNull(SemanticsProperties.ContentDescription))
        compose.onNodeWithText(text(R.string.entry_big_space), useUnmergedTree = true).assertExists()
        assertEquals(listOf(text(R.string.entry_big_cell_digit, 5, "1")), cell(4).getOrNull(SemanticsProperties.ContentDescription))
    }

    @Test
    fun withNothingToShowItLeavesAtOnce() {
        show(field = "old-password")
        compose.waitUntil(10_000L) { rig.nav.backs == 1 }
        compose.onNodeWithTag(EntryTags.BIG_CELLS).assertDoesNotExist()
    }
}
