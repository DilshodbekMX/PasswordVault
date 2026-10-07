package io.github.dilshodbekmx.passwordvault.data

import io.github.dilshodbekmx.passwordvault.core.crypto.CryptoFactory
import io.github.dilshodbekmx.passwordvault.core.crypto.KdfUnavailable
import io.github.dilshodbekmx.passwordvault.core.crypto.MasterKdf
import io.github.dilshodbekmx.passwordvault.core.lock.flow.PasswordOpen
import io.github.dilshodbekmx.passwordvault.core.lock.flow.VaultRef
import io.github.dilshodbekmx.passwordvault.core.lock.flow.VaultWrite
import io.github.dilshodbekmx.passwordvault.core.model.CustomField
import io.github.dilshodbekmx.passwordvault.core.model.DraftEntry
import io.github.dilshodbekmx.passwordvault.core.model.EditTexts
import io.github.dilshodbekmx.passwordvault.core.model.Entry
import io.github.dilshodbekmx.passwordvault.core.model.EntryResult
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.core.model.VaultEdits
import io.github.dilshodbekmx.passwordvault.core.storage.FilePrimitives
import io.github.dilshodbekmx.passwordvault.core.storage.VaultStores
import io.github.dilshodbekmx.passwordvault.core.vaultfile.BodyResult
import io.github.dilshodbekmx.passwordvault.core.vaultfile.BuildInfo
import io.github.dilshodbekmx.passwordvault.core.vaultfile.FileCheck
import io.github.dilshodbekmx.passwordvault.core.vaultfile.VaultCodec
import io.github.dilshodbekmx.passwordvault.core.vaultfile.VaultCodecs
import io.github.dilshodbekmx.passwordvault.core.vaultfile.VaultHeader
import io.github.dilshodbekmx.passwordvault.fake.FakeAppSettings
import io.github.dilshodbekmx.passwordvault.platform.AppDispatchers
import io.github.dilshodbekmx.passwordvault.testing.FakeClock
import io.github.dilshodbekmx.passwordvault.testing.FakeRandom
import io.github.dilshodbekmx.passwordvault.testing.storage.FakeFiles
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import org.junit.Assert.assertTrue
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

/*
 * What the repository's tests share: a phone whose disk outlives its app processes, a fast stand-in
 * for Argon2id, file primitives over a real directory, and short ways to make entries.
 */

/** The app's private files directory on the fake disk, and the vaults directory in it (architecture.md §4.7). */
internal const val FILES = "/data/files"
internal const val VAULTS = "$FILES/vaults"
internal const val LIVE = "$VAULTS/live"

internal const val PASSWORD = "correct horse battery staple"
internal const val OTHER_PASSWORD = "a different master password"

/** The bytes the lock would hand over for [password] (`MasterPasswordBytes.of` of plain ASCII is its ASCII). */
internal fun bytesOf(password: String): ByteArray = password.encodeToByteArray()

/** The n-th test id: a valid lower-case UUID v4. */
internal fun id(n: Int): String = "00000000-0000-4000-8000-" + n.toString().padStart(12, '0')

/** The n-th vault id. */
internal fun vaultId(n: Int): String = "11111111-1111-4111-8111-" + n.toString().padStart(12, '0')

internal fun entry(
    n: Int,
    title: String = "Account $n",
    password: String = "secret-$n",
    deletedAt: Long? = null,
    favourite: Boolean = false,
): Entry = Entry(
    id = id(n),
    title = title,
    username = "user$n@example.com",
    password = password,
    notes = "note $n",
    urls = listOf("https://site$n.example.com/login"),
    fields = listOf(CustomField("PIN", "12$n", true), CustomField("Phone", "+998 90 000-00-0$n", false)),
    favourite = favourite,
    createdAt = 1_000L + n,
    updatedAt = 1_000L + n,
    passwordChangedAt = 1_000L + n,
    deletedAt = deletedAt,
)

