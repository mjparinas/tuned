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
include(":app")
// Generates the app's baseline profile and measures cold start on an emulator (docs/decisions/speed.md).
include(":baselineprofile")
// The device smoke test CI runs on an emulator, and the fake provider both device tests use.
include(":smoketest")
include(":fakeprovider")
