package io.github.dilshodbekmx.passwordvault.core.lock.flow.vaults

import io.github.dilshodbekmx.passwordvault.core.lock.flow.CreateOutcome
import io.github.dilshodbekmx.passwordvault.core.lock.flow.KeyCreate
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockState
import io.github.dilshodbekmx.passwordvault.core.lock.flow.Notice
import io.github.dilshodbekmx.passwordvault.core.lock.flow.NoticeAnswer
import io.github.dilshodbekmx.passwordvault.core.lock.flow.PasswordOpen
import io.github.dilshodbekmx.passwordvault.core.lock.flow.PinKey
import io.github.dilshodbekmx.passwordvault.core.lock.flow.VaultRef
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.OTHER_VAULT
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.PASSWORD
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.THIRD_VAULT
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.VAULT
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.await
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.bytes
import io.github.dilshodbekmx.passwordvault.core.lock.guard.PinGuard
import io.github.dilshodbekmx.passwordvault.core.lock.guard.PinWrap
import io.github.dilshodbekmx.passwordvault.core.lock.policy.Ks
import io.github.dilshodbekmx.passwordvault.core.lock.record.RecordCodec
import io.github.dilshodbekmx.passwordvault.core.lock.record.RecordFiles
import io.github.dilshodbekmx.passwordvault.core.lock.record.RecordRead
import io.github.dilshodbekmx.passwordvault.core.model.Entry
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.testing.lock.FakeRecordFiles
import io.github.dilshodbekmx.passwordvault.testing.lock.FakeVaultPort
import org.junit.Assert.assertTrue

/*
 * What the tests of build plan 3.14 (lock.md F12, F13, F14, F17, F18) share, on top of the core's
 * `LockFixture`: the passwords and PINs of the vaults they put on the phone, a backup to restore, a
 * phone whose process dies whole at a chosen boundary (lock.md T14), and the assertions that every
 * crash point is checked against.
 */

/** The master passwords and PINs of the vaults these tests put on the phone. */
internal object Secrets {
    /** The master password of [OTHER_VAULT]. */
    const val OTHER = "the other vault's own words"

    /** The master password of [THIRD_VAULT]. */
    const val THIRD = "a third vault's own words"

    /** The master password of the backup these tests restore, and so of the vault a restore makes. */
    const val BACKUP = "the words of an old backup"

    /** The PIN of [OTHER_VAULT]; [LockFixture.PIN] is the current vault's. Both pass the blocklist (lock.md §7.2). */
    const val OTHER_PIN = "905127"

    /** The PIN of [THIRD_VAULT]. */
    const val THIRD_PIN = "368014"

    /** The master password of [vaultId]: the fixture's for [VAULT], the two above, and the backup's for a vault a restore made. */
    fun passwordOf(vaultId: String): String = when (vaultId) {
        VAULT -> PASSWORD
        OTHER_VAULT -> OTHER
        THIRD_VAULT -> THIRD
        else -> BACKUP
    }

    /** The PIN of [vaultId]. */
    fun pinOf(vaultId: String): String = when (vaultId) {
        VAULT -> LockFixture.PIN
        OTHER_VAULT -> OTHER_PIN
        else -> THIRD_PIN
    }
}

/** What a restored backup holds: two accounts. */
internal val BACKUP_VAULT = Vault(
    entries = listOf(
        Entry(id = "00000000-0000-4000-8000-0000000000c1", title = "Gmail", username = "aziz", password = "p1", createdAt = 1L, updatedAt = 2L),
        Entry(id = "00000000-0000-4000-8000-0000000000c2", title = "Bank", username = "aziz", password = "p2", createdAt = 1L, updatedAt = 2L),
    ),
)

/** Restores [BACKUP_VAULT] with its master password typed once and passed twice, as the restore screen does (F13 step 4). */
internal fun LockFixture.restoreBackup(password: String = Secrets.BACKUP, created: suspend () -> Unit = {}): CreateOutcome {
    val first = password.toCharArray()
    val again = password.toCharArray()
    return await { engine.createVault(first, again, restoreFrom = BACKUP_VAULT, created = created) }
}

/** Answers every notice shown with LATER where it offers that, otherwise OK. */
internal fun LockFixture.answerNotices() {
    for (notice in (state as LockState.Unlocked).notices) {
        val answer = when (notice) {
            is Notice.ChoosePin, is Notice.FingerprintAgain, is Notice.StorageUnreadable, is Notice.BackupNow, is Notice.PendingReady -> NoticeAnswer.LATER
            else -> NoticeAnswer.OK
        }
        await { engine.answerNotice(notice.id, answer) }
    }
}

// -------------------------------------------------------------------------------------------------
// A phone whose process dies whole (lock.md T14)

/** How the process ends at the stopping point. */
internal enum class Restart { KILL, POWER_CUT }

/** The record files as the engine reaches them: dead as soon as the vault port has stopped. */
private class LinkedFiles(private val inner: FakeRecordFiles, private val portAlive: () -> Boolean) : RecordFiles {
    private fun <T> alive(call: () -> T): T {
        if (!portAlive()) throw FakeVaultPort.Stopped(0, "the process is dead")
        return call()
    }