internal fun form(title: String, password: String = "p4ss-$title", notes: String = ""): DraftEntry =
    DraftEntry(title = title, username = "$title@example.com", password = password, notes = notes, urls = listOf("https://$title.example.com"))

/** The stored texts of the tests: the English ones, with a mark so that a test can see where a title came from. */
internal object TestTexts : EditTexts {
    override fun untitled(nowMs: Long): String = "Untitled $nowMs"

    override fun fromBackup(title: String): String = "$title (from backup)"

    override fun fromFile(title: String): String = "$title (from file)"
}

/**
 * A stand-in for Argon2id that the tests can afford: SHA-256 over a tag, the salt and the password
 * bytes, 32 bytes, so a wrong password or salt gives another key as Argon2id would. [unavailable]
 * makes every call throw `KdfUnavailable`; [calls] counts them.
 */
internal class FastKdf : MasterKdf {
    @Volatile var unavailable: Boolean = false
    val calls = AtomicInteger()

    override suspend fun kek(passwordBytes: ByteArray, salt: ByteArray): ByteArray {
        calls.incrementAndGet()
        if (unavailable) throw KdfUnavailable(null)
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("pv-test-kdf".encodeToByteArray())
        digest.update(salt)
        digest.update(passwordBytes)
        return digest.digest()
    }
}

/**
 * [FilePrimitives] over a real directory, for the tests on a temporary directory: `createNewFile`
 * for an exclusive create, `FileDescriptor.sync` for the file, `FileChannel.force` on the directory,
 * and `File.renameTo`, which is rename(2) on Linux and replaces an existing target.
 */
internal class DirFiles : FilePrimitives {
    override fun list(dir: String): List<String>? {
        val file = File(dir)
        if (!file.exists()) return null
        if (!file.isDirectory) throw IOException("not a directory")
        return file.list()?.toList() ?: throw IOException("cannot list")
    }

    override fun read(path: String, maxBytes: Int): ByteArray {
        val file = File(path)
        if (!file.isFile) throw FileNotFoundException("no such file")
        FileInputStream(file).use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (total <= maxBytes) {
                val n = input.read(buffer, 0, minOf(buffer.size.toLong(), maxBytes + 1L - total).toInt())
                if (n < 0) break
                out.write(buffer, 0, n)
                total += n
            }
            return out.toByteArray()
        }
    }

    override fun writeNew(path: String, bytes: ByteArray) {
        val file = File(path)
        if (!file.createNewFile()) throw IOException("exists")
        FileOutputStream(file).use { out ->
            out.write(bytes)
            out.flush()
            out.fd.sync()
        }
    }

    override fun exists(path: String): Boolean = File(path).exists()

    override fun rename(from: String, to: String) {
        if (!File(from).exists()) throw FileNotFoundException("no such file")
        if (!File(from).renameTo(File(to))) throw IOException("rename failed")
    }

    override fun syncDir(dir: String) {
        FileChannel.open(Paths.get(dir), StandardOpenOption.READ).use { it.force(true) }
    }

    override fun delete(path: String) {
        val file = File(path)
        if (!file.exists()) throw FileNotFoundException("no such file")
        if (file.isDirectory || !file.delete()) throw IOException("delete failed")
    }

    override fun mkdir(dir: String) {
        if (!File(dir).mkdir()) throw IOException("mkdir failed")
    }

    override fun deleteTree(dir: String) {
        val file = File(dir)
        if (!file.isDirectory) throw FileNotFoundException("no such directory")
        if (!file.deleteRecursively()) throw IOException("delete failed")
    }
}

/**
 * One phone: [files] is its disk, kept from one app process to the next, with the vaults in
 * [vaultsDir]; [process] starts a new process: a new store (which remembers nothing of the last
 * one) and a new repository over it. Clock, random, settings and KDF are the phone's and outlive
 * processes, as the clocks and the settings file do.
 */
