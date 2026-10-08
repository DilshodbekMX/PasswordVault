package io.github.dilshodbekmx.passwordvault.keystore

import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.UserNotAuthenticatedException
import java.security.InvalidKeyException
import java.security.ProviderException
import java.util.concurrent.atomic.AtomicReference

/**
 * The Keystore faults that a test can ask for: `ks-fault <row of 14.3>` of lock.md §15.3, for check
 * E10 ("`ks-fault` for every row of 14.3 through the real adapter: the listed class"), and the
 * device tests of this package. One fault is armed at a time, by the name of its [Row]; the next
 * Keystore call that reaches the row's place meets it, once, and the fault is then spent. A fault
 * is thrown, or answered, at the place where the platform would have done it, so it takes the same
 * way through [KeystoreFailureAdapter] and the table of `KeystoreErrors` as a real one.
 *
 * Nothing in a release build arms a fault. The app's only caller will be `LockDebug.ksFault` (build
 * plan 4.1), behind the debug commands of the `debug` and `staging` source sets (build check R-2);
 * and the app's own faults are made with [testBuild] false in a release build, where [arm] refuses
 * every name. While nothing is armed, each place costs one read of a field.
 *
 * `KeystoreAccess.faults` is the one object that the three Keystore classes of the app share:
 * `(graph.keys.device as KeystoreAccess).faults`. No fault makes anything open: each one fails a
 * call, or (`getkey-null-once`) leaves it as it was.
 *
 * @param testBuild whether faults can be armed at all, and whether `KeystoreAccess.dropAllKeys`
 *   works: true in the builds that carry the debug commands, which are the builds that allow
 *   software keys (architecture.md §7.2), and in tests.
 */
class KeystoreFaults(val testBuild: Boolean = true) {
    /**
     * The rows of lock.md §14.3 (and the cases of §14.2) that the Keystore classes can be made to
     * meet. [id] is the name `ks-fault` takes. Each row says where it strikes, what happens there,
     * and the class that lock.md lists for it.
     */
    enum class Row(val id: String) {
        /** `KeyStore.getInstance("AndroidKeyStore").load(null)` throws: TEMPORARY for a key that is used, NOT NOW for one that is made, no alias listed, nothing deleted. */
        KEYSTORE_LOAD("keystore-load"),

        /** `getKey` gives null for the key both times it is asked, while the canary answers as it really does (§14.2): GONE, "not found". */
        GET_KEY_NULL("getkey-null"),

        /** The first `getKey` gives null and the second finds the key (§14.2): a passing null is not believed, and the key is used. */
        GET_KEY_NULL_ONCE("getkey-null-once"),

        /** `getKey` gives null for the key and for the canary, and no canary can be made (§14.2): the Keystore is not answering, TEMPORARY. */
        GET_KEY_NOTHING_ANSWERS("getkey-nothing-answers"),

        /** `getKey` throws `UnrecoverableKeyException` without a cause: GONE, "invalidated". */
        GET_KEY_UNRECOVERABLE("getkey-unrecoverable"),

        /** `getKey` throws `UnrecoverableKeyException` with a cause: TEMPORARY. */
        GET_KEY_UNRECOVERABLE_CAUSE("getkey-unrecoverable-cause"),

        /** `getKey` throws anything else: TEMPORARY. */
        GET_KEY_OTHER("getkey-other"),

        /** `Mac.init` or `Cipher.init` throws `KeyPermanentlyInvalidatedException`: GONE, "invalidated". */
        INIT_INVALIDATED("init-invalidated"),

        /** `Mac.init` or `Cipher.init` throws `UserNotAuthenticatedException`: TEMPORARY. */
        INIT_NOT_AUTHENTICATED("init-not-authenticated"),

        /** `Mac.init` or `Cipher.init` throws another `InvalidKeyException`, as "Device locked" does on API 35 and newer: TEMPORARY, never GONE. */
        INIT_INVALID_KEY("init-invalid-key"),

        /** `Mac.init` or `Cipher.init` throws anything else: TEMPORARY. */
        INIT_OTHER("init-other"),

        /** `Mac.doFinal` of the PIN key throws: TEMPORARY, and the try is refunded. */
        MAC_DO_FINAL("mac-dofinal"),

        /** `onAuthenticationSucceeded` without a cipher: TEMPORARY, never "unlocked". */
        PROMPT_NO_CIPHER("prompt-no-cipher"),

        /** The fingerprint `doFinal` that opens a wrap throws `AEADBadTagException`: GONE, "bad_wrap". It waits for an unlock; making a wrap does not meet it. */
        FINGERPRINT_BAD_TAG("fp-dofinal-bad-tag"),

        /** The fingerprint `doFinal` that opens a wrap gives a result that is not 32 bytes: GONE, "bad_wrap". It waits for an unlock too. */
        FINGERPRINT_WRONG_SIZE("fp-dofinal-wrong-size"),

