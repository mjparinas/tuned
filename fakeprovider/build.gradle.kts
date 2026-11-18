// A stand-in IPTV provider for the device tests (:baselineprofile, :smoketest): plain Kotlin on java.net, so both
// test apps share it. It holds no real login; the clip is a generated test pattern.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}
