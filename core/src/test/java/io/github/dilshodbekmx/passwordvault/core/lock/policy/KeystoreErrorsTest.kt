package io.github.dilshodbekmx.passwordvault.core.lock.policy

import io.github.dilshodbekmx.passwordvault.core.lock.flow.FingerprintLine
import io.github.dilshodbekmx.passwordvault.core.lock.flow.KeyCreate
import io.github.dilshodbekmx.passwordvault.core.lock.policy.KeystoreErrors.Callback
import io.github.dilshodbekmx.passwordvault.core.lock.policy.KeystoreErrors.CreateFailure
import io.github.dilshodbekmx.passwordvault.core.lock.policy.KeystoreErrors.Lookup
import io.github.dilshodbekmx.passwordvault.core.lock.policy.KeystoreErrors.PinWraps
import io.github.dilshodbekmx.passwordvault.core.lock.policy.KeystoreErrors.Place
import io.github.dilshodbekmx.passwordvault.core.lock.policy.KeystoreErrors.Response
import io.github.dilshodbekmx.passwordvault.core.lock.policy.KeystoreFailure.BadTag
import io.github.dilshodbekmx.passwordvault.core.lock.policy.KeystoreFailure.NullKey
import io.github.dilshodbekmx.passwordvault.core.lock.policy.KeystoreFailure.Other
import io.github.dilshodbekmx.passwordvault.core.lock.policy.KeystoreFailure.OtherInvalidKey
import io.github.dilshodbekmx.passwordvault.core.lock.policy.KeystoreFailure.PermanentlyInvalidated
import io.github.dilshodbekmx.passwordvault.core.lock.policy.KeystoreFailure.UnrecoverableNoCause
import io.github.dilshodbekmx.passwordvault.core.lock.policy.KeystoreFailure.UnrecoverableWithCause
import io.github.dilshodbekmx.passwordvault.core.lock.policy.KeystoreFailure.UserNotAuthenticated
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * lock.md test T13, the error table: every row of §14.3; null with the canary present is GONE;
 * null with the canary missing and not makeable is TEMPORARY; null with the canary missing, then
 * made and loaded, is GONE; `UnrecoverableKeyException` with and without a cause; a callback with
 * no launch outstanding is dropped.
 *
 * Rows tested elsewhere: "Reading a record" and "Writing a record" in `FileRecordStoreTest`; the
 * Argon2id row in the tests of `GuardedArgon2id` (package 2.1). `deleteEntry` failures are ignored
 * by `DeviceKeys.delete` (package 3.9); nothing in :core decides anything about them.
 */
class KeystoreErrorsTest {
    private val cause = IllegalStateException("from the platform")

    // ------------------------------------------------------------------ GONE only on the platform's word

    @Test
    fun t13_goneOnlyOnThePlatformsOwnWord() {
        val gone = mutableListOf<String>()
        for (place in Place.entries) {
            for (failure in KeystoreFailure.entries) {
                when (val ks = KeystoreErrors.classify(place, failure, cause)) {
                    is Ks.Gone -> gone += "$place $failure ${ks.why}"
                    is Ks.Temporary -> assertSame("$place $failure keeps its cause", cause, ks.cause)
                    is Ks.Ok -> throw AssertionError("a failure was OK")
                }
            }
        }
        assertEquals(
            listOf(
                "GET_KEY UnrecoverableNoCause invalidated",
                "INIT PermanentlyInvalidated invalidated",
                "FINGERPRINT_DO_FINAL BadTag bad_wrap",
            ),
            gone,
        )
    }

    @Test
    fun t13_keystoreLoadAnyExceptionIsTemporary() {
        for (failure in KeystoreFailure.entries) assertTemporary(KeystoreErrors.classify(Place.KEYSTORE_LOAD, failure, cause))
    }

    @Test
    fun t13_initPermanentlyInvalidatedIsGone() {
        assertEquals(Ks.Gone("invalidated"), KeystoreErrors.classify(Place.INIT, PermanentlyInvalidated, cause))
    }

    @Test
    fun t13_initUserNotAuthenticatedIsTemporary() {
        assertTemporary(KeystoreErrors.classify(Place.INIT, UserNotAuthenticated, cause))
    }

    @Test
    fun t13_initAnyOtherInvalidKeyExceptionSuchAsDeviceLockedIsTemporary() {
        assertTemporary(KeystoreErrors.classify(Place.INIT, OtherInvalidKey, cause))
    }

