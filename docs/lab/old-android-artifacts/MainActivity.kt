package com.example.pvlab.old

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.util.Base64
import android.util.Log
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.NavDisplay
import java.util.concurrent.Executors
import javax.crypto.Cipher

const val TAG = "PVLAB"

sealed interface LabKey {
    data object Home : LabKey
    data object Detail : LabKey
}

/**
 * Drive with: adb shell am start -n com.example.pvlab.old/.MainActivity --es run <action>
 * (or .PlatformThemeActivity). Actions: info kdf bc64 bc64x2 storage clip clip-bg crypto extras
 * all bio-status bio-keygen bio-noauth bio-enc bio-dec secure-on secure-off
 */
open class LabActivity : FragmentActivity() {
    protected open val hostLabel = "AppCompat"
    private val lines = mutableStateListOf<String>()
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var probes: Probes
    private lateinit var prompt: BiometricPrompt
    private var onCipherReady: ((Cipher) -> Unit)? = null

    fun log(msg: String) {
        Log.i(TAG, "[$hostLabel] $msg")
        main.post { lines.add(0, msg) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        probes = Probes(applicationContext, ::log)
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                Log.i(TAG, "process went to background")
            }
        })
        // BiometricPrompt must be created before the activity is started (here in onCreate).
        prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                val cipher = result.cryptoObject?.cipher
                log("bio: onAuthenticationSucceeded type=${result.authenticationType} hasCipher=${cipher != null}")
                val action = onCipherReady
                onCipherReady = null
                if (cipher != null && action != null) {
                    try {
                        action(cipher)
                    } catch (t: Throwable) {
                        log("bio: using the cipher after success FAILED: $t")
                    }
                }
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                log("bio: onAuthenticationError code=$errorCode msg=\"$errString\"")
                onCipherReady = null
            }

            override fun onAuthenticationFailed() {
                log("bio: onAuthenticationFailed (finger not recognised, prompt stays up)")
            }
        })
        setContent { LabApp(title(), lines) }
        log("onCreate host=$hostLabel sdk=${Build.VERSION.SDK_INT} biometric=${BuildConfig.BIOMETRIC_VERSION} " +
            "core=${BuildConfig.CORE_VERSION} largeHeap=${BuildConfig.LARGE_HEAP}")
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    private fun title() = "API ${Build.VERSION.SDK_INT} | host theme: $hostLabel | biometric ${BuildConfig.BIOMETRIC_VERSION}"

    private fun handle(intent: Intent?) {
        val run = intent?.getStringExtra("run") ?: return
        log("run=$run")
        when (run) {
            "info" -> worker.execute { probes.info() }
            "kdf" -> worker.execute { probes.kdf() }
            "bc64" -> worker.execute { probes.bouncy64(1) }
            "bc64x2" -> worker.execute { probes.bouncy64(2) }
            "storage" -> worker.execute { probes.storage() }
            "clip" -> probes.clipboard()
            "clip-bg" -> clipboardFromBackground()
            "crypto" -> worker.execute { probes.platformCrypto() }
            "extras" -> worker.execute { probes.extras() }
            "all" -> {
                worker.execute { probes.info(); probes.kdf(); probes.storage(); probes.platformCrypto(); probes.extras() }
                probes.clipboard()
            }
            "bio-status" -> bioStatus()
            "bio-keygen" -> bioKeygen()
            "bio-noauth" -> bioNoAuth()
            "bio-enc" -> bioEncrypt()
            "bio-dec" -> bioDecrypt()
            "secure-on" -> { window.addFlags(WindowManager.LayoutParams.FLAG_SECURE); log("FLAG_SECURE on") }
            "secure-off" -> { window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE); log("FLAG_SECURE off") }
            else -> log("unknown action $run")
        }
    }

    private fun clipboardFromBackground() {
        SensitiveClipboard.copy(this, "pw", "background-secret-5")
        log("clip-bg: copied, clearing in 8 s; text now=\"${SensitiveClipboard.peek(this)}\"")
        main.postDelayed({
            try {
                SensitiveClipboard.clear(applicationContext)
                log("clip-bg: cleared while lifecycle=${lifecycle.currentState}; " +
                    "text now=${SensitiveClipboard.peek(applicationContext)?.let { "\"$it\"" }}")
            } catch (t: Throwable) {
                log("clip-bg: clear FAILED: $t")
            }
        }, 8000)
    }

    // ------------------------------------------------------------------ biometrics
    @Suppress("DEPRECATION")
    private fun bioStatus() {
        val bm = BiometricManager.from(this)
        log("bio-status: canAuthenticate(BIOMETRIC_STRONG)=${name(bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG))}")
        log("bio-status: canAuthenticate(BIOMETRIC_WEAK)=${name(bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK))}")
        log("bio-status: canAuthenticate(DEVICE_CREDENTIAL)=${name(bm.canAuthenticate(BiometricManager.Authenticators.DEVICE_CREDENTIAL))}")
        // android.hardware.fingerprint.FingerprintManager is marked removed="37.0" in the SDK, so
        // with compileSdk 37 it can only be reached by reflection (it still exists on API 23-28 devices).
        try {
            val fm = getSystemService("fingerprint")
            val hw = fm?.javaClass?.getMethod("isHardwareDetected")?.invoke(fm)
            val enrolled = fm?.javaClass?.getMethod("hasEnrolledFingerprints")?.invoke(fm)
            log("bio-status: framework FingerprintManager (reflection) isHardwareDetected=$hw hasEnrolledFingerprints=$enrolled")
            // Hidden API: the secure ID that auth-bound keys are tied to. It must change when a
            // fingerprint is enrolled, otherwise setInvalidatedByBiometricEnrollment cannot work.
            val authId = try {
                fm?.javaClass?.getMethod("getAuthenticatorId")?.invoke(fm)
            } catch (t: Throwable) {
                "unavailable ($t)"
            }
            log("bio-status: FingerprintManager.getAuthenticatorId() (hidden, reflection)=$authId")
        } catch (t: Throwable) {
            log("bio-status: framework FingerprintManager reflection FAILED: $t")
        }
        val compat = androidx.core.hardware.fingerprint.FingerprintManagerCompat.from(this)
        log("bio-status: androidx.core FingerprintManagerCompat isHardwareDetected=${compat.isHardwareDetected} " +
            "hasEnrolledFingerprints=${compat.hasEnrolledFingerprints()}")
        val km = getSystemService(android.app.KeyguardManager::class.java)
        log("bio-status: KeyguardManager isDeviceSecure=${km.isDeviceSecure} isKeyguardSecure=${km.isKeyguardSecure}")
        try {
            log("bio-status: key present=${BiometricKey.get()?.let { BiometricKey.describe(it) } ?: "no"}")
        } catch (t: Throwable) {
            log("bio-status: key lookup FAILED: $t")
        }
    }

    private fun name(code: Int) = when (code) {
        BiometricManager.BIOMETRIC_SUCCESS -> "0 BIOMETRIC_SUCCESS"
        BiometricManager.BIOMETRIC_STATUS_UNKNOWN -> "-1 BIOMETRIC_STATUS_UNKNOWN"
        BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED -> "-2 BIOMETRIC_ERROR_UNSUPPORTED"
        BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> "1 BIOMETRIC_ERROR_HW_UNAVAILABLE"
        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> "11 BIOMETRIC_ERROR_NONE_ENROLLED"
        BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> "12 BIOMETRIC_ERROR_NO_HARDWARE"
        BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED -> "15 BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED"
        else -> "$code"
    }

    private fun bioKeygen() {
        try {
            BiometricKey.delete()
            val key = BiometricKey.create()
            log("bio-keygen: created ${BiometricKey.describe(key)}")
            prefs().edit().clear().apply()
        } catch (t: Throwable) {
            log("bio-keygen: FAILED: $t")
        }
    }

    /** Negative control: the key must refuse to work without a biometric bound to the operation. */
    private fun bioNoAuth() {
        try {
            val key = BiometricKey.get() ?: return log("bio-noauth: no key")
            val cipher = BiometricKey.encryptCipher(key)
            log("bio-noauth: cipher.init OK without auth (expected; the check happens at doFinal)")
            cipher.doFinal("x".toByteArray())
            log("bio-noauth: doFinal WITHOUT authentication SUCCEEDED (bad: key not auth-bound)")
        } catch (t: Throwable) {
            log("bio-noauth: refused as expected: $t cause=${t.cause}")
        }
    }

    private fun promptInfo(what: String) = BiometricPrompt.PromptInfo.Builder()
        .setTitle("PV Lab: $what")
        .setSubtitle("API ${Build.VERSION.SDK_INT}, host $hostLabel, biometric ${BuildConfig.BIOMETRIC_VERSION}")
        .setNegativeButtonText("Use master password")
        .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        .setConfirmationRequired(false)
        .build()

    private fun bioEncrypt() {
        val key = try {
            BiometricKey.get()
        } catch (t: Throwable) {
            return log("bio-enc: key lookup FAILED: $t")
        } ?: return log("bio-enc: no key, run bio-keygen first")
        val cipher = try {
            BiometricKey.encryptCipher(key)
        } catch (e: KeyPermanentlyInvalidatedException) {
            return log("bio-enc: KeyPermanentlyInvalidatedException at cipher.init: $e")
        } catch (t: Throwable) {
            return log("bio-enc: cipher.init FAILED: $t")
        }
        onCipherReady = { c ->
            val iv = c.iv
            val ct = c.doFinal(SECRET.toByteArray())
            prefs().edit()
                .putString("iv", Base64.encodeToString(iv, Base64.NO_WRAP))
                .putString("ct", Base64.encodeToString(ct, Base64.NO_WRAP))
                .apply()
            log("bio-enc: cipher.doFinal after auth OK ivLen=${iv.size} ctLen=${ct.size}")
        }
        log("bio-enc: authenticate(promptInfo, CryptoObject(cipher)) called")
        prompt.authenticate(promptInfo("encrypt"), BiometricPrompt.CryptoObject(cipher))
    }

    private fun bioDecrypt() {
        val iv = prefs().getString("iv", null)?.let { Base64.decode(it, Base64.NO_WRAP) }
        val ct = prefs().getString("ct", null)?.let { Base64.decode(it, Base64.NO_WRAP) }
        if (iv == null || ct == null) return log("bio-dec: nothing stored, run bio-enc first")
        val key = try {
            BiometricKey.get()
        } catch (t: Throwable) {
            return log("bio-dec: key lookup FAILED: $t")
        } ?: return log("bio-dec: no key")
        val cipher = try {
            BiometricKey.decryptCipher(key, iv)
        } catch (e: KeyPermanentlyInvalidatedException) {
            return log("bio-dec: KeyPermanentlyInvalidatedException at cipher.init: $e")
        } catch (t: Throwable) {
            return log("bio-dec: cipher.init FAILED: $t")
        }
        onCipherReady = { c ->
            val plain = String(c.doFinal(ct))
            log("bio-dec: cipher.doFinal after auth OK plaintextMatches=${plain == SECRET}")
        }
        log("bio-dec: authenticate(promptInfo, CryptoObject(cipher)) called")
        prompt.authenticate(promptInfo("decrypt"), BiometricPrompt.CryptoObject(cipher))
    }

    private fun prefs() = getSharedPreferences("bio", MODE_PRIVATE)

    companion object {
        const val SECRET = "vault-key-0123456789abcdef"
    }
}

