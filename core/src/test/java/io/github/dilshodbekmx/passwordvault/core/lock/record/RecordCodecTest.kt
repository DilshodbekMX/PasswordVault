package io.github.dilshodbekmx.passwordvault.core.lock.record

import io.github.dilshodbekmx.passwordvault.core.lock.record.RecordCodec.Reason
import io.github.dilshodbekmx.passwordvault.core.util.B64
import io.github.dilshodbekmx.passwordvault.core.util.Bytes
import io.github.dilshodbekmx.passwordvault.core.util.ParseError
import io.github.dilshodbekmx.passwordvault.core.util.StrictJson
import java.security.MessageDigest
import java.util.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * lock.md test T3, the codec: the examples of §5.4 to §5.6 round-trip; every framing, hash, length
 * and field violation is DAMAGED, a third wrap among them; a valid record with `"v": 2` is NEWER;
 * random bytes never throw past the codec. (An I/O error is UNREADABLE and a missing file MISSING:
 * [FileRecordStoreTest].)
 */
class RecordCodecTest {

    // ------------------------------------------------------------------ the examples of §5.4 to §5.6

    /** The example of §5.4 as printed, its placeholders filled: the PIN vector's nonce and wrap of §4.3. */
    private val pinExample = """
        {
          "v": 1, "kind": "pin", "vault_id": "$VAULT",
          "slot": {
            "id": "$PIN_SLOT",
            "created": 1759752000000,
            "wraps": [ { "vk_id": "$VK1", "nonce": "$NONCE_B64", "ct": "$CT_B64" } ]
          },
          "guard": { "charged": false, "row": 0, "total": 0 },
          "off": null,
          "unseen": { "count": 0, "last_at": null, "interrupted": false }
        }
    """.trimIndent()

    private val pinExampleRecord = PinRecord(
        vaultId = VAULT,
        slot = ShortcutSlot(PIN_SLOT, 1_759_752_000_000L, listOf(KeyWrap(VK1, NONCE, CT))),
        guard = PinRecord.Guard(charged = false, row = 0, total = 0),
        off = null,
        unseen = PinRecord.Unseen(count = 0, lastAtMs = null, interrupted = false),
    )

    /** The example of §5.5, filled in the same way (`cipher.iv` as the nonce). */
    private val fingerprintExample = """
        {
          "v": 1, "kind": "fingerprint", "vault_id": "$VAULT",
          "slot": {
            "id": "$FP_SLOT", "created": 1759752300000,
            "wraps": [ { "vk_id": "$VK1", "nonce": "$NONCE_B64", "ct": "$CT_B64" } ]
          },
          "off": null
        }
    """.trimIndent()

    private val fingerprintExampleRecord = FingerprintRecord(
        vaultId = VAULT,
        slot = ShortcutSlot(FP_SLOT, 1_759_752_300_000L, listOf(KeyWrap(VK1, NONCE, CT))),
        off = null,
    )

    /** The example of §5.6, as printed. */
    private val stateExample = """
        {
          "v": 1, "kind": "state", "vault_id": "$VAULT",
          "practice":   { "last_ok": 1759752000000, "step": 0, "snoozed_until": null },
          "mp_guard":   { "failures": 0, "last_fail_elapsed_ms": null, "unseen": 0 },
          "mp_change":  null,
          "pending_mp": null,
          "replaced":   null,
          "max_generation": { "n": 57, "at": 1759752000000 }
        }
    """.trimIndent()

    private val stateExampleRecord = StateRecord(
        vaultId = VAULT,
        practice = StateRecord.Practice(lastOkMs = 1_759_752_000_000L, step = 0, snoozedUntilMs = null),
        mpGuard = StateRecord.MpGuard(failures = 0, lastFailElapsedMs = null, unseen = 0),
        mpChange = null,
        pendingMp = null,
        replaced = null,
        maxGeneration = StateRecord.MaxGeneration(n = 57, atMs = 1_759_752_000_000L),
    )

    @Test
    fun t3_theExamplesOf5_4To5_6RoundTrip() {
        roundTripExample(RecordCodec.Pin, pinExample, pinExampleRecord)
        roundTripExample(RecordCodec.Fingerprint, fingerprintExample, fingerprintExampleRecord)
        roundTripExample(RecordCodec.State, stateExample, stateExampleRecord)
    }

    private fun <T> roundTripExample(format: RecordCodec.Format<T>, exampleJson: String, expected: T) {
        // The example as printed, white space and all, framed by hand: it reads as the record.
        assertEquals(RecordRead.Ok(expected), format.decode(frame(exampleJson), VAULT))
        // The writer writes the same keys in the same order, without white space.
        val written = format.encode(expected)
        assertEquals(compact(exampleJson), jsonOf(written))
        assertArrayEquals(frame(compact(exampleJson)), written)
        assertEquals(RecordRead.Ok(expected), format.decode(written, VAULT))
    }

    @Test
    fun t3_everyFormOfEveryFieldRoundTrips() {
        for (record in pinRecords()) assertRoundTrip(RecordCodec.Pin, record)
        for (record in fingerprintRecords()) assertRoundTrip(RecordCodec.Fingerprint, record)
        for (record in stateRecords()) assertRoundTrip(RecordCodec.State, record)
    }

    private fun <T> assertRoundTrip(format: RecordCodec.Format<T>, record: T) {
        val bytes = format.encode(record)
        assertEquals(RecordRead.Ok(record), format.decode(bytes, VAULT))
        assertArrayEquals("the same record is written as the same bytes", bytes, format.encode(record))
        assertTrue("a record is under 2 KB", bytes.size < 2_048)
        assertTrue("ASCII only", bytes.copyOfRange(12, bytes.size - 32).all { it in 0x20..0x7e })
    }

