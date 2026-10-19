package io.github.dilshodbekmx.passwordvault.ui.security

import androidx.activity.ComponentActivity
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.core.lock.flow.Notice
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.data.CheckProgress
import io.github.dilshodbekmx.passwordvault.fake.FakeSecurityReports
import io.github.dilshodbekmx.passwordvault.lock.LockUiState
import io.github.dilshodbekmx.passwordvault.nav.Route
import io.github.dilshodbekmx.passwordvault.platform.AppDispatchers
import io.github.dilshodbekmx.passwordvault.settings.ThemeChoice
import io.github.dilshodbekmx.passwordvault.ui.security.SecurityAccounts.entry
import io.github.dilshodbekmx.passwordvault.ui.security.SecurityAccounts.id
import io.github.dilshodbekmx.passwordvault.ui.theme.PasswordVaultTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Build plan 7.1 on a device: the Security tab and the lists behind its cards (ux.md §5.16), drawn by
 * the app's check over a vault in memory. Every card says what it counts and its button opens its
 * list; the visit clears the dot with one write of `securitySeenAt`, never under a notice; the dot is
 * Material's small badge, and a tab like the bottom bar's is read "Security, new results" through its
 * state; a group lists its accounts email first, and its rows
 * open the account, the guided change, and "Leave out of the security check" with Undo.
 */
