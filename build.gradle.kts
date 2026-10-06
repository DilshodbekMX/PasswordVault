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
    // Every configuration is locked to the versions in <project>/gradle.lockfile, and every artifact
    // is checked against the SHA-256 sums in gradle/verification-metadata.xml (architecture.md §7.2).
    // Whoever changes a version regenerates both in the same commit:
    //   tools/gradle.sh resolveAndLockAll --write-locks
    //   tools/gradle.sh --write-verification-metadata sha256 <the tasks of tools/check.sh all>
    dependencyLocking {
        lockAllConfigurations()
    }

    tasks.register("resolveAndLockAll") {
        description = "Resolves every configuration of this project, so that --write-locks records all of them."
        notCompatibleWithConfigurationCache("Resolves configurations while it runs")
        doFirst {
            require(gradle.startParameter.isWriteDependencyLocks) { "Run $path with --write-locks" }
        }
        doLast {
            configurations.filter { it.isCanBeResolved }.forEach { it.resolve() }
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