    @Test
    fun t3_theWriterWritesEveryValueInItsOwnForm() {
        val pin = PinRecord(
            VAULT,
            slot = null,
            guard = PinRecord.Guard(charged = true, row = 4, total = 9),
            off = PinRecord.Off(PinRecord.OffReason.LIMIT, 1_759_752_000_123L),
            unseen = PinRecord.Unseen(count = 3, lastAtMs = 1_759_752_000_456L, interrupted = true),
        )
        assertEquals(
            """{"v":1,"kind":"pin","vault_id":"$VAULT","slot":null,"guard":{"charged":true,"row":4,"total":9},""" +
                """"off":{"reason":"limit","at":1759752000123},"unseen":{"count":3,"last_at":1759752000456,"interrupted":true}}""",
            jsonOf(RecordCodec.Pin.encode(pin)),
        )
        val state = fullState()
        assertEquals(
            """{"v":1,"kind":"state","vault_id":"$VAULT",""" +
                """"practice":{"last_ok":-5,"step":3,"snoozed_until":1759824000000},""" +
                """"mp_guard":{"failures":7,"last_fail_elapsed_ms":123456,"unseen":2},""" +
                """"mp_change":{"to_vk_id":"$VK2","at":1759752000000},""" +
                """"pending_mp":{"requested":1759752000000,"by":"fingerprint","credited_ms":3600000,"seen_elapsed_ms":98765,""" +
                """"vk_id":"$VK1","salt":"AAECAwQFBgcICQoLDA0ODw==","nonce":"$NONCE_B64","ct":"$CT_B64"},""" +
                """"replaced":{"new_vault_id":"$OTHER_VAULT","at":1759752000000,"by":"pin","credited_ms":0,"seen_elapsed_ms":4242},""" +
                """"max_generation":{"n":9999999999,"at":1759752000000}}""",
            jsonOf(RecordCodec.State.encode(state)),
        )
    }

    // ------------------------------------------------------------------ framing, hash and length

    @Test
    fun t3_everyFramingViolationIsDamaged() {
        val good = RecordCodec.Pin.encode(pinExampleRecord)
        val json = good.copyOfRange(12, good.size - 32)
        assertDamaged(Reason.MAGIC, 0, ByteArray(0))
        assertDamaged(Reason.MAGIC, 0, "PVLOCK0".toByteArray())
        assertDamaged(Reason.MAGIC, 0, good.copyOf().also { it[7] = '2'.code.toByte() }) // PVLOCK02
        assertDamaged(Reason.MAGIC, 0, good.copyOf().also { it[0] = 'p'.code.toByte() })
        assertDamaged(Reason.LENGTH, 8, "PVLOCK01".toByteArray())
        assertDamaged(Reason.LENGTH, 8, good.copyOf(11))
        // len outside 2 to 65,536, each with a hash that matches what is there
        for (len in listOf(0L, 1L, 65_537L, 0xFFFF_FFFFL)) assertDamaged(Reason.LENGTH, 8, frameRaw(json, len))
        // the file is not exactly as long as len says
        assertDamaged(Reason.LENGTH, 8, good.copyOf(good.size - 1)) // the hash cut short
        assertDamaged(Reason.LENGTH, 8, good + byteArrayOf(0)) // nothing may follow
        assertDamaged(Reason.LENGTH, 8, good + good)
        assertDamaged(Reason.LENGTH, 8, frameRaw(json, json.size + 1L))
        assertDamaged(Reason.LENGTH, 8, frameRaw(json, json.size - 1L))
    }

    @Test
    fun t3_theLengthBoundsAre2And65536() {
        // 65,536 bytes of JSON (the record padded with spaces) is still a record; 65,537 is not.
        val json = compact(pinExample)
        val padded = json + " ".repeat(RecordCodec.MAX_JSON_LENGTH - json.length)
        assertEquals(RecordRead.Ok(pinExampleRecord), RecordCodec.Pin.decode(frame(padded), VAULT))
        assertDamaged(Reason.LENGTH, 8, frame("$padded "))
        // 2 bytes, "{}": framing passes, and the record then misses its keys.
        assertDamaged(Reason.MISSING_KEY, 12, frame("{}"))
        assertDamaged(Reason.LENGTH, 8, frame("1"))
    }

