// AGP 9 compiles Kotlin itself (built-in Kotlin), so there is no org.jetbrains.kotlin.android plugin.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
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
            buildConfigField("String", "DEFAULT_SERVER", "\"http://eros4k.ddns.net\"")
        }
        create("play") {
            dimension = "store"
            buildConfigField("boolean", "GITHUB_UPDATES", "false")
            buildConfigField("String", "DEFAULT_SERVER", "\"\"")
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
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        optIn.addAll("androidx.media3.common.util.UnstableApi", "androidx.compose.material3.ExperimentalMaterial3Api")
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
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.media3.cast)

    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.play.app.update)
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
}
