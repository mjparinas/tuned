package app.tuned.widget

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import app.tuned.R
import app.tuned.container
import app.tuned.core.ContentKind
import app.tuned.core.Titles
import app.tuned.data.AppSettings
import app.tuned.data.hiddenKeys
import app.tuned.player.PlayerActivity
import kotlinx.coroutines.flow.first

/**
 * Home screen widget: the films and episodes in progress, newest first, each with its poster, how far in and the time
 * left; a tap picks it up where it was left (Melvin, 2026-11-19, docs/decisions/architecture.md). Episodes show TMDB's
 * name when the device already knows it ("Harbour Town · The Lighthouse Keeper"), never looking it up for the widget.
 * One title on a small square or a short strip, posters side by side when there's room.
 */
class ContinueWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(
        context: Context,
        id: GlanceId,
    ) {
        val data = load(context)
        provideContent { WidgetTheme(data.look) { ContinueContent(data) } }
    }

    internal companion object {
        private const val MAX_CARDS = 6
        private const val POSTER_W = 160
        private const val POSTER_H = 240

        suspend fun load(context: Context): ContinueData {
            val c = context.container
            val settings = c.settings.settings.first()
            val look = WidgetLook.of(settings)
            if (c.credentials.load() == null) return ContinueData(look, signedIn = false, cards = emptyList())
            return ContinueData(look, signedIn = true, cards = cards(context, settings))
        }

        /** The films and episodes in progress, newest first, as cards. */
        suspend fun cards(
            context: Context,
            settings: AppSettings,
        ): List<ContinueCard> {
            val c = context.container
            val hidden = settings.hiddenKeys()
            val items = c.db.items()
            val rows =
                (
                    items.continueWatching(ContentKind.MOVIE.code, hidden, settings.showAdult).first() +
                        items.continueWatching(ContentKind.SERIES.code, hidden, settings.showAdult).first()
                ).sortedByDescending { it.watchedAt }
                    .take(MAX_CARDS)
            val posters = WidgetArt.load(context, rows.map { it.logo }, POSTER_W, POSTER_H)
            return rows.mapIndexed { i, row ->
                val show = row.showName
                val season = row.season
                val episode = row.episode
                val subtitle =
                    if (show != null && season != null && episode != null) {
                        c.metadata.cachedEpisode(show, season, episode)?.name
                            ?: context.getString(R.string.widget_episode_number, season, episode)
                    } else {
                        null
                    }
                ContinueCard(
                    id = row.id,
                    title = Titles.display(show ?: row.name),
                    subtitle = subtitle,
                    poster = posters[i],
                    fraction = row.fraction,
                    minutesLeft = ((row.durationMs - row.positionMs) / MINUTE).toInt().coerceAtLeast(1),
                )
            }
        }

        private const val MINUTE = 60_000L
    }
}

class ContinueWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ContinueWidget()
}

internal data class ContinueData(
    val look: WidgetLook,
    val signedIn: Boolean,
    val cards: List<ContinueCard>,
)

internal data class ContinueCard(
    val id: Long,
    val title: String,
    /** The episode's name, or "S1 E2" when TMDB's isn't known; null for a film. */
    val subtitle: String?,
    val poster: Bitmap?,
    val fraction: Float,
    val minutesLeft: Int,
)

@Composable
internal fun ContinueContent(data: ContinueData) {
    val context = LocalContext.current
    val size = LocalSize.current
    val compact = size.height < POSTERS_FROM_HEIGHT
    when {
        !data.signedIn -> {
            WidgetMessage(
                context.getString(R.string.widget_signed_out),
                context.getString(R.string.widget_continue_signed_out_body),
                context.getString(R.string.widget_open_tuned),
                compact,
            )
        }

        data.cards.isEmpty() -> {
            WidgetMessage(
                context.getString(R.string.widget_continue_empty_title),
                context.getString(R.string.widget_continue_empty),
                context.getString(R.string.widget_open_tuned),
                compact,
            )
        }

        compact || size.width < POSTERS_FROM_WIDTH -> {
            OneTitle(data.cards.first(), wide = size.width >= POSTERS_FROM_WIDTH)
        }

        else -> {
            Posters(data.cards, size)
        }
    }
}

