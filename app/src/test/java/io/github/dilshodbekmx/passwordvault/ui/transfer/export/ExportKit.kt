package io.github.dilshodbekmx.passwordvault.ui.transfer.export

import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.snapshots.Snapshot
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.dilshodbekmx.passwordvault.core.formats.VaultXlsx
import io.github.dilshodbekmx.passwordvault.core.model.CustomField
import io.github.dilshodbekmx.passwordvault.core.model.Entry
import io.github.dilshodbekmx.passwordvault.core.model.HistoryItem
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.data.VaultState
import io.github.dilshodbekmx.passwordvault.fake.FakeAppSettings
import io.github.dilshodbekmx.passwordvault.fake.FakeDocumentIo
import io.github.dilshodbekmx.passwordvault.fake.FakeLockManager
import io.github.dilshodbekmx.passwordvault.fake.FakeSecretClipboard
import io.github.dilshodbekmx.passwordvault.fake.FakeSession
import io.github.dilshodbekmx.passwordvault.fake.FakeVaultRepository
import io.github.dilshodbekmx.passwordvault.files.BackupSharing
import io.github.dilshodbekmx.passwordvault.files.DocRef
import io.github.dilshodbekmx.passwordvault.files.DocumentIo
import io.github.dilshodbekmx.passwordvault.files.FileTaskStore
import io.github.dilshodbekmx.passwordvault.files.PickerLauncher
import io.github.dilshodbekmx.passwordvault.lock.LockReason
import io.github.dilshodbekmx.passwordvault.lock.LockUiState
import io.github.dilshodbekmx.passwordvault.platform.AppDispatchers
import io.github.dilshodbekmx.passwordvault.testing.FakeClock
import io.github.dilshodbekmx.passwordvault.ui.transfer.ExportChoice
import io.github.dilshodbekmx.passwordvault.ui.transfer.ExportEvent
import io.github.dilshodbekmx.passwordvault.ui.transfer.ExportUiState
import io.github.dilshodbekmx.passwordvault.ui.transfer.ExportViewModel
import java.io.File
import java.nio.file.Files
import java.util.TimeZone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * What the export's JVM tests share: a test dispatcher as the main one, the fakes of `APP/fake/`
 * and `:testing`, the real [FileTaskStore] over them with a [picker] that records what it was asked
 * to open, and the export's ViewModels, each in a store of its own that [leave] clears, as
 * Navigation 3 does when the screen leaves the back stack (at a lock, architecture.md §5.5).
 *
 * The file picker answers through the store, as the activity's launchers do ([pick], [closePicker]).
 * A new process is a new store over the same settings ([newProcess]). The wall clock stands at
 * 2026-10-06 00:00 UTC and the files' dates are in Tashkent's time zone.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExportKit private constructor(val scope: TestScope, private val dispatcher: TestDispatcher, vault: Vault) {
    val clock = FakeClock(autoAdvance = false)
    val settings = FakeAppSettings()
    val documents = FakeDocumentIo()

    /** While true, every write waits (the screen shows "Saving…"); set it back to false to let them go on. */
    var holdWrites: Boolean
        get() = writesHeld.value
        set(value) {
            writesHeld.value = value
        }
    private val writesHeld = MutableStateFlow(false)

    /** [documents], whose writes wait while [holdWrites] is true: what the store and the screen use. */
    private val io: DocumentIo = object : DocumentIo by documents {
        override suspend fun write(doc: DocRef, bytes: ByteArray): Boolean {
            writesHeld.first { !it }
            return documents.write(doc, bytes)
        }
    }
    val repository = FakeVaultRepository(vault) { clock.wallMs() }
    val lock = FakeLockManager(LockUiState.Unlocked(emptyList(), null), repository)
    val picker = RecordingPicker()
    val zone: TimeZone = TimeZone.getTimeZone("Asia/Tashkent")
    val dispatchers = AppDispatchers.single(dispatcher)

    /** The app's scope: the checks and writes that must finish, and the store's own work. */
    val work = CoroutineScope(SupervisorJob() + dispatcher)
    private val collectors = CoroutineScope(SupervisorJob() + dispatcher)
    private val sharing = Files.createTempDirectory("pv-export-share").toFile()
    private val stores = mutableMapOf<ExportViewModel, ViewModelStore>()
    private val others = mutableListOf<ViewModelStore>()

    /** The process's store; a new one after [newProcess]. */
    var store: FileTaskStore = newStore()
        private set

    private fun newStore(): FileTaskStore = FileTaskStore(
        settings = settings,
        session = FakeSession(work, lock, FakeSecretClipboard(), repository),
        documents = io,
        lockState = lock.state,
        scope = work,
        dispatchers = dispatchers,
        sharing = BackupSharing(File(sharing, "shared")),
    ).also { it.attach(picker) }

    /** The export screen as it opens: its ViewModel, made in a store of its own. */
    fun screen(oldAndroid: Boolean = false, generator: String = GENERATOR): ExportViewModel {
        val factory = viewModelFactory {
            initializer {
                ExportViewModel(
                    repository = repository,
                    lock = lock,
                    store = store,
                    documents = io,
                    clock = clock,
                    dispatchers = dispatchers,
                    work = work,
                    generator = generator,
                    oldAndroid = oldAndroid,
                    zone = { zone },
                )
            }
        }
        val owner = ViewModelStore()
        val vm = ViewModelProvider.create(owner, factory)["export", ExportViewModel::class]
        stores[vm] = owner
        settle()
        return vm
    }

    /** Another screen's ViewModel made by [create], in a store of its own that the end of the test clears. */
    fun <T : ViewModel> other(create: () -> T): T {
        val owner = ViewModelStore()
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <VM : ViewModel> create(modelClass: Class<VM>): VM = create() as VM
        }
        val vm = ViewModelProvider.create(owner, factory)["other", ViewModel::class]
        others += owner
        settle()
        @Suppress("UNCHECKED_CAST")
        return vm as T
    }

    /** The screen leaves the back stack: its ViewModel is cleared. */
    fun leave(vm: ExportViewModel) {
        stores.remove(vm)?.clear()
        settle()
    }

    /** Every event of [vm] from now on, in order; the list grows as the test goes. */
    fun eventsOf(vm: ExportViewModel): List<ExportEvent> {
        val seen = mutableListOf<ExportEvent>()
        collectors.launch { vm.events.collect { seen += it } }
        settle()
        return seen
    }

    /** Types [text] into the master-password field as the keyboard would. */
    fun type(vm: ExportViewModel, text: String) {
        vm.password.setTextAndPlaceCursorAtEnd(text)
        Snapshot.sendApplyNotifications()
    }

    /** Types the master password and taps "Continue". */
    fun submit(vm: ExportViewModel, text: String = MASTER) {
        type(vm, text)
        vm.submitPassword()
        settle()
    }

    /**
     * From "Choose a format" to the master password of [choice]'s format: the row, "Continue", "I
     * understand", "Continue".
     */
    fun toPassword(vm: ExportViewModel, choice: ExportChoice) {
        vm.choose(choice)
        vm.continueFromChoice()
        settle()
        vm.setUnderstood(true)
        vm.continueFromWarning()
        settle()
    }

    /** The picker returns a new, empty document at [uri], named [name] by its place. */
    fun pick(uri: String = DOC, name: String? = null) {
        documents.files[uri] = ByteArray(0)
        if (name != null) documents.names[uri] = name
        store.returned(FileTaskStore.Action.CREATE, uri)
        settle()
    }

    /** The picker is closed without a document. */
    fun closePicker() {
        store.returned(FileTaskStore.Action.CREATE, null)
        settle()
    }

    /** The app locks, as `AutoLock` does at a screen-off: the vault closes. */
    fun lockNow() {
        scope.launch { lock.lock(LockReason.SCREEN_OFF) }
        settle()
    }

    /** The app is unlocked again with the PIN: the vault opens; no master password was typed. */
    fun unlock() {
        repository.open()
        lock.state.value = LockUiState.Unlocked(emptyList(), null)
        settle()
    }

    /** The process ends and a new one starts, locked: a new store over the same settings. */
    fun newProcess() {
        for (owner in stores.values) owner.clear()
        stores.clear()
        repository.close()
        lock.state.value = LockUiState.Locked(lock.lockScreen)
        store = newStore()
        settle()
    }

    /** The open vault. */
    fun vault(): Vault = (repository.state.value as VaultState.Unlocked).vault

    /** Runs everything that is due. */
    fun settle() = scope.advanceUntilIdle()

    /** [ms] pass on both clocks, and what became due runs. */
    fun pass(ms: Long) {
        clock.advance(ms)
        settle()
    }

    private fun close() {
        for (owner in stores.values) owner.clear()
        stores.clear()
        for (owner in others) owner.clear()
        collectors.cancel()
        work.cancel()
        sharing.deleteRecursively()
    }

    /** "Save as" and the share sheet, as the activity would open them: recorded, never shown. */
    class RecordingPicker : PickerLauncher {
        /** Each "Save as": the suggested name and the MIME type. */
        val creates = mutableListOf<Pair<String, String>>()
        val opens = mutableListOf<List<String>>()
        val shares = mutableListOf<String>()

        override fun create(name: String, mime: String) {
            creates += name to mime
        }

        override fun open(mimeTypes: List<String>) {
            opens += mimeTypes
        }

        override fun share(file: File, title: CharSequence) {
            shares += file.name
        }
    }

    companion object {
        /** Runs [test] with a test dispatcher of its own as the main one, over a vault of [vault]. */
        fun run(vault: Vault = sampleVault(), test: suspend ExportKit.() -> Unit) {
            val dispatcher = StandardTestDispatcher()
            Dispatchers.setMain(dispatcher)
            try {
                runTest(dispatcher) {
                    val kit = ExportKit(this, dispatcher, vault)
                    try {
                        kit.test()
                    } finally {
                        kit.close()
                    }
                }
            } finally {
                Dispatchers.resetMain()
            }
        }

        const val MASTER = "Toshkent-2026-qalam"
        const val GENERATOR = "PasswordVault 1.0"

        /** A document the picker made in Downloads. */
        const val DOC = "content://com.android.providers.downloads.documents/document/41"
        const val DOC2 = "content://com.android.providers.downloads.documents/document/42"
        const val DRIVE_DOC = "content://com.google.android.apps.docs.storage/document/acc%3D1%3Bdoc%3D7"

        /** 2026-10-01 00:00 UTC. */
        const val ADDED_MS = 1_790_812_800_000L
        const val DAY_MS = 86_400_000L

        const val GMAIL = "11111111-1111-4111-8111-111111111111"
        const val BANK = "22222222-2222-4222-8222-222222222222"
        const val PHONE = "33333333-3333-4333-8333-333333333333"
        const val GONE = "44444444-4444-4444-8444-444444444444"

        /**
         * Accounts with what the integration check of wave 5 exports (build plan §7, step 3): Uzbek
         * Latin and Cyrillic, a phone number starting with +998, a value starting with "=", a note of
         * several lines, a hidden field, a pending change, previous passwords; and one in Recently
         * deleted, which no plain export holds (D-10e).
         */
        fun sampleVault(): Vault = Vault(
            entries = listOf(
                Entry(
                    id = GMAIL,
                    title = "Gmail – Oʻzbekiston",
                    username = "aziz@gmail.com",
                    password = "N3w!pass",
                    notes = "Birinchi qator\nИккинчи қатор",
                    urls = listOf("https://mail.google.com", "android://hash@com.google.android.gm/"),
                    fields = listOf(CustomField("PIN code", "4321", secret = true), CustomField("Телефон", "+998901234567", secret = false)),
                    favourite = true,
                    changePending = true,
                    pendingOldPassword = "Old#2",
                    createdAt = ADDED_MS,
                    updatedAt = ADDED_MS + DAY_MS,
                    passwordChangedAt = ADDED_MS + DAY_MS,
                    passwordHistory = listOf(HistoryItem("old-1", ADDED_MS)),
                ),
                Entry(
                    id = BANK,
                    title = "Ипотека банк",
                    username = "+998901234567",
                    password = "=1+2",
                    createdAt = ADDED_MS,
                    updatedAt = ADDED_MS,
                ),
                Entry(id = PHONE, title = "Uy telefoni", password = "0123", createdAt = ADDED_MS, updatedAt = ADDED_MS),
                Entry(
                    id = GONE,
                    title = "Deleted one",
                    password = "gone-pass",
                    urls = listOf("https://gone.example"),
                    createdAt = ADDED_MS,
                    updatedAt = ADDED_MS,
                    deletedAt = ADDED_MS + DAY_MS,
                ),
            ),
        )

        /** The texts the tests give the Excel file, different from the defaults so that they can be seen. */
        val LABELS = VaultXlsx.Labels(sheet = "Parollar", name = "Nomi", password = "Parol", yes = "Ha", hidden = "yashirin")
    }
}

/** The state [vm] shows, as the type the test expects; fails with what it shows instead. */
inline fun <reified T : ExportUiState> ExportViewModel.shows(): T =
    state.value as? T ?: throw AssertionError("the export shows ${state.value}, not ${T::class.simpleName}")
