package io.github.dilshodbekmx.passwordvault.core.vaultfile

import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.core.util.Bytes
import io.github.dilshodbekmx.passwordvault.core.vaultfile.Vectors.BODY_A
import io.github.dilshodbekmx.passwordvault.core.vaultfile.Vectors.BODY_B
import io.github.dilshodbekmx.passwordvault.core.vaultfile.Vectors.FILE_A
import io.github.dilshodbekmx.passwordvault.core.vaultfile.Vectors.FILE_B
import io.github.dilshodbekmx.passwordvault.core.vaultfile.Vectors.KEK
import io.github.dilshodbekmx.passwordvault.core.vaultfile.Vectors.SLOT
import io.github.dilshodbekmx.passwordvault.core.vaultfile.Vectors.VAULT_ID
import io.github.dilshodbekmx.passwordvault.core.vaultfile.Vectors.VK
import io.github.dilshodbekmx.passwordvault.core.vaultfile.Vectors.VK_ID
import io.github.dilshodbekmx.passwordvault.core.vaultfile.Vectors.codec
import io.github.dilshodbekmx.passwordvault.core.vaultfile.Vectors.retrailer
import io.github.dilshodbekmx.passwordvault.core.vaultfile.Vectors.u32
import io.github.dilshodbekmx.passwordvault.core.vaultfile.Vectors.utf8
import io.github.dilshodbekmx.passwordvault.testing.FakeRandom
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 100,000 random and mutated inputs never throw past `check` and `open` (architecture.md §8.1
 * `vaultfile`), and every refusal is a result: a [FileCheck], a [BodyResult], a [ParseError][io.github.dilshodbekmx.passwordvault.core.util.ParseError]
 * whose reason is one of the codec's fixed list.
 *
 * Mutated whole files mostly stop at R1 to R8, because a changed byte breaks the trailer or the
 * tag. So most inputs are mutated *bodies* sealed properly under VK, which reach the JSON reader
 * and R9 and R10. Whatever opens must also be written again, and read again to the same vault.
 * The counts at the end show that every kind of outcome was reached.
 */
class VaultFuzzTest {
    private val codec = codec(FakeRandom(77))
    private val wrongVk = ByteArray(32) { 0x55 }
    private val checks = sortedMapOf<String, Int>()
    private val opens = sortedMapOf<String, Int>()

    private fun count(map: MutableMap<String, Int>, key: String) {
        map[key] = (map[key] ?: 0) + 1
    }

    @Test
    fun aHundredThousandRandomAndMutatedInputsNeverThrowPastCheckAndOpen() {
        val rnd = Random(20_261_007L)
        var inputs = 0

        // 1. Whole files, mutated: framing, header, trailer, ciphertext.
        val files = listOf(FILE_A, FILE_B) +
            List(6) { codec.seal(RandomVaults(it.toLong()).vault(5), VK, VAULT_ID, VK_ID, 1L + it, SLOT) }
        repeat(35_000) {
            val input = mutateFile(files[rnd.nextInt(files.size)], rnd)
            run(inputs++, input) { exercise(input) }
        }

        // 2. Bodies, mutated, then sealed under VK with a canonical header: open reaches R9 and R10.
        val bodies = listOf(BODY_A, BODY_B, """{"schema":2,"entries":[]}""").map(::utf8) +
            List(10) { BodyCodec.write(RandomVaults(100L + it, lone = true).vault(3)) }
        repeat(55_000) {
            val plaintext = mutateBody(bodies[rnd.nextInt(bodies.size)], rnd)
            val input = codec.sealPadded(plaintext, VK, VAULT_ID, VK_ID, 1, SLOT)
            run(inputs++, input) {
                val result = exercise(input)
                assertTrue("$result", result is BodyResult.Opened || result is BodyResult.Damaged || result is BodyResult.Newer)
                if (result is BodyResult.Opened) writesAgain(result.vault)
            }
        }

        // 3. Random bytes, some behind the magic and a plausible header length.
        repeat(10_000) {
            val length = if (rnd.nextInt(10) == 0) rnd.nextInt(10_000) else rnd.nextInt(700)
            val input = ByteArray(length).also(rnd::nextBytes)
            if (length >= 12 && rnd.nextBoolean()) {
                utf8("PVAULT01").copyInto(input)
                u32(rnd.nextInt(600).toLong()).copyInto(input, 8)
            }
            run(inputs++, input) { exercise(input) }
        }

        assertEquals(100_000, inputs)
        // The counts go to the test report (system-out), so a run shows what it reached.
        println("vaultfile fuzz: $inputs inputs; check: $checks; open with VK: $opens")
        // Every way out was reached.
        for (key in listOf("Ok", "NotOurs", "Newer", "Damaged(1)", "Damaged(2)", "Damaged(3)", "Damaged(5)", "Damaged(6)", "Damaged(7)")) {
            assertTrue("never saw $key: $checks", (checks[key] ?: 0) > 0)
        }
        for (key in listOf("Opened", "TagFailed", "Newer")) assertTrue("never saw $key: $opens", (opens[key] ?: 0) > 0)
        val reasons = opens.keys.filter { it.startsWith("Damaged(") }
        assertTrue("too few kinds of damaged bodies: $opens", reasons.size >= 12)
    }

