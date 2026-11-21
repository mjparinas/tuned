plugins {
    alias(libs.plugins.kotlin.jvm)
    // :core runs on Android 8.0 (minSdk 26), whose Java library is older than the JDK it compiles against. Android Lint's
    // NewApi can't see into a plain JVM module, so Animal Sniffer fails the build on any call Android 8.0 lacks
    // (URLEncoder.encode(String, Charset) is Android 13 and newer only; it broke loading channels on an Android 11 TV).
    alias(libs.plugins.animalsniffer)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    signature(variantOf(libs.android.api.signature) { artifactType("signature") })
    // Android ships XmlPullParser; on the JVM the interface comes from xmlpull and the parser from kxml2 (tests).
    compileOnly(libs.xmlpull)
    testImplementation(libs.junit)
    testImplementation(libs.kxml2)
    testImplementation(kotlin("test-junit"))
}

animalsniffer {
    // Production code only: tests run on the JVM and may use anything it has.
    sourceSets = listOf(project.sourceSets["main"])
}
