// The Android library under the screens (AGENTS.md › Modules): Room and its DAOs, DataStore settings, the encrypted login,
// accounts, downloads, the playlist and guide imports, and the log (AppLog, launchSafely, attempt). It has no flavours,
// so the github and play builds share one compile of it, and the build cache serves it when a change leaves it alone.
// Files that reach up into the app (WorkManager jobs, widgets, the player, AppContainer) stay in :app, in the same
// packages.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

android {
    namespace = "com.tunedplayer.app.data"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        // The same values as the app's BuildConfig, from the same environment (app/build.gradle.kts). Libraries get
        // no version of their own, and nothing per build (github or play) or per commit belongs here, so this
        // module's compile stays cacheable between commits.
        buildConfigField("String", "VERSION_NAME", "\"${System.getenv("VERSION_NAME") ?: "0.1.0"}\"")
        buildConfigField("int", "VERSION_CODE", (System.getenv("VERSION_CODE") ?: "1").toInt().toString())
        buildConfigField("String", "OPENSUBTITLES_API_KEY", "\"${System.getenv("OPENSUBTITLES_API_KEY").orEmpty().trim()}\"")
        buildConfigField("String", "TRAKT_CLIENT_ID", "\"${System.getenv("TRAKT_CLIENT_ID").orEmpty()}\"")
        buildConfigField("String", "SIMKL_CLIENT_ID", "\"${System.getenv("SIMKL_CLIENT_ID").orEmpty()}\"")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        buildConfig = true
    }

    lint {
        // As in the app: any warning fails CI. The app's lint reads this module too (checkDependencies).
        warningsAsErrors = true
        abortOnError = true
        textReport = true
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion", "OldTargetApi")
        disable += "InsecureBaseConfiguration"
    }
}

kotlin {
    jvmToolchain(21)
}

// Room's plugin writes each variant's schema separately, so debug and release builds don't race on one file.
room {
    schemaDirectory("$projectDir/schemas")
}

ksp {
    arg("room.generateKotlin", "true")
}

dependencies {
    api(project(":core"))

    // The screens use these types straight from :data's classes (flows, paging sources, settings, HTTP clients).
    api(libs.kotlinx.coroutines.android)
    api(libs.kotlinx.serialization.json)
    api(libs.okhttp)
    api(libs.androidx.core.ktx)
    api(libs.androidx.datastore.preferences)
    api(libs.androidx.room.runtime)
    api(libs.androidx.room.ktx)
    api(libs.androidx.room.paging)
    ksp(libs.androidx.room.compiler)
    // Artwork saved for offline use goes through the app's one image loader.
    implementation(libs.coil.singleton)
}
