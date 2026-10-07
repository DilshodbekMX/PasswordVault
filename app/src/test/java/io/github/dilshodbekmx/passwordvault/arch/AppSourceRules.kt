package io.github.dilshodbekmx.passwordvault.arch

import io.github.dilshodbekmx.passwordvault.testing.arch.PatternRule
import io.github.dilshodbekmx.passwordvault.testing.arch.Rule
import io.github.dilshodbekmx.passwordvault.testing.arch.SourceFile

/**
 * The build checks of architecture.md §8.5 that read the app's sources (R-4, R-6, R-13), and those
 * that read every module's sources (R-11, R-15 and the offline class scan), as source scans with
 * comments ignored. The rules that concern :core alone are in `core/src/test/.../core/arch`.
 *
 * An allow-list below changes only through an interface request that says why.
 */
object AppSourceRules {
    private const val APP_PACKAGE = "io.github.dilshodbekmx.passwordvault"
    private const val APP_DIR = "app/src/main/java/io/github/dilshodbekmx/passwordvault"

    /** The source sets the app is built from. */
    val appSets = listOf("app/src/main", "app/src/debug", "app/src/staging", "app/src/backupCheck", "app/src/release")

    /** Every source that is built into an APK or runs on a device: :core, :testing and the app. */
    val allSources = listOf("core/src/main", "testing/src/main") + appSets

    /** R-6: the source sets where nothing may log; the debug and staging sets may. */
    val noLoggingSets = listOf("app/src/main", "app/src/backupCheck", "app/src/release")

    /** R-13: the screens. */
    const val UI_DIR = "$APP_DIR/ui"
    private const val UI_COMMON = "$APP_DIR/ui/common/"

    /** R-11: files outside `nav` that may use rememberSaveable or SavedStateHandle. */
    val savedStateAllowList: Set<String> = emptySet()

    /** R-15: the file that declares `MasterProof.forFakes`, and the fakes that may call it. */
    private const val MASTER_PROOF = "core/src/main/java/io/github/dilshodbekmx/passwordvault/core/lock/flow/MasterProof.kt"
    private const val APP_FAKE = "$APP_DIR/fake/"

    /** R-4: only the classic BiometricPrompt API, whose dialog stays inside our activity (lock.md §9.1). */
    val r4: List<Rule> = listOf(
        PatternRule("R-4", "AuthenticationRequest", Regex("""\bAuthenticationRequest\b""")),
        PatternRule("R-4", "registerForAuthenticationResult", Regex("""\bregisterForAuthenticationResult\b""")),
        PatternRule("R-4", "androidx.biometric.compose", Regex("""\bandroidx\.biometric\.compose\b""")),
    )

    /**
     * R-6 as widened by architecture.md §5.6: outside the debug and staging source sets the app does
     * not log (its only logging there is `AppLog.event(Event)`).
     */
    val r6: List<Rule> = listOf(
        PatternRule("R-6", "android.util.Log", Regex("""\bandroid\.util\.Log\b""")),
        PatternRule("R-6", "Log.<level>(", Regex("""(?<![\w.])Log\s*\.\s*(v|d|i|w|e|wtf|println)\s*\(""")),
        PatternRule("R-6", "printStackTrace", Regex("""\bprintStackTrace\b""")),
        PatternRule("R-6", "println or print", Regex("""(?<![\w.])print(ln)?\s*\(""")),
        PatternRule("R-6", "System.out or System.err", Regex("""\bSystem\s*\.\s*(out|err)\b""")),
        PatternRule("R-6", "java.util.logging", Regex("""\bjava\.util\.logging\b""")),
    )

    /** R-11: names that must not appear in any source (V-C1, V-C10, V-C11, V-S7, API 26 classes). */
    val r11Apis: List<Rule> = listOf(
        "rememberTextFieldState" to """\brememberTextFieldState\b""",
        "Toast" to """\bToast\b""",
        "AtomicFile" to """\bAtomicFile\b""",
        "DataStore" to """\bDataStore\b|\bandroidx\.datastore\b""",
        "Files.move" to """\bFiles\s*\.\s*move\b""",
        "java.time" to """\bjava\.time\b""",
        "java.util.Base64" to """\bjava\.util\.Base64\b""",
        "a .destroy( call" to """\.\s*destroy\s*\(""",
        "rawHashAsByteArray" to """\brawHashAsByteArray\b""",
        "encodedOutputAsString" to """\bencodedOutputAsString\b""",
    ).map { (what, pattern) -> PatternRule("R-11", what, Regex(pattern)) }

    /** R-11: saved state holds route keys only, so it is written in `nav` and nowhere else. */
    val r11SavedState: List<Rule> = listOf(
        "rememberSaveable outside nav" to """\brememberSaveable\b""",
        "SavedStateHandle outside nav" to """\bSavedStateHandle\b""",
    ).map { (what, pattern) ->
        PatternRule("R-11", what, Regex(pattern)) { file, _ ->
            inPackage(file, "$APP_PACKAGE.nav") || file.path in savedStateAllowList
        }
    }

