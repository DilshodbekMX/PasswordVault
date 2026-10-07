package io.github.dilshodbekmx.passwordvault.i18n

import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalResources

/**
 * A text for the screen as a resource id and its arguments, never a finished sentence
 * (architecture.md §5.4, i18n.md §1.4.4 rule 2). UI state carries it and a composable resolves it
 * with [resolve], so that the language can change under it. Nothing resolves one with the
 * application context, which keeps the phone's language on Android 12L and lower (i18n.md §1.4.3).
 *
 * An argument is a number, a string, or another [UiText], which is resolved first: "Password for
 * %1$s" takes an entry's title, "%1$s · clears in %2$s" takes a [UiText] and a time. Because an
 * argument may be account data (a title), [toString] shows only the id and how many arguments
 * there are (architecture.md §5.6).
 */
sealed interface UiText {
    /** `getString(id, args…)`. */
    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : UiText {
        override fun toString(): String = "UiText.Res(0x${Integer.toHexString(id)}, ${args.size} arguments)"
    }

    /**
     * `getQuantityString(id, count, args…)`: [count] picks the plural form; [args] are the format
     * arguments, which usually start with [count] itself ("%1$d tries left").
     */
    data class Plural(@PluralsRes val id: Int, val count: Int, val args: List<Any> = listOf(count)) : UiText {
        override fun toString(): String = "UiText.Plural(0x${Integer.toHexString(id)}, count=$count, ${args.size} arguments)"
    }

    companion object {
        /** A string resource with [args]. */
        fun of(@StringRes id: Int, vararg args: Any): UiText = Res(id, args.toList())

        /** A plural resource for [count]; with no [args] the only argument is [count]. */
        fun plural(@PluralsRes id: Int, count: Int, vararg args: Any): UiText =
            Plural(id, count, if (args.isEmpty()) listOf(count) else args.toList())
    }
}

/**
 * This text in the language of [resources]: an activity's or a composition's resources, never the
 * application's (see [UiText]). Numbers are formatted for that language, because
 * `Resources.getString` formats with the resources' own locale.
 */
fun UiText.resolve(resources: Resources): String = when (this) {
    is UiText.Res -> if (args.isEmpty()) resources.getString(id) else resources.getString(id, *formatArgs(args, resources))
    is UiText.Plural -> resources.getQuantityString(id, count, *formatArgs(args, resources))
}

/** This text in the composition's language; it is resolved again when the language changes. */
@Composable
fun UiText.resolve(): String = resolve(LocalResources.current)

private fun formatArgs(args: List<Any>, resources: Resources): Array<Any> =
    Array(args.size) { i -> (args[i] as? UiText)?.resolve(resources) ?: args[i] }
