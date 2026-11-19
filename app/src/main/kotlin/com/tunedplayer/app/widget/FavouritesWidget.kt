package com.tunedplayer.app.widget

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import com.tunedplayer.app.R
import com.tunedplayer.app.container
import com.tunedplayer.app.core.ChannelNames
import com.tunedplayer.app.data.AppSettings
import com.tunedplayer.app.player.PlayerActivity
import kotlinx.coroutines.flow.first

/**
 * Home screen widget: favourite channels as logo tiles with what's on now and how far through it is; a tap starts the
 * channel and the Tuned header opens the app (Melvin, 2026-11-19: "build all those widget enhancements",
 * docs/decisions/architecture.md). It follows the size it's given: one channel on a small square, a strip of logos when
 * short and wide, and a grid with the header when there's room. Favourites are the profile in use's, without what the
 * profile or the parental lock hides.
 */
class FavouritesWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(
        context: Context,
        id: GlanceId,
    ) {
        val data = load(context)
        provideContent { WidgetTheme(data.look) { FavouritesContent(data) } }
    }

    internal companion object {
        /** More than a full-screen grid holds, and few enough to keep the launcher's bitmap budget. */
        private const val MAX_TILES = 20
        private const val LOGO_PX = 120

        /** The widget's channels from the cache: names cleaned, logos small, and the programme on each now. */
        suspend fun load(context: Context): FavouritesData {
            val c = context.container
            val settings = c.settings.settings.first()
            val look = WidgetLook.of(settings)
            if (c.credentials.load() == null) return FavouritesData(look, signedIn = false, tiles = emptyList())
            return FavouritesData(look, signedIn = true, tiles = tiles(context, settings))
        }

        /** The favourites, as tiles. */
        suspend fun tiles(
            context: Context,
            settings: AppSettings,
        ): List<ChannelTile> {
            val c = context.container
            val favourites =
                c.db
                    .library()
                    .favouriteChannels()
                    .first()
                    .filter { it.allowed(settings) }
                    .take(MAX_TILES)
            val ids = favourites.mapNotNull { it.tvgId }.distinct()
            val now = System.currentTimeMillis()
            val onNow =
                if (ids.isEmpty()) {
                    emptyMap()
                } else {
                    c.db
                        .programmes()
                        .onNow(ids, now)
                        .associateBy { it.channelId }
                }
            val logos = WidgetArt.load(context, favourites.map { it.logo }, LOGO_PX, LOGO_PX)
            return favourites.mapIndexed { i, f ->
                val programme = f.tvgId?.let(onNow::get)
                val length = programme?.let { it.stop - it.start } ?: 0
                ChannelTile(
                    id = f.id,
                    name = ChannelNames.short(f.name),
                    logo = logos[i],
                    now = programme?.title,
                    progress = if (programme != null && length > 0) (now - programme.start).toFloat() / length else null,
                )
            }
        }
    }
}

class FavouritesWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = FavouritesWidget()
}

internal data class FavouritesData(
    val look: WidgetLook,
    val signedIn: Boolean,
    val tiles: List<ChannelTile>,
)

internal data class ChannelTile(
    val id: Long,
    val name: String,
    val logo: Bitmap?,
    val now: String?,
    val progress: Float?,
)

/** The widget's layout for its size: one channel, a strip, or the grid; or a way in when there's nothing to show. */
@Composable
internal fun FavouritesContent(data: FavouritesData) {
    val context = LocalContext.current
    val size = LocalSize.current
    val compact = size.height < STRIP_BELOW
    when {
        !data.signedIn -> {
            WidgetMessage(
                context.getString(R.string.widget_signed_out),
                context.getString(R.string.widget_signed_out_body),
                context.getString(R.string.widget_open_tuned),
                compact,
            )
        }

        data.tiles.isEmpty() -> {
            WidgetMessage(
                context.getString(R.string.widget_empty_title),
                context.getString(R.string.widget_empty),
                context.getString(R.string.widget_add_favourites),
                compact,
            )
        }

        size.width < SINGLE_BELOW -> {
            SingleChannel(data.tiles.first())
        }

        compact -> {
            ChannelStrip(data.tiles, size)
        }

        else -> {
            ChannelGrid(data.tiles, size)
        }
    }
}

/** A small square: the first favourite, big. */
@Composable
private fun SingleChannel(tile: ChannelTile) {
    val context = LocalContext.current
    WidgetCard(
        GlanceModifier
            .clickable(actionStartActivity(PlayerActivity.intent(context, tile.id)))
            .semantics { contentDescription = describe(context, tile) },
        padding = 10.dp,
    ) {
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TunedMark(16.dp, null)
            Spacer(GlanceModifier.width(6.dp))
            Text(
                tile.name,
                maxLines = 1,
                style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold, color = GlanceTheme.colors.onBackground),
            )
        }
        Spacer(GlanceModifier.height(8.dp))
        LogoPlate(tile.logo, tile.name, GlanceModifier.fillMaxWidth().defaultWeight(), initialsSize = 24)
        tile.now?.let { now ->
            Spacer(GlanceModifier.height(6.dp))
            Text(now, maxLines = 1, style = TextStyle(fontSize = 12.sp, color = GlanceTheme.colors.onSurfaceVariant))
        }
        tile.progress?.let { p ->
            Spacer(GlanceModifier.height(4.dp))
            ThinProgress(p)
        }
    }
}

