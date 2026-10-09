package io.github.dilshodbekmx.passwordvault.ui.security

import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.test.platform.app.InstrumentationRegistry
import io.github.dilshodbekmx.passwordvault.core.model.Entry
import io.github.dilshodbekmx.passwordvault.core.model.HistoryItem
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.core.strength.SecurityReport
import io.github.dilshodbekmx.passwordvault.data.SecurityCheckRunner
import io.github.dilshodbekmx.passwordvault.fake.FakeVaultRepository
import io.github.dilshodbekmx.passwordvault.i18n.SortKeys
import io.github.dilshodbekmx.passwordvault.lock.LockUiState
import io.github.dilshodbekmx.passwordvault.nav.Navigator
import io.github.dilshodbekmx.passwordvault.nav.Route
import io.github.dilshodbekmx.passwordvault.platform.AppDispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The Security tab's screens on a device, over the app's own check: a real [SecurityCheckRunner] (the
 * bundled meter, `SortKeys` of [locale], one thread of `Dispatchers.Default`) following a
 * [FakeVaultRepository] that holds [vault], and the lock's state as [lock] (unlocked, no notice, until
 * a test sets one). [close] stops the coroutines.
 */
class SecurityRig(vault: Vault, locale: Locale = Locale.ENGLISH, clock: () -> Long = { System.currentTimeMillis() }) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val repository = FakeVaultRepository(vault, clock)
    val lock = MutableStateFlow<LockUiState>(LockUiState.Unlocked(emptyList(), null))
    val nav = SecurityNavigator()
    val runner = SecurityCheckRunner(
        repository = repository,
        locales = MutableStateFlow(locale),
        collationOf = ::SortKeys,
        scope = scope,
        dispatcher = Dispatchers.Default.limitedParallelism(1),
    )

    init {
        runner.start()
    }

    fun securityViewModel() = SecurityViewModel(runner, runner.progress, runner::markSeen, repository.state, lock, AppDispatchers.Default)

    fun groupViewModel(listId: String) = GroupListViewModel(listId, runner, repository, AppDispatchers.Default)

    /** Waits until the check of the current vault has published its report. */
    fun awaitReport(): SecurityReport = runBlocking {
        withTimeout(REPORT_MS) { runner.report.first { it != null } }!!
    }

    fun close() = scope.cancel()

    private companion object {
        /** The first check loads the meter's dictionary: a few seconds on the slowest device. */
        const val REPORT_MS = 60_000L
    }
}

/** A [Navigator] that records where the screens asked to go. */
class SecurityNavigator : Navigator {
    val routes: MutableList<Route> = CopyOnWriteArrayList()

    @Volatile
    var backs = 0

    override fun go(route: Route): Boolean = routes.add(route)

    override fun back(): Boolean {
        backs++
        return true
    }

    override fun replace(route: Route): Boolean = routes.add(route)

    override fun tab(root: Route): Boolean = false
}

/** Accounts as the tests need them. */
object SecurityAccounts {
    private const val DAY = 86_400_000L

    fun id(n: Int): String = "00000000-0000-4000-8000-%012d".format(Locale.ROOT, n)

    fun entry(
        n: Int,
        title: String,
        password: String = "Own-Pw-$n#Zq8x",
        username: String = "",
        urls: List<String> = emptyList(),
        favourite: Boolean = false,
        changePending: Boolean = false,
        pendingOldPassword: String? = null,
        checkIgnored: Boolean = false,
        history: List<String> = emptyList(),
        now: Long = System.currentTimeMillis(),
    ): Entry = Entry(
        id = id(n),
        title = title,
        username = username,
        password = password,
        urls = urls,
        favourite = favourite,
        changePending = changePending,
        pendingOldPassword = pendingOldPassword,
        checkIgnored = checkIgnored,
        createdAt = now - 90 * DAY + n,
        updatedAt = now - 30 * DAY + n,
        passwordHistory = history.map { HistoryItem(it, now - 20 * DAY) },
    )

