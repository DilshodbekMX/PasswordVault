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
    //   tools/gradle.sh resolveAll --write-locks
    //   tools/gradle.sh --write-verification-metadata sha256 --rerun-tasks resolveAll \
    //       :core:testDebugUnitTest :app:testDebugUnitTest :core:lintDebug :app:lintDebug \
    //       :app:assembleDebug :app:assembleStaging :app:assembleRelease :app:assembleBackupCheck \
    //       :app:assembleDebugAndroidTest
    // and then checks that the <configuration> part of the metadata file is unchanged.
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
    }
}
