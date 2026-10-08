package io.github.dilshodbekmx.passwordvault.ui.settings

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.snapshots.Snapshot
import io.github.dilshodbekmx.passwordvault.core.lock.flow.MasterProof
import io.github.dilshodbekmx.passwordvault.fake.FakeLockManager
import io.github.dilshodbekmx.passwordvault.lock.LockManager
import io.github.dilshodbekmx.passwordvault.lock.PinChange
import io.github.dilshodbekmx.passwordvault.lock.PinSetResult

/**
 * A [LockManager] that is [fake] and also records the PINs and proofs handed to `setPin` and
 * `changePin`, as text, so that a test can say which PIN went where. Only tests may keep a PIN; the
 * app's own fake never does.
 */
class RecordingLock(val fake: FakeLockManager = FakeLockManager()) : LockManager by fake {
    /** Each `setPin` as the PIN's digits and the proof it came with. */
    val setPins = mutableListOf<Pair<String, MasterProof>>()

    /** Each `changePin` as the current PIN's digits and the new PIN's. */
    val changedPins = mutableListOf<Pair<String, String>>()

    override suspend fun setPin(pin: ByteArray, proof: MasterProof): PinSetResult {
        setPins += String(pin, Charsets.US_ASCII) to proof
        return fake.setPin(pin, proof)
    }

    override suspend fun changePin(current: ByteArray, new: ByteArray): PinChange {
        changedPins += String(current, Charsets.US_ASCII) to String(new, Charsets.US_ASCII)
        return fake.changePin(current, new)
    }
}

/** The digits of [pin], as the pad would send them one by one. */
fun digitsOf(pin: String): List<Int> {
    require(pin.length == 6 && pin.all { it in '0'..'9' })
    return pin.map { it - '0' }
}

/** Types [text] into the field, as the keyboard would. */
fun TextFieldState.type(text: String) {
    setTextAndPlaceCursorAtEnd(text)
    Snapshot.sendApplyNotifications()
}

/** PINs from lock.md §7.2 and the message group each one gets (§7.3). */
object Pins {
    /** Allowed by every rule (§7.2 "Allowed"). */
    const val ALLOWED = "582913"
    const val ALLOWED_2 = "905127"
    const val ALLOWED_3 = "368014"

    /** Rule 8, a date (DDMMYY): "looks like a date or a year". */
    const val DATE = "250890"

    /** Rule 9, a year: "looks like a date or a year". */
    const val YEAR = "451987"

    /** Rule 1, a repeat: "repeats digits or follows a pattern". */
    const val REPEAT = "111111"

    /** Rule 2, a pattern: "repeats digits or follows a pattern". */
    const val PATTERN = "121212"

    /** Rule 5, steps: "repeats digits or follows a pattern". */
    const val STEPS = "102030"

    /** Rule 3, a sequence: "is a sequence or a shape on the keypad". */
    const val SEQUENCE = "123456"

    /** Rule 4, keypad lines: "is a sequence or a shape on the keypad". */
    const val KEYPAD = "147258"

    /** Rule 6, a run of four: "is a sequence or a shape on the keypad". */
    const val RUN_OF_FOUR = "123457"

    /** Rule 7, a keypad path: "is a sequence or a shape on the keypad". */
    const val PATH = "147896"
}
