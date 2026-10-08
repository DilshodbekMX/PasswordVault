package io.github.dilshodbekmx.passwordvault.files

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Build plan 5.1, [device], on API 24 and 37: [SafDocumentIo] against a documents provider in
 * another process (the test APK's, [TestDocumentsProvider]), with the permissions a picker grants.
 * Write and read back; a provider that writes 0 bytes, cuts the file, changes a byte or refuses to
 * write is caught [V-S6]; delete where the place allows it, and not where it does not; a document
 * that cannot be read is an IOException; keep takes the persistable permission.
 */
@RunWith(AndroidJUnit4::class)
class SafDocumentIoTest {
    private val io = SafDocumentIo(DocumentsRig.app, Dispatchers.IO)
    private val made = mutableListOf<DocRef>()

    @After
    fun releasePermissions() {
        made.forEach(DocumentsRig::release)
    }

    /** A new document of [behaviour] holding [content], granted to the app as a picker grants it. */
    private fun document(behaviour: String, content: ByteArray = ByteArray(0)): Pair<String, DocRef> {
        val id = DocumentsRig.newId(behaviour)
        val doc = DocumentsRig.newDocument(id, content)
        made += doc
        return id to doc
    }

    /** What the provider holds for [id], once [settled] holds for it: a broken provider stores after the writer closed. */
    private fun storedOnce(id: String, settled: (ByteArray?) -> Boolean): ByteArray? {
        var bytes: ByteArray? = null
        DocumentsRig.await("the provider's copy of $id", 5_000) {
            bytes = DocumentsRig.stored(id)
            settled(bytes)
        }
        return bytes
    }

    @Test
    fun theAppsDocumentIoIsSafDocumentIo() {
        assertTrue(AppVault.graph.files is SafDocumentIo)
    }

    @Test
    fun writeAndReadBack() = runBlocking {
        val (id, doc) = document(TestDocuments.PLAIN)
        val bytes = payload(100_003)
        assertTrue("written and read back", io.write(doc, bytes))
        assertArrayEquals(bytes, io.read(doc, bytes.size))
        assertArrayEquals("the provider holds what was written", bytes, DocumentsRig.stored(id))

        // "wt": a shorter content leaves nothing of the longer one behind.
        val shorter = payload(17, seed = 5)
        assertTrue(io.write(doc, shorter))
        assertArrayEquals(shorter, io.read(doc, 1_000))
        assertArrayEquals(shorter, DocumentsRig.stored(id))

        // At most maxBytes + 1 bytes, so that the caller can tell "too long".
        assertEquals(11, io.read(doc, 10).size)
        assertEquals(17, io.read(doc, 17).size)
        assertEquals(1, io.read(doc, 0).size)

        // Nothing at all is a content too.
        assertTrue(io.write(doc, ByteArray(0)))
        assertEquals(0, io.read(doc, 10).size)
    }

    @Test
    fun aProviderThatWritesZeroBytesIsCaught() = runBlocking {
        val (id, doc) = document(TestDocuments.ZERO)
        assertFalse("the provider took every byte and stored none", io.write(doc, payload(4_096)))
        assertEquals(0, storedOnce(id) { it != null }!!.size)
    }

    @Test
    fun aProviderThatCutsTheFileIsCaught() = runBlocking {
        val (id, doc) = document(TestDocuments.CUT)
        val bytes = payload(10_000)
        assertFalse("the provider stored half of it", io.write(doc, bytes))
        val kept = storedOnce(id) { it != null && it.isNotEmpty() }!!
        assertArrayEquals(bytes.copyOf(5_000), kept)
    }

    @Test
    fun aProviderThatChangesAByteIsCaught() = runBlocking {
        val (id, doc) = document(TestDocuments.FLIP)
        val bytes = payload(3_001)
        assertFalse("same length, one byte changed: the hashes differ", io.write(doc, bytes))
        val kept = storedOnce(id) { it != null && it.isNotEmpty() }!!
        assertEquals(bytes.size, kept.size)
        assertFalse(bytes.contentEquals(kept))
    }

    @Test
    fun aProviderThatRefusesToWriteIsCaught() = runBlocking {
        val (_, doc) = document(TestDocuments.NO_WRITE)
        assertFalse(io.write(doc, payload(100)))
    }

    @Test
    fun deleteWhereAllowed() = runBlocking {
        val (id, doc) = document(TestDocuments.PLAIN, payload(10))
        assertTrue("the place allows deleting", io.delete(doc))
        assertNull("gone from the provider", DocumentsRig.stored(id))
        try {
            io.read(doc, 10)
            fail("a deleted document cannot be read")
        } catch (e: IOException) {
            // expected
        }
    }

    @Test
    fun notWhereThePlaceDoesNotAllowIt() = runBlocking {
        val (id, doc) = document(TestDocuments.NO_DELETE, payload(10))
        assertFalse("the place does not allow deleting", io.delete(doc))
        assertNotNull("still there", DocumentsRig.stored(id))
        assertArrayEquals(payload(10), io.read(doc, 100))
    }

    @Test
    fun aDocumentThatCannotBeReadIsAnIOException() = runBlocking {
        val (_, doc) = document(TestDocuments.NO_READ, payload(10))
        try {
            io.read(doc, 100)
            fail("IOException expected")
        } catch (e: IOException) {
            // expected
        }
        assertFalse("written, but the read-back fails", io.write(doc, payload(10)))
    }

    @Test
    fun aDocumentWithoutPermissionIsAnIOException() = runBlocking {
        // Made, but never granted: the app may neither read, write nor delete it.
        val doc = DocumentsRig.docOf(DocumentsRig.newId(TestDocuments.PLAIN))
        try {
            io.read(doc, 100)
            fail("IOException expected")
        } catch (e: IOException) {
            // expected: a SecurityException underneath
        }
        assertFalse(io.write(doc, payload(10)))
        assertFalse(io.delete(doc))
    }

    @Test
    fun keepTakesTheGrantedPermissionsForGood() {
        val (_, doc) = document(TestDocuments.PLAIN)
        assertNull(DocumentsRig.persisted(doc))
        io.keep(doc)
        assertEquals("read and write, kept", true to true, DocumentsRig.persisted(doc))
    }

    @Test
    fun theProvidersNamesAreShown() {
        val (_, doc) = document(TestDocuments.PLAIN)
        assertEquals(Place.Other("PV test documents"), io.place(doc))
        assertEquals(doc.uri.substringAfterLast('/') + ".bin", io.name(doc))
    }

    private fun payload(size: Int, seed: Int = 1): ByteArray = ByteArray(size) { ((it * 31 + seed * 7) xor (it ushr 8)).toByte() }
}
