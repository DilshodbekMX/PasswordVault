package io.github.dilshodbekmx.passwordvault.files

import java.io.ByteArrayOutputStream

/**
 * Which [Place] a chosen document is in, recognised by its documents provider (ux.md §5.19 step 5):
 * the line under "Backup saved", "Last backup: 3 days ago, Google Drive", and the text that asks the
 * user to delete an empty file themselves where a place does not allow it (ux.md §4 item 3).
 *
 * - **Phone storage:** the Downloads provider; the external-storage provider's main storage, its
 *   root `primary` (and `home`, the Documents folder on it, from Android 8); and the media
 *   provider's documents, which are files on that same storage. A copy there is lost with the phone.
 * - **Google Drive:** Drive's documents provider, by its authority.
 * - **Anything else**, an SD card or USB drive (the external-storage provider's other roots, named
 *   by their volume id) or another app's provider: [Place.Other] with the name that [of]'s caller
 *   finds for it.
 *
 * Pure text work on the `content://` address, so that it needs nothing of Android.
 */
object Places {
    /** The Downloads provider. */
    const val DOWNLOADS = "com.android.providers.downloads.documents"

    /** The external-storage provider: the phone's main storage, SD cards and USB drives. */
    const val EXTERNAL_STORAGE = "com.android.externalstorage.documents"

    /** The media provider's documents (Images, Videos, Audio, and from Android 10 Documents). */
    const val MEDIA = "com.android.providers.media.documents"

    /** Google Drive's documents provider, and its older authority. */
    val GOOGLE_DRIVE: Set<String> = setOf("com.google.android.apps.docs.storage", "com.google.android.apps.docs.storage.legacy")

    /** The external-storage provider's roots on the phone's main storage. */
    private val MAIN_STORAGE_ROOTS = setOf("primary", "home")

    /**
     * The place of the document at [uri]. [name] names any other place: it gets the provider's
     * authority and, for the external-storage provider, the root id (an SD card's or USB drive's
     * volume id); when it finds no name, the place is named by its authority.
     */
    fun of(uri: String, name: (authority: String, rootId: String?) -> String?): Place {
        val authority = authorityOf(uri).orEmpty()
        val rootId = if (authority == EXTERNAL_STORAGE) documentIdOf(uri)?.substringBefore(':') else null
        return when {
            authority == DOWNLOADS || authority == MEDIA -> Place.PhoneStorage
            authority == EXTERNAL_STORAGE && rootId in MAIN_STORAGE_ROOTS -> Place.PhoneStorage
            authority in GOOGLE_DRIVE -> Place.GoogleDrive
            else -> Place.Other(name(authority, rootId)?.takeIf { it.isNotBlank() } ?: authority)
        }
    }

    /**
     * The authority of a `content://` address, without the user id that a document of another
     * profile carries (`content://10@authority/…`); null for any other address.
     */
    fun authorityOf(uri: String): String? {
        if (!uri.startsWith(CONTENT)) return null
        val rest = uri.substring(CONTENT.length)
        val end = rest.indexOfAny(charArrayOf('/', '?', '#')).let { if (it < 0) rest.length else it }
        return rest.substring(0, end).substringAfterLast('@').takeIf { it.isNotEmpty() }
    }

    /**
     * The document id of a document address (`…/document/<id>` or `…/tree/<tree>/document/<id>`),
     * decoded; null when the address names no document.
     */
    fun documentIdOf(uri: String): String? {
        if (authorityOf(uri) == null) return null
        val path = uri.substring(CONTENT.length).substringBefore('?').substringBefore('#')
        val segments = path.split('/').drop(1)
        val at = segments.lastIndexOf("document")
        if (at < 0 || at + 1 >= segments.size) return null
        return decode(segments[at + 1]).takeIf { it.isNotEmpty() }
    }

    /** Percent-decoding of one path segment as UTF-8; a broken escape is kept as it is. A `+` stays a `+`. */
    private fun decode(segment: String): String {
        if ('%' !in segment) return segment
        val bytes = ByteArrayOutputStream(segment.length)
        var i = 0
        while (i < segment.length) {
            val hi = if (segment[i] == '%' && i + 2 < segment.length) Character.digit(segment[i + 1], 16) else -1
            val lo = if (hi >= 0) Character.digit(segment[i + 2], 16) else -1
            if (hi >= 0 && lo >= 0) {
                bytes.write(hi * 16 + lo)
                i += 3
            } else {
                // Plain text up to the next escape, as one run, so that a surrogate pair stays whole.
                val next = segment.indexOf('%', i + 1).let { if (it < 0) segment.length else it }
                val text = segment.substring(i, next).toByteArray(Charsets.UTF_8)
                bytes.write(text, 0, text.size)
                i = next
            }
        }
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }

    private const val CONTENT = "content://"
}
