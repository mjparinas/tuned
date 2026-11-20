package com.tunedplayer.app.screenshots

import com.tunedplayer.app.R
import com.tunedplayer.app.ui.HomeRoute
import com.tunedplayer.app.ui.SettingsPageRoute
import com.tunedplayer.app.ui.SettingsRoute
import com.tunedplayer.app.ui.settings.SettingsPage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The app in other languages (docs/decisions/languages.md): Arabic for right-to-left, German for the longest words,
 * Hindi and Chinese for other scripts, on a phone and on a TV.
 */
@RunWith(RobolectricTestRunner::class)
class LanguageScreenshots : Screenshots() {
    @Test
    @Config(qualifiers = "ar-ldrtl-$PHONE")
    fun liveTvArabic() = shot("language-ar-live-tv", HomeRoute, wait = "Maple News 24")

    @Test
    @Config(qualifiers = "ar-ldrtl-$PHONE")
    fun settingsArabic() = shot("language-ar-settings", SettingsRoute, wait = text(R.string.settings_playback))

    @Test
    @Config(qualifiers = "ar-ldrtl-$TV")
    fun liveTvArabicOnTv() = shot("language-ar-live-tv-tv", HomeRoute, wait = "Maple News 24")

    @Test
    @Config(qualifiers = "de-$PHONE")
    fun playbackGerman() =
        shot("language-de-settings-playback", SettingsPageRoute(SettingsPage.PLAYBACK.name), wait = text(R.string.settings_resume))

    @Test
    @Config(qualifiers = "de-$TV")
    fun settingsGermanOnTv() = shot("language-de-settings-tv", SettingsRoute, wait = text(R.string.settings_playback))

    @Test
    @Config(qualifiers = "hi-$PHONE")
    fun settingsHindi() = shot("language-hi-settings", SettingsRoute, wait = text(R.string.settings_playback))

    @Test
    @Config(qualifiers = "zh-rCN-$PHONE")
    fun liveTvChinese() = shot("language-zh-live-tv", HomeRoute, wait = "Maple News 24")
}
