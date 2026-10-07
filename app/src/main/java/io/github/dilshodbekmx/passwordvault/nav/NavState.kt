package io.github.dilshodbekmx.passwordvault.nav

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import io.github.dilshodbekmx.passwordvault.lock.LockManager
import io.github.dilshodbekmx.passwordvault.lock.LockUiState
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Which screen is shown (architecture.md §5.1, §5.4; ux.md §4): three back stacks of route keys,
 * one per top-level state of the app, and the one that `LockManager.state` selects ([follow]).
 *
 * - **Setup** (no vault): Welcome and the steps up to "Type it again". It starts at Welcome
 *   whenever the app enters setup, except at process start, where a stack restored from saved state
 *   is kept. It is emptied once a vault exists.
 * - **Locked**: always `[Locked]` when the app locks; the lock route shows what `LockUiState` says.
 *   Restore may be opened over it from the "Forgot master password?" sheet.
 * - **Unlocked**: the bottom bar's tabs and everything below them. A lock keeps it as ids, so the
 *   same screen comes back after unlock (ux.md §4), except the keys that are never kept across a
 *   lock: "Show in big letters" (D-9d) and the theme and language dialogs (ux.md §4: dialogs are
 *   closed). It starts at `[Passwords]` after setup, and is emptied when the app goes back to setup
 *   (the vault was put aside).
 *
 * Only the stack shown now is in the `NavDisplay`, so a lock takes the unlocked screens out of it:
 * Navigation 3 then clears their ViewModels and their saved state (architecture.md §5.5 step 5),
 * while the ids stay here. All of it is snapshot state, read by Compose, and changed on the main
 * thread only. [encode] and [decode] keep the three stacks across a new process; the top-level state
 * is not kept, because a new process starts at [Top.STARTING] and the lock decides again.
 */
