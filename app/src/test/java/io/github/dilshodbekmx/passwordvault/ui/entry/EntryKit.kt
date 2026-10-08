package io.github.dilshodbekmx.passwordvault.ui.entry

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.dilshodbekmx.passwordvault.clipboard.SecretClipboard
import io.github.dilshodbekmx.passwordvault.core.model.CustomField
import io.github.dilshodbekmx.passwordvault.core.model.Entry
import io.github.dilshodbekmx.passwordvault.core.model.HistoryItem
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.data.VaultRepository
import io.github.dilshodbekmx.passwordvault.data.VaultState
import io.github.dilshodbekmx.passwordvault.fake.FakeSecretClipboard
import io.github.dilshodbekmx.passwordvault.fake.FakeVaultRepository
import io.github.dilshodbekmx.passwordvault.testing.FakeClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * What the tests of the entry package share: a test dispatcher as the main one, the fakes of
 * `APP/fake/` and `:testing`, and ViewModels kept in a store that [run] clears at the end, as
 * Navigation 3 does when a screen leaves the back stack.
 *
 * **Time** is the [clock]'s and stands still until a test lets it pass ([pass]): the screens wait
 * with `Clock.delayUntilElapsed`, which this clock ends only when it is advanced, so "29.999 seconds
 * later" and "30 seconds later" are exact.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EntryKit private constructor(val scope: TestScope, private val dispatcher: TestDispatcher) {
    val clock = FakeClock(autoAdvance = false)
    val clipboard = FakeSecretClipboard { clock.elapsedMs() }
    private val store = ViewModelStore()

    /** The tests' collectors. Not `backgroundScope`: `advanceUntilIdle` stops when only background work is left. */
    private val collectors = CoroutineScope(SupervisorJob() + dispatcher)
    private var made = 0

    /** A repository holding [vault], open, whose edits get the [clock]'s wall time. */
    fun repository(vault: Vault): FakeVaultRepository = FakeVaultRepository(vault) { clock.wallMs() }

    /** The screen of the account [id] on [repository], with the vault seen. */
    fun screen(
        repository: VaultRepository,
        id: String = GMAIL,
        justSaved: Boolean = false,
        clipboard: SecretClipboard = this.clipboard,
    ): EntryViewModel {
        val factory = viewModelFactory { initializer { EntryViewModel(id, repository, clipboard, clock, justSaved) } }
        val vm = ViewModelProvider.create(store, factory)["entry-${made++}", EntryViewModel::class]
        settle()
        return vm
    }

    /** "Show in big letters" for the value [field] of the account [id] on [repository]. */
    fun bigLetters(repository: VaultRepository, id: String = GMAIL, field: String = "password"): BigLettersViewModel {
        val factory = viewModelFactory { initializer { BigLettersViewModel(id, field, repository, clock) } }
        val vm = ViewModelProvider.create(store, factory)["big-${made++}", BigLettersViewModel::class]
        settle()
        return vm
    }

    /** Every event of [vm] from now on, in order; the list grows as the test goes. */
    fun eventsOf(vm: EntryViewModel): List<EntryEvent> {
        val seen = mutableListOf<EntryEvent>()
        collectors.launch { vm.events.collect { seen += it } }
        settle()
        return seen
    }

    /** Runs everything that is due. */
    fun settle() = scope.advanceUntilIdle()

    /** [ms] pass on both clocks, and what became due runs. */
    fun pass(ms: Long) {
        clock.advance(ms)
        settle()
    }

    /** The screens go, as when they leave the back stack. */
    fun clear() {
        store.clear()
        collectors.cancel()
    }

    companion object {
        /** Runs [test] with a test dispatcher of its own as the main one; the screens are cleared at the end. */
        fun run(test: suspend EntryKit.() -> Unit) {
            val dispatcher = StandardTestDispatcher()
            Dispatchers.setMain(dispatcher)
            try {
                runTest(dispatcher) {
                    val kit = EntryKit(this, dispatcher)
                    try {
                        kit.test()
                    } finally {
                        kit.clear()
                    }
                }
            } finally {
                Dispatchers.resetMain()
            }
        }

        const val GMAIL = "11111111-1111-4111-8111-111111111111"
        const val BANK = "22222222-2222-4222-8222-222222222222"
        const val NOBODY = "99999999-9999-4999-8999-999999999999"

        /** 2026-10-01 00:00 UTC, five days before the kit's clock starts. */
        const val ADDED_MS = 1_790_812_800_000L
        const val DAY_MS = 86_400_000L

        const val PASSWORD = "Tr0ub4dor&3-xyz!"
        const val OLD_PASSWORD = "summer-2019"
        const val PIN = "4071"
        const val NOTE = "Security answer: Samarqand. Ask for Aziz at the bank."

        /** The account most tests look at: a username, a password, two websites, notes, a hidden and a plain field, two previous passwords. */
        fun gmail(
            password: String = PASSWORD,
            changePending: Boolean = false,
            pendingOldPassword: String? = null,
            favourite: Boolean = false,
            checkIgnored: Boolean = false,
            deletedAt: Long? = null,
        ) = Entry(
            id = GMAIL,
            title = "Gmail",
            username = "aziz@gmail.com",
            password = password,
            notes = NOTE,
            urls = listOf("https://mail.google.com/mail/u/0", "android://kO3pZ9hash==@com.google.android.gm/"),
            fields = listOf(CustomField("PIN code", PIN, secret = true), CustomField("Phone number", "+998901234567", secret = false)),
            favourite = favourite,
            changePending = changePending,
            checkIgnored = checkIgnored,
            createdAt = ADDED_MS,
            updatedAt = ADDED_MS + 3 * DAY_MS,
            passwordChangedAt = ADDED_MS + 2 * DAY_MS,
            deletedAt = deletedAt,
            pendingOldPassword = pendingOldPassword,
            passwordHistory = listOf(HistoryItem("winter-2025", ADDED_MS + 2 * DAY_MS), HistoryItem("autumn-2024", ADDED_MS + DAY_MS)),
        )

        /** An account with a name and nothing else. */
        fun bare(id: String = BANK, title: String = "Kapitalbank") = Entry(id = id, title = title, createdAt = ADDED_MS, updatedAt = ADDED_MS)

        /** The account [id] of [repository]'s open vault. */
        fun entryOf(repository: FakeVaultRepository, id: String = GMAIL): Entry =
            (repository.state.value as VaultState.Unlocked).vault.entries.first { it.id == id }

        /** The details that [vm] shows; fails when it shows something else. */
        fun shown(vm: EntryViewModel): EntryDetails =
            (vm.state.value as? EntryUiState.Shown)?.details ?: throw AssertionError("the screen shows ${vm.state.value}, not the account")
    }
}
