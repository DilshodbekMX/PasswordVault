package io.github.dilshodbekmx.passwordvault.core.lock.flow.shortcuts

import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockScreen
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockState
import io.github.dilshodbekmx.passwordvault.core.lock.flow.MasterPasswordOutcome
import io.github.dilshodbekmx.passwordvault.core.lock.flow.MasterProof
import io.github.dilshodbekmx.passwordvault.core.lock.flow.NoticeAnswer
import io.github.dilshodbekmx.passwordvault.core.lock.flow.PinSetOutcome
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.PASSWORD
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.await
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.digits
import io.github.dilshodbekmx.passwordvault.core.lock.guard.PinWrap
import io.github.dilshodbekmx.passwordvault.core.lock.record.RecordCodec
import io.github.dilshodbekmx.passwordvault.core.lock.record.RecordFiles
import io.github.dilshodbekmx.passwordvault.core.lock.record.RecordRead
import io.github.dilshodbekmx.passwordvault.core.util.Bytes
import io.github.dilshodbekmx.passwordvault.testing.lock.FakeVaultPort
import java.io.IOException
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertTrue

/*
 * What the tests of build plan 3.13 (F6, F9, F10, F15; lock.md §12) share, over the phone of
 * `LockFixture` (package 3.10's, used as it is).
 */

/** A new PIN that the blocklist allows (lock.md §7.2: 905127 is one of its examples). */
internal const val NEW_PIN = "905127"

/** Another allowed PIN (§7.2). */
internal const val THIRD_PIN = "368014"

/** A PIN the blocklist refuses (rule 3, a sequence). */
internal const val BLOCKED_PIN = "123456"

/** The master password typed on the lock screen, after "Use master password" when the screen is another; the proof of that unlock. */
internal fun LockFixture.unlockWithMasterPassword(password: String = PASSWORD): MasterProof {
    if (state is LockState.Locked && screen !is LockScreen.MasterPassword) await { engine.useMasterPassword() }
    val outcome = unlockWith(password)
    assertTrue("the master password gave $outcome", outcome is MasterPasswordOutcome.Right)
    return (outcome as MasterPasswordOutcome.Right).proof
}

/** The master password typed in a prompt of the open app ("Type master password", "Use master password instead"): its proof. */
internal fun LockFixture.proofInTheOpenApp(password: String = PASSWORD): MasterProof {
    val outcome = check(password)
    assertTrue("the master password gave $outcome", outcome is MasterPasswordOutcome.Right)
    return (outcome as MasterPasswordOutcome.Right).proof
}

/** Every notice shown now is answered with OK or LATER, as a user who reads them and goes on. */
internal fun LockFixture.answerEveryNotice() {
    for (notice in (state as LockState.Unlocked).notices) await { engine.answerNotice(notice.id, NoticeAnswer.OK) }
}

/** Whether the PIN was set and its record is on disk (`PinSetOutcome.Set`); [setPinOutcome] gives the outcome itself. */
internal fun LockFixture.setPin(pin: String, proof: MasterProof): Boolean = setPinOutcome(pin, proof) == PinSetOutcome.Set

internal fun LockFixture.setPinOutcome(pin: String, proof: MasterProof): PinSetOutcome = await { engine.setPin(digits(pin), proof) }

internal fun LockFixture.changePin(current: String, new: String) = await { engine.changePin(digits(current), digits(new)) }

internal fun LockFixture.fingerprintOn(proof: MasterProof) = await { engine.fingerprintOn(proof) }

internal fun LockFixture.fingerprintOff() = await { engine.fingerprintOff() }

/** The alias of the PIN slot of `pin.rec` on disk, or null. */
internal fun LockFixture.pinAliasOnDisk(vaultId: String = LockFixture.VAULT): String? =
    (pinOnDisk(vaultId) as? RecordRead.Ok)?.record?.slot?.let { PinWrap.alias(it.id) }

/** The alias of the fingerprint slot of `fingerprint.rec` on disk, or null. */
internal fun LockFixture.fingerprintAliasOnDisk(vaultId: String = LockFixture.VAULT): String? =
    (fingerprintOnDisk(vaultId) as? RecordRead.Ok)?.record?.slot?.let { RecordCodec.FINGERPRINT_ALIAS_PREFIX + it.id }

/**
 * Whether the PIN slot of `pin.rec` on disk opens [vault]'s key [vkId] with [pin], through the HMAC
 * of its Keystore key: the wrap of lock.md §4.3, made with the bytes of that section.
 */
internal fun LockFixture.pinOpens(vault: FakeVaultPort.FakeVault, pin: String, vkId: String = vault.newestVkId): Boolean {
    val slot = pinRecord(vault.vaultId).slot ?: return false
    val wrap = slot.wraps.firstOrNull { it.vkId == vkId } ?: return false
    val alias = PinWrap.alias(slot.id)
    if (keys.pinKeyOf(alias) == null) return false
    val kek = keys.hmacOf(alias, PinWrap.message(vault.vaultId, slot.id, digits(pin)))
    val vk = PinWrap.open(aead, kek, wrap, vault.vaultId, slot.id) ?: return false
    return Bytes.equal(vk, vault.vk(vkId))
}

/** Whether the fingerprint slot of `fingerprint.rec` on disk opens [vault]'s key [vkId] under its Keystore key (AES-GCM, no AAD, §4.2). */
internal fun LockFixture.fingerprintOpens(vault: FakeVaultPort.FakeVault, vkId: String = vault.newestVkId): Boolean {
    val slot = fingerprintRecord(vault.vaultId).slot ?: return false
    val wrap = slot.wraps.firstOrNull { it.vkId == vkId } ?: return false
    val key = keys.fingerprintKeyOf(RecordCodec.FINGERPRINT_ALIAS_PREFIX + slot.id) ?: return false
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, wrap.nonce))
    return Bytes.equal(cipher.doFinal(wrap.ct), vault.vk(vkId))
}

/** [record], a framed lock record of version 1, as a newer app would write it: `"v": 2`, framed and hashed again (lock.md §5.3 NEWER). */
internal fun newerRecord(record: ByteArray): ByteArray {
    val json = String(record, 12, record.size - 44, Charsets.UTF_8).replace("\"v\":1", "\"v\":2")
    val bytes = json.toByteArray(Charsets.UTF_8)
    val len = bytes.size
    val head = "PVLOCK01".toByteArray(Charsets.US_ASCII) +
        byteArrayOf((len ushr 24).toByte(), (len ushr 16).toByte(), (len ushr 8).toByte(), len.toByte())
    val body = head + bytes
    return body + MessageDigest.getInstance("SHA-256").digest(body)
}

/**
 * Record files whose writes fail when [fails] says so for the record about to be written: the
 * record is unchanged, as a write that failed at its temp file leaves it (lock.md §5.9). Everything
 * else goes to [inner].
 */
internal class FailingWrites(private val inner: RecordFiles, private val fails: (dir: String, name: String, bytes: ByteArray) -> Boolean) : RecordFiles {
    override fun read(dir: String, name: String): ByteArray? = inner.read(dir, name)

    override fun replace(dir: String, name: String, bytes: ByteArray) {
        if (fails(dir, name, bytes)) throw IOException("injected failure: no space left on the device")
        inner.replace(dir, name, bytes)
    }

    override fun dirs(root: String): List<String> = inner.dirs(root)

    override fun deleteDir(dir: String) = inner.deleteDir(dir)

    override fun deleteStaleTmp(root: String) = inner.deleteStaleTmp(root)
}
