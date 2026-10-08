package io.github.dilshodbekmx.passwordvault.testing.lock

import io.github.dilshodbekmx.passwordvault.core.crypto.KdfUnavailable
import io.github.dilshodbekmx.passwordvault.core.crypto.MasterKdf
import io.github.dilshodbekmx.passwordvault.core.lock.flow.AsideVault
import io.github.dilshodbekmx.passwordvault.core.lock.flow.CurrentVault
import io.github.dilshodbekmx.passwordvault.core.lock.flow.GenerationInfo
import io.github.dilshodbekmx.passwordvault.core.lock.flow.PasswordOpen
import io.github.dilshodbekmx.passwordvault.core.lock.flow.VaultOpen
import io.github.dilshodbekmx.passwordvault.core.lock.flow.VaultPort
import io.github.dilshodbekmx.passwordvault.core.lock.flow.VaultRef
import io.github.dilshodbekmx.passwordvault.core.lock.flow.VaultScan
import io.github.dilshodbekmx.passwordvault.core.lock.flow.VaultWrite
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.core.util.Clock
import io.github.dilshodbekmx.passwordvault.core.util.RandomSource
import io.github.dilshodbekmx.passwordvault.core.util.Uuid
import io.github.dilshodbekmx.passwordvault.testing.FakeRandom
import java.io.IOException
import java.security.MessageDigest

/**
 * The vaults on the phone behind [VaultPort], in memory, for the tests of the lock's flows (lock.md
 * §15.1; architecture.md §2.1, §3.4). It stands where `DefaultVaultRepository` over `VaultStore`
 * stands in the app, and answers as they do (architecture.md §4.8, §4.9, §4.11), without deriving or
 * encrypting anything: a password slot opens when the bytes given equal the bytes it was made from,
 * and a generation opens when the key given for its `vk_id` equals its VK.
 *
 * **What is on the phone.** The current vault ([current], `live/`) and the vaults put aside
 * ([asideVaults], `aside-<ms>-<vault_id>/`), each a [FakeVault]: a list of generations, each with
 * its number, its `vk_id`, its `created` time and what reading it gives ([FileState], the damage
 * flags of lock.md §15.1), and the keys those generations are sealed under, each with its VK and
 * the master-password bytes of its slot. [seed] and [seedAside] put a vault there as an earlier
 * process left it; a test changes it through the [FakeVault].
 *
 * **What the process holds.** The open current vault ([openVaultId], [openGeneration], [openVkId])
 * and at most one vault put aside that was opened in place ([openAsideId]). [restart] is a new
 * process: both are gone, and so is everything that was staged and not published (`*.tmp`, a
 * `new-<vault_id>/` directory), as at the start of `VaultStore`.
 *
 * **A record of every call and of every write.** [calls] lists every call, by name. [writes] lists
 * every change of the vault files, one line per file or directory made, renamed or deleted. Test
 * T17 of lock.md ("the lock never writes vault data") asserts on both: no line in [writes], and in
 * [calls] none of [WRITING_CALLS].
 *
 * **A stop at every boundary** (lock.md T14, T15, T16). Every call passes a boundary when it starts
 * and when it ends, and a call that writes passes one after each of its steps: staged, proven, the
 * current vault put aside, published. [stopAt] makes the n-th boundary from now throw [Stopped] (an
 * `Error`, so that no `catch (e: Exception)` of the code under test stops it). From then on the
 * process is dead: every call throws [Stopped] until [restart]. [processAlive] lets a test tie this
 * to another fake that stopped first (`FakeRecordFiles.alive`).
 *
 * **It can fail and wait.** [noMemory] makes every derivation fail as when Argon2id cannot run.
 * [failWrites] makes a writing call end the way the storage engine ends when it cannot write.
 * [scanFails] makes [vaults] throw. [beforeCall], when set, runs at the start of every suspending
 * call and may suspend, so a test can end a visit while "Argon2id runs".
 *
 * Not modelled: a power cut inside the storage engine (its own tests cover every kill point of
 * architecture.md §4.9 on `FakeFiles`; here every finished step is on disk), housekeeping, and the
 * undoing of an open whose caller was cancelled (the lock drops such a vault itself, lock.md I11).
 * Calls are synchronized; only [beforeCall] suspends.
 */
