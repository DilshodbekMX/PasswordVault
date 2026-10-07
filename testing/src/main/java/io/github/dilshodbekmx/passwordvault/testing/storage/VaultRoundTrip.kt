package io.github.dilshodbekmx.passwordvault.testing.storage

import io.github.dilshodbekmx.passwordvault.core.crypto.Aead
import io.github.dilshodbekmx.passwordvault.core.crypto.Argon2Params
import io.github.dilshodbekmx.passwordvault.core.crypto.Argon2id
import io.github.dilshodbekmx.passwordvault.core.crypto.MasterPasswordBytes
import io.github.dilshodbekmx.passwordvault.core.model.CustomField
import io.github.dilshodbekmx.passwordvault.core.model.Entry
import io.github.dilshodbekmx.passwordvault.core.model.HistoryItem
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.core.storage.CommitResult
import io.github.dilshodbekmx.passwordvault.core.storage.Current
import io.github.dilshodbekmx.passwordvault.core.storage.FilePrimitives
import io.github.dilshodbekmx.passwordvault.core.storage.OpenResult
import io.github.dilshodbekmx.passwordvault.core.storage.OpenedVault
import io.github.dilshodbekmx.passwordvault.core.storage.PasswordResult
import io.github.dilshodbekmx.passwordvault.core.storage.StageResult
import io.github.dilshodbekmx.passwordvault.core.storage.VaultRef
import io.github.dilshodbekmx.passwordvault.core.storage.VaultStorage
import io.github.dilshodbekmx.passwordvault.core.storage.VaultStores
import io.github.dilshodbekmx.passwordvault.core.util.Clock
import io.github.dilshodbekmx.passwordvault.core.util.RandomSource
import io.github.dilshodbekmx.passwordvault.core.vaultfile.BuildInfo
import io.github.dilshodbekmx.passwordvault.core.vaultfile.FileCheck
import io.github.dilshodbekmx.passwordvault.core.vaultfile.VaultCodec
import io.github.dilshodbekmx.passwordvault.core.vaultfile.VaultCodecs

/**
 * The vault through the real parts of :core together: the store of build plan 2.3, the codec of 2.2,
 * AES-256-GCM and Argon2id of 2.1, `MasterPasswordBytes` of 1.3, over any [FilePrimitives].
 *
 * Create with a password, reopen with the password, edit, reopen with the key, change the password,
 * reopen with both, put aside and swap back. Every "reopen" is a new store, as a new process would
 * make it, over the same files.
 *
 * These are the steps of `VaultRoundTripTest.roundTrip` (2.3's test in :core), moved here unchanged by
 * build plan 2.7 so that the same steps run in two places: on the JVM over `FakeFiles` with Bouncy
 * Castle (`VaultRoundTripTest`), and on a phone over `OsFiles` with `NativeArgon2id` (the app's
 * instrumented `platform.VaultRoundTripDeviceTest`). :testing has no JUnit, so a step that does not
 * hold throws `IllegalStateException` through Kotlin's `check` and `error`, which fails the test that
 * called [roundTrip] in either place.
 */
object VaultRoundTrip {
    /** The writer of every file the round trip makes. */
    val BUILD = BuildInfo("PasswordVault 1.0", 1)

