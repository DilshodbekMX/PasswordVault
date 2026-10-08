package io.github.dilshodbekmx.passwordvault.keystore

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.dilshodbekmx.passwordvault.core.lock.flow.DeviceKeys
import io.github.dilshodbekmx.passwordvault.core.lock.flow.FingerprintKey
import io.github.dilshodbekmx.passwordvault.core.lock.flow.PinKey
import io.github.dilshodbekmx.passwordvault.core.lock.flow.PromptUse
import io.github.dilshodbekmx.passwordvault.core.lock.policy.Ks
import io.github.dilshodbekmx.passwordvault.fake.FakeLauncher
import io.github.dilshodbekmx.passwordvault.platform.AppDispatchers
import io.github.dilshodbekmx.passwordvault.testing.lock.FakeKeys
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Build plan 3.9, [device], on API 24, 28 and 37: the real Keystore classes answer as `FakeKeys` of
 * `:testing` does. The lock engine is tested on the JVM against that fake (lock.md §15.1), so one
 * story is told here to both, through the three ports alone, and what each port answered must be
 * the same line for line: making and listing keys, a key that is used, a key that is gone with the
 * canary present and after every key was dropped, and what a prompt came to.
 */
@RunWith(AndroidJUnit4::class)
class FakeKeysParityTest {
    private val kit = KeystoreKit()

    @Before
    fun theDeviceIsUnlocked() = kit.requireUnlockedDevice()

    @After
    fun cleanUp() = kit.cleanUp()

    /** One Keystore behind the three ports, and what the story does to it from outside. */
    private class World(
        val device: DeviceKeys,
        val pin: PinKey,
        val fingerprint: FingerprintKey,
        /** The next prompt ends with `onAuthenticationError(code)`. */
        val promptEndsWith: (Int) -> Unit,
        /** The next prompt cannot be shown. */
        val promptIsNotShown: () -> Unit,
        /** The next prompt succeeds, and the key operation is then refused. */
        val promptSucceedsAndTheKeyRefuses: () -> Unit,
        /** Every key of the app goes, the canary too. */
        val dropAllKeys: () -> Unit,
    )

    private fun <T> Ks<T>.told(): String = when (this) {
        is Ks.Ok -> when (val v = value) {
            is ByteArray -> "Ok(${v.size} bytes)"
            else -> "Ok"
        }
        is Ks.Gone -> "Gone($why)"
        is Ks.Temporary -> "Temporary"
    }

