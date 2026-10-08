package io.github.dilshodbekmx.passwordvault.ui.list

import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.snapshots.Snapshot
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.clipboard.ClipStatus
import io.github.dilshodbekmx.passwordvault.clipboard.SecretClipboard
import io.github.dilshodbekmx.passwordvault.core.lock.record.Shortcut
import io.github.dilshodbekmx.passwordvault.core.model.CustomField
import io.github.dilshodbekmx.passwordvault.core.model.Entry
import io.github.dilshodbekmx.passwordvault.core.model.FileRef
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.core.search.SearchIndex
import io.github.dilshodbekmx.passwordvault.core.search.SearchOutcome
import io.github.dilshodbekmx.passwordvault.data.SaveProblem
import io.github.dilshodbekmx.passwordvault.data.VaultState
import io.github.dilshodbekmx.passwordvault.fake.FakeAppSettings
import io.github.dilshodbekmx.passwordvault.fake.FakeDocumentIo
import io.github.dilshodbekmx.passwordvault.fake.FakeListModelSource
import io.github.dilshodbekmx.passwordvault.fake.FakeLockManager
import io.github.dilshodbekmx.passwordvault.fake.FakeSecretClipboard
import io.github.dilshodbekmx.passwordvault.fake.FakeSession
import io.github.dilshodbekmx.passwordvault.fake.FakeVaultRepository
import io.github.dilshodbekmx.passwordvault.i18n.Collation
import io.github.dilshodbekmx.passwordvault.i18n.UiText
import io.github.dilshodbekmx.passwordvault.lock.LockIntent
import io.github.dilshodbekmx.passwordvault.lock.LockStatus
import io.github.dilshodbekmx.passwordvault.lock.LockUiState
import io.github.dilshodbekmx.passwordvault.lock.MpPurpose
import io.github.dilshodbekmx.passwordvault.lock.MpResult
import io.github.dilshodbekmx.passwordvault.lock.PendingMasterPassword
import io.github.dilshodbekmx.passwordvault.platform.AppDispatchers
import io.github.dilshodbekmx.passwordvault.testing.FakeClock
import io.github.dilshodbekmx.passwordvault.ui.edit.NewAccountName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale

