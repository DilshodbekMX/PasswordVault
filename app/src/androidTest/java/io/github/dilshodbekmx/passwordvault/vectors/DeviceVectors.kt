package io.github.dilshodbekmx.passwordvault.vectors

import io.github.dilshodbekmx.passwordvault.core.model.CustomField
import io.github.dilshodbekmx.passwordvault.core.model.Draft
import io.github.dilshodbekmx.passwordvault.core.model.DraftEntry
import io.github.dilshodbekmx.passwordvault.core.model.Entry
import io.github.dilshodbekmx.passwordvault.core.model.FileRef
import io.github.dilshodbekmx.passwordvault.core.model.HistoryItem
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.core.util.Bytes
import io.github.dilshodbekmx.passwordvault.core.vaultfile.BuildInfo
import io.github.dilshodbekmx.passwordvault.core.vaultfile.PasswordSlot
import java.security.MessageDigest

/**
 * The frozen vectors of architecture.md §4.13 (vault files A and B) and lock.md §4.3 (the PIN wrap
 * and the master-password keys), as the device tests need them. The same values as the JVM tests'
 * `vaultfile/Vectors.kt` and `crypto/DerivedKeysTest.kt` in :core, whose test sources the app's
 * instrumented tests cannot see; the committed files `core/src/test/resources/vectors/` are pinned
 * here by their size and SHA-256.
 */
internal object DeviceVectors {
    fun sequence(first: Int, size: Int): ByteArray = ByteArray(size) { (first + it).toByte() }

    fun sha256Hex(bytes: ByteArray): String = Bytes.hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    fun utf8(text: String): ByteArray = text.toByteArray(Charsets.UTF_8)

    fun u32(value: Int): ByteArray =
        byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())

    // --- architecture.md §4.13 -----------------------------------------------------------------------

    /** The project's pinned Argon2id value for "correct horse battery staple" with salt 00 01 … 0f. */
    const val KEK_HEX = "853b272a44db1421c02962669a55eb0994f3cab385ed1c4c79253eee19bab49e"
    val KEK: ByteArray get() = Bytes.fromHex(KEK_HEX)
    val VK: ByteArray get() = sequence(0xa0, 32)
    val SALT: ByteArray get() = sequence(0x00, 16)
    val SLOT_NONCE: ByteArray get() = sequence(0x20, 12)
    val NONCE_A: ByteArray get() = sequence(0x30, 12)
    val NONCE_B: ByteArray get() = sequence(0x40, 12)
    const val WRAPPED_KEY_HEX =
        "4da4366623849589554fe3243a37b064a3117364b4ba9993b6a7c320723a8192ebc1ed80fb8f15516b561c63de23172b"
    val SLOT: PasswordSlot get() = PasswordSlot(SALT, SLOT_NONCE, Bytes.fromHex(WRAPPED_KEY_HEX))

    const val VAULT_ID = "00000000-0000-4000-8000-000000000001"
    const val VK_ID = "00000000-0000-4000-8000-000000000002"
    const val CREATED_A = 1_791_244_800_000L
    const val CREATED_B = 1_791_331_200_000L
    val BUILD = BuildInfo("PasswordVault 1.0", 1)

    /** `v1-container.pvault` and `v1-body.pvault`: 4,667 bytes each. */
    const val FILE_SIZE = 4_667
    const val FILE_A_SHA256 = "3210ed2c15bddde2e2d3793aeff65deda2d745d1ac10ed6c6443be1711ee1d2d"
    const val FILE_B_SHA256 = "1bb8d230c949b197859bbd9fec613bd65f149595b3f9db98b40dd6dc2e5312c7"
    const val AAD_A_SHA256 = "adfd3efa701b6e18938f770551cf86b990d3c651fe7016769fccf2f90fce15f0"
    const val TAG_A = "b725d6eb1c28bcaf9cab305f5a362876"
    const val TRAILER_A = "bb6953c8aec55e24dfb00e016dbd49268fbcab8ce5df20854416862ba0ce4e2a"
    const val TAG_B = "5e76cebd4bd31f0794d5da6ef151ef7b"
    const val TRAILER_B = "a12f31da424719de8cb33cbbad1accaae5ec6e4553dd2b2db1bc7748643afd3d"
    const val BODY_B_SHA256 = "610a407ad04359ae1fc8787f29f4ed943d46358eae7bdddc6dd7ad2490186cb7"

    /** Vector A's plaintext before its 4,071 spaces. */
    const val BODY_A = """{"schema":1,"entries":[]}"""

    /** Vector A's header, 499 bytes (§4.13.1). */
    const val HEADER_A =
        """{"format":"passwordvault","version":1,"cipher":"AES-256-GCM","vault_id":"00000000-0000-4000-8000-000000000001","vk_id":"00000000-0000-4000-8000-000000000002","generation":1,"created":1791244800000,"generator":"PasswordVault 1.0","writer_version":1,"body_len":4112,"slots":[{"type":"password","kdf":"argon2id","argon2_version":19,"m_kib":65536,"t":3,"p":4,"salt":"AAECAwQFBgcICQoLDA0ODw==","nonce":"ICEiIyQlJicoKSor","wrapped_key":"TaQ2ZiOElYlVT+MkOjewZKMRc2S0upmTtqfDIHI6gZLrwe2A+48VUWtWHGPeIxcr"}]}"""

    /** [json] padded with spaces to a multiple of 4,096 bytes, as the appendix script pads it. */
    fun padded(json: ByteArray): ByteArray {
        val size = (json.size + 4095) / 4096 * 4096
        return json.copyOf(size).also { it.fill(0x20, json.size, size) }
    }

    /** Vector B's body as a [Vault] value: what the writer must turn into its 1,448 bytes. */
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

    // --- lock.md §4.3 --------------------------------------------------------------------------------

    /** "oʻgʻlim-Toʻra" in every spelling of lock.md §4.3, and the Greek signs. */
    val UZBEK_SPELLINGS = listOf(
        "oʻgʻlim-Toʻra", // U+02BB, the Uzbek letter
        "o‘g‘lim-To‘ra", // U+2018
        "o'g'lim-To'ra", // U+0027
        "o’gʼlim-To`ra", // U+2019, U+02BC, U+0060
        "o´g＇lim-To′ra", // U+00B4, U+FF07, U+2032
        "oʹg`lim-To´ra", // the Greek signs, which NFC turns into U+02B9, U+0060, U+00B4
    )
    const val UZBEK_BYTES = "6f2767276c696d2d546f277261"
    const val UZBEK_KEY = "35914871c9a019bf3fa9ae69825be6a300a86905bcf69ca6bb84de0a3064b26b"

    /** "Köz-Ўzbek" typed decomposed: o + U+0308, У + U+0306. */
    const val KOZ_DECOMPOSED = "Köz-Ўzbek"
    const val KOZ_BYTES = "4bc3b67a2dd08e7a62656b"
    const val KOZ_KEY = "9f05463cb3a665fb4e22120cdad867630f76f12dc5ffdf4efaaa589f5baad5fa"
}
