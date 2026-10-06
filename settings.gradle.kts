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
