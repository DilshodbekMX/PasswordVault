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
 * **Missing translations are a warning until wave 6**: the test prints them (they are in its report,
 * `app/build/test-results/testDebugUnitTest/TEST-…ResourcesTest.xml`) and passes, because the
 * translations come in wave 6 (build plan 6.3, 6.4). At the integration check of wave 6 the
 * integrator sets [MISSING_IS_FAILURE] to true (build plan §1 "The integrator"). Until then
 * `-Ppv.strictResources` makes them failures for one run, for example for a translation package that
 * checks its own directory:
 *
 * ```
 * tools/gradle.sh :app:testDebugUnitTest --tests '*.i18n.ResourcesTest' -Ppv.strictResources
 * ```
 *
 * Every other finding (placeholders, plural quantities, apostrophes, scripts, quotes, extra names) is
 * a failure now.
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
            if (strict()) problems += text else println("WARNING (a failure from wave 6 on): $text")
        }
        if (problems.isNotEmpty()) fail("${language.dir}:\n- " + problems.joinToString("\n- "))
    }

    companion object {
        /** False until the integration check of wave 6, where the integrator sets it to true. */
        const val MISSING_IS_FAILURE = false

        /** Whether a missing translation fails this run: [MISSING_IS_FAILURE], or `-Ppv.strictResources` (not `=false`). */
        fun strict(): Boolean = MISSING_IS_FAILURE || System.getProperty("pv.strictResources")?.let { it != "false" } == true
    }
}
