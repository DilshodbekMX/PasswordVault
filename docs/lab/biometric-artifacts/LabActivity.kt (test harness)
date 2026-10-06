package com.example.pvlab.bio

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.security.keystore.KeyInfo
import android.util.Base64
import android.util.Log
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.example.pvlab.bio.lift.BiometricPromptLauncher
import com.example.pvlab.bio.lift.BiometricVaultKey
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec

/** Process-wide lab state, so it survives activity recreation. */
object Lab {
    const val TAG = "PVBIO"
    const val ACTION = "com.example.pvlab.bio.CMD"
    val lines = mutableStateListOf<String>()
    private val main = Handler(Looper.getMainLooper())
    var pendingOp: String? = null
    var pendingSince = 0L
    var lazyMode = false

    fun log(msg: String) {
        Log.i(TAG, msg)
        val add = {
            lines.add(0, msg)
            while (lines.size > 80) lines.removeAt(lines.lastIndex)
        }
        if (Looper.myLooper() == Looper.getMainLooper()) add() else main.post(add)
    }

    fun describe(t: Throwable?): String {
        if (t == null) return "null"
        val sb = StringBuilder()
        var cur: Throwable? = t
        var depth = 0
        while (cur != null && depth < 6) {
            if (depth > 0) sb.append(" <- caused by ")
            sb.append(cur.javaClass.name).append(": ").append(cur.message)
            if (Build.VERSION.SDK_INT >= 33 && cur is android.security.KeyStoreException) {
                sb.append(" [numericErrorCode=").append(cur.numericErrorCode)
                    .append(" transient=").append(cur.isTransientFailure)
                    .append(" requiresUserAuth=").append(cur.requiresUserAuthentication())
                    .append(" systemError=").append(cur.isSystemError)
                    .append(" retryPolicy=").append(cur.retryPolicy)
                    .append(" toString=").append(cur.toString()).append("]")
            }
            cur = cur.cause
            depth++
        }
        return sb.toString()
    }

    fun hex(b: ByteArray?): String = b?.joinToString("") { "%02x".format(it) } ?: "null"
    fun sha(b: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(b)).take(16)

    fun canAuthName(code: Int) = when (code) {
        BiometricManager.BIOMETRIC_SUCCESS -> "SUCCESS"
        BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> "HW_UNAVAILABLE"
        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> "NONE_ENROLLED"
        BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> "NO_HARDWARE"
        BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED -> "SECURITY_UPDATE_REQUIRED"
        BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED -> "UNSUPPORTED"
        BiometricManager.BIOMETRIC_STATUS_UNKNOWN -> "STATUS_UNKNOWN"
        else -> "?"
    }

    fun errName(code: Int) = when (code) {
        1 -> "ERROR_HW_UNAVAILABLE"; 2 -> "ERROR_UNABLE_TO_PROCESS"; 3 -> "ERROR_TIMEOUT"
        4 -> "ERROR_NO_SPACE"; 5 -> "ERROR_CANCELED"; 7 -> "ERROR_LOCKOUT"; 8 -> "ERROR_VENDOR"
        9 -> "ERROR_LOCKOUT_PERMANENT"; 10 -> "ERROR_USER_CANCELED"; 11 -> "ERROR_NO_BIOMETRICS"
        12 -> "ERROR_HW_NOT_PRESENT"; 13 -> "ERROR_NEGATIVE_BUTTON"; 14 -> "ERROR_NO_DEVICE_CREDENTIAL"
        15 -> "ERROR_SECURITY_UPDATE_REQUIRED"
        else -> "?"
    }
}

