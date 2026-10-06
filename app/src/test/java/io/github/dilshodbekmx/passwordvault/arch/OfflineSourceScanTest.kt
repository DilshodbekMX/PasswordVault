package io.github.dilshodbekmx.passwordvault.arch

import io.github.dilshodbekmx.passwordvault.testing.arch.SourceTree
import io.github.dilshodbekmx.passwordvault.testing.arch.describe
import io.github.dilshodbekmx.passwordvault.testing.arch.scan
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The class scan of the offline check (architecture.md §8.5 "Offline", tools/check.sh offline): no
 * source of :core, :testing or the app names a class that opens a connection.
 */
class OfflineSourceScanTest {
    @Test
    fun noClassThatOpensAConnection() {
        val root = SourceTree.root()
        val findings = scan(SourceTree.files(root, AppSourceRules.allSources), AppSourceRules.offline)
        assertTrue(describe(findings), findings.isEmpty())
    }
}
