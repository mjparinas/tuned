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
}

rootProject.name = "tuned"

// :core is plain Kotlin (parsers, rules) so its tests run on the JVM in seconds; :app is the Android app.
include(":core")
// :data is the Android library under the screens: the database, settings, accounts, downloads and the log. Neither build
// (github, play) changes it, so it compiles once for both and comes from the build cache when a change doesn't touch it.
include(":data")
include(":app")
// Generates the app's baseline profile and measures cold start on an emulator (docs/decisions/speed.md).
include(":baselineprofile")
// The device smoke test CI runs on an emulator, and the fake provider both device tests use.
include(":smoketest")
include(":fakeprovider")
// The TV apps' shared core: :core's plain-Kotlin rules compiled to JavaScript for the Samsung (Tizen) and Vega OS apps
// (tv/, docs/decisions/tizen.md). Android never depends on it.
include(":tvcore")
project(":tvcore").projectDir = file("tv/core")
