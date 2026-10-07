package io.github.dilshodbekmx.passwordvault.core.crypto

import io.github.dilshodbekmx.passwordvault.core.util.Bytes
import io.github.dilshodbekmx.passwordvault.core.util.Uuid
import io.github.dilshodbekmx.passwordvault.testing.FakeRandom
import java.security.MessageDigest
import java.util.Random
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * `JcaAesGcm` through [CryptoFactory.aead] (architecture.md §3.2; §8.1, "AES-GCM with and without
 * AAD"): round trips, tag failures, and two sets of vectors reproduced byte for byte, the vault file
 * vector A of architecture.md §4.13.1 (the slot without AAD, the body with it) and the PIN wrap of
 * lock.md §4.3.
 */
class JcaAesGcmTest {

    private val aead = CryptoFactory.aead()
    private val random = Random(20261007)

    private val vk = FakeRandom.sequence(0xa0, 32) // VK of both vector sets: a0 a1 … bf

    private fun bytes(size: Int): ByteArray = ByteArray(size).also { random.nextBytes(it) }

    private fun sha256(vararg parts: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        parts.forEach { digest.update(it) }
        return digest.digest()
    }

    private fun changed(array: ByteArray, at: Int): ByteArray = array.copyOf().also { it[at] = (it[at].toInt() xor 0x01).toByte() }

    // --- round trips ---

    @Test
    fun roundTrip_withoutAad_andWithAad() {
        for (size in listOf(0, 1, 15, 16, 17, 31, 32, 48, 4096, 65_537)) {
            for (aad in listOf(null, bytes(1), bytes(67), bytes(511))) {
                val key = bytes(32)
                val nonce = bytes(12)
                val plaintext = bytes(size)
                val sealed = aead.seal(key, nonce, plaintext, aad)
                assertEquals("the tag is 16 bytes at the end", size + 16, sealed.size)
                assertArrayEquals(plaintext, aead.open(key, nonce, sealed, aad))
            }
        }
    }

    @Test
    fun roundTrip_ofTheLargestBodyAVersion1FileCanHold() {
        // A file is at most 16,777,216 bytes (architecture.md §4.1); with the 499-byte header of the
        // vectors, the padded plaintext is at most 4,095 blocks of 4,096 bytes.
        val plaintext = bytes(4_095 * 4_096)
        val aad = bytes(12 + 499)
        val sealed = aead.seal(vk, FakeRandom.sequence(0x30, 12), plaintext, aad)
        assertTrue(56 + 499 + sealed.size <= 16_777_216)
        assertArrayEquals(plaintext, aead.open(vk, FakeRandom.sequence(0x30, 12), sealed, aad))
    }

    @Test
    fun anEmptyAadIsTheSameAsNone() {
        val key = bytes(32)
        val nonce = bytes(12)
        val plaintext = bytes(100)
        val withNone = aead.seal(key, nonce, plaintext, null)
        assertArrayEquals(withNone, aead.seal(key, nonce, plaintext, ByteArray(0)))
        assertArrayEquals(plaintext, aead.open(key, nonce, withNone, ByteArray(0)))
    }

    @Test
    fun theInputsAreNotChanged() {
        val key = bytes(32)
        val nonce = bytes(12)
        val plaintext = bytes(100)
        val aad = bytes(20)
        val copies = listOf(key, nonce, plaintext, aad).map { it.copyOf() }
        val sealed = aead.seal(key, nonce, plaintext, aad)
        val sealedCopy = sealed.copyOf()
        aead.open(key, nonce, sealed, aad)
        aead.open(key, nonce, changed(sealed, 3), aad)
        listOf(key, nonce, plaintext, aad).zip(copies).forEach { (array, copy) -> assertArrayEquals(copy, array) }
        assertArrayEquals(sealedCopy, sealed)
    }

    // --- tag failures: open gives null, never throws, never returns a wrong plaintext ---

    @Test
    fun everyChangedByteOfTheCiphertextOrTagFails() {
        val key = bytes(32)
        val nonce = bytes(12)
        val sealed = aead.seal(key, nonce, vk, null) // 48 bytes, the size of every wrap
        for (at in sealed.indices) assertNull("byte $at", aead.open(key, nonce, changed(sealed, at), null))
    }

    @Test
    fun everyChangedByteOfTheAadFails() {
        val key = bytes(32)
        val nonce = bytes(12)
        val aad = bytes(67)
        val sealed = aead.seal(key, nonce, vk, aad)
        for (at in aad.indices) assertNull("byte $at", aead.open(key, nonce, sealed, changed(aad, at)))
    }

    @Test
    fun anotherKeyOrNonceFails() {
        val key = bytes(32)
        val nonce = bytes(12)
        val sealed = aead.seal(key, nonce, bytes(64), null)
        for (at in key.indices) assertNull("key byte $at", aead.open(changed(key, at), nonce, sealed, null))
        for (at in nonce.indices) assertNull("nonce byte $at", aead.open(key, changed(nonce, at), sealed, null))
    }

    @Test
    fun anAadAddedOrDroppedFails() {
        val key = bytes(32)
        val nonce = bytes(12)
        val aad = bytes(16)
        assertNull(aead.open(key, nonce, aead.seal(key, nonce, vk, null), aad))
        assertNull(aead.open(key, nonce, aead.seal(key, nonce, vk, aad), null))
    }

    @Test
    fun aShortenedOrLengthenedCiphertextFails() {
        val key = bytes(32)
        val nonce = bytes(12)
        val sealed = aead.seal(key, nonce, bytes(100), null)
        assertNull(aead.open(key, nonce, sealed.copyOf(sealed.size - 1), null))
        assertNull(aead.open(key, nonce, sealed.copyOfRange(1, sealed.size), null))
        assertNull(aead.open(key, nonce, sealed + byteArrayOf(0), null))
        assertNull(aead.open(key, nonce, sealed.copyOf(16), null))
    }

    @Test
    fun aCiphertextShorterThanTheTagFails() {
        for (size in 0 until 16) assertNull("$size bytes", aead.open(bytes(32), bytes(12), bytes(size), null))
    }

    // --- what is refused ---

    @Test
    fun onlyA32ByteKeyAndA12ByteNonce() {
        fun refused(what: String, call: () -> Unit) {
            try {
                call()
                fail("$what was accepted")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message.orEmpty(), e.message.orEmpty().startsWith("AES-256-GCM takes"))
            }
        }
        for (size in listOf(0, 16, 24, 31, 33, 64)) {
            refused("a $size-byte key for seal") { aead.seal(bytes(size), bytes(12), bytes(10), null) }
            refused("a $size-byte key for open") { aead.open(bytes(size), bytes(12), bytes(26), null) }
        }
        for (size in listOf(0, 8, 11, 13, 16)) {
            refused("a $size-byte nonce for seal") { aead.seal(bytes(32), bytes(size), bytes(10), null) }
            refused("a $size-byte nonce for open") { aead.open(bytes(32), bytes(size), bytes(26), null) }
        }
    }

    // --- architecture.md §4.13.1, vector A ---

    @Test
    fun a413_vectorA_theSlot_withoutAad() {
        val wrapped = aead.seal(Bytes.fromHex(KEK_A), FakeRandom.sequence(0x20, 12), vk, null)
        assertEquals(WRAPPED_KEY_A, Bytes.hex(wrapped))
        assertArrayEquals(vk, aead.open(Bytes.fromHex(KEK_A), FakeRandom.sequence(0x20, 12), wrapped, null))
    }

    @Test
    fun a413_vectorA_theBody_withTheAad_reproducesTheFile() {
        val header = HEADER_A.toByteArray(Charsets.US_ASCII)
        assertEquals(499, header.size)
        val aad = "PVAULT01".toByteArray(Charsets.US_ASCII) + byteArrayOf(0, 0, (499 shr 8).toByte(), 499.toByte()) + header
        assertEquals("adfd3efa701b6e18938f770551cf86b990d3c651fe7016769fccf2f90fce15f0", Bytes.hex(sha256(aad)))
        val plaintext = """{"schema":1,"entries":[]}""".toByteArray(Charsets.UTF_8) + ByteArray(4_071) { 0x20.toByte() }
        val bodyNonce = FakeRandom.sequence(0x30, 12)

        val body = aead.seal(vk, bodyNonce, plaintext, aad)

        assertEquals(4_112, body.size)
        assertEquals("b725d6eb1c28bcaf9cab305f5a362876", Bytes.hex(body.copyOfRange(4_096, 4_112)))
        val beforeTrailer = aad + bodyNonce + body
        val file = beforeTrailer + sha256(beforeTrailer)
        assertEquals(4_667, file.size)
        assertEquals("bb6953c8aec55e24dfb00e016dbd49268fbcab8ce5df20854416862ba0ce4e2a", Bytes.hex(file.copyOfRange(4_635, 4_667)))
        assertEquals("3210ed2c15bddde2e2d3793aeff65deda2d745d1ac10ed6c6443be1711ee1d2d", Bytes.hex(sha256(file)))
        assertArrayEquals(plaintext, aead.open(vk, bodyNonce, body, aad))
        // "With the body nonce added to the AAD the tag fails" [V-C3].
        assertNull(aead.open(vk, bodyNonce, body, aad + bodyNonce))
    }

    // --- lock.md §4.3, the PIN wrap (appendix C) ---

    private val vaultId = Uuid.uuid16("00000000-0000-4000-8000-000000000001")
    private val vkId = Uuid.uuid16("00000000-0000-4000-8000-000000000002")
    private val slotId = Uuid.uuid16("00000000-0000-4000-8000-000000000003")

    /** `KEK_pin` = HMAC-SHA256(K_pin, pin_msg), with the test stand-in `42 × 32` for K_pin. */
    private fun kekPin(pin: String): ByteArray {
        val message = "pvlock-v1:pin-kek".toByteArray(Charsets.US_ASCII) + byteArrayOf(0) + vaultId + slotId + pin.toByteArray(Charsets.US_ASCII)
        assertEquals(56, message.size)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(ByteArray(32) { 0x42.toByte() }, "HmacSHA256"))
        return mac.doFinal(message)
    }

    private fun aadPin(vkId: ByteArray, slotId: ByteArray): ByteArray =
        "pvlock-v1:pin-wrap".toByteArray(Charsets.US_ASCII) + byteArrayOf(0) + vaultId + vkId + slotId

    @Test
    fun lockMd43_thePinWrap() {
        val kek = kekPin("582913")
        assertEquals("344f054090df8d29988b680e0a4eaaa2256f5195f6f0d9579bb5bd21972f0d74", Bytes.hex(kek))
        val aad = aadPin(vkId, slotId)
        assertEquals(67, aad.size)
        val nonce = FakeRandom.sequence(0x10, 12)

        val wrap = aead.seal(kek, nonce, vk, aad)

        assertEquals(
            "e406013346c5ad2cda2a4ca5c7eef739ee9cc17382092ecae223efc3a79c819cb6aef30c458b67b05303f36055881d02",
            Bytes.hex(wrap),
        )
        assertArrayEquals(vk, aead.open(kek, nonce, wrap, aad))
        // "PIN 582914, or vk_id and slot_id swapped in the AAD → AEADBadTagException": here, null.
        assertNull(aead.open(kekPin("582914"), nonce, wrap, aad))
        assertNull(aead.open(kek, nonce, wrap, aadPin(slotId, vkId)))
    }

    // --- threads ---

    @Test
    fun callsFromManyThreadsAtOnceDoNotDisturbEachOther() {
        val pool = Executors.newFixedThreadPool(8)
        try {
            val tasks = (0 until 8).map { thread ->
                Callable {
                    val own = Random(thread.toLong())
                    repeat(200) {
                        val key = ByteArray(32).also { own.nextBytes(it) }
                        val nonce = ByteArray(12).also { own.nextBytes(it) }
                        val plaintext = ByteArray(own.nextInt(300)).also { own.nextBytes(it) }
                        val sealed = aead.seal(key, nonce, plaintext, nonce)
                        check(aead.open(key, nonce, sealed, nonce)!!.contentEquals(plaintext))
                        check(aead.open(key, nonce, changed(sealed, 0), nonce) == null)
                    }
                }
            }
            pool.invokeAll(tasks).forEach { it.get(60, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
    }

    private companion object {
        const val KEK_A = "853b272a44db1421c02962669a55eb0994f3cab385ed1c4c79253eee19bab49e"
        const val WRAPPED_KEY_A =
            "4da4366623849589554fe3243a37b064a3117364b4ba9993b6a7c320723a8192ebc1ed80fb8f15516b561c63de23172b"
        const val HEADER_A = """{"format":"passwordvault","version":1,"cipher":"AES-256-GCM",""" +
            """"vault_id":"00000000-0000-4000-8000-000000000001","vk_id":"00000000-0000-4000-8000-000000000002",""" +
            """"generation":1,"created":1791244800000,"generator":"PasswordVault 1.0","writer_version":1,"body_len":4112,""" +
            """"slots":[{"type":"password","kdf":"argon2id","argon2_version":19,"m_kib":65536,"t":3,"p":4,""" +
            """"salt":"AAECAwQFBgcICQoLDA0ODw==","nonce":"ICEiIyQlJicoKSor",""" +
            """"wrapped_key":"TaQ2ZiOElYlVT+MkOjewZKMRc2S0upmTtqfDIHI6gZLrwe2A+48VUWtWHGPeIxcr"}]}"""
    }
}
