package com.tunedplayer.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tunedplayer.app.R
import com.tunedplayer.app.core.ScreenLayout
import com.tunedplayer.app.data.AppSettings
import com.tunedplayer.app.data.CaptionEdge
import com.tunedplayer.app.data.CaptionFont
import com.tunedplayer.app.data.CaptionStyle
import com.tunedplayer.app.player.CaptionStyles
import com.tunedplayer.app.ui.common.BackTopBar
import com.tunedplayer.app.ui.common.rememberStartFocus
import com.tunedplayer.app.ui.common.windowWidthDp
import kotlin.math.roundToInt

/** Settings › Captions, with a live preview over a fake video frame. */
@Composable
fun CaptionSettingsScreen(
    settings: AppSettings,
    onBack: () -> Unit,
    vm: SettingsViewModel = viewModel(),
) {
    val c = settings.captions
    val set: (CaptionStyle.() -> CaptionStyle) -> Unit = { change -> vm.update { it.copy(captions = it.captions.change()) } }
    // On a TV the remote starts on the first row (Show captions), so OK alone turns them on or off.
    val start = rememberStartFocus()
    Scaffold(topBar = { BackTopBar(stringResource(R.string.settings_captions), onBack) }) { padding ->
        val rows: @Composable (Modifier) -> Unit = { modifier ->
            CompositionLocalProvider(LocalRowStartFocus provides start.takeIf { it.enabled }) { CaptionRows(c, set, vm, modifier) }
        }
        if (ScreenLayout.panes(windowWidthDp())) {
            // A wide screen (a TV, a tablet) puts the preview beside the rows: on a TV 480 dp tall, a preview above
            // left room for about two rows, so every press scrolled.
            Row(Modifier.padding(padding).fillMaxSize()) {
                Box(Modifier.weight(1f)) { CaptionPreview(c) }
                rows(Modifier.weight(1f))
            }
        } else {
            Column(Modifier.padding(padding).fillMaxSize()) {
                CaptionPreview(c)
                rows(Modifier)
            }
        }
    }
}

/** The rows under the preview, which scroll while the preview stays in view. */
@Composable
private fun CaptionRows(
    c: CaptionStyle,
    set: (CaptionStyle.() -> CaptionStyle) -> Unit,
    vm: SettingsViewModel,
    modifier: Modifier = Modifier,
) {
    Column(modifier.verticalScroll(rememberScrollState()).navigationBarsPadding()) {
        SwitchRow(stringResource(R.string.captions_show), c.enabled, stringResource(R.string.captions_show_summary)) { on ->
            set {
                copy(enabled = on)
            }
        }
        ChoiceRow(stringResource(R.string.captions_language), languageCodes, c.language, label = { languageName(it) }) { l ->
            set { copy(language = l) }
        }
        SectionHeader(stringResource(R.string.captions_text))
        SliderRow(
            stringResource(R.string.captions_size),
            c.textScale,
            0.5f..2.5f,
            steps = 7,
            label = { "${(it * 100).roundToInt()}%" },
        ) { v ->
            set { copy(textScale = v) }
        }
        ChoiceRow(
            stringResource(R.string.captions_font),
            CaptionFont.entries,
            c.font,
            label = { stringResource(fontLabel(it)) },
        ) { f ->
            set { copy(font = f) }
        }
        SwitchRow(stringResource(R.string.captions_bold), c.bold) { on -> set { copy(bold = on) } }
        ColourRow(stringResource(R.string.captions_text_colour), c.textColor) { col -> set { copy(textColor = col) } }
        ChoiceRow(
            stringResource(R.string.captions_edge),
            CaptionEdge.entries,
            c.edge,
            label = { stringResource(edgeLabel(it)) },
        ) { e ->
            set { copy(edge = e) }
        }
        ColourRow(stringResource(R.string.captions_edge_colour), c.edgeColor) { col -> set { copy(edgeColor = col) } }
        SectionHeader(stringResource(R.string.captions_background))
        ColourRow(
            stringResource(R.string.captions_background_colour),
            c.backgroundColor,
        ) { col -> set { copy(backgroundColor = col) } }
        SliderRow(
            stringResource(R.string.captions_background_opacity),
            c.backgroundOpacity,
            0f..1f,
            steps = 9,
            label = { "${(it * 100).roundToInt()}%" },
        ) { v -> set { copy(backgroundOpacity = v) } }
        SliderRow(
            stringResource(R.string.captions_position),
            c.bottomPadding,
            0f..0.4f,
            steps = 7,
            label = { "${(it * 100).roundToInt()}%" },
        ) { v -> set { copy(bottomPadding = v) } }
        SwitchRow(
            stringResource(R.string.captions_override),
            c.overrideStreamStyle,
            stringResource(R.string.captions_override_summary),
        ) { on ->
            set { copy(overrideStreamStyle = on) }
        }
        SettingRow(stringResource(R.string.captions_reset), onClick = {
            vm.update { it.copy(captions = CaptionStyle(enabled = c.enabled, language = c.language)) }
        })
    }
}

@Composable
private fun CaptionPreview(c: CaptionStyle) {
    val shadow =
        when (c.edge) {
            CaptionEdge.NONE -> null
            CaptionEdge.OUTLINE, CaptionEdge.RAISED, CaptionEdge.DEPRESSED -> Shadow(Color(c.edgeColor), blurRadius = 3f)
            CaptionEdge.DROP_SHADOW -> Shadow(Color(c.edgeColor), offset = Offset(3f, 3f), blurRadius = 4f)
        }
    val family =
        when (c.font) {
            CaptionFont.DEFAULT, CaptionFont.SANS -> FontFamily.SansSerif
            CaptionFont.SERIF -> FontFamily.Serif
            CaptionFont.MONO -> FontFamily.Monospace
            CaptionFont.CASUAL -> FontFamily.Cursive
        }
    Box(
        Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .height(180.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF2B2F4A), Color(0xFF5A4A7A), Color(0xFF1C3B3A)))),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Text(
            stringResource(R.string.captions_preview),
            style =
                TextStyle(
                    color = Color(c.textColor),
                    fontSize = (18 * c.textScale).sp,
                    fontFamily = family,
                    fontWeight = if (c.bold) FontWeight.Bold else FontWeight.Normal,
                    shadow = shadow,
                    textAlign = TextAlign.Center,
                    background = Color(CaptionStyles.withOpacity(c.backgroundColor, c.backgroundOpacity)),
                ),
            modifier = Modifier.padding(bottom = (180 * c.bottomPadding).dp + 8.dp, start = 16.dp, end = 16.dp),
        )
    }
}

private fun fontLabel(font: CaptionFont): Int = when (font) {
    CaptionFont.DEFAULT -> R.string.font_default
    CaptionFont.SANS -> R.string.font_sans
    CaptionFont.SERIF -> R.string.font_serif
    CaptionFont.MONO -> R.string.font_mono
    CaptionFont.CASUAL -> R.string.font_casual
}

private fun edgeLabel(edge: CaptionEdge): Int = when (edge) {
    CaptionEdge.NONE -> R.string.edge_none
    CaptionEdge.OUTLINE -> R.string.edge_outline
    CaptionEdge.DROP_SHADOW -> R.string.edge_drop_shadow
    CaptionEdge.RAISED -> R.string.edge_raised
    CaptionEdge.DEPRESSED -> R.string.edge_depressed
}
