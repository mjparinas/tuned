package app.tuned.ui.browse

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.tuned.R
import app.tuned.ui.details.MovieScreen
import app.tuned.ui.guide.GuideScreen
import app.tuned.ui.shows.ShowScreen

/** What the pane beside the list shows on a wide screen: a film's page, a show's episodes or a channel's guide. */
internal data class Detail(
    val kind: Kind,
    val id: Long = 0,
    val showKey: String = "",
    val title: String = "",
) {
    enum class Kind { MOVIE, SHOW, GUIDE }

    companion object {
        fun movie(id: Long) = Detail(Kind.MOVIE, id = id)

        fun show(
            showKey: String,
            title: String,
        ) = Detail(Kind.SHOW, showKey = showKey, title = title)

        fun guide(id: Long) = Detail(Kind.GUIDE, id = id)

        /** Kept across rotation and process death, so the pane comes back as it was. */
        val saver: Saver<Detail?, String> =
            Saver(
                save = { d -> d?.let { listOf(it.kind.name, it.id.toString(), it.showKey, it.title).joinToString(SEP) } ?: "" },
                restore = { saved ->
                    saved.split(SEP).takeIf { it.size == 4 }?.let { Detail(Kind.valueOf(it[0]), it[1].toLong(), it[2], it[3]) }
                },
            )

        private const val SEP = "\u0000"
    }
}

/** On tablets and unfolded foldables the tabs run down the left edge instead of along the bottom. */
@Composable
internal fun HomeRail(
    selected: Int,
    onSelect: (Int) -> Unit,
    onOpenDownloads: () -> Unit,
) {
    NavigationRail {
        Spacer(Modifier.height(8.dp))
        tabs.forEachIndexed { index, t ->
            NavigationRailItem(
                selected = index == selected,
                onClick = { onSelect(index) },
                icon = { Icon(t.icon, contentDescription = null) },
                label = { Text(stringResource(t.label)) },
            )
        }
        NavigationRailItem(
            selected = false,
            onClick = onOpenDownloads,
            icon = { DownloadsIcon() },
            label = { Text(stringResource(R.string.downloads)) },
        )
    }
}

/**
 * The same pages a phone opens full screen, beside the list. Each gets its own view models, keyed by what it shows,
 * so switching from one film to the next never flashes the last one's details.
 */
@Composable
internal fun DetailPane(
    detail: Detail,
    onPlay: (Long) -> Unit,
    onClose: () -> Unit,
) {
    key(detail) {
        when (detail.kind) {
            Detail.Kind.MOVIE -> {
                MovieScreen(itemId = detail.id, onPlay = onPlay, onBack = onClose, vm = viewModel(key = "pane:movie:${detail.id}"))
            }

            Detail.Kind.SHOW -> {
                ShowScreen(
                    showKey = detail.showKey,
                    title = detail.title,
                    onPlay = onPlay,
                    onBack = onClose,
                    vm = viewModel(key = "pane:show:${detail.showKey}"),
                    details = viewModel(key = "pane:details:${detail.showKey}"),
                )
            }

            Detail.Kind.GUIDE -> {
                GuideScreen(itemId = detail.id, onPlay = onPlay, onBack = onClose, vm = viewModel(key = "pane:guide:${detail.id}"))
            }
        }
    }
}
