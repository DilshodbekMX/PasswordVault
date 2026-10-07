package io.github.dilshodbekmx.passwordvault.nav

/**
 * How a screen moves to another (architecture.md §2.2 `nav`). It acts on the back stack shown now:
 * the setup stack, the locked one or the unlocked one, which `LockManager.state` chooses (ux.md
 * §4). A route that does not belong to the stack shown now is refused, so that a screen whose
 * work finishes after a lock can never put an unlocked screen in front of the lock screen.
 */
interface Navigator {
    /** Opens [route] on top of the screen shown now. False: [route] does not belong to the stack shown now; nothing changed. */
    fun go(route: Route): Boolean

    /** Closes the screen shown now. False at the root of its stack, where Back leaves the app (ux.md §4). */
    fun back(): Boolean

    /** Replaces the screen shown now with [route]: a step of a flow that cannot be gone back to. False as for [go]. */
    fun replace(route: Route): Boolean

    /**
     * One of the bottom bar's roots, [Route.Passwords], [Route.Security] or [Route.Settings]: the
     * unlocked stack becomes `[Passwords]` or `[Passwords, root]`, so that Back from Security or
     * Settings goes to Passwords, then leaves (ux.md §4). False while the app is not unlocked, or for
     * another route.
     */
    fun tab(root: Route): Boolean
}
