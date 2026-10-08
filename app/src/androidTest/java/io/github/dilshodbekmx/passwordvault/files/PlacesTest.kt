package io.github.dilshodbekmx.passwordvault.files

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Build plan 5.1 (ux.md §5.19 step 5): which [Place] a document is in, from its address alone, as
 * the providers write their addresses (the Downloads provider's ids of Android 7 to 17, the
 * external-storage provider's `<root>:<path>` ids, Google Drive's authorities).
 */
@RunWith(AndroidJUnit4::class)
class PlacesTest {
    private val asked = mutableListOf<Pair<String, String?>>()

    private fun place(uri: String): Place = Places.of(uri) { authority, root ->
        asked += authority to root
        "name of $authority $root"
    }

    @Test
    fun downloadsAndTheMainStorageAreThePhone() {
        val phone = listOf(
            "content://com.android.providers.downloads.documents/document/14",
            "content://com.android.providers.downloads.documents/document/msf%3A1000000031",
            "content://com.android.providers.downloads.documents/document/raw%3A%2Fstorage%2Femulated%2F0%2FDownload%2Fx.pvault",
            "content://com.android.externalstorage.documents/document/primary%3ADownload%2FPasswordVault-backup-2026-10-06.pvault",
            "content://com.android.externalstorage.documents/tree/primary%3ADownload/document/primary%3ADownload%2Fpasswords.csv",
            "content://com.android.externalstorage.documents/document/home%3Apasswords-2026-10-06.json",
            "content://com.android.providers.media.documents/document/document%3A1000000040",
            "content://10@com.android.externalstorage.documents/document/primary%3Ax.pvault",
        )
        for (uri in phone) assertEquals(uri, Place.PhoneStorage, place(uri))
        assertTrue("the phone needs no name", asked.isEmpty())
    }

    @Test
    fun googleDriveByItsAuthority() {
        assertEquals(Place.GoogleDrive, place("content://com.google.android.apps.docs.storage/document/acc%3D1%3Bdoc%3Dencoded%3DabcDEF"))
        assertEquals(Place.GoogleDrive, place("content://com.google.android.apps.docs.storage.legacy/enc%3Dxyz"))
        assertTrue(asked.isEmpty())
    }

    @Test
    fun anSdCardOrUsbDriveIsNamedWithItsVolume() {
        assertEquals(
            Place.Other("name of com.android.externalstorage.documents 1A2B-3C4D"),
            place("content://com.android.externalstorage.documents/document/1A2B-3C4D%3ADownload%2Fx.pvault"),
        )
        assertEquals(listOf("com.android.externalstorage.documents" to "1A2B-3C4D"), asked)
    }

    @Test
    fun anotherAppIsNamedByItsProviderOrElseByItsAuthority() {
        assertEquals(Place.Other("name of com.dropbox.android.Dropbox.Documents null"), place("content://com.dropbox.android.Dropbox.Documents/document/%2Fbackup.pvault"))
        assertEquals(Place.Other("org.example.files"), Places.of("content://org.example.files/document/1") { _, _ -> null })
        assertEquals(Place.Other("org.example.files"), Places.of("content://org.example.files/document/1") { _, _ -> "  " })
    }

    @Test
    fun theNameOfAnotherPlaceIsMadeSafeToShow() {
        // The label of another app's provider, shown in "Saved to …" and "Delete the empty file … in … yourself".
        val hostile = "Drive\n\u202EevirD elgooG\u200B" + "y".repeat(3_000)
        val place = Places.of("content://org.example.files/document/1") { _, _ -> hostile } as Place.Other
        assertTrue(place.name.length <= 255)
        assertTrue(place.name, place.name.startsWith("Drive evirD elgooGyyy"))
        assertTrue(place.name.none { Character.getType(it) == Character.CONTROL.toInt() || Character.getType(it) == Character.FORMAT.toInt() })
        assertEquals(Place.Other("SanDisk SD card"), Places.of("content://org.example.files/document/1") { _, _ -> "SanDisk SD card" })
        assertEquals("nothing left to show: the authority", Place.Other("org.example.files"), Places.of("content://org.example.files/document/1") { _, _ -> "\u200B\n" })
    }

    @Test
    fun anAddressIsTakenApart() {
        assertEquals("com.android.externalstorage.documents", Places.authorityOf("content://10@com.android.externalstorage.documents/document/primary%3Ax"))
        assertEquals("a.b", Places.authorityOf("content://a.b"))
        assertEquals("a.b", Places.authorityOf("content://a.b?x=1"))
        assertNull(Places.authorityOf("file:///sdcard/x.pvault"))
        assertNull(Places.authorityOf("content://"))

        assertEquals("primary:Download/PasswordVault-backup-2026-10-06.pvault",
            Places.documentIdOf("content://com.android.externalstorage.documents/document/primary%3ADownload%2FPasswordVault-backup-2026-10-06.pvault"))
        assertEquals("primary:Download/x",
            Places.documentIdOf("content://com.android.externalstorage.documents/tree/primary%3ADownload/document/primary%3ADownload%2Fx"))
        assertEquals("primary:Пароли/Oʻzbek 1+1.csv",
            Places.documentIdOf("content://com.android.externalstorage.documents/document/primary%3A%D0%9F%D0%B0%D1%80%D0%BE%D0%BB%D0%B8%2FO%CA%BBzbek%201+1.csv"))
        assertEquals("a broken escape is kept", "100%zz", Places.documentIdOf("content://a.b/document/100%zz"))
        assertEquals("an escape cut short is kept", "x%4", Places.documentIdOf("content://a.b/document/x%4"))
        assertNull("no document", Places.documentIdOf("content://com.android.externalstorage.documents/tree/primary%3ADownload"))
        assertNull(Places.documentIdOf("content://a.b/document/"))
        assertNull(Places.documentIdOf("file:///document/x"))
    }
}
