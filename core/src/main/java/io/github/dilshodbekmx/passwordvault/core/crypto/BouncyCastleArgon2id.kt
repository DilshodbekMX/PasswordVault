package io.github.dilshodbekmx.passwordvault.core.crypto

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters

/**
 * Argon2id v1.3 in Bouncy Castle (architecture.md §3.2): the implementation of the JVM tests and, on a
 * phone, the fallback when argon2kt did not load [V-C2]. It gives the bytes of the reference
 * implementation (`Argon2KnownAnswerTest`). It runs the lanes one after another and keeps its whole
 * work area on the Java heap: about 70 MiB for [Argon2Params.V1] on Android 9 and about 134 MiB on
 * Android 7 [L-old §2]. So on a phone it runs only behind `GuardedArgon2id`, which checks the free
 * heap first, and one derivation at a time (`GatedMasterKdf`). `CryptoFactory.bouncyCastleArgon2id()`
 * makes it.
 *
 * Every call makes its own generator. Bouncy Castle zeroes the work area when the derivation ends,
 * and the 64 MiB become garbage with the generator when the call returns; a generator kept between
 * calls would keep them.
 *
 * Refused with `IllegalArgumentException` before anything is allocated, as the reference
 * implementation (and so argon2kt) refuses them: less than 8 bytes of salt, less than 4 bytes of
 * output, no pass, a number of lanes outside 1 to 2^24 − 1, and less than 8 KiB of memory per lane,
 * which Bouncy Castle would otherwise raise silently and so derive another key than argon2kt.
 */
internal class BouncyCastleArgon2id : Argon2id {

    override fun derive(password: ByteArray, salt: ByteArray, params: Argon2Params): ByteArray {
        require(salt.size >= MIN_SALT_BYTES) { "Argon2id takes at least 8 bytes of salt" }
        require(params.outLen >= MIN_OUT_BYTES) { "Argon2id gives at least 4 bytes" }
        require(params.passes >= 1) { "Argon2id takes at least one pass" }
        require(params.lanes in 1..MAX_LANES) { "Argon2id takes 1 to 2^24 - 1 lanes" }
        require(params.memoryKiB >= MIN_KIB_PER_LANE * params.lanes) { "Argon2id takes at least 8 KiB per lane" }
        val parameters = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withSalt(salt)
            .withMemoryAsKB(params.memoryKiB)
            .withIterations(params.passes)
            .withParallelism(params.lanes)
            .build()
        val out = ByteArray(params.outLen)
        Argon2BytesGenerator().apply { init(parameters) }.generateBytes(password, out)
        return out
    }

    private companion object {
        const val MIN_SALT_BYTES = 8
        const val MIN_OUT_BYTES = 4
        const val MAX_LANES = 0xFFFFFF
        const val MIN_KIB_PER_LANE = 8
    }
}