/** Test harness host. Driven by `am broadcast -a com.example.pvlab.bio.CMD --es cmd ...`. */
open class LabActivity : FragmentActivity() {
    private val vault = BiometricKeyVault()
    private var prompt: BiometricUnlockPrompt? = null
    private val fvault = BiometricVaultKey()
    private lateinit var flauncher: BiometricPromptLauncher
    private val instance get() = Integer.toHexString(System.identityHashCode(this))
    private val name get() = javaClass.simpleName
    private val prefs: SharedPreferences get() = getSharedPreferences("lab", MODE_PRIVATE)

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val to = intent.getStringExtra("to") ?: "MainActivity"
            if (to != name) return
            handle(intent.getStringExtra("cmd") ?: "", intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val orientation = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) "landscape" else "portrait"
        Lab.log("$name onCreate instance=$instance restored=${savedInstanceState != null} $orientation lazyMode=${Lab.lazyMode} pendingOp=${Lab.pendingOp}")
        if (!Lab.lazyMode) prompt = createPrompt()
        flauncher = createFinalLauncher()
        ContextCompat.registerReceiver(this, receiver, IntentFilter(Lab.ACTION), ContextCompat.RECEIVER_EXPORTED)
        setContent {
            val dark = isSystemInDarkTheme()
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                Scaffold(Modifier.fillMaxSize()) { padding ->
                    Column(Modifier.padding(padding).padding(12.dp)) {
                        Text("$name  biometric ${BuildConfig.BIOMETRIC_VERSION}  #$instance", style = MaterialTheme.typography.titleSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Button(onClick = { handle("status", null) }) { Text("Status") }
                            Button(onClick = { handle("create", null) }) { Text("Create") }
                            Button(onClick = { handle("enable", null) }) { Text("Enable") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Button(onClick = { handle("unlock", null) }) { Text("Unlock") }
                            Button(onClick = { handle("delete", null) }) { Text("Delete") }
                        }
                        LazyColumn {
                            items(Lab.lines) { Text(it, fontSize = 11.sp, lineHeight = 13.sp) }
                        }
                    }
                }
            }
        }
    }

    override fun onStart() { super.onStart(); Lab.log("$name onStart instance=$instance") }
    override fun onResume() { super.onResume(); Lab.log("$name onResume instance=$instance") }
    override fun onPause() { Lab.log("$name onPause instance=$instance"); super.onPause() }
    override fun onStop() { Lab.log("$name onStop instance=$instance"); super.onStop() }
    override fun onDestroy() {
        Lab.log("$name onDestroy instance=$instance changingConfigurations=$isChangingConfigurations")
        unregisterReceiver(receiver)
        super.onDestroy()
    }

    private fun createPrompt(): BiometricUnlockPrompt {
        val createdBy = instance
        return BiometricUnlockPrompt(
            this,
            onFailedAttempt = { Lab.log("callback onAuthenticationFailed (prompt of $createdBy, live instance $instance)") },
        ) { outcome ->
            val elapsed = SystemClock.elapsedRealtime() - Lab.pendingSince
            when (outcome) {
                is BiometricUnlockPrompt.Outcome.Error -> {
                    Lab.log("callback onAuthenticationError code=${outcome.code} ${Lab.errName(outcome.code)} msg='${outcome.message}' pendingOp=${Lab.pendingOp} prompt of $createdBy, destroyed=$isDestroyed, +${elapsed}ms")
                    Lab.pendingOp = null
                }
                is BiometricUnlockPrompt.Outcome.Success -> {
                    Lab.log("callback onAuthenticationSucceeded type=${outcome.authenticationType} pendingOp=${Lab.pendingOp} prompt of $createdBy, destroyed=$isDestroyed, +${elapsed}ms")
                    finishOperation(outcome.cipher)
                }
            }
        }
    }

    // ---- the liftable classes (com.example.pvlab.bio.lift) driven through f* commands ----

    private fun describe(r: BiometricVaultKey.Result<*>): String = when (r) {
        is BiometricVaultKey.Result.Ok -> "Ok"
        is BiometricVaultKey.Result.Gone -> "GONE(${r.reason}) ${Lab.describe(r.cause).take(300)}"
        is BiometricVaultKey.Result.Temporary -> "TEMPORARY(${r.reason}) ${Lab.describe(r.cause).take(300)}"
    }

    private fun createFinalLauncher() = BiometricPromptLauncher(this) { result ->
        val op = Lab.pendingOp
        Lab.pendingOp = null
        when (result) {
            is BiometricPromptLauncher.PromptResult.Closed ->
                Lab.log("final launcher Closed code=${result.code} ${Lab.errName(result.code)} userChoice=${result.userChoice} msg='${result.message}' op=$op")
            is BiometricPromptLauncher.PromptResult.Authenticated -> when (op) {
                "fenable" -> {
                    val secret = ByteArray(32).also { SecureRandom().nextBytes(it) }
                    when (val w = fvault.wrap(result.cipher, secret)) {
                        is BiometricVaultKey.Result.Ok -> {
                            prefs.edit()
                                .putString("f_wrapped", Base64.encodeToString(w.value.ciphertext, Base64.NO_WRAP))
                                .putString("f_iv", Base64.encodeToString(w.value.iv, Base64.NO_WRAP))
                                .putString("f_sha", Lab.sha(secret)).apply()
                            Lab.log("final fenable wrap Ok ${w.value.ciphertext.size}B iv=${Lab.hex(w.value.iv)}")
                        }
                        else -> Lab.log("final fenable wrap ${describe(w)}")
                    }
                }
                "funlock", "funlock_tampered" -> {
                    val blob = Base64.decode(prefs.getString("f_wrapped", null), Base64.NO_WRAP)
                    if (op == "funlock_tampered") blob[blob.size - 1] = (blob[blob.size - 1].toInt() xor 1).toByte()
                    when (val u = fvault.unwrap(result.cipher, blob)) {
                        is BiometricVaultKey.Result.Ok ->
                            Lab.log("final $op unwrap Ok ${if (Lab.sha(u.value) == prefs.getString("f_sha", null)) "MATCH" else "MISMATCH"}")
                        else -> Lab.log("final $op unwrap ${describe(u)}")
                    }
                }
                else -> Lab.log("final launcher Authenticated with no pending op ($op)")
            }
        }
    }

    private fun finalCommand(cmd: String) {
        when (cmd) {
            "fcreate" -> {
                prefs.edit().remove("f_wrapped").remove("f_iv").remove("f_sha").apply()
                Lab.log("final createKey ${describe(fvault.createKey())} canUseBiometrics=${BiometricVaultKey.canUseBiometrics(this)}")
            }
            "fenable" -> when (val r = fvault.cipherForEnable()) {
                is BiometricVaultKey.Result.Ok -> {
                    Lab.pendingOp = "fenable"
                    val shown = flauncher.launch(r.value, "Turn on fingerprint unlock", null, "Use password")
                    Lab.log("final fenable cipher Ok, launch()=$shown")
                    if (!shown) Lab.pendingOp = null
                }
                else -> Lab.log("final fenable cipher ${describe(r)}")
            }
            "funlock", "funlock_tampered" -> {
                val iv = prefs.getString("f_iv", null)?.let { Base64.decode(it, Base64.NO_WRAP) }
                if (iv == null) { Lab.log("final funlock: nothing stored"); return }
                when (val r = fvault.cipherForUnlock(iv)) {
                    is BiometricVaultKey.Result.Ok -> {
                        Lab.pendingOp = cmd
                        val shown = flauncher.launch(r.value, "Unlock vault", null, "Use password")
                        Lab.log("final $cmd cipher Ok, launch()=$shown")
                        if (!shown) Lab.pendingOp = null
                    }
                    else -> Lab.log("final $cmd cipher ${describe(r)}")
                }
            }
            "fdelete" -> { fvault.deleteKey(); Lab.log("final deleteKey done") }
        }
    }

    private fun promptForUse(): BiometricUnlockPrompt =
        if (Lab.lazyMode) createPrompt().also { prompt = it } else prompt!!

    private fun finishOperation(cipher: Cipher) {
        val op = Lab.pendingOp
        Lab.pendingOp = null
        try {
            when (op) {
                "enable" -> {
                    val secret = ByteArray(32).also { SecureRandom().nextBytes(it) }
                    val wrapped = cipher.doFinal(secret)
                    val iv = cipher.iv
                    prefs.edit()
                        .putString("wrapped", Base64.encodeToString(wrapped, Base64.NO_WRAP))
                        .putString("iv", Base64.encodeToString(iv, Base64.NO_WRAP))
                        .putString("sha", Lab.sha(secret))
                        .apply()
                    Lab.log("enable SUCCESS doFinal -> wrapped=${wrapped.size}B iv=${Lab.hex(iv)} (${iv.size}B) secretSha=${Lab.sha(secret)}")
                }
                "unlock", "unlock_tampered" -> {
                    val wrapped = Base64.decode(prefs.getString("wrapped", null), Base64.NO_WRAP)
                    if (op == "unlock_tampered") wrapped[0] = (wrapped[0].toInt() xor 1).toByte()
                    val plain = cipher.doFinal(wrapped)
                    val ok = Lab.sha(plain) == prefs.getString("sha", null)
                    Lab.log("$op doFinal -> ${plain.size}B ${if (ok) "MATCH" else "MISMATCH"} sha=${Lab.sha(plain)}")
                }
                else -> Lab.log("success with no pending op ($op)")
            }
        } catch (t: Throwable) {
            Lab.log("$op doFinal FAILED: ${Lab.describe(t)}")
        }
    }

    private fun handle(cmd: String, intent: Intent?) {
        Lab.log(">> cmd=$cmd on $name instance=$instance lifecycle=${lifecycle.currentState}")
        try {
            when (cmd) {
                "status" -> status()
                "keyinfo" -> keyInfo()
                "create" -> create(
                    strongBox = intent?.getBooleanExtra("strongbox", false) ?: false,
                    unlocked = intent?.getBooleanExtra("unlocked", false) ?: false,
                )
                "enable" -> enable()
                "unlock" -> unlock(tampered = false)
                "unlock_tampered" -> unlock(tampered = true)
                "noauth" -> noAuth()
                "calleriv" -> callerIv()
                "probe" -> probe()
                "delete" -> {
                    vault.deleteKey()
                    prefs.edit().clear().apply()
                    Lab.log("delete: hasKey=${vault.hasKey()}")
                }
                "cancel" -> { promptForUse().cancel(); Lab.log("cancelAuthentication() called") }
                "fcreate", "fenable", "funlock", "funlock_tampered", "fdelete" -> finalCommand(cmd)
                "mode" -> {
                    Lab.lazyMode = intent?.getBooleanExtra("lazy", false) ?: false
                    Lab.log("lazyMode=${Lab.lazyMode} (applies from next onCreate)")
                }
                else -> Lab.log("unknown cmd '$cmd'")
            }
        } catch (t: Throwable) {
            Lab.log("cmd $cmd threw ${Lab.describe(t)}")
        }
    }

    private fun status() {
        val bm = BiometricManager.from(this)
        fun ca(a: Int) = runCatching { bm.canAuthenticate(a) }
            .fold({ "$it(${Lab.canAuthName(it)})" }, { "threw ${Lab.describe(it)}" })
        val km = getSystemService(KeyguardManager::class.java)
        Lab.log(
            "status sdk=${Build.VERSION.SDK_INT} lib=${BuildConfig.BIOMETRIC_VERSION} canAuthenticate: STRONG=${ca(BIOMETRIC_STRONG)} " +
                "WEAK=${ca(BIOMETRIC_WEAK)} STRONG|DEVICE_CREDENTIAL=${ca(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)} " +
                "DEVICE_CREDENTIAL=${ca(DEVICE_CREDENTIAL)} isDeviceSecure=${km.isDeviceSecure} " +
                "hasKey=${vault.hasKey()} stored=${prefs.contains("wrapped")}",
        )
    }

    private fun keyInfo() {
        val key = try {
            vault.loadKey()
        } catch (t: Throwable) {
            Lab.log("keyinfo: getKey threw ${Lab.describe(t)}"); return
        }
        if (key == null) {
            Lab.log("keyinfo: getKey returned null, containsAlias=${vault.hasKey()}"); return
        }
        val ki = SecretKeyFactory.getInstance(key.algorithm, "AndroidKeyStore")
            .getKeySpec(key, KeyInfo::class.java) as KeyInfo
        val unlockedRequired = runCatching { KeyInfo::class.java.getMethod("isUnlockedDeviceRequired").invoke(ki) }
            .getOrElse { "n/a(${it.javaClass.simpleName})" }
        @Suppress("DEPRECATION")
        Lab.log(
            "keyinfo securityLevel=${if (Build.VERSION.SDK_INT >= 31) ki.securityLevel else "n/a"} " +
                "isInsideSecureHardware=${ki.isInsideSecureHardware} authRequired=${ki.isUserAuthenticationRequired} " +
                "authType=${if (Build.VERSION.SDK_INT >= 30) ki.userAuthenticationType else "n/a"} " +
                "validitySeconds=${ki.userAuthenticationValidityDurationSeconds} " +
                "invalidatedByBiometricEnrollment=${ki.isInvalidatedByBiometricEnrollment} " +
                "authEnforcedBySecureHw=${ki.isUserAuthenticationRequirementEnforcedBySecureHardware} " +
                "keySize=${ki.keySize} origin=${ki.origin} blockModes=${ki.blockModes.toList()} " +
                "paddings=${ki.encryptionPaddings.toList()} unlockedDeviceRequired=$unlockedRequired",
        )
    }

    private fun create(strongBox: Boolean, unlocked: Boolean) {
        vault.deleteKey()
        prefs.edit().clear().apply()
        val t0 = SystemClock.elapsedRealtime()
        try {
            vault.createKey(strongBox = strongBox, unlockedDeviceRequired = unlocked)
            Lab.log("create OK strongBox=$strongBox unlockedDeviceRequired=$unlocked in ${SystemClock.elapsedRealtime() - t0}ms")
            keyInfo()
        } catch (t: Throwable) {
            Lab.log("create FAILED strongBox=$strongBox unlockedDeviceRequired=$unlocked: ${Lab.describe(t)}")
        }
    }

    private fun logCipherResult(what: String, r: BiometricKeyVault.CipherResult): Cipher? = when (r) {
        is BiometricKeyVault.CipherResult.Ready -> {
            Lab.log("$what: Cipher.init OK (no auth yet), cipher.iv=${Lab.hex(r.cipher.iv)}"); r.cipher
        }
        is BiometricKeyVault.CipherResult.KeyGone -> {
            Lab.log("$what: KEY_GONE ${Lab.describe(r.cause)}"); null
        }
        is BiometricKeyVault.CipherResult.TemporaryFailure -> {
            Lab.log("$what: TEMPORARY_FAILURE ${Lab.describe(r.cause)}"); null
        }
    }

    private fun enable() {
        val cipher = logCipherResult("enable", vault.cipherForEnable()) ?: return
        Lab.pendingOp = "enable"
        Lab.pendingSince = SystemClock.elapsedRealtime()
        promptForUse().launch(cipher, "Turn on fingerprint unlock", "Confirm with your fingerprint", "Use password")
        Lab.log("enable: authenticate() called")
    }

    private fun unlock(tampered: Boolean) {
        val ivB64 = prefs.getString("iv", null) ?: run { Lab.log("unlock: nothing stored"); return }
        val cipher = logCipherResult("unlock", vault.cipherForUnlock(Base64.decode(ivB64, Base64.NO_WRAP))) ?: return
        Lab.pendingOp = if (tampered) "unlock_tampered" else "unlock"
        Lab.pendingSince = SystemClock.elapsedRealtime()
        promptForUse().launch(cipher, "Unlock vault", "Touch the fingerprint sensor", "Use password")
        Lab.log("unlock: authenticate() called")
    }

    /** Case 2: init without a prompt, then doFinal without authentication. */
    private fun noAuth() {
        logCipherResult("noauth ENCRYPT", vault.cipherForEnable())?.let { c ->
            try {
                val out = c.doFinal(ByteArray(32))
                Lab.log("noauth ENCRYPT doFinal WITHOUT auth SUCCEEDED (${out.size}B) - unexpected")
            } catch (t: Throwable) {
                Lab.log("noauth ENCRYPT doFinal without auth threw ${Lab.describe(t)}")
            }
        }
        val ivB64 = prefs.getString("iv", null) ?: return
        logCipherResult("noauth DECRYPT", vault.cipherForUnlock(Base64.decode(ivB64, Base64.NO_WRAP)))?.let { c ->
            try {
                c.doFinal(Base64.decode(prefs.getString("wrapped", null), Base64.NO_WRAP))
                Lab.log("noauth DECRYPT doFinal WITHOUT auth SUCCEEDED - unexpected")
            } catch (t: Throwable) {
                Lab.log("noauth DECRYPT doFinal without auth threw ${Lab.describe(t)}")
            }
        }
    }

    /** Case 3: a caller-supplied IV for encryption. */
    private fun callerIv() {
        val key = vault.loadKey() ?: run { Lab.log("calleriv: no key"); return }
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance(BiometricKeyVault.TRANSFORMATION)
        try {
            c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
            Lab.log("calleriv: init with caller IV SUCCEEDED (iv=${Lab.hex(c.iv)}) - unexpected")
        } catch (t: Throwable) {
            Lab.log("calleriv: init with caller IV threw ${Lab.describe(t)}")
        }
    }

    /** Init both ciphers without prompting; usable while the activity is stopped or the screen is locked. */
    private fun probe() {
        logCipherResult("probe ENCRYPT", vault.cipherForEnable())
        val ivB64 = prefs.getString("iv", null)
        if (ivB64 != null) logCipherResult("probe DECRYPT", vault.cipherForUnlock(Base64.decode(ivB64, Base64.NO_WRAP)))
        keyInfo()
    }
}

class MainActivity : LabActivity()

class PlatformThemeActivity : LabActivity()
