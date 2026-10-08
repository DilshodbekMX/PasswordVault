package io.github.dilshodbekmx.passwordvault.core.lock.flow.password

import io.github.dilshodbekmx.passwordvault.core.crypto.MasterPasswordBytes
import io.github.dilshodbekmx.passwordvault.core.lock.flow.AsideVault
import io.github.dilshodbekmx.passwordvault.core.lock.flow.ChangeOutcome
import io.github.dilshodbekmx.passwordvault.core.lock.flow.GenerationInfo
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockState
import io.github.dilshodbekmx.passwordvault.core.lock.flow.MasterPasswordOutcome
import io.github.dilshodbekmx.passwordvault.core.lock.flow.MasterProof
import io.github.dilshodbekmx.passwordvault.core.lock.flow.Notice
import io.github.dilshodbekmx.passwordvault.core.lock.flow.NoticeAnswer
import io.github.dilshodbekmx.passwordvault.core.lock.flow.OlderPasswordAnswer
import io.github.dilshodbekmx.passwordvault.core.lock.flow.PasswordOpen
import io.github.dilshodbekmx.passwordvault.core.lock.flow.PendingWrap
import io.github.dilshodbekmx.passwordvault.core.lock.flow.ProofOutcome
import io.github.dilshodbekmx.passwordvault.core.lock.flow.RequestOutcome
import io.github.dilshodbekmx.passwordvault.core.lock.flow.VaultRef
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.await
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.bytes
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.digits
import io.github.dilshodbekmx.passwordvault.core.lock.flow.vaults.Restart
import io.github.dilshodbekmx.passwordvault.core.lock.flow.vaults.crashablePhone
import io.github.dilshodbekmx.passwordvault.core.lock.flow.vaults.restartAfter
import io.github.dilshodbekmx.passwordvault.core.lock.guard.PinWrap
import io.github.dilshodbekmx.passwordvault.core.lock.policy.EntryPolicy
import io.github.dilshodbekmx.passwordvault.core.lock.record.RecordCodec
import io.github.dilshodbekmx.passwordvault.core.lock.record.RecordRead
import io.github.dilshodbekmx.passwordvault.core.lock.record.Shortcut
import io.github.dilshodbekmx.passwordvault.core.lock.record.StateRecord
import io.github.dilshodbekmx.passwordvault.testing.lock.FakeRecordFiles
import io.github.dilshodbekmx.passwordvault.testing.lock.FakeVaultPort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/*
 * What the tests of build plan 4.4 (lock.md F8, F11, F16) share, over the phone of `LockFixture`
 * (package 3.10's), with the helpers of the shortcut tests (3.13) and the crash phone of the vault
 * tests (3.14), each used as it is.
 */

/** The master password a change or a replacement chooses. */
internal const val NEW_PASSWORD = "seven new words for the same vault"

/** The master password of a second change, or of a second replacement. */
internal const val THIRD_PASSWORD = "and a third master password later"

/** An hour and a day in milliseconds. */
internal const val HOUR = 3_600_000L
internal const val DAY = 24 * HOUR

/** F8: [new] typed twice ([again]) after the current master password ([proof]); [pin] is the PIN of step 3, or null for "Not now". */
internal fun LockFixture.changeMasterPassword(proof: MasterProof, new: String = NEW_PASSWORD, pin: String? = null, again: String = new): ChangeOutcome {
    val first = new.toCharArray()
    val second = again.toCharArray()
    val typed = pin?.let(::digits)
    return await { engine.changeMasterPassword(proof, first, second, typed) }
}

/** F11 step 3 in the open app: "Proof now", with the PIN typed in its prompt, or without one. */
internal fun LockFixture.prove(pin: String? = null): ProofOutcome {
    val typed = pin?.let(::digits)
    return await { engine.proveForNewMasterPassword(typed) }
}

/** F11 steps 4 and 5: the new master password typed twice. */
internal fun LockFixture.request(new: String = NEW_PASSWORD, again: String = new): RequestOutcome {
    val first = new.toCharArray()
    val second = again.toCharArray()
    return await { engine.requestNewMasterPassword(first, second) }
}

/** F11 "Finish now" in the open app. */
internal fun LockFixture.finish(typed: String = NEW_PASSWORD): MasterPasswordOutcome {
    val chars = typed.toCharArray()
    return await { engine.finishPending(chars) }
}

internal fun LockFixture.cantRemember() = await { engine.cantRemember() }

internal fun LockFixture.answerOlder(answer: OlderPasswordAnswer) = await { engine.olderPasswordAnswer(answer) }

/** Time passes with the phone switched on, and the lock looks at the clock, as it does whenever the app is looked at (§6.9). */
internal fun LockFixture.passTime(ms: Long) {
    clock.advance(ms)
    await { engine.refresh() }
}

