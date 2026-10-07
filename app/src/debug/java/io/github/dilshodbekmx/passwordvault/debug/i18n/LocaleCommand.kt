package io.github.dilshodbekmx.passwordvault.debug.i18n

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import io.github.dilshodbekmx.passwordvault.debug.DebugCommand
import io.github.dilshodbekmx.passwordvault.i18n.AppLanguages
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * The debug command `locale` (build plan 2.9; architecture.md §3.6 "Debug commands"): switches the
 * app's language the way the language dialog does ([AppLanguages.setTags]), on every Android version
 * from 7 on, without restarting the app. The activities are recreated in the new language; the
 * process, the lock and the open vault go on (lock.md §11), so a script can check that the app
 * stays on the same screen and the list re-sorts (the skeleton check, build plan 3A step 8).
 *
 * ```
 * adb -s <serial> shell am broadcast -a io.github.dilshodbekmx.passwordvault.debug.CMD \
 *     -p io.github.dilshodbekmx.passwordvault --es cmd 'locale b+uz+Cyrl'
 * ```
 *
 * - `locale TAG`: TAG is a BCP 47 tag (`uz`, `uz-Cyrl`, `en`, `ru`, the pseudo-locales `en-XA` and
 *   `ar-XB`) or the same as a resource qualifier (`b+uz+Cyrl`, `ru-rRU`).
 * - `locale system`: back to the phone's languages (also `default`).
 * - `locale`: changes nothing and says what is set.
 *
 * It answers `locale: <tags> (was <tags>); shown: <language>` once Android reports the new choice,
 * where "shown" is the language the app's texts are in: Android's choice among the languages the APK
 * has, so English for a language whose translations do not exist yet (Uzbek Cyrillic until wave 6).
 * Android 13 and later keep the choice in the system; Android 7 to 12 in AppCompat's record file, and
 * there it needs the app's activity to have started once in this process, as the dialog does.
 */
object LocaleCommand : DebugCommand {
    override suspend fun run(context: Context, args: List<String>): String {
        val before = tagsOf(context)
        if (args.isEmpty()) return "locale: $before; shown: ${shown(context)}"
        require(args.size == 1) { "usage: locale [TAG | system]" }
        val wanted = bcp47(args[0])
        AppLanguages.setTags(context, wanted)
        // Android 13 and later apply the choice through the system: wait until it reports it.
        var waitedMs = 0L
        while (tagsOf(context) != describe(wanted) && waitedMs < WAIT_MS) {
            delay(STEP_MS)
            waitedMs += STEP_MS
        }
        val after = tagsOf(context)
        check(after == describe(wanted)) { "the app's locales are $after, not ${describe(wanted)}" }
        return "locale: $after (was $before); shown: ${shown(context)}"
    }

    /** `b+uz+Cyrl` → `uz-Cyrl`, `ru-rRU` → `ru-RU`, `system` → the empty list; a BCP 47 tag stays. */
    private fun bcp47(arg: String): String {
        if (arg == "system" || arg == "default") return ""
        val tag = when {
            arg.startsWith("b+") -> arg.removePrefix("b+").replace('+', '-')
            Regex("[a-z]{2,3}-r[A-Z]{2}").matches(arg) -> arg.replace("-r", "-")
            else -> arg
        }
        val locale = Locale.forLanguageTag(tag)
        require(locale.language.isNotEmpty() && locale.toLanguageTag() != "und") { "not a language tag: $arg" }
        return locale.toLanguageTag()
    }

    private fun tagsOf(context: Context): String = describe(AppLanguages.appLocales(context).joinToString(",") { it.toLanguageTag() })

    private fun describe(tags: String): String = tags.ifEmpty { "system" }

    /**
     * The language the app's texts are in now: Android's own choice among the languages the APK has,
     * read from a configuration with the app's locales in front of the phone's. Until a language's
     * translations exist (wave 6), Android picks English for it and so does this.
     */
    private fun shown(context: Context): String {
        val locales = (AppLanguages.appLocales(context) + AppLanguages.systemLocales(context)).distinct()
        val configuration = Configuration(context.resources.configuration)
        configuration.setLocales(LocaleList(*locales.toTypedArray()))
        val resolved = context.createConfigurationContext(configuration).resources.configuration
        return AppLanguages.shown(AppLanguages.localesOf(resolved)).name
    }

    private const val WAIT_MS = 5_000L
    private const val STEP_MS = 50L
}
