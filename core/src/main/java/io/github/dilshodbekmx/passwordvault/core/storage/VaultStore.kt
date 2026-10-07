package io.github.dilshodbekmx.passwordvault.core.storage

import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.core.util.Bytes
import io.github.dilshodbekmx.passwordvault.core.util.Clock
import io.github.dilshodbekmx.passwordvault.core.util.TextHygiene
import io.github.dilshodbekmx.passwordvault.core.util.Uuid
import io.github.dilshodbekmx.passwordvault.core.vaultfile.BodyResult
import io.github.dilshodbekmx.passwordvault.core.vaultfile.BuildInfo
import io.github.dilshodbekmx.passwordvault.core.vaultfile.FileCheck
import io.github.dilshodbekmx.passwordvault.core.vaultfile.PasswordSlot
import io.github.dilshodbekmx.passwordvault.core.vaultfile.VaultCodec
import io.github.dilshodbekmx.passwordvault.core.vaultfile.VaultFile
import io.github.dilshodbekmx.passwordvault.core.vaultfile.VaultHeader
import java.io.IOException
import java.util.IdentityHashMap

/**
 * The generation store of architecture.md §4.7 to §4.12 over [FilePrimitives] and [VaultCodec].
 * `VaultStores.create` makes it; nothing else names this class.
 *
 * **Directories** (§4.7): `live/` holds the vault, `aside-<ms>-<vault_id>/` a vault put aside,
 * `new-<vault_id>/` a vault being created. Every save is a new file `vault-N.pvault`, numbered above
 * every name on disk [V-S1], written as `.tmp`, read back, proven when it carries a new slot, and only
 * then renamed into place and the directory synced (§4.9). A rename never replaces a file: the target
 * is checked first, under this store's lock and the caller's vault mutex.
 *
 * **What it remembers in this process** (§5.1): for every vault, `L` (the generation opened at its
 * first unlock), `W` (the generations written) and the generations opened from disk, for
 * housekeeping (§4.10) and the retirement of old keys (§4.11); which vaults put aside were opened in
 * place; and what it learned of each generation file, so that a save does not read every file again.
 * A file that could not be read is read again whenever a walk reaches it, and [scan] reads every
 * file again.
 *
 * **Failures.** A [FilePrimitives] call that throws `IOException` ends the step it was part of with
 * the result the contract gives for it; nothing is logged. A codec that throws counts as a failed
 * check. Nothing else is caught: in particular no `Error`, so a test's simulated kill stops it
 * wherever it happens.
 *
 * Callers hold the vault mutex (§5.2); every public method also holds this store's own lock, so a
 * call never interleaves with another.
 */
