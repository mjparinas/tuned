package com.tunedplayer.app.ui.browse

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tunedplayer.app.R
import com.tunedplayer.app.core.ContentKind
import com.tunedplayer.app.core.TitleSort

/**
 * Movies' and Shows' sort, first in the tab's chip row (Melvin, 2026-11-20: "sort by newest first or sort by the
 * rating"): the chip names the order in use, and a press lists the others. Each tab keeps its own, in Settings. A short
 * menu, not a dialog, so a remote goes straight down the choices.
 */
@Composable
internal fun SortChip(
    vm: BrowseViewModel,
    kind: ContentKind,
) {
    val order by vm.sort(kind).collectAsStateWithLifecycle()
    var open by rememberSaveable { mutableStateOf(false) }
    val name = stringResource(sortName(order))
    val label = stringResource(R.string.sort_chip_a11y, name)
    Box {
        FilterChip(
            selected = order != TitleSort.Order.PROVIDER,
            onClick = { open = true },
            leadingIcon = { Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = null, modifier = Modifier.size(16.dp)) },
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp)) },
            label = { Text(if (order == TitleSort.Order.PROVIDER) stringResource(R.string.sort_chip) else name) },
            modifier = Modifier.semantics { contentDescription = label },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            TitleSort.Order.entries.forEach { choice ->
                val on = choice == order
                DropdownMenuItem(
                    text = { Text(stringResource(sortName(choice))) },
                    onClick = {
                        open = false
                        if (!on) vm.setSort(kind, choice)
                    },
                    // The tick says which is in use, and so does the item to a screen reader.
                    trailingIcon =
                        if (on) {
                            { Icon(Icons.Filled.Check, contentDescription = null) }
                        } else {
                            null
                        },
                    modifier =
                        Modifier.semantics {
                            selected = on
                            role = Role.RadioButton
                        },
                )
            }
        }
    }
}

internal fun sortName(order: TitleSort.Order): Int = when (order) {
    TitleSort.Order.PROVIDER -> R.string.sort_provider
    TitleSort.Order.ADDED -> R.string.sort_added
    TitleSort.Order.YEAR -> R.string.sort_year
    TitleSort.Order.RATING -> R.string.sort_rating
    TitleSort.Order.TITLE -> R.string.sort_title
}
