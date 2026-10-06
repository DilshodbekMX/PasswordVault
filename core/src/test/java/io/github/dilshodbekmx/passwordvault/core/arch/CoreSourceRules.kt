package io.github.dilshodbekmx.passwordvault.core.arch

import io.github.dilshodbekmx.passwordvault.testing.arch.PatternRule
import io.github.dilshodbekmx.passwordvault.testing.arch.Rule
import io.github.dilshodbekmx.passwordvault.testing.arch.SourceFile

/**
 * The build checks of architecture.md §8.5 that concern :core (and :testing), as source scans with
 * comments ignored. The rules for every module's sources (R-11's list, the offline scan) and those
 * of :app are in the app's tests (`app/src/test/.../arch`).
 */
object CoreSourceRules {
    const val CORE_MAIN = "core/src/main"
    const val TESTING_MAIN = "testing/src/main"

    private const val CORE_PACKAGE = "io.github.dilshodbekmx.passwordvault.core"

    /** R-10: neither module reaches the Android framework or AndroidX, imported or by full name. */
    val noAndroid = PatternRule("R-10", "android or androidx in :core or :testing", Regex("""(?<![\w.])androidx?\.\w"""))

    /** R-10: the lock's rules never reach the vault file or its storage; they go through VaultPort. */
    val lockLayers = PatternRule(
        "R-10",
        "core.lock names core.storage or core.vaultfile",
        Regex("""\bio\.github\.dilshodbekmx\.passwordvault\.core\.(storage|vaultfile)\b"""),
    ) { file, _ -> !inPackage(file, "$CORE_PACKAGE.lock") }

    /** R-6 as widened by architecture.md §5.6: no logging of any kind in :core. */
    val noLogging: List<Rule> = listOf(
        PatternRule("R-6", "println or print", Regex("""(?<![\w.])print(ln)?\s*\(""")),
        PatternRule("R-6", "System.out or System.err", Regex("""\bSystem\s*\.\s*(out|err)\b""")),
        PatternRule("R-6", "printStackTrace", Regex("""\bprintStackTrace\b""")),
        PatternRule("R-6", "dumpStack", Regex("""\bdumpStack\s*\(""")),
        PatternRule("R-6", "java.util.logging", Regex("""\bjava\.util\.logging\b""")),
        PatternRule("R-6", "System.Logger", Regex("""\bSystem\s*\.\s*(getLogger|Logger)\b""")),
    )

    /**
     * Files of :core allowed a `.format(` call without a Locale argument. Empty: a number in a file
     * or a file name is written with ASCII digits (architecture.md §4), so every format names its
     * locale. A file is added here only through an interface request that says why.
     */
    val formatAllowList: Set<String> = emptySet()

    /**
     * R-11: in :core, `String.format` and `.format(` take a Locale as their first argument, such as
     * `Locale.ROOT` or a parameter named `locale`. Not calls: declarations (`fun String.format(`).
     * Not locale formatting: a call on one of our own types or objects, written in UpperCamelCase
     * (`IsoTime.format(ms)`); whatever such a function formats inside is checked where it does.
     * `String.format`, `MessageFormat.format`, `"…".format(…)`, variables and constants
     * (`PATTERN.format(n)`) are always checked.
     */
    val formatWithLocale = object : Rule("R-11", "String.format or .format( without a Locale in :core") {
        private val call = Regex("""\.\s*format\s*\(""")
        private val declaration = Regex("""\bfun\s+(<[^>]*>\s*)?[\w.<>?, ]*$""")
        private val localeFirst = Regex("""^((java\.util\.)?Locale\b|\w*[lL]ocale\b)""")
        private val receiverName = Regex("""(\w+)\s*$""")
        private val localeFormatters = setOf("String", "MessageFormat")

        override fun offsetsIn(file: SourceFile): List<Int> {
            if (file.path in formatAllowList) return emptyList()
            return call.findAll(file.code).filter { match ->
                if (file.isLiteral(match.range.first)) return@filter false
                val lineStart = file.code.lastIndexOf('\n', match.range.first) + 1
                val before = file.code.substring(lineStart, match.range.first)
                if (declaration.containsMatchIn(before)) return@filter false
                val receiver = receiverName.find(before)?.groupValues?.get(1).orEmpty()
                val ownType = receiver.isNotEmpty() && receiver.first().isUpperCase() && receiver.any { it.isLowerCase() }
                if (ownType && receiver !in localeFormatters) return@filter false
                val first = file.arguments(match.range.last).firstOrNull()
                first == null || !localeFirst.containsMatchIn(file.code.substring(first.first, first.last + 1))
            }.map { it.range.first }.toList()
        }
    }

    private fun inPackage(file: SourceFile, pkg: String) =
        file.packageName == pkg || file.packageName.startsWith("$pkg.")
}