internal class VaultStore(
    private val files: FilePrimitives,
    private val codec: VaultCodec,
    private val clock: Clock,
    vaultsDir: String,
    private val build: BuildInfo,
) : VaultStorage {
    private val vaultsDir: String = vaultsDir.trimEnd('/')
    private val liveDir: String = Names.child(this.vaultsDir, Names.LIVE)

    init {
        require(this.vaultsDir.startsWith("/") && this.vaultsDir.length > 1) { "vaultsDir must be an absolute path" }
    }

    private val lock = Any()
    private var started = false
    private val known = HashMap<String, Known>()
    private val memory = HashMap<String, VaultMemory>()
    private val stagedWork = IdentityHashMap<Staged, StagedWork>()
    private val trigger = Housekeeping.Trigger()
    private val openedAside = HashSet<String>()

    // ---------------------------------------------------------------------------------------------
    // Reading (§4.8)

    override fun scan(): Scan = synchronized(lock) {
        start()
        val current = try {
            currentOf(survey(liveDir, null, fresh = true))
        } catch (e: IOException) {
            Current.Unreadable(Current.Why.IO)
        }
        Scan(current, asideVaults())
    }

    override fun slots(target: VaultRef): List<SlotRef> = synchronized(lock) {
        start()
        val dir = dirOf(target) ?: return emptyList()
        val survey = try {
            survey(dir, expectedVaultId(target), fresh = false)
        } catch (e: IOException) {
            null
        } ?: return emptyList()
        val distinct = LinkedHashMap<PasswordSlot, SlotRef>()
        for (gen in survey.gens) {
            if (gen.state != FileState.OK) continue
            val header = gen.header ?: continue
            if (header.slot !in distinct) distinct[header.slot] = SlotRef(header.slot.salt.copyOf(), header.vkId, gen.number, header.slot)
        }
        distinct.values.toList()
    }

    override fun openWithSlot(target: VaultRef, slot: SlotRef, kek: ByteArray): PasswordResult =
        when (val outcome = slotOutcome(target, slot, kek)) {
            is SlotOutcome.Opened -> PasswordResult.Opened(outcome.opened)
            is SlotOutcome.OlderPassword -> PasswordResult.OlderPassword(outcome.changedAtMs)
            SlotOutcome.SlotClosed -> PasswordResult.SlotClosed
            // The slot opened, so the password is right, and still no generation could be used. The contract of
            // architecture.md §3.3 has no case for this yet (an interface request asks for Damaged, Newer and
            // Unreadable, as OpenResult has them); until then the store reports what it can.
            SlotOutcome.Damaged, SlotOutcome.Newer, SlotOutcome.Unreadable -> PasswordResult.SlotClosed
        }

    /**
     * §4.8 "Opening with the master password", for one slot. [SlotOutcome.Damaged],
     * [SlotOutcome.Newer] and [SlotOutcome.Unreadable] mean that the slot opened with [kek] and
     * still no generation could be used: every one under its `vk_id` failed, a newer app wrote one,
     * or a file could not be read before one opened (never past it).
     */
    internal fun slotOutcome(target: VaultRef, slot: SlotRef, kek: ByteArray): SlotOutcome = synchronized(lock) {
        start()
        val vk = try {
            codec.unwrap(slot.slot, kek)
        } catch (e: RuntimeException) {
            null
        } ?: return SlotOutcome.SlotClosed
        val outcome = walkWithSlot(target, slot.vkId, vk)
        if (outcome !is SlotOutcome.Opened) Bytes.wipe(vk)
        outcome
    }

    private fun walkWithSlot(target: VaultRef, vkId: String, vk: ByteArray): SlotOutcome {
        val dir = dirOf(target) ?: return SlotOutcome.Damaged
        val survey = try {
            survey(dir, expectedVaultId(target), fresh = false)
        } catch (e: IOException) {
            return SlotOutcome.Unreadable
        } ?: return SlotOutcome.Damaged
        val newestOnDisk = survey.gens.firstOrNull()?.number ?: return SlotOutcome.Damaged
        var reference = survey.vaultId
        var newerKey: String? = null // the vk_id of the highest OK generation with a good trailer under another key
        for (gen in survey.gens) {
            val read = readNow(gen.path, gen.number)
            val look = read.known.look
            when (look) {
                Look.Unreadable -> return SlotOutcome.Unreadable
                Look.Newer -> return SlotOutcome.Newer
                Look.Damaged -> continue
                is Look.Ok -> {
                    val header = look.header
                    if (reference == null) reference = header.vaultId
                    if (header.vaultId != reference) continue
                    if (header.vkId != vkId) {
                        if (look.trailerOk && newerKey == null && read.known.body.isUsable()) newerKey = header.vkId
                        continue
                    }
                    when (val body = openBody(gen.path, read.file!!, vk)) {
                        is BodyResult.Opened -> {
                            if (newerKey != null) return SlotOutcome.OlderPassword(changedAtMs(survey, newerKey, header.vaultId))
                            val opened = OpenedVault(body.vault, header.vaultId, gen.number, header.vkId, newestOnDisk, vk, header.slot)
                            noteOpened(target, opened, byPassword = true)
                            return SlotOutcome.Opened(opened)
                        }
                        is BodyResult.Newer -> return SlotOutcome.Newer
                        else -> continue
                    }
                }
            }
        }
        return SlotOutcome.Damaged
    }

    /** The `created` time of the lowest-numbered OK generation with [vkId] (lock.md F16b: "You changed your master password on …"). */
    private fun changedAtMs(survey: Survey, vkId: String, vaultId: String): Long {
        var created = 0L
        for (gen in survey.gens) { // highest first: the last match is the lowest
            val known = known[gen.path] ?: continue
            val look = known.look as? Look.Ok ?: continue
            if (look.header.vkId == vkId && look.header.vaultId == vaultId && known.body.isUsable()) created = look.header.createdMs
        }
        return created
    }

    override fun open(target: VaultRef, keys: Map<String, ByteArray>): OpenResult = synchronized(lock) {
        start()
        val dir = dirOf(target) ?: return OpenResult.Damaged
        val survey = try {
            survey(dir, expectedVaultId(target), fresh = false)
        } catch (e: IOException) {
            return OpenResult.Unreadable
        } ?: return OpenResult.Damaged
        val newestOnDisk = survey.gens.firstOrNull()?.number ?: return OpenResult.Damaged
        var reference = survey.vaultId
        for (gen in survey.gens) {
            val read = readNow(gen.path, gen.number)
            when (val look = read.known.look) {
                Look.Unreadable -> return OpenResult.Unreadable
                Look.Newer -> return OpenResult.Newer
                Look.Damaged -> continue
                is Look.Ok -> {
                    val header = look.header
                    if (reference == null) reference = header.vaultId
                    if (header.vaultId != reference) continue
                    val key = keys[header.vkId] ?: return OpenResult.NeedsOtherKey(gen.number, header.createdMs)
                    when (val body = openBody(gen.path, read.file!!, key)) {
                        is BodyResult.Opened -> {
                            val opened = OpenedVault(body.vault, header.vaultId, gen.number, header.vkId, newestOnDisk, key.copyOf(), header.slot)
                            noteOpened(target, opened, byPassword = false)
                            return OpenResult.Opened(opened)
                        }
                        is BodyResult.Newer -> return OpenResult.Newer
                        else -> continue
                    }
                }
            }
        }
        OpenResult.Damaged
    }

    override fun <T> useKey(opened: OpenedVault, use: (vk: ByteArray) -> T): T = synchronized(lock) { use(opened.vk) }

    // ---------------------------------------------------------------------------------------------
    // Writing (§4.9)

    override fun commit(opened: OpenedVault, vault: Vault): CommitResult = synchronized(lock) {
        start()
        val survey = try {
            survey(liveDir, null, fresh = false)
        } catch (e: IOException) {
            return CommitResult.Failed
        } ?: return CommitResult.Failed
        // Step 1: no live generation is NEWER, and the opened vault is the current one.
        if (survey.gens.any { it.state == FileState.NEWER }) return CommitResult.ReadOnly
        if (survey.vaultId != opened.vaultId) return CommitResult.Failed
        // Step 2.
        val number = nextNumber(survey.names) ?: return CommitResult.Failed
        // Steps 3 to 6.
        val written = when (val w = writeGeneration(liveDir, number, vault, opened.vk, opened.vaultId, opened.vkId, opened.slot)) {
            Written.Failed -> return CommitResult.Failed
            Written.TooLarge -> return CommitResult.TooLarge
            is Written.Ok -> w
        }
        // Steps 8 and 9.
        when (val placed = place(liveDir, number, written.tmp, written.header)) {
            Placed.Failed -> CommitResult.Failed
            is Placed.Done -> {
                trigger.saved(placed.durable)
                if (!placed.durable) {
                    CommitResult.NotDurable
                } else {
                    CommitResult.Saved(OpenedVault(vault, opened.vaultId, number, opened.vkId, number, opened.vk, opened.slot))
                }
            }
        }
    }

    override fun stageVault(vault: Vault, vaultId: String, vk: ByteArray, vkId: String, slot: PasswordSlot): StageResult =
        synchronized(lock) {
            start()
            if (!Names.isVaultId(vaultId)) return StageResult.Failed // it becomes a directory name
            val dir = Names.child(vaultsDir, Names.newDir(vaultId))
            try {
                ensureVaultsDir()
                if (files.exists(dir)) {
                    stagedWork.entries.removeAll { it.value.dir == dir }
                    files.deleteTree(dir)
                }
                files.mkdir(dir)
            } catch (e: IOException) {
                return StageResult.Failed
            }
            when (val written = writeGeneration(dir, 1L, vault, vk, vaultId, vkId, slot)) {
                Written.Failed -> {
                    deleteTreeQuietly(dir)
                    StageResult.Failed
                }
                Written.TooLarge -> {
                    deleteTreeQuietly(dir)
                    StageResult.TooLarge
                }
                is Written.Ok -> stage(Kind.NEW_VAULT, dir, written, vault, vk, slot)
            }
        }

    override fun stageRekey(opened: OpenedVault, vault: Vault, vk: ByteArray, vkId: String, slot: PasswordSlot): StageResult =
        synchronized(lock) {
            start()
            if (vkId == opened.vkId) return StageResult.Failed // a new slot always comes with a new key (§4.6)
            val survey = try {
                survey(liveDir, null, fresh = false)
            } catch (e: IOException) {
                return StageResult.Failed
            } ?: return StageResult.Failed
            if (survey.gens.any { it.state == FileState.NEWER } || survey.vaultId != opened.vaultId) return StageResult.Failed
            val number = nextNumber(survey.names) ?: return StageResult.Failed
            when (val written = writeGeneration(liveDir, number, vault, vk, opened.vaultId, vkId, slot)) {
                Written.Failed -> StageResult.Failed
                Written.TooLarge -> StageResult.TooLarge
                is Written.Ok -> stage(Kind.REKEY, liveDir, written, vault, vk, slot)
            }
        }

    private fun stage(kind: Kind, dir: String, written: Written.Ok, vault: Vault, vk: ByteArray, slot: PasswordSlot): StageResult {
        val header = written.header
        val staged = Staged(header.vaultId, header.vkId, header.generation, header.slot.salt.copyOf(), written.tmp)
        stagedWork[staged] = StagedWork(kind, dir, vault, vk.copyOf(), slot, header)
        return StageResult.Staged(staged)
    }

    override fun prove(staged: Staged, kek: ByteArray): Boolean = synchronized(lock) {
        start()
        val work = stagedWork[staged] ?: return false
        when (work.step) {
            Step.PROVEN -> return true
            Step.STAGED -> Unit
            Step.REFUSED, Step.DONE -> return false
        }
        if (proof(staged, work, kek)) {
            work.step = Step.PROVEN
            return true
        }
        // §4.9 step 7: a staged file that does not prove is quarantined, never deleted, and nothing live changed.
        work.step = Step.REFUSED
        val bad = Names.withSuffix(staged.path, Names.TMP, Names.BAD)
        try {
            if (files.exists(staged.path) && !files.exists(bad)) files.rename(staged.path, bad)
            files.syncDir(work.dir)
        } catch (e: IOException) {
            // The .tmp is not live; the next start deletes it.
        }
        false
    }

    /** §4.9 step 7 on the bytes as they are on disk now: the slot opens with [kek] and the body with the VK it gives. */
    private fun proof(staged: Staged, work: StagedWork, kek: ByteArray): Boolean {
        val bytes = try {
            files.read(staged.path, CAP)
        } catch (e: IOException) {
            return false
        }
        if (bytes.size > CAP) return false
        val file = (checkOf(bytes) as? FileCheck.Ok)?.file ?: return false
        val header = file.header
        if (header.vaultId != staged.vaultId || header.vkId != staged.vkId || header.generation != staged.generation ||
            !header.slot.salt.contentEquals(staged.slotSalt)
        ) {
            return false
        }
        val vk = try {
            codec.unwrap(header.slot, kek)
        } catch (e: RuntimeException) {
            null
        } ?: return false
        try {
            val body = try {
                codec.open(file, vk)
            } catch (e: RuntimeException) {
                return false
            }
            return body is BodyResult.Opened && body.vault == work.vault
        } finally {
            Bytes.wipe(vk)
        }
    }

    override fun publish(staged: Staged, putCurrentAside: Boolean): CommitResult = synchronized(lock) {
        start()
        val work = stagedWork[staged] ?: return CommitResult.Failed
        if (work.step != Step.PROVEN) return CommitResult.Failed // a live generation never carries an unproven slot
        when (work.kind) {
            Kind.REKEY -> publishRekey(staged, work, putCurrentAside)
            Kind.NEW_VAULT -> publishNewVault(staged, work, putCurrentAside)
        }
    }

    private fun publishRekey(staged: Staged, work: StagedWork, putCurrentAside: Boolean): CommitResult {
        if (putCurrentAside) return CommitResult.Failed // a new key goes into the current vault, never beside it
        val survey = try {
            survey(liveDir, null, fresh = false)
        } catch (e: IOException) {
            return CommitResult.Failed
        } ?: return CommitResult.Failed
        val tmpName = Names.nameOf(staged.path)
        val writtenSince = survey.names.any { it != tmpName && (Names.numberOf(it) ?: -1L) >= staged.generation }
        if (survey.gens.any { it.state == FileState.NEWER } || survey.vaultId != staged.vaultId || writtenSince) {
            deleteQuietly(staged.path)
            finish(staged, work, handedOut = false)
            return CommitResult.Failed
        }
        return when (val placed = place(liveDir, staged.generation, staged.path, work.header)) {
            Placed.Failed -> {
                finish(staged, work, handedOut = false)
                CommitResult.Failed
            }
            is Placed.Done -> {
                finish(staged, work, handedOut = placed.durable)
                trigger.saved(placed.durable)
                if (!placed.durable) {
                    CommitResult.NotDurable
                } else {
                    CommitResult.Saved(
                        OpenedVault(work.vault, staged.vaultId, staged.generation, staged.vkId, staged.generation, work.vk, work.slot),
                    )
                }
            }
        }
    }

    /**
     * §4.9 steps 8 and 9 for a new vault: its file is renamed into place inside `new-<vault_id>/`
     * and that directory synced; then, with [putCurrentAside], `live/` becomes
     * `aside-<ms>-<vault_id>/`; then `new-<vault_id>/` becomes `live/`, which must not exist, and
     * `vaults/` is synced. A kill between the two renames leaves no live vault, and the vault put
     * aside is listed by [scan] (lock.md F11 and F17).
     */
    private fun publishNewVault(staged: Staged, work: StagedWork, putCurrentAside: Boolean): CommitResult {
        val dir = work.dir
        val placedInside = place(dir, staged.generation, staged.path, work.header, remember = false)
        if (placedInside !is Placed.Done || !placedInside.durable) {
            // Not live yet: nothing changed.
            deleteTreeQuietly(dir)
            finish(staged, work, handedOut = false)
            return CommitResult.Failed
        }
        var movedAside: String? = null
        try {
            if (files.exists(liveDir)) {
                val names = try {
                    files.list(liveDir)
                } catch (e: IOException) {
                    null // it exists and cannot be listed: it may hold a vault
                }
                if (names != null && names.isEmpty()) {
                    files.deleteTree(liveDir) // an empty live/ is "no vault" (§4.7)
                } else if (!putCurrentAside) {
                    throw IOException("a vault is current") // never replaced
                } else {
                    val aside = Names.child(vaultsDir, freeAsideName(liveVaultIdForName()))
                    files.rename(liveDir, aside)
                    movedAside = aside
                    forget(liveDir)
                }
            }
            files.rename(dir, liveDir)
        } catch (e: IOException) {
            movedAside?.let { aside -> renameQuietly(aside, liveDir) }
            deleteTreeQuietly(dir)
            finish(staged, work, handedOut = false)
            return CommitResult.Failed
        }
        forget(dir)
        forget(liveDir)
        memoryOf(staged.vaultId).apply {
            createdHere = true
            written[staged.generation] = staged.vkId
        }
        known[Names.child(liveDir, Names.generation(staged.generation, Names.PVAULT))] = Known(Look.Ok(work.header, true), Body.OPENED)
        val durable = try {
            files.syncDir(vaultsDir)
            true
        } catch (e: IOException) {
            false
        }
        finish(staged, work, handedOut = durable)
        trigger.saved(durable)
        return if (!durable) {
            CommitResult.NotDurable
        } else {
            CommitResult.Saved(OpenedVault(work.vault, staged.vaultId, staged.generation, staged.vkId, staged.generation, work.vk, work.slot))
        }
    }

    override fun discard(staged: Staged): Unit = synchronized(lock) {
        start()
        val work = stagedWork[staged] ?: return
        if (work.step == Step.DONE) return
        when (work.kind) {
            Kind.REKEY -> deleteQuietly(staged.path) // the .tmp only; the .bad of a refused proof stays
            Kind.NEW_VAULT -> deleteTreeQuietly(work.dir)
        }
        finish(staged, work, handedOut = false)
    }

    private fun finish(staged: Staged, work: StagedWork, handedOut: Boolean) {
        work.step = Step.DONE
        stagedWork.remove(staged)
        if (!handedOut) Bytes.wipe(work.vk)
    }

    /**
     * Steps 3 to 6 of §4.9 into [dir] as generation [number]: the write-time rules of §4.3, the
     * seal, the check in memory against [vault] and [slot], `writeNew` of `vault-N.tmp`, and the
     * read back compared byte for byte. On any failure nothing is left behind.
     */
    private fun writeGeneration(
        dir: String,
        number: Long,
        vault: Vault,
        vk: ByteArray,
        vaultId: String,
        vkId: String,
        slot: PasswordSlot,
    ): Written {
        if (!WriteRules.hold(vault)) return Written.Failed
        val sealed = try {
            codec.seal(vault, vk, vaultId, vkId, number, slot)
        } catch (e: RuntimeException) {
            return Written.Failed
        }
        if (sealed.size > CAP) return Written.TooLarge // §4.5 rule 5
        if (!sameInMemory(sealed, vault, vk, vaultId, vkId, number, slot)) return Written.Failed
        val tmp = Names.child(dir, Names.generation(number, Names.TMP))
        try {
            files.writeNew(tmp, sealed)
        } catch (e: IOException) {
            deleteQuietly(tmp)
            return Written.Failed
        }
        val back = try {
            files.read(tmp, CAP)
        } catch (e: IOException) {
            deleteQuietly(tmp)
            return Written.Failed
        }
        val header = if (back.contentEquals(sealed)) (checkOf(back) as? FileCheck.Ok)?.file?.header else null
        if (header == null) {
            deleteQuietly(tmp)
            return Written.Failed
        }
        return Written.Ok(tmp, header)
    }

    /** §4.9 step 4: the sealed bytes pass `check`, carry what was asked for, and open under [vk] to [vault] itself. */
    private fun sameInMemory(
        sealed: ByteArray,
        vault: Vault,
        vk: ByteArray,
        vaultId: String,
        vkId: String,
        number: Long,
        slot: PasswordSlot,
    ): Boolean {
        val file = (checkOf(sealed) as? FileCheck.Ok)?.file ?: return false
        val header = file.header
        if (!file.trailerOk || header.vaultId != vaultId || header.vkId != vkId || header.generation != number || header.slot != slot) {
            return false
        }
        val body = try {
            codec.open(file, vk)
        } catch (e: RuntimeException) {
            return false
        }
        return body is BodyResult.Opened && body.vault == vault
    }

    /**
     * Steps 8 and 9 of §4.9 for [tmp] in [dir]: refuse when `vault-N.pvault`, `.bad` or `.aside`
     * exists [V-S1]; rename; `syncDir`. A failure before the rename deletes the `.tmp`.
     */
    private fun place(dir: String, number: Long, tmp: String, header: VaultHeader, remember: Boolean = true): Placed {
        val taken = try {
            listOf(Names.PVAULT, Names.BAD, Names.ASIDE).any { files.exists(Names.child(dir, Names.generation(number, it))) }
        } catch (e: IOException) {
            true
        }
        if (taken) {
            deleteQuietly(tmp)
            return Placed.Failed
        }
        val target = Names.child(dir, Names.generation(number, Names.PVAULT))
        try {
            files.rename(tmp, target)
        } catch (e: IOException) {
            deleteQuietly(tmp)
            return Placed.Failed
        }
        if (remember) {
            known[target] = Known(Look.Ok(header, true), Body.OPENED)
            memoryOf(header.vaultId).written[number] = header.vkId
        }
        return try {
            files.syncDir(dir)
            Placed.Done(durable = true)
        } catch (e: IOException) {
            Placed.Done(durable = false)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Housekeeping and old keys (§4.10, §4.11)

    override fun housekeeping(opened: OpenedVault): Unit = synchronized(lock) {
        start()
        if (!trigger.due) return
        if (runHousekeeping(opened)) trigger.ran()
    }

    /** §4.10 on the current vault. False: it could not finish, and stays due. */
    private fun runHousekeeping(opened: OpenedVault): Boolean {
        val survey = try {
            survey(liveDir, null, fresh = false)
        } catch (e: IOException) {
            return false
        } ?: return true
        if (survey.vaultId != opened.vaultId) return true // only the current vault; nothing put aside is ever pruned
        val memory = memoryOf(opened.vaultId)
        val current = opened.vkId
        val first = memory.first
        val gens = LinkedHashMap<Long, Housekeeping.Gen>()
        for (gen in survey.gens) gens[gen.number] = Housekeeping.Gen(gen.number, gen.state, gen.header?.vkId, gen.header?.writerVersion)
        var changed = false
        try {
            if (first != null) {
                // Step 1: what lies above L, was not written here and did not open goes to quarantine.
                for (gen in survey.gens) {
                    if (gen.number <= first || gen.number in memory.written) continue
                    var state = gen.state
                    if (state == FileState.OK && gen.header?.vkId == current && known[gen.path]?.body == null) {
                        state = verify(gen, survey.vaultId, current, opened.vk, memory)
                    }
                    if (state == FileState.DAMAGED) {
                        if (quarantine(gen.path, gen.number)) {
                            gens.remove(gen.number)
                            changed = true
                        }
                    } else {
                        gens[gen.number] = Housekeeping.Gen(gen.number, state, gen.header?.vkId, gen.header?.writerVersion)
                    }
                }
                // Step 2: verify below L, highest first, until five of the current key are verified.
                val verified = verifiedOf(survey, memory, current)
                for (gen in survey.gens) {
                    if (verified.size >= Housekeeping.VERIFIED) break
                    if (gen.number >= first) continue
                    val state = when {
                        gen.state == FileState.OK && gen.header?.vkId != current -> FileState.OK // not part of the walk
                        gen.state == FileState.OK && gen.number in verified -> FileState.OK
                        gen.state == FileState.DAMAGED -> FileState.DAMAGED
                        else -> verify(gen, survey.vaultId, current, opened.vk, memory) // read again: it may read fine now
                    }
                    val header = (known[gen.path]?.look as? Look.Ok)?.header ?: gen.header // as read just now
                    gens[gen.number] = Housekeeping.Gen(gen.number, state, header?.vkId, header?.writerVersion)
                    when (state) {
                        FileState.OK -> if (gen.number in memory.opened && gen.number !in memory.written) verified += gen.number
                        FileState.DAMAGED -> if (quarantine(gen.path, gen.number)) {
                            gens.remove(gen.number)
                            changed = true
                        }
                        FileState.UNREADABLE, FileState.NEWER -> break // nothing below it is pruned
                    }
                }
            }
            // Steps 3 and 4.
            val doomed = Housekeeping.deletions(
                gens.values.toList(),
                current,
                verifiedOf(survey, memory, current).filter { it in gens },
                memory.written.keys,
                build.writerVersion,
            )
            for (number in doomed) {
                val path = Names.child(liveDir, Names.generation(number, Names.PVAULT))
                files.delete(path)
                known.remove(path)
                changed = true
            }
        } catch (e: IOException) {
            if (changed) syncQuietly(liveDir)
            return false
        }
        if (changed) syncQuietly(liveDir)
        return true
    }

    /** The live generations of [current] that this process opened from disk and did not write: verified (§4.10). */
    private fun verifiedOf(survey: Survey, memory: VaultMemory, current: String): MutableSet<Long> =
        survey.gens.filter {
            it.state == FileState.OK && it.header?.vkId == current && it.number in memory.opened && it.number !in memory.written
        }.mapTo(HashSet()) { it.number }

    /**
     * Reads [gen] again and, when it is OK under the [current] key, opens it with [vk]: OK (verified
     * when it opened), DAMAGED, NEWER or UNREADABLE. A generation of another key is only checked.
     */
    private fun verify(gen: Gen, vaultId: String?, current: String, vk: ByteArray, memory: VaultMemory): FileState {
        val read = readNow(gen.path, gen.number)
        return when (val look = read.known.look) {
            Look.Unreadable -> FileState.UNREADABLE
            Look.Newer -> FileState.NEWER
            Look.Damaged -> FileState.DAMAGED
            is Look.Ok -> when {
                vaultId != null && look.header.vaultId != vaultId -> FileState.DAMAGED
                look.header.vkId != current -> FileState.OK
                else -> when (openBody(gen.path, read.file!!, vk)) {
                    is BodyResult.Opened -> {
                        if (gen.number !in memory.written) memory.opened += gen.number
                        FileState.OK
                    }
                    is BodyResult.Newer -> FileState.NEWER
                    else -> FileState.DAMAGED
                }
            }
        }
    }

    /** `vault-N.pvault` → `vault-N.bad`, unless that name is taken (a rename never replaces). */
    private fun quarantine(path: String, number: Long): Boolean {
        val bad = Names.child(Names.parent(path)!!, Names.generation(number, Names.BAD))
        if (files.exists(bad)) return false
        files.rename(path, bad)
        known.remove(path)
        return true
    }

    override fun retireOldKeys(opened: OpenedVault): Set<String> = synchronized(lock) {
        start()
        val survey = try {
            survey(liveDir, null, fresh = false)
        } catch (e: IOException) {
            null
        }
        if (survey == null || survey.vaultId != opened.vaultId) return keptVkIds(survey, opened)
        // Only once the current key's master password, typed, has opened a generation from disk that an
        // earlier process wrote [V-C5, V-S5]; only while the newest live generation is OK under that key;
        // only OK generations of other keys below the one opened. Nothing that failed is deleted.
        val newest = survey.gens.firstOrNull()
        val proven = opened.vkId in memoryOf(opened.vaultId).passwordOpened
        if (!proven || newest == null || newest.state != FileState.OK || newest.header?.vkId != opened.vkId) {
            return keptVkIds(survey, opened)
        }
        var changed = false
        try {
            for (gen in survey.gens) {
                val vkId = gen.header?.vkId ?: continue
                if (gen.state == FileState.OK && vkId != opened.vkId && gen.number < opened.generation) {
                    files.delete(gen.path)
                    known.remove(gen.path)
                    changed = true
                }
            }
        } catch (e: IOException) {
            // What is left is retired at a later call.
        }
        if (changed) syncQuietly(liveDir)
        val after = try {
            survey(liveDir, null, fresh = false)
        } catch (e: IOException) {
            null
        }
        keptVkIds(after, opened)
    }

    /** The `vk_id`s of the OK live generations, and always the opened one's. */
    private fun keptVkIds(survey: Survey?, opened: OpenedVault): Set<String> {
        val kept = LinkedHashSet<String>()
        survey?.gens?.forEach { if (it.state == FileState.OK) it.header?.vkId?.let(kept::add) }
        if (survey == null || survey.vaultId == opened.vaultId) kept += opened.vkId
        return kept
    }

    // ---------------------------------------------------------------------------------------------
    // Vaults put aside (§4.11)

    override fun putAside(): Boolean = synchronized(lock) {
        start()
        try {
            if (!files.exists(liveDir)) return false
            val names = files.list(liveDir)
            if (names != null && names.isEmpty()) return false
            files.rename(liveDir, Names.child(vaultsDir, freeAsideName(liveVaultIdForName())))
        } catch (e: IOException) {
            return false
        }
        forget(liveDir)
        syncQuietly(vaultsDir)
        true
    }

    override fun swapIn(vaultId: String): Boolean = synchronized(lock) {
        start()
        if (vaultId !in openedAside) return false // only a vault that opened in place in this process
        val source = asideDirOf(vaultId) ?: return false
        var movedAside: String? = null
        try {
            if (files.exists(liveDir)) {
                val names = try {
                    files.list(liveDir)
                } catch (e: IOException) {
                    null
                }
                if (names != null && names.isEmpty()) {
                    files.deleteTree(liveDir)
                } else {
                    val aside = Names.child(vaultsDir, freeAsideName(liveVaultIdForName()))
                    files.rename(liveDir, aside)
                    movedAside = aside
                }
            }
            files.rename(source, liveDir)
        } catch (e: IOException) {
            movedAside?.let { aside -> renameQuietly(aside, liveDir) }
            forget(liveDir)
            return false
        }
        forget(liveDir)
        forget(source)
        openedAside.remove(vaultId)
        syncQuietly(vaultsDir)
        true
    }

    override fun putNewerAside(generation: Long): Boolean = synchronized(lock) {
        start()
        try {
            val names = files.list(liveDir) ?: return false
            val live = names.mapNotNull { Names.numberOf(it, Names.PVAULT) }
            if (live.none { it <= generation }) return false // never every generation: the copy that opened stays
            for (number in live.filter { it > generation }.sortedDescending()) {
                val from = Names.child(liveDir, Names.generation(number, Names.PVAULT))
                val to = Names.child(liveDir, Names.generation(number, Names.ASIDE))
                if (files.exists(to)) return false
                files.rename(from, to)
                known.remove(from)
            }
            files.syncDir(liveDir)
        } catch (e: IOException) {
            return false // it can be repeated
        }
        true
    }

    override fun deleteAside(vaultId: String): Unit = synchronized(lock) {
        start()
        val names = files.list(vaultsDir) ?: return
        for (aside in names.mapNotNull { Names.parseAsideDir(it) }.filter { it.vaultId == vaultId }) {
            val dir = Names.child(vaultsDir, aside.name)
            files.deleteTree(dir)
            forget(dir)
        }
        openedAside.remove(vaultId)
        syncQuietly(vaultsDir)
    }

    private fun asideVaults(): List<AsideVault> {
        val names = try {
            files.list(vaultsDir)
        } catch (e: IOException) {
            null
        } ?: return emptyList()
        return names.mapNotNull { Names.parseAsideDir(it) }.map { aside ->
            val survey = try {
                survey(Names.child(vaultsDir, aside.name), aside.vaultId, fresh = true)
            } catch (e: IOException) {
                null
            }
            val newest = (survey?.let { currentOf(it) } as? Current.Present)?.facts?.newest
            AsideVault(aside.vaultId, aside.putAsideAtMs, newest)
        }.sortedWith(compareByDescending<AsideVault> { it.putAsideAtMs }.thenBy { it.vaultId })
    }

    /** The newest directory put aside with [vaultId], or null. */
    private fun asideDirOf(vaultId: String): String? {
        val names = try {
            files.list(vaultsDir)
        } catch (e: IOException) {
            null
        } ?: return null
        return names.mapNotNull { Names.parseAsideDir(it) }
            .filter { it.vaultId == vaultId }
            .maxWithOrNull(compareBy<Names.AsideDir> { it.putAsideAtMs }.thenBy { it.name })
            ?.let { Names.child(vaultsDir, it.name) }
    }

    /**
     * The `vault_id` for the name of `live/` when it goes aside: that of its newest OK generation.
     * When no generation can be read, a stand-in made from the clock, of the same form, so that the
     * directory can still be put aside (a restore over a vault that cannot be opened, lock.md F17).
     */
    private fun liveVaultIdForName(): String {
        val survey = try {
            survey(liveDir, null, fresh = false)
        } catch (e: IOException) {
            null
        }
        survey?.vaultId?.let { return it }
        val now = clock.wallMs()
        val hex = Bytes.hex(ByteArray(6) { i -> (now ushr (8 * (5 - i))).toByte() })
        return "00000000-0000-4000-8000-$hex"
    }

    /** `aside-<ms>-<vaultId>` with the wall clock now, moved on by a millisecond while the name is taken. */
    private fun freeAsideName(vaultId: String): String {
        val now = clock.wallMs()
        for (i in 0 until 1_000) {
            val name = Names.asideDir(now + i, vaultId)
            if (!files.exists(Names.child(vaultsDir, name))) return name
        }
        throw IOException("no free name for a vault put aside")
    }

    // ---------------------------------------------------------------------------------------------
    // The state of the files (§4.8)

    /**
     * §4.8 step 4 for one directory, from its [survey] (null: the directory does not exist).
     * A file that could not be read and has no OK generation above it is reported as such: the
     * store never falls back past it.
     */
    private fun currentOf(survey: Survey?): Current {
        if (survey == null || survey.names.isEmpty()) return Current.NoVault
        if (survey.gens.any { it.state == FileState.NEWER }) return Current.Unreadable(Current.Why.NEWER)
        val newest = survey.gens.firstOrNull { it.state == FileState.OK }
        val unreadable = survey.gens.firstOrNull { it.state == FileState.UNREADABLE }
        if (unreadable != null && (newest == null || unreadable.number > newest.number)) return Current.Unreadable(Current.Why.IO)
        if (newest == null) return Current.Unreadable(Current.Why.DAMAGED)
        val header = newest.header!!
        val keptVkIds = if (unreadable != null) {
            null
        } else {
            survey.gens.filter { it.state == FileState.OK }.mapNotNullTo(LinkedHashSet()) { it.header?.vkId }
        }
        val olderCopyFirst = survey.gens.any { it.state == FileState.DAMAGED && it.number > newest.number }
        return Current.Present(VaultFacts(header.vaultId, GenerationInfo(newest.number, header.vkId, header.createdMs), keptVkIds, olderCopyFirst))
    }

    /**
     * The live generations of [dir], highest first, each in one of the four states of §4.8. With
     * [fresh] every file is read again; otherwise what this process learned of a file stands, and
     * only names it has not seen are read. A generation is OK when `check` passed, its number is
     * the one in its name, its body has not failed under its own key, and its `vault_id` is
     * [expectedVaultId] or, for `live/`, that of the newest OK file with a good trailer (else the
     * newest OK file). Null when [dir] does not exist.
     *
     * @throws IOException when [dir] cannot be listed.
     */
    private fun survey(dir: String, expectedVaultId: String?, fresh: Boolean): Survey? {
        val names = files.list(dir) ?: return null
        val looked = names.mapNotNull { name -> Names.numberOf(name, Names.PVAULT)?.let { number -> number to Names.child(dir, name) } }
            .sortedByDescending { it.first }
            .map { (number, path) ->
                val cached = known[path]
                Triple(number, path, if (fresh || cached == null) readNow(path, number).known else cached)
            }
        val reference = expectedVaultId
            ?: looked.firstOrNull { (_, _, k) -> k.usableOk()?.trailerOk == true }?.third?.usableOk()?.header?.vaultId
            ?: looked.firstOrNull { (_, _, k) -> k.usableOk() != null }?.third?.usableOk()?.header?.vaultId
        val gens = looked.map { (number, path, k) ->
            val ok = k.look as? Look.Ok
            val state = when (val look = k.look) {
                Look.Unreadable -> FileState.UNREADABLE
                Look.Damaged -> FileState.DAMAGED
                Look.Newer -> FileState.NEWER
                is Look.Ok -> when {
                    k.body == Body.DAMAGED -> FileState.DAMAGED
                    k.body == Body.NEWER -> FileState.NEWER
                    reference != null && look.header.vaultId != reference -> FileState.DAMAGED
                    else -> FileState.OK
                }
            }
            Gen(number, path, state, ok?.header, ok?.trailerOk ?: false)
        }
        return Survey(names, gens, reference)
    }

    /** Reads [path] now and runs `check` (§4.4 R1 to R7): what this process now knows of it, and the file when it is OK. */
    private fun readNow(path: String, number: Long): Read {
        val bytes = try {
            files.read(path, CAP)
        } catch (e: IOException) {
            return Read(remember(path, Look.Unreadable), null)
        }
        if (bytes.size > CAP) return Read(remember(path, Look.Damaged), null) // over the cap: DAMAGED in a vault directory
        return when (val check = checkOf(bytes)) {
            is FileCheck.Ok -> if (check.file.header.generation == number) {
                Read(remember(path, Look.Ok(check.file.header, check.file.trailerOk)), check.file)
            } else {
                Read(remember(path, Look.Damaged), null)
            }
            is FileCheck.Newer -> Read(remember(path, Look.Newer), null)
            else -> Read(remember(path, Look.Damaged), null) // NOT OURS counts as DAMAGED here (§4.8)
        }
    }

    private fun checkOf(bytes: ByteArray): FileCheck = try {
        codec.check(bytes)
    } catch (e: RuntimeException) {
        FileCheck.Damaged(0)
    }

    private fun remember(path: String, look: Look): Known {
        val old = known[path]
        val kept = if (old != null && old.look.sameAs(look)) old.body else null
        return Known(look, kept).also { known[path] = it }
    }

    /** R8 to R10 under [vk]; what it gives is remembered for the file. */
    private fun openBody(path: String, file: VaultFile, vk: ByteArray): BodyResult {
        val result = try {
            codec.open(file, vk)
        } catch (e: RuntimeException) {
            BodyResult.TagFailed
        }
        known[path]?.body = when (result) {
            is BodyResult.Opened -> Body.OPENED
            is BodyResult.Newer -> Body.NEWER
            else -> Body.DAMAGED
        }
        return result
    }

    private fun noteOpened(target: VaultRef, opened: OpenedVault, byPassword: Boolean) {
        val memory = memoryOf(opened.vaultId)
        if (opened.generation !in memory.written) {
            memory.opened += opened.generation
            if (memory.first == null && !memory.createdHere) memory.first = opened.generation
            if (byPassword) memory.passwordOpened += opened.vkId
        }
        if (target is VaultRef.Aside) openedAside += opened.vaultId
    }

    private fun memoryOf(vaultId: String): VaultMemory = memory.getOrPut(vaultId) { VaultMemory() }

    private fun dirOf(target: VaultRef): String? = when (target) {
        VaultRef.Current -> liveDir
        is VaultRef.Aside -> asideDirOf(target.vaultId)
    }

    private fun expectedVaultId(target: VaultRef): String? = (target as? VaultRef.Aside)?.vaultId

    /** §4.9 step 2: 1 + the highest number among all names that start with `vault-` and ten digits [V-S1]. */
    private fun nextNumber(names: List<String>): Long? {
        val highest = names.mapNotNull { Names.numberOf(it) }.maxOrNull() ?: 0L
        return if (highest < Names.MAX_GENERATION) highest + 1 else null
    }

    private fun ensureVaultsDir() {
        if (files.exists(vaultsDir)) return
        files.mkdir(vaultsDir)
        Names.parent(vaultsDir)?.let { files.syncDir(it) }
    }

    /**
     * §4.8 step 1, once per process before anything else: the `.tmp` files of `live/` and every
     * `new-<vault_id>/` directory are deleted, because nothing in them was published. Best effort:
     * what stays is deleted at the next start, and neither is ever live.
     */
    private fun start() {
        if (started) return
        started = true
        try {
            files.list(liveDir)?.filter { Names.numberOf(it, Names.TMP) != null }?.forEach { deleteQuietly(Names.child(liveDir, it)) }
        } catch (e: IOException) {
            // Left for the next start.
        }
        try {
            files.list(vaultsDir)?.filter { Names.isNewDir(it) }?.forEach { deleteTreeQuietly(Names.child(vaultsDir, it)) }
        } catch (e: IOException) {
            // Left for the next start.
        }
    }

    /** Drops what this process learned of the files in [dir], after [dir] was renamed or deleted. */
    private fun forget(dir: String) {
        val prefix = "$dir/"
        known.keys.removeAll { it.startsWith(prefix) }
    }

    private fun deleteQuietly(path: String) {
        try {
            if (files.exists(path)) files.delete(path)
        } catch (e: IOException) {
            // A .tmp that stays is deleted at the next start.
        }
    }

    private fun deleteTreeQuietly(dir: String) {
        try {
            if (files.exists(dir)) files.deleteTree(dir)
        } catch (e: IOException) {
            // A new-* directory that stays is deleted at the next start.
        }
        forget(dir)
    }

    private fun renameQuietly(from: String, to: String) {
        try {
            if (!files.exists(to)) files.rename(from, to)
        } catch (e: IOException) {
            // Then no vault is current, and scan lists the one put aside (lock.md F17).
        }
    }

    private fun syncQuietly(dir: String) {
        try {
            files.syncDir(dir)
        } catch (e: IOException) {
            // Only deletions and quarantine renames wait for it; neither loses anything if it is undone.
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Types

    /** What reading one generation file and running `check` on it gave, without a key. */
    private sealed interface Look {
        data object Unreadable : Look

        data object Damaged : Look

        data object Newer : Look

        class Ok(val header: VaultHeader, val trailerOk: Boolean) : Look

        fun sameAs(other: Look): Boolean =
            if (this is Ok) other is Ok && header == other.header && trailerOk == other.trailerOk else this === other
    }

    /** What opening a body under the key of its `vk_id` gave (R8 to R10). */
    private enum class Body { OPENED, DAMAGED, NEWER }

    /** What this process knows of one file. */
    private class Known(val look: Look, var body: Body?) {
        /** The check result when the file is OK as far as its own body is concerned. */
        fun usableOk(): Look.Ok? = (look as? Look.Ok)?.takeIf { body.isUsable() }
    }

    private class Read(val known: Known, val file: VaultFile?)

    /** One live generation in one of the four states of §4.8; [header] when `check` passed. */
    private class Gen(val number: Long, val path: String, val state: FileState, val header: VaultHeader?, val trailerOk: Boolean)

    /** Every name of one vault directory and its live generations, highest first; [vaultId] is the reference id. */
    private class Survey(val names: List<String>, val gens: List<Gen>, val vaultId: String?)

    /** What this process remembers of one vault (§4.10, §4.11), by `vault_id`, whatever directory it is in. */
    private class VaultMemory {
        /** Created, restored or made by a replacement in this process: it has no `L`. */
        var createdHere = false

        /** `L`: the generation opened from disk at the first unlock in this process. */
        var first: Long? = null

        /** Generations opened from disk in this process; with [written] taken out, the verified ones. */
        val opened = HashSet<Long>()

        /** `W`: the generations this process wrote, with each one's `vk_id`. */
        val written = HashMap<Long, String>()

        /** The `vk_id`s whose password slot opened, from disk, a generation an earlier process wrote. */
        val passwordOpened = HashSet<String>()
    }

    private enum class Kind { NEW_VAULT, REKEY }

    private enum class Step { STAGED, PROVEN, REFUSED, DONE }

    private class StagedWork(
        val kind: Kind,
        val dir: String,
        val vault: Vault,
        val vk: ByteArray,
        val slot: PasswordSlot,
        val header: VaultHeader,
    ) {
        var step = Step.STAGED
    }

    private sealed interface Written {
        data object Failed : Written

        data object TooLarge : Written

        class Ok(val tmp: String, val header: VaultHeader) : Written
    }

    private sealed interface Placed {
        data object Failed : Placed

        class Done(val durable: Boolean) : Placed
    }

    /** What one password slot came to; the last three have no case in [PasswordResult] yet. */
    internal sealed interface SlotOutcome {
        class Opened(val opened: OpenedVault) : SlotOutcome

        class OlderPassword(val changedAtMs: Long) : SlotOutcome

        data object SlotClosed : SlotOutcome

        data object Damaged : SlotOutcome

        data object Newer : SlotOutcome

        data object Unreadable : SlotOutcome
    }

    internal companion object {
        /** The largest vault file: 16,777,216 bytes [V-C4]. A read takes at most one byte more. */
        const val CAP = 16_777_216

        private fun Body?.isUsable(): Boolean = this != Body.DAMAGED && this != Body.NEWER
    }
}

/**
 * The write-time rules of architecture.md §4.3, checked at §4.9 step 4: every entry `id` is a
 * valid UUID v4 and unique, and no string holds an unpaired surrogate. A reader never enforces
 * them, so no stored entry is ever refused; a writer never writes one that breaks them.
 */
internal object WriteRules {
    fun hold(vault: Vault): Boolean {
        val ids = HashSet<String>()
        for (entry in vault.entries) if (!Uuid.isValid(entry.id) || !ids.add(entry.id)) return false
        return strings(vault).none { TextHygiene.hasUnpairedSurrogate(it) }
    }

    private fun strings(vault: Vault): Sequence<String> = sequence {
        for (entry in vault.entries) {
            yield(entry.id)
            yield(entry.title)
            yield(entry.username)
            yield(entry.password)
            yield(entry.notes)
            yieldAll(entry.urls)
            for (field in entry.fields) {
                yield(field.name)
                yield(field.value)
            }
            entry.pendingOldPassword?.let { yield(it) }
            for (item in entry.passwordHistory) yield(item.password)
            for ((key, value) in entry.extras) {
                yield(key)
                yield(value)
            }
        }
        vault.draft?.let { draft ->
            draft.entryId?.let { yield(it) }
            val form = draft.entry
            yield(form.title)
            yield(form.username)
            yield(form.password)
            yield(form.notes)
            yieldAll(form.urls)
            for (field in form.fields) {
                yield(field.name)
                yield(field.value)
            }
        }
        for (ref in vault.plainExports + vault.backups) {
            yield(ref.uri)
            yield(ref.name)
        }
        yieldAll(vault.masterPasswordMatches)
        for ((key, value) in vault.extras) {
            yield(key)
            yield(value)
        }
    }
}
