package com.tunedplayer.app.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Writes the app's baseline profile: the code the first minute runs (cold start to the cached Live TV list, the
 * three tabs, opening a channel), compiled ahead of time at install instead of interpreted. Run with
 * `./gradlew :app:generateBaselineProfile`; the result is committed under app/src/release/generated/.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Before
    fun provider() = startFakeProvider()

    @Test
    fun generate() = rule.collect(packageName = TARGET_PACKAGE, includeInStartupProfile = true) {
        pressHome()
        signInIfNeeded()
        browseAndPlay()
    }
}