    /**
     * R-13: text fields, dialogs and sheets are made only by the wrappers of `ui/common`, which apply
     * `vaultTextField()` and `VaultWindow`. A call is allowed in a file of `ui/common` that declares
     * the wrapper. `InputField` is Material 3's `SearchBarDefaults.InputField`, a `BasicTextField`
     * underneath; its wrapper is `VaultSearchField` (ux.md §5.11).
     */
    val r13Calls: List<Rule> = listOf(
        PatternRule(
            "R-13",
            "a text field outside VaultTextField, VaultSecureField and VaultSearchField",
            Regex("""\b(TextField|OutlinedTextField|BasicTextField|SecureTextField|OutlinedSecureTextField|BasicSecureTextField|InputField)\s*\("""),
        ) { file, match ->
            isDeclaration(file, match.range.first) ||
                (file.path.startsWith(UI_COMMON) && declares(file, "VaultTextField", "VaultSecureField", "VaultSearchField"))
        },
        PatternRule(
            "R-13",
            "a dialog or sheet outside VaultDialog and VaultSheet",
            Regex("""\b(Dialog|AlertDialog|BasicAlertDialog|ModalBottomSheet)\s*\(|\bAlertDialog\s*\.\s*Builder\b"""),
        ) { file, match ->
            isDeclaration(file, match.range.first) ||
                (file.path.startsWith(UI_COMMON) && declares(file, "VaultDialog", "VaultSheet"))
        },
    )

    /** R-13: under `ui/`, `Text(` and `contentDescription =` take no string literal; texts are resources. */
    val r13Literals: List<Rule> = listOf(
        object : Rule("R-13", "Text( with a string literal") {
            private val call = Regex("""\bText\s*\(""")
            private val named = Regex("""^(\w+)\s*=(?!=)""")

            override fun offsetsIn(file: SourceFile): List<Int> = call.findAll(file.code).filter { match ->
                if (file.isLiteral(match.range.first) || isDeclaration(file, match.range.first)) return@filter false
                val args = file.arguments(match.range.last)
                fun nameOf(range: IntRange) = named.find(file.code.substring(range.first, range.last + 1))?.groupValues?.get(1)
                val text = args.firstOrNull { nameOf(it) == "text" } ?: args.firstOrNull()?.takeIf { nameOf(it) == null }
                text != null && file.hasTopLevelLiteral(text)
            }.map { it.range.first }.toList()
        },
        object : Rule("R-13", "contentDescription = a string literal") {
            private val assignment = Regex("""\bcontentDescription\s*=(?!=)""")

            override fun offsetsIn(file: SourceFile): List<Int> = assignment.findAll(file.code).filter { match ->
                if (file.isLiteral(match.range.first)) return@filter false
                val code = file.code
                var i = match.range.last + 1
                while (i < code.length && code[i].isWhitespace()) i++
                val start = i
                var depth = 0
                while (i < code.length) {
                    if (!file.isLiteral(i)) {
                        val c = code[i]
                        if (c == '(' || c == '[' || c == '{') {
                            depth++
                        } else if (c == ')' || c == ']' || c == '}') {
                            if (depth == 0) break
                            depth--
                        } else if ((c == ',' || c == '\n') && depth == 0) {
                            break
                        }
                    }
                    i++
                }
                file.hasTopLevelLiteral(start until i)
            }.map { it.range.first }.toList()
        },
    )

    /**
     * R-15: `MasterProof.forFakes`, a proof that no master password stands behind (architecture.md
     * §3.4), is named only in the file that declares it, by the fakes under `APP/fake/`, in the
     * `debug` source set (previews) and in `:testing`; the test source sets are not scanned. So
     * release code only ever holds the proofs that `LockEngine` makes after a correct master
     * password.
     */
    val r15: List<Rule> = listOf(
        PatternRule("R-15", "MasterProof.forFakes outside the fakes, previews and tests", Regex("""\bforFakes\b""")) { file, _ ->
            file.path == MASTER_PROOF || file.path.startsWith(APP_FAKE) ||
                file.path.startsWith("app/src/debug/") || file.path.startsWith("testing/src/main/")
        },
    )

    /**
     * The offline scan of architecture.md §8.5: no class that opens a connection. `java.net.IDN` and
     * `java.net.URI` stay allowed (`WebAddress` uses IDN for punycode).
     */
    val offline: List<Rule> = listOf(
        PatternRule("Offline", "java.net.URL, java.net.Proxy or java.net.http", Regex("""\bjava\.net\.(URL|Proxy|http)\b""")),
        PatternRule(
            "Offline",
            "a wildcard import of a network package",
            Regex("""\bimport\s+(java\.net|java\.nio\.channels|javax\.net|javax\.net\.ssl|android\.webkit|android\.net\.http)\.\*"""),
        ),
        PatternRule(
            "Offline",
            "a class that opens a connection",
            Regex(
                """\b(URLConnection|HttpURLConnection|HttpsURLConnection|Socket|ServerSocket|DatagramSocket|MulticastSocket|""" +
                    """SSLSocket|SSLServerSocket|SocketFactory|SSLSocketFactory|InetAddress|Inet4Address|Inet6Address|""" +
                    """InetSocketAddress|SocketChannel|ServerSocketChannel|DatagramChannel|AsynchronousSocketChannel|""" +
                    """AsynchronousServerSocketChannel|WebView|HttpClient)\b""",
            ),
        ),
        PatternRule("Offline", "a network library", Regex("""\b(okhttp3|retrofit2|io\.ktor)\b""")),
    )

    private fun inPackage(file: SourceFile, pkg: String) =
        file.packageName == pkg || file.packageName.startsWith("$pkg.")

    /** Whether the match at [offset] is the name in a `fun` declaration, not a call. */
    private fun isDeclaration(file: SourceFile, offset: Int): Boolean {
        val lineStart = file.code.lastIndexOf('\n', offset - 1) + 1
        return Regex("""\bfun\s+(<[^>]*>\s*)?([\w.]+\.)?$""").containsMatchIn(file.code.substring(lineStart, offset))
    }

    private fun declares(file: SourceFile, vararg names: String) =
        names.any { Regex("""\bfun\s+(<[^>]*>\s*)?$it\s*\(""").containsMatchIn(file.code) }
}
