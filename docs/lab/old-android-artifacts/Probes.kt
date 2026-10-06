package com.example.pvlab.old

import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.PersistableBundle
import android.os.SystemClock
import com.lambdapioneer.argon2kt.Argon2Kt
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.SecureRandom
import java.text.Normalizer
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

@Serializable
data class VaultEntry(
    val id: String,
    val title: String,
    val username: String = "",
    val password: String = "",
    val tags: List<String> = emptyList(),
)

@Serializable
data class VaultFile(val version: Int, val entries: List<VaultEntry>)

/** Device-side checks. Every result goes through [log]; nothing here touches the UI. */
class Probes(private val context: Context, private val log: (String) -> Unit) {

    private val password = "correct horse battery staple".toByteArray(Charsets.UTF_8)
    private val salt = ByteArray(16) { it.toByte() } // 00 01 .. 0f

    fun memory(): String {
        val am = context.getSystemService(ActivityManager::class.java)
        val rt = Runtime.getRuntime()
        val mib = 1024 * 1024
        val largeHeapFlag = (context.applicationInfo.flags and ApplicationInfo.FLAG_LARGE_HEAP) != 0
        return "maxMemory=${rt.maxMemory() / mib}MiB memoryClass=${am.memoryClass} " +
            "largeMemoryClass=${am.largeMemoryClass} isLowRamDevice=${am.isLowRamDevice} " +
            "manifestLargeHeap=$largeHeapFlag usedHeap=${(rt.totalMemory() - rt.freeMemory()) / mib}MiB"
    }

    fun info() {
        log("info: sdk=${Build.VERSION.SDK_INT} release=${Build.VERSION.RELEASE} model=${Build.MODEL} " +
            "manufacturer=${Build.MANUFACTURER} abis=${Build.SUPPORTED_ABIS.joinToString()}")
        log("info: biometric=${BuildConfig.BIOMETRIC_VERSION} core=${BuildConfig.CORE_VERSION} " +
            "largeHeapBuild=${BuildConfig.LARGE_HEAP}")
        log("info: ${memory()}")
    }

    // ---------------------------------------------------------------- 2. key derivation
    fun kdf() {
        val p = Argon2Params(memKiB = 65536, iterations = 3, lanes = 4, outLen = 32)
        try {
            Argon2Kt() // loads libargon2jni.so
            log("kdf: argon2kt native library loaded OK")
        } catch (t: Throwable) {
            log("kdf: argon2kt load FAILED: $t")
        }
        for (round in 1..2) {
            var nativeHex = "n/a"
            var bcHex = "n/a"
            try {
                val t0 = SystemClock.elapsedRealtime()
                nativeHex = Kdf.native(password, salt, p).hex()
                log("kdf: round=$round argon2kt m=${p.memKiB} t=${p.iterations} p=${p.lanes} " +
                    "-> $nativeHex in ${SystemClock.elapsedRealtime() - t0} ms, " +
                    "matchesExpected=${nativeHex == EXPECTED}")
            } catch (t: Throwable) {
                log("kdf: round=$round argon2kt FAILED: $t")
            }
            try {
                val t0 = SystemClock.elapsedRealtime()
                bcHex = Kdf.bouncy(password, salt, p).hex()
                log("kdf: round=$round bouncycastle m=${p.memKiB} t=${p.iterations} p=${p.lanes} " +
                    "-> $bcHex in ${SystemClock.elapsedRealtime() - t0} ms, " +
                    "matchesExpected=${bcHex == EXPECTED}")
            } catch (t: Throwable) {
                log("kdf: round=$round bouncycastle FAILED: $t")
            }
            log("kdf: round=$round nativeEqualsBouncy=${nativeHex == bcHex}")
        }
        log("kdf: ${memory()}")
    }

    /** Bouncy Castle at 64 MiB, [parallelRuns] derivations at the same time, in a fresh process. */
    fun bouncy64(parallelRuns: Int) {
        log("bc64: before ${memory()}")
        val p = Argon2Params()
        val threads = (1..parallelRuns).map { n ->
            Thread {
                val t0 = SystemClock.elapsedRealtime()
                try {
                    val hex = Kdf.bouncy(password, salt, p).hex()
                    log("bc64: run $n/$parallelRuns OK in ${SystemClock.elapsedRealtime() - t0} ms " +
                        "matchesExpected=${hex == EXPECTED}; ${memory()}")
                } catch (e: OutOfMemoryError) {
                    log("bc64: run $n/$parallelRuns OutOfMemoryError after " +
                        "${SystemClock.elapsedRealtime() - t0} ms: ${e.message}")
                }
            }.apply { start() }
        }
        threads.forEach { it.join() }
        val t0 = SystemClock.elapsedRealtime()
        try {
            val hex = Kdf.native(password, salt, p).hex()
            log("bc64: argon2kt (native) same params OK in ${SystemClock.elapsedRealtime() - t0} ms " +
                "matchesExpected=${hex == EXPECTED}; ${memory()}")
        } catch (t: Throwable) {
            log("bc64: argon2kt FAILED: $t")
        }
    }

