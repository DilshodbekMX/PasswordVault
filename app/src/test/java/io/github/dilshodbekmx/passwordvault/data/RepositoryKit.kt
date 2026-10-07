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
import org.junit.rules.TemporaryFolder
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
 *
 * It has the hooks of `FakeFiles` that the tests use: [beforeOp] runs at the start of every call,
 * and [fail] makes chosen calls throw `IOException` (a `writeNew` after half of its bytes).
 */
internal class DirFiles : FilePrimitives {
    private class Fault(val op: FakeFiles.Op, val matches: (String) -> Boolean, var times: Int)

    private val faults = mutableListOf<Fault>()

    @Volatile var beforeOp: ((op: FakeFiles.Op, path: String) -> Unit)? = null

    /** The next [times] calls of [op] on a path that [path] accepts throw (negative: every one, until [clearFaults]). */
    @Synchronized
    fun fail(op: FakeFiles.Op, times: Int = 1, path: (String) -> Boolean = { true }) {
        faults += Fault(op, path, times)
    }

    @Synchronized
    fun clearFaults() = faults.clear()

    /** Runs the hook; true when this call is to fail. */
    private fun enter(op: FakeFiles.Op, path: String): Boolean {
        beforeOp?.invoke(op, path)
        synchronized(this) {
            val fault = faults.firstOrNull { it.op == op && it.matches(path) } ?: return false
            if (fault.times > 0 && --fault.times == 0) faults.remove(fault)
            return true
        }
    }

    private fun check(op: FakeFiles.Op, path: String) {
        if (enter(op, path)) throw IOException("injected failure: $op $path")
    }

    override fun list(dir: String): List<String>? {
        check(FakeFiles.Op.LIST, dir)
        val file = File(dir)
        if (!file.exists()) return null
        if (!file.isDirectory) throw IOException("not a directory")
        return file.list()?.toList() ?: throw IOException("cannot list")
    }

    override fun read(path: String, maxBytes: Int): ByteArray {
        check(FakeFiles.Op.READ, path)
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
        val failing = enter(FakeFiles.Op.WRITE_NEW, path)
        val file = File(path)
        if (!file.createNewFile()) throw IOException("exists")
        FileOutputStream(file).use { out ->
            if (failing) {
                out.write(bytes, 0, bytes.size / 2)
                throw IOException("injected failure: no space left")
            }
            out.write(bytes)
            out.flush()
            out.fd.sync()
        }
    }

    override fun exists(path: String): Boolean {
        check(FakeFiles.Op.EXISTS, path)
        return File(path).exists()
    }

    override fun rename(from: String, to: String) {
        check(FakeFiles.Op.RENAME, from)
        if (!File(from).exists()) throw FileNotFoundException("no such file")
        if (!File(from).renameTo(File(to))) throw IOException("rename failed")
    }

    override fun syncDir(dir: String) {
        check(FakeFiles.Op.SYNC_DIR, dir)
        FileChannel.open(Paths.get(dir), StandardOpenOption.READ).use { it.force(true) }
    }

    override fun delete(path: String) {
        check(FakeFiles.Op.DELETE, path)
        val file = File(path)
        if (!file.exists()) throw FileNotFoundException("no such file")
        if (file.isDirectory || !file.delete()) throw IOException("delete failed")
    }

    override fun mkdir(dir: String) {
        check(FakeFiles.Op.MKDIR, dir)
        if (!File(dir).mkdir()) throw IOException("mkdir failed")
    }

    override fun deleteTree(dir: String) {
        check(FakeFiles.Op.DELETE_TREE, dir)
        val file = File(dir)
        if (!file.isDirectory) throw FileNotFoundException("no such directory")
        if (!file.deleteRecursively()) throw IOException("delete failed")
    }
}

/** Where a test's vaults live: the in-memory file system of `:testing`, or a real temporary directory. */
enum class DiskKind { FAKE_FILES, TEMPORARY_DIRECTORY }

