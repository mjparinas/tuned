package app.tuned.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import app.tuned.R
import app.tuned.container
import app.tuned.player.PlayerActivity
import kotlinx.coroutines.flow.first

/** Home screen widget: favourite channels, each with what's on now; a tap starts the channel. */
class FavouritesWidget : GlanceAppWidget() {
    private data class Row(
        val id: Long,
        val name: String,
        val now: String?,
    )

    override suspend fun provideGlance(
        context: Context,
        id: GlanceId,
    ) {
        val rows = load(context)
        provideContent { GlanceTheme { Content(context, rows) } }
    }

    private suspend fun load(context: Context): List<Row> {
        val c = context.container
        if (c.credentials.load() == null) return emptyList()
        val settings = c.settings.settings.first()
        val favourites =
            c.db
                .library()
                .favouriteChannels()
                .first()
                .filter { it.allowed(settings) }
                .take(MAX_ROWS)
        val ids = favourites.mapNotNull { it.tvgId }.distinct()
        val now = System.currentTimeMillis()
        val onNow =
            if (ids.isEmpty()) {
                emptyMap()
            } else {
                c.db
                    .programmes()
                    .onNow(ids, now)
                    .associate { it.channelId to it.title }
            }
        return favourites.map { Row(it.id, it.name, it.tvgId?.let(onNow::get)) }
    }

    @Composable
    private fun Content(
        context: Context,
        rows: List<Row>,
    ) {
        Column(
            GlanceModifier
                .fillMaxSize()
                .background(GlanceTheme.colors.widgetBackground)
                .cornerRadius(16.dp)
                .padding(12.dp),
        ) {
            Text(
                context.getString(R.string.widget_favourites),
                style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 14.sp, color = GlanceTheme.colors.primary),
                modifier = GlanceModifier.padding(bottom = 6.dp),
            )
            if (rows.isEmpty()) {
                Text(
                    context.getString(R.string.widget_empty),
                    style = TextStyle(fontSize = 13.sp, color = GlanceTheme.colors.onSurfaceVariant),
                )
                return@Column
            }
            LazyColumn {
                items(rows, itemId = { it.id }) { row ->
                    Column(
                        GlanceModifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp)
                            .clickable(actionStartActivity(PlayerActivity.intent(context, row.id))),
                    ) {
                        Text(
                            row.name,
                            maxLines = 1,
                            style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium, color = GlanceTheme.colors.onSurface),
                        )
                        row.now?.let {
                            Text(it, maxLines = 1, style = TextStyle(fontSize = 12.sp, color = GlanceTheme.colors.onSurfaceVariant))
                        }
                    }
                }
            }
        }
    }

    private companion object {
        const val MAX_ROWS = 30
    }
}

class FavouritesWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = FavouritesWidget()
}
