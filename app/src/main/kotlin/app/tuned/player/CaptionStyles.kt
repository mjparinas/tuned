package app.tuned.player

import android.graphics.Typeface
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import app.tuned.data.CaptionEdge
import app.tuned.data.CaptionFont
import app.tuned.data.CaptionStyle

/** Applies Settings › Captions to Media3's subtitle view. */
// Media3's caption, track and buffer APIs are marked unstable; the version is pinned and Dependabot raises it with CI.
@OptIn(UnstableApi::class)
object CaptionStyles {
    fun apply(
        view: SubtitleView,
        style: CaptionStyle,
    ) {
        val typeface = Typeface.create(family(style.font), if (style.bold) Typeface.BOLD else Typeface.NORMAL)
        view.setStyle(
            CaptionStyleCompat(
                style.textColor,
                withOpacity(style.backgroundColor, style.backgroundOpacity),
                style.windowColor,
                edgeType(style.edge),
                style.edgeColor,
                typeface,
            ),
        )
        view.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * style.textScale)
        view.setApplyEmbeddedStyles(!style.overrideStreamStyle)
        view.setApplyEmbeddedFontSizes(!style.overrideStreamStyle)
        view.setBottomPaddingFraction(style.bottomPadding)
    }

    fun family(font: CaptionFont): Typeface = when (font) {
        CaptionFont.DEFAULT -> Typeface.DEFAULT
        CaptionFont.SANS -> Typeface.SANS_SERIF
        CaptionFont.SERIF -> Typeface.SERIF
        CaptionFont.MONO -> Typeface.MONOSPACE
        CaptionFont.CASUAL -> Typeface.create("casual", Typeface.NORMAL)
    }

    fun withOpacity(
        color: Int,
        opacity: Float,
    ): Int = (color and 0x00FFFFFF) or ((opacity.coerceIn(0f, 1f) * 255).toInt() shl 24)

    private fun edgeType(edge: CaptionEdge): Int = when (edge) {
        CaptionEdge.NONE -> CaptionStyleCompat.EDGE_TYPE_NONE
        CaptionEdge.OUTLINE -> CaptionStyleCompat.EDGE_TYPE_OUTLINE
        CaptionEdge.DROP_SHADOW -> CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW
        CaptionEdge.RAISED -> CaptionStyleCompat.EDGE_TYPE_RAISED
        CaptionEdge.DEPRESSED -> CaptionStyleCompat.EDGE_TYPE_DEPRESSED
    }
}
