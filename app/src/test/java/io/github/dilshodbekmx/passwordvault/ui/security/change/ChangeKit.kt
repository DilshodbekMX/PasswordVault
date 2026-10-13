package io.github.dilshodbekmx.passwordvault.ui.security.change

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.dilshodbekmx.passwordvault.clipboard.SecretClipboard
import io.github.dilshodbekmx.passwordvault.core.model.Entry
import io.github.dilshodbekmx.passwordvault.core.model.HistoryItem
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.core.strength.Strength
import io.github.dilshodbekmx.passwordvault.data.VaultState
import io.github.dilshodbekmx.passwordvault.fake.FakeAppSettings
import io.github.dilshodbekmx.passwordvault.fake.FakeSecretClipboard
import io.github.dilshodbekmx.passwordvault.fake.FakeVaultRepository
import io.github.dilshodbekmx.passwordvault.platform.AppDispatchers
import io.github.dilshodbekmx.passwordvault.testing.FakeClock
import io.github.dilshodbekmx.passwordvault.testing.FakeRandom
import io.github.dilshodbekmx.passwordvault.ui.security.GuidedChangeEvent
import io.github.dilshodbekmx.passwordvault.ui.security.GuidedChangeMemory
import io.github.dilshodbekmx.passwordvault.ui.security.GuidedChangeUiState
import io.github.dilshodbekmx.passwordvault.ui.security.GuidedChangeViewModel
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
 * What the tests of the guided change share: a test dispatcher as the main one and as all three of
 * the app's dispatchers, the fakes of `APP/fake/` and `:testing`, and the life of the app around the
 * screen as Navigation 3 and the lock make it (architecture.md §5.5):
 *
 * - [screen] makes the screen's ViewModel in a store of the route, as `ChangeNav` does;
 * - [lock] locks the vault and then clears the route's store, as the lock screen's taking over does;
 *   [unlock] opens it again; the route is then shown again with a new ViewModel ([screen]);
 * - [leave] clears the store while the app is unlocked: Back, "Next", "Done for now";
 * - [newProcess] starts again from what was saved: a new repository over the stored vault, a new
 *   memory and a new store, as after `am force-stop`.
 *
 * The wall clock stands still until [later] moves it, so every saved change has its own time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChangeKit private constructor(val scope: TestScope, private val dispatcher: TestDispatcher) {
    val clock = FakeClock(autoAdvance = false)
    val clipboard = FakeSecretClipboard { clock.elapsedMs() }
    val settings = FakeAppSettings()
    val random = FakeRandom(SEED)
    val dispatchers = AppDispatchers.single(dispatcher)

    private var store = ViewModelStore()

    /** The process's collectors: the memory's watch of the vault and the tests' event lists. */
    private var process = CoroutineScope(SupervisorJob() + dispatcher)
    private var made = 0

    lateinit var repository: FakeVaultRepository
        private set
    lateinit var memory: GuidedChangeMemory
        private set

    /** The app with [vault] open, whose edits get the [clock]'s wall time. */
    fun open(vault: Vault): FakeVaultRepository {
        repository = FakeVaultRepository(vault) { clock.wallMs() }
        memory = GuidedChangeMemory(repository.state, process)
        settle()
        return repository
    }

    /** The guided change of the account [id], with the vault seen. */
    fun screen(id: String = GMAIL, titleOrder: Comparator<String>? = null, clipboard: SecretClipboard = this.clipboard): GuidedChangeViewModel {
        val factory = viewModelFactory {
            initializer {
                GuidedChangeViewModel(id, repository, clipboard, settings, random, dispatchers, memory, titleOrder, ::measure)
            }
        }
        val vm = ViewModelProvider.create(store, factory)["change-${made++}", GuidedChangeViewModel::class]
        settle()
        return vm
    }

    /** Every event of [vm] from now on, in order; the list grows as the test goes. */
    fun eventsOf(vm: GuidedChangeViewModel): List<GuidedChangeEvent> {
        val seen = mutableListOf<GuidedChangeEvent>()
        process.launch { vm.events.collect { seen += it } }
        settle()
        return seen
    }

    /** The vault locks, and the lock screen takes the route's place: its ViewModels go. */
    fun lock() {
        repository.close()
        settle()
        store.clear()
        store = ViewModelStore()
        settle()
    }

    /** The vault opens again with what was saved. */
    fun unlock() {
        repository.open()
        settle()
    }

    /** The screen is left while the app is unlocked (Back, "Next", "Done for now"): its ViewModels go. */
    fun leave() {
        store.clear()
        store = ViewModelStore()
        settle()
    }

    /**
     * The process ends (`am force-stop`) and a new one opens the vault as it was saved: nothing in
     * memory is left, neither the screens nor [GuidedChangeMemory].
     */
    fun newProcess() {
        val saved = repository.stored
        repository.close()
        settle()
        store.clear()
        store = ViewModelStore()
        process.cancel()
        process = CoroutineScope(SupervisorJob() + dispatcher)
        open(saved)
    }

    /** The account [id] as the open vault (or, while locked, the stored one) has it. */
    fun entry(id: String = GMAIL): Entry {
        val vault = (repository.state.value as? VaultState.Unlocked)?.vault ?: repository.stored
        return vault.entries.first { it.id == id }
    }

    /** Runs everything that is due. */
    fun settle() = scope.advanceUntilIdle()

    /** A second passes on the wall clock: the next save has a time of its own. */
    fun later() = clock.setWall(clock.wallMs() + 1_000L)

    private fun finish() {
        store.clear()
        process.cancel()
    }

    companion object {
        /** Runs [test] with a test dispatcher of its own as the main one; the screens are cleared at the end. */
        fun run(test: suspend ChangeKit.() -> Unit) {
            val dispatcher = StandardTestDispatcher()
            Dispatchers.setMain(dispatcher)
            try {
                runTest(dispatcher) {
                    val kit = ChangeKit(this, dispatcher)
                    try {
                        kit.test()
                    } finally {
                        kit.finish()
                    }
                }
            } finally {
                Dispatchers.resetMain()
            }
        }

        private const val SEED = 72L

        const val GMAIL = "11111111-1111-4111-8111-111111111111"
        const val FACEBOOK = "22222222-2222-4222-8222-222222222222"
        const val BANK = "33333333-3333-4333-8333-333333333333"
        const val YANDEX = "44444444-4444-4444-8444-444444444444"
        const val NOBODY = "99999999-9999-4999-8999-999999999999"

        /** 2026-10-01 00:00 UTC. */
        const val ADDED_MS = 1_790_812_800_000L
        const val DAY_MS = 86_400_000L

        /** The password three accounts share: what their websites have now. */
        const val SHARED = "Toshkent2020!"

        /** A previous password of Gmail, from before the shared one. */
        const val EARLIER = "summer-2019"

        /** The generator's strength word is not what these tests look at. */
        private fun measure(password: CharSequence): Strength = Strength(score = 4, hint = null)

        fun account(
            id: String,
            title: String,
            password: String = SHARED,
            username: String = "aziz@example.uz",
            urls: List<String> = emptyList(),
            history: List<HistoryItem> = emptyList(),
            favourite: Boolean = false,
            checkIgnored: Boolean = false,
            deletedAt: Long? = null,
            changePending: Boolean = false,
            pendingOldPassword: String? = null,
        ) = Entry(
            id = id,
            title = title,
            username = username,
            password = password,
            urls = urls,
            favourite = favourite,
            checkIgnored = checkIgnored,
            changePending = changePending,
            pendingOldPassword = pendingOldPassword,
            createdAt = ADDED_MS,
            updatedAt = ADDED_MS + DAY_MS,
            passwordChangedAt = ADDED_MS,
            deletedAt = deletedAt,
            passwordHistory = history,
        )

        /** Gmail: a username, a website and an app's address, one previous password. */
        fun gmail(password: String = SHARED) = account(
            id = GMAIL,
            title = "Gmail",
            password = password,
            username = "aziz.karimov@gmail.com",
            urls = listOf("android://kO3pZ9hash==@com.google.android.gm/", "https://accounts.google.com/"),
            history = listOf(HistoryItem(EARLIER, ADDED_MS)),
        )

        fun facebook(password: String = SHARED, history: List<HistoryItem> = emptyList()) =
            account(FACEBOOK, "Facebook", password = password, urls = listOf("https://www.facebook.com/"), history = history)

        fun bank(password: String = SHARED) = account(BANK, "Kapitalbank", password = password)

        /** Gmail, Facebook and Kapitalbank, sharing [SHARED]. */
        fun sharing(): Vault = Vault(entries = listOf(gmail(), facebook(), bank()))

        /** The steps that [vm] shows; fails when it shows something else. */
        fun steps(vm: GuidedChangeViewModel): GuidedChangeUiState.Steps =
            vm.state.value as? GuidedChangeUiState.Steps ?: throw AssertionError("the screen shows ${vm.state.value}, not the steps")

        /** The end of the change that [vm] shows; fails when it shows something else. */
        fun done(vm: GuidedChangeViewModel): GuidedChangeUiState.Done =
            vm.state.value as? GuidedChangeUiState.Done ?: throw AssertionError("the screen shows ${vm.state.value}, not the end")
    }
}