class MainActivity : LabActivity()

class PlatformThemeActivity : LabActivity() {
    override val hostLabel = "PlatformTheme"
}

@Composable
fun LabApp(title: String, lines: List<String>) {
    val backStack = remember { mutableStateListOf<LabKey>(LabKey.Home) }
    MaterialTheme {
        Scaffold(Modifier.fillMaxSize()) { padding ->
            NavDisplay(
                backStack = backStack,
                onBack = { backStack.removeLastOrNull() },
                modifier = Modifier.padding(padding),
                entryProvider = { key ->
                    when (key) {
                        LabKey.Home -> NavEntry<LabKey>(key) {
                            HomeScreen(title, lines) { backStack.add(LabKey.Detail) }
                        }
                        LabKey.Detail -> NavEntry<LabKey>(key) {
                            Column(Modifier.padding(16.dp)) {
                                Text("Screen: Detail", style = MaterialTheme.typography.headlineSmall)
                                Button(onClick = { backStack.removeLastOrNull() }) { Text("Back to list") }
                            }
                        }
                    }
                },
            )
        }
    }
}

@Composable
fun HomeScreen(title: String, lines: List<String>, openDetail: () -> Unit) {
    var password by remember { mutableStateOf("") }
    Column(Modifier.padding(horizontal = 8.dp)) {
        Text("Screen: Home", style = MaterialTheme.typography.titleMedium)
        Text(title, fontSize = 12.sp)
        Row {
            Icon(Icons.Filled.ContentCopy, contentDescription = "copy icon")
            Icon(Icons.Filled.Fingerprint, contentDescription = "fingerprint icon")
            Icon(Icons.Filled.Lock, contentDescription = "lock icon")
        }
        Row {
            Button(onClick = openDetail) { Text("Open detail") }
        }
        OutlinedTextField(
            value = password,
            onValueChange = {
                password = it
                Log.i(TAG, "textfield length=${it.length} value=$it")
            },
            label = { Text("Master password") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        LazyColumn(Modifier.fillMaxSize()) {
            items(lines) { Text(it, fontSize = 11.sp, lineHeight = 13.sp) }
        }
    }
}
