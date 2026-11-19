package app.tuned.ui.theme

import android.content.Context
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.tuned.data.Accent
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

/** The primary tones for each accent, light then dark (Material 3 tones 40/90/10 and 80/20/30/90). */
private class Tones(
    val light: Long,
    val lightContainer: Long,
    val onLightContainer: Long,
    val dark: Long,
    val onDark: Long,
    val darkContainer: Long,
    val onDarkContainer: Long,
)

private val accents =
    mapOf(
        // Blue's light tone was 0xFF1F5FD6, 4.4:1 on the darkest card colour; this one is 5:1 (Melvin, 2026-11-19).
        Accent.BLUE to Tones(0xFF1B57C4, 0xFFD9E2FF, 0xFF001A43, 0xFFB0C6FF, 0xFF002D6E, 0xFF00449B, 0xFFD9E2FF),
        Accent.TEAL to Tones(0xFF006B5C, 0xFF9FF2DF, 0xFF00201B, 0xFF5FDCC3, 0xFF00382F, 0xFF005045, 0xFF9FF2DF),
        Accent.GREEN to Tones(0xFF386A20, 0xFFB8F397, 0xFF042100, 0xFF9CD67D, 0xFF0B3900, 0xFF205107, 0xFFB8F397),
        Accent.ORANGE to Tones(0xFF9A4600, 0xFFFFDBC8, 0xFF321300, 0xFFFFB68B, 0xFF532200, 0xFF763300, 0xFFFFDBC8),
        Accent.PINK to Tones(0xFFB0136E, 0xFFFFD8E7, 0xFF3D0022, 0xFFFFB0CF, 0xFF640039, 0xFF8C0053, 0xFFFFD8E7),
        Accent.RED to Tones(0xFFBB1A2C, 0xFFFFDAD9, 0xFF410007, 0xFFFFB3B1, 0xFF680011, 0xFF92001C, 0xFFFFDAD9),
    )

/** The swatch shown in Settings for each accent. */
fun Accent.swatch(): Color = accents[this]?.let { Color(it.light) } ?: Light.primary

private fun ColorScheme.withAccent(
    accent: Accent,
    dark: Boolean,
): ColorScheme {
    val t = accents[accent] ?: return this // violet is the built-in scheme
    return if (dark) {
        copy(
            primary = Color(t.dark),
            onPrimary = Color(t.onDark),
            primaryContainer = Color(t.darkContainer),
            onPrimaryContainer = Color(t.onDarkContainer),
        )
    } else {
        copy(
            primary = Color(t.light),
            onPrimary = Color.White,
            primaryContainer = Color(t.lightContainer),
            onPrimaryContainer = Color(t.onLightContainer),
        )
    }
}

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

/**
 * Soft, rounded corners everywhere, like a modern website (Melvin, 2026-11-17: "rounded input boxes and buttons").
 * Set once here, so every text field (extraSmall), chip (small), card (medium) and sheet or dialog (large and up)
 * follows. Buttons are already fully round in Material 3.
 */
val TunedShapes =
    Shapes(
        extraSmall = RoundedCornerShape(14.dp),
        small = RoundedCornerShape(percent = 50),
        medium = RoundedCornerShape(20.dp),
        large = RoundedCornerShape(24.dp),
        extraLarge = RoundedCornerShape(32.dp),
    )

/** Tuned's own colours (not the wallpaper's) for a theme, accent and pure black, as [TunedTheme] uses them. */
internal fun tunedScheme(
    dark: Boolean,
    accent: Accent,
    pureBlack: Boolean,
): ColorScheme {
    val scheme = if (dark) Dark.withAccent(accent, dark = true) else Light.withAccent(accent, dark = false)
    return if (dark && pureBlack) scheme.pureBlack() else scheme
}

/**
 * The accent the player draws in (its seek bar), as a View colour. The player is always dark, so this is the dark
 * theme's primary for the chosen accent, or the wallpaper's when dynamic colour is on, matching the rest of the app.
 */
fun playerAccent(
    context: Context,
    accent: Accent,
    dynamicColour: Boolean,
): Int {
    val scheme =
        if (dynamicColour && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            dynamicDarkColorScheme(context)
        } else {
            tunedScheme(dark = true, accent = accent, pureBlack = false)
        }
    return scheme.primary.toArgb()
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
    accent: Accent = Accent.VIOLET,
    content: @Composable () -> Unit,
) {
    val dark = isDark(mode)
    val context = LocalContext.current
    var scheme =
        if (dynamicColour && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else {
            tunedScheme(dark, accent, pureBlack = false)
        }
    if (dark && pureBlack) scheme = scheme.pureBlack()
    MaterialTheme(colorScheme = scheme, typography = TunedType, shapes = TunedShapes, content = content)
}
