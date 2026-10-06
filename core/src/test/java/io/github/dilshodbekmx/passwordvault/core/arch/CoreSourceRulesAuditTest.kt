package io.github.dilshodbekmx.passwordvault.core.arch

import io.github.dilshodbekmx.passwordvault.testing.arch.SourceTree
import io.github.dilshodbekmx.passwordvault.testing.arch.scan
import io.github.dilshodbekmx.passwordvault.testing.arch.summarize
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The scans of [CoreSourceRules] tried on code that is not built (`prototypes/`, the lab
 * artifacts), every rule on every file: they must report exactly the lines listed below. Runs only
 * with `-Ppv.scanAudit`:
 *
 *     tools/gradle.sh :core:testDebugUnitTest --tests '*SourceRulesAuditTest' -Ppv.scanAudit
 *
 * The list was checked line by line against a plain per-line search of the same files (build plan
 * 0.1): every line it names holds a match in code, and every other line with a match has it only
 * in a comment or a string literal, after a Locale argument, or in a call on our own type
 * (`IsoTime.format`). Lines of files that no longer exist (prototypes move into the modules in
 * later waves) are dropped.
 */
class CoreSourceRulesAuditTest {
    @Test
    fun theScansReportWhatTheyShouldAndNothingElse() {
        assumeTrue("run with -Ppv.scanAudit", System.getProperty("pv.scanAudit") != null)
        val root = SourceTree.root()
        val rules = listOf(CoreSourceRules.noAndroid, CoreSourceRules.lockLayers, CoreSourceRules.formatWithLocale) +
            CoreSourceRules.noLogging
        val actual = summarize(scan(SourceTree.files(root, SourceTree.auditDirs(root)), rules))
        println(actual.joinToString("\n"))
        val expected = EXPECTED.filter { File(root, it.substringBefore(" | ")).isFile }
        assertEquals(expected.joinToString("\n"), actual.joinToString("\n"))
    }

    private companion object {
        val EXPECTED = listOf(
            "docs/lab/biometric-artifacts/BiometricKeyVault.kt | R-10 android or androidx in :core or :testing | 3-10",
            "docs/lab/biometric-artifacts/BiometricVaultKey.kt | R-10 android or androidx in :core or :testing | 3-12",
            "docs/lab/biometric-artifacts/LabActivity.kt | R-10 android or androidx in :core or :testing | 3-43 79",
            "docs/lab/biometric-artifacts/LabActivity.kt | R-11 String.format or .format( without a Locale in :core | 93",
            "docs/lab/clipboard-artifacts/SensitiveClipboard.kt | R-10 android or androidx in :core or :testing | 3-15",
            "docs/lab/old-android-artifacts/AtomicFiles.kt | R-10 android or androidx in :core or :testing | 3-4",
            "docs/lab/old-android-artifacts/BiometricKey.kt | R-10 android or androidx in :core or :testing | 3-6",
            "docs/lab/old-android-artifacts/Kdf.kt | R-11 String.format or .format( without a Locale in :core | 52",
            "docs/lab/old-android-artifacts/MainActivity.kt | R-10 android or androidx in :core or :testing | 3-51 196 199",
            "docs/lab/old-android-artifacts/Probes.kt | R-10 android or androidx in :core or :testing | 3-10 144 146 148 150 271 300",
            "docs/lab/old-android-artifacts/Probes.kt | R-11 String.format or .format( without a Locale in :core | 240",
            "docs/lab/old-android-artifacts/SensitiveClipboard.kt | R-10 android or androidx in :core or :testing | 3-7",
            "docs/lab/window-input-artifacts/LabA11yService.kt | R-10 android or androidx in :core or :testing | 3-7",
            "docs/lab/window-input-artifacts/LabAutofillService.kt | R-10 android or androidx in :core or :testing | 3-15",
            "docs/lab/window-input-artifacts/MainActivity.kt | R-10 android or androidx in :core or :testing | 3-80 117",
            "docs/lab/window-input-artifacts/OptInProbe.kt | R-10 android or androidx in :core or :testing | 6-13",
            "docs/lab/window-input-artifacts/OverlayReceiver.kt | R-10 android or androidx in :core or :testing | 3-13",
            "docs/lab/window-input-artifacts/VaultDemoActivity.kt | R-10 android or androidx in :core or :testing | 3-43 84",
            "docs/lab/window-input-artifacts/VaultSecurity.kt | R-10 android or androidx in :core or :testing | 3-25",
            "docs/lab/window-input-artifacts/WindowSafety.kt | R-10 android or androidx in :core or :testing | 3-21 126",
            "prototypes/formats/attack/attack/Attack.kt | R-6 println or print | 47 67 78-79 81-82 85-86 114 122-123 125 133 136-138 146-148 153-154 163 170-172 174 176 178 195 209-210 216 225 228 238-239 241 246 248 253 257 263 265 283 294 297 300 311 323 326",
            "prototypes/formats/attack/attack/Attack2.kt | R-6 println or print | 26 42 48 55 57 66 68 75 85",
            "prototypes/formats/attack/attack3/Nest.kt | R-6 println or print | 19",
            "prototypes/formats/attack/attack3/Nest2.kt | R-6 println or print | 16",
            "prototypes/formats/attack/attack3/Report.kt | R-11 String.format or .format( without a Locale in :core | 12",
            "prototypes/formats/attack/attack3/Report.kt | R-6 println or print | 8 14",
            "prototypes/formats/attack/attack3/Shapes.kt | R-6 println or print | 13-14",
            "prototypes/formats/test/FormatsTest.kt | R-11 String.format or .format( without a Locale in :core | 17 67 173-174",
            "prototypes/formats/test/FormatsTest.kt | R-6 println or print | 13 87 117 173 181 201 208 228 235",
            "prototypes/formats/test/ImportTool.kt | R-6 println or print | 9 11-12",
            "prototypes/formats/validate/PoiCheck.java | R-11 String.format or .format( without a Locale in :core | 58",
            "prototypes/formats/validate/PoiCheck.java | R-6 System.out or System.err | 19 21 44",
            "prototypes/search/src/test/kotlin/io/github/dilshodbekmx/passwordvault/search/SearchBenchmark.kt | R-6 println or print | 72",
            "prototypes/search/src/test/kotlin/io/github/dilshodbekmx/passwordvault/text/ConverterEval.kt | R-6 println or print | 97 99 108",
            "prototypes/search/src/test/kotlin/io/github/dilshodbekmx/passwordvault/text/UzbekCyrillicStrings.kt | R-6 println or print | 19",
        )
    }
}
