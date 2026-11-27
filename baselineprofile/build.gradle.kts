// Drives the release app on an emulator to write its baseline profile, and measures cold start with and without it
// (docs/decisions/speed.md). Run by .github/workflows/baseline-profile.yml; normal builds only read the committed profile.
plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.androidx.baselineprofile)
}

android {
    namespace = "com.tunedplayer.app.baselineprofile"
    // Compose 1.13 needs Android 37.1's APIs to compile against (minSdk and targetSdk are unchanged).
    compileSdk {
        version = release(37) { minorApiLevel = 1 }
    }

    defaultConfig {
        minSdk = 28
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // The CI emulator is the only device these run on; its numbers are for before and after, not absolutes.
        testInstrumentationRunnerArguments["androidx.benchmark.suppressErrors"] = "EMULATOR"
    }

    targetProjectPath = ":app"

    // The app's three builds (docs/decisions/premium.md). Only the github build is driven; the profile is merged into
    // main, so the play and amazon builds ship it too.
    flavorDimensions += "store"
    productFlavors {
        create("github") { dimension = "store" }
        create("play") { dimension = "store" }
        create("amazon") { dimension = "store" }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    testOptions.managedDevices.localDevices {
        create("pixel6Api34") {
            device = "Pixel 6"
            apiLevel = 34
            systemImageSource = "aosp"
        }
        // Gradle Managed Devices do not support android-tv images; cold-start numbers stay on Pixel 6.
    }
}

androidComponents {
    beforeVariants { if (!it.productFlavors.contains("store" to "github")) it.enable = false }
}

kotlin {
    jvmToolchain(21)
}

baselineProfile {
    managedDevices += "pixel6Api34"
    useConnectedDevices = false
}

dependencies {
    implementation(project(":fakeprovider"))
    implementation(libs.androidx.test.junit)
    implementation(libs.androidx.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}