    /**
     * The user's passwords as ux.md §5.16 pictures them, every card at once: the master password on
     * Gmail and Facebook; one password on seven accounts, three of which changed already; another on
     * three; two easy to guess; a change waiting for its answer; two accounts left out.
     */
    fun everything(now: Long = System.currentTimeMillis()): Vault {
        val shared = "Qovun#Sabzi_2019"
        return Vault(
            entries = listOf(
                entry(1, "Gmail", shared, "aziz.karimov@gmail.com", listOf("https://mail.google.com"), favourite = true, now = now),
                entry(2, "Facebook", shared, "aziz.karimov@gmail.com", listOf("https://www.facebook.com"), now = now),
                entry(3, "Yandex Pochta", shared, "aziz.karimov@yandex.ru", listOf("https://mail.yandex.uz"), now = now),
                entry(4, "Instagram", shared, "aziz_karimov", listOf("https://www.instagram.com"), now = now),
                entry(5, "Telegram Web", shared, "+998 90 123 45 67", listOf("https://web.telegram.org"), now = now),
                entry(6, "Oʻzbekiston pochtasi", shared, "aziz.karimov", listOf("https://pochta.uz"), now = now),
                entry(7, "Uzum Market", shared, "+998 90 123 45 67", listOf("https://uzum.uz"), now = now),
                entry(8, "OLX", "Olx-Own-Pw-77", "aziz.karimov@gmail.com", history = listOf(shared), now = now),
                entry(9, "LinkedIn", "Linked-Own-Pw-5", "aziz.karimov@gmail.com", history = listOf(shared), now = now),
                entry(10, "Netflix", "Netflix-Own-Pw-3", "aziz.karimov@gmail.com", history = listOf(shared), now = now),
                entry(11, "Payme", "Kv9#mT2pXq!w", "+998 90 123 45 67", now = now),
                entry(12, "Click", "Kv9#mT2pXq!w", "+998 90 123 45 67", now = now),
                entry(13, "Apelsin", "Kv9#mT2pXq!w", "+998 90 123 45 67", now = now),
                entry(14, "Uy Wi-Fi", "Toshkent2020", "admin", now = now),
                entry(15, "Kundalik", "Samarqand2024!", "aziz.karimov", listOf("https://kundalik.com"), now = now),
                entry(16, "Kapitalbank", "New-Bank-Pw-9#x2", "+998 90 123 45 67", changePending = true, pendingOldPassword = "Old-Bank-Pw-3#k", now = now),
                entry(17, "Card PIN", "4821", checkIgnored = true, now = now),
                entry(18, "Door code", "1234", checkIgnored = true, now = now),
                entry(19, "GitHub", "gH7#vQ2m!Lp9sW4z", "azizk", listOf("https://github.com"), now = now),
            ),
            masterPasswordMatches = listOf(id(1), id(2)),
        )
    }
}

/** Runs [command] as the shell user and returns what it printed. */
fun securityShell(command: String): String {
    val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
    return ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes().toString(Charsets.UTF_8) }
}

/**
 * Pictures of the Security tab for the user (build plan 7.1's note), on Android 17 (API 37) with the
 * debug build: the shell user's `screencap` writes them to [DIR] on the device, where AGP's uninstall
 * after the run leaves them, and the package's agent pulls them into `/home/asus/pvwork/shots-7/`.
 */
object SecurityShots {
    const val DIR = "/data/local/tmp/pv-shots-7.1"

    /** Saves the screen as [name].png in [DIR], once the composition is idle and the window has settled. */
    fun take(compose: ComposeTestRule, name: String) {
        if (Build.VERSION.SDK_INT < SHOTS_API) return
        compose.waitForIdle()
        Thread.sleep(SETTLE_MS)
        compose.waitForIdle()
        securityShell("mkdir -p $DIR")
        securityShell("screencap -p $DIR/$name.png")
    }

    private const val SHOTS_API = 37
    private const val SETTLE_MS = 700L
}