        /** The fingerprint `doFinal` throws anything else, as "Key user not authenticated" does: TEMPORARY, when a wrap is opened and when one is made. */
        FINGERPRINT_OTHER("fp-dofinal-other"),

        /** Making a key throws `InvalidAlgorithmParameterException`: NOT NOW, and for a fingerprint key "Add a fingerprint in your phone's settings first." */
        CREATE_NONE_ENROLLED("create-none-enrolled"),

        /** Making a key fails after the key was generated: NOT NOW, and the half-made alias is deleted. */
        CREATE_OTHER("create-other"),

        /** The key that is made is judged as a release build judges a key in software: NOT NOW for good, and the key is deleted (lock.md F18). */
        CREATE_SOFTWARE("create-software"),

        /** `deleteEntry` throws: ignored, the alias stays, and the sweep tries again. */
        DELETE("delete"),
    }

    /** What is armed: a [row], or the prompt error [promptError]. */
    private class Armed(val row: Row?, val promptError: Int?) {
        val name: String get() = row?.id ?: (PROMPT_ERROR + promptError)
    }

    private val armed = AtomicReference<Armed?>(null)

    /**
     * Arms the fault named [row] in place of the one that was armed: the [Row.id] of a row, or
     * `prompt-error-<code>` for a fingerprint prompt that ends at once with `onAuthenticationError`
     * [code] without being shown (10, 13 and 5 are CLOSED, every other code TEMPORARY). False, with
     * nothing changed, when no fault has that name, and always in a build that is not a [testBuild].
     */
    fun arm(row: String): Boolean {
        if (!testBuild) return false
        val next = Row.entries.firstOrNull { it.id == row }?.let { Armed(it, null) }
            ?: promptErrorOf(row)?.let { Armed(null, it) }
            ?: return false
        armed.set(next)
        return true
    }

    /** Forgets the armed fault. */
    fun disarm() {
        armed.set(null)
    }

    /** The name of the fault that is armed and has not struck yet, or null. */
    val pending: String? get() = armed.get()?.name

    /** True, once, when [row] is armed: the caller now does what the row says. */
    internal fun take(row: Row): Boolean {
        while (true) {
            val now = armed.get() ?: return false
            if (now.row != row) return false
            if (armed.compareAndSet(now, null)) return true
        }
    }

    /** The armed row when it is one of [rows], once; otherwise null. */
    internal fun takeOneOf(rows: Set<Row>): Row? {
        while (true) {
            val now = armed.get() ?: return null
            val row = now.row
            if (row == null || row !in rows) return null
            if (armed.compareAndSet(now, null)) return row
        }
    }

    /** The code of an armed `prompt-error-<code>`, once; otherwise null. */
    internal fun takePromptError(): Int? {
        while (true) {
            val now = armed.get() ?: return null
            val code = now.promptError ?: return null
            if (armed.compareAndSet(now, null)) return code
        }
    }

    /** Throws what [thrown] makes, once, when [row] is armed. */
    internal inline fun throwIf(row: Row, thrown: () -> Exception) {
        if (take(row)) throw thrown()
    }

    /** Throws the fault that is armed for `Mac.init` and `Cipher.init`, once, if one is. */
    internal fun throwAtInit() {
        when (takeOneOf(INIT_ROWS)) {
            Row.INIT_INVALIDATED -> throw KeyPermanentlyInvalidatedException(INJECTED)
            Row.INIT_NOT_AUTHENTICATED -> throw UserNotAuthenticatedException(INJECTED)
            Row.INIT_INVALID_KEY -> throw InvalidKeyException(INJECTED)
            Row.INIT_OTHER -> throw ProviderException(INJECTED)
            else -> Unit
        }
    }

    companion object {
        /** `prompt-error-<code>`: the prefix of the prompt faults. */
        const val PROMPT_ERROR = "prompt-error-"

        /** The message of every injected exception: a fixed text, as no exception of the app carries input (architecture.md §5.6). */
        internal const val INJECTED = "injected fault"

        /** The names [arm] takes, apart from `prompt-error-<code>`: the [Row.id]s in the order of the table. */
        val rows: List<String> get() = Row.entries.map { it.id }

        private fun promptErrorOf(name: String): Int? {
            if (!name.startsWith(PROMPT_ERROR)) return null
            val digits = name.substring(PROMPT_ERROR.length)
            // Plain ASCII digits only, and few of them: the codes of BiometricPrompt are small numbers.
            if (digits.isEmpty() || digits.length > MAX_CODE_DIGITS || !digits.all { it in '0'..'9' }) return null
            return digits.toInt()
        }

        private const val MAX_CODE_DIGITS = 4
        private val INIT_ROWS = setOf(Row.INIT_INVALIDATED, Row.INIT_NOT_AUTHENTICATED, Row.INIT_INVALID_KEY, Row.INIT_OTHER)
    }
}