/**
 * Build plan 3.5, [JVM]: the Passwords screen's ViewModel against the fakes of `APP/fake/`, with a
 * simple [Collation] (ux.md §5.11; search.md §7; architecture.md §5.3 to §5.5): the list keeps the
 * collation's order and letter headers, favourites first; search answers the latest query only,
 * with the wrong-layout retry; at most two banners in priority order; copies go to
 * `SecretClipboard` with the right flag and no password ever reaches the state; the row's edits;
 * "Type it now"; 'Add "gmal" as a new account' hands the query over in memory only; and everything
 * is dropped when the vault locks.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PasswordsViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    /**
     * The fakes' own coroutines and the tests' collectors. Not `backgroundScope`: `advanceUntilIdle`
     * stops when only background work is left, so a collector there may not have run yet.
     */
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    @Before
    fun setMain() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() {
        scope.cancel()
        Dispatchers.resetMain()
    }

    private val now = 1_791_244_800_000L
    private val day = 86_400_000L

    /**
     * A simple collation in the shape of Uzbek's (i18n.md §2.7): A to Z, then the letters Oʻ, Gʻ,
     * Sh and Ch after Z with headers of their own, and "#" before A for titles that start with a
     * digit or a sign. Plain A-to-Z sorting would put "Shahar" under S and "Oʻzbekiston" under O.
     */
    private object Uzbekish : Collation {
        private val digraphs = listOf("oʻ", "gʻ", "sh", "ch")

        private fun letter(title: String): String {
            val t = title.lowercase(Locale.ROOT)
            return digraphs.firstOrNull { t.startsWith(it) } ?: t.take(1)
        }

        private fun rank(letter: String): Int {
            val d = digraphs.indexOf(letter)
            if (d >= 0) return 26 + d
            val c = letter.firstOrNull() ?: return -1
            return if (c in 'a'..'z') c - 'a' else -1
        }

        override fun compare(a: String, b: String): Int {
            val byLetter = rank(letter(a)).compareTo(rank(letter(b)))
            return if (byLetter != 0) byLetter else a.lowercase(Locale.ROOT).compareTo(b.lowercase(Locale.ROOT))
        }

        override fun header(title: String): String {
            val l = letter(title)
            return when {
                rank(l) < 0 -> "#"
                l.length > 1 -> l.replaceFirstChar { it.uppercase(Locale.ROOT) }
                else -> l.uppercase(Locale.ROOT)
            }
        }
    }

    private fun entry(
        id: String,
        title: String,
        username: String = "",
        password: String = "pw-$id",
        urls: List<String> = emptyList(),
        favourite: Boolean = false,
        changePending: Boolean = false,
        deletedAt: Long? = null,
        notes: String = "",
        fields: List<CustomField> = emptyList(),
    ) = Entry(
        id = id,
        title = title,
        username = username,
        password = password,
        notes = notes,
        urls = urls,
        fields = fields,
        favourite = favourite,
        changePending = changePending,
        createdAt = now - 30 * day,
        updatedAt = now - 30 * day,
        deletedAt = deletedAt,
        pendingOldPassword = if (changePending) "old-$id" else null,
    )

    /** The app's parts as fakes, and a list that follows the vault as `ListModelProvider` does: null while locked. */
    private inner class Rig(scope: CoroutineScope, vault: Vault, private val collation: Collation = Uzbekish) {
        val clock = FakeClock(wallMs = now)
        val repository = FakeVaultRepository(vault, clock = { clock.wallMs() })
        val lists = FakeListModelSource()
        val lock = FakeLockManager(LockUiState.Unlocked(emptyList(), null), repository)
        val settings = FakeAppSettings()
        var clipboard: SecretClipboard = FakeSecretClipboard()
        val files = FakeDocumentIo()
        val session = FakeSession(scope, lock, FakeSecretClipboard(), repository)

        init {
            scope.launch {
                repository.state.collect { state ->
                    lists.model.value = (state as? VaultState.Unlocked)?.let { FakeListModelSource.build(it.vault.entries, collation) }
                }
            }
        }

        fun viewModel(searcher: suspend (SearchIndex, String) -> SearchOutcome = { index, text -> index.searchOrRetry(text) }) =
            PasswordsViewModel(repository, lists, lock, settings, clipboard, session, files, clock, AppDispatchers.single(dispatcher), searcher)
    }

    private fun rig(vararg entries: Entry, vault: Vault = Vault(entries = entries.toList())) = Rig(scope, vault)

    /** Types [text] into the search field as the keyboard would, and lets Compose's snapshot system tell the ViewModel. */
    private fun PasswordsViewModel.type(text: String) {
        query.setTextAndPlaceCursorAtEnd(text)
        Snapshot.sendApplyNotifications()
    }

    private val PasswordsViewModel.body: ListBody get() = state.value.body

    private fun ListBody.titles(): List<String> = when (this) {
        is ListBody.Browse -> sections.flatMap { s -> s.rows.map { it.title } }
        is ListBody.Results -> rows.map { it.title }
        else -> emptyList()
    }

    // ---- The list ----

    @Test
    fun theListKeepsTheCollationsOrderAndHeadersWithFavouritesFirst() = runTest(dispatcher) {
        val rig = rig(
            entry("sh", "Shahar", username = "dilshod"),
            entry("ap", "Apple", urls = listOf("https://www.apple.com/account"), favourite = true),
            entry("oz", "Oʻzbekiston pochtasi", favourite = true),
            entry("ze", "Zebra"),
            entry("ol", "olma"),
            entry("nu", "123 Bank"),
            entry("ch", "Chorsu"),
            entry("del", "Deleted", deletedAt = now - day),
        )
        val vm = rig.viewModel()
        advanceUntilIdle()

        val body = vm.body as ListBody.Browse
        assertEquals(listOf("#", "A", "O", "Z", "Oʻ", "Sh", "Ch"), body.sections.map { it.header })
        assertEquals(listOf("123 Bank", "Apple", "olma", "Zebra", "Oʻzbekiston pochtasi", "Shahar", "Chorsu"), body.titles())
        assertEquals("deleted accounts are not counted", 7, body.total)
        assertEquals("favourites first, in the collation's order", listOf("Apple", "Oʻzbekiston pochtasi"), body.favourites.map { it.title })
        assertEquals(0, body.moreFavourites)
        assertEquals("Recently deleted (1)", 1, vm.state.value.deletedCount)

        // The second line: the username, else the first website's host.
        val rows = body.sections.flatMap { it.rows }.associateBy { it.id }
        assertEquals("dilshod", rows.getValue("sh").detail)
        assertEquals("apple.com", rows.getValue("ap").detail)
        assertEquals("", rows.getValue("ze").detail)
        assertEquals("https://www.apple.com/account", rows.getValue("ap").website)
        assertEquals("O", rows.getValue("oz").initial)
        assertEquals("1", rows.getValue("nu").initial)
    }

    @Test
    fun tenFavouritesThenShowAll() = runTest(dispatcher) {
        val favourites = (1..12).map { entry("f$it", "Fav %02d".format(Locale.ROOT, it), favourite = true) }
        val rig = rig(*favourites.toTypedArray())
        val vm = rig.viewModel()
        advanceUntilIdle()

        val cut = vm.body as ListBody.Browse
        assertEquals((1..10).map { "Fav %02d".format(Locale.ROOT, it) }, cut.favourites.map { it.title })
        assertEquals(2, cut.moreFavourites)
        assertEquals("all accounts still list all twelve", 12, cut.total)

        vm.showAllFavourites()
        advanceUntilIdle()
        val all = vm.body as ListBody.Browse
        assertEquals(12, all.favourites.size)
        assertEquals(0, all.moreFavourites)
    }

    @Test
    fun emptyVaultLockedVaultAndRecentlyDeletedOnly() = runTest(dispatcher) {
        val rig = rig(entry("del", "Deleted", deletedAt = now - day))
        val vm = rig.viewModel()
        advanceUntilIdle()
        assertSame(ListBody.Empty, vm.body)
        assertEquals("the Recently deleted row shows below the empty state", 1, vm.state.value.deletedCount)

        rig.repository.close()
        advanceUntilIdle()
        assertSame(ListBody.Loading, vm.body)
        assertEquals(0, vm.state.value.deletedCount)
        assertEquals(emptyList<ListBanner>(), vm.state.value.banners)
    }

    // ---- Search ----

    @Test
    fun searchShowsRankedResultsWithoutTheFavourites() = runTest(dispatcher) {
        val rig = rig(
            entry("uz", "Uzum Bank", favourite = true),
            entry("bc", "Bank card"),
            entry("kb", "Kapitalbank"),
            entry("gm", "Gmail", username = "bank.clerk@gmail.com"),
            entry("tg", "Telegram"),
        )
        val vm = rig.viewModel()
        advanceUntilIdle()

        vm.type("bank")
        advanceUntilIdle()
        val results = vm.body as ListBody.Results
        assertEquals("bank", results.typed)
        assertEquals("bank", results.searched)
        assertFalse(results.retried)
        // search.md §2: the start of the title, then a later word, then inside a word, then the user name.
        assertEquals(listOf("Bank card", "Uzum Bank", "Kapitalbank", "Gmail"), results.titles())

        vm.type("   ")
        advanceUntilIdle()
        assertTrue("a query of spaces shows the list", vm.body is ListBody.Browse)

        vm.type("bank")
        advanceUntilIdle()
        vm.clearQuery()
        Snapshot.sendApplyNotifications()
        advanceUntilIdle()
        assertTrue(vm.body is ListBody.Browse)
        assertEquals("", vm.query.text.toString())
    }

    @Test
    fun aQueryTypedOnTheWrongLayoutShowsTheRetriedQuery() = runTest(dispatcher) {
        val rig = rig(entry("in", "Instagram"), entry("pr", "Привет банк"), entry("gm", "Gmail"))
        val vm = rig.viewModel()
        advanceUntilIdle()

        vm.type("штые")
        advanceUntilIdle()
        val latin = vm.body as ListBody.Results
        assertEquals("штые", latin.typed)
        assertEquals("inst", latin.searched)
        assertTrue(latin.retried)
        assertEquals(listOf("Instagram"), latin.titles())

        vm.type("ghbdtn")
        advanceUntilIdle()
        val cyrillic = vm.body as ListBody.Results
        assertEquals("привет", cyrillic.searched)
        assertEquals(listOf("Привет банк"), cyrillic.titles())

        vm.type("gmal")
        advanceUntilIdle()
        assertEquals(ListBody.NoResults("gmal"), vm.body)
    }

    @Test
    fun theLatestQueryWinsWhenAnOlderSearchIsSlower() = runTest(dispatcher) {
        val rig = rig(entry("ab", "Abc"), entry("ax", "Axe"), entry("zz", "Zzz"))
        val started = mutableListOf<String>()
        val finished = mutableListOf<String>()
        // "a" takes a second, "ab" 10 ms: without "latest query wins", "a"'s results would arrive last.
        val vm = rig.viewModel { index, text ->
            started += text
            delay(if (text == "a") 1_000L else 10L)
            finished += text
            index.searchOrRetry(text)
        }
        advanceUntilIdle()
        val seen = mutableListOf<ListBody>()
        scope.launch { vm.state.collect { seen += it.body } }

        vm.type("a")
        runCurrent()
        advanceTimeBy(100L)
        assertEquals(listOf("a"), started)
        vm.type("ab")
        advanceUntilIdle()

        assertEquals(listOf("a", "ab"), started)
        assertEquals("the older, slower search was cancelled", listOf("ab"), finished)
        val results = vm.body as ListBody.Results
        assertEquals("ab", results.typed)
        assertEquals(listOf("Abc"), results.titles())
        assertTrue("the collector saw the results of \"ab\"", seen.any { it is ListBody.Results && it.typed == "ab" })
        assertTrue("no state ever showed the results of \"a\"", seen.none { it is ListBody.Results && it.typed == "a" })
    }

    @Test
    fun aNewListIsSearchedAgainWithTheSameQuery() = runTest(dispatcher) {
        val rig = rig(entry("gm", "Gmail"), entry("tg", "Telegram"))
        val vm = rig.viewModel()
        advanceUntilIdle()
        vm.type("gmail")
        advanceUntilIdle()
        assertEquals(listOf("Gmail"), vm.body.titles())

        rig.repository.open(Vault(entries = listOf(entry("gm", "Gmail"), entry("g2", "Gmail work"))))
        advanceUntilIdle()
        assertEquals(listOf("Gmail", "Gmail work"), vm.body.titles())
    }

    // ---- Banners ----

    @Test
    fun atMostTwoBannersInPriorityOrderFromTheVaultTheLockAndTheSettings() = runTest(dispatcher) {
        val vault = Vault(
            entries = listOf(entry("gm", "Gmail", changePending = true), entry("tg", "Telegram")),
            plainExports = listOf(FileRef("content://docs/1", "passwords-2026-10-05.csv", now - 3_600_000L)),
        )
        val rig = rig(vault = vault)
        rig.lock.status.value = LockStatus.NONE.copy(practiceOverdueSinceMs = now - 40 * day)
        val vm = rig.viewModel()
        advanceUntilIdle()

        val export = ListBanner.UnprotectedExport("content://docs/1", "passwords-2026-10-05.csv", now - 3_600_000L)
        assertEquals(listOf(ListBanner.PracticeOverdue(now - 40 * day), export), vm.state.value.banners)

        // "I deleted it myself": the export's note goes, and the next banner moves up.
        vm.exportDeletedMyself("content://docs/1")
        advanceUntilIdle()
        assertEquals(emptyList<FileRef>(), rig.repository.stored.plainExports)
        assertEquals("the permission on it is given up", listOf("content://docs/1"), rig.files.released)
        assertEquals(listOf(ListBanner.PracticeOverdue(now - 40 * day), ListBanner.BackupDue), vm.state.value.banners)

        // Practice typed: the lock's banner goes.
        rig.lock.status.value = LockStatus.NONE
        advanceUntilIdle()
        assertEquals(listOf(ListBanner.BackupDue, ListBanner.ChangesPending(1, "gm", "Gmail")), vm.state.value.banners)

        // "Later" on the backup: 3 days in the settings, and the banner goes.
        vm.backupLater()
        advanceUntilIdle()
        assertEquals(now + 3 * day, rig.settings.backupReminder.value.snoozedUntilMs)
        assertEquals(listOf(ListBanner.ChangesPending(1, "gm", "Gmail")), vm.state.value.banners)
    }

    @Test
    fun theLocksBannerButtonsGoToTheLock() = runTest(dispatcher) {
        val rig = rig(entry("gm", "Gmail"))
        val pending = PendingMasterPassword(now - 3_600_000L, Shortcut.PIN, 3_600_000L, 23 * 3_600_000L, ready = false)
        rig.lock.status.value = LockStatus.NONE.copy(pending = pending)
        val vm = rig.viewModel()
        advanceUntilIdle()
        assertEquals(listOf(ListBanner.PendingMasterPassword(pending.requestedAtMs, Shortcut.PIN, 23)), vm.state.value.banners.take(1))

        vm.cancelPending()
        rig.lock.status.value = LockStatus.NONE.copy(pending = pending.copy(ready = true, remainingMs = 0))
        advanceUntilIdle()
        assertEquals(ListBanner.PendingReady, vm.state.value.banners.first())
        vm.finishPending()
        vm.pendingLater()
        advanceUntilIdle()
        assertFalse(vm.state.value.banners.any { it == ListBanner.PendingReady })
        vm.itWasntMe()
        assertEquals(listOf(LockIntent.CancelPending, LockIntent.FinishPending, LockIntent.ItWasntMe), rig.lock.sent)

        // A lock forgets what was put off until the next unlock.
        rig.repository.close()
        advanceUntilIdle()
        rig.repository.open()
        advanceUntilIdle()
        assertEquals(ListBanner.PendingReady, vm.state.value.banners.first())
    }

    @Test
    fun deletingAnExportTheProviderAllowsForgetsItAndOneItRefusesStays() = runTest(dispatcher) {
        val refs = listOf(FileRef("content://docs/1", "a.csv", now), FileRef("content://docs/2", "b.json", now))
        val rig = rig(vault = Vault(entries = listOf(entry("gm", "Gmail")), plainExports = refs))
        rig.files.files["content://docs/1"] = byteArrayOf(1)
        rig.files.notDeletable += "content://docs/2"
        val vm = rig.viewModel()
        val events = mutableListOf<ListEvent>()
        scope.launch { vm.events.toList(events) }
        advanceUntilIdle()

        vm.deleteExport("content://docs/1")
        advanceUntilIdle()
        assertFalse("content://docs/1" in rig.files.files)
        assertEquals(listOf("content://docs/2"), rig.repository.stored.plainExports.map { it.uri })
        assertEquals("the permission on it is given up", listOf("content://docs/1"), rig.files.released)

        vm.deleteExport("content://docs/2")
        advanceUntilIdle()
        assertEquals(listOf<ListEvent>(ListEvent.CannotDeleteExport), events)
        assertEquals(listOf("content://docs/2"), rig.repository.stored.plainExports.map { it.uri })
        assertEquals("kept for the banner that stays", listOf("content://docs/1"), rig.files.released)
    }

    // ---- Copying (ux.md §6) ----

    @Test
    fun copiesGoToSecretClipboardWithTheRightFlagAndNoPasswordIsInTheState() = runTest(dispatcher) {
        val rig = rig(
            entry("gm", "Gmail", username = "aziz@gmail.com", password = "s3cret-Gmail"),
            entry("fb", "Facebook", password = "new-Fb", changePending = true),
            entry("sms", "Bank by SMS", password = ""),
        )
        val clipboard = rig.clipboard as FakeSecretClipboard
        val vm = rig.viewModel()
        advanceUntilIdle()

        assertTrue(vm.copyPassword("gm"))
        assertTrue(vm.copyUsername("gm"))
        assertTrue(vm.copyOldPassword("fb"))
        assertTrue(vm.copyNewPassword("fb"))
        assertFalse("no password, nothing to copy", vm.copyPassword("sms"))
        assertFalse("no username", vm.copyUsername("fb"))
        assertFalse("no change pending", vm.copyOldPassword("gm"))
        assertFalse("no such entry", vm.copyPassword("nobody"))

        assertEquals(listOf("s3cret-Gmail", "aziz@gmail.com", "old-fb", "new-Fb"), clipboard.copies.map { it.value })
        assertEquals(listOf(true, false, true, true), clipboard.copies.map { it.sensitive })
        assertEquals(
            listOf(
                UiText.of(R.string.list_copied_password, "Gmail"),
                UiText.of(R.string.list_copied_username, "Gmail"),
                UiText.of(R.string.list_copied_old_password, "Facebook"),
                UiText.of(R.string.list_copied_new_password, "Facebook"),
            ),
            clipboard.copies.map { it.what },
        )

        // The rows say only whether there is a password, never which.
        val rows = (vm.body as ListBody.Browse).sections.flatMap { it.rows }
        val fieldValues = rows.flatMap { row -> listOf(row.id, row.title, row.detail, row.website.orEmpty(), row.initial) }
        for (secret in listOf("s3cret-Gmail", "old-fb", "new-Fb")) assertFalse(secret in fieldValues)
        assertEquals(listOf(true, true, false), rows.sortedBy { it.id }.let { r -> listOf("fb", "gm", "sms").map { id -> r.first { it.id == id }.hasPassword } })
    }

    @Test
    fun aPhoneThatBlocksCopyingIsToldSo() = runTest(dispatcher) {
        val rig = rig(entry("gm", "Gmail"))
        rig.clipboard = object : SecretClipboard {
            override val status: StateFlow<ClipStatus?> = MutableStateFlow(null)

            override fun copy(value: CharSequence, what: UiText, sensitive: Boolean) = throw SecurityException("blocked")

            override fun clearNow() = Unit

            override fun appCameToFront() = Unit
        }
        val vm = rig.viewModel()
        val events = mutableListOf<ListEvent>()
        scope.launch { vm.events.toList(events) }
        advanceUntilIdle()

        assertFalse(vm.copyPassword("gm"))
        advanceUntilIdle()
        assertEquals(listOf<ListEvent>(ListEvent.CopyBlocked), events)
    }

    // ---- The row's menu ----

    @Test
    fun deleteGoesToRecentlyDeletedWithUndo() = runTest(dispatcher) {
        val rig = rig(entry("gm", "Gmail"), entry("tg", "Telegram"))
        val vm = rig.viewModel()
        val events = mutableListOf<ListEvent>()
        scope.launch { vm.events.toList(events) }
        advanceUntilIdle()

        vm.delete("gm")
        advanceUntilIdle()
        val deleted = events.single() as ListEvent.Deleted
        assertEquals("gm", deleted.id)
        assertEquals("Gmail", deleted.title)
        assertEquals(now, rig.repository.stored.entries.first { it.id == "gm" }.deletedAt)
        assertEquals(listOf("Telegram"), vm.body.titles())
        assertEquals(1, vm.state.value.deletedCount)

        vm.undoDelete("gm")
        advanceUntilIdle()
        assertNull(rig.repository.stored.entries.first { it.id == "gm" }.deletedAt)
        assertEquals(listOf("Gmail", "Telegram"), vm.body.titles())
        assertEquals(0, vm.state.value.deletedCount)
    }

    @Test
    fun favouritesAndFailedSaves() = runTest(dispatcher) {
        val rig = rig(entry("gm", "Gmail"))
        val vm = rig.viewModel()
        val events = mutableListOf<ListEvent>()
        scope.launch { vm.events.toList(events) }
        advanceUntilIdle()

        vm.setFavourite("gm", true)
        advanceUntilIdle()
        assertEquals(listOf("Gmail"), (vm.body as ListBody.Browse).favourites.map { it.title })

        rig.repository.failures += SaveProblem.FAILED
        vm.setFavourite("gm", false)
        advanceUntilIdle()
        val failed = events.single() as ListEvent.NotSaved
        assertEquals(SaveProblem.FAILED, failed.problem)
        assertTrue("nothing changed", rig.repository.stored.entries.single().favourite)

        // "Try again" makes the same change.
        failed.retry!!.invoke()
        advanceUntilIdle()
        assertFalse(rig.repository.stored.entries.single().favourite)

        // A vault that no longer fits gets its sentence and no "Try again" (architecture.md §3.5).
        rig.repository.failures += SaveProblem.TOO_LARGE
        vm.setFavourite("gm", true)
        advanceUntilIdle()
        val tooLarge = events.last() as ListEvent.NotSaved
        assertEquals(SaveProblem.TOO_LARGE, tooLarge.problem)
        assertNull(tooLarge.retry)
    }

    @Test
    fun lockNowGoesToTheSession() = runTest(dispatcher) {
        val rig = rig(entry("gm", "Gmail"))
        val vm = rig.viewModel()
        advanceUntilIdle()
        vm.lockNow()
        advanceUntilIdle()
        assertEquals(1, rig.session.lockNows)
        assertTrue(rig.lock.state.value is LockUiState.Locked)
        assertSame("the vault locked, and the list went with it", ListBody.Loading, vm.body)
    }

    // ---- "Type it now" ----

    @Test
    fun typeItNowChecksTheMasterPasswordAsPractice() = runTest(dispatcher) {
        val rig = rig(entry("gm", "Gmail"))
        val vm = rig.viewModel()
        val events = mutableListOf<ListEvent>()
        scope.launch { vm.events.toList(events) }
        advanceUntilIdle()

        vm.openPractice()
        assertEquals(PracticeState(), vm.practice.value)
        rig.lock.mpResults += MpResult.Wrong
        rig.lock.mpResults += MpResult.Wait(untilElapsedMs = 30_000L)
        vm.practicePassword.setTextAndPlaceCursorAtEnd("wrong one")
        vm.submitPractice()
        assertEquals("the field is emptied as soon as it is read", "", vm.practicePassword.text.toString())
        assertEquals(PracticeState(checking = true), vm.practice.value)
        advanceUntilIdle()
        assertEquals(PracticeState(error = PracticeError.Wrong), vm.practice.value)

        vm.practicePassword.setTextAndPlaceCursorAtEnd("again")
        vm.submitPractice()
        advanceUntilIdle()
        assertEquals(PracticeState(error = PracticeError.Wait(30_000L)), vm.practice.value)
        vm.practicePassword.setTextAndPlaceCursorAtEnd("during the wait")
        vm.submitPractice()
        assertEquals("nothing is tried during the wait", 2, rig.lock.purposes.size)
        vm.practiceWaitOver()
        assertEquals(PracticeState(), vm.practice.value)

        vm.practicePassword.setTextAndPlaceCursorAtEnd("right one")
        vm.submitPractice()
        advanceUntilIdle()
        assertNull(vm.practice.value)
        assertEquals(listOf(MpPurpose.PRACTICE, MpPurpose.PRACTICE, MpPurpose.PRACTICE), rig.lock.purposes)
        assertEquals(listOf<ListEvent>(ListEvent.PracticeRight), events)
        assertEquals("the open app stays open", LockUiState.Unlocked(emptyList(), null).javaClass, rig.lock.state.value.javaClass)
    }

    @Test
    fun cancelEmptiesTheFieldAndAnEmptyFieldIsNotSent() = runTest(dispatcher) {
        val rig = rig(entry("gm", "Gmail"))
        val vm = rig.viewModel()
        advanceUntilIdle()
        vm.openPractice()
        vm.submitPractice()
        advanceUntilIdle()
        assertEquals(emptyList<MpPurpose>(), rig.lock.purposes)
        vm.practicePassword.setTextAndPlaceCursorAtEnd("half typed")
        vm.closePractice()
        assertNull(vm.practice.value)
        assertEquals("", vm.practicePassword.text.toString())
    }

    // ---- Adding (ux.md §5.11; architecture.md §3.6) ----

    @Test
    fun addAsNewAccountHandsTheQueryThatFoundNothingToTheNewForm() = runTest(dispatcher) {
        NewAccountName.clear()
        try {
            val rig = rig(entry("gm", "Gmail"), entry("tg", "Telegram"))
            val vm = rig.viewModel()
            advanceUntilIdle()
            vm.type("gmal")
            advanceUntilIdle()
            val none = vm.body as ListBody.NoResults
            assertEquals("gmal", none.typed)

            // The button names what was typed; the form it opens (Route.Edit(), no id) takes it once.
            vm.addAsNewAccount(none.typed)
            assertEquals("gmal", NewAccountName.take())
            assertNull(NewAccountName.take())
            assertEquals("the query stays for the way back", "gmal", vm.query.text.toString())

            // "Add" and the empty vault's "Add account" hand nothing over, not even an older offer.
            vm.addAsNewAccount(none.typed)
            vm.addAccount()
            assertNull(NewAccountName.take())
        } finally {
            NewAccountName.clear()
        }
    }

    @Test
    fun aLockDropsANameHandedOverAndNotYetTaken() = runTest(dispatcher) {
        NewAccountName.clear()
        try {
            val rig = rig(entry("gm", "Gmail"))
            val vm = rig.viewModel()
            advanceUntilIdle()
            vm.type("gmal")
            advanceUntilIdle()
            vm.addAsNewAccount((vm.body as ListBody.NoResults).typed)
            rig.repository.close()
            advanceUntilIdle()
            assertNull("nothing typed waits in memory while locked", NewAccountName.take())
        } finally {
            NewAccountName.clear()
        }
    }

    // ---- A lock (architecture.md §5.5 step 5) ----

    @Test
    fun aLockDropsTheListTheQueryAndTheDialog() = runTest(dispatcher) {
        val rig = rig(entry("gm", "Gmail"), entry("tg", "Telegram"))
        val vm = rig.viewModel()
        advanceUntilIdle()
        vm.type("gma")
        vm.openPractice()
        vm.practicePassword.setTextAndPlaceCursorAtEnd("typing")
        vm.showAllFavourites()
        advanceUntilIdle()
        assertTrue(vm.body is ListBody.Results)

        rig.repository.close()
        advanceUntilIdle()
        assertSame(ListBody.Loading, vm.body)
        assertEquals("", vm.query.text.toString())
        assertEquals("", vm.practicePassword.text.toString())
        assertNull(vm.practice.value)

        // The next unlock shows the list again, from the top.
        rig.repository.open()
        Snapshot.sendApplyNotifications()
        advanceUntilIdle()
        assertEquals(listOf("Gmail", "Telegram"), vm.body.titles())
    }

    @Test
    fun statesAndEventsNeverShowAccountTextInToString() = runTest(dispatcher) {
        val rig = rig(entry("gm", "Gmail", username = "aziz@gmail.com", favourite = true))
        val vm = rig.viewModel()
        advanceUntilIdle()
        val browse = vm.state.value.toString()
        vm.type("gmai")
        advanceUntilIdle()
        val results = vm.state.value.toString()
        val texts = listOf(browse, results, ListEvent.Deleted("gm", "Gmail").toString(), ListBody.NoResults("gmai").toString())
        for (text in texts) {
            assertFalse(text, "Gmail" in text)
            assertFalse(text, "aziz" in text)
            assertFalse(text, "gmai" in text)
        }
    }
}
