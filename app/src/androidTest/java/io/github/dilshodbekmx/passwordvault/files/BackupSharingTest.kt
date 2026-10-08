package io.github.dilshodbekmx.passwordvault.files

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.IntentCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Build plan 5.1, formats.md §2 rule 6, ux.md §5.19 item 6: "Send to another app…" hands the share
 * sheet a copy of the encrypted backup under `cache/shared/` through the app's `FileProvider`, with a
 * read permission on that copy alone that the chooser carries to the app the user picks; nothing
 * else can be handed out; the copies go at the next app start.
 */
@RunWith(AndroidJUnit4::class)
class BackupSharingTest {
    private val app = DocumentsRig.app
    private val sharing = BackupSharing(File(app.cacheDir, BackupSharing.DIR))

    @After
    fun clear() {
        sharing.clear()
    }

    @Test
    fun theCopyGoesToTheShareSheetThroughTheFileProvider() {
        val bytes = "PVAULT01 encrypted".toByteArray()
        val name = DocumentNames.backup("2026-10-08")
        val file = sharing.write(bytes, name)
        assertEquals(File(File(app.cacheDir, "shared"), name), file)

        val chooser = BackupSharing.chooser(app, file, "Send to another app")
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        assertEquals("Send to another app", chooser.getCharSequenceExtra(Intent.EXTRA_TITLE).toString())
        val send = IntentCompat.getParcelableExtra(chooser, Intent.EXTRA_INTENT, Intent::class.java)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals(DocumentTypes.BACKUP, send.type)
        val uri = IntentCompat.getParcelableExtra(send, Intent.EXTRA_STREAM, Uri::class.java)!!
        assertEquals("content", uri.scheme)
        assertEquals(app.packageName + ".files", uri.authority)
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals("read only", 0, send.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        assertEquals(uri, send.clipData!!.getItemAt(0).uri)
        assertTrue("the chooser carries the grant on", chooser.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(uri, chooser.clipData!!.getItemAt(0).uri)

        // What the receiving app reads: the copy, under the backup's name.
        assertArrayEquals(bytes, app.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
        val shown = app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)!!.use {
            it.moveToFirst()
            it.getString(0)
        }
        assertEquals(name, shown)

        // A second copy of that name replaces the first; the next app start removes them.
        sharing.write(byteArrayOf(1), name)
        assertArrayEquals(byteArrayOf(1), file.readBytes())
        sharing.clear()
        assertFalse(file.exists())
    }

    @Test
    fun nothingOutsideCacheSharedIsHandedOut() {
        val vaultSide = File(app.filesDir, "pvtest-not-shared.pvault").apply { writeBytes(byteArrayOf(1)) }
        try {
            BackupSharing.chooser(app, vaultSide, "Send")
            fail("the FileProvider hands out cache/shared/ only")
        } catch (e: IllegalArgumentException) {
            // expected
        } finally {
            vaultSide.delete()
        }
    }

    @Test
    fun onlyAFileNameIsTaken() {
        for (bad in listOf("", ".", "..", "../x.pvault", "a/b.pvault", "x\u0000.pvault")) {
            try {
                sharing.write(byteArrayOf(1), bad)
                fail("not a file name: $bad")
            } catch (e: IllegalArgumentException) {
                // expected
            }
        }
        assertNotEquals(0, sharing.write(byteArrayOf(1), "ok.pvault").length())
    }
}