    /**
     * The round trip over [files] in [vaultsDir], deriving every KEK with [argon2]. [aead] makes the
     * codec; [random] gives keys, ids and salts; [clock] the `created` times. [vaultsDir] must not
     * exist yet or hold no vault, and its parent must exist.
     */
    fun roundTrip(files: FilePrimitives, vaultsDir: String, argon2: Argon2id, aead: Aead, random: RandomSource, clock: Clock) {
        val codec: VaultCodec = VaultCodecs.create(aead, random, clock, BUILD)
        val keks = HashMap<String, ByteArray>()
        fun kek(password: String, salt: ByteArray): ByteArray = keks.getOrPut(password + "/" + salt.joinToString(",")) {
            val bytes = MasterPasswordBytes.of(password.toCharArray())
            try {
                argon2.derive(bytes, salt, Argon2Params.V1)
            } finally {
                bytes.fill(0)
            }
        }
        fun process(): VaultStorage = VaultStores.create(files, codec, clock, vaultsDir, BUILD)
        fun opened(result: Any): OpenedVault = when (result) {
            is PasswordResult.Opened -> result.opened
            is OpenResult.Opened -> result.opened
            is CommitResult.Saved -> result.opened
            else -> error("expected an open vault, got $result")
        }

        // 1. Create with a password (lock.md F1): the slot from the first typing, the proof with the second.
        val first = "correct horse battery staple"
        val vaultId = random.uuid()
        val vkId = random.uuid()
        val vk = random.bytes(32)
        val salt = random.bytes(16)
        val creating = process()
        checkEquals(Current.NoVault, creating.scan().current)
        val staged = (creating.stageVault(Vault(), vaultId, vk, vkId, codec.wrap(vk, kek(first, salt), salt)) as StageResult.Staged).staged
        check(salt.contentEquals(staged.slotSalt)) { "the slot's salt as read back from disk" }
        check(creating.prove(staged, kek(" correct  horse battery staple ", staged.slotSalt))) { "proved from disk with the second typing" }
        val created = opened(creating.publish(staged, putCurrentAside = false))
        checkEquals(1L, created.generation)

        // 2. Reopen with the password, in a new process.
        val reopening = process()
        val facts = (reopening.scan().current as Current.Present).facts
        checkEquals(vaultId, facts.vaultId)
        checkEquals(1L, facts.newest.number)
        val slot = reopening.slots(VaultRef.Current).single()
        checkEquals(PasswordResult.SlotClosed, reopening.openWithSlot(VaultRef.Current, slot, kek("correct horse battery stapler", slot.salt)))
        val reopened = opened(reopening.openWithSlot(VaultRef.Current, slot, kek(first, slot.salt)))
        checkEquals(Vault(), reopened.vault)

        // 3. Edit: one ordinary save, with text in both scripts and the characters a writer must escape.
        val now = clock.wallMs()
        val edited = Vault(
            entries = listOf(
                Entry(
                    id = random.uuid(),
                    title = "Gmail – Oʻzbekiston Ўзбекистон",
                    username = "aziz@example.com",
                    password = "p\"ss\\w/rd-🔑",
                    notes = "Birinchi qator\nИккинчи қатор\ttab\u0001",
                    urls = listOf("https://mail.google.com"),
                    fields = listOf(CustomField("PIN code", "0042", secret = true)),
                    favourite = true,
                    createdAt = now,
                    updatedAt = now,
                    passwordChangedAt = now,
                    passwordHistory = listOf(HistoryItem("old1", now - 1)),
                ),
            ),
        )
        val saved = opened(reopening.commit(reopened, edited))
        checkEquals(2L, saved.generation)

        // 4. Reopen with the key, as the PIN or the fingerprint would.
        val byKeyStore = process()
        byKeyStore.scan()
        val byKey = opened(byKeyStore.open(VaultRef.Current, mapOf(vkId to vk)))
        checkEquals(edited, byKey.vault)
        checkEquals(2L, byKey.generation)

        // 5. Change the master password (lock.md F8): a new key and slot in live/, proven, published.
        val second = "a quite different passphrase of 2026"
        val vkId2 = random.uuid()
        val vk2 = random.bytes(32)
        val salt2 = random.bytes(16)
        val stagedKey = (byKeyStore.stageRekey(byKey, byKey.vault, vk2, vkId2, codec.wrap(vk2, kek(second, salt2), salt2)) as StageResult.Staged).staged
        check(byKeyStore.prove(stagedKey, kek(second, stagedKey.slotSalt))) { "the new slot proved from disk" }
        val changed = opened(byKeyStore.publish(stagedKey, putCurrentAside = false))
        checkEquals(3L, changed.generation)
        checkEquals(vkId2, changed.vkId)

        // 6. Reopen with both keys and with both passwords.
        val both = process()
        val factsBoth = (both.scan().current as Current.Present).facts
        checkEquals(setOf(vkId, vkId2), factsBoth.keptVkIds)
        checkEquals(vkId2, factsBoth.newest.vkId)
        checkEquals(OpenResult.NeedsOtherKey(3L, factsBoth.newest.createdMs), both.open(VaultRef.Current, mapOf(vkId to vk)))
        val withBothKeys = opened(both.open(VaultRef.Current, mapOf(vkId to vk, vkId2 to vk2)))
        checkEquals(3L, withBothKeys.generation)
        val slots = both.slots(VaultRef.Current)
        checkEquals(listOf(vkId2, vkId), slots.map { it.vkId })
        checkEquals(edited, opened(both.openWithSlot(VaultRef.Current, slots[0], kek(second, slots[0].salt))).vault)
        checkEquals(PasswordResult.SlotClosed, both.openWithSlot(VaultRef.Current, slots[0], kek(first, slots[0].salt)))
        checkEquals(
            PasswordResult.OlderPassword(factsBoth.newest.createdMs),
            both.openWithSlot(VaultRef.Current, slots[1], kek(first, slots[1].salt)),
            "the old password: an older master password, never wrong",
        )

        // 7. Put aside ("Start with an empty app"), then open it in place and swap it back.
        check(both.putAside()) { "put aside" }
        checkEquals(Current.NoVault, both.scan().current)
        val swapping = process()
        val scan = swapping.scan()
        checkEquals(Current.NoVault, scan.current)
        checkEquals(listOf(vaultId), scan.aside.map { it.vaultId })
        checkEquals(3L, scan.aside.single().newest!!.number)
        val inPlace = opened(swapping.open(VaultRef.Aside(vaultId), mapOf(vkId2 to vk2)))
        checkEquals(edited, inPlace.vault)
        check(swapping.swapIn(vaultId)) { "swapped back in" }
        val back = process()
        val factsBack = (back.scan().current as Current.Present).facts
        checkEquals(vaultId, factsBack.vaultId)
        checkEquals(3L, factsBack.newest.number)
        val slotBack = back.slots(VaultRef.Current).first()
        checkEquals(edited, opened(back.openWithSlot(VaultRef.Current, slotBack, kek(second, slotBack.salt))).vault)

        // Every live file is a real version 1 file of this vault.
        for (n in 1..3) {
            val bytes = files.read("$vaultsDir/live/vault-000000000$n.pvault", 16_777_216)
            val check = codec.check(bytes)
            check(check is FileCheck.Ok && check.file.trailerOk && check.file.header.vaultId == vaultId) { "generation $n: $check" }
        }
    }

    /** `assertEquals` of the original steps, through Kotlin's `check`. */
    private fun checkEquals(expected: Any?, actual: Any?, what: String = "") {
        check(expected == actual) { (if (what.isEmpty()) "" else "$what: ") + "expected $expected, got $actual" }
    }
}