/**
 * A phone's disk for the repository's tests, with the same hooks on both kinds, so that every test
 * runs against the real `VaultStore` on `FakeFiles` and on a temporary directory (build plan 3.1).
 * Only `FakeFiles` can tell what a power cut would leave; a real directory answers [afterPowerCut]
 * with what is on it.
 */
internal sealed class Disk {
    abstract val files: FilePrimitives
    abstract val vaultsDir: String

    /** Runs at the start of every file operation, with its kind and path (for a rename, the source). */
    abstract var beforeOp: ((op: FakeFiles.Op, path: String) -> Unit)?

    /** The next [times] calls of [op] on a path that [path] accepts throw `IOException`. */
    abstract fun fail(op: FakeFiles.Op, times: Int = 1, path: (String) -> Boolean = { true })

    abstract fun clearFaults()

    /** Damage on the disk: every byte of [path] zero, as a lost write leaves a file. */
    abstract fun zeroFill(path: String)

    /** The bytes of the file at [path] now, or null. */
    abstract fun bytes(path: String): ByteArray?

    /** The bytes of the file at [path] after a power cut now, or null when it would be gone. */
    abstract fun afterPowerCut(path: String): ByteArray?

    class Fake : Disk() {
        val fake = FakeFiles().apply { mkdirs(FILES) }
        override val files: FilePrimitives get() = fake
        override val vaultsDir = VAULTS
        override var beforeOp: ((op: FakeFiles.Op, path: String) -> Unit)?
            get() = fake.beforeOp
            set(value) {
                fake.beforeOp = value
            }

        override fun fail(op: FakeFiles.Op, times: Int, path: (String) -> Boolean) = fake.fail(op, times, path)

        override fun clearFaults() = fake.clearFaults()

        override fun zeroFill(path: String) = fake.damage(path) { ByteArray(it.size) }

        override fun bytes(path: String): ByteArray? = fake.bytes(path)

        override fun afterPowerCut(path: String): ByteArray? = fake.copy().also { it.powerCut() }.bytes(path)

        override fun toString() = "FakeFiles"
    }

    class Directory(root: File) : Disk() {
        private val dir = DirFiles()
        override val files: FilePrimitives get() = dir
        override val vaultsDir: String = File(File(root, "files").apply { mkdirs() }, "vaults").path
        override var beforeOp: ((op: FakeFiles.Op, path: String) -> Unit)?
            get() = dir.beforeOp
            set(value) {
                dir.beforeOp = value
            }

        override fun fail(op: FakeFiles.Op, times: Int, path: (String) -> Boolean) = dir.fail(op, times, path)

        override fun clearFaults() = dir.clearFaults()

        override fun zeroFill(path: String) {
            val file = File(path)
            file.writeBytes(ByteArray(file.length().toInt()))
        }

        override fun bytes(path: String): ByteArray? = File(path).takeIf { it.isFile }?.readBytes()

        override fun afterPowerCut(path: String): ByteArray? = bytes(path)

        override fun toString() = "a temporary directory"
    }

    companion object {
        fun of(kind: DiskKind, temp: TemporaryFolder): Disk = when (kind) {
            DiskKind.FAKE_FILES -> Fake()
            DiskKind.TEMPORARY_DIRECTORY -> Directory(temp.newFolder())
        }
    }
}

/**
 * One phone: [disk] is its disk, kept from one app process to the next, with the vaults in
 * [vaultsDir]; [process] starts a new process: a new store (which remembers nothing of the last
 * one) and a new repository over it. Clock, random, settings and KDF are the phone's and outlive
 * processes, as the clocks and the settings file do.
 */
internal class Phone(
    val disk: Disk = Disk.Fake(),
    val clock: FakeClock = FakeClock(),
    val random: FakeRandom = FakeRandom(5L),
    val settings: FakeAppSettings = FakeAppSettings(),
    val kdf: MasterKdf = FastKdf(),
    val build: BuildInfo = BuildInfo("PasswordVault 1.0", 1),
) {
    val codec: VaultCodec = VaultCodecs.create(CryptoFactory.aead(), random, clock, build)

    val files: FilePrimitives get() = disk.files
    val vaultsDir: String get() = disk.vaultsDir

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
