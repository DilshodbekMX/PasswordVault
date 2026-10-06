package io.github.dilshodbekmx.passwordvault.core.arch

import io.github.dilshodbekmx.passwordvault.testing.arch.PatternRule
import io.github.dilshodbekmx.passwordvault.testing.arch.SourceFile
import io.github.dilshodbekmx.passwordvault.testing.arch.scan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The scanner of the build checks: comments are ignored, everything else is seen. */
class SourceScanTest {
    private val time = PatternRule("T", "time", Regex("""\bjava\.time\b"""))

    private fun lines(path: String, text: String) = scan(listOf(SourceFile(path, text)), listOf(time)).map { it.line }

    @Test
    fun lineAndBlockCommentsAreIgnored() {
        val text = """
            // java.time in a line comment
            /* java.time in a block comment */
            /** java.time in KDoc
             *  java.time again */
            val a = 1 // java.time after code
            val b = java.time.Instant.now()
        """.trimIndent()
        assertEquals(listOf(6), lines("A.kt", text))
    }

    @Test
    fun kotlinBlockCommentsNestButJavaOnesDoNot() {
        val text = "/* a /* java.time */ java.time */ java.time"
        assertEquals("Kotlin: only the last one is code", 1, lines("A.kt", text).size)
        assertTrue(SourceFile("A.kt", text).code.trimEnd().endsWith("java.time"))
        assertEquals("Java: the comment ends at the first */", 2, lines("A.java", text).size)
    }

    @Test
    fun commentMarkersInsideLiteralsAreText() {
        val text = """
            val url = "https://example.com"; val t = java.time.Instant.now()
            val s = "/*"; val u = java.time.Instant.now()
            val c = '"'; // java.time
            val r = ""${'"'}a "quoted" // not a comment""${'"'}; val v = java.time.Instant.now()
        """.trimIndent()
        assertEquals(listOf(1, 2, 4), lines("A.kt", text))
    }

    @Test
    fun templatesAreCodeAgain() {
        val text = "val s = \"a \${ f(\"}\") /* java.time */ } b\"; val t = java.time.Instant.now()"
        assertEquals(listOf(1), lines("A.kt", text))
        val file = SourceFile("A.kt", text)
        assertEquals(text.indexOf("java.time.Instant"), file.code.indexOf("java.time"))
    }

    @Test
    fun javaTextBlocksAreLiterals() {
        val text = "String s = \"\"\"\n  a /* b\n  \"\"\";\nvar t = java.time.Instant.now();"
        assertEquals(listOf(4), lines("A.java", text))
    }

    @Test
    fun offsetsAndLinesSurviveBlanking() {
        val text = "/* one\n two */\nval x = java.time.Instant.now()\n"
        val file = SourceFile("A.kt", text)
        assertEquals(text.length, file.code.length)
        assertEquals(3, file.lineOf(file.code.indexOf("java.time")))
        assertEquals("val x = java.time.Instant.now()", file.lineText(3))
    }

    @Test
    fun literalsAreKnown() {
        val file = SourceFile("A.kt", """Text("Hi"); Text(name); Text(if (a) "x" else "y"); Text(stringResource(R.string.x, "arg"))""")
        val calls = Regex("""Text\(""").findAll(file.code).map { file.arguments(it.range.last).single() }.toList()
        assertEquals(listOf(true, false, true, false), calls.map { file.hasTopLevelLiteral(it) })
    }

    @Test
    fun argumentsSplitAtTopLevelCommasOnly() {
        val code = """f(a, g(b, c), "x,y", { p, q -> p }, list[1, 2])"""
        val file = SourceFile("A.kt", code)
        val args = file.arguments(code.indexOf('(')).map { code.substring(it.first, it.last + 1) }
        assertEquals(listOf("a", "g(b, c)", "\"x,y\"", "{ p, q -> p }", "list[1, 2]"), args)
        assertEquals(emptyList<IntRange>(), SourceFile("A.kt", "f( )").arguments(1))
    }

    @Test
    fun theFormatRuleWantsALocaleFirst() {
        val text = """
            val a = "%d".format(n)
            val b = String.format("%d", n)
            val c = "%d".format(Locale.ROOT, n)
            val d = String.format(java.util.Locale.ROOT, "%d", n)
            val e = String.format(locale, "%d", n)
            fun String.format(x: Int) = this
            val f = "%d".format(appLocale, n)
            // val g = "%d".format(n)
            val h = ".format(n)"
        """.trimIndent()
        val found = scan(listOf(SourceFile("A.kt", text)), listOf(CoreSourceRules.formatWithLocale)).map { it.line }
        assertEquals(listOf(1, 2), found)
    }

    @Test
    fun theAndroidRuleSeesImportsAndFullNamesOnly() {
        val text = """
            package io.github.dilshodbekmx.passwordvault.core.util
            import android.util.Log
            // import androidx.core.Foo
            val x = androidx.core.Bar()
            val myandroid = 1
        """.trimIndent()
        val found = scan(listOf(SourceFile("A.kt", text)), listOf(CoreSourceRules.noAndroid)).map { it.line }
        assertEquals(listOf(2, 4), found)
    }

    @Test
    fun theLockLayerRuleAppliesToTheLockOnly() {
        val lock = SourceFile("L.kt", "package io.github.dilshodbekmx.passwordvault.core.lock.flow\nimport io.github.dilshodbekmx.passwordvault.core.storage.VaultStore\n")
        val other = SourceFile("O.kt", "package io.github.dilshodbekmx.passwordvault.core.model\nimport io.github.dilshodbekmx.passwordvault.core.storage.VaultStore\n")
        val lockalike = SourceFile("P.kt", "package io.github.dilshodbekmx.passwordvault.core.lockers\nimport io.github.dilshodbekmx.passwordvault.core.vaultfile.VaultFile\n")
        val found = scan(listOf(lock, other, lockalike), listOf(CoreSourceRules.lockLayers)).map { it.path }
        assertEquals(listOf("L.kt"), found)
    }

    @Test
    fun theLoggingRulesLeaveMemberCallsAlone() {
        val text = "println(x)\nwriter.println(x)\nSystem.err.write(1)\ne.printStackTrace()\nprint (x)\n"
        val found = scan(listOf(SourceFile("A.kt", text)), CoreSourceRules.noLogging).map { it.line }
        assertEquals(listOf(1, 3, 4, 5), found)
        assertFalse(found.contains(2))
    }
}
