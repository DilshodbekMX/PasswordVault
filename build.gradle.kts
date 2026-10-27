// Top-level build file where you can add configuration options common to all sub-projects/modules.
import java.util.Locale

// The plugins below are locked like every other dependency (buildscript-gradle.lockfile).
buildscript {
    configurations.classpath {
        resolutionStrategy.activateDependencyLocking()
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    // AGP 9 compiles Kotlin itself (built-in Kotlin), so there is no kotlin-android plugin.
    // Declaring the Compose compiler plugin at libs.versions.kotlin also sets the Kotlin version.
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

allprojects {
    // Every configuration is locked to the versions in <project>/gradle.lockfile (and the plugins in
    // buildscript-gradle.lockfile), and every artifact is checked against the SHA-256 sums in
    // gradle/verification-metadata.xml (architecture.md §7.2). Whoever changes a version regenerates
    // both in the same commit:
    //   tools/gradle.sh --write-verification-metadata sha256 --write-locks resolveAll
    //   tools/gradle.sh --write-verification-metadata sha256 --rerun-tasks resolveAll \
    //       :core:testDebugUnitTest :app:testDebugUnitTest :core:lintDebug :app:lintDebug \
    //       :app:assembleDebug :app:assembleStaging :app:assembleRelease :app:assembleBackupCheck \
    //       :app:assembleDebugAndroidTest
    // and then checks that the <configuration> part of the metadata file is unchanged: Gradle's
    // rewrite drops the comment in it, which goes back by hand. The first command resolves every
    // configuration without the old locks, and so also reads the .pom and .module files of versions
    // that lose a conflict, which locked builds never read; --write-locks without
    // --write-verification-metadata stops at any of them that the metadata lacks, as at every file
    // of a new version. The second records what only the tasks resolve. Run both in a fresh clone
    // with an empty Gradle home (GRADLE_USER_HOME set to a new directory), as a GitHub Actions
    // runner starts: Gradle does not record a .pom or .module file that its own cache answers, and
    // the first CI run stopped at six such files that this machine had cached.
    dependencyLocking {
        lockAllConfigurations()
    }

    tasks.register("resolveAll") {
        description = "Resolves every configuration of this project, graph and files, for --write-locks " +
            "and --write-verification-metadata."
        notCompatibleWithConfigurationCache("Resolves configurations while it runs")
        doLast {
            configurations.filter { it.isCanBeResolved }.forEach { configuration ->
                configuration.incoming.resolutionResult.allComponents
                // Lenient: AGP's own project variants cannot pick their files without the artifact
                // type of a real consumer; every external file is still downloaded and checked.
                configuration.incoming.artifactView { isLenient = true }.files.files
            }
        }
    }
}

subprojects {
    tasks.withType<Test>().configureEach {
        // Every -Ppv.<name>=<value> reaches the unit tests as the system property pv.<name>; tests that
        // must not run every time (benchmarks, converters, the source-scan audit) look for theirs.
        val pv = providers.gradlePropertiesPrefixedBy("pv.").get()
        systemProperties(pv)
        // -Ppv.testLocale=<BCP 47 tag> runs the tests under that default locale. tools/check.sh runs the
        // tests of :core under tr-TR and again under fa-IR, so that no result depends on the locale
        // (architecture.md §8.1).
        pv["pv.testLocale"]?.let { tag ->
            val locale = Locale.forLanguageTag(tag)
            require(locale.language.isNotEmpty()) { "pv.testLocale: not a language tag: $tag" }
            systemProperty("user.language", locale.language)
            systemProperty("user.country", locale.country)
            if (locale.script.isNotEmpty()) systemProperty("user.script", locale.script)
        }
        // -Ppv.scanAudit runs the source-scan audit (arch/*SourceRulesAuditTest, build plan 0.1), which
        // reads code that is not built: prototypes/ and docs/lab/*-artifacts/. Those files are inputs of
        // the run then, so that a result cached before one of them changed is never reused.
        if ("pv.scanAudit" in pv) {
            inputs.files(
                fileTree(rootDir.resolve("prototypes")) { include("**/*.kt", "**/*.java") },
                fileTree(rootDir.resolve("docs/lab")) { include("*-artifacts/**/*.kt", "*-artifacts/**/*.java") },
            ).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("auditedSources")
        }
        // Any other pv.* property starts a program that runs as a test (the audit above; search's
        // benchmark and its two converters, build plan 1.2). Such a run is never skipped as up to date
        // or taken from the build cache: a program may read and write files that are not the task's
        // inputs and outputs (a converter's draft is written only when it really runs), and a
        // benchmark must print its numbers every time.
        if (pv.keys.any { it != "pv.testLocale" }) {
            outputs.upToDateWhen { false }
            outputs.doNotCacheIf("a pv.* program run reads and writes files that are not declared") { true }
        }
    }
}
