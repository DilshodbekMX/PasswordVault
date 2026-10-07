package io.github.dilshodbekmx.passwordvault.arch

import io.github.dilshodbekmx.passwordvault.testing.arch.Rule
import io.github.dilshodbekmx.passwordvault.testing.arch.SourceFile
import io.github.dilshodbekmx.passwordvault.testing.arch.scan
import org.junit.Assert.assertEquals
import org.junit.Test

/** What each rule of [AppSourceRules] reports, and what it leaves alone, on small samples. */
class AppSourceRulesSamplesTest {
    private val screen = "app/src/main/java/io/github/dilshodbekmx/passwordvault/ui/list/PasswordsScreen.kt"

    private fun lines(rules: List<Rule>, text: String, path: String = screen) =
        scan(listOf(SourceFile(path, text.trimIndent())), rules).map { it.line }

    @Test
    fun r13_textTakesNoLiteral() {
        val text = """
            Text("Hi")
            Text(text = "Hi")
            Text(stringResource(R.string.list_title))
            Text(modifier = Modifier.testTag("title"), text = name)
            Text(name, modifier = Modifier.testTag("title"))
            Text(if (empty) "none" else name)
            @Composable fun Text(value: String) = Unit
            BasicText("not covered by R-13")
            // Text("in a comment")
            Text(
                "on the next line",
            )
            Text(text = "${'$'}count")
        """
        assertEquals(listOf(1, 2, 6, 10, 13), lines(listOf(AppSourceRules.r13Literals[0]), text))
    }

    @Test
    fun r13_contentDescriptionTakesNoLiteral() {
        val text = """
            Icon(painter, contentDescription = "Back")
            Icon(painter, contentDescription = null)
            Icon(painter, contentDescription = stringResource(R.string.common_back))
            Modifier.semantics { contentDescription = "PIN" }
            if (contentDescription == "x") Unit
            Icon(painter, contentDescription = if (shown) "Hide" else null)
        """
        assertEquals(listOf(1, 4, 6), lines(listOf(AppSourceRules.r13Literals[1]), text))
    }

    @Test
    fun r13_fieldsAndDialogsOnlyInTheirWrappers() {
        val text = """
            TextField(value, onValueChange)
            OutlinedTextField(value, onValueChange)
            BasicSecureTextField(state)
            VaultTextField(state)
            AlertDialog(onDismissRequest = {})
            ModalBottomSheet(onDismissRequest = {}) {}
            VaultDialog(onDismissRequest = {})
            android.app.AlertDialog.Builder(context)
            val s = TextFieldState()
            @Composable fun Dialog(x: Int) = Unit
        """
        assertEquals(listOf(1, 2, 3, 5, 6, 8), lines(AppSourceRules.r13Calls, text))

        val common = "app/src/main/java/io/github/dilshodbekmx/passwordvault/ui/common/"
        val field = "@Composable fun VaultSecureField(state: TextFieldState) { BasicSecureTextField(state, Modifier.vaultTextField()) }"
        assertEquals(emptyList<Int>(), lines(AppSourceRules.r13Calls, field, common + "VaultSecureField.kt"))
        val dialog = "@Composable fun VaultDialog(content: @Composable () -> Unit) { Dialog(onDismissRequest = {}) { content() } }"
        assertEquals(emptyList<Int>(), lines(AppSourceRules.r13Calls, dialog, common + "VaultDialog.kt"))
        assertEquals("a wrapper file elsewhere is no wrapper", listOf(1), lines(AppSourceRules.r13Calls, field, screen))
    }

