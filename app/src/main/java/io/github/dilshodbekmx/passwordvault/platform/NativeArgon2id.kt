package io.github.dilshodbekmx.passwordvault.platform

import com.lambdapioneer.argon2kt.Argon2Error
import com.lambdapioneer.argon2kt.Argon2Exception
import com.lambdapioneer.argon2kt.Argon2Kt
import com.lambdapioneer.argon2kt.Argon2Mode
import com.lambdapioneer.argon2kt.Argon2Version
import com.lambdapioneer.argon2kt.SoLoaderShim
import io.github.dilshodbekmx.passwordvault.core.crypto.Argon2Params
import io.github.dilshodbekmx.passwordvault.core.crypto.Argon2id
import io.github.dilshodbekmx.passwordvault.core.crypto.KdfUnavailable
import io.github.dilshodbekmx.passwordvault.core.util.Bytes
import java.nio.ByteBuffer

/**
 * Argon2id v1.3 through argon2kt 1.6.0: the reference C implementation over JNI, with its lanes on
 * threads and its work area in native memory, outside the Java heap (architecture.md §3.2,
 * crypto.md §4, [L-old §2]). It gives the bytes of `CryptoFactory.bouncyCastleArgon2id()`, the
 * fallback that `GuardedArgon2id` takes when this one is not there.
 *
 * **How argon2kt is called** [V-C1] (build check R-11):
 * - argon2kt hashes the whole *capacity* of the direct buffers it is given, not their position or
 *   limit (`Argon2Jni.cpp:33-39`). So the password and the salt each go into a direct buffer of
 *   exactly their size; a buffer with room to spare would hash the spare bytes too and give another
 *   key than Bouncy Castle.
 * - The hash comes back in native memory that argon2kt never zeroes or frees, and the encoded
 *   string, which contains the hash, in a buffer of its own. The key is copied out of the hash once,
 *   then both returned buffers are overwritten with zeros, and so are the password and salt buffers.
 *   argon2kt's two convenience methods, which would make more copies on the heap (the hash as an
 *   array, the encoded string as a `String`), are never called. One copy stays out of reach: the
 *   native buffer that argon2kt copied the encoded string out of before handing it over.
 * - A failure to allocate the work area or to start the lanes' threads (`ARGON2_MEMORY_ALLOCATION_ERROR`,
 *   `ARGON2_THREAD_FAIL`, `ARGON2JNI_MALLOC_FAILED`) is `KdfUnavailable`: not enough memory now,
 *   never a wrong password (lock.md §6.8). Every other argon2kt error is a programming error (bad
 *   parameters) and passes unchanged.
 *
 * **Loading** [V-C2]: loading `libargon2jni.so` fails on some phones with `UnsatisfiedLinkError`
 * (argon2kt issue #25). [load] catches any `Throwable` and gives null instead; it also gives null
 * when the library loaded but does not reproduce a small known answer, so that a broken build of the
 * library can never derive a wrong key (the vault would then open nowhere else). [loadedOnFirstUse]
 * defers all of that to the first derivation, which runs off the main thread behind the KDF gate.
 */
class NativeArgon2id private constructor(private val argon2: Argon2Kt) : Argon2id {

    override fun derive(password: ByteArray, salt: ByteArray, params: Argon2Params): ByteArray {
        val passwordBuffer = exactly(password)
        try {
            val saltBuffer = exactly(salt)
            try {
                val result = try {
                    argon2.hash(
                        mode = Argon2Mode.ARGON2_ID,
                        password = passwordBuffer,
                        salt = saltBuffer,
                        tCostInIterations = params.passes,
                        mCostInKibibyte = params.memoryKiB,
                        parallelism = params.lanes,
                        hashLengthInBytes = params.outLen,
                        version = Argon2Version.V13,
                    )
                } catch (e: Argon2Exception) {
                    throw failure(e)
                }
                return takeKey(result.rawHash, result.encodedOutput, params.outLen)
            } finally {
                wipe(saltBuffer)
            }
        } finally {
            wipe(passwordBuffer)
        }
    }

    /** Whether this library gives the known answer of [SELF_TEST_KEY]. */
    private fun passesSelfTest(): Boolean {
        val key = derive(SELF_TEST_PASSWORD.copyOf(), SELF_TEST_SALT.copyOf(), SELF_TEST_PARAMS)
        try {
            return Bytes.equal(key, SELF_TEST_KEY)
        } finally {
            Bytes.wipe(key)
        }
    }

