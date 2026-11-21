package com.tunedplayer.app.ui.common

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.tunedplayer.app.R

@Composable
fun BackTopBar(
    title: String,
    /** Null for a page with nowhere to go back to, such as a section of Home: no arrow. */
    onBack: (() -> Unit)?,
    actions: @Composable RowScope.() -> Unit = {},
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
    )
}
