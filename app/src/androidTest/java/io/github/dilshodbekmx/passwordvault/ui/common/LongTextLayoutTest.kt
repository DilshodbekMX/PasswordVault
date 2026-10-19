package io.github.dilshodbekmx.passwordvault.ui.common

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.settings.ThemeChoice
import io.github.dilshodbekmx.passwordvault.ui.layout.LayoutCheck
import io.github.dilshodbekmx.passwordvault.ui.layout.LayoutProbe
import io.github.dilshodbekmx.passwordvault.ui.theme.PasswordVaultTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Build plan 6.2, [device], on any phone: the shared parts of `ui/common` that keep long texts whole,
 * drawn 320 dp wide with the font at 200 % (a font scale of 2 given to the composition, whatever the
 * phone's own setting) and looked at with the layout walk's [LayoutProbe]: no text cut, clipped, split
 * or past an edge, no control under 48 dp. Texts long as Uzbek and Russian ones, or longer.
 */
@RunWith(AndroidJUnit4::class)
class LongTextLayoutTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /** [content] 320 dp wide and [height] high, at a font scale of 2, in the app's theme. */
    private fun show(height: Int = 700, content: @Composable () -> Unit) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                PasswordVaultTheme(ThemeChoice.LIGHT) {
                    Box(Modifier.width(320.dp).height(height.dp)) { content() }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun assertClean(what: String) {
        val probe = LayoutProbe(compose, compose.activity).apply { checkEnglish = false }
        val found = probe.look()
        val problems = LayoutCheck.entries.flatMap { check -> found.of(check).map { "${check.title}: $it" } }
        assertTrue("$what: $problems", problems.isEmpty())
    }

    private fun bottomOf(tag: String): Float = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.bottom

    private fun topOf(tag: String): Float = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top

    @Test
    fun aTopBarTitleThatDoesNotFitMovesUnderTheButtonsWhole() {
        show {
            VaultTopBar(
                title = "Restore from a backup file of this phone",
                titleModifier = Modifier.testTag(TITLE),
                navigationIcon = {
                    IconButton(onClick = {}, modifier = Modifier.testTag(BACK)) { Icon(painterResource(R.drawable.ic_entry_back), contentDescription = "Back") }
                },
                actions = { TextButton(onClick = {}) { Text("Empty the list") } },
            )
        }
        assertClean("the top bar")
        assertTrue("the title lies under the buttons", topOf(TITLE) >= bottomOf(BACK) - 1)
    }

    @Test
    fun aTopBarTitleThatMayBeCutStaysOnOneLine() {
        show {
            VaultTopBar(
                title = "Kapitalbank internet banking for the family business",
                titleMayBeCut = true,
                titleModifier = Modifier.testTag(TITLE),
                navigationIcon = { IconButton(onClick = {}, modifier = Modifier.testTag(BACK)) { Icon(painterResource(R.drawable.ic_entry_back), contentDescription = "Back") } },
            )
        }
        assertClean("the top bar of an account")
        assertTrue("the title is beside the button", topOf(TITLE) < bottomOf(BACK))
    }

    @Test
    fun tabLabelsShrinkRatherThanBreakAWord() {
        // As in the bottom bar: a third of 320 dp less the bar's gaps, in the label's style (labelMedium).
        show {
            Row(Modifier.fillMaxWidth()) {
                for (label in listOf("Parollar", "Безопасность", "Sozlamalar")) {
                    Box(Modifier.width(96.dp)) { FittedText(label, style = MaterialTheme.typography.labelMedium) }
                }
            }
        }
        assertClean("the tab labels")
    }

    @Test
    fun aFieldLabelOfTwoLinesGoesAboveItsBoxAndCoversNothing() {
        show {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                VaultTextField(state = remember { TextFieldState("Gmail") }, label = { Text("Nomi") }, modifier = Modifier.fillMaxWidth())
                VaultTextField(state = remember { TextFieldState("aziz@gmail.com") }, label = { Text("Elektron pochta, telefon raqami yoki login") }, modifier = Modifier.fillMaxWidth())
                VaultSecureField(state = remember { TextFieldState("x") }, label = { Text("Master parolingizni yana bir marta yozing") }, modifier = Modifier.fillMaxWidth())
            }
        }
        compose.waitForIdle()
        assertClean("the fields")
    }

    @Test
    fun aButtonBesideATextGoesUnderItWhenTheTextWouldBeSqueezed() {
        show {
            TextBeside(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                text = { Text("Gmail paroli · 0:52 dan keyin oʻchiriladi", modifier = Modifier.testTag(TEXT)) },
                action = { TextButton(onClick = {}, modifier = Modifier.testTag(ACTION)) { Text("Hozir oʻchirish") } },
            )
        }
        assertClean("the text and its button")
        assertTrue("the button is under the text", topOf(ACTION) >= bottomOf(TEXT) - 1)
    }

    @Test
    fun theClipboardRowKeepsItsTextsToAFifthOfTheWindowAndScrollsThem() {
        show {
            ClipboardRow(
                text = "«Kapitalbank internet banking» paroli · 0:52 dan keyin oʻchiriladi, agar uni oldinroq oʻchirmasangiz",
                announcement = ANNOUNCEMENT,
                headline = "Parol nusxalandi",
                actionLabel = "Hozir oʻchirish",
            )
        }
        assertClean("the clipboard row")
        val texts = compose.onNode(hasContentDescription(ANNOUNCEMENT), useUnmergedTree = true).fetchSemanticsNode()
        val window = compose.activity.window.decorView.height
        assertTrue("the texts are longer than what shows: ${texts.size.height} px", texts.size.height > texts.boundsInRoot.height + 1)
        assertTrue("what shows is at most a fifth of the window: ${texts.boundsInRoot.height} of $window px", texts.boundsInRoot.height <= window * 0.2f + 1)
        val button = compose.onNode(hasText("Hozir oʻchirish") and hasClickAction()).fetchSemanticsNode()
        assertTrue("Clear now is under the texts", button.boundsInRoot.top >= texts.boundsInRoot.bottom - 1)
    }

    @Test
    fun aKeypadPageKeepsTheDotsAndEveryKeyOnTheScreen() {
        show(height = 569) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                KeypadColumn(
                    viewport = 569.dp,
                    head = {
                        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
                            Text("Choose a 6-digit PIN")
                            repeat(4) { Text("Use this PIN to open the app on this phone, like your bank app. It is not your phone's PIN.") }
                        }
                    },
                    dots = { PinDots(entered = 2, modifier = Modifier.padding(16.dp)) },
                    keys = { PinKeys(onDigit = {}, onDelete = {}) },
                    foot = { TextButton(onClick = {}, modifier = Modifier.padding(8.dp)) { Text("Not now") } },
                )
            }
        }
        assertClean("the keypad page")
        val page = 569 * compose.activity.resources.displayMetrics.density
        assertTrue("the dots are on the screen", bottomOf(PinPadTags.DOTS) <= page)
        for (n in 0..9) {
            val key = compose.onNodeWithTag(PinPadTags.key(n), useUnmergedTree = true).fetchSemanticsNode()
            assertTrue("key $n is on the screen", key.boundsInRoot.bottom <= page + 1)
            assertTrue("key $n is at least 64 dp", key.size.height >= 64 * compose.activity.resources.displayMetrics.density - 1)
        }
        assertTrue("one keypad", compose.onAllNodesWithTag(PinPadTags.key(0), useUnmergedTree = true).fetchSemanticsNodes().size == 1)
    }

    private companion object {
        const val TITLE = "test.title"
        const val BACK = "test.back"
        const val TEXT = "test.text"
        const val ACTION = "test.action"
        const val ANNOUNCEMENT = "Parol nusxalandi. U 1 daqiqadan keyin oʻchiriladi."
    }
}