class FakeVaultPort(
    private val clock: Clock,
    private val random: RandomSource = FakeRandom(11),
) : VaultPort {

    /** What reading one generation file gives (architecture.md §4.8). */
    enum class FileState { OK, DAMAGED, NEWER, UNREADABLE }

    /** The suffix of a generation file (architecture.md §4.7): only [LIVE] ones are read. */
    enum class Suffix { LIVE, TMP, BAD, ASIDE }

    /** How a writing call is made to end, for [failWrites]. */
    enum class WriteFault {
        /** It fails before the new file is in place: nothing changed. */
        NOTHING_CHANGED,

        /** The new file does not prove from disk: it is quarantined. */
        QUARANTINED,

        /** The new file is in place, and the last directory sync fails: "Saving may not have finished". */
        UNCLEAR,

        /** A call that answers with a `Boolean` answers false, and nothing changed. */
        REFUSED,
    }

    /** Thrown at the boundary chosen with [stopAt], and by every call after it until [restart]. */
    class Stopped(val boundary: Int, val at: String) : Error("stopped at boundary $boundary: $at")

    /** One generation file of a vault. */
    class Generation internal constructor(
        val number: Long,
        val vkId: String,
        val createdMs: Long,
        /** The process that wrote it, counted from 0 ([restart] starts the next one); [EARLIER] for one a test put there. */
        val writtenByProcess: Int,
    ) {
        @Volatile var state: FileState = FileState.OK

        @Volatile var suffix: Suffix = Suffix.LIVE

        override fun toString(): String = "vault-$number.${suffix.name.lowercase()}($vkId, $state)"
    }

    /** One vault directory: its generations and the keys they are sealed under. */
    inner class FakeVault internal constructor(val vaultId: String) {
        private val files = ArrayList<Generation>()
        private val vks = LinkedHashMap<String, ByteArray>()
        private val passwords = LinkedHashMap<String, ByteArray>()

        /** When it was put aside (wall clock), or null while it is the current vault. */
        @Volatile var putAsideAtMs: Long? = null
            internal set

        /** The decrypted body, as far as a test cares about it. */
        @Volatile var content: Vault = Vault()

        /** Every generation file of the directory, lowest number first, whatever its suffix. */
        val generations: List<Generation> get() = synchronized(this@FakeVaultPort) { files.sortedBy { it.number } }

        /** The live generations (`.pvault`), highest number first. */
        val live: List<Generation> get() = synchronized(this@FakeVaultPort) { liveLocked() }

        /** The `vk_id`s of the keys this vault has had, oldest first. */
        val vkIds: List<String> get() = synchronized(this@FakeVaultPort) { vks.keys.toList() }

        /** The `vk_id` of the newest live generation, whatever its state. */
        val newestVkId: String get() = synchronized(this@FakeVaultPort) { liveLocked().first().vkId }

        /** A copy of the VK with [vkId]. */
        fun vk(vkId: String): ByteArray = synchronized(this@FakeVaultPort) { vks.getValue(vkId).copyOf() }

        /** A copy of the master-password bytes whose slot wraps the VK with [vkId]. */
        fun password(vkId: String): ByteArray = synchronized(this@FakeVaultPort) { passwords.getValue(vkId).copyOf() }

        /** The generation numbered [number]. */
        fun generation(number: Long): Generation = synchronized(this@FakeVaultPort) { files.first { it.number == number } }

        /**
         * An ordinary save as an earlier process left it: one more live generation under the newest
         * key, numbered above every file of the directory.
         */
        fun save(): Generation = synchronized(this@FakeVaultPort) { addLocked(liveLocked().first().vkId, Suffix.LIVE, EARLIER) }

        /**
         * A master-password change as an earlier process left it (lock.md F8 step 6): a new key whose
         * slot opens with [password], and one live generation under it. Returns the new `vk_id`.
         */
        fun rekey(password: ByteArray, vkId: String = random.uuid(), vk: ByteArray = random.bytes(KEY_SIZE)): String =
            synchronized(this@FakeVaultPort) {
                putKeyLocked(vkId, vk, password)
                addLocked(vkId, Suffix.LIVE, EARLIER)
                vkId
            }

        internal fun liveLocked(): List<Generation> = files.filter { it.suffix == Suffix.LIVE }.sortedByDescending { it.number }

        internal fun allLocked(): MutableList<Generation> = files

        internal fun nextNumberLocked(): Long = (files.maxOfOrNull { it.number } ?: 0L) + 1

        internal fun addLocked(vkId: String, suffix: Suffix, writtenBy: Int = process): Generation {
            val made = Generation(nextNumberLocked(), vkId, clock.wallMs(), writtenBy)
            made.suffix = suffix
            files += made
            return made
        }

        internal fun putKeyLocked(vkId: String, vk: ByteArray, password: ByteArray) {
            require(Uuid.isValid(vkId)) { "not a lower-case UUID of version 4" }
            require(vk.size == KEY_SIZE) { "VK has 32 bytes" }
            vks[vkId] = vk.copyOf()
            passwords[vkId] = password.copyOf()
        }

        internal fun vkLocked(vkId: String): ByteArray? = vks[vkId]

        internal fun passwordLocked(vkId: String): ByteArray? = passwords[vkId]

        override fun toString(): String = "FakeVault($vaultId, ${generations.joinToString()})"
    }

    private class Session(val vault: FakeVault, var generation: Long, var vkId: String)

    private var live: FakeVault? = null
    private val aside = ArrayList<FakeVault>()
    private val staged = ArrayList<FakeVault>() // new-<vault_id>/ directories: gone at the next start
    private var session: Session? = null
    private var asideSession: Session? = null
    private val openedAside = HashSet<String>() // opened in place in this process (swapIn asks for it)
    private val passwordOpened = HashSet<String>() // vault_id + vk_id whose password opened a copy an earlier process wrote
    private var process = 0
    private val callLog = ArrayList<String>()
    private val writeLog = ArrayList<String>()
    private val faults = ArrayDeque<WriteFault>()
    private var passed = 0
    private var stopAtBoundary = 0
    private var dead: Stopped? = null

    /** Argon2id cannot run: `openWithPassword` answers `NoMemory`, `checkPassword` null, and a writing call that derives throws `KdfUnavailable`. */
    @Volatile var noMemory: Boolean = false

    /** [vaults] throws `IOException`, as a port might when the vault directories cannot be listed. */
    @Volatile var scanFails: Boolean = false

    /** Whether the process is alive for a reason outside this fake; when it says false every call throws [Stopped]. */
    @Volatile var processAlive: () -> Boolean = { true }

    /** Called at every boundary, before a stop: its index (from 1) and where it is. */
    @Volatile var onBoundary: ((index: Int, at: String) -> Unit)? = null

    /** Runs at the start of every suspending call, with the call's name; it may suspend. */
    @Volatile var beforeCall: (suspend (call: String) -> Unit)? = null

    // ---------------------------------------------------------------------------------------------
    // For setting up and looking (no boundary, no record)

    /** The current vault, or null when there is none. */
    val current: FakeVault? @Synchronized get() = live

    /** The vaults put aside, newest first. */
    val asideVaults: List<FakeVault> @Synchronized get() = aside.sortedWith(ASIDE_ORDER)

    /** The vault with [vaultId], current or put aside (the newest when several were put aside with one id). */
    @Synchronized
    fun vault(vaultId: String): FakeVault? = live?.takeIf { it.vaultId == vaultId } ?: asideOfLocked(vaultId)

    /** The `vault_id` of the open current vault, or null while it is locked. */
    val openVaultId: String? @Synchronized get() = session?.vault?.vaultId

    /** The generation of the open current vault. */
    val openGeneration: Long? @Synchronized get() = session?.generation

    /** The `vk_id` of the open current vault. */
    val openVkId: String? @Synchronized get() = session?.vkId

    /** The `vault_id` of the vault put aside that is open in place, or null. */
    val openAsideId: String? @Synchronized get() = asideSession?.vault?.vaultId

    /** Every call so far, in order, such as `open current` or `swapIn <vault_id>`; calls of a dead process are not listed. */
    val calls: List<String> @Synchronized get() = callLog.toList()

    /** Every change of the vault files so far, in order: one line per file or directory made, renamed or deleted. */
    val writes: List<String> @Synchronized get() = writeLog.toList()

    /** The calls among [calls] whose name is one of [WRITING_CALLS]. */
    val writingCalls: List<String> @Synchronized get() = callLog.filter { call -> call.substringBefore(' ') in WRITING_CALLS }

    /** The number of boundaries passed so far. */
    val boundaries: Int @Synchronized get() = passed

    /** False after a stop, until [restart]. */
    val alive: Boolean @Synchronized get() = dead == null

    /**
     * Puts a vault on the phone as the current one, as an earlier process left it: one key whose
     * slot opens with [password], and [generations] live generations under it.
     */
    @Synchronized
    fun seed(
        vaultId: String,
        password: ByteArray,
        generations: Int = 1,
        vkId: String = random.uuid(),
        vk: ByteArray = random.bytes(KEY_SIZE),
    ): FakeVault {
        check(live == null) { "a vault is current already" }
        return seedLocked(vaultId, password, generations, vkId, vk).also { live = it }
    }

    /** Puts a vault on the phone as one that was put aside at [putAsideAtMs], as an earlier process left it. */
    @Synchronized
    fun seedAside(
        vaultId: String,
        password: ByteArray,
        putAsideAtMs: Long = clock.wallMs(),
        generations: Int = 1,
        vkId: String = random.uuid(),
        vk: ByteArray = random.bytes(KEY_SIZE),
    ): FakeVault = seedLocked(vaultId, password, generations, vkId, vk).also {
        it.putAsideAtMs = putAsideAtMs
        aside += it
    }

    private fun seedLocked(vaultId: String, password: ByteArray, generations: Int, vkId: String, vk: ByteArray): FakeVault {
        require(Uuid.isValid(vaultId)) { "not a lower-case UUID of version 4" }
        require(generations >= 1) { "a vault has a generation" }
        val made = FakeVault(vaultId)
        made.putKeyLocked(vkId, vk, password)
        repeat(generations) { made.addLocked(vkId, Suffix.LIVE, EARLIER) }
        return made
    }

    /** The next [results] writing calls end like this, in order, instead of writing. */
    @Synchronized
    fun failWrites(vararg results: WriteFault) {
        faults.addAll(results)
    }

    /** The [boundaries]-th boundary from now stops the process. */
    @Synchronized
    fun stopAt(boundaries: Int) {
        require(boundaries >= 1) { "a boundary from now: 1 or more" }
        stopAtBoundary = passed + boundaries
    }

    /** No stop is armed any more. */
    @Synchronized
    fun clearStop() {
        stopAtBoundary = 0
    }

    /**
     * A new process: nothing is open, what was staged and not published is deleted (the start of
     * `VaultStore`, architecture.md §4.8 step 1), pending faults and stops are dropped. The files
     * that were published stay as they are.
     */
    @Synchronized
    fun restart() {
        dead = null
        stopAtBoundary = 0
        faults.clear()
        session = null
        asideSession = null
        openedAside.clear()
        passwordOpened.clear()
        staged.clear()
        for (vault in listOfNotNull(live) + aside) vault.allLocked().removeAll { it.suffix == Suffix.TMP }
        process++
    }

    // ---------------------------------------------------------------------------------------------
    // VaultPort: reading

    @Synchronized
    override fun vaults(): VaultScan {
        enter("vaults")
        boundary("vaults: before")
        if (scanFails) throw IOException("injected failure: the vault directories cannot be listed")
        val scan = VaultScan(
            current = currentOfLocked(live),
            aside = aside.sortedWith(ASIDE_ORDER).map { vault ->
                AsideVault(vault.vaultId, vault.putAsideAtMs ?: 0L, (currentOfLocked(vault) as? CurrentVault.Present)?.newest)
            },
        )
        boundary("vaults: after")
        return scan
    }

    override suspend fun open(target: VaultRef, keys: Map<String, ByteArray>): VaultOpen {
        val name = "open " + nameOf(target)
        beforeCall?.invoke(name)
        return synchronized(this) {
            enter(name)
            boundary("$name: before")
            val result = openLocked(target, keys)
            boundary("$name: after")
            result
        }
    }

    private fun openLocked(target: VaultRef, keys: Map<String, ByteArray>): VaultOpen {
        val vault = dirOfLocked(target) ?: return VaultOpen.Damaged
        val files = vault.liveLocked()
        val newestOnDisk = files.firstOrNull()?.number ?: return VaultOpen.Damaged
        for (gen in files) {
            when (gen.state) {
                FileState.UNREADABLE -> return VaultOpen.Unreadable // never past a file that could not be read
                FileState.NEWER -> return VaultOpen.Newer
                FileState.DAMAGED -> continue
                FileState.OK -> {
                    val key = keys[gen.vkId] ?: return VaultOpen.NeedsOtherKey(gen.number, gen.createdMs)
                    if (!key.contentEquals(vault.vkLocked(gen.vkId))) continue // the body does not open under this key
                    took(target, vault, gen, byPassword = false)
                    return VaultOpen.Opened(vault.vaultId, gen.number, gen.vkId, gen.createdMs, olderCopy = newestOnDisk > gen.number)
                }
            }
        }
        return VaultOpen.Damaged
    }

    override suspend fun openWithPassword(target: VaultRef, passwordBytes: ByteArray): PasswordOpen {
        val name = "openWithPassword " + nameOf(target)
        beforeCall?.invoke(name)
        return synchronized(this) {
            enter(name)
            boundary("$name: before")
            val result = openWithPasswordLocked(target, passwordBytes)
            boundary("$name: after")
            result
        }
    }

    private fun openWithPasswordLocked(target: VaultRef, passwordBytes: ByteArray): PasswordOpen {
        val vault = dirOfLocked(target)
        val files = vault?.liveLocked().orEmpty()
        // The distinct slots among the OK generations, newest first [V-S3]; here one slot per key.
        val slots = files.filter { it.state == FileState.OK }.map { it.vkId }.distinct()
        if (vault == null || slots.isEmpty()) {
            return when {
                files.any { it.state == FileState.NEWER } -> PasswordOpen.Newer
                files.any { it.state == FileState.UNREADABLE } -> PasswordOpen.Unreadable
                files.isEmpty() -> PasswordOpen.Unreadable
                else -> PasswordOpen.Damaged
            }
        }
        if (noMemory) return PasswordOpen.NoMemory
        val vkId = slots.firstOrNull { passwordBytes.contentEquals(vault.passwordLocked(it)) } ?: return PasswordOpen.Wrong
        // The slot opened: walk the generations as architecture.md §4.8 step 3 does.
        val newestOnDisk = files.first().number
        var newerKey: String? = null
        for (gen in files) {
            when (gen.state) {
                FileState.UNREADABLE -> return PasswordOpen.Unreadable
                FileState.NEWER -> return PasswordOpen.Newer
                FileState.DAMAGED -> continue
                FileState.OK -> {
                    if (gen.vkId != vkId) {
                        if (newerKey == null) newerKey = gen.vkId
                        continue
                    }
                    if (newerKey != null) {
                        val changedAt = files.last { it.state == FileState.OK && it.vkId == newerKey }.createdMs
                        return PasswordOpen.OlderPassword(changedAt, gen.number)
                    }
                    took(target, vault, gen, byPassword = true)
                    return PasswordOpen.Opened(vault.vaultId, gen.number, gen.vkId, gen.createdMs, olderCopy = newestOnDisk > gen.number)
                }
            }
        }
        return PasswordOpen.Damaged
    }

    /** [gen] of [vault] opened for [target]: it is the open vault now, or the one put aside held in place. */
    private fun took(target: VaultRef, vault: FakeVault, gen: Generation, byPassword: Boolean) {
        val opened = Session(vault, gen.number, gen.vkId)
        when (target) {
            VaultRef.Current -> session = opened
            is VaultRef.Aside -> {
                asideSession = opened
                openedAside += vault.vaultId
            }
        }
        if (byPassword && gen.writtenByProcess < process) passwordOpened += vault.vaultId + gen.vkId
    }

    override suspend fun checkPassword(passwordBytes: ByteArray): Boolean? {
        beforeCall?.invoke("checkPassword")
        return synchronized(this) {
            enter("checkPassword")
            boundary("checkPassword: before")
            val open = session ?: throw IllegalStateException("no vault is open")
            val result = if (noMemory) null else passwordBytes.contentEquals(open.vault.passwordLocked(open.vkId))
            boundary("checkPassword: after")
            result
        }
    }

    override suspend fun <T> withVaultKey(use: (vkId: String, vk: ByteArray) -> T): T {
        beforeCall?.invoke("withVaultKey")
        val (vkId, copy) = synchronized(this) {
            enter("withVaultKey")
            val open = session ?: throw IllegalStateException("no vault is open")
            open.vkId to open.vault.vkLocked(open.vkId)!!.copyOf()
        }
        try {
            return use(vkId, copy)
        } finally {
            copy.fill(0)
        }
    }

    override suspend fun lock() {
        beforeCall?.invoke("lock")
        synchronized(this) {
            enter("lock")
            boundary("lock: before")
            session = null
            asideSession = null
            boundary("lock: after")
        }
    }

    override suspend fun dropAside() {
        beforeCall?.invoke("dropAside")
        synchronized(this) {
            enter("dropAside")
            boundary("dropAside: before")
            asideSession?.let { openedAside -= it.vault.vaultId } // no longer open in place: swapIn would refuse it
            asideSession = null
            boundary("dropAside: after")
        }
    }

    // ---------------------------------------------------------------------------------------------
    // VaultPort: the calls that write vault data (lock.md I1)

    override suspend fun createVault(vaultId: String, first: ByteArray, again: ByteArray, from: Vault?, putCurrentAside: Boolean): VaultWrite {
        val name = "createVault $vaultId" + if (putCurrentAside) " putCurrentAside" else ""
        beforeCall?.invoke(name)
        return synchronized(this) {
            enter(name)
            if (noMemory) throw KdfUnavailable(null)
            newVaultLocked("createVault", vaultId, first, again, from ?: Vault(), putCurrentAside)
        }
    }

    override suspend fun replaceVault(newVaultId: String, passwordBytes: ByteArray): VaultWrite {
        val name = "replaceVault $newVaultId"
        beforeCall?.invoke(name)
        return synchronized(this) {
            enter(name)
            val open = session ?: return@synchronized VaultWrite.NothingChanged
            if (noMemory) throw KdfUnavailable(null)
            newVaultLocked("replaceVault", newVaultId, passwordBytes, passwordBytes, open.vault.content, putCurrentAside = true)
        }
    }

    /** architecture.md §4.9 for a new vault: staged in `new-<vault_id>/`, proven, then published in one step. */
    private fun newVaultLocked(call: String, vaultId: String, first: ByteArray, again: ByteArray, content: Vault, putCurrentAside: Boolean): VaultWrite {
        require(Uuid.isValid(vaultId)) { "not a lower-case UUID of version 4" }
        boundary("$call: before")
        val fault = faults.removeFirstOrNull()
        if (fault == WriteFault.NOTHING_CHANGED || fault == WriteFault.REFUSED) return VaultWrite.NothingChanged
        val made = FakeVault(vaultId)
        val vkId = random.uuid()
        made.putKeyLocked(vkId, random.bytes(KEY_SIZE), first)
        made.content = content
        val gen = made.addLocked(vkId, Suffix.TMP)
        staged += made
        written("new-$vaultId/ staged")
        boundary("$call: staged")
        if (fault == WriteFault.QUARANTINED || !first.contentEquals(again)) {
            staged -= made // the file that did not prove is quarantined inside its directory, which is then discarded
            written("new-$vaultId/ discarded: it did not prove")
            return VaultWrite.Quarantined
        }
        boundary("$call: proven")
        val before = live
        if (before != null && !putCurrentAside) { // a vault is current: never replaced
            staged -= made
            written("new-$vaultId/ discarded: a vault is current")
            return VaultWrite.NothingChanged
        }
        gen.suffix = Suffix.LIVE
        if (before != null) {
            putAsideLocked(before)
            boundary("$call: the current vault put aside") // a kill here leaves no current vault
        }
        staged -= made
        live = made
        written("new-$vaultId/ is live/")
        asideSession = null
        if (fault == WriteFault.UNCLEAR) { // in place, but perhaps not durable: no vault is open
            session = null
            boundary("$call: published, not durable")
            return VaultWrite.Unclear
        }
        session = Session(made, gen.number, vkId)
        boundary("$call: published")
        return VaultWrite.Done(vaultId, vkId, gen.number, gen.createdMs)
    }

    override suspend fun newKey(): Pair<String, ByteArray> {
        beforeCall?.invoke("newKey")
        return synchronized(this) {
            enter("newKey")
            random.uuid() to random.bytes(KEY_SIZE)
        }
    }

    override suspend fun changePassword(first: ByteArray, again: ByteArray, vkId: String, vk: ByteArray): VaultWrite {
        beforeCall?.invoke("changePassword")
        return synchronized(this) {
            enter("changePassword $vkId")
            val open = session ?: return@synchronized VaultWrite.NothingChanged
            if (noMemory) throw KdfUnavailable(null)
            boundary("changePassword: before")
            val fault = faults.removeFirstOrNull()
            if (fault == WriteFault.NOTHING_CHANGED || fault == WriteFault.REFUSED) return@synchronized VaultWrite.NothingChanged
            val vault = open.vault
            val gen = vault.addLocked(vkId, Suffix.TMP)
            written("vault-${gen.number}.tmp staged")
            boundary("changePassword: staged")
            if (fault == WriteFault.QUARANTINED || !first.contentEquals(again)) {
                gen.suffix = Suffix.BAD
                written("vault-${gen.number}.tmp is .bad: it did not prove")
                boundary("changePassword: quarantined")
                return@synchronized VaultWrite.Quarantined
            }
            boundary("changePassword: proven")
            vault.putKeyLocked(vkId, vk, first)
            gen.suffix = Suffix.LIVE
            written("vault-${gen.number}.tmp is .pvault")
            open.generation = gen.number
            open.vkId = vkId
            if (fault == WriteFault.UNCLEAR) {
                boundary("changePassword: published, not durable")
                return@synchronized VaultWrite.Unclear
            }
            boundary("changePassword: published")
            VaultWrite.Done(vault.vaultId, vkId, gen.number, gen.createdMs)
        }
    }

    override suspend fun mergeAside(vaultId: String): VaultWrite {
        beforeCall?.invoke("mergeAside")
        return synchronized(this) {
            enter("mergeAside $vaultId")
            val open = session ?: return@synchronized VaultWrite.NothingChanged
            val held = asideSession?.takeIf { it.vault.vaultId == vaultId } ?: return@synchronized VaultWrite.NothingChanged
            boundary("mergeAside: before")
            if (faults.removeFirstOrNull() != null) return@synchronized VaultWrite.NothingChanged
            val known = open.vault.content.entries.map { it.id }.toSet()
            val added = held.vault.content.entries.filter { it.id !in known }
            open.vault.content = open.vault.content.copy(entries = open.vault.content.entries + added)
            val gen = open.vault.addLocked(open.vkId, Suffix.LIVE) // one ordinary save
            written("vault-${gen.number}.pvault saved: the accounts of $vaultId merged")
            open.generation = gen.number
            asideSession = null
            boundary("mergeAside: after")
            VaultWrite.Done(open.vault.vaultId, open.vkId, gen.number, gen.createdMs)
        }
    }

    override suspend fun swapIn(vaultId: String): Boolean {
        beforeCall?.invoke("swapIn")
        return synchronized(this) {
            enter("swapIn $vaultId")
            boundary("swapIn: before")
            val held = asideSession?.takeIf { it.vault.vaultId == vaultId }
            // Only a vault that opened in place in this process (architecture.md §4.11).
            if (held == null || vaultId !in openedAside || faults.removeFirstOrNull() != null) return@synchronized false
            live?.let { before ->
                putAsideLocked(before)
                boundary("swapIn: the current vault put aside") // a kill here leaves no current vault; Welcome lists both
            }
            aside -= held.vault
            held.vault.putAsideAtMs = null
            live = held.vault
            written("aside-…-$vaultId/ is live/")
            session = held
            asideSession = null
            openedAside -= vaultId
            boundary("swapIn: after")
            true
        }
    }

    override suspend fun putAside(): Boolean {
        beforeCall?.invoke("putAside")
        return synchronized(this) {
            enter("putAside")
            boundary("putAside: before")
            val before = live
            if (before == null || faults.removeFirstOrNull() != null) return@synchronized false
            putAsideLocked(before)
            session = null
            boundary("putAside: after")
            true
        }
    }

    override suspend fun putNewerAside(generation: Long): Boolean {
        beforeCall?.invoke("putNewerAside")
        return synchronized(this) {
            enter("putNewerAside $generation")
            boundary("putNewerAside: before")
            val vault = live
            if (vault == null || faults.removeFirstOrNull() != null) return@synchronized false
            val files = vault.liveLocked()
            if (files.none { it.number <= generation }) return@synchronized false // never every generation
            for (gen in files.filter { it.number > generation }) { // highest first; it can be repeated after an interruption
                gen.suffix = Suffix.ASIDE
                written("vault-${gen.number}.pvault is .aside")
                boundary("putNewerAside: vault-${gen.number} put aside")
            }
            boundary("putNewerAside: after")
            true
        }
    }

    override suspend fun deleteAside(vaultId: String): Boolean {
        beforeCall?.invoke("deleteAside")
        return synchronized(this) {
            enter("deleteAside $vaultId")
            boundary("deleteAside: before")
            if (faults.removeFirstOrNull() != null) return@synchronized false
            val gone = aside.filter { it.vaultId == vaultId }
            aside.removeAll(gone)
            gone.forEach { _ -> written("aside-…-$vaultId/ deleted") }
            if (asideSession?.vault?.vaultId == vaultId) asideSession = null
            openedAside -= vaultId
            boundary("deleteAside: after")
            true
        }
    }

    override suspend fun retireOldKeys(): Set<String> {
        beforeCall?.invoke("retireOldKeys")
        return synchronized(this) {
            enter("retireOldKeys")
            boundary("retireOldKeys: before")
            val open = session ?: throw IllegalStateException("no vault is open")
            val vault = open.vault
            val files = vault.liveLocked()
            val newest = files.firstOrNull()
            // Only once the current key's master password, typed, has opened from disk a copy that an
            // earlier process wrote, and only while the newest live generation is OK under that key.
            val proven = vault.vaultId + open.vkId in passwordOpened
            if (proven && newest != null && newest.state == FileState.OK && newest.vkId == open.vkId) {
                for (gen in files) {
                    if (gen.state == FileState.OK && gen.vkId != open.vkId && gen.number < open.generation) {
                        vault.allLocked().remove(gen)
                        written("vault-${gen.number}.pvault deleted: retired with its key")
                    }
                }
            }
            val kept = vault.liveLocked().filter { it.state == FileState.OK }.mapTo(LinkedHashSet()) { it.vkId }
            kept += open.vkId
            boundary("retireOldKeys: after")
            kept
        }
    }

    // ---------------------------------------------------------------------------------------------

    /** architecture.md §4.8 step 4 for one directory. */
    private fun currentOfLocked(vault: FakeVault?): CurrentVault {
        val files = vault?.liveLocked().orEmpty()
        if (vault == null || vault.allLocked().isEmpty()) return CurrentVault.NoVault
        if (files.any { it.state == FileState.NEWER }) return CurrentVault.Unreadable(CurrentVault.Why.NEWER)
        val newest = files.firstOrNull { it.state == FileState.OK }
        val unreadable = files.firstOrNull { it.state == FileState.UNREADABLE }
        if (unreadable != null && (newest == null || unreadable.number > newest.number)) return CurrentVault.Unreadable(CurrentVault.Why.IO)
        if (newest == null) return CurrentVault.Unreadable(CurrentVault.Why.DAMAGED)
        val kept = if (unreadable != null) null else files.filter { it.state == FileState.OK }.mapTo(LinkedHashSet()) { it.vkId }
        val olderCopyFirst = files.any { it.state == FileState.DAMAGED && it.number > newest.number }
        return CurrentVault.Present(vault.vaultId, GenerationInfo(newest.number, newest.vkId, newest.createdMs), kept, olderCopyFirst)
    }

    private fun dirOfLocked(target: VaultRef): FakeVault? = when (target) {
        VaultRef.Current -> live
        is VaultRef.Aside -> asideOfLocked(target.vaultId)
    }

    /** The newest directory put aside with [vaultId]. */
    private fun asideOfLocked(vaultId: String): FakeVault? = aside.filter { it.vaultId == vaultId }.maxByOrNull { it.putAsideAtMs ?: 0L }

    /** `live/` becomes `aside-<ms>-<vault_id>/`: one rename, with the wall clock now, moved on while the name is taken. */
    private fun putAsideLocked(vault: FakeVault) {
        var at = clock.wallMs()
        while (aside.any { it.vaultId == vault.vaultId && it.putAsideAtMs == at }) at++
        vault.putAsideAtMs = at
        aside += vault
        if (live === vault) live = null
        written("live/ is aside-$at-${vault.vaultId}/")
    }

    private fun nameOf(target: VaultRef): String = when (target) {
        VaultRef.Current -> "current"
        is VaultRef.Aside -> "aside " + target.vaultId
    }

    private fun written(what: String) {
        writeLog += what
    }

    /** The start of every call: a dead process throws; then the call is listed. */
    private fun enter(call: String) {
        dead?.let { throw Stopped(it.boundary, "after the stop: $call") }
        if (!processAlive()) throw Stopped(passed, "the process is dead: $call")
        callLog += call
    }

    private fun boundary(at: String) {
        passed++
        onBoundary?.invoke(passed, at)
        if (passed == stopAtBoundary) {
            stopAtBoundary = 0
            val stop = Stopped(passed, at)
            dead = stop
            throw stop
        }
    }

    companion object {
        /** The calls of [VaultPort] that ask storage to write (lock.md I1): only F1, F8, F11, F13, F16b and F17 make them. */
        val WRITING_CALLS: Set<String> = setOf(
            "createVault", "replaceVault", "changePassword", "mergeAside", "swapIn", "putAside", "putNewerAside", "deleteAside",
        )

        /** [Generation.writtenByProcess] of a generation that a test put on the phone: an earlier process wrote it. */
        const val EARLIER: Int = -1

        private const val KEY_SIZE = 32

        private val ASIDE_ORDER = compareByDescending<FakeVault> { it.putAsideAtMs ?: 0L }.thenBy { it.vaultId }
    }
}

/**
 * A [MasterKdf] for the lock's tests: SHA-256 of the salt and the password bytes in place of
 * Argon2id, which takes 64 MiB and most of a second for every derivation. It is not a key
 * derivation; it only gives each password and salt its own 32 bytes, the same every time.
 * [unavailable] makes it throw `KdfUnavailable`, as Argon2id does when memory runs out;
 * [derivations] counts the calls.
 */
class FakeKdf : MasterKdf {
    @Volatile var unavailable: Boolean = false

    @Volatile var derivations: Int = 0
        private set

    override suspend fun kek(passwordBytes: ByteArray, salt: ByteArray): ByteArray {
        if (unavailable) throw KdfUnavailable(null)
        synchronized(this) { derivations++ }
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt)
        digest.update(passwordBytes)
        return digest.digest()
    }
}
