package io.github.dilshodbekmx.passwordvault.core.integration

import io.github.dilshodbekmx.passwordvault.core.crypto.Aead
import io.github.dilshodbekmx.passwordvault.core.crypto.Argon2Params
import io.github.dilshodbekmx.passwordvault.core.crypto.Argon2id
import io.github.dilshodbekmx.passwordvault.core.crypto.CryptoFactory
import io.github.dilshodbekmx.passwordvault.core.crypto.MasterPasswordBytes
import io.github.dilshodbekmx.passwordvault.core.model.CustomField
import io.github.dilshodbekmx.passwordvault.core.model.Entry
import io.github.dilshodbekmx.passwordvault.core.model.HistoryItem
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.core.storage.CommitResult
import io.github.dilshodbekmx.passwordvault.core.storage.Current
import io.github.dilshodbekmx.passwordvault.core.storage.FilePrimitives
import io.github.dilshodbekmx.passwordvault.core.storage.OpenResult
import io.github.dilshodbekmx.passwordvault.core.storage.OpenedVault
import io.github.dilshodbekmx.passwordvault.core.storage.PasswordResult
import io.github.dilshodbekmx.passwordvault.core.storage.StageResult
import io.github.dilshodbekmx.passwordvault.core.storage.VaultRef
import io.github.dilshodbekmx.passwordvault.core.storage.VaultStorage
import io.github.dilshodbekmx.passwordvault.core.storage.VaultStores
import io.github.dilshodbekmx.passwordvault.core.util.Clock
import io.github.dilshodbekmx.passwordvault.core.util.RandomSource
import io.github.dilshodbekmx.passwordvault.core.vaultfile.BuildInfo
import io.github.dilshodbekmx.passwordvault.core.vaultfile.FileCheck
import io.github.dilshodbekmx.passwordvault.core.vaultfile.VaultCodec
import io.github.dilshodbekmx.passwordvault.core.vaultfile.VaultCodecs
import io.github.dilshodbekmx.passwordvault.testing.FakeClock
import io.github.dilshodbekmx.passwordvault.testing.FakeRandom
import io.github.dilshodbekmx.passwordvault.testing.storage.FakeFiles
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test

/**
 * The vault through the real parts of :core together (build plan 2.3): the store of 2.3, the codec
 * of 2.2, AES-256-GCM and Argon2id of 2.1, `MasterPasswordBytes` of 1.3, on [FakeFiles].
 *
 * Create with a password, reopen with the password, edit, reopen with the key, change the
 * password, reopen with both, put aside and swap back. Every "reopen" is a new store, as a new
 * process would make it, over the same files.
 *
 * Skipped while `CryptoFactory` or `VaultCodecs` still throws `NotImplementedError` (its packages
 * are built beside this one); it must pass at the integration check of sub-wave 2A. [roundTrip]
 * takes its ports as arguments so that the same steps can run elsewhere (build plan 2.7 runs them on
 * a device with `OsFiles` and `NativeArgon2id`).
 */
class VaultRoundTripTest {
    @Test
    fun createReopenEditChangeAndSwap() {
        val parts = try {
            val aead = CryptoFactory.aead()
            val argon2 = CryptoFactory.bouncyCastleArgon2id()
            Triple(aead, argon2, VaultCodecs.create(aead, FakeRandom(5L), FakeClock(), BUILD))
        } catch (e: NotImplementedError) {
            Assume.assumeNoException("skipped while a factory still throws (packages 2.1 and 2.2)", e)
            return
        }
        val files = FakeFiles().apply { mkdirs("/data/files") }
        roundTrip(files, "/data/files/vaults", parts.second, parts.first, FakeRandom(6L), FakeClock())
    }

