package io.github.dilshodbekmx.passwordvault.ui.theme

import android.content.Context
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.lock.LockUiState
import io.github.dilshodbekmx.passwordvault.settings.ThemeChoice
import io.github.dilshodbekmx.passwordvault.ui.common.PinPadTags
import io.github.dilshodbekmx.passwordvault.ui.entry.EntryTags
import io.github.dilshodbekmx.passwordvault.ui.list.ListTags
import io.github.dilshodbekmx.passwordvault.ui.lock.LockTags
import io.github.dilshodbekmx.passwordvault.ui.setup.SetupTags
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Build plan §1 rule 5 for `w5-carry` step 2: the screens whose look changed, walked once in the
 * `en-XA` pseudo-locale (the debug build makes it, i18n.md §1.1) in each of the app's own styles,
 * light and dark: Welcome with the logo, the PIN pad and the fingerprint-only screen with Bank blue's
 * panel, the list with its cards, its "Add" button and Emerald's outlines, and an account's screen
 * with its cards. Their texts come out pseudo-localized (the second step added no text, and none is
 * written into the code); the longer texts still lie where the style puts them (the pad's subtitle on
 * the panel); and each screen is kept as a picture in `/data/local/tmp/pv-shots-look-xa/` on the
 * device, for a person to look for clipped text.
 */
@RunWith(AndroidJUnit4::class)
class LookPseudoLocaleTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var gallery: LookGallery? = null

    @Before
    fun touchMode() = enterTouchMode(compose.activity)

    @After
    fun tearDown() {
        gallery?.close()
    }

    private fun pseudo(base: Context): Context {
        val configuration = Configuration(base.resources.configuration).apply { setLocale(Locale.forLanguageTag("en-XA")) }
        return base.createConfigurationContext(configuration)
    }

    @Test
    fun theChangedScreensInEnXaInEveryStyle() {
        val english = compose.activity
        val pseudo = pseudo(english)
        val res = pseudo.resources
        for (id in TEXTS) assertNotEquals("${english.resources.getResourceEntryName(id)} is pseudo-localized", english.getString(id), res.getString(id))

        lateinit var made: LookGallery
        compose.runOnUiThread { made = LookGallery().also { gallery = it } }
        made.list.awaitList()
        compose.setContent {
            CompositionLocalProvider(
                // The activity stays the window's owner (LocalActivity follows LocalContext otherwise), so the
                // theme and Bank blue's panel still set the status bar's icons.
                LocalActivity provides english,
                LocalContext provides pseudo,
                LocalConfiguration provides pseudo.resources.configuration,
                LocalResources provides pseudo.resources,
            ) {
                LookGalleryHost(made)
            }
        }
        shell("rm -rf $DIR")
        shell("mkdir -p $DIR")
        var pictures = 0
        for (style in OWN_STYLES) {
            for (theme in listOf(ThemeChoice.LIGHT, ThemeChoice.DARK)) {
                compose.runOnIdle {
                    made.settings.update {
                        it.style = style
                        it.theme = theme
                    }
                }
                val look = Looks.of(style, dark = theme == ThemeChoice.DARK)
                val name = "xa-${style.name.lowercase()}-${theme.name.lowercase()}"

                show(made, LookGallery.Screen.WELCOME, SetupTags.LOGO)
                assertShown(res.getString(R.string.setup_welcome_title), "$name: Welcome's title")
                pictures += take("$name-welcome")

                compose.runOnIdle { made.lock.state.value = LockUiState.Locked(LookGallery.PAD) }
                show(made, LookGallery.Screen.LOCK, PinPadTags.DOTS)
                compose.onNodeWithTag(LockTags.SUBTITLE, useUnmergedTree = true).assertTextEquals(res.getString(R.string.lock_pad_subtitle))
                if (look.lockPanel != null) {
                    assertTrue("$name: the longer subtitle still lies on the panel", bounds(LockTags.PANEL).holds(bounds(LockTags.SUBTITLE)))
                }
                pictures += take("$name-pad")

                compose.runOnIdle { made.lock.state.value = LockUiState.Locked(LookGallery.FINGERPRINT_ONLY) }
                show(made, LookGallery.Screen.LOCK, LockTags.FINGERPRINT_ONLY)
                compose.onNodeWithTag(LockTags.USE_FINGERPRINT).assertTextContains(res.getString(R.string.lock_use_fingerprint))
                pictures += take("$name-fingerprint-only")

                show(made, LookGallery.Screen.LIST, ListTags.LIST)
                compose.onNodeWithTag(ListTags.FAVOURITES, useUnmergedTree = true).assertTextEquals(res.getString(R.string.list_favourites))
                // The "Add" button's label is drawn but not in the semantics tree (Material 3's extended button clears it): the picture shows it.
                pictures += take("$name-list")

                show(made, LookGallery.Screen.ACCOUNT, EntryTags.PASSWORD)
                compose.onNodeWithTag(EntryTags.USERNAME).assertTextContains(res.getString(R.string.entry_username), substring = true)
                pictures += take("$name-account")
            }
        }
        assertTrue("the pictures were kept", pictures == OWN_STYLES.size * 2 * 5)
    }

    private fun show(gallery: LookGallery, screen: LookGallery.Screen, tag: String) {
        compose.runOnIdle { gallery.shown = screen }
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    private fun assertShown(text: String, what: String) {
        assertTrue(what, compose.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
    }

    private fun bounds(tag: String): Rect = compose.node(tag).boundsInRoot

    private fun Rect.holds(inner: Rect): Boolean =
        inner.left >= left - 0.5f && inner.top >= top - 0.5f && inner.right <= right + 0.5f && inner.bottom <= bottom + 0.5f

    /** Saves the screen as [name].png in [DIR]; 1 when it is there. */
    private fun take(name: String): Int {
        compose.waitForIdle()
        Thread.sleep(SETTLE_MS)
        shell("screencap -p $DIR/$name.png")
        return if (shell("ls $DIR").contains("$name.png")) 1 else 0
    }

    private companion object {
        const val DIR = "/data/local/tmp/pv-shots-look-xa"
        const val SETTLE_MS = 500L

        /** The texts the walk looks for, each of a screen whose look changed. */
        val TEXTS = listOf(
            R.string.setup_welcome_title,
            R.string.lock_pad_subtitle,
            R.string.lock_use_fingerprint,
            R.string.list_favourites,
            R.string.list_add,
            R.string.entry_username,
        )
    }
}
