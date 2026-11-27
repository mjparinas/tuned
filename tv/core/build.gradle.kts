// The TV apps' shared core (docs/decisions/tizen.md): :core's plain-Kotlin rules (the M3U reader, sorting streams into
// Live TV, Movies and Shows, countries, search text, Xtream links and `redact`, the guide grid, the remote's keys)
// compiled to JavaScript for the Samsung (Tizen) and Vega OS apps, so a rule fixed for Android is fixed on every TV.
// :core itself is untouched: this module compiles the same files a second time, and Android never depends on it.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

/**
 * :core's files that use nothing but Kotlin. The rest of :core uses Java (sockets, files, java.time, crypto) and stays
 * Android only. A file here may call `Platform`, which :core gives on the JVM (`JvmPlatform.kt`) and this module gives
 * for JavaScript (`src/jsMain/.../Platform.kt`), with the same names.
 */
val shared =
    listOf(
        "CatchUp",
        "ChannelCopies",
        "Classifier",
        "Episodes",
        "Eta",
        "GuideGrid",
        "GuideShift",
        "M3uLines",
        "Models",
        "RegionLanguages",
        "Regions",
        "RemoteKeys",
        "Search",
        "StreamLink",
        "TitleSort",
        "Tmdb",
        "XtreamAccount",
        "XtreamApi",
        "XtreamUrls",
    )

/** This module's own files: JavaScript's `Platform`, the tables behind it, and what TypeScript calls. */
val own = listOf("LocaleTables", "Platform", "TunedCore")

kotlin {
    js {
        outputModuleName = "tuned-core"
        nodejs()
        binaries.library()
        generateTypeScriptDefinitions()
        useEsModules()
        compilerOptions {
            // Plain ES2015 classes, no top-level await: Tizen's Chromium 69 (2020 TVs) and Vega's Hermes both run it.
            target = "es2015"
        }
    }
    sourceSets {
        jsMain {
            kotlin.srcDir("../../core/src/main/kotlin")
            // The filter covers this module's own files too, so they're listed as well.
            kotlin.include((shared + own).map { "com/tunedplayer/app/core/$it.kt" })
        }
        jsTest {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}
