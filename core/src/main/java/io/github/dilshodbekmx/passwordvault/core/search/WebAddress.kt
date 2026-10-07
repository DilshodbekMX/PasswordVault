package io.github.dilshodbekmx.passwordvault.core.search

import java.net.IDN
import java.util.Locale

/** The part of a saved website address that search looks at. */
object WebAddress {

    /**
     * The host name of [address] without the scheme, user name, port, path, query,
     * fragment or a leading "www.", in lower case, with punycode labels (xn--) shown as
     * Unicode. Works for bare host names ("gmail.com"), app links ("androidapp://com.x")
     * and text that is not an address at all (returned trimmed and lower-cased).
     *
     * "https://www.Google.com:443/accounts?x=1" → "google.com"
     */
    fun hostOf(address: String): String {
        var s = address.trim()
        if (s.isEmpty()) return s

        val schemeEnd = s.indexOf("://")
        if (schemeEnd > 0 && isScheme(s, schemeEnd)) {
            val scheme = s.substring(0, schemeEnd)
            s = s.substring(schemeEnd + 3)
            if (scheme.equals("android", ignoreCase = true)) {
                // Chrome's app link android://<certificate hash>@<package>/: the base64 hash
                // may contain '/', so the package is what follows the last '@'.
                val at = s.lastIndexOf('@')
                if (at >= 0) s = s.substring(at + 1)
            }
        } else if (s.startsWith("//")) {
            s = s.substring(2)
        }

        val pathStart = s.indexOfFirst { it == '/' || it == '?' || it == '#' || it == '\\' }
        if (pathStart >= 0) s = s.substring(0, pathStart)

        val at = s.lastIndexOf('@')
        if (at >= 0) s = s.substring(at + 1)

        if (s.startsWith("[")) {
            val close = s.indexOf(']')
            if (close > 0) s = s.substring(1, close) // IPv6 literal
        } else {
            val colon = s.indexOf(':')
            if (colon >= 0 && s.lastIndexOf(':') == colon) s = s.substring(0, colon) // port
        }

        s = s.trimEnd('.').lowercase(Locale.ROOT)
        if (s.startsWith("www.") && s.length > 4) s = s.substring(4)
        if (s.contains("xn--")) s = IDN.toUnicode(s, IDN.ALLOW_UNASSIGNED)
        return s
    }

    private fun isScheme(s: String, end: Int): Boolean {
        if (!s[0].isAsciiLetter()) return false
        for (k in 1 until end) {
            val c = s[k]
            if (!(c.isAsciiLetter() || c in '0'..'9' || c == '+' || c == '-' || c == '.')) return false
        }
        return true
    }

    private fun Char.isAsciiLetter(): Boolean = this in 'a'..'z' || this in 'A'..'Z'
}
