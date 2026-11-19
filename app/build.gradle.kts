// AGP 9 compiles Kotlin itself (built-in Kotlin), so there is no org.jetbrains.kotlin.android plugin.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
    alias(libs.plugins.androidx.baselineprofile)
}

android {
    namespace = "app.tuned"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.tuned"
        minSdk = 26
        targetSdk = 36
        // Release builds take these from the workflow (run number and package.json), like Liftsmith.
        versionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("VERSION_NAME") ?: "0.1.0"
        // Subtitle search's OpenSubtitles.com key, from the OPENSUBTITLES_API_KEY secret; builds without it hide the search.
        val openSubtitlesKey = System.getenv("OPENSUBTITLES_API_KEY").orEmpty().trim()
        buildConfigField("String", "OPENSUBTITLES_API_KEY", "\"$openSubtitlesKey\"")
        // The app's TMDB key (a v4 read token or v3 key) from the release secret, never committed; without it TMDB is off.
        buildConfigField("String", "TMDB_API_KEY", "\"${System.getenv("TMDB_API_KEY").orEmpty()}\"")
        // Trakt's app keys (docs/decisions/trakt.md): from the release workflow's secrets, never committed. Without
        // them the Trakt settings stay hidden.
        buildConfigField("String", "TRAKT_CLIENT_ID", "\"${System.getenv("TRAKT_CLIENT_ID").orEmpty()}\"")
        // The commit this build came from, shown as plain text at the bottom of Settings (Melvin, 2026-11-18). CI and Release
        // pass GIT_COMMIT (a pull request's own head, not GitHub's merge commit); local builds ask git. Empty hides the line.
        val gitCommit =
            System.getenv("GIT_COMMIT")?.takeIf { it.isNotBlank() }
                ?: runCatching {
                    providers
                        .exec {
                            commandLine("git", "rev-parse", "HEAD")
                            isIgnoreExitValue = true
                        }.standardOutput.asText
                        .get()
                }.getOrNull()
        buildConfigField("String", "GIT_COMMIT", "\"${gitCommit.orEmpty().trim().take(7)}\"")
        // Sync between devices (docs/decisions/sync.md): Google's "TVs and limited input devices" client, from the release
        // workflow's secrets, never committed. Without it sync stays hidden.
        buildConfigField("String", "GOOGLE_TV_CLIENT_ID", "\"${System.getenv("GOOGLE_TV_CLIENT_ID").orEmpty()}\"")
        buildConfigField("String", "GOOGLE_TV_CLIENT_SECRET", "\"${System.getenv("GOOGLE_TV_CLIENT_SECRET").orEmpty()}\"")
    }

    // Two builds of the same app (docs/decisions/premium.md). `github` is what GitHub Releases ship: it updates itself
    // from GitHub and fills in the default server. `play` is for Google Play and other stores: no self-updater (stores
    // don't allow REQUEST_INSTALL_PACKAGES), and the server field starts empty so the app never looks like it comes with
    // a provider.
    flavorDimensions += "store"
    productFlavors {
        create("github") {
            dimension = "store"
            buildConfigField("boolean", "GITHUB_UPDATES", "true")
            buildConfigField("boolean", "PREMIUM_ALWAYS", "true")
            buildConfigField("String", "DEFAULT_SERVER", "\"http://eros4k.ddns.net\"")
            buildConfigField("String", "DEVICES_URL", "\"\"")
        }
        create("play") {
            dimension = "store"
            buildConfigField("boolean", "GITHUB_UPDATES", "false")
            buildConfigField("boolean", "PREMIUM_ALWAYS", "false")
            buildConfigField("String", "DEFAULT_SERVER", "\"\"")
            // The Premium device list (docs/decisions/premium.md); without it a store build has no device limit.
            buildConfigField("String", "DEVICES_URL", "\"${System.getenv("PREMIUM_DEVICES_URL").orEmpty()}\"")
        }
    }

    signingConfigs {
        create("release") {
            val keystore = System.getenv("ANDROID_KEYSTORE_PATH")
            if (keystore != null) {
                storeFile = file(keystore)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS") ?: "upload"
                keyPassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("release") {
            if (System.getenv("ANDROID_KEYSTORE_PATH") != null) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        getByName("debug") {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        // Any warning fails CI, like Liftsmith's ESLint --max-warnings 0.
        warningsAsErrors = true
        abortOnError = true
        checkDependencies = true
        // A plain-text report as well, which CI prints to the job log when lint fails.
        textReport = true
        // Versions are Dependabot's job (weekly, grouped); a newer release on Maven must not fail an unrelated change.
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion", "OldTargetApi")
        // IPTV servers stream over plain HTTP (docs/decisions/security.md).
        disable += "InsecureBaseConfiguration"
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/versions/9/OSGI-INF/MANIFEST.MF")
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
        unitTests.all { test ->
            // Screenshots (docs/decisions/screenshots.md): `./gradlew :app:testGithubDebugUnitTest -Pscreenshots` draws every
            // screen with sample data into build/outputs/roborazzi, and runs nothing else. Ordinary runs compile them (so a
            // broken one fails CI) but skip them, since drawing takes longer than the other tests.
            if (project.hasProperty("screenshots")) {
                test.filter.includeTestsMatching("app.tuned.screenshots.*")
                test.systemProperty("roborazzi.test.record", "true")
                test.systemProperty("roborazzi.record.filePathStrategy", "relativePathFromRoborazziContextOutputDirectory")
                test.testLogging.exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
                // Each test class draws on its own; four at a time on CI's four cores.
                test.maxParallelForks = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
            } else {
                test.exclude("app/tuned/screenshots/**")
            }
        }
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        optIn.addAll("androidx.media3.common.util.UnstableApi", "androidx.compose.material3.ExperimentalMaterial3Api")
    }
}

// The baseline profile (docs/decisions/speed.md) is generated on an emulator by .github/workflows/baseline-profile.yml
// and committed under src/main/generated/, so release builds of both stores compile the first minute's code at install. It
// is recorded on the github build, whose code is the same as play's apart from the updater and billing. Normal
// builds only read it; they never start an emulator.
baselineProfile {
    automaticGenerationDuringBuild = false
    saveInSrc = true
    mergeIntoMain = true
    // Puts the startup code together at the front of the dex file too, so less of it is read from storage.
    dexLayoutOptimization = true
    // Only the github build is driven on the emulator; mergeIntoMain gives the play build the same profile.
    variants {
        create("github") { from(project(":baselineprofile")) }
    }
}

// Room's plugin writes each variant's schema separately, so debug and release builds don't race on one file.
room {
    schemaDirectory("$projectDir/schemas")
}

ksp {
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(project(":core"))

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.paging)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.paging.compose)

    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.tvprovider)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.datasource.okhttp)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.media3.cast)

    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.play.app.update)
    // Premium is sold through Play Billing in the store build only (docs/decisions/premium.md).
    "playImplementation"(libs.play.billing)
    // Play's update library pulls in an old Fragment; 1.3+ is needed for the activity result API it uses.
    implementation(libs.androidx.fragment)

    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.junit)
    testImplementation(kotlin("test-junit"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.work.testing)
    // Screenshots on the JVM (docs/decisions/screenshots.md); test-only, so nothing of it reaches an APK.
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.coil.test)
    // Screen-reader semantics are checked on Robolectric (docs/decisions/accessibility.md); the manifest gives the test an activity.
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
