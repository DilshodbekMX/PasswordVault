package io.github.dilshodbekmx.passwordvault.testing.lock

import io.github.dilshodbekmx.passwordvault.core.lock.flow.DeviceKeys
import io.github.dilshodbekmx.passwordvault.core.lock.flow.FingerprintKey
import io.github.dilshodbekmx.passwordvault.core.lock.flow.FingerprintResult
import io.github.dilshodbekmx.passwordvault.core.lock.flow.KeyCreate
import io.github.dilshodbekmx.passwordvault.core.lock.flow.PinKey
import io.github.dilshodbekmx.passwordvault.core.lock.flow.PromptUse
import io.github.dilshodbekmx.passwordvault.core.lock.policy.Ks
import io.github.dilshodbekmx.passwordvault.core.util.RandomSource
import io.github.dilshodbekmx.passwordvault.testing.FakeRandom
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CompletableDeferred

/**
 * The Android Keystore of the lock in software, with scripted results (lock.md §15.1;
 * architecture.md §3.4). One object holds the keys; its three views are the ports:
 *
 * - [pin] is `PinKey`: `K_pin` is a software HMAC-SHA256 key, so `KEK_pin` is the HMAC of lock.md
 *   §4.3 and the vectors there hold.
 * - [fingerprint] is `FingerprintKey`: `K_fp` is a software AES-256 key, a wrap is AES-GCM with a
 *   12-byte IV made here and no AAD (lock.md §4.2), and a prompt is a script (see [Prompt]).
 * - [device] is `DeviceKeys`: the canary `pv_canary`, the alias list and deletion.
 *
 * **What happens without a script.** A missing alias is judged by the rule of lock.md §14.2: GONE
 * ("not found") when the canary is there or can be made (it is then made), TEMPORARY when it is
 * missing and cannot be made. While the Keystore is not [answering], every use is TEMPORARY, every
 * creation is NOT NOW, [DeviceKeys.aliases] is empty and nothing is deleted. A fingerprint key that
 * [invalidateFingerprintKeys] marked is GONE ("invalidated") at its next use. A wrap that does not
 * open under its key, or opens to anything but 32 bytes, is GONE ("bad_wrap"), as in §14.3. With
 * [softwareOnly] (a release build on a phone without secure hardware) every creation gives
 * `NoSecureHardware` and keeps no key. Making a fingerprint key while no finger is [enrolled] gives
 * `NoneEnrolled`.
 *
 * **Scripts** come first, one per call, in the order queued: [scriptHmac] and [scriptProbe] (a
 * result of lock.md §14, such as `Ks.Temporary` for "Device locked" or `Ks.Gone("invalidated")`),
 * [scriptCreate] (any `KeyCreate`), [scriptPrompt] (what the user or the system does with the next
 * prompt). The JVM classes of §14.3 are not thrown here: `KeystoreErrors` (package 2.10) classifies
 * them and `KeystoreFailureAdapter` (package 3.9) produces them; the fakes start from the class.
 *
 * **Prompts.** By default a prompt is answered at once by [Prompt.Touch], an enrolled finger. With
 * [holdPrompts] a prompt stays open until the test calls [answer] or the code calls
 * `FingerprintKey.cancel()` (which answers with code 5, as `cancelAuthentication()` does); [openPrompt]
 * says whether one is open, and for which use and launch.
 *
 * **Logs.** [hmacCalls] (with the time from [now], so a test can check that no PIN was checked
 * without a durable charge one second old, lock.md I4), [prompts], [deleted], [availabilityCalls],
 * [cancels].
 *
 * It is not a fake of `KeystoreErrors`: a test of the error table uses that table. Calls are
 * synchronized; a held prompt is the only thing that suspends.
 */
