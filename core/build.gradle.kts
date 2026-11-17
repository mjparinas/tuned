plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // Android ships XmlPullParser; on the JVM the interface comes from xmlpull and the parser from kxml2 (tests).
    compileOnly(libs.xmlpull)
    testImplementation(libs.junit)
    testImplementation(libs.kxml2)
    testImplementation(kotlin("test-junit"))
}
