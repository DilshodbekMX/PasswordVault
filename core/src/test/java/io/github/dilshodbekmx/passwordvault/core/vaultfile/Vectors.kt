package io.github.dilshodbekmx.passwordvault.core.vaultfile

import io.github.dilshodbekmx.passwordvault.core.crypto.Aead
import io.github.dilshodbekmx.passwordvault.core.model.CustomField
import io.github.dilshodbekmx.passwordvault.core.model.Draft
import io.github.dilshodbekmx.passwordvault.core.model.DraftEntry
import io.github.dilshodbekmx.passwordvault.core.model.Entry
import io.github.dilshodbekmx.passwordvault.core.model.FileRef
import io.github.dilshodbekmx.passwordvault.core.model.HistoryItem
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.core.util.Bytes
import io.github.dilshodbekmx.passwordvault.core.util.Clock
import io.github.dilshodbekmx.passwordvault.testing.FakeClock
import io.github.dilshodbekmx.passwordvault.testing.FakeRandom
import java.security.MessageDigest
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM through the JDK's own `javax.crypto`, for the tests of this package only: `JcaAesGcm`
 * of package 2.1 is built beside this one (build plan 2.2, "Uses"). [calls] records every call, so a
 * test can see which AAD and nonce the codec handed over.
 */
internal class TestAead : Aead {
    class Call(val kind: String, val key: ByteArray, val nonce: ByteArray, val aad: ByteArray?)

    val calls = mutableListOf<Call>()

    override fun seal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray?): ByteArray {
        calls += Call("seal", key.copyOf(), nonce.copyOf(), aad?.copyOf())
        return gcm(Cipher.ENCRYPT_MODE, key, nonce, aad).doFinal(plaintext)
    }

    override fun open(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, aad: ByteArray?): ByteArray? {
        calls += Call("open", key.copyOf(), nonce.copyOf(), aad?.copyOf())
        return try {
            gcm(Cipher.DECRYPT_MODE, key, nonce, aad).doFinal(ciphertext)
        } catch (_: AEADBadTagException) {
            null
        }
    }

    private fun gcm(mode: Int, key: ByteArray, nonce: ByteArray, aad: ByteArray?): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            if (aad != null) updateAAD(aad)
        }
}

/**
 * The inputs and files of architecture.md §4.13 (vectors A and B), and small tools for making files
 * that break one rule. Everything here is the test's own: it uses the JDK's AES-GCM and SHA-256
 * directly, the way the appendix script uses Python's, so that a test never checks the codec only
 * against itself.
 */
internal object Vectors {
    /** The project's pinned Argon2id value for "correct horse battery staple" with salt 00 01 … 0f. */
    val KEK: ByteArray = Bytes.fromHex("853b272a44db1421c02962669a55eb0994f3cab385ed1c4c79253eee19bab49e")
    val VK: ByteArray = FakeRandom.sequence(0xa0, 32)
    val SALT: ByteArray = FakeRandom.sequence(0x00, 16)
    val SLOT_NONCE: ByteArray = FakeRandom.sequence(0x20, 12)
    val NONCE_A: ByteArray = FakeRandom.sequence(0x30, 12)
    val NONCE_B: ByteArray = FakeRandom.sequence(0x40, 12)
    const val WRAPPED_KEY_HEX =
        "4da4366623849589554fe3243a37b064a3117364b4ba9993b6a7c320723a8192ebc1ed80fb8f15516b561c63de23172b"
    val SLOT = PasswordSlot(SALT, SLOT_NONCE, Bytes.fromHex(WRAPPED_KEY_HEX))

    const val VAULT_ID = "00000000-0000-4000-8000-000000000001"
    const val VK_ID = "00000000-0000-4000-8000-000000000002"
    const val CREATED_A = 1_791_244_800_000L
    const val CREATED_B = 1_791_331_200_000L
    val BUILD = BuildInfo("PasswordVault 1.0", 1)

    /** Vector A's plaintext before its 4,071 spaces. */
    const val BODY_A = """{"schema":1,"entries":[]}"""

