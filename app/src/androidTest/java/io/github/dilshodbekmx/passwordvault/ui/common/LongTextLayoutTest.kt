package io.github.dilshodbekmx.passwordvault.ui.common

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
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

    /** [content] 320 dp wide and [height] high, at a font scale of [fontScale] (2), in the app's theme. */
    private fun show(height: Int = 700, fontScale: Float = 2f, content: @Composable () -> Unit) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = fontScale)) {
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

    @Test
    fun aKeypadPageShowsItsTextsWholeAndPutsItsButtonsBelowTheFirstScreenRatherThanCutThem() {
        // The phone's own font size on a short page: the buttons do not fit on the first screen with
        // the whole texts and keys of 72 dp, so they go below it (the lock pad on a 1920-pixel screen).
        show(height = 640, fontScale = 1f) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                KeypadColumn(
                    viewport = 640.dp,
                    head = {
                        Column(Modifier.verticalScroll(rememberScrollState()).testTag(HEAD).padding(horizontal = 24.dp)) {
                            Text("PasswordVault", style = MaterialTheme.typography.headlineSmall)
                            repeat(3) { Text("Enter your app PIN") }
                        }
                    },
                    dots = { PinDots(entered = 0, modifier = Modifier.padding(16.dp)) },
                    keys = { PinKeys(onDigit = {}, onDelete = {}) },
                    foot = { Box(Modifier.fillMaxWidth().height(260.dp).testTag(FOOT)) },
                )
            }
        }
        assertClean("the keypad page")
        val density = compose.activity.resources.displayMetrics.density
        val head = compose.onNodeWithTag(HEAD, useUnmergedTree = true).fetchSemanticsNode()
        val range = head.config[SemanticsProperties.VerticalScrollAxisRange]
        assertTrue("the texts show whole: their column does not scroll (${range.maxValue()} px more)", range.maxValue() < 1f)
        val key = compose.onNodeWithTag(PinPadTags.key(0), useUnmergedTree = true).fetchSemanticsNode()
        assertTrue("the keys keep their 72 dp", key.size.height >= 72 * density - 1)
        assertTrue("key 0 is on the first screen", key.boundsInRoot.bottom <= 640 * density + 1)
        assertTrue("the buttons come after the keys", topOf(FOOT) >= key.boundsInRoot.bottom - 1)
    }

    @Test
    fun aKeypadPageTooShortForItsPartsKeepsItsTextsAtTheirLeast() {
        // KeypadColumn's fourth case: a long line under the dots (the last try's "Wrong PIN. 1 try left. After
        // that…" at 200 % on a 569 dp screen) leaves the keypad too little room even beside the least of the
        // texts. Then everything is at its least and the page scrolls as a whole: the texts take MIN_HEAD and
        // scroll in it, so the dots and the first keys stay on the first screen.
        show(height = 569) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                KeypadColumn(
                    viewport = 569.dp,
                    head = {
                        Column(Modifier.verticalScroll(rememberScrollState()).testTag(HEAD).padding(horizontal = 24.dp)) {
                            Text("Enter your app PIN", style = MaterialTheme.typography.headlineSmall)
                            repeat(4) { Text("Use this PIN to open the app on this phone, like your bank app. It is not your phone's PIN.") }
                        }
                    },
                    dots = {
                        Column(Modifier.padding(horizontal = 24.dp)) {
                            PinDots(entered = 2, modifier = Modifier.padding(16.dp))
                            Text("Wrong PIN. 1 try left. After that you'll need your fingerprint or master password. Take your time.")
                        }
                    },
                    keys = { PinKeys(onDigit = {}, onDelete = {}) },
                    foot = { TextButton(onClick = {}, modifier = Modifier.padding(8.dp)) { Text("Forgot PIN? Use master password") } },
                )
            }
        }
        val density = compose.activity.resources.displayMetrics.density
        val head = compose.onNodeWithTag(HEAD, useUnmergedTree = true).fetchSemanticsNode()
        assertTrue("the texts take at most $MIN_HEAD: ${head.size.height / density} dp", head.size.height <= MIN_HEAD.value * density + 1)
        // Where the parts lie on the page, scrolled away or not (boundsInRoot is empty for a node out of view).
        val dots = compose.onNodeWithTag(PinPadTags.DOTS, useUnmergedTree = true).fetchSemanticsNode()
        assertTrue("the dots are on the first screen", dots.positionInRoot.y + dots.size.height <= 569 * density)
        val key = compose.onNodeWithTag(PinPadTags.key(1), useUnmergedTree = true).fetchSemanticsNode().positionInRoot.y
        assertTrue("the keypad starts on the first screen: key 1 at ${key / density} dp", key < 569 * density)
    }

    @Test
    fun theTopBarMirrorsRightToLeft() {
        // ux.md §9: start and end everywhere. In a right-to-left language the back button stands at the
        // right, where a line starts, the title beside it, and the screen's actions at the left.
        show {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                VaultTopBar(
                    title = "Sozlamalar",
                    titleModifier = Modifier.testTag(TITLE),
                    navigationIcon = {
                        IconButton(onClick = {}, modifier = Modifier.testTag(BACK)) { Icon(painterResource(R.drawable.ic_entry_back), contentDescription = "Back") }
                    },
                    actions = {
                        IconButton(onClick = {}, modifier = Modifier.testTag(ACTION)) { Icon(painterResource(R.drawable.ic_entry_more), contentDescription = "More") }
                    },
                )
            }
        }
        val back = boundsOf(BACK)
        val action = boundsOf(ACTION)
        val title = boundsOf(TITLE)
        assertTrue("the back button is at the right, the action at the left: back $back, action $action", back.left >= action.right)
        assertTrue("the title lies between them: title $title", title.left >= action.right - 1 && title.right <= back.left + 1)
        assertTrue("the title starts next to the back button: title $title, back $back", back.left - title.right <= 16 * compose.activity.resources.displayMetrics.density)
    }

    @Test
    fun aTextAndItsButtonMirrorRightToLeft() {
        show {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    // Side by side: the text where a line starts (the right), its button at the end (the left).
                    TextBeside(
                        text = { Text("Olx", modifier = Modifier.testTag(TEXT)) },
                        action = { TextButton(onClick = {}, modifier = Modifier.testTag(ACTION)) { Text("Tiklash") } },
                    )
                    // The button under the text, at the end of its line: the left.
                    TextBeside(
                        text = { Text("Gmail paroli · 0:52 dan keyin oʻchiriladi", modifier = Modifier.testTag(TEXT_2)) },
                        action = { TextButton(onClick = {}, modifier = Modifier.testTag(ACTION_2)) { Text("Hozir oʻchirish") } },
                    )
                }
            }
        }
        val text = boundsOf(TEXT)
        val button = boundsOf(ACTION)
        assertTrue("side by side, the button is at the left of the text: text $text, button $button", button.right <= text.left + 1)
        val long = boundsOf(TEXT_2)
        val under = boundsOf(ACTION_2)
        assertTrue("the button is under the text: text $long, button $under", under.top >= long.bottom - 1)
        // The column is 320 dp less 16 dp each side: the button's end, its left, is at the column's left.
        val density = compose.activity.resources.displayMetrics.density
        assertTrue("the button under the text is at the left: $under", under.left - 16 * density < 304 * density - under.right)
    }

    @Test
    fun theProbeFindsALabelThatItsButtonsRoundEndsCut() {
        // Material's text button is fully rounded: a label of several lines meets its round ends, which cut
        // off the corners of its first and last lines. The layout walk must see that ("cut by its rounded shape").
        show {
            Box(Modifier.width(200.dp)) {
                TextButton(onClick = {}) { Text("Katta harflarda koʻrsatish, bitta-bitta harf") }
            }
        }
        val found = LayoutProbe(compose, compose.activity).apply { checkEnglish = false }.look().of(LayoutCheck.BUTTONS)
        assertTrue("the probe sees the cut letters: $found", found.any { "rounded shape" in it })
    }

    @Test
    fun buttonsInTheLongLabelShapeKeepEveryLetterOfALabelOfSeveralLines() {
        // The app's buttons whose labels may wrap (LongLabelShape): a text button, one with the 4 dp of
        // padding of "Show in big letters", and a filled and an outlined one with a label of four lines.
        val label = "Katta harflarda koʻrsatish, bitta-bitta harf"
        val longer = "Avvalgi parollarimni ochish (2026-yil 7-oktabrda qulflangan)"
        show {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.width(200.dp)) { TextButton(onClick = {}, shape = LongLabelShape) { Text(label) } }
                Box(Modifier.width(200.dp)) {
                    TextButton(onClick = {}, shape = LongLabelShape, contentPadding = PaddingValues(horizontal = 4.dp)) { Text(label) }
                }
                Button(onClick = {}, shape = LongLabelShape, modifier = Modifier.fillMaxWidth()) { Text(longer) }
                OutlinedButton(onClick = {}, shape = LongLabelShape, modifier = Modifier.fillMaxWidth()) { Text(longer) }
                // One line keeps Material's round ends.
                TextButton(onClick = {}, shape = LongLabelShape, modifier = Modifier.testTag(ACTION)) { Text("Hozir") }
            }
        }
        assertClean("buttons of several lines")
        val one = compose.onNodeWithTag(ACTION, useUnmergedTree = true).fetchSemanticsNode()
        assertTrue("a button of one line is at most 56 dp high: ${one.size.height}", one.size.height <= 56 * compose.activity.resources.displayMetrics.density)
    }

    private fun boundsOf(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    private companion object {
        const val HEAD = "test.head"
        const val FOOT = "test.foot"
        const val TITLE = "test.title"
        const val BACK = "test.back"
        const val TEXT = "test.text"
        const val TEXT_2 = "test.text2"
        const val ACTION = "test.action"
        const val ACTION_2 = "test.action2"
        const val ANNOUNCEMENT = "Parol nusxalandi. U 1 daqiqadan keyin oʻchiriladi."
    }
}