/** Short and wide: the Tuned mark, then a row of logos with their names. */
@Composable
private fun ChannelStrip(
    tiles: List<ChannelTile>,
    size: DpSize,
) {
    val context = LocalContext.current
    val count = ((size.width - STRIP_MARK) / STRIP_TILE).toInt().coerceAtLeast(1)
    WidgetCard(padding = 8.dp) {
        Row(GlanceModifier.fillMaxWidth().defaultWeight(), verticalAlignment = Alignment.CenterVertically) {
            Column(
                GlanceModifier.fillMaxHeight().padding(horizontal = 4.dp).clickable(WidgetParts.openApp(context)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TunedMark(24.dp, context.getString(R.string.widget_open_tuned))
            }
            val shown = tiles.take(count)
            shown.forEach { tile ->
                Column(
                    GlanceModifier
                        .defaultWeight()
                        .fillMaxHeight()
                        .padding(horizontal = 3.dp)
                        .cornerRadius(14.dp)
                        .clickable(actionStartActivity(PlayerActivity.intent(context, tile.id)))
                        .semantics { contentDescription = describe(context, tile) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LogoPlate(tile.logo, tile.name, GlanceModifier.fillMaxWidth().defaultWeight())
                    Spacer(GlanceModifier.height(4.dp))
                    TileName(tile.name)
                    tile.progress?.let { p ->
                        Spacer(GlanceModifier.height(3.dp))
                        ThinProgress(p, GlanceModifier.padding(horizontal = 6.dp))
                    }
                }
            }
            repeat(count - shown.size) { Spacer(GlanceModifier.defaultWeight()) }
        }
    }
}

/** Room to spare: the header, then as many rows of tiles as fit, each with what's on now. */
@Composable
private fun ChannelGrid(
    tiles: List<ChannelTile>,
    size: DpSize,
) {
    val context = LocalContext.current
    val columns = (size.width / GRID_TILE_WIDTH).toInt().coerceIn(2, MAX_COLUMNS)
    val rows = ((size.height - GRID_HEADER) / GRID_TILE_HEIGHT).toInt().coerceAtLeast(1)
    WidgetCard {
        WidgetHeader(context.getString(R.string.widget_favourites))
        tiles.take(columns * rows).chunked(columns).forEach { row ->
            Row(GlanceModifier.fillMaxWidth().defaultWeight()) {
                row.forEach { tile ->
                    Column(
                        GlanceModifier
                            .defaultWeight()
                            .fillMaxHeight()
                            .padding(4.dp)
                            .cornerRadius(14.dp)
                            .clickable(actionStartActivity(PlayerActivity.intent(context, tile.id)))
                            .semantics { contentDescription = describe(context, tile) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        LogoPlate(tile.logo, tile.name, GlanceModifier.fillMaxWidth().defaultWeight())
                        Spacer(GlanceModifier.height(5.dp))
                        TileName(tile.name)
                        Text(
                            tile.now ?: "",
                            maxLines = 1,
                            style =
                                TextStyle(
                                    fontSize = 11.sp,
                                    color = GlanceTheme.colors.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                ),
                        )
                        Spacer(GlanceModifier.height(3.dp))
                        if (tile.progress != null) {
                            ThinProgress(tile.progress, GlanceModifier.padding(horizontal = 6.dp))
                        } else {
                            Spacer(GlanceModifier.height(3.dp))
                        }
                    }
                }
                repeat(columns - row.size) { Spacer(GlanceModifier.defaultWeight()) }
            }
        }
    }
}

@Composable
private fun TileName(name: String) {
    Text(
        name,
        maxLines = 1,
        style =
            TextStyle(
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = GlanceTheme.colors.onBackground,
                textAlign = TextAlign.Center,
            ),
    )
}

/** What a screen reader says for a tile: "Watch CP24. On now: The National Hour". */
private fun describe(
    context: Context,
    tile: ChannelTile,
): String = tile.now?.let { context.getString(R.string.widget_watch_on_now, tile.name, it) }
    ?: context.getString(R.string.widget_watch, tile.name)

/** Narrower than this shows one channel. */
private val SINGLE_BELOW = 180.dp

/** Shorter than this shows a strip of logos. */
private val STRIP_BELOW = 150.dp
private val STRIP_MARK = 48.dp
private val STRIP_TILE = 72.dp
private val GRID_HEADER = 56.dp
private val GRID_TILE_WIDTH = 96.dp
private val GRID_TILE_HEIGHT = 110.dp
private const val MAX_COLUMNS = 5
