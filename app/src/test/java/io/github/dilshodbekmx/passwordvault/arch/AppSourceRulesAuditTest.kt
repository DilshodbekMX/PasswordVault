package io.github.dilshodbekmx.passwordvault.arch

import io.github.dilshodbekmx.passwordvault.testing.arch.SourceTree
import io.github.dilshodbekmx.passwordvault.testing.arch.scan
import io.github.dilshodbekmx.passwordvault.testing.arch.summarize
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The scans of [AppSourceRules] tried on code that is not built (`prototypes/`, the lab
 * artifacts), every rule on every file whatever its directory: they must report exactly the lines
 * listed below. Runs only with `-Ppv.scanAudit`:
 *
 *     tools/gradle.sh :app:testDebugUnitTest --tests '*SourceRulesAuditTest' -Ppv.scanAudit
 *
 * The list was checked line by line against a plain per-line search of the same files (build plan
 * 0.1): every line it names holds a match in code, and every other line with a match has it only
 * in a comment or a string literal, or passes no literal to `Text(`. The offline scan reports
 * nothing here: no prototype or lab file opens a connection, and `java.net.IDN` in `WebAddress.kt`
 * is allowed. Lines of files that no longer exist (prototypes move into the modules in later
 * waves) are dropped.
 * The lines of `docs/lab/clipboard-artifacts/kit/` (package 0.3) were added at the wave-0
 * integration, checked the same way. With `-Ppv.scanAudit` the audited files are inputs of the
 * test task (root build.gradle.kts), so a cached result never stands for changed files.
 */
class AppSourceRulesAuditTest {
    @Test
    fun theScansReportWhatTheyShouldAndNothingElse() {
        assumeTrue("run with -Ppv.scanAudit", System.getProperty("pv.scanAudit") != null)
        val root = SourceTree.root()
        val rules = with(AppSourceRules) { r4 + r6 + r11Apis + r11SavedState + r13Calls + r13Literals + r15 + offline }
        val actual = summarize(scan(SourceTree.files(root, SourceTree.auditDirs(root)), rules))
        println(actual.joinToString("\n"))
        val expected = EXPECTED.filter { File(root, it.substringBefore(" | ")).isFile }
        assertEquals(expected.joinToString("\n"), actual.joinToString("\n"))
    }