    /** Vector A's header, 499 bytes (§4.13.1). */
    const val HEADER_A =
        """{"format":"passwordvault","version":1,"cipher":"AES-256-GCM","vault_id":"00000000-0000-4000-8000-000000000001","vk_id":"00000000-0000-4000-8000-000000000002","generation":1,"created":1791244800000,"generator":"PasswordVault 1.0","writer_version":1,"body_len":4112,"slots":[{"type":"password","kdf":"argon2id","argon2_version":19,"m_kib":65536,"t":3,"p":4,"salt":"AAECAwQFBgcICQoLDA0ODw==","nonce":"ICEiIyQlJicoKSor","wrapped_key":"TaQ2ZiOElYlVT+MkOjewZKMRc2S0upmTtqfDIHI6gZLrwe2A+48VUWtWHGPeIxcr"}]}"""

    /** Vector B's body (§4.13.2): one line, 1,448 bytes of UTF-8, SHA-256 610a407a…6cb7. */
    const val BODY_B = """{"schema":1,"entries":[{"id":"00000000-0000-4000-8000-000000000011","title":"Gmail – Oʻzbekiston Ўзбекистон","username":"aziz@example.com","password":"p\"ss\\w/rd-🔑","notes":"Birinchi qator\nИккинчи қатор\ttab\u0001","urls":["https://mail.google.com","https://accounts.google.com/a?b=c"],"fields":[{"name":"PIN code","value":"0042","secret":true},{"name":"Телефон","value":"+998 90 123-45-67","secret":false}],"favourite":true,"changePending":true,"checkIgnored":false,"createdAt":1791244800000,"updatedAt":1791331200000,"passwordChangedAt":1791331200000,"deletedAt":null,"pendingOldPassword":"eski-parol","passwordHistory":[{"password":"old2","replacedAt":1791331200000},{"password":"old1","replacedAt":1791288000000}],"futureEntryKey":"kept"}],"draft":{"entryId":null,"savedAt":1791331260000,"entry":{"title":"Yangi","username":"","password":"gen-🔑","notes":"","urls":[],"fields":[],"favourite":false}},"plainExports":[{"uri":"content://com.android.providers.downloads.documents/document/17","name":"passwords-2026-10-07.csv","savedAt":1791331300000}],"backups":[{"uri":"content://com.android.externalstorage.documents/document/primary%3ADownload%2FPasswordVault-backup-2026-10-07.pvault","name":"PasswordVault-backup-2026-10-07.pvault","savedAt":1791331400000}],"masterPasswordMatches":["00000000-0000-4000-8000-000000000011"],"securitySeenAt":1791331500000,"futureTopKey":{"n":1.50,"list":[true,null,"x"]}}"""

    /** Vector B's body as a [Vault] value: what the writer must turn into [BODY_B]. */
    val VAULT_B: Vault by lazy {
        val id = "00000000-0000-4000-8000-000000000011"
        Vault(
            entries = listOf(
                Entry(
                    id = id,
                    title = "Gmail – Oʻzbekiston Ўзбекистон",
                    username = "aziz@example.com",
                    password = "p\"ss\\w/rd-🔑",
                    notes = "Birinchi qator\nИккинчи қатор\ttab\u0001",
                    urls = listOf("https://mail.google.com", "https://accounts.google.com/a?b=c"),
                    fields = listOf(
                        CustomField("PIN code", "0042", true),
                        CustomField("Телефон", "+998 90 123-45-67", false),
                    ),
                    favourite = true,
                    changePending = true,
                    checkIgnored = false,
                    createdAt = 1_791_244_800_000L,
                    updatedAt = 1_791_331_200_000L,
                    passwordChangedAt = 1_791_331_200_000L,
                    deletedAt = null,
                    pendingOldPassword = "eski-parol",
                    passwordHistory = listOf(HistoryItem("old2", 1_791_331_200_000L), HistoryItem("old1", 1_791_288_000_000L)),
                    extras = linkedMapOf("futureEntryKey" to "\"kept\""),
                ),
            ),
            draft = Draft(
                entryId = null,
                savedAt = 1_791_331_260_000L,
                entry = DraftEntry(title = "Yangi", username = "", password = "gen-🔑", notes = "", urls = emptyList(), fields = emptyList(), favourite = false),
            ),
            plainExports = listOf(
                FileRef("content://com.android.providers.downloads.documents/document/17", "passwords-2026-10-07.csv", 1_791_331_300_000L),
            ),
            backups = listOf(
                FileRef(
                    "content://com.android.externalstorage.documents/document/primary%3ADownload%2FPasswordVault-backup-2026-10-07.pvault",
                    "PasswordVault-backup-2026-10-07.pvault",
                    1_791_331_400_000L,
                ),
            ),
            masterPasswordMatches = listOf(id),
            securitySeenAt = 1_791_331_500_000L,
            extras = linkedMapOf("futureTopKey" to """{"n":1.50,"list":[true,null,"x"]}"""),
        )
    }