    // ---------------------------------------------------------------- 4. storage
    fun storage() {
        val dir = context.filesDir
        log("storage: filesDir=${dir.path}")
        val target = File(dir, "vault.bin")
        target.delete()
        for (gen in 1..2) {
            val data = "generation $gen ".repeat(64).toByteArray()
            try {
                AtomicFiles.write(dir, "vault.bin", data)
                val back = target.readBytes()
                log("storage: gen=$gen write+fd.sync()+Os.rename+dir fsync OK; " +
                    "readBack=${back.contentEquals(data)} size=${back.size} " +
                    "tmpLeft=${File(dir, "vault.bin.tmp").exists()}")
            } catch (t: Throwable) {
                log("storage: gen=$gen FAILED: $t")
            }
        }
        // Each primitive separately, so a failure points at the exact call.
        try {
            val f = File(dir, "probe.tmp")
            java.io.FileOutputStream(f).use { it.write(1); it.fd.sync() }
            log("storage: FileOutputStream.fd.sync() OK")
            android.system.Os.rename(f.path, File(dir, "probe.bin").path)
            log("storage: Os.rename OK (exists=${File(dir, "probe.bin").exists()})")
            val fd = android.system.Os.open(dir.path, android.system.OsConstants.O_RDONLY, 0)
            log("storage: Os.open(dir, O_RDONLY) OK valid=${fd.valid()}")
            android.system.Os.fsync(fd)
            log("storage: Os.fsync(dirFd) OK")
            android.system.Os.close(fd)
            log("storage: Os.close(dirFd) OK")
        } catch (t: Throwable) {
            log("storage: primitive FAILED: $t")
        }
    }

