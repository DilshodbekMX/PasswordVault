package io.github.dilshodbekmx.passwordvault.i18n

import io.github.dilshodbekmx.passwordvault.i18n.ResourceCheck.Language
import io.github.dilshodbekmx.passwordvault.testing.arch.SourceTree
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Build plan 2.9, [JVM]: the resource checks of i18n.md §5 item 2 ([ResourceCheck]) on the app's own
 * strings, one test per language (architecture.md §8.2).
 *
 * **A missing translation is a failure** since the merge of round R6 (build plan §8, "Integration
 * check for wave 6"), like every other finding (placeholders, plural quantities, apostrophes,
 * scripts, quotes, extra names): every string and plural of `values/` that is translatable is in
 * all three translations, and a package that adds one adds it in all four languages (build plan §1
 * rule 5). Until then missing ones were only printed, because the translations came in wave 6
 * (build plan 6.3, 6.4); `-Ppv.strictResources` made them failures for one run, and it is still
 * accepted (`-Ppv.strictResources=false` no longer turns the failure off).
 */
class ResourcesTest {
    private val res = File(SourceTree.root(), "app/src/main/res")

    @Test
    fun english() = check(Language.EN)

    @Test
    fun uzbekLatin() = check(Language.UZ_LATN)

    @Test
    fun uzbekCyrillic() = check(Language.UZ_CYRL)

    @Test
    fun russian() = check(Language.RU)

    private fun check(language: Language) {
        val report = ResourceCheck.check(res, language)
        println(report)
        val problems = report.failures.toMutableList()
        if (report.missing.isNotEmpty()) {
            val text = "${language.dir} lacks ${report.missing.size} of the ${report.checked} translatable names of values/: " +
                report.missing.joinToString(", ")
            if (strict()) problems += text else println("WARNING: $text")
        }
        if (problems.isNotEmpty()) fail("${language.dir}:\n- " + problems.joinToString("\n- "))
    }

    companion object {
        /** True since the merge of round R6 (build plan §8, "Integration check for wave 6"); false before. */
        const val MISSING_IS_FAILURE = true

        /** Whether a missing translation fails this run: [MISSING_IS_FAILURE], or `-Ppv.strictResources` (not `=false`). */
        fun strict(): Boolean = MISSING_IS_FAILURE || System.getProperty("pv.strictResources")?.let { it != "false" } == true
    }
}
