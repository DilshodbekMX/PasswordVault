package io.github.dilshodbekmx.passwordvault.core.lock.flow.password

import io.github.dilshodbekmx.passwordvault.core.crypto.MasterPasswordBytes
import io.github.dilshodbekmx.passwordvault.core.lock.flow.ChangeOutcome
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockState
import io.github.dilshodbekmx.passwordvault.core.lock.flow.MasterPasswordOutcome
import io.github.dilshodbekmx.passwordvault.core.lock.flow.MasterProof
import io.github.dilshodbekmx.passwordvault.core.lock.flow.Notice
import io.github.dilshodbekmx.passwordvault.core.lock.flow.NoticeAnswer
import io.github.dilshodbekmx.passwordvault.core.lock.flow.OlderPasswordAnswer
import io.github.dilshodbekmx.passwordvault.core.lock.flow.PendingWrap
import io.github.dilshodbekmx.passwordvault.core.lock.flow.ProofOutcome
import io.github.dilshodbekmx.passwordvault.core.lock.flow.RequestOutcome
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.await
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.digits
import io.github.dilshodbekmx.passwordvault.core.lock.guard.PinWrap
import io.github.dilshodbekmx.passwordvault.core.lock.policy.EntryPolicy
import io.github.dilshodbekmx.passwordvault.core.lock.record.RecordCodec
import io.github.dilshodbekmx.passwordvault.core.lock.record.RecordRead
import io.github.dilshodbekmx.passwordvault.core.lock.record.Shortcut
import io.github.dilshodbekmx.passwordvault.core.lock.record.StateRecord
import io.github.dilshodbekmx.passwordvault.testing.lock.FakeVaultPort

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
