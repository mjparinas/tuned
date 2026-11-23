package com.tunedplayer.app.ui.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.tunedplayer.app.R
import com.tunedplayer.app.TunedApp
import com.tunedplayer.app.data.AppSettings
import com.tunedplayer.app.ui.common.LocalTvCalibration
import com.tunedplayer.app.ui.common.TvSafeArea
import com.tunedplayer.app.ui.common.tvCalibration
import com.tunedplayer.app.ui.theme.TunedTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * Calibrate screen writes corner marks and saves move / stretch into the backed-up settings, which every TV screen
 * then reads through [TvSafeArea].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-mdpi")
class TvScreenCalibrateTest {
    @get:Rule
    val compose = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<TunedApp>()

    @Test
    fun cornerMarksAndReset() {
        var settings by mutableStateOf(AppSettings(tvOffsetX = 8, tvOffsetY = -4, tvZoom = 1.2f))
        compose.setContent {
            TunedTheme {
                CompositionLocalProvider(LocalTvCalibration provides settings.tvCalibration()) {
                    TvSafeArea {
                        TvScreenCalibrateScreen(
                            settings = settings,
                            onBack = {},
                            onChange = { change -> settings = change(settings) },
                        )
                    }
                }
            }
        }
        compose.onNodeWithContentDescription(app.getString(R.string.settings_tv_calibrate_marks)).assertIsDisplayed()
        compose.onNodeWithText(app.getString(R.string.settings_tv_calibrate_horizontal)).assertIsDisplayed()
        compose.onNodeWithText(app.getString(R.string.settings_tv_calibrate_reset)).performClick()
        compose.waitForIdle()
        assertEquals(0, settings.tvOffsetX)
        assertEquals(0, settings.tvOffsetY)
        assertEquals(1f, settings.tvZoom)
    }
}