    override fun read(dir: String, name: String): ByteArray? = alive { inner.read(dir, name) }

    override fun replace(dir: String, name: String, bytes: ByteArray) = alive { inner.replace(dir, name, bytes) }

    override fun dirs(root: String): List<String> = alive { inner.dirs(root) }

    override fun deleteDir(dir: String) = alive { inner.deleteDir(dir) }

    override fun deleteStaleTmp(root: String) = alive { inner.deleteStaleTmp(root) }
}

/**
 * A PIN key that checks I4 at every HMAC, for whichever vault's PIN it is: the `pin.rec` that names
 * the key holds the charge on disk, and has for at least 1,000 ms (it would survive a power cut with
 * that settle time).
 */
internal class AgedChargeKey(private val inner: PinKey, private val f: LockFixture) : PinKey {
    @Volatile var checks = 0
        private set

    override fun create(alias: String): KeyCreate = inner.create(alias)

    override fun hmac(alias: String, message: ByteArray): Ks<ByteArray> {
        val records = lockDirs(f).mapNotNull { id ->
            val durable = f.files.durableBytes(f.dirOf(id), "pin.rec", PinGuard.CHARGE_AGE_MS) ?: return@mapNotNull null
            (RecordCodec.Pin.decode(durable, id) as? RecordRead.Ok)?.record?.takeIf { r -> r.slot?.let { PinWrap.alias(it.id) } == alias }
        }
        assertTrue("I4: no durable pin.rec names the key $alias", records.isNotEmpty())
        assertTrue("I4: a PIN was checked without a durable charge at least 1,000 ms old", records.all { it.guard.charged })
        checks++
        return inner.hmac(alias, message)
    }
}

/** A phone for crash injection: when the record files or the vault port stop, the other is dead too, and every HMAC checks I4. */
internal fun crashablePhone(seed: Long): LockFixture {
    val f = LockFixture(seed = seed)
    f.port.processAlive = { f.files.alive }
    f.recordFiles = LinkedFiles(f.files) { f.port.alive }
    f.pinKey = AgedChargeKey(f.keys.pin, f)
    f.rebuild()
    return f
}

/** Runs [script] until the process stops; true when it did. */
internal fun untilStopped(script: () -> Unit): Boolean =
    try {
        script()
        false
    } catch (e: FakeRecordFiles.Stopped) {
        true
    } catch (e: FakeVaultPort.Stopped) {
        true
    }

/** The process ends [how], and a new one is made over the same phone (nothing started yet). */
internal fun restartAfter(f: LockFixture, how: Restart) {
    if (how == Restart.POWER_CUT) f.files.powerCut(1_000)
    f.restart()
}

/** Every lock directory these tests may have made: the fixture's three, and those of the vaults on the phone now. */
internal fun lockDirs(f: LockFixture): List<String> =
    (listOf(VAULT, OTHER_VAULT, THIRD_VAULT) + listOfNotNull(f.port.current?.vaultId) + f.port.asideVaults.map { it.vaultId }).distinct()

/** The generation files of [vaultId], wherever its directory is now: they never change in these flows (I1), only move. */
internal fun filesOf(f: LockFixture, vaultId: String): String = f.port.vault(vaultId)?.generations?.toString() ?: "gone"

/**
 * For every record on disk that names a key, that key is still in the Keystore; except the keys in
 * [removing], those of slots whose removal was under way (lock.md §5.7: a key whose slot is removed is
 * deleted at once, and if the record write is then lost the next start removes the slot again).
 */
internal fun assertNoNamedKeyWasDeleted(f: LockFixture, where: String, removing: Set<String> = emptySet()) {
    for (dir in lockDirs(f)) {
        (f.pinOnDisk(dir) as? RecordRead.Ok)?.record?.slot?.let { slot ->
            val alias = PinWrap.alias(slot.id)
            assertTrue("$where: pin.rec of $dir names a key that was deleted", alias in f.keys.aliases || alias in removing)
        }
        (f.fingerprintOnDisk(dir) as? RecordRead.Ok)?.record?.slot?.let { slot ->
            val alias = RecordCodec.FINGERPRINT_ALIAS_PREFIX + slot.id
            assertTrue("$where: fingerprint.rec of $dir names a key that was deleted", alias in f.keys.aliases || alias in removing)
        }
    }
}

/**
 * Every vault on the phone opens with its own master password, asked of storage as a new process
 * would ask (lock.md T14, A3). It leaves the vault port as a new process finds it.
 */
internal fun assertEveryVaultOpens(f: LockFixture, where: String) {
    f.port.restart()
    f.port.current?.let { current ->
        val opened = await { f.port.openWithPassword(VaultRef.Current, bytes(Secrets.passwordOf(current.vaultId))) }
        assertTrue("$where: the current vault ${current.vaultId} gave $opened", opened is PasswordOpen.Opened)
    }
    for (aside in f.port.asideVaults) {
        val opened = await { f.port.openWithPassword(VaultRef.Aside(aside.vaultId), bytes(Secrets.passwordOf(aside.vaultId))) }
        assertTrue("$where: the vault put aside ${aside.vaultId} gave $opened", opened is PasswordOpen.Opened)
    }
    f.port.restart()
}