    companion object {
        /**
         * The known answer every loaded library must give: Argon2id v1.3 of the 3 bytes `d0 8e 7a`
         * ("Ўz" in UTF-8, an odd length, so that the exact-size rule counts), salt `00 01 … 0f`,
         * 64 KiB, 2 passes, 4 lanes (as many as the vault's), 32 bytes. Computed with Bouncy Castle
         * 1.86 on JDK 25; the device tests check it against `CryptoFactory.bouncyCastleArgon2id()`.
         * It takes well under a millisecond.
         */
        internal val SELF_TEST_PASSWORD: ByteArray = Bytes.fromHex("d08e7a")
        internal val SELF_TEST_SALT: ByteArray = ByteArray(16) { it.toByte() }
        internal val SELF_TEST_PARAMS = Argon2Params(memoryKiB = 64, passes = 2, lanes = 4, outLen = 32)
        internal val SELF_TEST_KEY: ByteArray = Bytes.fromHex("8267579bfa1f132a6b1dda593e3eccf73e8636c7aca05b37cdfc4ef7bca8a9f9")

        /** argon2kt's codes for "the memory or the threads could not be had now". */
        private val OUT_OF_RESOURCES = setOf(
            Argon2Error.ARGON2_MEMORY_ALLOCATION_ERROR.errorCode,
            Argon2Error.ARGON2_THREAD_FAIL.errorCode,
            Argon2Error.ARGON2JNI_MALLOC_FAILED.errorCode,
        )

        /** argon2kt's message is "<error name> (<code>)". */
        private val TRAILING_CODE = Regex("""\((-?\d+)\)\s*$""")

        /**
         * argon2kt with its library loaded through [loadLibrary] (`System.loadLibrary` unless a test
         * passes another), or null when that threw anything at all [V-C2], or when the library does
         * not give the known answer. Never throws.
         */
        fun load(loadLibrary: (String) -> Unit = { System.loadLibrary(it) }): NativeArgon2id? = try {
            NativeArgon2id(Argon2Kt(Loader(loadLibrary))).takeIf { it.passesSelfTest() }
        } catch (t: Throwable) {
            null
        }

        /**
         * An [Argon2id] that runs [load] at its first derivation, on the thread that derives, and then
         * derives with what it loaded. When [load] gave null, that derivation and every later one
         * throw `UnsatisfiedLinkError`: the `LinkageError` that `GuardedArgon2id` takes to mean "the
         * native implementation is not there", after which it derives with the fallback, with its heap
         * check, for the rest of the process (architecture.md §3.2). So the library is never loaded on
         * the main thread, and a process that never derives never loads it.
         */
        fun loadedOnFirstUse(loadLibrary: (String) -> Unit = { System.loadLibrary(it) }): Argon2id = OnFirstUse(loadLibrary)

        /**
         * The key out of argon2kt's [rawHash], copied once, after which [rawHash] and [encoded] are
         * overwritten with zeros, whatever happens.
         */
        internal fun takeKey(rawHash: ByteBuffer, encoded: ByteBuffer, outLen: Int): ByteArray {
            try {
                check(rawHash.capacity() == outLen) { "argon2kt gave a hash of another length" }
                val key = ByteArray(outLen)
                for (i in 0 until outLen) key[i] = rawHash.get(i)
                return key
            } finally {
                wipe(rawHash)
                wipe(encoded)
            }
        }

        /**
         * What an argon2kt error becomes: `KdfUnavailable` when the memory or the threads could not
         * be had, otherwise the error itself.
         *
         * Only a failure that the allocator reports arrives here. Android 11 and later allocate with
         * scudo, which refuses a request it cannot meet; the Android 7 and 9 emulator images let the
         * kernel promise any amount (`vm.overcommit_memory` 1), and a work area larger than the free
         * memory is then found out only while Argon2 fills it, by the low-memory killer. No code can
         * catch that; `GuardedArgon2id`'s heap check protects the Java fallback, and argon2kt's 64 MiB
         * fit on every phone the app supports.
         */
        internal fun failure(e: Argon2Exception): Exception = if (errorCodeOf(e) in OUT_OF_RESOURCES) KdfUnavailable(e) else e

        /** A direct buffer whose capacity is exactly [bytes]: argon2kt hashes the whole capacity [V-C1]. */
        private fun exactly(bytes: ByteArray): ByteBuffer = ByteBuffer.allocateDirect(bytes.size).put(bytes)

        /** Zeros over the whole capacity, by absolute index, so the buffer's position does not matter. */
        private fun wipe(buffer: ByteBuffer) {
            for (i in 0 until buffer.capacity()) buffer.put(i, 0)
        }

        private fun errorCodeOf(e: Argon2Exception): Int? = TRAILING_CODE.find(e.message.orEmpty())?.groupValues?.get(1)?.toIntOrNull()
    }

    /** argon2kt's hook for loading its library. */
    private class Loader(private val load: (String) -> Unit) : SoLoaderShim {
        override fun loadLibrary(libname: String) = load(libname)
    }

    private class OnFirstUse(private val loadLibrary: (String) -> Unit) : Argon2id {
        private var tried = false
        private var native: NativeArgon2id? = null

        override fun derive(password: ByteArray, salt: ByteArray, params: Argon2Params): ByteArray {
            val loaded = synchronized(this) {
                if (!tried) {
                    native = load(loadLibrary)
                    tried = true
                }
                native
            } ?: throw UnsatisfiedLinkError("argon2kt is not available on this phone")
            return loaded.derive(password, salt, params)
        }
    }
}