    @Test
    fun t13_initAnyOtherExceptionIsTemporary() {
        for (failure in listOf(Other, BadTag, NullKey, UnrecoverableNoCause, UnrecoverableWithCause)) {
            assertTemporary(KeystoreErrors.classify(Place.INIT, failure, cause))
        }
    }

    @Test
    fun t13_macDoFinalAnyExceptionIsTemporary() {
        for (failure in KeystoreFailure.entries) assertTemporary(KeystoreErrors.classify(Place.MAC_DO_FINAL, failure, cause))
    }

    // ------------------------------------------------------------------ getKey: lock.md §14.2

    /** A Keystore that answers each `getKey` from a script, and counts the canary it is asked to make. */
    private class Script(vararg answers: Pair<String, Lookup<String>>, val canMake: Boolean = true) {
        private val queue = ArrayDeque(answers.toList())
        val asked = mutableListOf<String>()
        var made = 0

        fun getKey(alias: String): Lookup<String> {
            asked += alias
            val (expected, answer) = queue.removeFirstOrNull() ?: throw AssertionError("an unexpected getKey($alias)")
            assertEquals("the order of §14.2", expected, alias)
            return answer
        }

        fun makeCanary(): Boolean {
            asked += "make canary"
            made++
            return canMake
        }

        fun load(alias: String = KEY): Ks<String> = KeystoreErrors.load(alias, ::getKey, ::makeCanary)
    }

    @Test
    fun t13_getKeyAKeyIsOk() {
        val script = Script(KEY to Lookup.Found("k"))
        assertEquals(Ks.Ok("k"), script.load())
        assertEquals(listOf(KEY), script.asked)
    }

    @Test
    fun t13_unrecoverableKeyExceptionWithoutACauseIsGoneAndWithACauseTemporary() {
        assertEquals(Ks.Gone("invalidated"), Script(KEY to Lookup.Failed(UnrecoverableNoCause)).load())
        val withCause = Script(KEY to Lookup.Failed(UnrecoverableWithCause, cause))
        assertEquals(Ks.Temporary(cause), withCause.load())
        assertEquals(listOf(KEY), withCause.asked) // the canary is not asked
    }

    @Test
    fun t13_anyOtherExceptionFromGetKeyIsTemporary() {
        for (failure in listOf(Other, OtherInvalidKey, UserNotAuthenticated, PermanentlyInvalidated, BadTag)) {
            assertEquals(Ks.Temporary(cause), Script(KEY to Lookup.Failed(failure, cause)).load())
        }
    }

    @Test
    fun t13_nullWithTheCanaryPresentIsGone() {
        val script = Script(KEY to NULL, CANARY to Lookup.Found("canary"), KEY to NULL)
        assertEquals(Ks.Gone("not found"), script.load())
        assertEquals(listOf(KEY, CANARY, KEY), script.asked)
        assertEquals(0, script.made)
    }

    @Test
    fun t13_nullThenTheKeyAfterAllIsOk() {
        // On API 24 to 30 getKey gives null for any passing error: asked again, it is there.
        val script = Script(KEY to NULL, CANARY to Lookup.Found("canary"), KEY to Lookup.Found("k"))
        assertEquals(Ks.Ok("k"), script.load())
    }

    @Test
    fun t13_nullWithTheCanaryMissingAndNotMakeableIsTemporary() {
        val script = Script(KEY to NULL, CANARY to NULL, canMake = false)
        assertEquals(Ks.Temporary(null), script.load())
        assertEquals(listOf(KEY, CANARY, "make canary"), script.asked)
    }

    @Test
    fun t13_nullWithTheCanaryMissingThenMadeAndLoadedIsGone() {
        // a reinstall that kept the data but not the keys, or a Keystore that was reset (F15b, D-21)
        val script = Script(KEY to NULL, CANARY to NULL, CANARY to Lookup.Found("new canary"), KEY to NULL)
        assertEquals(Ks.Gone("not found"), script.load())
        assertEquals(listOf(KEY, CANARY, "make canary", CANARY, KEY), script.asked)
        assertEquals(1, script.made)
    }

    @Test
    fun t13_aCanaryThatIsMadeButDoesNotLoadMeansTheKeystoreIsNotAnswering() {
        assertEquals(Ks.Temporary(null), Script(KEY to NULL, CANARY to NULL, CANARY to NULL).load())
        assertEquals(Ks.Temporary(cause), Script(KEY to NULL, CANARY to NULL, CANARY to Lookup.Failed(Other, cause)).load())
        assertEquals(
            Ks.Temporary(cause),
            Script(KEY to NULL, CANARY to NULL, CANARY to Lookup.Failed(UnrecoverableNoCause, cause)).load(),
        )
    }

