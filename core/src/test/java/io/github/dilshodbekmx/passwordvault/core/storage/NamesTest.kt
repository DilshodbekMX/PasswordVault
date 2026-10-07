package io.github.dilshodbekmx.passwordvault.core.storage

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The names of architecture.md §4.7, with ASCII digits whatever the locale (§4, R-11). */
class NamesTest {
    @Test
    fun generationNamesHaveTenAsciiDigits() {
        assertEquals("vault-0000000001.pvault", Names.generation(1, Names.PVAULT))
        assertEquals("vault-0000000057.tmp", Names.generation(57, Names.TMP))
        assertEquals("vault-9999999999.bad", Names.generation(Names.MAX_GENERATION, Names.BAD))
        assertEquals("vault-0000000012.aside", Names.generation(12, Names.ASIDE))
        try {
            Names.generation(Names.MAX_GENERATION + 1, Names.PVAULT)
            fail("a number of eleven digits")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun everyNameStartingWithTenDigitsCountsForTheNextNumber() {
        // §4.9 step 2 [V-S1]: whatever the suffix.
        assertEquals(12L, Names.numberOf("vault-0000000012.pvault"))
        assertEquals(12L, Names.numberOf("vault-0000000012.bad"))
        assertEquals(12L, Names.numberOf("vault-0000000012"))
        assertEquals(12L, Names.numberOf("vault-0000000012.something-new"))
        assertNull(Names.numberOf("vault-000000012.pvault")) // nine digits
        assertNull(Names.numberOf("Vault-0000000012.pvault"))
        assertNull(Names.numberOf("notes.txt"))
        assertNull(Names.numberOf("vault-00000000x2.pvault"))
        // Digits of other scripts are not ASCII digits: a Persian 12 is not a number here.
        assertNull(Names.numberOf("vault-۰۰۰۰۰۰۰۰۱۲.pvault"))
    }

    @Test
    fun aLiveGenerationIsExactlyTenDigitsAndItsSuffix() {
        assertEquals(3L, Names.numberOf("vault-0000000003.pvault", Names.PVAULT))
        assertNull(Names.numberOf("vault-0000000003.pvault", Names.TMP))
        assertNull(Names.numberOf("vault-0000000003.pvault.bak", Names.PVAULT))
        assertNull(Names.numberOf("vault-00000000033.pvault", Names.PVAULT))
        assertEquals(3L, Names.numberOf("vault-0000000003.tmp", Names.TMP))
    }

    @Test
    fun directoriesPutAsideCarryTheTimeAndTheVaultId() {
        val name = Names.asideDir(1_791_244_800_000L, id(7))
        assertEquals("aside-1791244800000-${id(7)}", name)
        val read = Names.parseAsideDir(name)!!
        assertEquals(1_791_244_800_000L, read.putAsideAtMs)
        assertEquals(id(7), read.vaultId)
        assertEquals("aside-0000000000000-${id(7)}", Names.asideDir(-5L, id(7))) // a clock before 1970
        assertEquals("aside-9999999999999-${id(7)}", Names.asideDir(Long.MAX_VALUE, id(7)))
        assertNull(Names.parseAsideDir("aside-179124480000-${id(7)}")) // twelve digits
        assertNull(Names.parseAsideDir("aside-1791244800000-$UPPER_CASE_ID"))
        assertNotNull(Names.parseAsideDir("aside-1791244800000-${UPPER_CASE_ID.lowercase(Locale.ROOT)}"))
        assertNull(Names.parseAsideDir("aside-1791244800000_${id(7)}"))
        assertNull(Names.parseAsideDir("live"))
        try {
            Names.asideDir(1L, "../../x")
            fail("a vault id becomes a directory name: nothing else is accepted")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun newDirectories() {
        assertEquals("new-${id(3)}", Names.newDir(id(3)))
        assertTrue(Names.isNewDir("new-${id(3)}"))
        assertFalse(Names.isNewDir("new-foo"))
        assertFalse(Names.isNewDir("new-"))
        try {
            Names.newDir("../live")
            fail("not a vault id")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun theDefaultLocaleChangesNoName() {
        val saved = Locale.getDefault()
        try {
            for (tag in listOf("fa-IR", "ar-EG", "tr-TR", "hi-IN-u-nu-deva")) {
                Locale.setDefault(Locale.forLanguageTag(tag))
                assertEquals(tag, "vault-0000001234.pvault", Names.generation(1234, Names.PVAULT))
                assertEquals(tag, 1234L, Names.numberOf("vault-0000001234.pvault"))
                assertEquals(tag, "aside-0000000000042-${id(1)}", Names.asideDir(42, id(1)))
            }
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test
    fun paths() {
        assertEquals("/a/b/c", Names.child("/a/b", "c"))
        assertEquals("/a/b", Names.parent("/a/b/c"))
        assertEquals("/", Names.parent("/a"))
        assertEquals("c", Names.nameOf("/a/b/c"))
        assertEquals("/v/vault-0000000002.bad", Names.withSuffix("/v/vault-0000000002.tmp", Names.TMP, Names.BAD))
    }
}
