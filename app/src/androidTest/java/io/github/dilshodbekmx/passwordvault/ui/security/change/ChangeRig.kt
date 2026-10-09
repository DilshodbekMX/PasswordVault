package io.github.dilshodbekmx.passwordvault.ui.security.change

import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.isRoot
import io.github.dilshodbekmx.passwordvault.core.model.Entry
import io.github.dilshodbekmx.passwordvault.core.model.HistoryItem
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.core.strength.Strength
import io.github.dilshodbekmx.passwordvault.fake.FakeAppSettings
import io.github.dilshodbekmx.passwordvault.fake.FakeSecretClipboard
import io.github.dilshodbekmx.passwordvault.fake.FakeVaultRepository
import io.github.dilshodbekmx.passwordvault.nav.Navigator
import io.github.dilshodbekmx.passwordvault.nav.Route
import io.github.dilshodbekmx.passwordvault.platform.AppDispatchers
import io.github.dilshodbekmx.passwordvault.testing.FakeRandom
import io.github.dilshodbekmx.passwordvault.ui.security.GuidedChangeMemory
import io.github.dilshodbekmx.passwordvault.ui.security.GuidedChangeViewModel
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * The guided change over the fakes of `APP/fake/`, on a device: a [FakeVaultRepository] holding
 * [vault], a [FakeSecretClipboard] that records the copies, [FakeAppSettings] for the generator's
 * options, and a [nav] that records where the screen asked to go. [close] it after the test.
 */
class ChangeRig(vault: Vault) {
    val repository = FakeVaultRepository(vault)
    val clipboard = FakeSecretClipboard()
    val settings = FakeAppSettings()
    val nav = RecordingNav()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val memory = GuidedChangeMemory(repository.state, scope)

    fun viewModel(id: String = GMAIL) =
        GuidedChangeViewModel(id, repository, clipboard, settings, FakeRandom(SEED), AppDispatchers.Default, memory, null, ::measure)

    /** The account [id] as the open vault holds it. */
    fun entry(id: String = GMAIL): Entry = repository.stored.entries.first { it.id == id }

    fun close() = scope.cancel()

    companion object {
        private const val SEED = 72L

        const val GMAIL = "11111111-1111-4111-8111-111111111111"
        const val FACEBOOK = "22222222-2222-4222-8222-222222222222"
        const val BANK = "33333333-3333-4333-8333-333333333333"
        const val DAY_MS = 86_400_000L

        /** The password three accounts share: what their websites have now. */
        const val SHARED = "Toshkent2020!"
        const val EARLIER = "Summer#2019-old"
        const val USERNAME = "aziz.karimov@gmail.com"

        /** The strength word of the generator sheet is not what these tests look at. */
        private fun measure(password: CharSequence): Strength = Strength(score = 4, hint = null)

        fun account(id: String, title: String, password: String = SHARED, history: List<HistoryItem> = emptyList(), urls: List<String> = emptyList()): Entry {
            val now = System.currentTimeMillis()
            return Entry(
                id = id,
                title = title,
                username = USERNAME,
                password = password,
                urls = urls,
                createdAt = now - 6 * DAY_MS,
                updatedAt = now - 2 * DAY_MS,
                passwordChangedAt = now - 3 * DAY_MS,
                passwordHistory = history,
            )
        }

        fun gmail() = account(
            GMAIL,
            "Gmail",
            urls = listOf("android://kO3pZ9hash==@com.google.android.gm/", "https://accounts.google.com/"),
            history = listOf(HistoryItem(EARLIER, System.currentTimeMillis() - 5 * DAY_MS)),
        )

        fun facebook(password: String = SHARED, history: List<HistoryItem> = emptyList()) =
            account(FACEBOOK, "Facebook", password = password, history = history, urls = listOf("https://www.facebook.com/"))

        fun bank() = account(BANK, "Kapitalbank")

        /** Gmail, Facebook and Kapitalbank, sharing [SHARED]. */
        fun sharing() = Vault(entries = listOf(gmail(), facebook(), bank()))
    }
}

/** A [Navigator] that records where the screen asked to go, and allows it. */
class RecordingNav : Navigator {
    val opened: MutableList<Route> = CopyOnWriteArrayList()
    val replaced: MutableList<Route> = CopyOnWriteArrayList()
    private val back = AtomicInteger()

    /** How many times the screen went back. */
    val backs: Int get() = back.get()

    override fun go(route: Route): Boolean = opened.add(route)

    override fun back(): Boolean {
        back.incrementAndGet()
        return true
    }

    override fun replace(route: Route): Boolean = replaced.add(route)

    override fun tab(root: Route): Boolean = false
}

/** Every node of the tree that a test sees, in tree order. */
fun SemanticsNodeInteractionsProvider.everyNode(useUnmergedTree: Boolean): List<SemanticsNode> {
    val nodes = mutableListOf<SemanticsNode>()
    fun walk(node: SemanticsNode) {
        nodes += node
        for (child in node.children) walk(child)
    }
    for (root in onAllNodes(isRoot(), useUnmergedTree).fetchSemanticsNodes()) walk(root)
    return nodes
}

/** Every property of every node of both trees as text: what an accessibility service or a test can read. */
fun SemanticsNodeInteractionsProvider.everythingReadable(): String =
    (everyNode(useUnmergedTree = true) + everyNode(useUnmergedTree = false)).joinToString("\n") { node ->
        node.config.joinToString(", ") { (key, value) -> "${key.name}=$value" }
    }