    private fun run(index: Int, input: ByteArray, block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            throw AssertionError("input $index (" + input.size + " bytes, starting " + Bytes.hex(input.copyOf(minOf(input.size, 48))) + ")", e)
        }
    }

    /** `check`, then `unwrap` and `open` with the right and a wrong key; none may throw. */
    private fun exercise(input: ByteArray): BodyResult? {
        val check = codec.check(input)
        count(
            checks,
            when (check) {
                is FileCheck.Ok -> "Ok"
                is FileCheck.NotOurs -> "NotOurs"
                is FileCheck.Newer -> "Newer"
                is FileCheck.Damaged -> "Damaged(${check.rule})"
            },
        )
        if (check !is FileCheck.Ok) return null
        codec.unwrap(check.file.header.slot, KEK)
        assertEquals(BodyResult.TagFailed, codec.open(check.file, wrongVk))
        val result = codec.open(check.file, VK)
        count(
            opens,
            when (result) {
                is BodyResult.Opened -> "Opened"
                is BodyResult.TagFailed -> "TagFailed"
                is BodyResult.Newer -> "Newer"
                is BodyResult.Damaged -> "Damaged(${result.error.reason})"
            },
        )
        if (result is BodyResult.Damaged) assertTrue(result.error.reason, result.error.reason in BodyCodec.REASON_CODES)
        if (result is BodyResult.Newer) assertTrue(result.schema > 1)
        return result
    }

    /** What opened is written again; read once more it is the same vault, and written again the same bytes. */
    private fun writesAgain(vault: Vault) {
        val first = BodyCodec.write(vault)
        val again = BodyCodec.read(BodyCodec.pad(first))
        if (!hasUnpairedSurrogate(vault)) assertEquals(BodyResult.Opened(vault), again)
        if (again is BodyResult.Opened) assertTrue(first.contentEquals(BodyCodec.write(again.vault)))
    }

    private fun hasUnpairedSurrogate(v: Vault): Boolean {
        val texts = ArrayList<String>()
        for (e in v.entries) {
            texts += listOf(e.id, e.title, e.username, e.password, e.notes) + e.urls + listOfNotNull(e.pendingOldPassword)
            for (f in e.fields) texts += listOf(f.name, f.value)
            for (h in e.passwordHistory) texts += h.password
            for ((k, t) in e.extras) texts += listOf(k, t)
        }
        v.draft?.let { d ->
            texts += listOfNotNull(d.entryId) + listOf(d.entry.title, d.entry.username, d.entry.password, d.entry.notes) + d.entry.urls
            for (f in d.entry.fields) texts += listOf(f.name, f.value)
        }
        for (f in v.plainExports + v.backups) texts += listOf(f.uri, f.name)
        texts += v.masterPasswordMatches
        for ((k, t) in v.extras) texts += listOf(k, t)
        return texts.any { s ->
            var i = 0
            var lone = false
            while (i < s.length) {
                val c = s[i]
                if (Character.isHighSurrogate(c) && i + 1 < s.length && Character.isLowSurrogate(s[i + 1])) {
                    i += 2
                    continue
                }
                if (Character.isSurrogate(c)) lone = true
                i++
            }
            lone
        }
    }

    // ------------------------------------------------------------------------------- mutations

    private fun mutateFile(seed: ByteArray, rnd: Random): ByteArray {
        var b = seed.copyOf()
        repeat(1 + rnd.nextInt(3)) {
            b = when (rnd.nextInt(11)) {
                0 -> flipBits(b, rnd)
                1 -> setByte(b, rnd)
                10 -> setVersion(b, rnd)
                2 -> b.copyOf(rnd.nextInt(b.size + 1))
                3 -> b + ByteArray(1 + rnd.nextInt(64)).also(rnd::nextBytes)
                4 -> deleteRange(b, rnd)
                5 -> insert(b, rnd.nextInt(b.size + 1), ByteArray(1 + rnd.nextInt(16)).also(rnd::nextBytes))
                6 -> setHeaderLength(b, rnd)
                7 -> editHeader(b, rnd)
                8 -> setFraming(b, rnd)
                else -> duplicateRange(b, rnd)
            }
        }
        return if (b.size >= 32 && rnd.nextBoolean()) retrailer(b) else b
    }

    private val tokens = listOf(
        "{", "}", "[", "]", "\"", ":", ",", "\\", "\\u", "\\ud800", "\\uDFFF", "\\u0000", "\\n", "null", "true", "false",
        "1e3", "-0", "1.0", "01", "-", "+1", "9223372036854775808", "12345678901234567890", "\"schema\":2", "\"schema\":0",
        "\"schema\":1", "\"entries\":[]", "\"id\":\"x\"", "\"title\":1", "\"createdAt\":1", ",\"x\":{}", "[[[[[[[[[[",
        "]]]]]]]]]]", "[[[[[[[[[[[[[[[[[", "{\"a\":{\"a\":{\"a\":{\"a\":", " ", "\n", "\t", "é", "🔑", "Ж", "\u0001",
    ).map(::utf8) + listOf(
        byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()),
        byteArrayOf(0xC3.toByte(), 0x28),
        byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x80.toByte()),
        byteArrayOf(0xFF.toByte()),
        byteArrayOf(0x00),
        byteArrayOf(0xC0.toByte(), 0xAF.toByte()),
        byteArrayOf(0xF4.toByte(), 0x90.toByte(), 0x80.toByte(), 0x80.toByte()),
    )

    private fun mutateBody(seed: ByteArray, rnd: Random): ByteArray {
        var b = seed.copyOf()
        repeat(1 + rnd.nextInt(4)) {
            b = when (rnd.nextInt(8)) {
                0 -> flipBits(b, rnd)
                1, 2 -> insert(b, rnd.nextInt(b.size + 1), tokens[rnd.nextInt(tokens.size)])
                3 -> deleteRange(b, rnd)
                4 -> duplicateRange(b, rnd)
                5 -> b.copyOf(rnd.nextInt(b.size + 1))
                6 -> if (b.isEmpty()) b else b.copyOf().also { it[rnd.nextInt(it.size)] = (0x20 + rnd.nextInt(0x5F)).toByte() }
                else -> swapRanges(b, rnd)
            }
        }
        val size = maxOf(4_096, (b.size + 4_095) / 4_096 * 4_096)
        val padding: Byte = when (rnd.nextInt(20)) {
            0 -> 0x00
            1 -> 0x0A
            else -> 0x20
        }
        return b.copyOf(size).also { it.fill(padding, b.size, size) }
    }

    private fun flipBits(b: ByteArray, rnd: Random): ByteArray {
        if (b.isEmpty()) return b
        val out = b.copyOf()
        repeat(1 + rnd.nextInt(4)) {
            val i = rnd.nextInt(out.size)
            out[i] = (out[i].toInt() xor (1 shl rnd.nextInt(8))).toByte()
        }
        return out
    }

    private fun setByte(b: ByteArray, rnd: Random): ByteArray {
        if (b.isEmpty()) return b
        return b.copyOf().also { it[rnd.nextInt(it.size)] = rnd.nextInt(256).toByte() }
    }

    private fun deleteRange(b: ByteArray, rnd: Random): ByteArray {
        if (b.isEmpty()) return b
        val from = rnd.nextInt(b.size)
        val to = minOf(b.size, from + 1 + rnd.nextInt(32))
        return b.copyOfRange(0, from) + b.copyOfRange(to, b.size)
    }

    private fun insert(b: ByteArray, at: Int, what: ByteArray): ByteArray = b.copyOfRange(0, at) + what + b.copyOfRange(at, b.size)

    private fun duplicateRange(b: ByteArray, rnd: Random): ByteArray {
        if (b.isEmpty()) return b
        val from = rnd.nextInt(b.size)
        val to = minOf(b.size, from + 1 + rnd.nextInt(64))
        return insert(b, rnd.nextInt(b.size + 1), b.copyOfRange(from, to))
    }

    private fun swapRanges(b: ByteArray, rnd: Random): ByteArray {
        if (b.size < 4) return b
        val cut1 = rnd.nextInt(b.size)
        val cut2 = rnd.nextInt(b.size)
        val (lo, hi) = if (cut1 <= cut2) cut1 to cut2 else cut2 to cut1
        return b.copyOfRange(hi, b.size) + b.copyOfRange(lo, hi) + b.copyOfRange(0, lo)
    }

    private fun setHeaderLength(b: ByteArray, rnd: Random): ByteArray {
        if (b.size < 12) return b
        val value = when (rnd.nextInt(4)) {
            0 -> rnd.nextInt(5_000).toLong()
            1 -> 0xFFFF_FFFFL - rnd.nextInt(3)
            2 -> listOf(0L, 1L, 2L, 4_096L, 4_097L)[rnd.nextInt(5)]
            else -> {
                var length = 0L
                for (i in 8..11) length = (length shl 8) or (b[i].toLong() and 0xFF)
                length + rnd.nextInt(5) - 2
            }
        }
        return b.copyOf().also { u32(value).copyInto(it, 8) }
    }

    /** One byte of the header text becomes another ASCII character: R5 to R7 with everything else intact. */
    private fun editHeader(b: ByteArray, rnd: Random): ByteArray {
        if (b.size < 600) return b
        val out = b.copyOf()
        val i = 12 + rnd.nextInt(480)
        out[i] = HEADER_CHARS[rnd.nextInt(HEADER_CHARS.length)].code.toByte()
        return out
    }

    /** The version number of a version 1 header (file offset 48) becomes another character: R5 and R6. */
    private fun setVersion(b: ByteArray, rnd: Random): ByteArray {
        if (b.size < 600) return b
        return b.copyOf().also { it[48] = HEADER_CHARS[rnd.nextInt(HEADER_CHARS.length)].code.toByte() }
    }

    private fun setFraming(b: ByteArray, rnd: Random): ByteArray {
        if (b.size < 8) return b
        return b.copyOf().also {
            it[6] = ('0' + rnd.nextInt(10)).code.toByte()
            it[7] = if (rnd.nextInt(4) == 0) rnd.nextInt(256).toByte() else ('0' + rnd.nextInt(10)).code.toByte()
        }
    }

    @Test
    fun theFuzzIsTheSameEveryRun() {
        // The inputs come from fixed seeds, so a failure can be run again exactly.
        for (seed in 1L..20L) {
            val a = mutateBody(utf8(BODY_B), Random(seed))
            assertTrue(a.contentEquals(mutateBody(utf8(BODY_B), Random(seed))))
            assertTrue(a.isNotEmpty() && a.size % 4_096 == 0)
            assertTrue(mutateFile(FILE_A, Random(seed)).contentEquals(mutateFile(FILE_A, Random(seed))))
        }
    }

    private companion object {
        const val HEADER_CHARS = "0123456789abcdefABCDEF\"{}[],: -=+/x"
    }
}
