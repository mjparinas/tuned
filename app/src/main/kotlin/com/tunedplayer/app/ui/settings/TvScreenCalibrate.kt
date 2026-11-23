package com.tunedplayer.app.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tunedplayer.app.R
import com.tunedplayer.app.data.AppSettings
import com.tunedplayer.app.ui.common.TV_OFFSET_MAX
import com.tunedplayer.app.ui.common.TV_ZOOM_MAX
import com.tunedplayer.app.ui.common.TV_ZOOM_MIN
import com.tunedplayer.app.ui.common.focusRing
import com.tunedplayer.app.ui.common.rememberStartFocus
import kotlin.math.roundToInt

/**
 * Settings › Appearance › Calibrate screen (Android TV only). Draws L-marks in every corner of the safe area so a
 * person can see what the TV crops, then moves and stretches that area with the remote. Changes save as they go and
 * apply to every screen through [com.tunedplayer.app.ui.common.TvSafeArea].
 */
@Composable
fun TvScreenCalibrateScreen(
    settings: AppSettings,
    onBack: () -> Unit,
    onChange: ((AppSettings) -> AppSettings) -> Unit,
) {
    val start = rememberStartFocus()
    val mark = MaterialTheme.colorScheme.primary
    Box(Modifier.fillMaxSize()) {
        val marksLabel = stringResource(R.string.settings_tv_calibrate_marks)
        Canvas(
            Modifier
                .fillMaxSize()
                .semantics { contentDescription = marksLabel },
        ) {
            val stroke = 3.dp.toPx()
            val arm = 48.dp.toPx()
            val inset = stroke / 2

            fun corner(
                x: Float,
                y: Float,
                right: Boolean,
                down: Boolean,
            ) {
                val dx = if (right) arm else -arm
                val dy = if (down) arm else -arm
                drawLine(mark, Offset(x, y), Offset(x + dx, y), stroke, StrokeCap.Square)
                drawLine(mark, Offset(x, y), Offset(x, y + dy), stroke, StrokeCap.Square)
            }
            corner(inset, inset, right = true, down = true)
            corner(size.width - inset, inset, right = false, down = true)
            corner(inset, size.height - inset, right = true, down = false)
            corner(size.width - inset, size.height - inset, right = false, down = false)
        }
        Column(
            Modifier
                .align(Alignment.Center)
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Instructions sit in the middle of the picture so they're the first thing you read on the sofa.
            Text(
                stringResource(R.string.settings_tv_calibrate),
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.settings_tv_calibrate_hint),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            val centre = stringResource(R.string.settings_tv_calibrate_centre)
            val resources = LocalContext.current.resources

            fun offsetLabel(dp: Int): String = when {
                dp == 0 -> centre
                dp > 0 -> resources.getString(R.string.settings_tv_calibrate_plus, dp)
                else -> resources.getString(R.string.settings_tv_calibrate_minus, -dp)
            }
            CompositionLocalProvider(LocalRowStartFocus provides start.takeIf { it.enabled }) {
                SliderRow(
                    stringResource(R.string.settings_tv_calibrate_horizontal),
                    settings.tvOffsetX.toFloat(),
                    -TV_OFFSET_MAX.toFloat()..TV_OFFSET_MAX.toFloat(),
                    steps = TV_OFFSET_MAX * 2 - 1,
                    label = { offsetLabel(it.roundToInt()) },
                ) { v -> onChange { it.copy(tvOffsetX = v.roundToInt()) } }
                SliderRow(
                    stringResource(R.string.settings_tv_calibrate_vertical),
                    settings.tvOffsetY.toFloat(),
                    -TV_OFFSET_MAX.toFloat()..TV_OFFSET_MAX.toFloat(),
                    steps = TV_OFFSET_MAX * 2 - 1,
                    label = { offsetLabel(it.roundToInt()) },
                ) { v -> onChange { it.copy(tvOffsetY = v.roundToInt()) } }
                SliderRow(
                    stringResource(R.string.settings_tv_calibrate_size),
                    settings.tvZoom,
                    TV_ZOOM_MIN..TV_ZOOM_MAX,
                    steps = 13,
                    label = { "${(it * 100).roundToInt()}%" },
                ) { v -> onChange { it.copy(tvZoom = v) } }
                Spacer(Modifier.height(16.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                ) {
                    OutlinedButton(
                        onClick = { onChange { it.copy(tvOffsetX = 0, tvOffsetY = 0, tvZoom = 1f) } },
                        modifier =
                            Modifier
                                .weight(1f)
                                .heightIn(min = 52.dp)
                                .focusRing(MaterialTheme.shapes.small, MaterialTheme.colorScheme.primary),
                    ) {
                        Text(stringResource(R.string.settings_tv_calibrate_reset))
                    }
                    Button(
                        onClick = onBack,
                        modifier =
                            Modifier
                                .weight(1f)
                                .heightIn(min = 52.dp)
                                .focusRing(MaterialTheme.shapes.small, MaterialTheme.colorScheme.primary),
                    ) {
                        Text(stringResource(R.string.settings_tv_calibrate_done))
                    }
                }
            }
        }
    }
}
