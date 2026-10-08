package io.github.dilshodbekmx.passwordvault.files

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.ProviderInfo
import android.net.Uri
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.SyncFailedException
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * [DocumentIo] over the Storage Access Framework (architecture.md §3.5, §4.12; formats.md §2 rules 2
 * and 3; ux.md §4 item 3, §5.19). The only place where a [DocRef] becomes an `android.net.Uri`.
 *
 * - **Write** goes straight into the document, opened with mode "wt" (never through a plain
 *   temporary file in the app's own storage), hashing what it writes, then syncs it where the
 *   provider gives a real file. Then the document is opened again and read back as a stream, hashed
 *   and counted up to one byte more than was written, and the two SHA-256 sums and byte counts are
 *   compared: providers have reported success after writing 0 bytes, and have cut files [V-S6]. A
 *   provider that refuses the mode "wt" (some take only "w") is written with "w": a longer old
 *   content that "w" leaves behind fails the comparison.
 * - **Read** reads at most `maxBytes + 1` bytes, so that the caller can tell "too long"; anything
 *   that keeps the document from being read, a missing permission included, is an [IOException].
 * - **Keep** takes the persistable permission that the picker's result granted, read and write
 *   where both were granted, so that a task continues in a new process (ux.md §4 item 1).
 * - **Delete** asks the provider (`DocumentsContract.deleteDocument`, API 19). False where the place
 *   does not allow it or the document is still there; true when it is gone, also when it was gone
 *   already.
 * - **Place** by the provider ([Places]); another place is named by the label of its provider, or for
 *   an SD card or USB drive by its volume's description ("SanDisk SD card").
 * - **Name** asks the provider for the document's display name.
 *
 * Everything that reads or writes runs on [io] (architecture.md §5.3); [keep], [delete], [place],
 * [name] and [nameOf] are binder calls of their own, made by their callers off the main thread.
 */
class SafDocumentIo(private val context: Context, private val io: CoroutineDispatcher) : DocumentIo {
    private val resolver: ContentResolver get() = context.contentResolver

    override suspend fun write(doc: DocRef, bytes: ByteArray): Boolean = withContext(io) {
        val uri = Uri.parse(doc.uri)
        val wrote = MessageDigest.getInstance(SHA_256)
        try {
            openForWriting(uri).use { out ->
                out.write(bytes)
                wrote.update(bytes)
                out.flush()
                syncIfFile(out)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            return@withContext false
        } catch (e: RuntimeException) {
            return@withContext false // SecurityException, IllegalArgumentException, UnsupportedOperationException...
        }
        // The read-back: a new stream, hashed and counted as it comes, one byte past what was written.
        val read = MessageDigest.getInstance(SHA_256)
        val count = try {
            openForReading(uri).use { input -> digestUpTo(input, read, bytes.size.toLong() + 1) }
        } catch (e: IOException) {
            return@withContext false
        } catch (e: RuntimeException) {
            return@withContext false
        }
        count == bytes.size.toLong() && MessageDigest.isEqual(read.digest(), wrote.digest())
    }

    override suspend fun read(doc: DocRef, maxBytes: Int): ByteArray = withContext(io) {
        require(maxBytes >= 0) { "maxBytes must not be negative" }
        readAtMost(Uri.parse(doc.uri), maxBytes.toLong() + 1)
    }

    override fun keep(doc: DocRef) {
        val uri = Uri.parse(doc.uri)
        for (flags in KEEP_FLAGS) {
            try {
                resolver.takePersistableUriPermission(uri, flags)
                return
            } catch (e: SecurityException) {
                // Not granted with these flags, or not persistable: try the next ones.
            }
        }
    }

    override fun delete(doc: DocRef): Boolean {
        val uri = Uri.parse(doc.uri)
        val deleted = try {
            DocumentsContract.deleteDocument(resolver, uri)
        } catch (e: FileNotFoundException) {
            false // from Android 10 the call throws where it returned false before
        } catch (e: RuntimeException) {
            false // the place does not allow it, or no permission
        }
        return deleted || isGone(uri)
    }

    override fun place(doc: DocRef): Place = Places.of(doc.uri, ::nameOf)

    /**
     * The name the provider shows for [doc] (`OpenableColumns.DISPLAY_NAME`), which may differ from
     * the one the picker suggested ("(1)" added on a clash); null when it cannot be read.
     */
    override fun name(doc: DocRef): String? = try {
        resolver.query(Uri.parse(doc.uri), arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
        }
    } catch (e: RuntimeException) {
        null
    }

    /** The name of a place that is neither the phone's storage nor Google Drive (see [Places.of]). */
    fun nameOf(authority: String, rootId: String?): String? {
        if (authority == Places.EXTERNAL_STORAGE && rootId != null) volumeDescription(rootId)?.let { return it }
        return providerLabel(authority)
    }

    private fun openForWriting(uri: Uri): OutputStream {
        val truncating = try {
            resolver.openOutputStream(uri, "wt")
        } catch (e: IllegalArgumentException) {
            null // "bad mode": this provider takes only "w"
        } catch (e: UnsupportedOperationException) {
            null
        }
        return truncating ?: resolver.openOutputStream(uri, "w") ?: throw FileNotFoundException("no stream for writing")
    }

    /** A provider that hands out a real file gets it synced before the read-back; a pipe cannot be. */
    private fun syncIfFile(out: OutputStream) {
        try {
            (out as? FileOutputStream)?.fd?.sync()
        } catch (e: SyncFailedException) {
            // A pipe or a socket: the provider writes the file itself.
        } catch (e: IOException) {
            // The same.
        }
    }

    /** A new stream of the document; [IOException] for anything that keeps it from being opened. */
    private fun openForReading(uri: Uri): InputStream = try {
        resolver.openInputStream(uri)
    } catch (e: IOException) {
        throw e
    } catch (e: RuntimeException) {
        throw IOException("the document cannot be opened", e) // SecurityException: no permission
    } ?: throw FileNotFoundException("no stream for reading")

    /** At most [limit] bytes of the document; [IOException] for anything that keeps it from being read. */
    private fun readAtMost(uri: Uri, limit: Long): ByteArray {
        val input = openForReading(uri)
        try {
            return input.use { readUpTo(it, limit) }
        } catch (e: RuntimeException) {
            throw IOException("the document cannot be read", e)
        }
    }

    /** Feeds [digest] with the stream until its end or [limit] bytes; the number of bytes. */
    private fun digestUpTo(input: InputStream, digest: MessageDigest, limit: Long): Long {
        val buffer = ByteArray(FIRST_BUFFER)
        var count = 0L
        while (count < limit) {
            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), limit - count).toInt())
            if (n < 0) break
            digest.update(buffer, 0, n)
            count += n
        }
        return count
    }

    /** Reads until the end or until [limit] bytes, growing the buffer as the bytes come. */
    private fun readUpTo(input: InputStream, limit: Long): ByteArray {
        val cap = minOf(limit, Int.MAX_VALUE.toLong() - 8).toInt()
        var buffer = ByteArray(minOf(cap, FIRST_BUFFER))
        var size = 0
        while (size < cap) {
            if (size == buffer.size) buffer = buffer.copyOf(minOf(cap.toLong(), buffer.size.toLong() * 2).toInt())
            val n = input.read(buffer, size, buffer.size - size)
            if (n < 0) break
            size += n
        }
        return if (size == buffer.size) buffer else buffer.copyOf(size)
    }

    /**
     * True only when the provider, which is there, says the document is not: a query that finds no
     * row, or none at all (a documents provider answers a missing document so). A provider that is
     * not installed, or a permission that is gone, tells nothing, and the document is taken to be
     * there.
     */
    private fun isGone(uri: Uri): Boolean {
        val authority = uri.authority ?: return false
        if (providerInfo(authority) == null) return false
        return try {
            resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)
                ?.use { it.count == 0 } ?: true
        } catch (e: RuntimeException) {
            false // SecurityException: no permission any more, nothing is known
        }
    }

    private fun volumeDescription(uuid: String): String? = try {
        context.getSystemService(StorageManager::class.java)?.storageVolumes
            ?.firstOrNull { it.uuid.equals(uuid, ignoreCase = true) }
            ?.getDescription(context)?.takeIf { it.isNotBlank() }
    } catch (e: RuntimeException) {
        null
    }

    private fun providerLabel(authority: String): String? = try {
        providerInfo(authority)?.loadLabel(context.packageManager)?.toString()?.takeIf { it.isNotBlank() }
    } catch (e: RuntimeException) {
        null
    }

    @Suppress("DEPRECATION") // the int flags form; its replacement needs API 33
    private fun providerInfo(authority: String): ProviderInfo? = try {
        context.packageManager.resolveContentProvider(authority, 0)
    } catch (e: RuntimeException) {
        null
    }

    private companion object {
        const val SHA_256 = "SHA-256"
        const val FIRST_BUFFER = 64 * 1024

        /** Read and write when both were granted, else what was. */
        val KEEP_FLAGS = intArrayOf(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
    }
}