    private fun story(world: World, pinAlias: String, otherPin: String, fingerprintAlias: String, otherFingerprint: String): List<String> = runBlocking {
        val told = ArrayList<String>()
        fun tell(what: String, answer: Any?) {
            told += "$what: $answer"
        }
        val mine = setOf(pinAlias, otherPin, fingerprintAlias, otherFingerprint)
        fun listed() = world.device.aliases().filter { it in mine }.sorted()
        val message = ByteArray(56) { it.toByte() }
        val nonce = ByteArray(12) { 3 }
        val wrap = ByteArray(48) { 9 }
        val vk = ByteArray(32) { 1 }

        tell("ensureCanary", world.device.ensureCanary())
        tell("ensureCanary again", world.device.ensureCanary())
        tell("aliases before", listed())
        tell("create pin", world.pin.create(pinAlias))
        tell("create fingerprint", world.fingerprint.create(fingerprintAlias))
        tell("aliases", listed())

        val first = world.pin.hmac(pinAlias, message)
        val second = world.pin.hmac(pinAlias, message)
        tell("hmac", first.told())
        tell("the same message twice", (first as Ks.Ok).value.contentEquals((second as Ks.Ok).value))
        tell("another message", first.value.contentEquals((world.pin.hmac(pinAlias, ByteArray(56)) as Ks.Ok).value))
        tell("hmac of a key that was never made", world.pin.hmac(otherPin, message).told())

        tell("probe", world.fingerprint.probe(fingerprintAlias, nonce).told())
        tell("probe of a key that was never made", world.fingerprint.probe(otherFingerprint, nonce).told())
        for (code in listOf(10, 13, 5, 7, 9, 1, 11)) {
            world.promptEndsWith(code)
            tell("open, the prompt ends with $code", world.fingerprint.open(fingerprintAlias, nonce, wrap, PromptUse.UNLOCK, code))
            world.promptEndsWith(code)
            tell("seal, the prompt ends with $code", world.fingerprint.seal(fingerprintAlias, vk, PromptUse.TURN_ON, code))
        }
        world.promptIsNotShown()
        tell("open, not shown", world.fingerprint.open(fingerprintAlias, nonce, wrap, PromptUse.UNLOCK, 20))
        world.promptSucceedsAndTheKeyRefuses()
        tell("open, success and then refused", world.fingerprint.open(fingerprintAlias, nonce, wrap, PromptUse.UNLOCK, 21))
        world.promptSucceedsAndTheKeyRefuses()
        tell("seal, success and then refused", world.fingerprint.seal(fingerprintAlias, vk, PromptUse.KEEP_AFTER_CHANGE, 22))
        tell("open with a key that was never made", world.fingerprint.open(otherFingerprint, nonce, wrap, PromptUse.UNLOCK, 23))
        tell("seal with a key that was never made", world.fingerprint.seal(otherFingerprint, vk, PromptUse.TURN_ON, 24))

        world.device.delete(pinAlias)
        tell("aliases after the PIN key was deleted", listed())
        tell("hmac after delete", world.pin.hmac(pinAlias, message).told())
        world.device.delete(pinAlias)
        tell("delete again", listed())
        tell("probe, untouched by the PIN's delete", world.fingerprint.probe(fingerprintAlias, nonce).told())

        world.dropAllKeys()
        tell("aliases after every key was dropped", listed())
        tell("hmac after the drop", world.pin.hmac(pinAlias, message).told())
        tell("probe after the drop", world.fingerprint.probe(fingerprintAlias, nonce).told())
        tell("the canary was made again on the way", world.device.ensureCanary())
        tell("a new PIN key", world.pin.create(otherPin))
        tell("its hmac", world.pin.hmac(otherPin, message).told())
        tell("aliases at the end", listed())
        told
    }

    @Test
    fun theRealKeystoreAnswersAsFakeKeysDoes() {
        val pinAlias = kit.pinAlias("11111111-1111-4111-8111-111111111111")
        val otherPin = kit.pinAlias("22222222-2222-4222-8222-222222222222")
        val fingerprintAlias = kit.fingerprintAlias("33333333-3333-4333-8333-333333333333")
        val otherFingerprint = kit.fingerprintAlias("44444444-4444-4444-8444-444444444444")

        val fake = FakeKeys()
        val fakeWorld = World(
            device = fake.device,
            pin = fake.pin,
            fingerprint = fake.fingerprint,
            promptEndsWith = { code ->
                fake.scriptPrompt(if (code == 10 || code == 13 || code == 5) FakeKeys.Prompt.Close(code) else FakeKeys.Prompt.Error(code))
            },
            promptIsNotShown = { fake.scriptPrompt(FakeKeys.Prompt.NotShown) },
            promptSucceedsAndTheKeyRefuses = { fake.scriptPrompt(FakeKeys.Prompt.SucceededButFailed) },
            dropAllKeys = { fake.dropAllKeys() },
        )

        kit.deleteCanary()
        val access = KeystoreAccess(allowSoftwareKeys = true)
        val launcher = FakeLauncher()
        val realWorld = World(
            device = access,
            pin = KeystorePinKey(access),
            fingerprint = KeystoreFingerprintKey(access, launcher, kit.context, AppDispatchers.Default) { true },
            promptEndsWith = { code -> launcher.error(code) },
            promptIsNotShown = { launcher.notShown() },
            // The real key needs a finger: after a "success" without one, its doFinal is refused.
            promptSucceedsAndTheKeyRefuses = { launcher.succeed() },
            dropAllKeys = { access.dropAllKeys() },
        )

        val expected = story(fakeWorld, pinAlias, otherPin, fingerprintAlias, otherFingerprint)
        val real = story(realWorld, pinAlias, otherPin, fingerprintAlias, otherFingerprint)
        assertEquals(expected.joinToString("\n"), real.joinToString("\n"))
        assertEquals("the story has its lines", 42, real.size)
    }
}
