// The device smoke test (docs/decisions/diagnostics.md): the github debug app on an emulator against the fake provider,
// signing in, playing, changing channel and going back, with at most one stream connection open at any moment.
// CI runs it on every pull request ("Device smoke test").
plugins {
    alias(libs.plugins.android.test)
}

android {
    namespace = "com.tunedplayer.app.smoketest"
    // Compose 1.13 needs Android 37.1's APIs to compile against (minSdk and targetSdk are unchanged).
    compileSdk {
        version = release(37) { minorApiLevel = 1 }
    }

    defaultConfig {
        minSdk = 26
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    targetProjectPath = ":app"

    // The app's three builds (docs/decisions/premium.md); the github build is the one tested.
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
            systemImageSource = "aosp-atd"
        }
        // Gradle Managed Devices do not support android-tv images; TV coverage stays in Robolectric and a real box.
    }
}

androidComponents {
    beforeVariants { if (!it.productFlavors.contains("store" to "github")) it.enable = false }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":fakeprovider"))
    implementation(libs.androidx.test.junit)
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.uiautomator)
}