/** The latest title: poster beside its name when wide, above it when narrow. */
@Composable
private fun OneTitle(
    card: ContinueCard,
    wide: Boolean,
) {
    val context = LocalContext.current
    WidgetCard(
        GlanceModifier
            .clickable(actionStartActivity(PlayerActivity.intent(context, card.id)))
            .semantics { contentDescription = describe(context, card) },
        padding = 10.dp,
    ) {
        if (wide) {
            Row(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                Poster(card, GlanceModifier.width(64.dp).fillMaxHeight())
                Spacer(GlanceModifier.width(12.dp))
                Column(GlanceModifier.defaultWeight()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TunedMark(14.dp, null)
                        Spacer(GlanceModifier.width(6.dp))
                        Text(
                            context.getString(R.string.widget_continue),
                            maxLines = 1,
                            style = TextStyle(fontSize = 11.sp, color = GlanceTheme.colors.onSurfaceVariant),
                        )
                    }
                    Spacer(GlanceModifier.height(4.dp))
                    CardText(card, titleSize = 15)
                }
            }
        } else {
            Poster(card, GlanceModifier.fillMaxWidth().defaultWeight())
            Spacer(GlanceModifier.height(6.dp))
            CardText(card, titleSize = 13)
        }
    }
}

/** Room to spare: the header, then posters side by side. */
@Composable
private fun Posters(
    cards: List<ContinueCard>,
    size: DpSize,
) {
    val context = LocalContext.current
    val count = (size.width / POSTER_WIDTH).toInt().coerceIn(2, MAX_POSTERS)
    val shown = cards.take(count)
    WidgetCard {
        WidgetHeader(context.getString(R.string.widget_continue))
        Row(GlanceModifier.fillMaxWidth().defaultWeight()) {
            shown.forEach { card ->
                Column(
                    GlanceModifier
                        .defaultWeight()
                        .fillMaxHeight()
                        .padding(4.dp)
                        .cornerRadius(14.dp)
                        .clickable(actionStartActivity(PlayerActivity.intent(context, card.id)))
                        .semantics { contentDescription = describe(context, card) },
                ) {
                    Poster(card, GlanceModifier.fillMaxWidth().defaultWeight())
                    Spacer(GlanceModifier.height(6.dp))
                    CardText(card, titleSize = 12)
                }
            }
            repeat(count - shown.size) { Spacer(GlanceModifier.defaultWeight()) }
        }
    }
}

@Composable
private fun Poster(
    card: ContinueCard,
    modifier: GlanceModifier,
) {
    Box(modifier.background(GlanceTheme.colors.surfaceVariant).cornerRadius(12.dp), contentAlignment = Alignment.Center) {
        if (card.poster != null) {
            Image(
                ImageProvider(card.poster),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = GlanceModifier.fillMaxSize(),
            )
        } else {
            Text(
                initials(card.title),
                style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold, color = GlanceTheme.colors.onSurfaceVariant),
            )
        }
    }
}

/** Title, episode, the bar, and the time left. */
@Composable
private fun CardText(
    card: ContinueCard,
    titleSize: Int,
) {
    val context = LocalContext.current
    Text(
        card.title,
        maxLines = 1,
        style = TextStyle(fontSize = titleSize.sp, fontWeight = FontWeight.Medium, color = GlanceTheme.colors.onBackground),
    )
    card.subtitle?.let {
        Text(it, maxLines = 1, style = TextStyle(fontSize = 11.sp, color = GlanceTheme.colors.onSurfaceVariant))
    }
    Spacer(GlanceModifier.height(4.dp))
    ThinProgress(card.fraction)
    Spacer(GlanceModifier.height(3.dp))
    Text(
        context.resources.getQuantityString(R.plurals.widget_minutes_left, card.minutesLeft, card.minutesLeft),
        maxLines = 1,
        style = TextStyle(fontSize = 10.sp, color = GlanceTheme.colors.onSurfaceVariant),
    )
}

/** "Resume Harbour Town, The Lighthouse Keeper. 12 min left". */
private fun describe(
    context: Context,
    card: ContinueCard,
): String {
    val left = context.resources.getQuantityString(R.plurals.widget_minutes_left, card.minutesLeft, card.minutesLeft)
    val what = listOfNotNull(card.title, card.subtitle).joinToString(", ")
    return context.getString(R.string.widget_resume, what, left)
}

/** Shorter than this shows one title. */
private val POSTERS_FROM_HEIGHT = 170.dp

/** Narrower than this shows the poster above the title; wider shows posters side by side (when tall enough). */
private val POSTERS_FROM_WIDTH = 230.dp
private val POSTER_WIDTH = 104.dp
private const val MAX_POSTERS = 5
