package io.github.dilshodbekmx.passwordvault.ui.entry

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuData
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuItem
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuSession
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuDataProvider
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuProvider
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.isRoot
import io.github.dilshodbekmx.passwordvault.core.model.CustomField
import io.github.dilshodbekmx.passwordvault.core.model.Entry
import io.github.dilshodbekmx.passwordvault.core.model.HistoryItem
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.fake.FakeSecretClipboard
import io.github.dilshodbekmx.passwordvault.fake.FakeVaultRepository
import io.github.dilshodbekmx.passwordvault.nav.Navigator
import io.github.dilshodbekmx.passwordvault.nav.Route
import io.github.dilshodbekmx.passwordvault.platform.AndroidClock
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred

/**
 * An account's screens over the fakes of `APP/fake/`, on a device: a [FakeVaultRepository] holding
 * [vault], a [FakeSecretClipboard] that records the copies, and a [nav] that records where the
 * screens asked to go. The clock is the device's own.
 */
class EntryRig(vault: Vault) {
    val repository = FakeVaultRepository(vault)
    val clipboard = FakeSecretClipboard()
    val nav = RecordingNav()

    fun entryViewModel(id: String = GMAIL, justSaved: Boolean = false) = EntryViewModel(id, repository, clipboard, AndroidClock, justSaved)

    fun bigLettersViewModel(id: String = GMAIL, field: String = "password") = BigLettersViewModel(id, field, repository, AndroidClock)

    /** The account [id] of the open vault. */
    fun entry(id: String = GMAIL): Entry = repository.stored.entries.first { it.id == id }

    companion object {
        const val GMAIL = "11111111-1111-4111-8111-111111111111"
        const val BANK = "22222222-2222-4222-8222-222222222222"
        const val DAY_MS = 86_400_000L

        const val PASSWORD = "Tr0ub4dor&3-xyz!"
        const val OLD_PASSWORD = "Summer#2019-old"
        const val PIN = "40719"
        const val PREVIOUS_0 = "Winter*2025-prev"
        const val PREVIOUS_1 = "Autumn+2024-prev"
        const val USERNAME = "aziz.karimov@gmail.com"
        const val PHONE = "+998901234567"
        const val NOTES = "Samarqand shahrida ochilgan hisob. Xavfsizlik savoli javobi: Registon."

        /** Every text of [gmail] that is a secret: none may be anywhere while it is hidden. */
        val SECRETS = listOf(PASSWORD, OLD_PASSWORD, PIN, PREVIOUS_0, PREVIOUS_1)

        /**
         * An account with everything a screen can show: a username, a password, a website and an
         * app's address, notes, a hidden and a plain field, two previous passwords; with
         * [changePending], also the password the website still has.
         */
        fun gmail(changePending: Boolean = false, now: Long = System.currentTimeMillis(), notes: String = NOTES) = Entry(
            id = GMAIL,
            title = "Gmail",
            username = USERNAME,
            password = PASSWORD,
            notes = notes,
            urls = listOf("https://mail.google.com/mail/u/0", "android://kO3pZ9hash==@com.google.android.gm/"),
            fields = listOf(CustomField("PIN code", PIN, secret = true), CustomField("Phone number", PHONE, secret = false)),
            changePending = changePending,
            createdAt = now - 6 * DAY_MS,
            updatedAt = now - 2 * DAY_MS,
            passwordChangedAt = now - 3 * DAY_MS,
            pendingOldPassword = if (changePending) OLD_PASSWORD else null,
            passwordHistory = listOf(HistoryItem(PREVIOUS_0, now - 3 * DAY_MS), HistoryItem(PREVIOUS_1, now - 5 * DAY_MS)),
        )

        /** An account with a name and nothing else. */
        fun bare(now: Long = System.currentTimeMillis()) = Entry(id = BANK, title = "Kapitalbank", createdAt = now - DAY_MS, updatedAt = now - DAY_MS)
    }
}

/** A [Navigator] that records where the screens asked to go, and allows it ([backAllowed]: Back too). */
class RecordingNav : Navigator {
    val opened: MutableList<Route> = CopyOnWriteArrayList()
    val replaced: MutableList<Route> = CopyOnWriteArrayList()
    private val back = AtomicInteger()

    /** False: [back] answers as at the root of a stack, where there is nowhere to go back to. */
    @Volatile var backAllowed: Boolean = true

    /** How many times the screens went back, or tried to. */
    val backs: Int get() = back.get()

    override fun go(route: Route): Boolean = opened.add(route)

    override fun back(): Boolean {
        back.incrementAndGet()
        return backAllowed
    }

    override fun replace(route: Route): Boolean = replaced.add(route)

    override fun tab(root: Route): Boolean = false
}

/**
 * The Compose clipboard as the app's root provides it (`LocalClipboard`; in the app it is the
 * clipboard package's `SecretClipboardBridge`, architecture.md D-25): records every clip that a
 * text selection's "Copy" hands over, and touches no real clipboard.
 */
class RecordingClipboard(private val context: Context) : Clipboard {
    val clips: MutableList<ClipEntry?> = CopyOnWriteArrayList()

    /** The text of each clip, in order. */
    fun texts(): List<String> = clips.map { clip -> clip?.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString().orEmpty() }

    override suspend fun getClipEntry(): ClipEntry? = clips.lastOrNull()

    override suspend fun setClipEntry(clipEntry: ClipEntry?) {
        clips += clipEntry
    }

    override val nativeClipboard: ClipboardManager
        get() = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
}

/**
 * The toolbar of a text selection, in place of the phone's floating one
 * (`LocalTextContextMenuToolbarProvider`): records what a selection offers and stays "open" until
 * an item closes it, so that a test can see "Copy" and choose it whatever the phone's own toolbar
 * looks like on its Android version.
 */
class RecordingToolbar : TextContextMenuProvider {
    /** Each menu that was shown, with what closes it. */
    class Shown(val data: TextContextMenuData, private val closed: CompletableDeferred<Unit>) : TextContextMenuSession {
        /** The item with [key], such as `TextContextMenuKeys.CopyKey`; null when the menu has none. */
        fun item(key: Any): TextContextMenuItem? = data.components.filterIsInstance<TextContextMenuItem>().firstOrNull { it.key == key }

        override fun close() {
            closed.complete(Unit)
        }
    }

    val shown: MutableList<Shown> = CopyOnWriteArrayList()

    override suspend fun showTextContextMenu(dataProvider: TextContextMenuDataProvider) {
        val closed = CompletableDeferred<Unit>()
        shown += Shown(dataProvider.data(), closed)
        closed.await()
    }
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
