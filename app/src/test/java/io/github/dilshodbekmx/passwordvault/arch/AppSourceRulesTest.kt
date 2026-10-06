package io.github.dilshodbekmx.passwordvault.arch

import io.github.dilshodbekmx.passwordvault.testing.arch.Rule
import io.github.dilshodbekmx.passwordvault.testing.arch.SourceTree
import io.github.dilshodbekmx.passwordvault.testing.arch.describe
import io.github.dilshodbekmx.passwordvault.testing.arch.scan
import org.junit.Assert.assertTrue
import org.junit.Test

/** Build checks R-4, R-6, R-11 and R-13 of architecture.md §8.5 on the real sources. */
class AppSourceRulesTest {
    private val root = SourceTree.root()

    private fun assertClean(dirs: List<String>, rules: List<Rule>) {
        val findings = scan(SourceTree.files(root, dirs), rules)
        assertTrue(describe(findings), findings.isEmpty())
    }

    @Test
    fun r4_onlyTheClassicBiometricApi() = assertClean(AppSourceRules.allSources, AppSourceRules.r4)

    @Test
    fun r6_theAppLogsOnlyInDebugAndStaging() = assertClean(AppSourceRules.noLoggingSets, AppSourceRules.r6)

    @Test
    fun r11_noForbiddenApi() = assertClean(AppSourceRules.allSources, AppSourceRules.r11Apis)

    @Test
    fun r11_savedStateOnlyInNav() = assertClean(AppSourceRules.allSources, AppSourceRules.r11SavedState)

    @Test
    fun r13_fieldsDialogsAndSheetsOnlyInTheirWrappers() = assertClean(AppSourceRules.appSets, AppSourceRules.r13Calls)

    @Test
    fun r13_noLiteralTextOnTheScreens() = assertClean(listOf(AppSourceRules.UI_DIR), AppSourceRules.r13Literals)

    @Test
    fun theScansSeeTheSources() {
        val paths = SourceTree.files(root, AppSourceRules.allSources).map { it.path }
        for (expected in listOf(
            "app/src/main/java/io/github/dilshodbekmx/passwordvault/MainActivity.kt",
            "app/src/debug/java/io/github/dilshodbekmx/passwordvault/debug/DebugCommandReceiver.kt",
            "testing/src/main/java/io/github/dilshodbekmx/passwordvault/testing/arch/SourceScan.kt",
        )) {
            assertTrue("$expected not scanned; scanned: $paths", expected in paths)
        }
    }
}
