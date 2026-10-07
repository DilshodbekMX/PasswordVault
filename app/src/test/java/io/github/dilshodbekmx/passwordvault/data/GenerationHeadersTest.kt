package io.github.dilshodbekmx.passwordvault.data

import io.github.dilshodbekmx.passwordvault.core.lock.flow.PasswordOpen
import io.github.dilshodbekmx.passwordvault.core.lock.flow.VaultRef
import io.github.dilshodbekmx.passwordvault.testing.storage.FakeFiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.util.Locale
import io.github.dilshodbekmx.passwordvault.core.storage.VaultRef as StoreVaultRef

/**
 * [GenerationHeaders]: the `created` time of a generation, read from its header by the names of
 * architecture.md §4.7, for the `createdMs` of the lock's results; null when the file is not the
 * one asked for or cannot be read now.
 */
@RunWith(Parameterized::class)
class GenerationHeadersTest(private val kind: DiskKind) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun kinds(): List<DiskKind> = DiskKind.entries
    }

    @get:Rule
    val temp = TemporaryFolder()

    private val disk: Disk by lazy { Disk.of(kind, temp) }

    @Test
    fun namesAreThoseOfSection47WhateverTheLocale() {
        val before = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("fa-IR")) // Persian digits for a default-locale format
            assertEquals("vault-0000000042.pvault", GenerationHeaders.fileName(42L))
            assertEquals("vault-9999999999.pvault", GenerationHeaders.fileName(9_999_999_999L))
        } finally {
            Locale.setDefault(before)
        }
        val id = vaultId(1)
        assertTrue(GenerationHeaders.isAsideDirOf("aside-1791244800000-$id", id))
        assertFalse(GenerationHeaders.isAsideDirOf("aside-179124480000-$id", id))
        assertFalse(GenerationHeaders.isAsideDirOf("aside-17912448000x0-$id", id))
        assertFalse(GenerationHeaders.isAsideDirOf("aside-1791244800000-${vaultId(2)}", id))
        assertFalse(GenerationHeaders.isAsideDirOf("new-$id", id))
    }

    @Test
    fun theCreatedTimeOfTheGenerationThatOpened() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        try {
            val phone = Phone(disk)
            val repo = phone.process(scope, dispatcher)
            val first = repo.create(vaultId(1))
            phone.clock.advance(1_000L)
            repo.add(form("later"), phone.random)
            val headers = GenerationHeaders(phone.files, phone.codec, phone.vaultsDir)
            assertEquals(first.createdMs, headers.createdMs(StoreVaultRef.Current, first.vaultId, first.vkId, 1L))
            assertEquals(first.createdMs + 1_000L, headers.createdMs(StoreVaultRef.Current, first.vaultId, first.vkId, 2L))
            assertNull("another vault", headers.createdMs(StoreVaultRef.Current, vaultId(2), first.vkId, 2L))
            assertNull("another key", headers.createdMs(StoreVaultRef.Current, first.vaultId, id(9), 2L))
            assertNull("no such generation", headers.createdMs(StoreVaultRef.Current, first.vaultId, first.vkId, 3L))
            assertNull("not a generation number", headers.createdMs(StoreVaultRef.Current, first.vaultId, first.vkId, 0L))
            disk.fail(FakeFiles.Op.READ) { it.endsWith(GenerationHeaders.fileName(2L)) }
            assertNull("cannot be read now", headers.createdMs(StoreVaultRef.Current, first.vaultId, first.vkId, 2L))

            // A vault put aside: its newest directory.
            repo.lock()
            assertTrue(repo.putAside())
            assertEquals(first.createdMs + 1_000L, headers.createdMs(StoreVaultRef.Aside(first.vaultId), first.vaultId, first.vkId, 2L))
            val opened = repo.openWithPassword(VaultRef.Aside(first.vaultId), bytesOf(PASSWORD)) as PasswordOpen.Opened
            assertEquals(first.createdMs + 1_000L, opened.createdMs)
            assertNull(headers.createdMs(StoreVaultRef.Aside(vaultId(3)), vaultId(3), first.vkId, 1L))
        } finally {
            scope.cancel()
        }
    }
}
