package io.github.dilshodbekmx.passwordvault.ui.settings.master

import io.github.dilshodbekmx.passwordvault.core.lock.flow.MasterProof
import io.github.dilshodbekmx.passwordvault.core.model.Entry
import io.github.dilshodbekmx.passwordvault.core.model.FileRef
import io.github.dilshodbekmx.passwordvault.fake.FakeLockManager
import io.github.dilshodbekmx.passwordvault.lock.ChangeResult
import io.github.dilshodbekmx.passwordvault.lock.FinishResult
import io.github.dilshodbekmx.passwordvault.lock.LockManager
import io.github.dilshodbekmx.passwordvault.lock.ProofResult
import io.github.dilshodbekmx.passwordvault.lock.RequestResult
import io.github.dilshodbekmx.passwordvault.testing.FakeRandom

/**
 * The fake lock of the screens' tests, with what each call of F8 and F11 was given, as text (the
 * fake itself keeps nothing typed): the two typings and the PIN of `changeMasterPassword`, the PIN of
 * `proveForNewMasterPassword`, the typings of `requestNewMasterPassword`, the password of
 * `finishNewMasterPassword`. Only tests keep such text, never the app.
 */
class MasterLock(val fake: FakeLockManager = FakeLockManager()) : LockManager by fake {
    /** Each `changeMasterPassword`: the proof, the two typings and the PIN typed to keep it (null for "Not now"). */
    val changes = mutableListOf<Change>()

    /** Each `proveForNewMasterPassword`: the PIN typed, or null. */
    val proofs = mutableListOf<String?>()

    /** Each `requestNewMasterPassword`: the two typings. */
    val requests = mutableListOf<Pair<String, String>>()

    /** Each `finishNewMasterPassword`: what was typed. */
    val finishes = mutableListOf<String>()

    class Change(val proof: MasterProof, val first: String, val again: String, val pin: String?)

    override suspend fun changeMasterPassword(proof: MasterProof, first: CharArray, again: CharArray, pin: ByteArray?): ChangeResult {
        changes += Change(proof, String(first), String(again), pin?.let { String(it, Charsets.US_ASCII) })
        return fake.changeMasterPassword(proof, first, again, pin)
    }

    override suspend fun proveForNewMasterPassword(pin: ByteArray?): ProofResult {
        proofs += pin?.let { String(it, Charsets.US_ASCII) }
        return fake.proveForNewMasterPassword(pin)
    }

    override suspend fun requestNewMasterPassword(first: CharArray, again: CharArray): RequestResult {
        requests += String(first) to String(again)
        return fake.requestNewMasterPassword(first, again)
    }

    override suspend fun finishNewMasterPassword(chars: CharArray): FinishResult {
        finishes += String(chars)
        return fake.finishNewMasterPassword(chars)
    }
}

/** The test data of the master-password screens. */
object MasterData {
    /** 2026-10-08 10:00 UTC. */
    const val NOW = 1_791_453_600_000L
    const val DAY_MS = 86_400_000L

    /** The current master password of the tests, and a new one of one's own that passes the checklist. */
    const val CURRENT = "correct horse battery staple"
    const val OWN_NEW = "Samarqand tongi 2026 yangi kalit"

    /** Six suggested words, as the queued draws of [FakeRandom] pick them ([words]). */
    val WORD_INDICES = intArrayOf(10, 20, 30, 40, 50, 60)

    /** A PIN that every rule of lock.md §7.2 allows. */
    const val PIN = "582913"

    val GMAIL = Entry(id = "00000000-0000-4000-8000-00000000000a", title = "Gmail", password = CURRENT, createdAt = NOW - DAY_MS, updatedAt = NOW - DAY_MS)
    val BANK = Entry(id = "00000000-0000-4000-8000-00000000000b", title = "Bank", password = "pw-bank", createdAt = NOW - DAY_MS, updatedAt = NOW - DAY_MS)

    /** Two backups this app saved: an older one on the phone and a newer one on Google Drive. */
    val OLD_BACKUP = FileRef("content://com.android.providers.downloads.documents/document/77", "PasswordVault-backup-2026-10-01.pvault", NOW - 7 * DAY_MS)
    val NEW_BACKUP = FileRef("content://com.google.android.apps.docs.storage/document/acc%3D1%3Bdoc%3D9", "PasswordVault-backup-2026-10-06.pvault", NOW - 2 * DAY_MS)
}
