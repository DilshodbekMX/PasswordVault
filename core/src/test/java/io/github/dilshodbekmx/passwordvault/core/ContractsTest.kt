package io.github.dilshodbekmx.passwordvault.core

import io.github.dilshodbekmx.passwordvault.core.lock.flow.MasterProof
import io.github.dilshodbekmx.passwordvault.core.lock.record.KeyWrap
import io.github.dilshodbekmx.passwordvault.core.lock.record.PinRecord
import io.github.dilshodbekmx.passwordvault.core.lock.record.Shortcut
import io.github.dilshodbekmx.passwordvault.core.lock.record.ShortcutSlot
import io.github.dilshodbekmx.passwordvault.core.lock.record.StateRecord
import io.github.dilshodbekmx.passwordvault.core.model.CustomField
import io.github.dilshodbekmx.passwordvault.core.model.Draft
import io.github.dilshodbekmx.passwordvault.core.model.DraftEntry
import io.github.dilshodbekmx.passwordvault.core.model.Entry
import io.github.dilshodbekmx.passwordvault.core.model.HistoryItem
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.core.vaultfile.PasswordSlot
import io.github.dilshodbekmx.passwordvault.testing.arch.PatternRule
import io.github.dilshodbekmx.passwordvault.testing.arch.SourceFile
import io.github.dilshodbekmx.passwordvault.testing.arch.SourceTree
import io.github.dilshodbekmx.passwordvault.testing.arch.describe
import io.github.dilshodbekmx.passwordvault.testing.arch.scan
import java.io.File
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The contracts of build plan package 1.1.
 *
 * Its acceptance line: no file of the package names a type of another package of wave 1, which is
 * built beside it (build plan §3: "None of them names a type of another"). Named here: the packages
 * and types of 1.2 (`core.search`, `core.text`), 1.3 (`core.lock.pin`, `core.lock.guard`,
 * `MasterPasswordBytes`, `SessionPolicy`) and 1.4 (`core.formats`, `StrictJson`, `TextHygiene`).
 * Some of these types live in packages where 1.1 has files too, where no import would show a use,
 * so they are matched by name. Comments and string literals are ignored, as in every source scan.
 * A later contract change made through the documents (build plan §1 rule 3) may name a type of
 * wave 1; it then takes that name out of the rule.
 *
 * The rest tests the few bodies the package has: [MasterProof], the types that compare byte arrays
 * by content, and the `toString` of the types that hold account text.
 */
class ContractsTest {
    private val root = SourceTree.root()

    private val otherWave1 = listOf(
        PatternRule(
            "1.1",
            "a package of 1.2, 1.3 or 1.4",
            Regex("""\bio\.github\.dilshodbekmx\.passwordvault\.core\.(search|text|formats|lock\.pin|lock\.guard)\b"""),
        ),
        PatternRule(
            "1.1",
            "a type of 1.3 or 1.4 in a package 1.1 shares",
            Regex("""\b(StrictJson|TextHygiene|MasterPasswordBytes|SessionPolicy|Credit|PracticeSchedule|PinBlocklist)\b"""),
        ),
    )

    @Test
    fun everyFileOfThePackageIsThere() {
        // Guards the list below: a file renamed or moved would otherwise drop out of the scan.
        val missing = PACKAGE_FILES.filterNot { File(root, it).isFile }
        assertTrue("missing: $missing", missing.isEmpty())
    }

    @Test
    fun noFileOfThePackageNamesATypeOfAnotherWave1Package() {
        val files = PACKAGE_FILES.map { SourceFile(it, File(root, it).readText()) }
        val findings = scan(files, otherWave1)
        assertTrue(describe(findings), findings.isEmpty())
    }

