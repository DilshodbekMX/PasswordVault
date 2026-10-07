package io.github.dilshodbekmx.passwordvault.nav

import io.github.dilshodbekmx.passwordvault.core.lock.flow.ChoosePinReason
import io.github.dilshodbekmx.passwordvault.core.lock.flow.CurrentVault
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockScreen
import io.github.dilshodbekmx.passwordvault.core.lock.flow.Notice
import io.github.dilshodbekmx.passwordvault.core.lock.flow.NoticeAnswer
import io.github.dilshodbekmx.passwordvault.core.model.Entry
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.data.VaultState
import io.github.dilshodbekmx.passwordvault.fake.FakeLockManager
import io.github.dilshodbekmx.passwordvault.fake.FakeVaultRepository
import io.github.dilshodbekmx.passwordvault.lock.CreateResult
import io.github.dilshodbekmx.passwordvault.lock.LockIntent
import io.github.dilshodbekmx.passwordvault.lock.LockReason
import io.github.dilshodbekmx.passwordvault.lock.LockUiState
import io.github.dilshodbekmx.passwordvault.lock.MpPurpose
import io.github.dilshodbekmx.passwordvault.lock.MpResult
import io.github.dilshodbekmx.passwordvault.nav.NavState.Top
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Build plan 2.5, [JVM]: `NavState` follows `FakeLockManager`'s states (architecture.md §5.4, ux.md
 * §4): the three back stacks, what a lock keeps and drops, the routes each stack accepts, the
 * bottom bar's tabs, and the saved form that a new process reads back.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NavStateTest {
    private val locked = LockUiState.Locked(LockScreen.Pad(digits = 0, status = null, fingerprintKey = false, opening = false, aside = null))
    private val e1 = Route.Entry("11111111-1111-4111-8111-111111111111")
    private val e2 = Route.Entry("22222222-2222-4222-8222-222222222222")

    private fun NavState.shown(): List<Route> = visible.toList()

    @Test
    fun followsFakeLockManagersStatesThroughSetupLockAndUnlock() = runTest(UnconfinedTestDispatcher()) {
        val vault = FakeVaultRepository()
        val lock = FakeLockManager(initial = LockUiState.Starting, vault = vault)
        val nav = NavState()
        backgroundScope.launch { nav.follow(lock) }

        // Process start: nothing is shown and nothing can be opened.
        assertEquals(Top.STARTING, nav.top)
        assertEquals(listOf(Route.Starting), nav.shown())
        assertFalse(nav.go(Route.Welcome))

        // No vault: setup, at Welcome.
        lock.state.value = LockUiState.NoVault(emptyList())
        assertEquals(Top.SETUP, nav.top)
        assertEquals(listOf(Route.Welcome), nav.shown())
        assertTrue(nav.go(Route.CreateMasterPassword))
        assertTrue(nav.go(Route.WriteDown))
        assertTrue(nav.go(Route.TypeAgain))
        assertFalse("an unlocked screen is never pushed onto setup", nav.go(e1))
        assertEquals(listOf(Route.Welcome, Route.CreateMasterPassword, Route.WriteDown, Route.TypeAgain), nav.shown())

        // "Type it again": the fake makes the vault and unlocks, with "Choose a PIN" (lock.md F1).
        assertEquals(CreateResult.Created(lock.vaultId), lock.createVault(charArrayOf('a'), charArrayOf('a'), null))
        assertEquals(Top.UNLOCKED, nav.top)
        assertEquals(listOf(Route.Passwords), nav.shown())
        assertEquals("setup is over once a vault exists", emptyList<Route>(), nav.stack(Top.SETUP))
        assertTrue(vault.state.value is VaultState.Unlocked)
        val unlocked = lock.state.value as LockUiState.Unlocked
        assertEquals(listOf(Notice.ChoosePin(1L, ChoosePinReason.NONE_HERE, needsMasterPassword = false)), unlocked.notices)
        lock.send(LockIntent.NoticeAnswered(1L, NoticeAnswer.CHOOSE_PIN))
        assertEquals(emptyList<Notice>(), (lock.state.value as LockUiState.Unlocked).notices)
        assertEquals("answering a notice is not a new top-level state", listOf(Route.Passwords), nav.shown())

        // Open an account and show its password in big letters.
        assertTrue(nav.go(e1))
        assertTrue(nav.go(Route.BigLetters(e1.id, "password")))
        assertFalse("the lock route is never pushed onto the unlocked stack", nav.go(Route.Locked))

        // A lock shows the lock route and keeps the route ids, but not "Show in big letters" (D-9d).
        lock.lock(LockReason.SCREEN_OFF)
        assertEquals(Top.LOCKED, nav.top)
        assertEquals(listOf(Route.Locked), nav.shown())
        assertEquals(listOf(Route.Passwords, e1), nav.stack(Top.UNLOCKED))
        assertEquals(VaultState.Locked, vault.state.value)
        assertEquals(listOf(LockReason.SCREEN_OFF), lock.lockReasons)

        // While locked, nothing of the unlocked app can be opened; Restore can (the "Forgot" sheet).
        assertFalse(nav.go(e2))
        assertFalse(nav.replace(e2))
        assertFalse(nav.tab(Route.Settings))
        assertTrue(nav.go(Route.Restore))
        assertEquals(listOf(Route.Locked, Route.Restore), nav.shown())
        assertTrue(nav.back())
        assertFalse("Back on the lock screen leaves the app", nav.back())

        // Unlock: the same screen comes back (ux.md §4).
        val right = lock.submitMasterPassword(charArrayOf('a'), MpPurpose.UNLOCK)
        assertTrue(right is MpResult.Right)
        assertEquals(Top.UNLOCKED, nav.top)
        assertEquals(listOf(Route.Passwords, e1), nav.shown())
        assertEquals(listOf(MpPurpose.UNLOCK), lock.purposes)

        // Files that cannot be read: the locked stack, ids still kept.
        lock.state.value = LockUiState.Unreadable(CurrentVault.Why.IO, emptyList())
        assertEquals(Top.LOCKED, nav.top)
        assertEquals(listOf(Route.Locked), nav.shown())
        assertEquals(listOf(Route.Passwords, e1), nav.stack(Top.UNLOCKED))

        // "Start with an empty app": setup again; the old vault's ids are dropped.
        lock.state.value = LockUiState.NoVault(emptyList())
        assertEquals(listOf(Route.Welcome), nav.shown())
        assertEquals(emptyList<Route>(), nav.stack(Top.UNLOCKED))

        // "Open my previous passwords" shows that vault's lock screen; Back returns to Welcome.
        assertTrue(nav.go(Route.CreateMasterPassword))
        lock.state.value = locked
        assertEquals(listOf(Route.Locked), nav.shown())
        assertEquals(emptyList<Route>(), nav.stack(Top.SETUP))
        lock.state.value = LockUiState.NoVault(emptyList())
        assertEquals(listOf(Route.Welcome), nav.shown())

        // The vault put aside opened: the unlocked stack starts at Passwords.
        lock.state.value = locked
        lock.state.value = LockUiState.Unlocked(emptyList(), null)
        assertEquals(listOf(Route.Passwords), nav.shown())
    }

    @Test
    fun aStateOfTheSameKindChangesNoStack() {
        val nav = NavState()
        nav.follow(locked)
        nav.go(Route.Restore)
        nav.follow(LockUiState.Locked(LockScreen.FingerprintOnly(status = null, pinReason = null, aside = null)))
        nav.follow(LockUiState.Unreadable(CurrentVault.Why.DAMAGED, emptyList()))
        assertEquals(listOf(Route.Locked, Route.Restore), nav.shown())

        nav.follow(LockUiState.Unlocked(listOf(Notice.BackupNow(7L)), null))
        nav.go(e1)
        nav.follow(LockUiState.Unlocked(emptyList(), null))
        assertEquals(listOf(Route.Passwords, e1), nav.shown())

        nav.follow(LockUiState.NoVault(emptyList()))
        nav.go(Route.Language)
        nav.follow(LockUiState.NoVault(emptyList()))
        assertEquals(listOf(Route.Welcome, Route.Language), nav.shown())
    }

    @Test
    fun aLockDropsBigLettersAndTheDialogsAndKeepsTheRest() {
        val nav = NavState()
        nav.follow(LockUiState.Unlocked(emptyList(), null))
        nav.tab(Route.Settings)
        nav.go(Route.Theme)
        nav.go(Route.Language)
        nav.go(Route.Export(2))
        nav.go(Route.BigLetters(e1.id, "field-0"))
        nav.follow(locked)
        assertEquals(listOf(Route.Passwords, Route.Settings, Route.Export(2)), nav.stack(Top.UNLOCKED))
        nav.follow(LockUiState.Unlocked(emptyList(), null))
        assertEquals(listOf(Route.Passwords, Route.Settings, Route.Export(2)), nav.shown())
    }

    @Test
    fun theBottomBarsTabsAndBack() {
        val nav = NavState()
        assertFalse("no tab before the app is unlocked", nav.tab(Route.Security))
        nav.follow(LockUiState.Unlocked(emptyList(), null))
        assertEquals(Route.Passwords, nav.tabRoot)
        assertTrue(nav.atTabRoot)

        assertTrue(nav.tab(Route.Security))
        assertEquals(listOf(Route.Passwords, Route.Security), nav.shown())
        assertEquals(Route.Security, nav.tabRoot)
        assertTrue(nav.atTabRoot)
        nav.go(Route.Group("g"))
        assertEquals(Route.Security, nav.tabRoot)
        assertFalse(nav.atTabRoot)

        assertTrue(nav.tab(Route.Settings))
        assertEquals(listOf(Route.Passwords, Route.Settings), nav.shown())
        assertTrue("Back from Settings goes to Passwords", nav.back())
        assertEquals(listOf(Route.Passwords), nav.shown())
        assertFalse("then Back leaves the app", nav.back())

        nav.go(e1)
        assertTrue(nav.tab(Route.Passwords))
        assertEquals(listOf(Route.Passwords), nav.shown())
        assertFalse("only the three roots are tabs", nav.tab(Route.Backup))
    }

    @Test
    fun replaceSwapsTheScreenShownNow() {
        val nav = NavState()
        nav.follow(LockUiState.NoVault(emptyList()))
        nav.go(Route.CreateMasterPassword)
        assertTrue(nav.replace(Route.WriteDown))
        assertEquals(listOf(Route.Welcome, Route.WriteDown), nav.shown())
        nav.back()
        assertTrue(nav.replace(Route.Restore))
        assertEquals(listOf(Route.Restore), nav.shown())
        assertFalse(nav.replace(Route.Passwords))
    }

    @Test
    fun eachRouteBelongsToItsStacks() {
        for (route in ALL_ROUTES) {
            val expected: Set<Top> = when (route) {
                Route.Starting -> emptySet()
                Route.Welcome, Route.CreateMasterPassword, Route.WriteDown, Route.TypeAgain -> setOf(Top.SETUP)
                Route.Restore -> setOf(Top.SETUP, Top.LOCKED)
                Route.Language -> setOf(Top.SETUP, Top.UNLOCKED)
                Route.Locked -> setOf(Top.LOCKED)
                else -> setOf(Top.UNLOCKED)
            }
            assertEquals(route.toString(), expected, Top.entries.filter { NavState.allowedIn(route, it) }.toSet())
        }
    }

    @Test
    fun aRoutesNameIsItsKeysName() {
        val names = ALL_ROUTES.map { it.name }
        assertEquals(names.distinct(), names)
        for (route in ALL_ROUTES) {
            assertTrue(route.toString(), Regex("[A-Z][A-Za-z]+").matches(route.name))
            assertEquals("pv.route.${route.name}", routeTag(route))
        }
        assertEquals("Entry", e1.name)
        assertEquals("Help", Route.Help(HelpPage.FORGOT_PIN).name)
    }

    @Test
    fun theSavedStacksComeBackInANewProcess() {
        val nav = NavState()
        nav.follow(LockUiState.Unlocked(emptyList(), null))
        nav.go(e1)
        nav.go(Route.Edit(null))
        nav.go(Route.Help(HelpPage.LOST_PHONE))
        nav.go(Route.Import(3))
        nav.go(Route.BigLetters(e1.id, "previous-1"))
        val text = nav.encode()

        val restored = NavState.decode(text)
        assertEquals(Top.STARTING, restored.top)
        assertEquals(nav.stack(Top.UNLOCKED), restored.stack(Top.UNLOCKED))

        // A new process starts locked: the ids come back after unlock, big letters never (D-9d).
        restored.follow(locked)
        restored.follow(LockUiState.Unlocked(emptyList(), null))
        assertEquals(listOf(Route.Passwords, e1, Route.Edit(null), Route.Help(HelpPage.LOST_PHONE), Route.Import(3)), restored.shown())
    }

    @Test
    fun aSetupStackSavedBeforeTheVaultExistedComesBackInSetup() {
        val nav = NavState()
        nav.follow(LockUiState.NoVault(emptyList()))
        nav.go(Route.CreateMasterPassword)
        nav.go(Route.WriteDown)
        val restored = NavState.decode(nav.encode())
        restored.follow(LockUiState.NoVault(emptyList()))
        assertEquals(listOf(Route.Welcome, Route.CreateMasterPassword, Route.WriteDown), restored.shown())
    }

    @Test
    fun savedStateHoldsRouteKeysOnly() {
        val nav = NavState()
        nav.follow(LockUiState.Unlocked(emptyList(), null))
        nav.go(Route.GuidedChange(e2.id))
        val text = nav.encode()
        assertTrue(text, text.contains(e2.id))
        assertEquals(
            "every value in the saved form is a route type, an id, a number or an enum",
            emptyList<String>(),
            Regex("\"([^\"]*)\"").findAll(text).map { it.groupValues[1] }
                .filterNot { it in setOf("setup", "locked", "unlocked", "type", "id", "entryId") }
                .filterNot { it.startsWith("io.github.dilshodbekmx.passwordvault.nav.Route.") || it == e2.id }
                .toList(),
        )
    }

    @Test
    fun anUnreadableSavedFormGivesEmptyStacks() {
        for (text in listOf(null, "", "{", "{\"unlocked\":[{\"type\":\"no.such.Route\"}]}")) {
            val nav = NavState.decode(text)
            for (top in listOf(Top.SETUP, Top.LOCKED, Top.UNLOCKED)) assertEquals(text.toString(), emptyList<Route>(), nav.stack(top))
            nav.follow(LockUiState.Unlocked(emptyList(), null))
            assertEquals(listOf(Route.Passwords), nav.shown())
        }
    }

    @Test
    fun theFakeLockAnswersAsScripted() = runTest(UnconfinedTestDispatcher()) {
        val vault = FakeVaultRepository(clock = { 1_000L })
        val lock = FakeLockManager(vault = vault)
        val nav = NavState()
        backgroundScope.launch { nav.follow(lock) }
        assertEquals(listOf(Route.Welcome), nav.shown())

        // Typings that differ: nothing changes.
        assertEquals(CreateResult.Mismatch, lock.createVault(charArrayOf('a'), charArrayOf('b'), null))
        assertEquals(Top.SETUP, nav.top)

        // A restore opens the vault it was given.
        val restoredVault = Vault(entries = listOf(Entry(id = e1.id, title = "t", createdAt = 1L, updatedAt = 1L)))
        lock.createVault(charArrayOf('a'), charArrayOf('a'), restoredVault)
        assertSame(restoredVault, (vault.state.value as VaultState.Unlocked).vault)
        assertTrue((lock.state.value as LockUiState.Unlocked).proof != null)

        // A wrong master password, then a right one.
        lock.lock(LockReason.LOCK_NOW)
        lock.mpResults += MpResult.Wrong
        assertEquals(MpResult.Wrong, lock.submitMasterPassword(charArrayOf('x'), MpPurpose.UNLOCK))
        assertEquals(Top.LOCKED, nav.top)
        assertTrue(lock.submitMasterPassword(charArrayOf('a'), MpPurpose.UNLOCK) is MpResult.Right)
        assertEquals(Top.UNLOCKED, nav.top)

        // A master password for a prompt inside the open app does not change the state.
        lock.submitMasterPassword(charArrayOf('a'), MpPurpose.EXPORT)
        assertEquals(Top.UNLOCKED, nav.top)
        assertNull(lock.pinProblem("582913".encodeToByteArray()))
        assertEquals(listOf(LockReason.LOCK_NOW), lock.lockReasons)
    }

    private companion object {
        /** One key of every route type (architecture.md §3.6). */
        val ALL_ROUTES: List<Route> = listOf(
            Route.Starting,
            Route.Welcome, Route.CreateMasterPassword, Route.WriteDown, Route.TypeAgain,
            Route.CreatePin, Route.ConfirmPin, Route.FingerprintOffer, Route.BackupsExplained,
            Route.Locked,
            Route.Passwords, Route.RecentlyDeleted,
            Route.Entry("e"), Route.BigLetters("e", "password"),
            Route.Edit(null),
            Route.Settings, Route.AppLock, Route.ChangePin, Route.Theme,
            Route.ChangeMasterPassword, Route.NewMasterPassword,
            Route.Language,
            Route.Help(HelpPage.PROTECTION), Route.Licences,
            Route.Backup, Route.Restore, Route.OldPasswords,
            Route.Export(0),
            Route.Import(0), Route.ColumnMapping,
            Route.Security, Route.Group("g"),
            Route.GuidedChange("e"),
        )
    }
}
