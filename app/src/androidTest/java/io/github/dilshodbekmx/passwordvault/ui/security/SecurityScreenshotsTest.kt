package io.github.dilshodbekmx.passwordvault.ui.security

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.data.CheckProgress
import io.github.dilshodbekmx.passwordvault.fake.FakeSecurityReports
import io.github.dilshodbekmx.passwordvault.platform.AppDispatchers
import io.github.dilshodbekmx.passwordvault.settings.StyleChoice
import io.github.dilshodbekmx.passwordvault.settings.ThemeChoice
import io.github.dilshodbekmx.passwordvault.ui.security.SecurityAccounts.entry
import io.github.dilshodbekmx.passwordvault.ui.security.SecurityAccounts.id
import io.github.dilshodbekmx.passwordvault.ui.theme.PasswordVaultTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pictures of the Security tab for the user (build plan 7.1's note), on Android 17 with the debug
 * build: [SecurityShots] saves each into `/data/local/tmp/pv-shots-7.1/` on the device, and the
 * package's agent copies them to `/home/asus/pvwork/shots-7/` with names that start with "7.1-". The
 * states are those the user meets: every card at once (the master password on two accounts, one
 * password on seven accounts of which three changed already, another on three, two easy to guess, a
 * change not finished, two left out); the first check running; all clear; the lists behind the cards;
 * the three styles, light and dark.
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 37)
class SecurityScreenshotsTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val rigs = mutableListOf<SecurityRig>()

    @After
    fun tearDown() = rigs.forEach { it.close() }

    private fun rig(vault: Vault): SecurityRig = SecurityRig(vault).also { rigs += it }

    /** What the gallery shows: a screen in a style, light or dark. */
    private class Gallery(first: @Composable () -> Unit) {
        var style by mutableStateOf(StyleChoice.BANK_BLUE)
        var theme by mutableStateOf(ThemeChoice.LIGHT)
        var screen by mutableStateOf(first)
    }

    @Test
    fun theTabAndItsListsForTheUser() {
        val main = rig(SecurityAccounts.everything())
        val report = main.awaitReport()
        val clear = rig(Vault(entries = listOf(entry(1, "GitHub", "gH7#vQ2m!Lp9sW4z", "azizk"), entry(2, "Card PIN", "4821", checkIgnored = true))))
        clear.awaitReport()

        lateinit var tab: SecurityViewModel
        lateinit var checking: SecurityViewModel
        lateinit var allClear: SecurityViewModel
        lateinit var group: GroupListViewModel
        lateinit var weak: GroupListViewModel
        lateinit var leftOut: GroupListViewModel
        compose.runOnUiThread {
            tab = main.securityViewModel()
            checking = SecurityViewModel(FakeSecurityReports(), MutableStateFlow(CheckProgress(120, 480)), { false }, main.repository.state, main.lock, AppDispatchers.Default)
            allClear = clear.securityViewModel()
            group = main.groupViewModel(report.reused[0].id)
            weak = main.groupViewModel(GroupKind.WEAK_ID)
            leftOut = main.groupViewModel(GroupKind.LEFT_OUT_ID)
        }
        val gallery = Gallery { SecurityScreen(tab, main.nav) }
        compose.setContent { PasswordVaultTheme(gallery.style, gallery.theme) { gallery.screen() } }
        compose.waitForIdle()

        // The first visit: "Why this matters" open, the cards below it.
        SecurityShots.take(compose, "7.1-01-tab-first-visit")
        scrollTab(SecurityTags.card(SecurityTags.PENDING))
        SecurityShots.take(compose, "7.1-02-tab-cards")
        scrollTab(SecurityTags.card(SecurityTags.LEFT_OUT))
        SecurityShots.take(compose, "7.1-03-tab-end")

        // A later visit: the card folded (the first visit wrote securitySeenAt).
        compose.waitUntil(15_000) { main.repository.stored.securitySeenAt != null }
        lateinit var later: SecurityViewModel
        compose.runOnUiThread { later = main.securityViewModel() }
        show(gallery) { SecurityScreen(later, main.nav) }
        SecurityShots.take(compose, "7.1-04-tab-later-visit")

        show(gallery) { SecurityScreen(checking, main.nav) }
        SecurityShots.take(compose, "7.1-05-tab-checking")
        show(gallery) { SecurityScreen(allClear, clear.nav) }
        SecurityShots.take(compose, "7.1-06-tab-all-clear")

        show(gallery) { GroupListScreen(group, main.nav) }
        SecurityShots.take(compose, "7.1-07-group-same-password")
        show(gallery) { GroupListScreen(weak, main.nav) }
        compose.onNodeWithTag(SecurityTags.rowMore(id(14))).performClick()
        SecurityShots.take(compose, "7.1-08-weak-leave-out-menu")
        compose.onNodeWithText("Leave out of the security check").performClick()
        compose.waitUntil(15_000) { main.repository.stored.entries.first { it.id == id(14) }.checkIgnored }
        SecurityShots.take(compose, "7.1-09-weak-left-out-undo")
        show(gallery) { GroupListScreen(leftOut, main.nav) }
        SecurityShots.take(compose, "7.1-10-left-out")

        // The styles, light and dark.
        for ((style, theme, name) in listOf(
            Triple(StyleChoice.BANK_BLUE, ThemeChoice.DARK, "bank-blue-dark"),
            Triple(StyleChoice.EMERALD, ThemeChoice.LIGHT, "emerald-light"),
            Triple(StyleChoice.EMERALD, ThemeChoice.DARK, "emerald-dark"),
            Triple(StyleChoice.NIGHT_VAULT, ThemeChoice.DARK, "night-vault-dark"),
            Triple(StyleChoice.NIGHT_VAULT, ThemeChoice.LIGHT, "night-vault-light"),
        )) {
            compose.runOnUiThread {
                gallery.style = style
                gallery.theme = theme
            }
            show(gallery) { SecurityScreen(tab, main.nav) }
            SecurityShots.take(compose, "7.1-11-tab-$name")
            show(gallery) { GroupListScreen(group, main.nav) }
            SecurityShots.take(compose, "7.1-12-group-$name")
        }
        assertTrue(securityShell("ls ${SecurityShots.DIR}").contains("7.1-12-group-night-vault-light.png"))
    }

    private fun show(gallery: Gallery, screen: @Composable () -> Unit) {
        compose.runOnUiThread { gallery.screen = screen }
        compose.waitForIdle()
    }

    private fun scrollTab(tag: String) {
        compose.onNodeWithTag(SecurityTags.LIST).performScrollToNode(hasTestTag(tag))
        compose.waitForIdle()
    }
}
