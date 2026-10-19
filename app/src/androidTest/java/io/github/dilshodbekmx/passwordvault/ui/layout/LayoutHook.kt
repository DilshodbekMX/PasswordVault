package io.github.dilshodbekmx.passwordvault.ui.layout

import androidx.compose.ui.test.junit4.ComposeTestRule

/**
 * The layout checks of build plan 6.2 at the states the areas' own pseudo-locale tests draw with
 * fakes, which the real app reaches only with time or a damaged file (the lock's other screens, the
 * notices, the results of a backup, an export or an import, the steps of a new master password, the
 * Security tab's cards): each of those tests calls [look] where it keeps a picture.
 *
 * In a plain connected run [look] does nothing. Run by `tools/emu/layout/walk.sh` (its part
 * `states`, `-e pvLayout states`), it looks at the state as [LayoutWalkTest] looks at a screen
 * ([Walker.look]): the probe on what is laid out and on each stretch of every list or column that
 * scrolls, and a picture, all recorded in one [LayoutRun] for the whole instrumentation. The
 * English check runs only on a state drawn in a pseudo-locale, which the tests name with its tag
 * ("…-en-XA", "…-ar-XB").
 */
object LayoutHook {
    private val run: LayoutRun? by lazy { LayoutRun.fromArguments() }

    fun look(compose: ComposeTestRule, area: String, name: String, scrollLists: Boolean = true) {
        val run = run ?: return
        val screen = "$area-$name".replace(Regex("[^A-Za-z0-9._-]"), "_")
        val pseudo = name.contains("en-XA") || name.contains("ar-XB") || name.contains("XA") || name.contains("XB")
        Walker(compose, run).look(screen, scrollLists = scrollLists, english = pseudo)
    }
}
