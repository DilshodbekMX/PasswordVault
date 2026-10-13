package io.github.dilshodbekmx.passwordvault.ui.settings.master

import android.os.Build
import androidx.compose.ui.test.junit4.ComposeTestRule
import io.github.dilshodbekmx.passwordvault.core.lock.record.Shortcut
import io.github.dilshodbekmx.passwordvault.core.model.Entry
import io.github.dilshodbekmx.passwordvault.core.model.FileRef
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.fake.FakeLockManager
import io.github.dilshodbekmx.passwordvault.fake.FakeVaultRepository
import io.github.dilshodbekmx.passwordvault.lock.FingerprintStatus
import io.github.dilshodbekmx.passwordvault.lock.LockManager
import io.github.dilshodbekmx.passwordvault.lock.LockUiState
import io.github.dilshodbekmx.passwordvault.lock.PendingMasterPassword
import io.github.dilshodbekmx.passwordvault.lock.PinStatus
import io.github.dilshodbekmx.passwordvault.platform.AndroidClock
import io.github.dilshodbekmx.passwordvault.platform.AppDispatchers
import io.github.dilshodbekmx.passwordvault.testing.FakeRandom
import io.github.dilshodbekmx.passwordvault.ui.settings.ChangeMasterPasswordViewModel
import io.github.dilshodbekmx.passwordvault.ui.settings.NewMasterPasswordViewModel
import io.github.dilshodbekmx.passwordvault.ui.settings.StepNavigator
import io.github.dilshodbekmx.passwordvault.ui.settings.shell
import io.github.dilshodbekmx.passwordvault.ui.transfer.NoteDeleted
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * The two routes of build plan 5.5 over the fakes of `APP/fake/`, on a device: an unlocked
 * [FakeLockManager] with the PIN [pin], fingerprint [fingerprint] and a new master password [pending],
 * the vault [vault], and a [StepNavigator]. [offered] counts the proofs handed to the backup, and
 * [deleted] the backups deleted from the list. [close] stops the fakes' coroutines.
 */
class MasterRig(
    pin: PinStatus = PinStatus.On,
    fingerprint: FingerprintStatus = FingerprintStatus.Off,
    pending: PendingMasterPassword? = null,
    vault: Vault = Vault(entries = listOf(GMAIL)),
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val repository = FakeVaultRepository(vault)
    val lock = FakeLockManager(LockUiState.Unlocked(emptyList(), null), repository)
    val nav = StepNavigator()
    val offered: MutableList<Any> = CopyOnWriteArrayList()
    val deleted: MutableList<FileRef> = CopyOnWriteArrayList()

    /** The answer of the next deletes, in order; when empty, deleted. */
    val deleteAnswers = ArrayDeque<NoteDeleted>()

    init {
        lock.status.value = lock.status.value.copy(pin = pin, fingerprint = fingerprint, pending = pending)
    }

    /** "Change master password" over [over]: the fake lock, or a [HeldLock] around it that holds the change. */
    fun change(over: LockManager = lock): ChangeMasterPasswordViewModel = ChangeMasterPasswordViewModel(
        lock = over,
        repository = repository,
        clock = AndroidClock,
        random = FakeRandom(seed = 21L),
        dispatchers = AppDispatchers.Default,
        work = scope,
        offerBackup = { offered += it },
        deleteBackup = { ref ->
            deleted += ref
            synchronized(deleteAnswers) { deleteAnswers.removeFirstOrNull() } ?: NoteDeleted.DELETED
        },
    )

    fun newOne(): NewMasterPasswordViewModel =
        NewMasterPasswordViewModel(lock, AndroidClock, FakeRandom(seed = 22L), AppDispatchers.Default, scope, offerBackup = { offered += it })

    fun close() = scope.cancel()

    companion object {
        /** 2026-10-08 10:00 UTC. */
        const val NOW = 1_791_453_600_000L
        const val DAY_MS = 86_400_000L

        val GMAIL = Entry(id = "00000000-0000-4000-8000-00000000000a", title = "Gmail", password = "pw", createdAt = NOW - DAY_MS, updatedAt = NOW - DAY_MS)

        /** Two backups this app saved, for the list of the last step. */
        val BACKUPS = listOf(
            FileRef("content://com.android.providers.downloads.documents/document/77", "PasswordVault-backup-2026-10-01.pvault", NOW - 7 * DAY_MS),
            FileRef("content://com.google.android.apps.docs.storage/document/acc%3D1%3Bdoc%3D9", "PasswordVault-backup-2026-10-06.pvault", NOW - 2 * DAY_MS),
        )

        /** A vault whose master password is also the password of an account, with two backups. */
        val USED_ELSEWHERE = Vault(entries = listOf(GMAIL), backups = BACKUPS, masterPasswordMatches = listOf(GMAIL.id))

        val READY = PendingMasterPassword(requestedAtMs = NOW - DAY_MS, by = Shortcut.PIN, creditedMs = DAY_MS, remainingMs = 0L, ready = true)
        val WAITING = PendingMasterPassword(requestedAtMs = NOW - 4 * 3_600_000L, by = Shortcut.PIN, creditedMs = 4 * 3_600_000L, remainingMs = 20 * 3_600_000L, ready = false)
    }
}

/**
 * Pictures of the screens for the user (build plan 5.5): the shell user's `screencap` writes them to
 * [dir] on the device, where the uninstall after the run leaves them, and the package's agent pulls
 * them into /home/asus/pvwork/shots-5/ (debug build: the window is not secure). Only on Android 17
 * (API 37), the user's version.
 */
class MasterShots(val dir: String) {
    private val taken = CopyOnWriteArrayList<String>()

    /** Empties [dir]. */
    fun start() {
        if (!on) return
        shell("rm -rf $dir")
        shell("mkdir -p $dir")
        shell("chmod 777 $dir")
    }

    /** Saves the screen as [name].png once the composition is idle and the window has settled. */
    fun take(compose: ComposeTestRule, name: String) {
        if (!on) return
        compose.waitForIdle()
        Thread.sleep(SETTLE_MS)
        compose.waitForIdle()
        shell("screencap -p $dir/$name.png")
        taken += name
    }

    /** The names of the pictures in [dir]. */
    fun taken(): List<String> = shell("ls $dir").lines().map { it.trim() }.filter { it.endsWith(".png") }

    companion object {
        val USER = MasterShots("/data/local/tmp/pv-shots-5.5")

        /** Pictures are taken on Android 17 only. */
        val on: Boolean get() = Build.VERSION.SDK_INT >= 37

        private const val SETTLE_MS = 700L
    }
}

/** Waits until [condition] holds, for up to [ms]. */
fun ComposeTestRule.awaitThat(ms: Long = 10_000L, condition: () -> Boolean) = waitUntil(ms, condition)