    @Test
    fun t3_aWrongHashIsDamaged() {
        val good = RecordCodec.State.encode(stateExampleRecord)
        val hashAt = good.size - 32L
        assertDamaged(Reason.HASH, hashAt, good.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }, RecordCodec.State)
        assertDamaged(Reason.HASH, hashAt, good.copyOf().also { it[20] = (it[20].toInt() xor 0x20).toByte() }, RecordCodec.State)
        // The hash covers the magic and len too: they are fine here, the JSON is not the one hashed.
        val other = RecordCodec.State.encode(stateExampleRecord.copy(maxGeneration = StateRecord.MaxGeneration(58, 1_759_752_000_000L)))
        val spliced = good.copyOf()
        other.copyInto(spliced, 0, 0, other.size - 32) // same length: "57" and "58"
        assertDamaged(Reason.HASH, hashAt, spliced, RecordCodec.State)
    }

    @Test
    fun t3_everySingleBitFlipIsDamaged() {
        for (format in RecordCodec.FORMATS) {
            val good = encodeAny(format)
            for (i in good.indices) {
                for (bit in 0 until 8) {
                    val bad = good.copyOf()
                    bad[i] = (bad[i].toInt() xor (1 shl bit)).toByte()
                    val read = format.decode(bad, VAULT)
                    assertTrue("${format.kind}: byte $i bit $bit gave $read", read is RecordRead.Damaged)
                }
            }
        }
    }

    // ------------------------------------------------------------------ the JSON

    @Test
    fun t3_jsonThatTheStrictReaderRefusesIsDamaged() {
        val body = compact(pinExample)
        assertJsonDamaged("not-an-object", 12, "[]")
        assertJsonDamaged("not-an-object", 12, "\"pin\"")
        assertJsonDamaged("duplicate-key", null, body.replace("\"total\":0", "\"total\":0,\"row\":0"))
        assertJsonDamaged("duplicate-key", null, body.replaceFirst("{", "{\"v\":1,"))
        assertJsonDamaged("trailing-characters", null, "$body x")
        assertJsonDamaged("end-of-input", null, body.dropLast(1))
        assertJsonDamaged("unexpected-character", null, body.replace("false", "False"))
        assertJsonDamaged("bad-number", null, body.replace("\"row\":0", "\"row\":00"))
        assertJsonDamaged("control-character", null, body.replace(PIN_SLOT, PIN_SLOT.dropLast(1) + "\t"))
        assertJsonDamaged("bad-escape", null, body.replace(PIN_SLOT, "\\x"))
        assertJsonDamaged("byte-order-mark", 12, "﻿" + body)
        // malformed UTF-8 in the JSON, with a hash that matches it
        val bytes = body.toByteArray()
        val at = body.indexOf(PIN_SLOT)
        bytes[at] = 0xC0.toByte()
        assertDamaged("malformed-utf8", 12L + at, frameBytes(bytes))
        // nesting: 16 levels are read (and refused as fields), 17 are not
        val deep16 = "{\"v\":1,\"kind\":\"pin\",\"vault_id\":\"$VAULT\",\"x\":" + "[".repeat(15) + "]".repeat(15) + "}"
        assertDamaged(Reason.UNKNOWN_KEY, 12, frame(deep16.replace(",\"x\":", ",\"slot\":null,\"guard\":{\"charged\":false,\"row\":0,\"total\":0},\"off\":null,\"unseen\":{\"count\":0,\"last_at\":null,\"interrupted\":false},\"x\":")))
        val deep17 = "{\"v\":1,\"x\":" + "[".repeat(16) + "]".repeat(16) + "}"
        assertJsonDamaged("too-deep", null, deep17)
    }

    private fun assertJsonDamaged(reason: String, offset: Long?, json: String) {
        val read = RecordCodec.Pin.decode(frame(json), VAULT)
        assertTrue("$reason: $read", read is RecordRead.Damaged)
        val error = (read as RecordRead.Damaged).error
        assertEquals(reason, error.reason)
        if (offset != null) assertEquals(offset, error.offset)
        assertTrue("the offset points into the JSON", error.offset >= 12)
    }

    // ------------------------------------------------------------------ the version

    @Test
    fun t3_aValidRecordWithV2IsNewer() {
        for (format in RecordCodec.FORMATS) {
            val json = jsonOf(encodeAny(format))
            assertEquals(RecordRead.Newer, format.decode(frame(json.replace("\"v\":1", "\"v\":2")), VAULT))
            // Nothing else of a newer record is read: new keys, new kinds, other values are its own.
            val newer = """{"v":2,"kind":"${format.kind}2","vault_id":7,"new":{"a":[1,2,3]}}"""
            assertEquals(RecordRead.Newer, format.decode(frame(newer), VAULT))
            assertEquals(RecordRead.Newer, format.decode(frame("""{"v":9223372036854775807}"""), VAULT))
            assertEquals(RecordRead.Newer, format.decode(frame("""{"x":0,"v":3}"""), VAULT))
        }
        // But the framing comes first: a newer record with a bad hash is DAMAGED.
        val newer = frame(jsonOf(RecordCodec.Pin.encode(pinExampleRecord)).replace("\"v\":1", "\"v\":2"))
        newer[newer.size - 1] = (newer[newer.size - 1].toInt() xor 1).toByte()
        assertDamaged(Reason.HASH, newer.size - 32L, newer)
    }

    @Test
    fun t3_aVersionThatIsNot1OrAbove1IsDamaged() {
        val json = compact(pinExample)
        assertDamaged(Reason.VERSION, 12, frame(json.replace("\"v\":1", "\"v\":0")))
        assertDamaged(Reason.VERSION, 12, frame(json.replace("\"v\":1", "\"v\":-1")))
        assertDamaged(Reason.WRONG_TYPE, 12, frame(json.replace("\"v\":1", "\"v\":\"1\"")))
        assertDamaged(Reason.WRONG_TYPE, 12, frame(json.replace("\"v\":1", "\"v\":1.0")))
        assertDamaged(Reason.WRONG_TYPE, 12, frame(json.replace("\"v\":1", "\"v\":2e0")))
        assertDamaged(Reason.WRONG_TYPE, 12, frame(json.replace("\"v\":1", "\"v\":null")))
        assertDamaged(Reason.WRONG_TYPE, 12, frame(json.replace("\"v\":1", "\"v\":99999999999999999999")))
        assertDamaged(Reason.MISSING_KEY, 12, frame(json.replace("\"v\":1,", "")))
    }

    // ------------------------------------------------------------------ whose record, which record

    @Test
    fun t3_kindAndVaultIdMustMatchTheFileAndItsDirectory() {
        val pin = RecordCodec.Pin.encode(pinExampleRecord)
        assertDamaged(Reason.KIND, 12, pin, RecordCodec.State) // pin.rec's bytes read as state.rec
        assertDamaged(Reason.KIND, 12, pin, RecordCodec.Fingerprint)
        assertDamaged(Reason.KIND, 12, RecordCodec.State.encode(stateExampleRecord), RecordCodec.Pin)
        assertDamaged(Reason.VAULT_ID, 12, pin, RecordCodec.Pin, OTHER_VAULT) // copied into another vault's directory
        assertDamaged(Reason.VAULT_ID, 12, pin, RecordCodec.Pin, VAULT.uppercase())
        val json = compact(pinExample)
        assertDamaged(Reason.WRONG_TYPE, 12, frame(json.replace("\"kind\":\"pin\"", "\"kind\":1")))
        assertDamaged(Reason.KIND, 12, frame(json.replace("\"kind\":\"pin\"", "\"kind\":\"PIN\"")))
        assertDamaged(Reason.MISSING_KEY, 12, frame(json.replace("\"kind\":\"pin\",", "")))
        assertDamaged(Reason.WRONG_TYPE, 12, frame(json.replace("\"vault_id\":\"$VAULT\"", "\"vault_id\":null")))
        assertDamaged(Reason.MISSING_KEY, 12, frame(json.replace("\"vault_id\":\"$VAULT\",", "")))
        // A directory whose name is not a vault id: a record that names it is still refused.
        val notAnId = "lost+found"
        assertDamaged(Reason.BAD_ID, 12, frame(json.replace(VAULT, notAnId)), RecordCodec.Pin, notAnId)
    }

    // ------------------------------------------------------------------ keys and types

    @Test
    fun t3_aMissingKeyAtAnyLevelIsDamaged() {
        for ((format, json) in fullJsons()) {
            for (key in keysIn(json)) {
                val without = removeKey(json, key)
                assertDamaged("${format.kind} without $key", Reason.MISSING_KEY, frame(without), format)
            }
        }
    }

    @Test
    fun t3_anUnknownKeyAtAnyLevelIsDamaged() {
        for ((format, json) in fullJsons()) {
            // after every '{' that opens an object of the record, add a key it does not have
            var at = json.indexOf('{')
            var objects = 0
            while (at >= 0) {
                val with = json.substring(0, at + 1) + "\"extra\":0," + json.substring(at + 1)
                assertDamaged("${format.kind}: an extra key in object $objects", Reason.UNKNOWN_KEY, frame(with), format)
                objects++
                at = json.indexOf('{', at + 1)
            }
            assertTrue(objects >= 2)
        }
    }

    @Test
    fun t3_aWrongTypeIsDamaged() {
        val pin = jsonOf(RecordCodec.Pin.encode(twoWrapRecord()))
        val wrongs = listOf(
            "\"slot\":{" to "\"slot\":[{",
            "\"slot\":{" to "\"slot\":\"{",
            "\"wraps\":[" to "\"wraps\":{\"a\":[",
            "\"charged\":true" to "\"charged\":1",
            "\"charged\":true" to "\"charged\":\"true\"",
            "\"charged\":true" to "\"charged\":null",
            "\"row\":2" to "\"row\":\"2\"",
            "\"row\":2" to "\"row\":2.0",
            "\"row\":2" to "\"row\":true",
            "\"total\":7" to "\"total\":7e0",
            "\"created\":1759752000000" to "\"created\":\"1759752000000\"",
            "\"created\":1759752000000" to "\"created\":1.759752e12",
            "\"created\":1759752000000" to "\"created\":null",
            "\"count\":0" to "\"count\":[]",
            "\"last_at\":null" to "\"last_at\":false",
            "\"interrupted\":false" to "\"interrupted\":\"no\"",
            "\"vk_id\":\"$VK1\"" to "\"vk_id\":1",
            "\"nonce\":\"$NONCE_B64\"" to "\"nonce\":[16,17]",
            "\"guard\":{" to "\"guard\":[{",
            "\"unseen\":{" to "\"unseen\":7,\"x\":{",
            "\"off\":null" to "\"off\":\"limit\"",
            "\"off\":null" to "\"off\":[]",
        )
        for ((from, to) in wrongs) {
            assertTrue("$from is in the record", pin.contains(from))
            val read = RecordCodec.Pin.decode(frame(pin.replaceFirst(from, to)), VAULT)
            assertTrue("$to: $read", read is RecordRead.Damaged)
        }
        // the plain cases name the rule
        assertDamaged(Reason.WRONG_TYPE, 12, frame(pin.replaceFirst("\"row\":2", "\"row\":2.0")))
        assertDamaged(Reason.WRONG_TYPE, 12, frame(pin.replaceFirst("\"slot\":{", "\"slot\":[{").replaceFirst("]},\"guard\"", "]}],\"guard\"")))
        val state = jsonOf(RecordCodec.State.encode(fullState()))
        assertDamaged(Reason.WRONG_TYPE, 12, frame(state.replace("\"step\":3", "\"step\":\"3\"")), RecordCodec.State)
        assertDamaged(Reason.WRONG_TYPE, 12, frame(state.replace("\"mp_change\":{", "\"mp_change\":[{").replace(",\"at\":1759752000000},\"pending_mp\"", ",\"at\":1759752000000}],\"pending_mp\"")), RecordCodec.State)
        assertDamaged(Reason.WRONG_TYPE, 12, frame(state.replace("\"n\":9999999999", "\"n\":\"9999999999\"")), RecordCodec.State)
    }

    @Test
    fun t3_base64ThatIsNotCanonicalIsDamaged() {
        val json = compact(pinExample)
        val nonCanonical = listOf(
            NONCE_B64.dropLast(1), // missing a character
            NONCE_B64.substring(0, 8) + " " + NONCE_B64.substring(8, 15), // white space
            "EBESExQVFhcYGRob==", // padding where none belongs
        )
        for (bad in nonCanonical) {
            assertDamaged("nonce '$bad'", Reason.WRONG_TYPE, frame(json.replace(NONCE_B64, bad)))
        }
        assertDamaged(Reason.WRONG_TYPE, 12, frame(json.replace(NONCE_B64, "EBESExQVFhcYGRo-")))
    }

    // ------------------------------------------------------------------ the field rules of §5.4 to §5.6

    @Test
    fun t3_aThirdWrapIsDamaged() {
        val json = jsonOf(RecordCodec.Pin.encode(twoWrapRecord()))
        val third = """{"vk_id":"$VK3","nonce":"$NONCE_B64","ct":"$CT_B64"}"""
        val three = json.replace("]},\"guard\"", ",$third]},\"guard\"")
        assertTrue(three != json)
        assertDamaged(Reason.WRAPS, 12, frame(three))
        val fp = jsonOf(RecordCodec.Fingerprint.encode(fingerprintExampleRecord))
        val fpThree = fp.replace("]},\"off\"", ",$third,${third.replace(VK3, VK2)}]},\"off\"")
        assertDamaged(Reason.WRAPS, 12, frame(fpThree), RecordCodec.Fingerprint)
        // and the writer refuses to make one
        assertRefused(Reason.WRAPS) {
            RecordCodec.Pin.encode(twoWrapRecord().copy(slot = ShortcutSlot(PIN_SLOT, 1L, listOf(wrap(VK1), wrap(VK2), wrap(VK3)))))
        }
    }

    @Test
    fun t3_aSlotWithoutAWrapOrWithTwoWrapsForOneKeyIsDamaged() {
        val json = compact(pinExample)
        assertDamaged(Reason.WRAPS, 12, frame(json.replace(Regex("\"wraps\":\\[.*?]"), "\"wraps\":[]")))
        val one = """{"vk_id":"$VK1","nonce":"$NONCE_B64","ct":"$CT_B64"}"""
        assertDamaged(Reason.WRAPS, 12, frame(json.replace(one, "$one,$one")))
        val same = """{"vk_id":"$VK1","nonce":"AAAAAAAAAAAAAAAA","ct":"$CT_B64"}"""
        assertDamaged(Reason.WRAPS, 12, frame(json.replace(one, "$one,$same")))
        assertRefused(Reason.WRAPS) { RecordCodec.Pin.encode(pinExampleRecord.copy(slot = ShortcutSlot(PIN_SLOT, 1L, emptyList()))) }
        assertRefused(Reason.WRAPS) {
            RecordCodec.Fingerprint.encode(fingerprintExampleRecord.copy(slot = ShortcutSlot(FP_SLOT, 1L, listOf(wrap(VK1), wrap(VK1)))))
        }
    }

    @Test
    fun t3_wrongLengthsAreDamaged() {
        val json = compact(pinExample)
        for (size in listOf(0, 11, 13, 16)) {
            assertDamaged("nonce of $size bytes", Reason.WRONG_LENGTH, frame(json.replace(NONCE_B64, b64(size))))
        }
        for (size in listOf(0, 32, 47, 49, 64)) {
            assertDamaged("ct of $size bytes", Reason.WRONG_LENGTH, frame(json.replace(CT_B64, b64(size))))
        }
        val state = jsonOf(RecordCodec.State.encode(fullState()))
        val salt = "AAECAwQFBgcICQoLDA0ODw=="
        for (size in listOf(0, 15, 17, 32)) {
            assertDamaged("salt of $size bytes", Reason.WRONG_LENGTH, frame(state.replace(salt, b64(size))), RecordCodec.State)
        }
        assertDamaged(Reason.WRONG_LENGTH, 12, frame(state.replace(NONCE_B64, b64(11))), RecordCodec.State)
        assertDamaged(Reason.WRONG_LENGTH, 12, frame(state.replace(CT_B64, b64(47))), RecordCodec.State)
    }

    @Test
    fun t3_idsMustBeLowerCaseUuidsOfVersion4() {
        val bads = listOf(
            PIN_SLOT.uppercase(),
            PIN_SLOT.replaceRange(14, 15, "1"), // version 1
            PIN_SLOT.replaceRange(19, 20, "c"), // another variant
            PIN_SLOT.drop(1),
            PIN_SLOT.replace("-", ""),
            "",
        )
        val pin = compact(pinExample)
        for (bad in bads) assertDamaged("slot.id '$bad'", Reason.BAD_ID, frame(pin.replace(PIN_SLOT, bad)))
        assertDamaged(Reason.BAD_ID, 12, frame(pin.replace(VK1, VK1.uppercase())))
        val state = jsonOf(RecordCodec.State.encode(fullState()))
        assertDamaged(Reason.BAD_ID, 12, frame(state.replace(VK2, VK2.uppercase())), RecordCodec.State) // mp_change.to_vk_id
        assertDamaged(Reason.BAD_ID, 12, frame(state.replace(OTHER_VAULT, "other")), RecordCodec.State) // replaced.new_vault_id
        assertDamaged(Reason.BAD_ID, 12, frame(state.replace("\"vk_id\":\"$VK1\"", "\"vk_id\":\"x\"")), RecordCodec.State)
    }

    @Test
    fun t3_numbersOutsideTheirRangeAreDamaged() {
        val pin = jsonOf(RecordCodec.Pin.encode(twoWrapRecord())) // row 2, total 7, count 0
        for (row in listOf("-1", "6", "2147483648", "-9223372036854775808")) {
            assertDamaged("row $row", Reason.OUT_OF_RANGE, frame(pin.replace("\"row\":2", "\"row\":$row")))
        }
        for (total in listOf("-1", "11", "99999999999")) {
            assertDamaged("total $total", Reason.OUT_OF_RANGE, frame(pin.replace("\"total\":7", "\"total\":$total")))
        }
        assertDamaged(Reason.OUT_OF_RANGE, 12, frame(pin.replace("\"count\":0", "\"count\":-1")))
        // the bounds themselves are records
        assertTrue(RecordCodec.Pin.decode(frame(pin.replace("\"row\":2", "\"row\":5").replace("\"total\":7", "\"total\":10")), VAULT) is RecordRead.Ok)
        assertTrue(RecordCodec.Pin.decode(frame(pin.replace("\"row\":2", "\"row\":0").replace("\"total\":7", "\"total\":0")), VAULT) is RecordRead.Ok)

        val state = jsonOf(RecordCodec.State.encode(fullState()))
        val outOfRange = listOf(
            "\"step\":3" to "\"step\":4",
            "\"step\":3" to "\"step\":-1",
            "\"failures\":7" to "\"failures\":-1",
            "\"failures\":7" to "\"failures\":2147483648",
            "\"last_fail_elapsed_ms\":123456" to "\"last_fail_elapsed_ms\":-1",
            "\"unseen\":2" to "\"unseen\":-2",
            "\"credited_ms\":3600000" to "\"credited_ms\":-1",
            "\"seen_elapsed_ms\":98765" to "\"seen_elapsed_ms\":-1",
            "\"credited_ms\":0" to "\"credited_ms\":-5",
            "\"seen_elapsed_ms\":4242" to "\"seen_elapsed_ms\":-4242",
            "\"n\":9999999999" to "\"n\":-1",
        )
        for ((from, to) in outOfRange) {
            assertTrue("$from is in the record", state.contains(from))
            assertDamaged(to, Reason.OUT_OF_RANGE, frame(state.replace(from, to)), RecordCodec.State)
        }
        assertTrue(RecordCodec.State.decode(frame(state.replace("\"step\":3", "\"step\":0")), VAULT) is RecordRead.Ok)
    }

    @Test
    fun t3_wordsOutsideTheirListsAreDamaged() {
        val off = jsonOf(RecordCodec.Pin.encode(offRecord(PinRecord.OffReason.KEY_GONE)))
        for (bad in listOf("turned_off", "LIMIT", "limit ", "")) {
            assertDamaged("pin off.reason '$bad'", Reason.BAD_VALUE, frame(off.replace("\"key_gone\"", "\"$bad\"")))
        }
        val fpOff = jsonOf(RecordCodec.Fingerprint.encode(FingerprintRecord(VAULT, null, FingerprintRecord.Off(FingerprintRecord.OffReason.TURNED_OFF, 5L))))
        assertDamaged(Reason.BAD_VALUE, 12, frame(fpOff.replace("\"turned_off\"", "\"limit\"")), RecordCodec.Fingerprint)
        val state = jsonOf(RecordCodec.State.encode(fullState()))
        assertDamaged(Reason.BAD_VALUE, 12, frame(state.replace("\"by\":\"fingerprint\"", "\"by\":\"face\"")), RecordCodec.State)
        assertDamaged(Reason.BAD_VALUE, 12, frame(state.replace("\"by\":\"pin\"", "\"by\":\"Pin\"")), RecordCodec.State)
    }

    @Test
    fun t3_aSlotAndAnOffTogetherAreDamaged() {
        val json = compact(pinExample).replace("\"off\":null", "\"off\":{\"reason\":\"limit\",\"at\":5}")
        assertDamaged(Reason.SLOT_AND_OFF, 12, frame(json))
        val fp = compact(fingerprintExample).replace("\"off\":null", "\"off\":{\"reason\":\"turned_off\",\"at\":5}")
        assertDamaged(Reason.SLOT_AND_OFF, 12, frame(fp), RecordCodec.Fingerprint)
        assertRefused(Reason.SLOT_AND_OFF) {
            RecordCodec.Pin.encode(pinExampleRecord.copy(off = PinRecord.Off(PinRecord.OffReason.LIMIT, 5L)))
        }
    }

    @Test
    fun t3_wallClockTimesMayBeAnyWholeNumber() {
        // A phone whose clock was set before 1970 still writes and reads its records (lock.md I8).
        val early = pinExampleRecord.copy(
            slot = ShortcutSlot(PIN_SLOT, Long.MIN_VALUE, listOf(KeyWrap(VK1, NONCE, CT))),
            unseen = PinRecord.Unseen(1, -1L, false),
        )
        assertRoundTrip(RecordCodec.Pin, early)
        assertRoundTrip(RecordCodec.State, fullState().copy(practice = StateRecord.Practice(Long.MIN_VALUE, 0, Long.MAX_VALUE)))
    }

    @Test
    fun t3_theWriterRefusesEveryRecordTheReaderWouldRefuseAndSaysNoValue() {
        val refusals: List<Pair<Reason, () -> ByteArray>> = listOf(
            Reason.BAD_ID to { RecordCodec.Pin.encode(pinExampleRecord.copy(vaultId = "x")) },
            Reason.BAD_ID to { RecordCodec.Pin.encode(pinExampleRecord.copy(slot = ShortcutSlot("SLOT", 1L, listOf(wrap(VK1))))) },
            Reason.BAD_ID to { RecordCodec.Pin.encode(pinExampleRecord.copy(slot = ShortcutSlot(PIN_SLOT, 1L, listOf(wrap("vk"))))) },
            Reason.WRONG_LENGTH to { RecordCodec.Pin.encode(pinExampleRecord.copy(slot = ShortcutSlot(PIN_SLOT, 1L, listOf(KeyWrap(VK1, ByteArray(11), CT))))) },
            Reason.WRONG_LENGTH to { RecordCodec.Pin.encode(pinExampleRecord.copy(slot = ShortcutSlot(PIN_SLOT, 1L, listOf(KeyWrap(VK1, NONCE, ByteArray(49)))))) },
            Reason.OUT_OF_RANGE to { RecordCodec.Pin.encode(pinExampleRecord.copy(guard = PinRecord.Guard(false, 6, 0))) },
            Reason.OUT_OF_RANGE to { RecordCodec.Pin.encode(pinExampleRecord.copy(guard = PinRecord.Guard(false, 0, 11))) },
            Reason.OUT_OF_RANGE to { RecordCodec.Pin.encode(pinExampleRecord.copy(unseen = PinRecord.Unseen(-1, null, false))) },
            Reason.OUT_OF_RANGE to { RecordCodec.State.encode(stateExampleRecord.copy(practice = StateRecord.Practice(0L, 4, null))) },
            Reason.OUT_OF_RANGE to { RecordCodec.State.encode(stateExampleRecord.copy(mpGuard = StateRecord.MpGuard(0, -1L, 0))) },
            Reason.OUT_OF_RANGE to { RecordCodec.State.encode(stateExampleRecord.copy(maxGeneration = StateRecord.MaxGeneration(-1L, 0L))) },
            Reason.WRONG_LENGTH to { RecordCodec.State.encode(fullState().copy(pendingMp = pending(salt = ByteArray(15)))) },
            Reason.BAD_ID to { RecordCodec.State.encode(fullState().copy(mpChange = StateRecord.MpChange(VK2.uppercase(), 0L))) },
        )
        for ((reason, encode) in refusals) {
            try {
                encode()
                fail("written although it breaks '${reason.code}'")
            } catch (e: IllegalArgumentException) {
                val message = e.message ?: ""
                assertTrue("'$message' names the rule '${reason.code}'", message.contains("'${reason.code}'"))
                for (value in listOf(VAULT, PIN_SLOT, VK1, VK2, "SLOT", NONCE_B64)) {
                    assertFalse("'$message' holds no value", message.contains(value))
                }
            }
        }
    }

    // ------------------------------------------------------------------ random bytes

    @Test
    fun t3_randomBytesNeverThrowPastTheCodec() {
        val random = Random(20_261_007L)
        var results = 0
        for (n in 0 until 30_000) {
            val bytes = ByteArray(random.nextInt(160)).also { random.nextBytes(it) }
            if (n % 3 == 0 && bytes.size >= 8) "PVLOCK01".toByteArray().copyInto(bytes) // past the magic
            for (format in RecordCodec.FORMATS) {
                assertReasonable(format.decode(bytes, VAULT))
                results++
            }
        }
        assertEquals(90_000, results)
    }

    @Test
    fun t3_mutatedRecordsWithAGoodHashNeverThrowPastTheCodec() {
        // Mutations behind a hash that matches them reach the JSON reader and the field rules.
        val random = Random(57L)
        val outcomes = HashMap<String, Int>()
        for (n in 0 until 40_000) {
            val format = RecordCodec.FORMATS[n % 3]
            val json = jsonOf(encodeAny(format, random.nextInt(4))).toByteArray().toMutableList()
            repeat(1 + random.nextInt(3)) {
                when (random.nextInt(4)) {
                    0 -> if (json.isNotEmpty()) json[random.nextInt(json.size)] = MUTATIONS[random.nextInt(MUTATIONS.length)].code.toByte()
                    1 -> if (json.isNotEmpty()) json.removeAt(random.nextInt(json.size))
                    2 -> json.add(random.nextInt(json.size + 1), MUTATIONS[random.nextInt(MUTATIONS.length)].code.toByte())
                    else -> if (json.isNotEmpty()) json[random.nextInt(json.size)] = random.nextInt(256).toByte()
                }
            }
            val read = format.decode(frameBytes(json.toByteArray()), VAULT)
            assertReasonable(read)
            val key = when (read) {
                is RecordRead.Damaged -> read.error.reason
                else -> read.javaClass.simpleName
            }
            outcomes[key] = (outcomes[key] ?: 0) + 1
        }
        // The mutations reached every layer: the JSON reader, the version, the keys and the field rules.
        for (reached in listOf("Ok", "Newer", "unexpected-character", "missing-key", "wrong-type")) {
            assertTrue("$reached in $outcomes", (outcomes[reached] ?: 0) > 0)
        }
    }

    private fun assertReasonable(read: RecordRead<*>) {
        when (read) {
            is RecordRead.Damaged -> {
                assertTrue("reason '${read.error.reason}' is from the codec's list", read.error.reason in RecordCodec.REASON_CODES)
                assertTrue(read.error.offset >= 0)
            }
            is RecordRead.Ok, RecordRead.Newer -> Unit
            RecordRead.Missing, RecordRead.Unreadable -> fail("the codec never says $read: those are the file's")
        }
    }

    @Test
    fun t3_reasonCodesAreAFixedListThatHoldsNoInput() {
        assertEquals(Reason.entries.size + StrictJson.Reason.entries.size, RecordCodec.REASON_CODES.size)
        for (code in RecordCodec.REASON_CODES) assertTrue(code, code.matches(Regex("[a-z0-9-]+")))
        assertEquals(listOf("pin.rec", "fingerprint.rec", "state.rec"), RecordCodec.FORMATS.map { it.fileName })
    }

    // ------------------------------------------------------------------ helpers

    private fun assertDamaged(reason: Reason, offset: Long, bytes: ByteArray, format: RecordCodec.Format<*> = RecordCodec.Pin, vaultId: String = VAULT) {
        val read = format.decode(bytes, vaultId)
        assertEquals(RecordRead.Damaged(ParseError(offset, reason.code)), read)
    }

    private fun assertDamaged(what: String, reason: Reason, bytes: ByteArray, format: RecordCodec.Format<*> = RecordCodec.Pin) {
        val read = format.decode(bytes, VAULT)
        assertEquals(what, RecordRead.Damaged(ParseError(12, reason.code)), read)
    }

    private fun assertDamaged(reason: String, offset: Long, bytes: ByteArray) {
        assertEquals(RecordRead.Damaged(ParseError(offset, reason)), RecordCodec.Pin.decode(bytes, VAULT))
    }

    private fun assertRefused(reason: Reason, write: () -> Unit) {
        try {
            write()
            fail("written although it breaks '${reason.code}'")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message ?: "", (e.message ?: "").contains("'${reason.code}'"))
        }
    }

    private fun <T> encodeAny(format: RecordCodec.Format<T>, variant: Int = 0): ByteArray {
        @Suppress("UNCHECKED_CAST")
        val record = when (format) {
            RecordCodec.Pin -> pinRecords()[variant % pinRecords().size]
            RecordCodec.Fingerprint -> fingerprintRecords()[variant % fingerprintRecords().size]
            RecordCodec.State -> stateRecords()[variant % stateRecords().size]
        } as T
        return format.encode(record)
    }

    /** The JSON of a framed record. */
    private fun jsonOf(record: ByteArray): String = String(record, 12, record.size - 44, Charsets.UTF_8)

    /** The examples without the white space between tokens (none of their strings holds a space). */
    private fun compact(json: String): String = json.filterNot { it == ' ' || it == '\n' }

    private fun frame(json: String): ByteArray = frameBytes(json.toByteArray(Charsets.UTF_8))

    private fun frameBytes(json: ByteArray): ByteArray = frameRaw(json, json.size.toLong())

    /** Magic, [len] as written (whatever the JSON's real length), the JSON, and a SHA-256 that matches all of it. */
    private fun frameRaw(json: ByteArray, len: Long): ByteArray {
        val head = "PVLOCK01".toByteArray() +
            byteArrayOf((len ushr 24).toByte(), (len ushr 16).toByte(), (len ushr 8).toByte(), len.toByte())
        val body = head + json
        return body + MessageDigest.getInstance("SHA-256").digest(body)
    }

    /** The JSON texts with every optional value set, for the key tests. */
    private fun fullJsons(): List<Pair<RecordCodec.Format<*>, String>> = listOf(
        RecordCodec.Pin to jsonOf(RecordCodec.Pin.encode(twoWrapRecord())),
        RecordCodec.Pin to jsonOf(RecordCodec.Pin.encode(offRecord(PinRecord.OffReason.LIMIT))),
        RecordCodec.Fingerprint to jsonOf(RecordCodec.Fingerprint.encode(fingerprintExampleRecord)),
        RecordCodec.Fingerprint to jsonOf(
            RecordCodec.Fingerprint.encode(FingerprintRecord(VAULT, null, FingerprintRecord.Off(FingerprintRecord.OffReason.BAD_WRAP, 9L))),
        ),
        RecordCodec.State to jsonOf(RecordCodec.State.encode(fullState())),
    )

    /** Every key of [json], with the position of its quote: `"name":`. */
    private fun keysIn(json: String): List<Int> = Regex("\"[a-z_]+\":").findAll(json).map { it.range.first }.toList()

    /** [json] without the key at [at] and its value (and the comma that goes with them). */
    private fun removeKey(json: String, at: Int): String {
        val colon = json.indexOf(':', at)
        var i = colon + 1
        var depth = 0
        var inString = false
        while (i < json.length) {
            val c = json[i]
            if (inString) {
                if (c == '"') inString = false
            } else {
                when (c) {
                    '"' -> inString = true
                    '{', '[' -> depth++
                    '}', ']' -> {
                        if (depth == 0) break
                        depth--
                    }
                    ',' -> if (depth == 0) break
                }
            }
            i++
        }
        return when {
            i < json.length && json[i] == ',' -> json.removeRange(at, i + 1) // "key":value,
            json[at - 1] == ',' -> json.removeRange(at - 1, i) // ,"key":value} at the end
            else -> json.removeRange(at, i) // the only key
        }
    }

    private fun b64(size: Int): String = B64.encode(ByteArray(size) { (it * 7).toByte() })

    private fun twoWrapRecord() = PinRecord(
        VAULT,
        slot = ShortcutSlot(PIN_SLOT, 1_759_752_000_000L, listOf(KeyWrap(VK1, NONCE, CT), KeyWrap(VK2, NONCE2, CT2))),
        guard = PinRecord.Guard(charged = true, row = 2, total = 7),
        off = null,
        unseen = PinRecord.Unseen(count = 0, lastAtMs = null, interrupted = false),
    )

    private fun offRecord(reason: PinRecord.OffReason) = PinRecord(
        VAULT,
        slot = null,
        guard = PinRecord.Guard(charged = false, row = 5, total = 9),
        off = PinRecord.Off(reason, 1_759_752_111_000L),
        unseen = PinRecord.Unseen(count = 5, lastAtMs = 1_759_752_110_000L, interrupted = true),
    )

    private fun pending(salt: ByteArray = SALT) = StateRecord.PendingMp(
        requestedMs = 1_759_752_000_000L,
        by = Shortcut.FINGERPRINT,
        creditedMs = 3_600_000L,
        seenElapsedMs = 98_765L,
        vkId = VK1,
        salt = salt,
        nonce = NONCE,
        ct = CT,
    )

    private fun fullState() = StateRecord(
        VAULT,
        practice = StateRecord.Practice(lastOkMs = -5L, step = 3, snoozedUntilMs = 1_759_824_000_000L),
        mpGuard = StateRecord.MpGuard(failures = 7, lastFailElapsedMs = 123_456L, unseen = 2),
        mpChange = StateRecord.MpChange(VK2, 1_759_752_000_000L),
        pendingMp = pending(),
        replaced = StateRecord.Replaced(OTHER_VAULT, 1_759_752_000_000L, Shortcut.PIN, 0L, 4_242L),
        maxGeneration = StateRecord.MaxGeneration(9_999_999_999L, 1_759_752_000_000L),
    )

    private fun pinRecords(): List<PinRecord> = listOf(
        pinExampleRecord,
        twoWrapRecord(),
        PinRecord(VAULT, null, PinRecord.Guard(false, 0, 0), null, PinRecord.Unseen(0, null, false)), // never set
    ) + PinRecord.OffReason.entries.map(::offRecord)

    private fun fingerprintRecords(): List<FingerprintRecord> = listOf(
        fingerprintExampleRecord,
        FingerprintRecord(VAULT, ShortcutSlot(FP_SLOT, 0L, listOf(KeyWrap(VK2, NONCE2, CT2), KeyWrap(VK1, NONCE, CT))), null),
        FingerprintRecord(VAULT, null, null),
    ) + FingerprintRecord.OffReason.entries.map { FingerprintRecord(VAULT, null, FingerprintRecord.Off(it, 77L)) }

    private fun stateRecords(): List<StateRecord> = listOf(
        stateExampleRecord,
        fullState(),
        fullState().copy(pendingMp = pending().let {
            StateRecord.PendingMp(it.requestedMs, Shortcut.PIN, 0L, 0L, VK3, it.salt, it.nonce, it.ct)
        }, replaced = StateRecord.Replaced(OTHER_VAULT, 0L, Shortcut.FINGERPRINT, 604_800_000L, 0L), mpChange = null),
        stateExampleRecord.copy(maxGeneration = StateRecord.MaxGeneration(0L, 0L)),
    )

    private fun wrap(vkId: String) = KeyWrap(vkId, NONCE, CT)

    private companion object {
        const val VAULT = "0f6b2f0e-5d3b-4c1a-9a7e-2b1c8d4e6f70" // §5.4
        const val PIN_SLOT = "7b0c1f8e-0b9a-4d27-9e3a-55c6f6d1a2b4" // §5.4
        const val FP_SLOT = "c2a4e6f8-1b3d-4a5c-8e7f-9a0b1c2d3e4f" // "c2a4…" of §5.5
        const val VK1 = "3a1d0c5e-8f2b-4e6a-b1c9-7d4e2f6a8b0c" // "3a1d…" of §5.4
        const val VK2 = "5e2f7a9b-3c4d-4e5f-a6b7-c8d9e0f1a2b3"
        const val VK3 = "9b8a7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d"
        const val OTHER_VAULT = "00000000-0000-4000-8000-000000000001"

        /** The nonce and the wrap of the PIN vector of lock.md §4.3. */
        val NONCE: ByteArray = ByteArray(12) { (0x10 + it).toByte() }
        val CT: ByteArray = Bytes.fromHex(
            "e406013346c5ad2cda2a4ca5c7eef739ee9cc17382092ecae223efc3a79c819cb6aef30c458b67b05303f36055881d02",
        )
        val NONCE2: ByteArray = ByteArray(12) { (0x60 + it).toByte() }
        val CT2: ByteArray = ByteArray(48) { (0x80 + it).toByte() }
        val SALT: ByteArray = ByteArray(16) { it.toByte() }
        val NONCE_B64: String = B64.encode(NONCE)
        val CT_B64: String = B64.encode(CT)

        /** Characters that JSON and the records give a meaning to, for the mutations. */
        const val MUTATIONS = "{}[]\":,0123456789-.eEtrufalsn \\/ABCxyz_"
    }
}
