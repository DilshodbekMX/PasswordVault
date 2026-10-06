package io.github.dilshodbekmx.passwordvault.core.arch

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The unit-test wiring of the build: `-Ppv.<name>` arrives as a system property, and
 * `-Ppv.testLocale=<tag>` is the JVM's default locale (tools/check.sh runs the tests of :core under
 * tr-TR and under fa-IR, architecture.md §8.1).
 */
class BuildWiringTest {
    private val tag: String? = System.getProperty("pv.testLocale")

    @Test
    fun theTestLocaleIsTheDefaultLocale() {
        assumeTrue("run with -Ppv.testLocale=<tag>", tag != null)
        assertEquals(Locale.forLanguageTag(tag!!), Locale.getDefault())
    }

    @Test
    fun turkishBreaksCaseFolding() {
        assumeTrue(tag == "tr-TR")
        assertEquals("ı", "I".lowercase(Locale.getDefault())) // dotless i
    }

    @Test
    fun persianWritesItsOwnDigits() {
        assumeTrue(tag == "fa-IR")
        assertEquals("۴۲", String.format(Locale.getDefault(), "%d", 42))
    }
}