    // ---------------------------------------------------------------- 5. clipboard
    fun clipboard() {
        val cm = context.getSystemService(ClipboardManager::class.java)
        fun state(tag: String) = log("clip: $tag -> hasPrimaryClip=${cm.hasPrimaryClip()} " +
            "text=${SensitiveClipboard.peek(context)?.let { "\"$it\"" }} " +
            "label=${cm.primaryClipDescription?.label?.let { "\"$it\"" }} " +
            "extras=${cm.primaryClipDescription?.extras}")

        cm.setPrimaryClip(ClipData.newPlainText("pw", "plain-secret-1"))
        state("after plain copy")
        cm.setPrimaryClip(ClipData.newPlainText("", ""))
        state("after empty-clip overwrite")

        try {
            SensitiveClipboard.copy(context, "pw", "sensitive-secret-2")
            state("after copy with IS_SENSITIVE extra")
        } catch (t: Throwable) {
            log("clip: copy with IS_SENSITIVE extra FAILED: $t")
        }
        SensitiveClipboard.clear(context)
        state("after SensitiveClipboard.clear (sdk ${Build.VERSION.SDK_INT})")

        // What happens if code calls clearPrimaryClip() without an SDK check:
        cm.setPrimaryClip(ClipData.newPlainText("pw", "plain-secret-3"))
        try {
            cm.clearPrimaryClip()
            state("after unguarded clearPrimaryClip()")
        } catch (t: Throwable) {
            log("clip: unguarded clearPrimaryClip() threw $t")
            cm.setPrimaryClip(ClipData.newPlainText("", ""))
        }
        // Sensitive extra on a clip that also has an extra of our own
        try {
            val clip = ClipData.newPlainText("pw", "sensitive-secret-4")
            clip.description.extras = PersistableBundle().apply {
                putBoolean(SensitiveClipboard.EXTRA_IS_SENSITIVE, true)
                putString("com.example.pvlab.TAG", "x")
            }
            cm.setPrimaryClip(clip)
            state("after copy with two extras")
        } catch (t: Throwable) {
            log("clip: two extras FAILED: $t")
        }
        SensitiveClipboard.clear(context)
        state("final clear")
    }

    // ---------------------------------------------------------------- 6. platform crypto and text
    fun platformCrypto() {
        try {
            val rng = SecureRandom()
            val key = ByteArray(32).also { rng.nextBytes(it) }
            val iv = ByteArray(12).also { rng.nextBytes(it) }
            log("crypto: SecureRandom algorithm=${rng.algorithm} provider=${rng.provider.name} " +
                "sample=${ByteArray(8).also { rng.nextBytes(it) }.hex()}")
            val aad = "vault-header-v1".toByteArray()
            val plain = "pässwörd ✓ 🔑".toByteArray(Charsets.UTF_8)
            val enc = Cipher.getInstance("AES/GCM/NoPadding")
            enc.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            enc.updateAAD(aad)
            val ct = enc.doFinal(plain)
            val dec = Cipher.getInstance("AES/GCM/NoPadding")
            dec.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            dec.updateAAD(aad)
            val back = dec.doFinal(ct)
            log("crypto: AES-256-GCM+AAD provider=${enc.provider.name} ctLen=${ct.size} " +
                "(plain ${plain.size} + 16 tag) roundTrip=${back.contentEquals(plain)}")
            val bad = Cipher.getInstance("AES/GCM/NoPadding")
            bad.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            bad.updateAAD("vault-header-v2".toByteArray())
            try {
                bad.doFinal(ct)
                log("crypto: wrong AAD was ACCEPTED (bad)")
            } catch (e: AEADBadTagException) {
                log("crypto: wrong AAD rejected with AEADBadTagException (good)")
            }
        } catch (t: Throwable) {
            log("crypto: AES-GCM FAILED: $t")
        }
        try {
            val decomposed = "Café Å" // e + combining acute, Angstrom sign
            val nfc = Normalizer.normalize(decomposed, Normalizer.Form.NFC)
            log("crypto: Normalizer NFC ok=${nfc == "Café Å"} " +
                "codepoints=${nfc.codePoints().toArray().joinToString(",") { "%04x".format(it) }}")
        } catch (t: Throwable) {
            log("crypto: Normalizer FAILED: $t")
        }
        try {
            val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
            val vault = VaultFile(
                version = 1,
                entries = listOf(
                    VaultEntry("1", "Mail \"work\"", "me@example.com", "pä\$\$\\w0rd\n✓", listOf("a", "b")),
                ),
            )
            val text = json.encodeToString(VaultFile.serializer(), vault)
            val back = json.decodeFromString(VaultFile.serializer(), text.replace("{\"version\":1", "{\"version\":1,\"future\":true"))
            log("crypto: kotlinx-serialization json=$text roundTrip=${back == vault}")
        } catch (t: Throwable) {
            log("crypto: kotlinx-serialization FAILED: $t")
        }
    }

    // ---------------------------------------------------------------- 7. extra API probes
    fun extras() {
        fun probe(name: String, block: () -> String) {
            try {
                log("extra: $name -> OK ${block()}")
            } catch (t: Throwable) {
                log("extra: $name -> ${t.javaClass.name}: ${t.message}")
            }
        }
        probe("java.util.Base64") { java.util.Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3)) }
        probe("android.util.Base64") {
            android.util.Base64.encodeToString(byteArrayOf(1, 2, 3), android.util.Base64.NO_WRAP)
        }
        probe("kotlin.io.encoding.Base64") {
            @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
            kotlin.io.encoding.Base64.Default.encode(byteArrayOf(1, 2, 3))
        }
        probe("java.time.Instant.now") { java.time.Instant.now().toString() }
        probe("SecureRandom.getInstanceStrong") { SecureRandom.getInstanceStrong().algorithm }
        probe("PBKDF2WithHmacSHA256") {
            val f = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            val spec = javax.crypto.spec.PBEKeySpec("pw".toCharArray(), salt, 1000, 256)
            f.generateSecret(spec).encoded.hex().take(16)
        }
        probe("PBKDF2WithHmacSHA1") {
            val f = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
            val spec = javax.crypto.spec.PBEKeySpec("pw".toCharArray(), salt, 1000, 256)
            f.generateSecret(spec).encoded.hex().take(16)
        }
        probe("ChaCha20-Poly1305") { Cipher.getInstance("ChaCha20/Poly1305/NoPadding").provider.name }
        probe("java.nio.file.Files.move ATOMIC_MOVE") {
            val a = File(context.filesDir, "nio-a.tmp").apply { writeText("x") }
            val b = File(context.filesDir, "nio-b.bin")
            java.nio.file.Files.move(
                a.toPath(), b.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            ).toString()
        }
        probe("android.util.AtomicFile") {
            val af = android.util.AtomicFile(File(context.filesDir, "atomic.bin"))
            val out = af.startWrite()
            out.write(byteArrayOf(1, 2, 3))
            af.finishWrite(out) // fsyncs the data, then renames
            "readBack=${af.readFully().size} bytes"
        }
        probe("MessageDigest SHA-256") {
            java.security.MessageDigest.getInstance("SHA-256").digest("abc".toByteArray()).hex().take(16)
        }
        probe("Mac HmacSHA256") {
            val mac = javax.crypto.Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(ByteArray(32), "HmacSHA256"))
            mac.doFinal("abc".toByteArray()).hex().take(16)
        }
        probe("String.repeat (Java 11)") { "ab".repeat(3) }
        probe("List.of (Java 9)") { java.util.List.of(1, 2, 3).toString() }
        probe("CharArray wipe via Arrays.fill") {
            val c = "secret".toCharArray(); java.util.Arrays.fill(c, '\u0000'); "wiped=${c.all { it == '\u0000' }}"
        }
    }

    companion object {
        const val EXPECTED = "853b272a44db1421c02962669a55eb0994f3cab385ed1c4c79253eee19bab49e"
    }
}
