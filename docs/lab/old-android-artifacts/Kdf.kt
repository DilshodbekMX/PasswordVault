package com.example.pvlab.old

import com.lambdapioneer.argon2kt.Argon2Kt
import com.lambdapioneer.argon2kt.Argon2Mode
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters

/** Argon2id cost parameters: m = memKiB (total, in KiB), t = iterations, p = lanes. */
data class Argon2Params(
    val memKiB: Int = 65536,
    val iterations: Int = 3,
    val lanes: Int = 4,
    val outLen: Int = 32,
)

object Kdf {
    /**
     * argon2kt: the reference C implementation over JNI (libargon2jni.so). The m KiB work area is
     * allocated natively, so it does not count against the Java heap limit.
     */
    fun native(password: ByteArray, salt: ByteArray, p: Argon2Params): ByteArray =
        Argon2Kt().hash(
            mode = Argon2Mode.ARGON2_ID,
            password = password,
            salt = salt,
            tCostInIterations = p.iterations,
            mCostInKibibyte = p.memKiB,
            parallelism = p.lanes,
            hashLengthInBytes = p.outLen,
        ).rawHashAsByteArray()

    /**
     * Bouncy Castle (pure Java). The m KiB work area is allocated on the Java heap
     * (65536 KiB is about 66 MiB of long[] blocks), and lanes are computed one after another.
     */
    fun bouncy(password: ByteArray, salt: ByteArray, p: Argon2Params): ByteArray {
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withSalt(salt)
            .withMemoryAsKB(p.memKiB)
            .withIterations(p.iterations)
            .withParallelism(p.lanes)
            .build()
        val out = ByteArray(p.outLen)
        val generator = Argon2BytesGenerator()
        generator.init(params)
        generator.generateBytes(password, out)
        return out
    }
}

fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