    companion object {
        val BUILD = BuildInfo("PasswordVault 1.0", 1)

        /**
         * The round trip over [files] in [vaultsDir], deriving every KEK with [argon2]. [aead] makes
         * the codec; [random] gives keys, ids and salts; [clock] the `created` times.
         */
        fun roundTrip(files: FilePrimitives, vaultsDir: String, argon2: Argon2id, aead: Aead, random: RandomSource, clock: Clock) {
            val codec: VaultCodec = VaultCodecs.create(aead, random, clock, BUILD)
            val keks = HashMap<String, ByteArray>()
            fun kek(password: String, salt: ByteArray): ByteArray = keks.getOrPut(password + "/" + salt.joinToString(",")) {
                val bytes = MasterPasswordBytes.of(password.toCharArray())
                try {
                    argon2.derive(bytes, salt, Argon2Params.V1)
                } finally {
                    bytes.fill(0)
                }
            }
            fun process(): VaultStorage = VaultStores.create(files, codec, clock, vaultsDir, BUILD)
            fun opened(result: Any): OpenedVault = when (result) {
                is PasswordResult.Opened -> result.opened
                is OpenResult.Opened -> result.opened
                is CommitResult.Saved -> result.opened
                else -> throw AssertionError("expected an open vault, got $result")
            }

            // 1. Create with a password (lock.md F1): the slot from the first typing, the proof with the second.
            val first = "correct horse battery staple"
            val vaultId = random.uuid()
            val vkId = random.uuid()
            val vk = random.bytes(32)
            val salt = random.bytes(16)
            val creating = process()
            assertEquals(Current.NoVault, creating.scan().current)
            val staged = (creating.stageVault(Vault(), vaultId, vk, vkId, codec.wrap(vk, kek(first, salt), salt)) as StageResult.Staged).staged
            assertArrayEquals(salt, staged.slotSalt)
            assertTrue("proved from disk with the second typing", creating.prove(staged, kek(" correct  horse battery staple ", staged.slotSalt)))
            val created = opened(creating.publish(staged, putCurrentAside = false))
            assertEquals(1L, created.generation)

            // 2. Reopen with the password, in a new process.
            val reopening = process()
            val facts = (reopening.scan().current as Current.Present).facts
            assertEquals(vaultId, facts.vaultId)
            assertEquals(1L, facts.newest.number)
            val slot = reopening.slots(VaultRef.Current).single()
            assertEquals(PasswordResult.SlotClosed, reopening.openWithSlot(VaultRef.Current, slot, kek("correct horse battery stapler", slot.salt)))
            val reopened = opened(reopening.openWithSlot(VaultRef.Current, slot, kek(first, slot.salt)))
            assertEquals(Vault(), reopened.vault)

            // 3. Edit: one ordinary save, with text in both scripts and the characters a writer must escape.
            val now = clock.wallMs()
            val edited = Vault(
                entries = listOf(
                    Entry(
                        id = random.uuid(),
                        title = "Gmail – Oʻzbekiston Ўзбекистон",
                        username = "aziz@example.com",
                        password = "p\"ss\\w/rd-🔑",
                        notes = "Birinchi qator\nИккинчи қатор\ttab\u0001",
                        urls = listOf("https://mail.google.com"),
                        fields = listOf(CustomField("PIN code", "0042", secret = true)),
                        favourite = true,
                        createdAt = now,
                        updatedAt = now,
                        passwordChangedAt = now,
                        passwordHistory = listOf(HistoryItem("old1", now - 1)),
                    ),
                ),
            )
            val saved = opened(reopening.commit(reopened, edited))
            assertEquals(2L, saved.generation)

            // 4. Reopen with the key, as the PIN or the fingerprint would.
            val byKeyStore = process()
            byKeyStore.scan()
            val byKey = opened(byKeyStore.open(VaultRef.Current, mapOf(vkId to vk)))
            assertEquals(edited, byKey.vault)
            assertEquals(2L, byKey.generation)

            // 5. Change the master password (lock.md F8): a new key and slot in live/, proven, published.
            val second = "a quite different passphrase of 2026"
            val vkId2 = random.uuid()
            val vk2 = random.bytes(32)
            val salt2 = random.bytes(16)
            val stagedKey = (byKeyStore.stageRekey(byKey, byKey.vault, vk2, vkId2, codec.wrap(vk2, kek(second, salt2), salt2)) as StageResult.Staged).staged
            assertTrue(byKeyStore.prove(stagedKey, kek(second, stagedKey.slotSalt)))
            val changed = opened(byKeyStore.publish(stagedKey, putCurrentAside = false))
            assertEquals(3L, changed.generation)
            assertEquals(vkId2, changed.vkId)

            // 6. Reopen with both keys and with both passwords.
            val both = process()
            val factsBoth = (both.scan().current as Current.Present).facts
            assertEquals(setOf(vkId, vkId2), factsBoth.keptVkIds)
            assertEquals(vkId2, factsBoth.newest.vkId)
            assertEquals(OpenResult.NeedsOtherKey(3L, factsBoth.newest.createdMs), both.open(VaultRef.Current, mapOf(vkId to vk)))
            val withBothKeys = opened(both.open(VaultRef.Current, mapOf(vkId to vk, vkId2 to vk2)))
            assertEquals(3L, withBothKeys.generation)
            val slots = both.slots(VaultRef.Current)
            assertEquals(listOf(vkId2, vkId), slots.map { it.vkId })
            assertEquals(edited, opened(both.openWithSlot(VaultRef.Current, slots[0], kek(second, slots[0].salt))).vault)
            assertEquals(PasswordResult.SlotClosed, both.openWithSlot(VaultRef.Current, slots[0], kek(first, slots[0].salt)))
            assertEquals(
                "the old password: an older master password, never wrong",
                PasswordResult.OlderPassword(factsBoth.newest.createdMs),
                both.openWithSlot(VaultRef.Current, slots[1], kek(first, slots[1].salt)),
            )

            // 7. Put aside ("Start with an empty app"), then open it in place and swap it back.
            assertTrue(both.putAside())
            assertEquals(Current.NoVault, both.scan().current)
            val swapping = process()
            val scan = swapping.scan()
            assertEquals(Current.NoVault, scan.current)
            assertEquals(listOf(vaultId), scan.aside.map { it.vaultId })
            assertEquals(3L, scan.aside.single().newest!!.number)
            val inPlace = opened(swapping.open(VaultRef.Aside(vaultId), mapOf(vkId2 to vk2)))
            assertEquals(edited, inPlace.vault)
            assertTrue(swapping.swapIn(vaultId))
            val back = process()
            val factsBack = (back.scan().current as Current.Present).facts
            assertEquals(vaultId, factsBack.vaultId)
            assertEquals(3L, factsBack.newest.number)
            val slotBack = back.slots(VaultRef.Current).first()
            assertEquals(edited, opened(back.openWithSlot(VaultRef.Current, slotBack, kek(second, slotBack.salt))).vault)

            // Every live file is a real version 1 file of this vault.
            for (n in 1..3) {
                val bytes = files.read("$vaultsDir/live/vault-000000000$n.pvault", 16_777_216)
                val check = codec.check(bytes)
                assertTrue("generation $n: $check", check is FileCheck.Ok && check.file.trailerOk && check.file.header.vaultId == vaultId)
            }
        }
    }
}
