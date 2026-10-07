package io.github.dilshodbekmx.passwordvault.core.util

import io.github.dilshodbekmx.passwordvault.testing.FakeRandom
import java.util.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class UuidTest {
    @Test
    fun makeSetsTheVersionAndTheVariantAndKeepsTheRest() {
        assertEquals("00000000-0000-4000-8000-000000000000", Uuid.make(ByteArray(16)))
        assertEquals("ffffffff-ffff-4fff-bfff-ffffffffffff", Uuid.make(ByteArray(16) { -1 }))
        val random = ByteArray(16) { (it * 17 + 3).toByte() }
        val before = random.copyOf()
        val id = Uuid.make(random)
        assertArrayEquals("the caller's bytes are left alone", before, random)
        assertEquals("03142536-4758-497a-8b9c-adbecfe0f102", id)
        // Version 4, variant 10: exactly the six fixed bits differ from the random input.
        val bytes = Uuid.uuid16(id)
        for (i in 0 until 16) {
            val mask = when (i) {
                6 -> 0x0f
                8 -> 0x3f
                else -> 0xff
            }
            assertEquals(random[i].toInt() and mask, bytes[i].toInt() and mask)
        }
    }

    @Test
    fun makeNeedsSixteenBytes() {
        for (size in listOf(0, 15, 17, 32)) {
            try {
                Uuid.make(ByteArray(size))
                fail("accepted $size bytes")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    @Test
    fun everyMadeIdIsValid() {
        val random = Random(5)
        val seen = HashSet<String>()
        repeat(10_000) {
            val id = Uuid.make(ByteArray(16).also(random::nextBytes))
            assertTrue(id, Uuid.isValid(id))
            assertTrue(seen.add(id))
        }
    }

    @Test
    fun isValidTakesOnlyLowerCaseVersion4() {
        for (good in listOf(
            "00000000-0000-4000-8000-000000000001", // the vault_id of the vectors
            "0f6b2f0e-5d3b-4c1a-9a7e-2b1c8d4e6f70", // lock.md §5.4
            "7b0c1f8e-0b9a-4d27-9e3a-55c6f6d1a2b4",
        )) {
            assertTrue(good, Uuid.isValid(good))
        }
        for (bad in listOf(
            "", "00000000-0000-4000-8000-00000000000", "00000000-0000-4000-8000-0000000000011",
            "0F6B2F0E-5D3B-4C1A-9A7E-2B1C8D4E6F70", // upper case
            "00000000-0000-1000-8000-000000000001", // version 1
            "00000000-0000-4000-c000-000000000001", // variant 110
            "00000000-0000-4000-7000-000000000001", // variant 0
            "000000000000400080000000000000001", "{00000000-0000-4000-8000-000000000001}",
            "00000000-0000-4000-8000-000000000001\n", " 00000000-0000-4000-8000-000000000001",
            "00000000_0000_4000_8000_000000000001", "g0000000-0000-4000-8000-000000000001",
            "../../../../../../../../../../../..", "٠٠٠٠٠٠٠٠-٠٠٠٠-٤٠٠٠-٨٠٠٠-٠٠٠٠٠٠٠٠٠٠٠١", // Arabic-Indic digits
        )) {
            assertFalse(bad, Uuid.isValid(bad))
        }
    }

    @Test
    fun uuid16IsTheBytesMostSignificantFirst() {
        assertEquals("00000000000040008000000000000001", Bytes.hex(Uuid.uuid16("00000000-0000-4000-8000-000000000001")))
        assertEquals("0f6b2f0e5d3b4c1a9a7e2b1c8d4e6f70", Bytes.hex(Uuid.uuid16("0f6b2f0e-5d3b-4c1a-9a7e-2b1c8d4e6f70")))
    }

    @Test
    fun uuid16BuildsThePinMessageAndAadOfLockMd() {
        // lock.md §4.3: pin_msg = ASCII("pvlock-v1:pin-kek") ‖ 0x00 ‖ uuid16(vault_id) ‖ uuid16(slot_id) ‖ PIN (56 bytes),
        // AAD_pin = ASCII("pvlock-v1:pin-wrap") ‖ 0x00 ‖ uuid16(vault_id) ‖ uuid16(vk_id) ‖ uuid16(slot_id) (67 bytes).
        val vault = Uuid.uuid16("00000000-0000-4000-8000-000000000001")
        val vk = Uuid.uuid16("00000000-0000-4000-8000-000000000002")
        val slot = Uuid.uuid16("00000000-0000-4000-8000-000000000003")
        val ascii = { s: String -> s.toByteArray(Charsets.US_ASCII) }
        val pinMsg = ascii("pvlock-v1:pin-kek") + byteArrayOf(0) + vault + slot + ascii("582913")
        assertEquals(56, pinMsg.size)
        assertEquals(
            "70766c6f636b2d76313a70696e2d6b656b00" + "00000000000040008000000000000001" +
                "00000000000040008000000000000003" + "353832393133",
            Bytes.hex(pinMsg),
        )
        val aadPin = ascii("pvlock-v1:pin-wrap") + byteArrayOf(0) + vault + vk + slot
        assertEquals(67, aadPin.size)
        assertEquals(
            "70766c6f636b2d76313a70696e2d7772617000" + "00000000000040008000000000000001" +
                "00000000000040008000000000000002" + "00000000000040008000000000000003",
            Bytes.hex(aadPin),
        )
    }

    @Test
    fun uuid16RefusesAnInvalidIdWithoutQuotingIt() {
        for (bad in listOf("0F6B2F0E-5D3B-4C1A-9A7E-2B1C8D4E6F70", "00000000-0000-1000-8000-000000000001", "secret-text")) {
            try {
                Uuid.uuid16(bad)
                fail("accepted $bad")
            } catch (e: IllegalArgumentException) {
                assertFalse(e.message.orEmpty().contains(bad))
            }
        }
    }

    @Test
    fun fakeRandomMakesValidIdsAndHandsOutWhatWasQueued() {
        // The :testing module reaches the tests of :core (build plan 0.1) and its ids are real ones.
        val random = FakeRandom(seed = 42)
        val queued = "00000000-0000-4000-8000-000000000011"
        random.queueUuids(queued)
        assertEquals(queued, random.uuid())
        val first = random.uuid()
        assertTrue(first, Uuid.isValid(first))
        assertNotEquals(first, random.uuid())
        // The same seed gives the same ids.
        val again = FakeRandom(seed = 42)
        assertEquals(first, again.uuid())
    }
}