    @Test
    fun theScanFindsSuchANameInCodeAndNowhereElse() {
        fun findings(code: String) = scan(listOf(SourceFile("Sample.kt", code)), otherWave1)
        assertEquals(1, findings("package a\nimport io.github.dilshodbekmx.passwordvault.core.search.SearchIndex\n").size)
        assertEquals(1, findings("package a\nimport io.github.dilshodbekmx.passwordvault.core.lock.pin.*\n").size)
        assertEquals(1, findings("package a\nfun f(x: io.github.dilshodbekmx.passwordvault.core.formats.Csv) = x\n").size)
        assertEquals(1, findings("package a\nfun f(s: String) = StrictJson.parse(s)\n").size)
        assertEquals(1, findings("package a\nval c: Credit? = null\n").size)
        assertEquals(0, findings("package a\n// StrictJson and core.search in a comment\n/** [TextHygiene] */\nval s = \"MasterPasswordBytes\"\n").size)
        assertEquals(0, findings("package a\nval creditedMs = 0L\nval searchKey = 1\n").size)
    }

    @Test
    fun masterProofLendsCopiesAndDropZeroes() {
        val typed = byteArrayOf(1, 2, 3, 4)
        val proof = MasterProof(typed, "00000000-0000-4000-8000-000000000001")
        typed.fill(9) // the caller wipes its own array; the proof kept a copy
        var lent: ByteArray? = null
        val sum = proof.useBytes { bytes ->
            lent = bytes
            assertArrayEquals(byteArrayOf(1, 2, 3, 4), bytes)
            bytes.fill(7) // a step that scribbles on its copy changes nothing for the next one
            bytes.size
        }
        assertEquals(4, sum)
        assertArrayEquals("the lent copy is wiped afterwards", ByteArray(4), lent)
        proof.useBytes { assertArrayEquals(byteArrayOf(1, 2, 3, 4), it) }
        assertEquals("00000000-0000-4000-8000-000000000001", proof.vaultId)
        assertFalse(proof.toString().contains("1, 2"))

        proof.drop()
        proof.drop()
        try {
            proof.useBytes { it }
            fail("a dropped proof lent its bytes")
        } catch (_: IllegalStateException) {
        }
    }

    @Test
    fun aProofForFakesIsMadeLikeTheLocksOwn() {
        // architecture.md §3.4: the fakes, previews and tests outside :core make one this way (R-15).
        val typed = byteArrayOf(1, 2, 3)
        val proof = MasterProof.forFakes(typed, "00000000-0000-4000-8000-000000000002")
        typed.fill(0)
        proof.useBytes { assertArrayEquals(byteArrayOf(1, 2, 3), it) }
        assertEquals("00000000-0000-4000-8000-000000000002", proof.vaultId)
        proof.drop()
        try {
            proof.useBytes { it }
            fail("a dropped proof lent its bytes")
        } catch (_: IllegalStateException) {
        }
    }

    @Test
    fun aDropDuringAUseLeavesTheBytesOfThatUseAlone() {
        val proof = MasterProof(ByteArray(32) { 5 }, "00000000-0000-4000-8000-000000000001")
        val inUse = CountDownLatch(1)
        val dropped = CountDownLatch(1)
        var seen: ByteArray? = null
        val user = thread {
            proof.useBytes { bytes ->
                inUse.countDown()
                dropped.await()
                seen = bytes.copyOf()
            }
        }
        inUse.await()
        thread { proof.drop() }.join()
        dropped.countDown()
        user.join()
        assertArrayEquals(ByteArray(32) { 5 }, seen)
    }

    @Test
    fun byteHoldingValuesAreEqualByContent() {
        val slot = PasswordSlot(ByteArray(16) { it.toByte() }, ByteArray(12) { 0x20 }, ByteArray(48) { 0x4d })
        val same = PasswordSlot(ByteArray(16) { it.toByte() }, ByteArray(12) { 0x20 }, ByteArray(48) { 0x4d })
        assertEquals(slot, same)
        assertEquals(slot.hashCode(), same.hashCode())
        assertNotEquals(slot, PasswordSlot(ByteArray(16) { it.toByte() }, ByteArray(12) { 0x21 }, ByteArray(48) { 0x4d }))

        fun record(ct: Byte) = PinRecord(
            vaultId = "0f6b2f0e-5d3b-4c1a-9a7e-2b1c8d4e6f70",
            slot = ShortcutSlot("7b0c1f8e-0b9a-4d27-9e3a-55c6f6d1a2b4", 1_759_752_000_000L, listOf(KeyWrap("3a1d0000-0000-4000-8000-000000000000", ByteArray(12), ByteArray(48) { ct }))),
            guard = PinRecord.Guard(charged = false, row = 0, total = 0),
            off = null,
            unseen = PinRecord.Unseen(count = 0, lastAtMs = null, interrupted = false),
        )
        assertEquals("a record read back equals the one written", record(1), record(1))
        assertEquals(record(1).hashCode(), record(1).hashCode())
        assertNotEquals(record(1), record(2))

        fun pending(salt: Byte) = StateRecord.PendingMp(1L, Shortcut.PIN, 0L, 5L, "3a1d0000-0000-4000-8000-000000000000", ByteArray(16) { salt }, ByteArray(12), ByteArray(48))
        assertEquals(pending(1), pending(1))
        assertEquals(pending(1).hashCode(), pending(1).hashCode())
        assertNotEquals(pending(1), pending(2))
    }

