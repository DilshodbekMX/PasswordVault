package io.github.dilshodbekmx.passwordvault.core.storage

import io.github.dilshodbekmx.passwordvault.core.model.Entry
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.core.vaultfile.BuildInfo
import io.github.dilshodbekmx.passwordvault.core.vaultfile.FileCheck
import io.github.dilshodbekmx.passwordvault.core.vaultfile.PasswordSlot
import io.github.dilshodbekmx.passwordvault.testing.FakeClock
import io.github.dilshodbekmx.passwordvault.testing.FakeRandom
import io.github.dilshodbekmx.passwordvault.testing.storage.FakeCodec
import io.github.dilshodbekmx.passwordvault.testing.storage.FakeFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/*
 * What the storage tests share: one phone whose disk outlives its processes, the names of its vault
 * directories, and short ways to make keys, entries and generations.
 */

internal const val FILES = "/data/files"
internal const val VAULTS = "$FILES/vaults"
internal const val LIVE = "$VAULTS/live"

/** `vault-N<suffix>` in `live/`. */
internal fun live(number: Long, suffix: String = Names.PVAULT): String = "$LIVE/${Names.generation(number, suffix)}"

/** A UUID v4 in upper case: not an id here (lock.md §5.1 takes lower case only). */
internal const val UPPER_CASE_ID = "0000000A-0000-4000-8000-00000000000B"

/** The n-th test id, a valid lower-case UUID v4. */
internal fun id(n: Int): String = "00000000-0000-4000-8000-" + n.toString().padStart(12, '0')

internal fun entry(n: Int, title: String = "Account $n", password: String = "secret-$n"): Entry =
    Entry(id = id(n), title = title, password = password, createdAt = 1_000L + n, updatedAt = 1_000L + n)

/** A vault of [count] entries, numbered from [from]. */
internal fun vaultOf(count: Int, from: Int = 1): Vault = Vault(entries = (from until from + count).map { entry(it) })

/** The keys of one vault, as the lock and the repository would hold them: VK, its id, and the password slot. */
internal class Keys(val vaultId: String, val vkId: String, val vk: ByteArray, val password: String, val slot: PasswordSlot)

/**
 * One phone: [files] is its disk, kept from one process to the next; [process] starts a new app
 * process with a store of its own, which remembers nothing of the last one. [codec], [clock] and
 * [random] are deterministic, so a test that builds the same phone twice gets the same bytes.
 */
internal class Phone(
    val files: FakeFiles = FakeFiles().apply { mkdirs(FILES) },
    val clock: FakeClock = FakeClock(),
    val random: FakeRandom = FakeRandom(11L),
    build: BuildInfo = BuildInfo("PasswordVault 1.0", 1),
) {
    val codec = FakeCodec(random, clock, build)

    /** The running app's build: [process] gives it to the store and the codec. */
    var build: BuildInfo = build
        set(value) {
            field = value
            codec.build = value
        }

    fun process(): VaultStore = VaultStore(files, codec, clock, VAULTS, build)

    /** New keys for a vault: VK, `vk_id`, and a slot made with [password] (FakeCodec.kek stands for Argon2id). */
    fun keys(password: String, vaultId: String = random.uuid()): Keys {
        val vk = random.bytes(32)
        val salt = random.bytes(16)
        return Keys(vaultId, random.uuid(), vk, password, codec.wrap(vk, FakeCodec.kek(password, salt), salt))
    }

    /** F1: a new vault holding [vault], staged, proven with the second typing of [password], published. */
    fun create(store: VaultStore, vault: Vault = Vault(), password: String = "first master password"): Pair<Keys, OpenedVault> {
        val keys = keys(password)
        val staged = (store.stageVault(vault, keys.vaultId, keys.vk, keys.vkId, keys.slot) as StageResult.Staged).staged
        assertTrue("the proof", store.prove(staged, FakeCodec.kek(password, staged.slotSalt)))
        val saved = store.publish(staged, putCurrentAside = false)
        assertTrue("published: $saved", saved is CommitResult.Saved)
        return keys to (saved as CommitResult.Saved).opened
    }

    /** F8: the master password becomes [password] under a new key: staged in live/, proven, published. */
    fun changePassword(store: VaultStore, opened: OpenedVault, password: String): Pair<Keys, OpenedVault> {
        val keys = keys(password, opened.vaultId)
        val staged = (store.stageRekey(opened, opened.vault, keys.vk, keys.vkId, keys.slot) as StageResult.Staged).staged
        assertTrue("the proof", store.prove(staged, FakeCodec.kek(password, staged.slotSalt)))
        val saved = store.publish(staged, putCurrentAside = false)
        assertTrue("published: $saved", saved is CommitResult.Saved)
        return keys to (saved as CommitResult.Saved).opened
    }

    /** Saves [vault] as an ordinary generation and returns what is open afterwards. */
    fun save(store: VaultStore, opened: OpenedVault, vault: Vault = opened.vault.copy(securitySeenAt = (opened.vault.securitySeenAt ?: 0L) + 1)): OpenedVault {
        val result = store.commit(opened, vault)
        assertTrue("saved: $result", result is CommitResult.Saved)
        return (result as CommitResult.Saved).opened
    }

    /** §4.8 "Opening with the master password" as the repository does it: every slot in turn. */
    fun unlock(store: VaultStore, password: String, target: VaultRef = VaultRef.Current): PasswordResult {
        for (slot in store.slots(target)) {
            val result = store.openWithSlot(target, slot, FakeCodec.kek(password, slot.salt))
            if (result !is PasswordResult.SlotClosed) return result
        }
        return PasswordResult.SlotClosed
    }

    /** Opens the current vault with [password] and expects it to open. */
    fun unlockOpened(store: VaultStore, password: String, target: VaultRef = VaultRef.Current): OpenedVault {
        val result = unlock(store, password, target)
        assertTrue("opened: $result", result is PasswordResult.Opened)
        return (result as PasswordResult.Opened).opened
    }

    /** The live generation numbers, by their names. */
    fun liveNumbers(): List<Long> = files.names(LIVE).mapNotNull { Names.numberOf(it, Names.PVAULT) }.sorted()

    /** The names in live/ with [suffix]. */
    fun numbers(suffix: String, dir: String = LIVE): List<Long> = files.names(dir).mapNotNull { Names.numberOf(it, suffix) }.sorted()

    /** The header of the generation file at [path]. */
    fun header(path: String) = (codec.check(files.bytes(path)!!) as FileCheck.Ok).file.header

    /** The vault the generation at [path] holds, opened with [vk]. */
    fun vaultIn(path: String, vk: ByteArray): Vault {
        val file = (codec.check(files.bytes(path)!!) as FileCheck.Ok).file
        return (codec.open(file, vk) as io.github.dilshodbekmx.passwordvault.core.vaultfile.BodyResult.Opened).vault
    }

    /** No rename replaced an existing name [V-S1]. */
    fun assertNoReplacement() = assertEquals("renames onto an existing name", emptyList<String>(), files.replacements)

    /** Damage on the disk: every byte zero, as a lost write leaves a file. */
    fun zeroFill(path: String) = files.damage(path) { ByteArray(it.size) }

    /** Damage confined to the trailer: its last byte flipped. */
    fun breakTrailer(path: String) = files.damage(path) { it.also { b -> b[b.size - 1] = (b[b.size - 1].toInt() xor 1).toByte() } }

    /** Damage inside the body: one byte of the ciphertext flipped, so the trailer and the tag both fail. */
    fun breakBody(path: String) = files.damage(path) { it.also { b -> b[b.size - 100] = (b[b.size - 100].toInt() xor 1).toByte() } }
}