internal class Phone(
    val files: FilePrimitives = FakeFiles().apply { mkdirs(FILES) },
    val vaultsDir: String = VAULTS,
    val clock: FakeClock = FakeClock(),
    val random: FakeRandom = FakeRandom(5L),
    val settings: FakeAppSettings = FakeAppSettings(),
    val kdf: MasterKdf = FastKdf(),
    val build: BuildInfo = BuildInfo("PasswordVault 1.0", 1),
) {
    val codec: VaultCodec = VaultCodecs.create(CryptoFactory.aead(), random, clock, build)

    val liveDir: String get() = "$vaultsDir/live"

    /** A new app process: the store and the repository as `DataModule` builds them. */
    fun process(scope: CoroutineScope, dispatcher: CoroutineDispatcher): DefaultVaultRepository {
        val storage = VaultStores.create(files, codec, clock, vaultsDir, build)
        return DefaultVaultRepository(
            storage = storage,
            codec = codec,
            kdf = kdf,
            random = random,
            clock = clock,
            settings = { settings },
            texts = TestTexts,
            headers = GenerationHeaders(files, codec, vaultsDir),
            scope = scope,
            dispatchers = AppDispatchers.single(dispatcher),
        )
    }

    /** The live generation numbers, by their names. */
    fun liveNumbers(): List<Long> = names(liveDir).mapNotNull { numberOf(it, ".pvault") }.sorted()

    /** The names of [dir], sorted; empty when it does not exist. */
    fun names(dir: String): List<String> = (files.list(dir) ?: emptyList()).sorted()

    fun pathOf(generation: Long, dir: String = liveDir): String = "$dir/" + GenerationHeaders.fileName(generation)

    /** The header of the generation file at [path]. */
    fun header(path: String): VaultHeader = (codec.check(files.read(path, GenerationHeaders.CAP)) as FileCheck.Ok).file.header

    /** The vault in the generation file at [path], opened with [vk]. */
    fun vaultIn(path: String, vk: ByteArray): Vault {
        val file = (codec.check(files.read(path, GenerationHeaders.CAP)) as FileCheck.Ok).file
        return (codec.open(file, vk) as BodyResult.Opened).vault
    }

    private fun numberOf(name: String, suffix: String): Long? {
        if (!name.startsWith("vault-") || !name.endsWith(suffix) || name.length != 6 + 10 + suffix.length) return null
        val digits = name.substring(6, 16)
        return if (digits.all { it in '0'..'9' }) digits.toLong() else null
    }
}

/** F1 as the lock runs it: a new vault with [password] typed twice, which opens. */
internal suspend fun DefaultVaultRepository.create(vaultId: String = vaultId(1), password: String = PASSWORD, from: Vault? = null): VaultWrite.Done {
    val result = createVault(vaultId, bytesOf(password), bytesOf(password), from, putCurrentAside = false)
    assertTrue("created: $result", result is VaultWrite.Done)
    return result as VaultWrite.Done
}

/** An unlock with the master password, which opens. */
internal suspend fun DefaultVaultRepository.unlock(password: String = PASSWORD, target: VaultRef = VaultRef.Current): PasswordOpen.Opened {
    val result = openWithPassword(target, bytesOf(password))
    assertTrue("opened: $result", result is PasswordOpen.Opened)
    return result as PasswordOpen.Opened
}

/** Save of the add form: the new entry's id. */
internal suspend fun DefaultVaultRepository.add(form: DraftEntry, random: FakeRandom): String {
    val result = edit(VaultEdits.add(form, TestTexts, random))
    assertTrue("saved: $result", result is SaveResult.Saved)
    val value = (result as SaveResult.Saved).value
    assertTrue("added: $value", value is EntryResult.Done)
    return (value as EntryResult.Done).entryId
}

/** The vault shown now; fails when it is locked. */
internal fun DefaultVaultRepository.shown(): VaultState.Unlocked {
    val state = state.value
    assertTrue("unlocked: $state", state is VaultState.Unlocked)
    return state as VaultState.Unlocked
}
