package io.github.dilshodbekmx.passwordvault.ui.security

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.settings.ThemeChoice
import io.github.dilshodbekmx.passwordvault.ui.theme.PasswordVaultTheme
import org.junit.After
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale

/**
 * Build plan §1 rule 5: the Security tab's screens walked once in the `en-XA` pseudo-locale (the debug
 * build makes it, i18n.md §1.1, §5), and in `ar-XB` for a right-to-left layout. Every `security_`
 * string and plural comes out pseudo-localized, so none is plain English or marked untranslatable;
 * the screens drawn in the pseudo-locale show the pseudo-localized texts, so none is written into the
 * code; and each screen is kept as a picture in the app's cache directory
 * (`cache/pv-screens/security-*.png`) for a person to look for clipped text.
 */
@RunWith(AndroidJUnit4::class)
class SecurityPseudoLocaleTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var rig: SecurityRig? = null

    @After
    fun closeRig() {
        rig?.close()
    }

    private fun pseudo(tag: String): Context {
        val base = compose.activity
        val configuration = Configuration(base.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }
        return base.createConfigurationContext(configuration)
    }

    @Composable
    private fun InLocale(context: Context, content: @Composable () -> Unit) {
        val configuration = context.resources.configuration
        CompositionLocalProvider(
            LocalContext provides context,
            LocalConfiguration provides configuration,
            LocalResources provides context.resources,
            // The direction comes from the window, not from these resources: ar-XB is laid out right to left.
            LocalLayoutDirection provides if (configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL) LayoutDirection.Rtl else LayoutDirection.Ltr,
        ) {
            PasswordVaultTheme(ThemeChoice.LIGHT, content)
        }
    }

    /**
     * Keeps a picture of the whole screen in `cache/pv-screens/` (the debug build does not set
     * FLAG_SECURE), and one in `/data/local/tmp/pv-shots-7.1/pseudo/`, which the uninstall after the run
     * leaves for the package's agent to look at.
     */
    private fun save(name: String) {
        compose.waitForIdle()
        val dir = File(compose.activity.cacheDir, "pv-screens").apply { mkdirs() }
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        File(dir, "security-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        securityShell("mkdir -p ${SecurityShots.DIR}/pseudo")
        securityShell("screencap -p ${SecurityShots.DIR}/pseudo/security-$name.png")
    }

    @Test
    fun everySecurityStringIsPseudoLocalized() {
        val english = compose.activity
        val pseudo = pseudo("en-XA")
        val strings = R.string::class.java.fields.filter { it.name.startsWith("security_") }
        assertTrue("the tab's strings are found (${strings.size})", strings.size >= 35)
        for (field in strings) {
            val id = field.getInt(null)
            assertNotEquals("string ${field.name}", english.getString(id), pseudo.getString(id))
        }
        val plurals = R.plurals::class.java.fields.filter { it.name.startsWith("security_") }
        assertTrue("the tab's plurals are found (${plurals.size})", plurals.size >= 10)
        for (field in plurals) {
            val id = field.getInt(null)
            for (count in listOf(1, 5)) {
                val args = arrayOf<Any>(count, count)
                assertNotEquals(
                    "plural ${field.name} for $count",
                    english.resources.getQuantityString(id, count, *args),
                    pseudo.resources.getQuantityString(id, count, *args),
                )
            }
        }
    }

    @Test
    fun theTabInEnXa() = tab("en-XA")

    @Test
    fun theTabInArXb() = tab("ar-XB")

    @Test
    fun aGroupInEnXa() = group("en-XA")

    @Test
    fun aGroupInArXb() = group("ar-XB")

    /** The tab with every card: its top, then scrolled to its end. */
    private fun tab(tag: String) {
        val context = pseudo(tag)
        val made = SecurityRig(SecurityAccounts.everything()).also { rig = it }
        made.awaitReport()
        compose.setContent {
            InLocale(context) {
                val vm = viewModel { made.securityViewModel() }
                SecurityScreen(vm, made.nav)
            }
        }
        compose.waitForIdle()
        val res = context.resources
        compose.onNodeWithText(res.getString(R.string.security_title)).assertExists()
        compose.onNodeWithText(res.getString(R.string.security_why_title)).assertExists()
        compose.onNodeWithText(res.getString(R.string.security_master_title)).assertExists()
        compose.onNodeWithText(compose.activity.getString(R.string.security_title)).assertDoesNotExist()
        save("tab-top-$tag")
        compose.onNodeWithTag(SecurityTags.LIST).performScrollToNode(hasTestTag(SecurityTags.card(SecurityTags.LEFT_OUT)))
        compose.onNodeWithText(res.getString(R.string.security_left_out_title)).assertExists()
        compose.onNodeWithText(res.getQuantityString(R.plurals.security_left_out_count, 2, "2")).assertExists()
        save("tab-end-$tag")
    }

    /** The group of seven accounts that share one password, three of which changed it. */
    private fun group(tag: String) {
        val context = pseudo(tag)
        val made = SecurityRig(SecurityAccounts.everything()).also { rig = it }
        val report = made.awaitReport()
        compose.setContent {
            InLocale(context) {
                val vm = viewModel { made.groupViewModel(report.reused[0].id) }
                GroupListScreen(vm, made.nav)
            }
        }
        compose.waitForIdle()
        val res = context.resources
        compose.onNodeWithText(res.getString(R.string.security_list_reused)).assertExists()
        compose.onNodeWithText(res.getQuantityString(R.plurals.security_group_reused, 7, "7")).assertExists()
        compose.onNodeWithText(res.getQuantityString(R.plurals.security_reused_progress, 7, "3", "7")).assertExists()
        compose.onAllNodesWithText(res.getString(R.string.security_change)).onFirst().assertExists()
        save("group-$tag")
    }
}