/** Every notice shown is answered with LATER where it offers that, otherwise OK. */
internal fun LockFixture.answerAll() {
    for (notice in (state as LockState.Unlocked).notices) {
        val answer = when (notice) {
            is Notice.ChoosePin, is Notice.FingerprintAgain, is Notice.StorageUnreadable, is Notice.BackupNow, is Notice.PendingReady -> NoticeAnswer.LATER
            else -> NoticeAnswer.OK
        }
        await { engine.answerNotice(notice.id, answer) }
    }
}

/** The `vk_id`s of the wraps in the PIN slot of `pin.rec` on disk; empty without a slot. */
internal fun LockFixture.pinWraps(vaultId: String = LockFixture.VAULT): List<String> =
    (pinOnDisk(vaultId) as? RecordRead.Ok)?.record?.slot?.wraps?.map { it.vkId }.orEmpty()

/** The `vk_id`s of the wraps in the fingerprint slot of `fingerprint.rec` on disk; empty without a slot. */
internal fun LockFixture.fingerprintWraps(vaultId: String = LockFixture.VAULT): List<String> =
    (fingerprintOnDisk(vaultId) as? RecordRead.Ok)?.record?.slot?.wraps?.map { it.vkId }.orEmpty()

/** The aliases that the two slot records of [vaultId] name on disk now. */
internal fun LockFixture.aliasesOnDisk(vaultId: String): Set<String> = setOfNotNull(
    (pinOnDisk(vaultId) as? RecordRead.Ok)?.record?.slot?.let { PinWrap.alias(it.id) },
    (fingerprintOnDisk(vaultId) as? RecordRead.Ok)?.record?.slot?.let { RecordCodec.FINGERPRINT_ALIAS_PREFIX + it.id },
)

/** A pending new master password that was asked for [agoMs] ago by [by], with [creditedMs] of its 24 hours credited: as `PasswordFlows` writes one, for [password]. */
internal fun LockFixture.plantPending(
    vault: FakeVaultPort.FakeVault,
    password: String = NEW_PASSWORD,
    creditedMs: Long = EntryPolicy.PENDING_WAIT_MS,
    by: Shortcut = Shortcut.PIN,
    agoMs: Long = 2 * DAY,
): StateRecord.PendingMp {
    val salt = ByteArray(16) { (it + 1).toByte() }
    val nonce = ByteArray(12) { (it + 40).toByte() }
    val vkId = vault.newestVkId
    val kek = await { kdf.kek(MasterPasswordBytes.of(password.toCharArray()), salt) }
    val ct = PendingWrap.seal(aead, kek, nonce, vault.vk(vkId), vault.vaultId, vkId)
    return StateRecord.PendingMp(clock.wallMs() - agoMs, by, creditedMs, clock.elapsedMs(), vkId, salt, nonce, ct)
}

// -------------------------------------------------------------------------------------------------
// A stop at every boundary (lock.md T14, T15, T16)

/**
 * One run of a crash test, after its "restart": the phone [f] as a new process finds it (nothing has
 * started), where it was stopped ([where], for messages) and [how] it came back. [trace] is what the
 * dead process passed, in order: every boundary of the record files (`files: replace …/state.rec:
 * after the rename`), every boundary of storage (`storage: changePassword: published`) and the start
 * of every suspending storage call (`call: newKey`); its last entry is the boundary of the stop.
 * [hmacCalls] is how many PINs the Keystore had been asked to check by then. [again] makes the same
 * phone once more, stopped at the same boundary and restarted the same way, for a second look that
 * needs it untouched.
 */
internal class CrashRun(
    val f: LockFixture,
    val where: String,
    val how: Restart,
    val trace: List<String>,
    val hmacCalls: Int,
    val again: () -> LockFixture,
)

/** What the boundaries of [f] passed from now on, as [CrashRun.trace] lists it. */
internal fun traceOf(f: LockFixture): MutableList<String> {
    val trace = ArrayList<String>()
    f.files.onBoundary = { _, at -> trace += "files: $at" }
    f.port.onBoundary = { _, at -> trace += "storage: $at" }
    f.port.beforeCall = { call -> trace += "call: $call" }
    return trace
}

private fun untraced(f: LockFixture) {
    f.files.onBoundary = null
    f.port.onBoundary = null
    f.port.beforeCall = null
}

/**
 * lock.md T14: runs [script] on a phone made by [setUp] once without a stop, then once for every
 * boundary it passes (the four of every record write, the two of every delete, and before and after
 * every storage step), stopped there; each stop is followed by a kill (everything handed over stays)
 * and, in another run, by a power cut (what was handed over less than a second before is lost), and
 * [check] looks at what the next process finds. Returns the phone of the run without a stop.
 */