    /** The two committed files (core/src/test/resources/vectors/). */
    val FILE_A: ByteArray by lazy { resource("v1-container.pvault") }
    val FILE_B: ByteArray by lazy { resource("v1-body.pvault") }

    fun resource(name: String): ByteArray {
        val stream = Vectors::class.java.getResourceAsStream("/vectors/$name") ?: error("no test resource vectors/$name")
        return stream.use { it.readBytes() }
    }

    /** A codec over the test's own AES-GCM, with fixed randomness and a fixed clock. */
    fun codec(
        random: FakeRandom = FakeRandom(),
        clock: Clock = FakeClock(),
        aead: Aead = TestAead(),
        build: BuildInfo = BUILD,
    ) = DefaultVaultCodec(aead, random, clock, build)

    fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    fun sha256Hex(bytes: ByteArray): String = Bytes.hex(sha256(bytes))

    fun utf8(text: String): ByteArray = text.toByteArray(Charsets.UTF_8)

    /** [json] padded with spaces to a multiple of 4,096 bytes, as the appendix script pads it. */
    fun padded(json: ByteArray): ByteArray {
        val size = (json.size + 4095) / 4096 * 4096
        return json.copyOf(size).also { it.fill(0x20, json.size, size) }
    }

    /**
     * A file put together the way the appendix script does it: magic ‖ `header_len` ‖ [header] ‖
     * [nonce] ‖ AES-256-GCM([vk], [nonce], [plaintext], AAD) ‖ SHA-256 trailer. [headerLen] is the
     * value of the length field (the true length unless a test says otherwise); with [goodTrailer]
     * false the trailer's last byte is flipped.
     */
    fun assemble(
        header: ByteArray,
        plaintext: ByteArray = padded(utf8(BODY_A)),
        magic: String = "PVAULT01",
        headerLen: Long = header.size.toLong(),
        nonce: ByteArray = NONCE_A,
        vk: ByteArray = VK,
        goodTrailer: Boolean = true,
    ): ByteArray {
        val aad = utf8(magic) + u32(headerLen) + header
        val body = TestAead().seal(vk, nonce, plaintext, aad)
        return withTrailer(aad + nonce + body, goodTrailer)
    }

    fun assemble(header: String, plaintext: ByteArray = padded(utf8(BODY_A)), goodTrailer: Boolean = true): ByteArray =
        assemble(utf8(header), plaintext, goodTrailer = goodTrailer)

    /** [withoutTrailer] followed by its SHA-256, or by a wrong one. */
    fun withTrailer(withoutTrailer: ByteArray, good: Boolean = true): ByteArray {
        val trailer = sha256(withoutTrailer)
        if (!good) trailer[31] = (trailer[31].toInt() xor 1).toByte()
        return withoutTrailer + trailer
    }

    /** [file] with its last 32 bytes made the SHA-256 of the rest again, after a change. */
    fun retrailer(file: ByteArray): ByteArray = withTrailer(file.copyOf(file.size - 32))

    fun u32(value: Long): ByteArray =
        byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())

    /**
     * Vector A's header with [body_len][bodyLen] and some text replaced: [edit] gets the header text
     * and returns the changed one. With the default plaintext of one block, `body_len` stays 4112.
     */
    fun headerA(edit: (String) -> String = { it }): String = edit(HEADER_A)

    /** The body of a file that passed `check`, decrypted with [vk] by the test's own AES-GCM. */
    fun decryptBody(file: VaultFile, vk: ByteArray = VK): ByteArray? = TestAead().open(vk, file.bodyNonce, file.bodyCt, file.aad)
}
