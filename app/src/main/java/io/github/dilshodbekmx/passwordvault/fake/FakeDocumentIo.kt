package io.github.dilshodbekmx.passwordvault.fake

import io.github.dilshodbekmx.passwordvault.files.DocRef
import io.github.dilshodbekmx.passwordvault.files.DocumentIo
import io.github.dilshodbekmx.passwordvault.files.Place
import java.io.IOException

/**
 * In-memory documents (architecture.md §2.2 `fake`): [files] by URI. Scripted with [writeResults]
 * (the next writes: false fails and stores nothing), [notDeletable], [places] and [names]; [kept]
 * lists the documents given a persistable permission. Single-threaded.
 */
class FakeDocumentIo : DocumentIo {
    /** The documents' bytes by URI. */
    val files = mutableMapOf<String, ByteArray>()

    /** The URIs [keep] was called for. */
    val kept = mutableSetOf<String>()

    /** The answers of the next writes; when empty, a write succeeds. */
    val writeResults = ArrayDeque<Boolean>()

    /** URIs whose place does not allow deleting. */
    val notDeletable = mutableSetOf<String>()

    /** The place of each URI; [Place.PhoneStorage] when not given. */
    val places = mutableMapOf<String, Place>()

    /** The name the place shows for each URI; null (it cannot be read) when not given. */
    val names = mutableMapOf<String, String>()

    override suspend fun write(doc: DocRef, bytes: ByteArray): Boolean {
        if (!(writeResults.removeFirstOrNull() ?: true)) return false
        files[doc.uri] = bytes.copyOf()
        return true
    }

    override suspend fun read(doc: DocRef, maxBytes: Int): ByteArray {
        val bytes = files[doc.uri] ?: throw IOException("no such document")
        return bytes.copyOf(minOf(bytes.size, maxBytes + 1))
    }

    override fun keep(doc: DocRef) {
        kept += doc.uri
    }

    override fun delete(doc: DocRef): Boolean {
        if (doc.uri in notDeletable) return false
        files.remove(doc.uri)
        return true
    }

    override fun place(doc: DocRef): Place = places[doc.uri] ?: Place.PhoneStorage

    override fun name(doc: DocRef): String? = names[doc.uri]
}