    private companion object {
        val EXPECTED = listOf(
            "docs/lab/biometric-artifacts/LabActivity.kt | R-13 Text( with a string literal | 148 150-152 155-156",
            "docs/lab/biometric-artifacts/LabActivity.kt | R-6 Log.<level>( | 63",
            "docs/lab/biometric-artifacts/LabActivity.kt | R-6 android.util.Log | 17",
            "docs/lab/clipboard-artifacts/kit/project/app/src/main/java/com/example/pvlab/clip/IoTest.kt | R-6 Log.<level>( | 23",
            "docs/lab/clipboard-artifacts/kit/project/app/src/main/java/com/example/pvlab/clip/IoTest.kt | R-6 android.util.Log | 8",
            "docs/lab/clipboard-artifacts/kit/project/app/src/main/java/com/example/pvlab/clip/Lab.kt | R-6 Log.<level>( | 71",
            "docs/lab/clipboard-artifacts/kit/project/app/src/main/java/com/example/pvlab/clip/Lab.kt | R-6 android.util.Log | 18",
            "docs/lab/old-android-artifacts/Kdf.kt | R-11 rawHashAsByteArray | 30",
            "docs/lab/old-android-artifacts/MainActivity.kt | R-13 Text( with a string literal | 330-331 345 353 361",
            "docs/lab/old-android-artifacts/MainActivity.kt | R-13 a text field outside VaultTextField, VaultSecureField and VaultSearchField | 355",
            "docs/lab/old-android-artifacts/MainActivity.kt | R-13 contentDescription = a string literal | 348-350",
            "docs/lab/old-android-artifacts/MainActivity.kt | R-6 Log.<level>( | 77 87 359",
            "docs/lab/old-android-artifacts/MainActivity.kt | R-6 android.util.Log | 10",
            "docs/lab/old-android-artifacts/Probes.kt | R-11 AtomicFile | 300",
            "docs/lab/old-android-artifacts/Probes.kt | R-11 Files.move | 293",
            "docs/lab/old-android-artifacts/Probes.kt | R-11 java.time | 277",
            "docs/lab/old-android-artifacts/Probes.kt | R-11 java.util.Base64 | 269",
            "docs/lab/window-input-artifacts/LabA11yService.kt | R-6 Log.<level>( | 20 36 42",
            "docs/lab/window-input-artifacts/LabA11yService.kt | R-6 android.util.Log | 5",
            "docs/lab/window-input-artifacts/LabAutofillService.kt | R-6 Log.<level>( | 28 32 42 53 62 69 76 83 119",
            "docs/lab/window-input-artifacts/LabAutofillService.kt | R-6 android.util.Log | 13",
            "docs/lab/window-input-artifacts/MainActivity.kt | R-11 Toast | 12 220",
            "docs/lab/window-input-artifacts/MainActivity.kt | R-11 rememberSaveable outside nav | 62 207",
            "docs/lab/window-input-artifacts/MainActivity.kt | R-11 rememberTextFieldState | 31 201 204",
            "docs/lab/window-input-artifacts/MainActivity.kt | R-13 Text( with a string literal | 249-250 260-261 265 267-269 271 273-274 277 280 283 285-287 291 297 303 309 316 321 331-333 341 344 350 353 362 365 369",
            "docs/lab/window-input-artifacts/MainActivity.kt | R-13 a dialog or sheet outside VaultDialog and VaultSheet | 329 335 358",
            "docs/lab/window-input-artifacts/MainActivity.kt | R-13 a text field outside VaultTextField, VaultSecureField and VaultSearchField | 289 295 301 307 314 319 342 348 363",
            "docs/lab/window-input-artifacts/MainActivity.kt | R-6 Log.<level>( | 87 91 102 108 119 125 144 148-151 153 179 183 188-190 214 226 263",
            "docs/lab/window-input-artifacts/MainActivity.kt | R-6 android.util.Log | 7",
            "docs/lab/window-input-artifacts/OverlayReceiver.kt | R-6 Log.<level>( | 37 41 71",
            "docs/lab/window-input-artifacts/OverlayReceiver.kt | R-6 android.util.Log | 9",
            "docs/lab/window-input-artifacts/VaultDemoActivity.kt | R-13 Text( with a string literal | 95 99 101-103 107 113 119 134 137 143 146 156 159 163",
            "docs/lab/window-input-artifacts/VaultDemoActivity.kt | R-13 a dialog or sheet outside VaultDialog and VaultSheet | 125 151",
            "docs/lab/window-input-artifacts/VaultDemoActivity.kt | R-13 a text field outside VaultTextField, VaultSecureField and VaultSearchField | 105 111 117 135 141 157",
            "docs/lab/window-input-artifacts/VaultDemoActivity.kt | R-6 Log.<level>( | 72 77 97",
            "docs/lab/window-input-artifacts/VaultDemoActivity.kt | R-6 android.util.Log | 4",
            "docs/lab/window-input-artifacts/WindowSafety.kt | R-6 Log.<level>( | 42 102 104 111 134 137",
            "docs/lab/window-input-artifacts/WindowSafety.kt | R-6 android.util.Log | 4",
            "prototypes/formats/attack/attack/Attack.kt | R-6 println or print | 47 67 78-79 81-82 85-86 114 122-123 125 133 136-138 146-148 153-154 163 170-172 174 176 178 195 209-210 216 225 228 238-239 241 246 248 253 257 263 265 283 294 297 300 311 323 326",
            "prototypes/formats/attack/attack/Attack2.kt | R-6 println or print | 26 42 48 55 57 66 68 75 85",
            "prototypes/formats/attack/attack3/Nest.kt | R-6 println or print | 19",
            "prototypes/formats/attack/attack3/Nest2.kt | R-6 println or print | 16",
            "prototypes/formats/attack/attack3/Report.kt | R-6 println or print | 8 14",
            "prototypes/formats/attack/attack3/Shapes.kt | R-6 println or print | 13-14",
            "prototypes/formats/test/FormatsTest.kt | R-6 println or print | 13 87 117 173 181 201 208 228 235",
            "prototypes/formats/test/ImportTool.kt | R-6 println or print | 9 11-12",
            "prototypes/formats/validate/PoiCheck.java | R-6 System.out or System.err | 19 21 44",
            "prototypes/search/src/test/kotlin/io/github/dilshodbekmx/passwordvault/search/SearchBenchmark.kt | R-6 println or print | 72",
            "prototypes/search/src/test/kotlin/io/github/dilshodbekmx/passwordvault/text/ConverterEval.kt | R-6 println or print | 97 99 108",
            "prototypes/search/src/test/kotlin/io/github/dilshodbekmx/passwordvault/text/UzbekCyrillicStrings.kt | R-6 println or print | 19",
        )
    }
}
