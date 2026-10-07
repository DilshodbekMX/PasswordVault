package io.github.dilshodbekmx.passwordvault.ui.list

import io.github.dilshodbekmx.passwordvault.core.model.CustomField
import io.github.dilshodbekmx.passwordvault.core.model.Entry
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.data.VaultState
import io.github.dilshodbekmx.passwordvault.fake.FakeAppSettings
import io.github.dilshodbekmx.passwordvault.fake.FakeDocumentIo
import io.github.dilshodbekmx.passwordvault.fake.FakeListModelSource
import io.github.dilshodbekmx.passwordvault.fake.FakeLockManager
import io.github.dilshodbekmx.passwordvault.fake.FakeSecretClipboard
import io.github.dilshodbekmx.passwordvault.fake.FakeSession
import io.github.dilshodbekmx.passwordvault.fake.FakeVaultRepository
import io.github.dilshodbekmx.passwordvault.i18n.Collation
import io.github.dilshodbekmx.passwordvault.i18n.SortKeys
import io.github.dilshodbekmx.passwordvault.lock.LockUiState
import io.github.dilshodbekmx.passwordvault.nav.Navigator
import io.github.dilshodbekmx.passwordvault.nav.Route
import io.github.dilshodbekmx.passwordvault.platform.AndroidClock
import io.github.dilshodbekmx.passwordvault.platform.AppDispatchers
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * The list's screens over the fakes of `APP/fake/`, on a device: a [FakeVaultRepository] holding
 * [vault], and a [FakeListModelSource] that follows it as `ListModelProvider` (build plan 3.1)
 * does, sorted by the device's own ICU ([SortKeys], English by default) and null while locked.
 * [close] stops the fakes' coroutines.
 */
class ListRig(vault: Vault, private val collation: Collation = SortKeys(Locale.ENGLISH)) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val repository = FakeVaultRepository(vault)
    val lists = FakeListModelSource()
    val lock = FakeLockManager(LockUiState.Unlocked(emptyList(), null), repository)
    val settings = FakeAppSettings()
    val clipboard = FakeSecretClipboard()
    val files = FakeDocumentIo()
    val session = FakeSession(scope, lock, FakeSecretClipboard(), repository)
    val nav = RecordingNavigator()

    init {
        scope.launch(Dispatchers.Default) {
            repository.state.collect { state ->
                lists.model.value = (state as? VaultState.Unlocked)?.let { FakeListModelSource.build(it.vault.entries, collation) }
            }
        }
    }

    /** Waits until the list of the current vault is built (5,000 entries take a moment to sort). */
    fun awaitList() = runBlocking { lists.model.first { it != null } }

    fun passwordsViewModel() = PasswordsViewModel(
        repository, lists, lock, settings, clipboard, session, files, AndroidClock, AppDispatchers.Default,
    )

    fun recentlyDeletedViewModel() = RecentlyDeletedViewModel(repository, AppDispatchers.Default)

    fun close() = scope.cancel()
}

/** A [Navigator] that records where the screens asked to go. */
class RecordingNavigator : Navigator {
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

/**
 * [n] accounts made from a fixed [seed], in the manner of search.md §5's benchmark: names in every
 * script with prefixes, suffixes and numbers, a username (an e-mail, a phone number or a handle),
 * one or two websites, extra fields on one in five, a note of about 120 characters on three in
 * ten, and a favourite on one in fifty.
 */
fun generatedEntries(n: Int, seed: Int = 2026, now: Long = System.currentTimeMillis()): List<Entry> {
    val r = Random(seed)
    return List(n) { i ->
        val base = NAMES[r.nextInt(NAMES.size)]
        val title = when (r.nextInt(4)) {
            0 -> base
            1 -> "$base ${WORDS[r.nextInt(WORDS.size)]}"
            2 -> "$base ${r.nextInt(1, 100)}"
            else -> "${WORDS[r.nextInt(WORDS.size)]} $base"
        }
        val user = when (r.nextInt(3)) {
            0 -> "dilshod${r.nextInt(1000)}@${DOMAINS[r.nextInt(DOMAINS.size)]}"
            1 -> "+99890${r.nextInt(1_000_000, 9_999_999)}"
            else -> "user_${r.nextInt(100_000)}"
        }
        val host = base.lowercase(Locale.ROOT).filter { it in 'a'..'z' }.ifEmpty { "site$i" }
        val sites = List(1 + r.nextInt(2)) { k -> "https://${if (k == 0) "www." else "m."}$host.uz/login?id=$i" }
        val fields = if (r.nextInt(5) == 0) listOf(CustomField("PIN kod", "0042", true), CustomField("Kodli soʻz", "olma", false)) else emptyList()
        val notes = if (r.nextInt(10) < 3) {
            "Parolni har 90 kunda almashtirish kerak. Bank kartasi bilan bogʻlangan. Telefon: +99890${r.nextInt(1_000_000, 9_999_999)}"
        } else {
            ""
        }
        Entry(
            id = "00000000-0000-4000-8000-%012d".format(Locale.ROOT, i),
            title = title,
            username = user,
            password = "pw-$i-${r.nextInt(1_000_000)}",
            notes = notes,
            urls = sites,
            fields = fields,
            favourite = r.nextInt(50) == 0,
            createdAt = now - 90L * 86_400_000L,
            updatedAt = now - 90L * 86_400_000L,
        )
    }
}

private val NAMES = listOf(
    "Oʻzbekiston Temir Yoʻllari", "Ўзбектелеком", "Telegram", "Сбербанк Онлайн", "Click Uz", "Payme", "Gmail",
    "Gʻalaba fitnes", "Яндекс Почта", "Kapitalbank", "Uzum Bank", "Davlat xizmatlari markazi", "Ипак Йўли банки",
    "Mail.ru", "WhatsApp", "Instagram", "Facebook", "YouTube", "Netflix", "Spotify", "Wildberries", "Ozon",
    "Тошкент шаҳар ҳокимлиги", "Soliq kabineti", "my.gov.uz", "Beeline", "Ucell", "Uzmobile", "Humans",
    "Hamkorbank", "Agrobank", "Xalq banki", "Asaka bank", "Anorbank", "TBC Bank", "Узнацбанк", "Госуслуги",
    "Вконтакте", "Одноклассники", "Avito", "OLX", "Yandex Go", "Steam", "Epic Games", "PlayStation",
    "Microsoft", "Apple ID", "Dropbox", "GitHub", "Stack Overflow", "Coursera", "Udemy", "Duolingo",
    "Qoʻqon kolleji", "Samarqand universiteti", "Buxoro aeroporti", "Хоразм вилояти", "Fargʻona", "Namangan",
    "Andijon bozori", "Ёшлар агентлиги", "Kitob doʻkoni", "Uy Wi-Fi", "Ish pochtasi", "Bolalar bogʻchasi",
    "Klinika", "Dorixona", "Taksi", "Avtoservis", "Kommunal toʻlovlar", "Gaz", "Elektr", "Suv",
)

private val WORDS = listOf(
    "shaxsiy", "ish", "oila", "eski", "yangi", "asosiy", "zaxira", "test", "онлайн", "кабинет", "личный",
    "rabochiy", "2024", "2025", "admin", "user", "mobile", "web", "karta", "hisob",
)

private val DOMAINS = listOf("gmail.com", "mail.ru", "yandex.ru", "inbox.uz", "umail.uz", "outlook.com", "icloud.com")