@RunWith(AndroidJUnit4::class)
class SecurityScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var rig: SecurityRig? = null

    @After
    fun closeRig() {
        rig?.close()
    }

    private fun rigFor(vault: Vault): SecurityRig = SecurityRig(vault).also { rig = it }

    private fun showTab(rig: SecurityRig) {
        lateinit var vm: SecurityViewModel
        compose.runOnUiThread { vm = rig.securityViewModel() }
        compose.setContent { PasswordVaultTheme(ThemeChoice.LIGHT) { SecurityScreen(vm, rig.nav) } }
        compose.waitForIdle()
    }

    private fun showList(rig: SecurityRig, listId: String) {
        lateinit var vm: GroupListViewModel
        compose.runOnUiThread { vm = rig.groupViewModel(listId) }
        compose.setContent { PasswordVaultTheme(ThemeChoice.LIGHT) { GroupListScreen(vm, rig.nav) } }
        compose.waitForIdle()
    }

    private fun scrollTo(tag: String) {
        compose.onNodeWithTag(SecurityTags.LIST).performScrollToNode(hasTestTag(tag))
        compose.waitForIdle()
    }

    private fun count(tag: String): Int = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().size

    private fun waitUntil(what: String, condition: () -> Boolean) {
        try {
            compose.waitUntil(WAIT_MS, condition)
        } catch (e: ComposeTimeoutException) {
            throw AssertionError("timed out waiting for $what", e)
        }
    }

    @Test
    fun everyCardSaysWhatItCountsAndItsButtonOpensItsList() {
        val rig = rigFor(SecurityAccounts.everything())
        val report = rig.awaitReport()
        showTab(rig)

        scrollTo(SecurityTags.card(SecurityTags.MASTER))
        compose.onNodeWithText("Master password used elsewhere").assertIsDisplayed()
        compose.onNodeWithText("Your master password is also the password for 2\u00A0accounts (Gmail, Facebook).", substring = true).assertIsDisplayed()
        compose.onNodeWithTag(SecurityTags.cardButton(SecurityTags.MASTER)).performClick()

        scrollTo(SecurityTags.card(SecurityTags.REUSED))
        compose.onNodeWithText("7\u00A0accounts use the same password.").assertIsDisplayed()
        compose.onNodeWithText("3\u00A0changed, 7\u00A0to go").assertIsDisplayed()
        compose.onNodeWithTag(SecurityTags.cardButton(SecurityTags.REUSED)).performClick()
        scrollTo(SecurityTags.otherGroup(report.reused[1].id))
        compose.onNodeWithText("3\u00A0accounts share another password").assertIsDisplayed().performClick()

        scrollTo(SecurityTags.card(SecurityTags.WEAK))
        compose.onNodeWithText("Easy to guess").assertIsDisplayed()
        compose.onNodeWithTag(SecurityTags.cardButton(SecurityTags.WEAK)).performClick()

        scrollTo(SecurityTags.card(SecurityTags.PENDING))
        compose.onNodeWithText("1\u00A0password change is waiting for your answer (Kapitalbank).").assertIsDisplayed()
        compose.onNodeWithTag(SecurityTags.cardButton(SecurityTags.PENDING)).performClick()

        scrollTo(SecurityTags.card(SecurityTags.LEFT_OUT))
        compose.onNodeWithText("2\u00A0accounts are left out of the check.").assertIsDisplayed()
        compose.onNodeWithTag(SecurityTags.cardButton(SecurityTags.LEFT_OUT)).performClick()

        compose.waitForIdle()
        assertEquals(
            listOf(
                Route.ChangeMasterPassword,
                Route.Group(report.reused[0].id),
                Route.Group(report.reused[1].id),
                Route.Group(GroupKind.WEAK_ID),
                Route.Entry(id(16)),
                Route.Group(GroupKind.LEFT_OUT_ID),
            ),
            rig.nav.routes,
        )
    }

    @Test
    fun theVisitClearsTheDotWithOneWrite() {
        val rig = rigFor(SecurityAccounts.everything())
        rig.awaitReport()
        assertTrue("never seen: the dot is on", rig.runner.unseen.value)
        val saves = rig.repository.saves
        showTab(rig)
        waitUntil("securitySeenAt") { rig.repository.stored.securitySeenAt != null }
        waitUntil("the dot to go") { !rig.runner.unseen.value }
        Thread.sleep(500)
        compose.waitForIdle()
        assertEquals("one write", saves + 1, rig.repository.saves)
    }

    @Test
    fun underANoticeTheVisitWaits() {
        val rig = rigFor(SecurityAccounts.everything())
        rig.awaitReport()
        rig.lock.value = LockUiState.Unlocked(listOf(Notice.OlderCopy(1L, System.currentTimeMillis() - 86_400_000L)), null)
        showTab(rig)
        Thread.sleep(1_500)
        compose.waitForIdle()
        assertNull("nothing is written before the older-copy notice is answered", rig.repository.stored.securitySeenAt)
        assertTrue(rig.runner.unseen.value)

        rig.lock.value = LockUiState.Unlocked(emptyList(), null)
        waitUntil("securitySeenAt") { rig.repository.stored.securitySeenAt != null }
    }

    @Test
    fun theDotIsDrawnOnlyWhileThereIsSomethingNew() {
        val shown = MutableStateFlow(true)
        compose.setContent {
            PasswordVaultTheme(ThemeChoice.LIGHT) {
                val on by shown.collectAsState()
                SecurityDot(on)
            }
        }
        compose.onNodeWithTag(SecurityTags.BADGE).assertIsDisplayed()
        shown.value = false
        compose.waitForIdle()
        assertEquals(0, count(SecurityTags.BADGE))
    }

    /**
     * The bottom bar's Security item as `UnlockedScaffold` draws it, a label and the dot in a
     * `BadgedBox` in the icon slot, with the state [securityTabState] gives (the shell's part of the
     * interface request): the tab's state carries "New results". (material3 clears the icon slot's
     * semantics when the item has a label, so nothing drawn there is read; in the real app on API 37
     * the Security tab's node had only its "Security" text below it while the dot showed.)
     */
    @Test
    fun aTabLikeTheBottomBarsIsReadSecurityNewResultsWhileTheDotShows() {
        val shown = MutableStateFlow(true)
        compose.setContent {
            PasswordVaultTheme(ThemeChoice.LIGHT) {
                val on by shown.collectAsState()
                val state = newResultsState(on)
                NavigationBar {
                    NavigationBarItem(
                        selected = false,
                        onClick = {},
                        icon = { BadgedBox(badge = { SecurityDot(on) }) { Icon(painterResource(R.drawable.ic_shell_security), contentDescription = null) } },
                        label = { Text("Security") },
                        modifier = Modifier.testTag(TAB).semantics { if (state != null) stateDescription = state },
                    )
                }
            }
        }
        compose.onNodeWithTag(TAB).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "New results"))
        compose.onNodeWithTag(TAB).assert(hasText("Security"))
        shown.value = false
        compose.waitForIdle()
        compose.onNodeWithTag(TAB).assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
    }

    @Test
    fun whyThisMattersIsOpenOnTheFirstVisitAndFolds() {
        val rig = rigFor(SecurityAccounts.everything())
        rig.awaitReport()
        showTab(rig)
        compose.onNodeWithTag(SecurityTags.WHY_TEXT).assertIsDisplayed()
        compose.onNodeWithContentDescription("Hide why this matters").performClick()
        compose.waitForIdle()
        assertEquals(0, count(SecurityTags.WHY_TEXT))
        compose.onNodeWithContentDescription("Show why this matters").performClick()
        compose.onNodeWithTag(SecurityTags.WHY_TEXT).assertIsDisplayed()
    }

    @Test
    fun whileTheFirstCheckRunsTheTabSaysHowFarItIs() {
        val rig = rigFor(SecurityAccounts.everything())
        lateinit var vm: SecurityViewModel
        compose.runOnUiThread {
            vm = SecurityViewModel(FakeSecurityReports(), MutableStateFlow(CheckProgress(120, 480)), { false }, rig.repository.state, rig.lock, AppDispatchers.Default)
        }
        compose.setContent { PasswordVaultTheme(ThemeChoice.LIGHT) { SecurityScreen(vm, rig.nav) } }
        compose.onNodeWithText("Checking 120 of 480…").assertIsDisplayed()
    }

    @Test
    fun allClearStillCountsWhatIsLeftOut() {
        val clear = rigFor(Vault(entries = listOf(entry(1, "GitHub", "gH7#vQ2m!Lp9sW4z"), entry(2, "Card PIN", "4821", checkIgnored = true))))
        clear.awaitReport()
        showTab(clear)
        compose.onNodeWithText("All your passwords are different and hard to guess. Well done.").assertIsDisplayed()
        compose.onNodeWithText("1\u00A0account is left out of the check.").assertIsDisplayed()
    }

    @Test
    fun noAccountsYet() {
        val none = rigFor(Vault())
        none.awaitReport()
        showTab(none)
        compose.onNodeWithText("Add some accounts first. The check runs on this phone only.").assertIsDisplayed()
    }

    // ---- The lists behind the cards ----

    @Test
    fun aGroupListsItsAccountsEmailFirstAndEachRowOpensTheAccountOrTheChange() {
        val rig = rigFor(SecurityAccounts.everything())
        val report = rig.awaitReport()
        showList(rig, report.reused[0].id)
        compose.onNodeWithText("These 7\u00A0accounts share one password").assertIsDisplayed()
        compose.onNodeWithText("3\u00A0changed, 7\u00A0to go").assertIsDisplayed()
        // Gmail (email and a favourite), the other two email accounts A to Z, then the rest A to Z.
        val tops = listOf(1, 6, 3, 2, 4, 5, 7).map { n ->
            compose.onNodeWithTag(SecurityTags.GROUP_LIST).performScrollToNode(hasTestTag(SecurityTags.row(id(n))))
            compose.onNodeWithTag(SecurityTags.row(id(n))).fetchSemanticsNode().positionInRoot.y
        }
        assertEquals("Gmail, Oʻzbekiston pochtasi, Yandex Pochta, Facebook, Instagram, Telegram Web, Uzum Market", tops.sorted(), tops)

        compose.onNodeWithTag(SecurityTags.GROUP_LIST).performScrollToNode(hasTestTag(SecurityTags.row(id(1))))
        compose.onNodeWithContentDescription("Change the password of Gmail").assertIsDisplayed()
        compose.onNodeWithTag(SecurityTags.rowAction(id(1))).performClick()
        compose.onNodeWithTag(SecurityTags.row(id(1))).performClick()
        compose.waitForIdle()
        assertEquals(listOf(Route.GuidedChange(id(1)), Route.Entry(id(1))), rig.nav.routes)
    }

    @Test
    fun leaveOutFromARowsMenuWithUndo() {
        val rig = rigFor(SecurityAccounts.everything())
        rig.awaitReport()
        showList(rig, GroupKind.WEAK_ID)
        compose.onNodeWithText("Uy Wi-Fi").assertIsDisplayed()
        compose.onNodeWithTag(SecurityTags.rowMore(id(14))).performClick()
        compose.onNodeWithText("Leave out of the security check").performClick()
        waitUntil("the account left out") { rig.repository.stored.entries.first { it.id == id(14) }.checkIgnored }
        compose.onNodeWithText("Uy Wi-Fi is left out of the security check.").assertIsDisplayed()
        waitUntil("the row to go") { count(SecurityTags.row(id(14))) == 0 }
        compose.onNodeWithText("Undo").performClick()
        waitUntil("the account checked again") { !rig.repository.stored.entries.first { it.id == id(14) }.checkIgnored }
        waitUntil("the row to come back") { count(SecurityTags.row(id(14))) == 1 }
    }

    @Test
    fun checkItAgainInTheListOfAccountsLeftOut() {
        val rig = rigFor(SecurityAccounts.everything())
        rig.awaitReport()
        showList(rig, GroupKind.LEFT_OUT_ID)
        compose.onNodeWithText("These 2\u00A0accounts are left out of the check").assertIsDisplayed()
        compose.onNodeWithContentDescription("Check Card PIN again").performClick()
        waitUntil("Card PIN checked again") { !rig.repository.stored.entries.first { it.id == id(17) }.checkIgnored }
        compose.onNodeWithText("Card PIN is checked again.").assertIsDisplayed()
        waitUntil("Card PIN to leave the list") { count(SecurityTags.row(id(17))) == 0 }
        assertTrue("Door code stays left out", rig.repository.stored.entries.first { it.id == id(18) }.checkIgnored)
    }

    @Test
    fun aGroupThatNoLongerExistsSaysSo() {
        val rig = rigFor(SecurityAccounts.everything())
        rig.awaitReport()
        showList(rig, id(99))
        compose.onNodeWithText("These accounts no longer share a password.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitForIdle()
        assertEquals(1, rig.nav.backs)
    }

    private companion object {
        const val WAIT_MS = 15_000L
        const val TAB = "test.tab.Security"
    }
}
