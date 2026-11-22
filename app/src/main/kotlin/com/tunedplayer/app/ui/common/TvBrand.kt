package com.tunedplayer.app.ui.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tunedplayer.app.R

/**
 * Whether Tuned's mark already shows in the upper left of a TV screen (the rail on Home), so a title bar inside the
 * same screen (Downloads, a film or guide pane) doesn't show it a second time.
 */
val LocalTvBrandShown = compositionLocalOf { false }

/**
 * Tuned's mark and name in the upper left of a TV screen (Melvin, 2026-11-21: "Since there's so much real estate on TV,
 * I want to see the tuned logo in the upper left-hand corner"). The mark is the app icon on its own dark square, so it
 * reads on both themes; the name takes the theme's text colour. It takes no focus, so the remote never lands on it.
 * [stacked] puts the name under the mark, to fit the 80 dp rail; otherwise it sits beside it, for a title bar.
 */
@Composable
fun TvBrand(
    stacked: Boolean,
    modifier: Modifier = Modifier,
) {
    val name = stringResource(R.string.app_name)
    if (stacked) {
        Column(
            modifier.semantics(mergeDescendants = true) {},
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Image(painterResource(R.drawable.ic_brand_mark), contentDescription = null, modifier = Modifier.size(MARK_SIZE))
            Text(
                name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    } else {
        Row(
            modifier.padding(start = 12.dp).semantics(mergeDescendants = true) {},
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(painterResource(R.drawable.ic_brand_mark), contentDescription = null, modifier = Modifier.size(MARK_SIZE))
            Spacer(Modifier.width(10.dp))
            Text(
                name,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.width(12.dp))
        }
    }
}

private val MARK_SIZE = 36.dp
