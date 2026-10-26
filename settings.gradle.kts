pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
// The settings plugins are locked like every other dependency (settings-gradle.lockfile).
buildscript {
    configurations.classpath {
        resolutionStrategy.activateDependencyLocking()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
// The configuration cache keys on the JVM that runs the build. A cache entry names that JVM for the
// test workers and the Kotlin daemon; without this read, a daemon on another JVM of the same version
// reuses the entry, and every test task fails once that JVM is gone. On 2026-10-26 entries stored by
// a daemon on VS Code's bundled JRE broke the tests after an update of the Java extension removed it
// (build plan §12, "From the device check of waves 6 and 8A"; tools/gradle.sh keeps to its own JDK).
providers.systemProperty("java.home").get()
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "PasswordVault"
include(":app")
include(":core")
// The fakes of every :core port, shared by the tests of :core and :app (architecture.md §2.1).
include(":testing")
