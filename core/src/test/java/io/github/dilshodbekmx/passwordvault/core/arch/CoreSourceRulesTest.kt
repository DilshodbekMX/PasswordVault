package io.github.dilshodbekmx.passwordvault.core.arch

import io.github.dilshodbekmx.passwordvault.core.arch.CoreSourceRules.CORE_MAIN
import io.github.dilshodbekmx.passwordvault.core.arch.CoreSourceRules.TESTING_MAIN
import io.github.dilshodbekmx.passwordvault.testing.arch.Rule
import io.github.dilshodbekmx.passwordvault.testing.arch.SourceTree
import io.github.dilshodbekmx.passwordvault.testing.arch.describe
import io.github.dilshodbekmx.passwordvault.testing.arch.scan
import org.junit.Assert.assertTrue
import org.junit.Test

/** Build checks R-6, R-10 and R-11 (the :core part) of architecture.md §8.5 on the real sources. */
class CoreSourceRulesTest {
    private val root = SourceTree.root()

    private fun assertClean(dirs: List<String>, rules: List<Rule>) {
        val findings = scan(SourceTree.files(root, dirs), rules)
        assertTrue(describe(findings), findings.isEmpty())
    }

    @Test
    fun r10_coreAndTestingDoNotReachAndroid() = assertClean(listOf(CORE_MAIN, TESTING_MAIN), listOf(CoreSourceRules.noAndroid))

    @Test
    fun r10_theLockDoesNotReachStorageOrTheVaultFile() = assertClean(listOf(CORE_MAIN), listOf(CoreSourceRules.lockLayers))

    @Test
    fun r6_coreDoesNotLog() = assertClean(listOf(CORE_MAIN), CoreSourceRules.noLogging)

    @Test
    fun r11_coreFormatsWithALocale() = assertClean(listOf(CORE_MAIN), listOf(CoreSourceRules.formatWithLocale))

    @Test
    fun theScansSeeTheSources() {
        // Guards against scanning the wrong place: the scanner itself lives in testing/src/main.
        val files = SourceTree.files(root, listOf(TESTING_MAIN)).map { it.path }
        assertTrue(files.toString(), files.any { it.endsWith("/testing/arch/SourceScan.kt") })
    }
}