    @Test
    fun theTypesThatHoldAccountTextNeverPrintIt() {
        val secret = "s3cret-Oʻzbekiston-пароль"
        val entry = Entry(
            id = "00000000-0000-4000-8000-000000000011",
            title = secret,
            username = secret,
            password = secret,
            notes = secret,
            urls = listOf(secret),
            fields = listOf(CustomField(secret, secret, secret = true)),
            createdAt = 1L,
            updatedAt = 2L,
            pendingOldPassword = secret,
            passwordHistory = listOf(HistoryItem(secret, 3L)),
            extras = mapOf("futureEntryKey" to "\"$secret\""),
        )
        val vault = Vault(
            entries = listOf(entry),
            draft = Draft(entry.id, 4L, DraftEntry(secret, secret, secret, secret, listOf(secret), listOf(CustomField(secret, secret, false)))),
            extras = mapOf("futureTopKey" to "\"$secret\""),
        )
        for (text in listOf(entry.toString(), vault.toString(), entry.fields[0].toString(), entry.passwordHistory[0].toString())) {
            assertFalse(text, text.contains("s3cret"))
            assertFalse(text, text.contains("futureEntryKey"))
        }
        assertTrue(entry.toString().contains(entry.id))
        // Equality still sees every field.
        assertNotEquals(entry, entry.copy(password = "other"))
        assertEquals(entry, entry.copy())
    }

    private companion object {
        const val CORE = "core/src/main/java/io/github/dilshodbekmx/passwordvault/core"
        const val TEST = "testing/src/main/java/io/github/dilshodbekmx/passwordvault/testing"

        /** The main files of package 1.1 (build plan §3, "Owns"). */
        val PACKAGE_FILES: List<String> =
            listOf("Clock", "RandomSource", "Bytes", "B64", "Uuid", "ParseError").map { "$CORE/util/$it.kt" } +
                listOf("Entry", "CustomField", "HistoryItem", "Vault", "Draft", "FileRef", "Edited", "EditTexts").map { "$CORE/model/$it.kt" } +
                listOf("Argon2id", "Argon2Params", "Aead", "KdfUnavailable", "MasterKdf", "CryptoFactory").map { "$CORE/crypto/$it.kt" } +
                listOf("VaultCodec", "VaultHeader", "PasswordSlot", "VaultFile", "FileCheck", "BodyResult", "BuildInfo", "VaultCodecs")
                    .map { "$CORE/vaultfile/$it.kt" } +
                listOf("FilePrimitives", "VaultStorage", "StoreTypes", "VaultStores").map { "$CORE/storage/$it.kt" } +
                listOf("RecordFiles", "RecordStore", "PinRecord", "FingerprintRecord", "StateRecord", "RecordRead")
                    .map { "$CORE/lock/record/$it.kt" } +
                listOf("Ks", "KeystoreFailure").map { "$CORE/lock/policy/$it.kt" } +
                listOf("PinKey", "FingerprintKey", "DeviceKeys", "KeyCreate", "VaultPort", "VaultTypes", "MasterProof", "LockScreen", "Notice", "Outcomes")
                    .map { "$CORE/lock/flow/$it.kt" } +
                listOf("$CORE/generator/GeneratorOptions.kt", "$CORE/strength/SecurityReport.kt", "$TEST/FakeClock.kt", "$TEST/FakeRandom.kt")
    }
}