internal fun atEveryBoundary(
    name: String,
    seed: Long,
    setUp: (LockFixture) -> Unit,
    script: (LockFixture) -> Unit,
    minFileBoundaries: Int,
    minPortBoundaries: Int,
    check: (CrashRun) -> Unit,
): LockFixture {
    val whole = crashablePhone(seed)
    setUp(whole)
    val filesBefore = whole.files.boundaries
    val portBefore = whole.port.boundaries
    script(whole)
    val fileBoundaries = whole.files.boundaries - filesBefore
    val portBoundaries = whole.port.boundaries - portBefore
    assertTrue("$name: the script writes records: $fileBoundaries boundaries", fileBoundaries >= minFileBoundaries)
    assertTrue("$name: the script asks storage: $portBoundaries boundaries", portBoundaries >= minPortBoundaries)

    /** The phone stopped at boundary [k] (the record files' first, then storage's) and restarted [how]; with its trace. */
    fun stopped(k: Int, how: Restart, where: String): Triple<LockFixture, List<String>, Int> {
        val f = crashablePhone(seed)
        setUp(f)
        val trace = traceOf(f)
        if (k <= fileBoundaries) f.files.stopAt(k) else f.port.stopAt(k - fileBoundaries)
        val stoppedThere = try {
            script(f)
            false
        } catch (e: FakeRecordFiles.Stopped) {
            true
        } catch (e: FakeVaultPort.Stopped) {
            true
        }
        assertTrue("no stop: $where", stoppedThere)
        untraced(f)
        val hmacCalls = f.keys.hmacCalls.size
        restartAfter(f, how)
        return Triple(f, trace.toList(), hmacCalls)
    }

    var checked = 0
    for (how in Restart.entries) {
        for (k in 1..fileBoundaries + portBoundaries) {
            val where = "$name, " + (if (k <= fileBoundaries) "record boundary $k" else "storage boundary ${k - fileBoundaries}") + ", $how"
            val (f, trace, hmacCalls) = stopped(k, how, where)
            check(CrashRun(f, "$where (${trace.lastOrNull()})", how, trace, hmacCalls) { stopped(k, how, where).first })
            checked++
        }
    }
    assertEquals(2 * (fileBoundaries + portBoundaries), checked)
    return whole
}

/**
 * Every vault on the phone opens with its own master password, asked of storage as a new process
 * would ask (lock.md T14, A3): [passwordOf] says which that is for each vault as it stands now. It
 * leaves the vault port as a new process finds it.
 */
internal fun assertEveryVaultOpensWith(f: LockFixture, where: String, passwordOf: (FakeVaultPort.FakeVault) -> String) {
    f.port.restart()
    f.port.current?.let { current ->
        val opened = await { f.port.openWithPassword(VaultRef.Current, bytes(passwordOf(current))) }
        assertTrue("$where: the current vault ${current.vaultId} gave $opened", opened is PasswordOpen.Opened)
        assertEquals("$where: its newest copy", current.live.first().number, (opened as PasswordOpen.Opened).generation)
    }
    for (aside in f.port.asideVaults) {
        val opened = await { f.port.openWithPassword(VaultRef.Aside(aside.vaultId), bytes(passwordOf(aside))) }
        assertTrue("$where: the vault put aside ${aside.vaultId} gave $opened", opened is PasswordOpen.Opened)
    }
    f.port.restart()
}

/**
 * Whether the vault [vaultId] put aside offers a PIN or a fingerprint by the records on disk (lock.md
 * §13.1) with a key behind it: what "the old vault has no usable shortcut" (T14, T16) is asked of.
 */
internal fun hasUsableShortcut(f: LockFixture, vaultId: String): Boolean {
    val newest = f.port.vault(vaultId)!!.live.first()
    val decision = EntryPolicy.decide(
        EntryPolicy.Input(
            target = EntryPolicy.Target.Aside(AsideVault(vaultId, 0L, GenerationInfo(newest.number, newest.vkId, newest.createdMs))),
            pin = f.pinOnDisk(vaultId),
            fingerprint = f.fingerprintOnDisk(vaultId),
            state = f.stateOnDisk(vaultId),
            nowWallMs = f.clock.wallMs(),
            nowElapsedMs = f.clock.elapsedMs(),
            canAuthenticate = 0,
        ),
    ) as EntryPolicy.Decision.Locked
    val pinKey = (f.pinOnDisk(vaultId) as? RecordRead.Ok)?.record?.slot?.let { PinWrap.alias(it.id) in f.keys.aliases } == true
    val fingerprintKey =
        (f.fingerprintOnDisk(vaultId) as? RecordRead.Ok)?.record?.slot?.let { RecordCodec.FINGERPRINT_ALIAS_PREFIX + it.id in f.keys.aliases } == true
    return (decision.pinUsable && pinKey) || (decision.fingerprintUsable && fingerprintKey)
}