class NavState(setup: List<Route> = emptyList(), locked: List<Route> = emptyList(), unlocked: List<Route> = emptyList()) :
    Navigator {

    /** The app's top-level state, from [LockUiState]. */
    enum class Top { STARTING, SETUP, LOCKED, UNLOCKED }

    private val setupStack: SnapshotStateList<Route> = mutableStateListOf<Route>().apply { addAll(setup) }
    private val lockedStack: SnapshotStateList<Route> = mutableStateListOf<Route>().apply { addAll(locked) }
    private val unlockedStack: SnapshotStateList<Route> = mutableStateListOf<Route>().apply { addAll(unlocked) }

    /** The top-level state shown now. */
    var top: Top by mutableStateOf(Top.STARTING)
        private set

    /** The back stack shown now, root first: what `NavDisplay` shows. Never empty. */
    val visible: List<Route>
        get() = when (top) {
            Top.STARTING -> STARTING_STACK
            Top.SETUP -> setupStack
            Top.LOCKED -> lockedStack
            Top.UNLOCKED -> unlockedStack
        }

    /** A copy of the back stack of [which], shown or kept: for tests and for [encode]. */
    fun stack(which: Top): List<Route> = when (which) {
        Top.STARTING -> STARTING_STACK
        Top.SETUP -> setupStack.toList()
        Top.LOCKED -> lockedStack.toList()
        Top.UNLOCKED -> unlockedStack.toList()
    }

    /** The bottom bar's tab shown now: Passwords, Security or Settings (ux.md §4). */
    val tabRoot: Route
        get() = unlockedStack.getOrNull(1)?.takeIf { it in TAB_ROOTS } ?: Route.Passwords

    /** The unlocked screen shown now is a tab's root, where the bottom bar shows. */
    val atTabRoot: Boolean
        get() = top == Top.UNLOCKED &&
            (unlockedStack.size <= 1 || (unlockedStack.size == 2 && unlockedStack[1] in TAB_ROOTS))

    /**
     * Shows the stack that [state] selects. Entering a top-level state from another one sets up its
     * stack as the class comment says; a new state of the same kind (another lock screen, new
     * notices) changes nothing.
     */
    fun follow(state: LockUiState) {
        val next = topOf(state)
        val previous = top
        if (next == previous) return
        when (next) {
            Top.STARTING -> Unit
            Top.SETUP -> {
                unlockedStack.clear()
                lockedStack.clear()
                if (previous != Top.STARTING || setupStack.isEmpty()) reset(setupStack, Route.Welcome)
            }
            Top.LOCKED -> {
                setupStack.clear()
                reset(lockedStack, Route.Locked)
                unlockedStack.removeAll { !keptAcrossLock(it) }
            }
            Top.UNLOCKED -> {
                setupStack.clear()
                lockedStack.clear()
                if (previous == Top.SETUP || unlockedStack.isEmpty()) reset(unlockedStack, Route.Passwords)
            }
        }
        top = next
    }

    /** Follows [lock]'s state for as long as the caller's coroutine runs. */
    suspend fun follow(lock: LockManager): Nothing {
        lock.state.collect { follow(it) }
    }

    override fun go(route: Route): Boolean {
        val stack = current() ?: return false
        if (!allowedIn(route, top)) return false
        stack.add(route)
        return true
    }

    override fun back(): Boolean {
        val stack = current() ?: return false
        if (stack.size <= 1) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    override fun replace(route: Route): Boolean {
        val stack = current() ?: return false
        if (!allowedIn(route, top)) return false
        if (stack.size <= 1) {
            reset(stack, route)
        } else {
            stack[stack.lastIndex] = route
        }
        return true
    }

    override fun tab(root: Route): Boolean {
        if (top != Top.UNLOCKED || root !in TAB_ROOTS) return false
        if (root == Route.Passwords) reset(unlockedStack, Route.Passwords) else reset(unlockedStack, Route.Passwords, root)
        return true
    }

    /** The three stacks as text for saved state: route keys only, never a secret (architecture.md §5.6). */
    fun encode(): String = JSON.encodeToString(Saved.serializer(), Saved(setupStack.toList(), lockedStack.toList(), unlockedStack.toList()))

    /** The stack shown now, or null while starting, when no navigation is possible. */
    private fun current(): MutableList<Route>? = when (top) {
        Top.STARTING -> null
        Top.SETUP -> setupStack
        Top.LOCKED -> lockedStack
        Top.UNLOCKED -> unlockedStack
    }

    private fun reset(stack: MutableList<Route>, vararg routes: Route) {
        stack.clear()
        stack.addAll(routes)
    }

    @Serializable
    private class Saved(val setup: List<Route> = emptyList(), val locked: List<Route> = emptyList(), val unlocked: List<Route> = emptyList())

    companion object {
        private val STARTING_STACK: List<Route> = listOf(Route.Starting)

        private val TAB_ROOTS: Set<Route> = setOf(Route.Passwords, Route.Security, Route.Settings)

        private val JSON = Json { ignoreUnknownKeys = true }

        /** The stacks of [text] from [encode]; empty stacks when it is null or cannot be read (an older app's routes). */
        fun decode(text: String?): NavState {
            if (text == null) return NavState()
            return try {
                val saved = JSON.decodeFromString(Saved.serializer(), text)
                NavState(saved.setup, saved.locked, saved.unlocked)
            } catch (e: Exception) {
                NavState()
            }
        }

        /** The top-level state that [state] selects. */
        fun topOf(state: LockUiState): Top = when (state) {
            LockUiState.Starting -> Top.STARTING
            is LockUiState.NoVault -> Top.SETUP
            is LockUiState.Unreadable, is LockUiState.Locked -> Top.LOCKED
            is LockUiState.Unlocked -> Top.UNLOCKED
        }

        /**
         * Whether [route] may be opened while [top] is shown: setup's own screens, Restore and the
         * language in setup; the lock route and Restore while locked; every other key, and the
         * language, only while unlocked.
         */
        fun allowedIn(route: Route, top: Top): Boolean = when (route) {
            Route.Starting -> false
            Route.Welcome, Route.CreateMasterPassword, Route.WriteDown, Route.TypeAgain -> top == Top.SETUP
            Route.Restore -> top == Top.SETUP || top == Top.LOCKED
            Route.Language -> top == Top.SETUP || top == Top.UNLOCKED
            Route.Locked -> top == Top.LOCKED
            else -> top == Top.UNLOCKED
        }

        /** Whether [route] stays in the unlocked stack across a lock: not "Show in big letters" (D-9d), nor a dialog. */
        fun keptAcrossLock(route: Route): Boolean = route !is Route.BigLetters && route != Route.Theme && route != Route.Language
    }
}