    @Test
    fun r15_aProofForFakesOnlyInTheFakesPreviewsAndTests() {
        val text = """
            import io.github.dilshodbekmx.passwordvault.core.lock.flow.MasterProof
            val proof = MasterProof.forFakes(ByteArray(4), "00000000-0000-4000-8000-000000000001")
            // MasterProof.forFakes in a comment
            val s = "forFakes"
            val real = MasterProof(bytes, vaultId)
        """
        val app = "app/src/main/java/io/github/dilshodbekmx/passwordvault"
        val core = "core/src/main/java/io/github/dilshodbekmx/passwordvault/core"
        for (path in listOf(
            "$app/lock/DefaultLockManager.kt",
            "$app/fakes/Fakes.kt",
            "app/src/release/java/io/github/dilshodbekmx/passwordvault/Release.kt",
            "app/src/staging/java/io/github/dilshodbekmx/passwordvault/Staging.kt",
            "$core/lock/flow/LockEngine.kt",
        )) {
            assertEquals(path, listOf(2), lines(AppSourceRules.r15, text, path))
        }
        val importLine = "import io.github.dilshodbekmx.passwordvault.core.lock.flow.MasterProof.Companion.forFakes"
        assertEquals(listOf(1), lines(AppSourceRules.r15, importLine, "$app/lock/DefaultLockManager.kt"))
        for (path in listOf(
            "$app/fake/FakeLockManager.kt",
            "app/src/debug/java/io/github/dilshodbekmx/passwordvault/ui/lock/LockPreviews.kt",
            "testing/src/main/java/io/github/dilshodbekmx/passwordvault/testing/lock/FakeKeys.kt",
        )) {
            assertEquals(path, emptyList<Int>(), lines(AppSourceRules.r15, text, path))
        }
        val declaration = "fun forFakes(bytes: ByteArray, vaultId: String): MasterProof = MasterProof(bytes, vaultId)"
        assertEquals(emptyList<Int>(), lines(AppSourceRules.r15, declaration, "$core/lock/flow/MasterProof.kt"))
    }

    @Test
    fun r11_savedStateOnlyInNav() {
        val list = "package io.github.dilshodbekmx.passwordvault.ui.list\nval x = rememberSaveable { 0 }\n"
        val nav = "package io.github.dilshodbekmx.passwordvault.nav\nval x = rememberSaveable { 0 }\nclass V(h: SavedStateHandle)\n"
        val navigator = "package io.github.dilshodbekmx.passwordvault.navigator\nval x = rememberSaveable { 0 }\n"
        assertEquals(listOf(2), lines(AppSourceRules.r11SavedState, list))
        assertEquals(emptyList<Int>(), lines(AppSourceRules.r11SavedState, nav))
        assertEquals(listOf(2), lines(AppSourceRules.r11SavedState, navigator))
    }

    @Test
    fun r11_forbiddenApis() {
        val text = """
            import java.time.Instant
            // java.time and Toast in a comment
            key.destroy()
            Toast.makeText(context, text, Toast.LENGTH_SHORT)
            val f = AtomicFile(file)
            Files.move(a, b)
            val b = java.util.Base64.getEncoder()
            val h = hash.rawHashAsByteArray()
            val s = rememberTextFieldState()
            override fun destroy() = Unit
            val toaster = showToast()
            Button(onClick = {}) { Text("Toast") }
            probe("java.nio.file.Files.move") { }
        """
        assertEquals(listOf(1, 3, 4, 5, 6, 7, 8, 9), lines(AppSourceRules.r11Apis, text))
    }

    @Test
    fun r6_noLoggingOutsideDebug() {
        val text = """
            import android.util.Log
            Log.d("T", "x")
            AppLog.event(Event.STARTED)
            e.printStackTrace()
            writer.println(line)
            val log = Log
        """
        assertEquals(listOf(1, 2, 4), lines(AppSourceRules.r6, text))
    }

    @Test
    fun r4_onlyTheClassicPrompt() {
        val text = """
            import androidx.biometric.compose.rememberAuthenticationLauncher
            val r = AuthenticationRequest.biometricRequest(title) {}
            val p = BiometricPrompt(activity, executor, callback)
            registerForAuthenticationResult { }
        """
        assertEquals(listOf(1, 2, 4), lines(AppSourceRules.r4, text))
    }

    @Test
    fun offline_seesConnectionsButNotIdnOrUri() {
        val text = """
            import java.net.IDN
            import java.net.URI
            import java.net.URL
            import java.net.*
            val s = Socket("example.com", 443)
            val w = WebView(context)
            // java.net.URL in a comment
            fun toUrl(): String = ""
            val c = url.openConnection() as HttpURLConnection
            val e = java.net.URLEncoder.encode(x, "UTF-8")
        """
        assertEquals(listOf(3, 4, 5, 6, 9), lines(AppSourceRules.offline, text))
    }
}
