package io.github.dilshodbekmx.passwordvault.core.vaultfile

import io.github.dilshodbekmx.passwordvault.core.crypto.Aead
import io.github.dilshodbekmx.passwordvault.core.model.Entry
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.core.util.Clock
import io.github.dilshodbekmx.passwordvault.core.util.RandomSource
import io.github.dilshodbekmx.passwordvault.core.vaultfile.Vectors.BUILD
import io.github.dilshodbekmx.passwordvault.core.vaultfile.Vectors.CREATED_B
import io.github.dilshodbekmx.passwordvault.core.vaultfile.Vectors.FILE_A
import io.github.dilshodbekmx.passwordvault.core.vaultfile.Vectors.FILE_B
import io.github.dilshodbekmx.passwordvault.core.vaultfile.Vectors.KEK
import io.github.dilshodbekmx.passwordvault.core.vaultfile.Vectors.NONCE_B
import io.github.dilshodbekmx.passwordvault.core.vaultfile.Vectors.SALT
import io.github.dilshodbekmx.passwordvault.core.vaultfile.Vectors.SLOT
import io.github.dilshodbekmx.passwordvault.core.vaultfile.Vectors.VAULT_B
import io.github.dilshodbekmx.passwordvault.core.vaultfile.Vectors.VAULT_ID
import io.github.dilshodbekmx.passwordvault.core.vaultfile.Vectors.VK
import io.github.dilshodbekmx.passwordvault.core.vaultfile.Vectors.VK_ID
import io.github.dilshodbekmx.passwordvault.core.vaultfile.Vectors.codec
import io.github.dilshodbekmx.passwordvault.testing.FakeClock
import io.github.dilshodbekmx.passwordvault.testing.FakeRandom
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * What `VaultCodec`'s contract and the writer rules of architecture.md §4.5 promise besides the
 * vectors: where nonces, the time and the build come from, what is copied, what the AEAD is given,
 * what is refused and what is wiped; and that `VaultCodecs.create` is the way to get the codec.
 */
class CodecContractTest {
    private fun ok(codec: VaultCodec, bytes: ByteArray) = (codec.check(bytes) as FileCheck.Ok).file