class FakeKeys(
    private val random: RandomSource = FakeRandom(7),
    private val now: () -> Long = { 0L },
) {
    /** What the user or the system does with a fingerprint prompt (lock.md §9.3, §14.3). */
    sealed interface Prompt {
        /** An enrolled finger: the key operation runs, and its result is the prompt's. */
        data object Touch : Prompt

        /** The prompt is closed: 10 (Back or the X), 13 (the negative button) or 5 (cancelled): CLOSED. */
        data class Close(val code: Int) : Prompt

        /** An error such as 7 or 9 (lockout) or 1 (not allowed for apps): TEMPORARY with that code. */
        data class Error(val code: Int) : Prompt

        /** `onAuthenticationSucceeded`, then the key operation fails, or no cipher came back: TEMPORARY(null) (§9.3). */
        data object SucceededButFailed : Prompt

        /** The activity was not resumed, so no prompt was shown (§9.2 rule 3). */
        data object NotShown : Prompt
    }

    /** One `PinKey.hmac` call: the alias, a copy of the message, and when it was made by [now]. */
    class HmacCall(val alias: String, val message: ByteArray, val atMs: Long)

    /** One prompt: `seal` or `open`, the alias, what it was for and the launch number it carried. */
    data class PromptCall(val operation: String, val alias: String, val use: PromptUse, val launch: Int)

    private class FingerprintSecret(val key: ByteArray) {
        var invalidated = false
    }

    private val pinKeys = LinkedHashMap<String, ByteArray>()
    private val fingerprintKeys = LinkedHashMap<String, FingerprintSecret>()
    private val hmacScripts = ArrayDeque<Ks<Nothing>>()
    private val probeScripts = ArrayDeque<Ks<Nothing>>()
    private val createScripts = ArrayDeque<KeyCreate>()
    private val promptScripts = ArrayDeque<Prompt>()
    private val hmacLog = ArrayList<HmacCall>()
    private val promptLog = ArrayList<PromptCall>()
    private val deleteLog = ArrayList<String>()
    private var availabilityCount = 0
    private var cancelCount = 0
    private var held: CompletableDeferred<Prompt>? = null
    private var heldCall: PromptCall? = null

    /** Whether `pv_canary` exists. */
    @Volatile var canary: Boolean = false

    /** Whether a missing canary can be made now (lock.md §14.2). */
    @Volatile var canaryMakeable: Boolean = true

    /** False: the Keystore does not answer (every use TEMPORARY, every creation NOT NOW, no aliases listed). */
    @Volatile var answering: Boolean = true

    /** Every key would be made in software, in a release build: creation gives `NoSecureHardware` (lock.md §4.5, F18). */
    @Volatile var softwareOnly: Boolean = false

    /** Whether a finger is enrolled; without one, making a fingerprint key gives `NoneEnrolled`. */
    @Volatile var enrolled: Boolean = true

    /** What `FingerprintKey.availability()` answers: `canAuthenticate(BIOMETRIC_STRONG)` (lock.md §9.4). */
    @Volatile var availability: Int = 0

    /** Prompts stay open until [answer] or `cancel()`. */
    @Volatile var holdPrompts: Boolean = false

    /** Every `PinKey.hmac` call so far. */
    val hmacCalls: List<HmacCall> @Synchronized get() = hmacLog.toList()

    /** Every prompt so far. */
    val prompts: List<PromptCall> @Synchronized get() = promptLog.toList()

    /** Every alias deleted through `DeviceKeys.delete`, also when it did not exist; not while the Keystore did not answer. */
    val deleted: List<String> @Synchronized get() = deleteLog.toList()

    /** How many times `FingerprintKey.availability()` was asked (once per visit, lock.md §13.1). */
    val availabilityCalls: Int @Synchronized get() = availabilityCount

    /** How many times `FingerprintKey.cancel()` was called. */
    val cancels: Int @Synchronized get() = cancelCount

    /** The prompt that is open now while [holdPrompts], or null. */
    val openPrompt: PromptCall? @Synchronized get() = heldCall

    /** The aliases that exist now: `pv_pin_…` and `pv_fp_…`, in the order they were made. */
    val aliases: List<String> @Synchronized get() = pinKeys.keys.toList() + fingerprintKeys.keys.toList()

    // ---------------------------------------------------------------------------------------------
    // Scripts and events

    /** The next `PinKey.hmac` calls give these results, in order, before anything is computed. */
    @Synchronized
    fun scriptHmac(vararg results: Ks<Nothing>): FakeKeys = apply { hmacScripts.addAll(results) }

    /** The next `FingerprintKey.probe` calls give these results, in order. */
    @Synchronized
    fun scriptProbe(vararg results: Ks<Nothing>): FakeKeys = apply { probeScripts.addAll(results) }

    /** The next key creations (PIN or fingerprint; not the canary) give these results, in order; only `Ok` makes a key. */
    @Synchronized
    fun scriptCreate(vararg results: KeyCreate): FakeKeys = apply { createScripts.addAll(results) }

    /** The next prompts end like this, in order; without a script a prompt is [Prompt.Touch]. */
    @Synchronized
    fun scriptPrompt(vararg prompts: Prompt): FakeKeys = apply { promptScripts.addAll(prompts) }

    /** Answers the prompt that is open while [holdPrompts]. */
    fun answer(prompt: Prompt) {
        val deferred = synchronized(this) {
            val open = held ?: throw IllegalStateException("no prompt is open")
            held = null
            heldCall = null
            open
        }
        deferred.complete(prompt)
    }

    /** A finger was added, or every finger was removed: every fingerprint key is invalid from now on (lock.md F15a). */
    @Synchronized
    fun invalidateFingerprintKeys() {
        fingerprintKeys.values.forEach { it.invalidated = true }
    }

    /** Every key of the app is gone, the canary too: the `drop-keys` command of lock.md §15.3, or a reinstall without the keys. */
    @Synchronized
    fun dropAllKeys() {
        pinKeys.clear()
        fingerprintKeys.clear()
        canary = false
    }

    /** The software `K_pin` of [alias], so that a test can make a wrap by hand; null when there is none. */
    @Synchronized
    fun pinKeyOf(alias: String): ByteArray? = pinKeys[alias]?.copyOf()

    /** The software `K_fp` of [alias]; null when there is none. */
    @Synchronized
    fun fingerprintKeyOf(alias: String): ByteArray? = fingerprintKeys[alias]?.key?.copyOf()

    /** `HMAC(K_pin, message)` for an existing [alias], without logging a call or using a script: a test's own wrap (lock.md F6 step 5). */
    @Synchronized
    fun hmacOf(alias: String, message: ByteArray): ByteArray =
        hmacSha256(pinKeys[alias] ?: throw IllegalStateException("no such PIN key"), message)

    /** Puts a PIN key with the given bytes under [alias], such as the 42 × 32 stand-in of the vector of lock.md §4.3. */
    @Synchronized
    fun putPinKey(alias: String, key: ByteArray) {
        require(alias.startsWith(PIN_PREFIX)) { "a PIN alias starts with $PIN_PREFIX" }
        require(key.size == 32) { "a 256-bit key" }
        pinKeys[alias] = key.copyOf()
    }

    // ---------------------------------------------------------------------------------------------
    // The three ports

    /** `PinKey` over these keys. */
    val pin: PinKey = object : PinKey {
        override fun create(alias: String): KeyCreate {
            require(alias.startsWith(PIN_PREFIX)) { "a PIN alias starts with $PIN_PREFIX" }
            synchronized(this@FakeKeys) {
                val made = make { KeyCreate.Ok }
                if (made == KeyCreate.Ok) pinKeys[alias] = random.bytes(32)
                return made
            }
        }

        override fun hmac(alias: String, message: ByteArray): Ks<ByteArray> {
            synchronized(this@FakeKeys) {
                hmacLog += HmacCall(alias, message.copyOf(), now())
                hmacScripts.removeFirstOrNull()?.let { return it }
                if (!answering) return Ks.Temporary(null)
                val key = pinKeys[alias] ?: return missing()
                return Ks.Ok(hmacSha256(key, message))
            }
        }
    }

    /** `FingerprintKey` over these keys and the prompt scripts. */
    val fingerprint: FingerprintKey = object : FingerprintKey {
        override fun availability(): Int = synchronized(this@FakeKeys) {
            availabilityCount++
            availability
        }

        override fun create(alias: String): KeyCreate {
            require(alias.startsWith(FINGERPRINT_PREFIX)) { "a fingerprint alias starts with $FINGERPRINT_PREFIX" }
            synchronized(this@FakeKeys) {
                val made = make { if (enrolled) KeyCreate.Ok else KeyCreate.NoneEnrolled }
                if (made == KeyCreate.Ok) fingerprintKeys[alias] = FingerprintSecret(random.bytes(32))
                return made
            }
        }

        override fun probe(alias: String, nonce: ByteArray): Ks<Unit> {
            synchronized(this@FakeKeys) {
                probeScripts.removeFirstOrNull()?.let { return it }
                if (!answering) return Ks.Temporary(null)
                val secret = fingerprintKeys[alias] ?: return missing()
                return if (secret.invalidated) Ks.Gone("invalidated") else Ks.Ok(Unit)
            }
        }

        override suspend fun seal(alias: String, vk: ByteArray, use: PromptUse, launch: Int): FingerprintResult {
            val prompt = prompt(PromptCall("seal", alias, use, launch))
            return synchronized(this@FakeKeys) {
                finish(prompt, alias) { key ->
                    val iv = random.bytes(12)
                    FingerprintResult.Ok(aesGcm(Cipher.ENCRYPT_MODE, key, iv, vk), iv)
                }
            }
        }

        override suspend fun open(alias: String, nonce: ByteArray, ct: ByteArray, use: PromptUse, launch: Int): FingerprintResult {
            val prompt = prompt(PromptCall("open", alias, use, launch))
            return synchronized(this@FakeKeys) {
                finish(prompt, alias) { key ->
                    val vk = try {
                        aesGcm(Cipher.DECRYPT_MODE, key, nonce, ct)
                    } catch (e: GeneralSecurityException) { // AEADBadTagException above all: the wrap does not fit the key
                        return@finish FingerprintResult.Gone("bad_wrap")
                    }
                    if (vk.size != 32) FingerprintResult.Gone("bad_wrap") else FingerprintResult.Ok(vk, nonce.copyOf())
                }
            }
        }

        override fun cancel() {
            val open = synchronized(this@FakeKeys) {
                cancelCount++
                val deferred = held
                held = null
                heldCall = null
                deferred
            }
            open?.complete(Prompt.Close(5))
        }
    }

    /** `DeviceKeys`: the canary, the alias list and deletion. */
    val device: DeviceKeys = object : DeviceKeys {
        override fun ensureCanary(): KeyCreate = synchronized(this@FakeKeys) {
            when {
                !answering -> KeyCreate.NotNow
                canary -> KeyCreate.Ok
                softwareOnly -> KeyCreate.NoSecureHardware
                !canaryMakeable -> KeyCreate.NotNow
                else -> {
                    canary = true
                    KeyCreate.Ok
                }
            }
        }

        override fun aliases(): List<String> = synchronized(this@FakeKeys) { if (answering) aliases else emptyList() }

        override fun delete(alias: String) {
            synchronized(this@FakeKeys) {
                if (!answering) return // errors are ignored; the sweep tries again
                deleteLog += alias
                pinKeys.remove(alias)
                fingerprintKeys.remove(alias)
            }
        }
    }

    // ---------------------------------------------------------------------------------------------

    private inline fun make(ok: () -> KeyCreate): KeyCreate {
        createScripts.removeFirstOrNull()?.let { return it }
        return when {
            !answering -> KeyCreate.NotNow
            softwareOnly -> KeyCreate.NoSecureHardware
            else -> ok()
        }
    }

    /** lock.md §14.2: a key that is not there is GONE only when the canary answers, or can be made and then answers. */
    private fun missing(): Ks<Nothing> {
        if (!canary) {
            if (!canaryMakeable || softwareOnly) return Ks.Temporary(null)
            canary = true
        }
        return Ks.Gone("not found")
    }

    private suspend fun prompt(call: PromptCall): Prompt {
        val deferred = synchronized(this) {
            promptLog += call
            promptScripts.removeFirstOrNull()?.let { return it }
            if (!holdPrompts) return Prompt.Touch
            check(held == null) { "a prompt is already open" }
            CompletableDeferred<Prompt>().also {
                held = it
                heldCall = call
            }
        }
        return deferred.await()
    }

    private inline fun finish(prompt: Prompt, alias: String, operation: (ByteArray) -> FingerprintResult): FingerprintResult =
        when (prompt) {
            is Prompt.Close -> FingerprintResult.Closed(prompt.code)
            is Prompt.Error -> FingerprintResult.Temporary(prompt.code)
            Prompt.SucceededButFailed -> FingerprintResult.Temporary(null)
            Prompt.NotShown -> FingerprintResult.NotShown
            Prompt.Touch -> when {
                !answering -> FingerprintResult.Temporary(null)
                else -> {
                    val secret = fingerprintKeys[alias]
                    when {
                        secret == null -> when (val gone = missing()) {
                            is Ks.Gone -> FingerprintResult.Gone(gone.why)
                            else -> FingerprintResult.Temporary(null)
                        }
                        secret.invalidated -> FingerprintResult.Gone("invalidated")
                        else -> operation(secret.key)
                    }
                }
            }
        }

    private companion object {
        const val PIN_PREFIX = "pv_pin_"
        const val FINGERPRINT_PREFIX = "pv_fp_"

        fun hmacSha256(key: ByteArray, message: ByteArray): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key, "HmacSHA256"))
            return mac.doFinal(message)
        }

        fun aesGcm(mode: Int, key: ByteArray, iv: ByteArray, input: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            return cipher.doFinal(input)
        }
    }
}
