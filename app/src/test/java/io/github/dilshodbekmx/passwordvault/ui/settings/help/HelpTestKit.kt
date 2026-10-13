package io.github.dilshodbekmx.passwordvault.ui.settings.help

import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.i18n.ResourceCheck
import io.github.dilshodbekmx.passwordvault.i18n.UiText
import io.github.dilshodbekmx.passwordvault.testing.arch.SourceTree
import java.io.File
import java.util.Locale

/**
 * The app's English texts as the phone shows them, read from `app/src/main/res/values/` with build
 * plan 2.9's reader ([ResourceCheck.Strings]), so that a JVM test can read what a [UiText] says: a
 * string with its arguments, or the English plural form for its count.
 */
object English {
    val res: File = File(SourceTree.root(), "app/src/main/res")

    private val strings by lazy { ResourceCheck.Strings.read(File(res, "values"), null) }

    private val names: Map<Int, String> by lazy {
        (R.string::class.java.fields + R.plurals::class.java.fields).associate { it.getInt(null) to it.name }
    }

    /** The resource name of [id]. */
    fun name(id: Int): String = names[id] ?: error("no string or plural with the id 0x${Integer.toHexString(id)}")

    /** The file and name of [name] ("values/strings_help.xml help_title"). */
    fun where(name: String): String = entry(name).where

    /** [text] in English, its arguments filled in (a [UiText] argument first resolved itself). */
    fun text(text: UiText): String = when (text) {
        is UiText.Res -> format(string(name(text.id)), text.args)
        is UiText.Plural -> format(plural(name(text.id), text.count), text.args)
    }

    /** The English text of the string [name], unformatted. */
    fun string(name: String): String {
        val entry = entry(name)
        check(entry.kind == ResourceCheck.Kind.STRING) { "$name is not a string" }
        return ResourceCheck.unescape(entry.items.getValue(null))
    }

    /** The English form of the plural [name] for [count]: "one" for 1, "other" otherwise. */
    fun plural(name: String, count: Int): String {
        val entry = entry(name)
        check(entry.kind == ResourceCheck.Kind.PLURALS) { "$name is not a plural" }
        return ResourceCheck.unescape(entry.items.getValue(if (count == 1) "one" else "other"))
    }

    /** Every name of values/strings_help.xml. */
    fun helpNames(): List<String> = strings.entries.values.filter { it.where.startsWith("values/strings_help.xml ") }.map { it.name }

    private fun entry(name: String): ResourceCheck.Entry = strings.entries[name] ?: error("values/ has no $name")

    private fun format(pattern: String, args: List<Any>): String =
        if (args.isEmpty()) pattern else String.format(Locale.US, pattern, *args.map { (it as? UiText)?.let(::text) ?: it }.toTypedArray())
}