    @Test
    fun t13_anyFailureOfTheCanaryOrOfTheSecondLookupIsTemporary() {
        for (failure in KeystoreFailure.entries - NullKey) {
            assertEquals("canary $failure", Ks.Temporary(cause), Script(KEY to NULL, CANARY to Lookup.Failed(failure, cause)).load())
            assertEquals(
                "again $failure",
                Ks.Temporary(cause),
                Script(KEY to NULL, CANARY to Lookup.Found("c"), KEY to Lookup.Failed(failure, cause)).load(),
            )
        }
    }

    @Test
    fun t13_classifyingANullOnItsOwnIsADoubt() {
        // Without the canary nothing can be concluded from null: TEMPORARY, never GONE.
        assertTemporary(KeystoreErrors.classify(Place.GET_KEY, NullKey, cause))
    }

    // ------------------------------------------------------------------ the PIN wraps

    @Test
    fun t13_everyTagFailingIsWrongAndOneOpeningIsRight() {
        assertEquals(PinWraps.WRONG, KeystoreErrors.pinWraps(0))
        assertEquals(PinWraps.RIGHT, KeystoreErrors.pinWraps(1)) // the failing one of two is dropped
        assertEquals(PinWraps.RIGHT, KeystoreErrors.pinWraps(2))
    }

    // ------------------------------------------------------------------ the fingerprint

    @Test
    fun t13_succeededWithoutACipherIsTemporaryAndNeverUnlocked() {
        assertEquals(Response.Temporary(FingerprintLine.DidNotOpen, hidesKey = false), KeystoreErrors.respond(Callback.Succeeded(false), true))
        assertEquals(Response.UseCipher, KeystoreErrors.respond(Callback.Succeeded(true), true))
    }

    @Test
    fun t13_fingerprintDoFinalOf32BytesOpensTheVault() {
        val vk = ByteArray(32) { it.toByte() }
        val ok = KeystoreErrors.fingerprintOutput(vk) as Ks.Ok
        assertSame(vk, ok.value)
    }

    @Test
    fun t13_fingerprintDoFinalBadTagIsGoneBadWrap() {
        assertEquals(Ks.Gone("bad_wrap"), KeystoreErrors.classify(Place.FINGERPRINT_DO_FINAL, BadTag, cause))
    }

    @Test
    fun t13_aFingerprintResultThatIsNot32BytesIsGoneBadWrapAndIsWiped() {
        for (size in listOf(0, 16, 31, 33, 48)) {
            val out = ByteArray(size) { 7 }
            assertEquals(Ks.Gone("bad_wrap"), KeystoreErrors.fingerprintOutput(out))
            assertArrayEquals(ByteArray(size), out)
        }
    }

    @Test
    fun t13_anyOtherFingerprintDoFinalExceptionIsTemporary() {
        // "Key user not authenticated" after the phone's PIN on the lockout screen [L-bio finding b]
        for (failure in KeystoreFailure.entries - BadTag) {
            assertTemporary(KeystoreErrors.classify(Place.FINGERPRINT_DO_FINAL, failure, cause))
        }
    }

    @Test
    fun t13_userCancelledNegativeButtonAndCancelledAreClosed() {
        for (code in listOf(10, 13, 5)) assertEquals("code $code", Response.Closed, KeystoreErrors.promptError(code))
    }

    @Test
    fun t13_lockoutCodes7And9AreTemporaryWithTheLockoutText() {
        for (code in listOf(7, 9)) {
            assertEquals(Response.Temporary(FingerprintLine.Locked, hidesKey = false), KeystoreErrors.promptError(code))
            assertEquals(FingerprintLine.Locked, KeystoreErrors.temporaryLine(code))
        }
    }

    @Test
    fun t13_hardwareUnavailableCode1IsTemporaryWithTheFingerprintForAppsText() {
        assertEquals(Response.Temporary(FingerprintLine.NotForApps, hidesKey = false), KeystoreErrors.promptError(1))
    }

    @Test
    fun t13_everyOtherPromptErrorIsTemporaryAndHidesTheKeyForTheVisit() {
        for (code in listOf(11, 12, 2, 3, 4, 8, 14, 15, 0, -1, 6, 16, 21, 99, Int.MAX_VALUE, Int.MIN_VALUE)) {
            assertEquals("code $code", Response.Temporary(FingerprintLine.CannotCheck, hidesKey = true), KeystoreErrors.promptError(code))
            assertTrue(KeystoreErrors.hidesKey(code))
        }
    }

