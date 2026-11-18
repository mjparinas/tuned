// Drives the release app on an emulator to write its baseline profile, and measures cold start with and without it
// (docs/decisions/speed.md). Run by .github/workflows/baseline-profile.yml; normal builds only read the committed profile.
plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.androidx.baselineprofile)
}

android {
    namespace = "app.tuned.baselineprofile"
    compileSdk = 37

    defaultConfig {
        minSdk = 28
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // The CI emulator is the only device these run on; its numbers are for before and after, not absolutes.
        testInstrumentationRunnerArguments["androidx.benchmark.suppressErrors"] = "EMULATOR"
    }

    targetProjectPath = ":app"

    // The app's two stores (docs/decisions/premium.md). Only the github build is driven; the profile is merged into
    // main, so the play build ships it too.
    flavorDimensions += "store"
    productFlavors {
        create("github") { dimension = "store" }
        create("play") { dimension = "store" }
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
    }
}

androidComponents {
    beforeVariants { if (it.productFlavors.contains("store" to "play")) it.enable = false }
}

kotlin {
    jvmToolchain(21)
}

baselineProfile {
    managedDevices += "pixel6Api34"
    useConnectedDevices = false
}

dependencies {
    implementation(libs.androidx.test.junit)
    implementation(libs.androidx.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}
