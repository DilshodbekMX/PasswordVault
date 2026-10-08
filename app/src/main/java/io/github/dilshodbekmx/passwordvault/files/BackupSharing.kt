package io.github.dilshodbekmx.passwordvault.files

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * "Send to another app…" for an encrypted backup (ux.md §5.19 item 6; formats.md §2 rule 6;
 * architecture.md D-12): the backup is copied to `cache/shared/`, the only directory the app's
 * `FileProvider` (authority `<applicationId>.files`, `res/xml/file_paths.xml`) hands out, and the
 * share sheet gets a read permission on that copy alone. Plain exports never come here: they are
 * saved through the file picker only.
 *
 * The copy is encrypted, and it is kept until the next app start (ux.md §5.19), so that a receiving
 * app can still read it after this app has locked: [clear] runs once when a process makes its
 * [FileTaskStore], and a new copy of the same name replaces the old one.
 *
 * @param dir `cache/shared/`.
 */
class BackupSharing(private val dir: File) {
    /** Writes [bytes] as [name] in [dir], replacing a copy of that name. Off the main thread. */
    @Throws(IOException::class)
    fun write(bytes: ByteArray, name: String): File {
        require(name.isNotEmpty() && name != "." && name != ".." && '/' !in name && '\u0000' !in name) { "not a file name" }
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) throw IOException("cache/shared cannot be made")
        val file = File(dir, name)
        FileOutputStream(file).use { it.write(bytes) }
        return file
    }

    /** Deletes every copy an earlier process shared. Off the main thread; best effort. */
    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }

    companion object {
        /** The directory under `cacheDir` that `res/xml/file_paths.xml` names. */
        const val DIR = "shared"

        /** The `FileProvider`'s authority is the application id followed by this. */
        const val AUTHORITY_SUFFIX = ".files"

        /**
         * The share sheet for [file]: `ACTION_SEND` of an `application/octet-stream` stream, with a
         * read permission on its `content://` address alone, in the system's chooser titled [title].
         */
        fun chooser(context: Context, file: File, title: CharSequence): Intent {
            val uri = FileProvider.getUriForFile(context, context.packageName + AUTHORITY_SUFFIX, file)
            val send = Intent(Intent.ACTION_SEND)
                .setType(DocumentTypes.BACKUP)
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // Through the chooser to the app the user picks: Intent.createChooser carries the clip and the flag over.
            send.clipData = ClipData.newRawUri(file.name, uri)
            return Intent.createChooser(send, title)
        }
    }
}
