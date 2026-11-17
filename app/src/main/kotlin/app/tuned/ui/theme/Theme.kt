package app.tuned.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.tuned.data.ThemeMode

/** Brand colours (docs/decisions/branding.md): Tuned violet with a signal teal accent. */
object Brand {
    val Violet = Color(0xFF6D4AFF)
    val VioletDeep = Color(0xFF3B1FB8)
    val Teal = Color(0xFF00C2A8)
    val Ink = Color(0xFF0E0B1A)
}

private val Light =
    lightColorScheme(
        primary = Color(0xFF5A35F0),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFE6DEFF),
        onPrimaryContainer = Color(0xFF1C0A66),
        secondary = Color(0xFF006B5C),
        onSecondary = Color.White,
        secondaryContainer = Color(0xFF9FF2DF),
        onSecondaryContainer = Color(0xFF00201B),
        tertiary = Color(0xFF8E4A8A),
        background = Color(0xFFFCFAFF),
        onBackground = Color(0xFF1B1A21),
        surface = Color(0xFFFCFAFF),
        onSurface = Color(0xFF1B1A21),
        surfaceVariant = Color(0xFFE6E0F0),
        onSurfaceVariant = Color(0xFF48454F),
        surfaceContainerLowest = Color.White,
        surfaceContainerLow = Color(0xFFF6F2FC),
        surfaceContainer = Color(0xFFF0ECF6),
        surfaceContainerHigh = Color(0xFFEAE6F1),
        surfaceContainerHighest = Color(0xFFE4E0EB),
        outline = Color(0xFF79757F),
        outlineVariant = Color(0xFFCAC4D0),
        error = Color(0xFFBA1A1A),
    )

private val Dark =
    darkColorScheme(
        primary = Color(0xFFC9BEFF),
        onPrimary = Color(0xFF2B10A0),
        primaryContainer = Color(0xFF4325D6),
        onPrimaryContainer = Color(0xFFE6DEFF),
        secondary = Color(0xFF5FDCC3),
        onSecondary = Color(0xFF00382F),
        secondaryContainer = Color(0xFF005045),
        onSecondaryContainer = Color(0xFF9FF2DF),
        tertiary = Color(0xFFF5B2EC),
        background = Color(0xFF121018),
        onBackground = Color(0xFFE6E1EA),
        surface = Color(0xFF121018),
        onSurface = Color(0xFFE6E1EA),
        surfaceVariant = Color(0xFF48454F),
        onSurfaceVariant = Color(0xFFCAC4D0),
        surfaceContainerLowest = Color(0xFF0C0B11),
        surfaceContainerLow = Color(0xFF1A1820),
        surfaceContainer = Color(0xFF1E1C25),
        surfaceContainerHigh = Color(0xFF29262F),
        surfaceContainerHighest = Color(0xFF34313A),
        outline = Color(0xFF938F99),
        outlineVariant = Color(0xFF48454F),
        error = Color(0xFFFFB4AB),
    )

/** For OLED screens: true black behind everything, so the video is the only light in the room. */
private fun ColorScheme.pureBlack(): ColorScheme = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF0A090D),
    surfaceContainer = Color(0xFF110F15),
)

private val TunedType =
    Typography().run {
        copy(
            titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold),
            titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
            labelLarge = labelLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.2.sp),
        )
    }

@Composable
fun isDark(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

@Composable
fun TunedTheme(
    mode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColour: Boolean = false,
    pureBlack: Boolean = false,
    content: @Composable () -> Unit,
) {
    val dark = isDark(mode)
    val context = LocalContext.current
    var scheme =
        when {
            dynamicColour && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            }

            dark -> {
                Dark
            }

            else -> {
                Light
            }
        }
    if (dark && pureBlack) scheme = scheme.pureBlack()
    MaterialTheme(colorScheme = scheme, typography = TunedType, content = content)
}
