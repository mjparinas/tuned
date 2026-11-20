package com.tunedplayer.app.widget

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.glance.GlanceTheme
import androidx.glance.material3.ColorProviders
import com.tunedplayer.app.data.Accent
import com.tunedplayer.app.data.AppSettings
import com.tunedplayer.app.data.ThemeMode
import com.tunedplayer.app.ui.theme.tunedScheme

/**
 * The colours the home screen widgets draw in: the app's own theme, accent and pure black, or the wallpaper's colours
 * when Settings › Appearance › Dynamic colour is on (Material You, Android 12 and later). A theme forced to light or dark
 * in Settings holds on the home screen too; "System" follows the phone, as the app does.
 */
internal data class WidgetLook(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColour: Boolean = false,
    val accent: Accent = Accent.VIOLET,
    val pureBlack: Boolean = false,
) {
    companion object {
        fun of(settings: AppSettings) = WidgetLook(settings.theme, settings.dynamicColour, settings.accent, settings.pureBlack)
    }
}

@Composable
internal fun WidgetTheme(
    look: WidgetLook,
    content: @Composable () -> Unit,
) {
    if (look.dynamicColour && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        GlanceTheme(content = content) // the wallpaper's colours
        return
    }
    val light = tunedScheme(dark = false, accent = look.accent, pureBlack = false)
    val dark = tunedScheme(dark = true, accent = look.accent, pureBlack = look.pureBlack)
    val colours =
        when (look.theme) {
            ThemeMode.SYSTEM -> ColorProviders(light = light, dark = dark)
            ThemeMode.LIGHT -> ColorProviders(light = light, dark = light)
            ThemeMode.DARK -> ColorProviders(light = dark, dark = dark)
        }
    GlanceTheme(colors = colours, content = content)
}
