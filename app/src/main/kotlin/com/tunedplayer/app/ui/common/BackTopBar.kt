package com.tunedplayer.app.ui.common

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.tunedplayer.app.R

@Composable
fun BackTopBar(
    title: String,
    /** Null for a page with nowhere to go back to, such as a section of Home: no arrow. */
    onBack: (() -> Unit)?,
    actions: @Composable RowScope.() -> Unit = {},
    /**
     * Over a screen's own backdrop (the glow behind "Who's watching?"), the bar is see-through so the backdrop shows
     * behind the title, rather than a block of the surface colour (Melvin, 2026-11-21).
     */
    transparent: Boolean = false,
) {
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // On a TV, Tuned's mark leads the bar in the upper left, unless the screen already shows it (Home's rail).
                if (isTelevision() && !LocalTvBrandShown.current) TvBrand(stacked = false)
                if (onBack != null) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                }
            }
        },
        actions = actions,
        colors =
            if (transparent) {
                TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
                    actionIconContentColor = MaterialTheme.colorScheme.onBackground,
                )
            } else {
                TopAppBarDefaults.topAppBarColors()
            },
    )
}