    @Test
    fun t13_aCallbackWithNoLaunchOutstandingIsDropped() {
        // whatever it says: the late code 10 of a prompt the system closed [L-bio case 4] among them
        val callbacks = listOf(Callback.Succeeded(true), Callback.Succeeded(false), Callback.NotRecognised) +
            (listOf(1, 5, 7, 9, 10, 11, 13, 99).map { Callback.Error(it) })
        for (callback in callbacks) assertEquals("$callback", Response.Dropped, KeystoreErrors.respond(callback, launchOutstanding = false))
        assertEquals(Response.Closed, KeystoreErrors.respond(Callback.Error(10), launchOutstanding = true))
    }

    @Test
    fun t13_canAuthenticateOtherThan0OrMinus1LeavesTheKeyUnprobedAndTheSlotAlone() {
        assertTrue(KeystoreErrors.mayProbe(0))
        assertTrue(KeystoreErrors.mayProbe(-1))
        for (value in listOf(1, 11, 12, -2, 15, 99)) assertFalse("$value", KeystoreErrors.mayProbe(value))
        // and the status line says why (lock.md §13.3)
        assertEquals(null, KeystoreErrors.unavailableLine(0))
        assertEquals(null, KeystoreErrors.unavailableLine(-1))
        assertEquals(FingerprintLine.NotForApps, KeystoreErrors.unavailableLine(1))
        assertEquals(FingerprintLine.NoneSaved, KeystoreErrors.unavailableLine(11))
        assertEquals(FingerprintLine.UpdateNeeded, KeystoreErrors.unavailableLine(15))
        for (value in listOf(12, -2, 2, 99)) assertEquals("$value", FingerprintLine.CannotCheck, KeystoreErrors.unavailableLine(value))
    }

    @Test
    fun t13_aFingerNotRecognisedLeavesThePromptOpen() {
        assertEquals(Response.StaysOpen, KeystoreErrors.respond(Callback.NotRecognised, true))
    }

    @Test
    fun theLinesOfTemporaryFingerprintResults() {
        assertEquals(FingerprintLine.DidNotOpen, KeystoreErrors.temporaryLine(null))
        assertFalse(KeystoreErrors.hidesKey(null)) // a new prompt works (§9.3)
        assertEquals(FingerprintLine.NotForApps, KeystoreErrors.temporaryLine(1))
        assertEquals(FingerprintLine.CannotCheck, KeystoreErrors.temporaryLine(11))
        assertFalse(KeystoreErrors.hidesKey(7))
        assertFalse(KeystoreErrors.hidesKey(10)) // a closed prompt is no failure
    }

    // ------------------------------------------------------------------ making a key

    @Test
    fun t13_makingAKeyWithNoBiometricEnrolledIsNotNowWithItsText() {
        assertEquals(KeyCreate.NoneEnrolled, KeystoreErrors.createFailed(CreateFailure.NO_BIOMETRIC_ENROLLED))
    }

    @Test
    fun t13_anyOtherExceptionMakingAKeyIsNotNow() {
        assertEquals(KeyCreate.NotNow, KeystoreErrors.createFailed(CreateFailure.OTHER))
    }

    @Test
    fun t13_aKeyInSoftwareIsNotNowForGoodInReleaseBuilds() {
        assertEquals(KeyCreate.NoSecureHardware, KeystoreErrors.created(insideSecureHardware = false, allowSoftware = false))
        assertEquals(KeyCreate.Ok, KeystoreErrors.created(insideSecureHardware = false, allowSoftware = true)) // debug, staging
        assertEquals(KeyCreate.Ok, KeystoreErrors.created(insideSecureHardware = true, allowSoftware = false))
        assertEquals(KeyCreate.Ok, KeystoreErrors.created(insideSecureHardware = true, allowSoftware = true))
    }

    private fun assertTemporary(ks: Ks<Nothing>) {
        assertEquals(Ks.Temporary(cause), ks)
    }

    private companion object {
        const val KEY = "pv_pin_7b0c1f8e-0b9a-4d27-9e3a-55c6f6d1a2b4"
        const val CANARY = "pv_canary"
        val NULL: Lookup<String> = Lookup.Failed(NullKey)
    }
}