    private fun refused(block: () -> Unit) {
        try {
            block()
            fail("not refused")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun aSealDrawsOneFreshBodyNonceAndAWrapOneFreshSlotNonce() {
        val random = FakeRandom(5)
        val codec = codec(random)
        val first = codec.seal(VAULT_B, VK, VAULT_ID, VK_ID, 7, SLOT)
        val second = codec.seal(VAULT_B, VK, VAULT_ID, VK_ID, 7, SLOT)
        assertEquals(listOf(12, 12), random.byteRequests)
        val a = ok(codec, first)
        val b = ok(codec, second)
        assertFalse("a fresh nonce for every seal", a.bodyNonce.contentEquals(b.bodyNonce))
        assertEquals(BodyResult.Opened(VAULT_B), codec.open(a, VK))
        assertEquals(BodyResult.Opened(VAULT_B), codec.open(b, VK))
        val s1 = codec.wrap(VK, KEK, SALT)
        val s2 = codec.wrap(VK, KEK, SALT)
        assertEquals(listOf(12, 12, 12, 12), random.byteRequests)
        assertFalse(s1.nonce.contentEquals(s2.nonce))
        assertArrayEquals(VK, codec.unwrap(s1, KEK))
        assertArrayEquals(VK, codec.unwrap(s2, KEK))
    }

    @Test
    fun checkOpenAndUnwrapUseNoRandomnessAndNoClock() {
        val noRandom = object : RandomSource {
            override fun bytes(n: Int): ByteArray = throw AssertionError("random bytes drawn")
            override fun int(bound: Int): Int = throw AssertionError("random int drawn")
            override fun uuid(): String = throw AssertionError("random uuid drawn")
        }
        val noClock = object : Clock {
            override fun wallMs(): Long = throw AssertionError("clock read")
            override fun elapsedMs(): Long = throw AssertionError("clock read")
            override suspend fun delayUntilElapsed(elapsedMs: Long) {
                throw AssertionError("clock read")
            }
        }
        val codec = DefaultVaultCodec(TestAead(), noRandom, noClock, BUILD)
        val file = ok(codec, FILE_B)
        assertArrayEquals(VK, codec.unwrap(file.header.slot, KEK))
        assertEquals(BodyResult.Opened(VAULT_B), codec.open(file, VK))
    }

    @Test
    fun createdIsTheWallClockAtSealingHeldToWhatTheHeaderCarries() {
        fun createdWith(wallMs: Long): Long {
            val codec = codec(clock = FakeClock(wallMs = wallMs))
            return ok(codec, codec.seal(Vault(), VK, VAULT_ID, VK_ID, 1, SLOT)).header.createdMs
        }
        assertEquals(CREATED_B, createdWith(CREATED_B))
        assertEquals(0L, createdWith(0))
        assertEquals("a clock set before 1970 still makes a readable file", 0L, createdWith(-1))
        assertEquals(0L, createdWith(Long.MIN_VALUE))
        assertEquals(9_999_999_999_999_999L, createdWith(Long.MAX_VALUE))
        // The clock is read at every seal.
        val clock = FakeClock(wallMs = 1_000)
        val codec = codec(clock = clock)
        val early = codec.seal(Vault(), VK, VAULT_ID, VK_ID, 1, SLOT)
        clock.advance(5_000)
        val later = codec.seal(Vault(), VK, VAULT_ID, VK_ID, 2, SLOT)
        assertEquals(1_000L, ok(codec, early).header.createdMs)
        assertEquals(6_000L, ok(codec, later).header.createdMs)
    }

    @Test
    fun theGeneratorAndWriterVersionComeFromBuildInfo() {
        val build = BuildInfo("PasswordVault 2.10.3 (beta_1)", Int.MAX_VALUE)
        val codec = codec(build = build)
        val header = ok(codec, codec.seal(Vault(), VK, VAULT_ID, VK_ID, 1, SLOT)).header
        assertEquals("PasswordVault 2.10.3 (beta_1)", header.generator)
        assertEquals(Int.MAX_VALUE, header.writerVersion)
    }

    @Test
    fun theSlotAndTheIdsAreWrittenAsGiven() {
        val slot = PasswordSlot(ByteArray(16) { (it * 7).toByte() }, ByteArray(12) { (255 - it).toByte() }, ByteArray(48) { it.toByte() })
        val vaultId = "0f1e2d3c-4b5a-4968-a7b6-c5d4e3f2a1b0"
        val vkId = "ffffffff-ffff-4fff-bfff-ffffffffffff"
        val codec = codec()
        val header = ok(codec, codec.seal(Vault(), VK, vaultId, vkId, 9_999_999_999, slot)).header
        assertEquals(slot, header.slot) // byte for byte (§4.5 rule 4)
        assertEquals(vaultId, header.vaultId)
        assertEquals(vkId, header.vkId)
        assertEquals(9_999_999_999L, header.generation)
    }

    @Test
    fun theFileIsItsPartsAndTheAeadSeesTheAadWithoutTheNonce() {
        val aead = TestAead()
        val codec = codec(FakeRandom(9), aead = aead)
        val bytes = codec.seal(VAULT_B, VK, VAULT_ID, VK_ID, 7, SLOT)
        val file = ok(codec, bytes)
        assertEquals(56 + (file.aad.size - 12) + file.header.bodyLen, bytes.size)
        val sealCall = aead.calls.single()
        assertEquals("seal", sealCall.kind)
        assertArrayEquals(VK, sealCall.key)
        assertArrayEquals(file.bodyNonce, sealCall.nonce)
        assertArrayEquals("magic ‖ header_len ‖ header, without the body nonce [V-C3]", bytes.copyOf(file.aad.size), sealCall.aad)
        aead.calls.clear()
        codec.open(file, VK)
        assertArrayEquals(file.aad, aead.calls.single().aad)
        aead.calls.clear()
        codec.wrap(VK, KEK, SALT)
        assertNull("a slot is sealed with no AAD (§4.6)", aead.calls.single().aad)
        aead.calls.clear()
        codec.unwrap(SLOT, KEK)
        assertNull(aead.calls.single().aad)
    }

    @Test
    fun aSealRefusesWhatAVersion1HeaderCannotHold() {
        val codec = codec()
        refused { codec.seal(Vault(), VK, VAULT_ID, VK_ID, 0, SLOT) }
        refused { codec.seal(Vault(), VK, VAULT_ID, VK_ID, -1, SLOT) }
        refused { codec.seal(Vault(), VK, VAULT_ID, VK_ID, 10_000_000_000, SLOT) }
        refused { codec.seal(Vault(), VK, "0000000A-0000-4000-8000-000000000001", VK_ID, 1, SLOT) }
        refused { codec.seal(Vault(), VK, VAULT_ID, "00000000-0000-1000-8000-000000000002", 1, SLOT) }
        refused { codec.seal(Vault(), VK, "", VK_ID, 1, SLOT) }
        refused { codec.seal(Vault(), VK.copyOf(31), VAULT_ID, VK_ID, 1, SLOT) }
        refused { codec.seal(Vault(), VK, VAULT_ID, VK_ID, 1, PasswordSlot(ByteArray(15), ByteArray(12), ByteArray(48))) }
        refused { codec.seal(Vault(), VK, VAULT_ID, VK_ID, 1, PasswordSlot(ByteArray(16), ByteArray(12), ByteArray(32))) }
        refused { codec.seal(Vault(schema = 2), VK, VAULT_ID, VK_ID, 1, SLOT) }
        refused { codec.sealPadded(ByteArray(4_095), VK, VAULT_ID, VK_ID, 1, SLOT) }
        refused { codec.sealPadded(ByteArray(0), VK, VAULT_ID, VK_ID, 1, SLOT) }
    }

    @Test
    fun wrapAndUnwrap() {
        val random = FakeRandom(13)
        val codec = codec(random)
        repeat(50) {
            val vk = random.bytes(32)
            val kek = random.bytes(32)
            val salt = random.bytes(16)
            val slot = codec.wrap(vk, kek, salt)
            assertArrayEquals(salt, slot.salt)
            assertEquals(48, slot.wrappedKey.size)
            assertArrayEquals(vk, codec.unwrap(slot, kek))
            assertNull("another KEK", codec.unwrap(slot, random.bytes(32)))
        }
        assertNull(codec.unwrap(PasswordSlot(SALT, SLOT.nonce, SLOT.wrappedKey.copyOf(47)), KEK))
        assertNull(codec.unwrap(PasswordSlot(SALT, SLOT.nonce.copyOf(11), SLOT.wrappedKey), KEK))
        val flipped = SLOT.wrappedKey.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertNull(codec.unwrap(PasswordSlot(SALT, SLOT.nonce, flipped), KEK))
        // The caller owns what unwrap returns: a new array each time.
        val first = codec.unwrap(SLOT, KEK)!!
        first.fill(0)
        assertArrayEquals(VK, codec.unwrap(SLOT, KEK))
        // The salt is the caller's; the slot keeps its own copy.
        val salt = SALT.copyOf()
        val slot = codec.wrap(VK, KEK, salt)
        salt.fill(9)
        assertArrayEquals(SALT, slot.salt)
        refused { codec.unwrap(SLOT, KEK.copyOf(31)) }
        refused { codec.wrap(VK.copyOf(31), KEK, SALT) }
        refused { codec.wrap(VK, KEK.copyOf(33), SALT) }
        refused { codec.wrap(VK, KEK, SALT.copyOf(12)) }
    }

    @Test
    fun openNeedsAKeyOfThirtyTwoBytesAndAWholeFile() {
        val codec = codec()
        val file = ok(codec, FILE_A)
        refused { codec.open(file, VK.copyOf(16)) }
        assertEquals(BodyResult.TagFailed, codec.open(VaultFile(file.header, file.aad, file.bodyNonce.copyOf(11), file.bodyCt, true), VK))
        assertEquals(BodyResult.TagFailed, codec.open(VaultFile(file.header, file.aad, file.bodyNonce, ByteArray(15), true), VK))
    }

    @Test
    fun thePlaintextIsWipedAfterSealingAndOpening() {
        // An AEAD that keeps the arrays it was given and the ones it gave back.
        val inner = TestAead()
        val sealed = mutableListOf<ByteArray>()
        val opened = mutableListOf<ByteArray>()
        val keeping = object : Aead {
            override fun seal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray?): ByteArray {
                sealed += plaintext
                return inner.seal(key, nonce, plaintext, aad)
            }

            override fun open(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, aad: ByteArray?): ByteArray? =
                inner.open(key, nonce, ciphertext, aad)?.also { opened += it }
        }
        val codec = codec(aead = keeping)
        val file = ok(codec, codec.seal(VAULT_B, VK, VAULT_ID, VK_ID, 7, SLOT))
        assertEquals(BodyResult.Opened(VAULT_B), codec.open(file, VK))
        assertTrue("the body plaintext is wiped after sealing", sealed.single().all { it == 0.toByte() })
        assertTrue("the body plaintext is wiped after opening", opened.single().all { it == 0.toByte() })
        // A VK unwrapped from a slot is the caller's to wipe, so it is not wiped here.
        opened.clear()
        assertArrayEquals(VK, codec.unwrap(SLOT, KEK))
        assertArrayEquals(VK, opened.single())
    }

    @Test
    fun vaultCodecsCreateMakesThisCodec() {
        val codec = VaultCodecs.create(TestAead(), FakeRandom().queueBytes(NONCE_B), FakeClock(wallMs = CREATED_B), BUILD)
        assertArrayEquals(FILE_B, codec.seal(VAULT_B, VK, VAULT_ID, VK_ID, 7, SLOT))
        val file = (codec.check(FILE_B) as FileCheck.Ok).file
        assertEquals(BodyResult.Opened(VAULT_B), codec.open(file, VK))
        assertNotNull(codec.unwrap(file.header.slot, KEK))
    }

    @Test
    fun vaultCodecsCreateRefusesABuildThatCannotStandInAHeader() {
        for (generator in listOf("", "PasswordVault 1.0+git", "PasswordVault 1.0-é", "x".repeat(65), "Password\"Vault", "PasswordVault\n1.0")) {
            refused { VaultCodecs.create(TestAead(), FakeRandom(), FakeClock(), BuildInfo(generator, 1)) }
        }
        refused { VaultCodecs.create(TestAead(), FakeRandom(), FakeClock(), BuildInfo("PasswordVault 1.0", 0)) }
        refused { VaultCodecs.create(TestAead(), FakeRandom(), FakeClock(), BuildInfo("PasswordVault 1.0", -1)) }
        VaultCodecs.create(TestAead(), FakeRandom(), FakeClock(), BuildInfo("x".repeat(64), 1))
    }

    @Test
    fun aLargeVaultRoundTrips() {
        // ux.md §2 designs for 5,000 entries.
        val entries = List(5_000) { i ->
            Entry(
                id = "00000000-0000-4000-8000-" + i.toString().padStart(12, '0'),
                title = "Account $i – Oʻzbekiston",
                username = "user$i@example.com",
                password = "p@ss-$i-🔑",
                notes = "Line one\nLine two $i",
                urls = listOf("https://example$i.uz/login"),
                createdAt = 1_791_244_800_000L + i,
                updatedAt = 1_791_331_200_000L + i,
            )
        }
        val vault = Vault(entries = entries)
        val codec = codec(FakeRandom(17))
        val bytes = codec.seal(vault, VK, VAULT_ID, VK_ID, 3, SLOT)
        assertTrue("under the cap of 16 MiB", bytes.size <= 16_777_216)
        assertEquals(0, (bytes.size - 56 - (ok(codec, bytes).aad.size - 12) - 16) % 4_096)
        assertEquals(BodyResult.Opened(vault), codec.open(ok(codec, bytes), VK))
    }
}
