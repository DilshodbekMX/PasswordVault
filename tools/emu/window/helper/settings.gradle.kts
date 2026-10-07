// tools/emu/window/helper: the window lab's helper app as a small Gradle build of its own (build
// plan 3.12; architecture.md §8.4). Built through the project's wrapper:
//
//   tools/gradle.sh -p tools/emu/window/helper assembleDebug
//
// It is not a module of the app's build: nothing of it is ever in an APK of PasswordVault. It takes
// the Android Gradle plugin's version from the app's own catalog, so that both builds use the
// plugin that is locked and verified there (gradle/libs.versions.toml, gradle/verification-metadata.xml).
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
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
    versionCatalogs {
        create("libs") {
            from(files("../../../../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "pv-window-helper"
