package com.tunedplayer.app.screenshots

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.ExperimentalGlanceApi
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.compose
import androidx.glance.appwidget.provideContent
import com.github.takahirom.roborazzi.captureRoboImage
import com.tunedplayer.app.container
import com.tunedplayer.app.data.Accent
import com.tunedplayer.app.data.ThemeMode
import com.tunedplayer.app.data.db.FavouriteEntity
import com.tunedplayer.app.widget.ContinueContent
import com.tunedplayer.app.widget.ContinueData
import com.tunedplayer.app.widget.ContinueWidget
import com.tunedplayer.app.widget.FavouritesContent
import com.tunedplayer.app.widget.FavouritesData
import com.tunedplayer.app.widget.FavouritesWidget
import com.tunedplayer.app.widget.WidgetLook
import com.tunedplayer.app.widget.WidgetTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * The home screen widgets (docs/decisions/architecture.md), drawn by Glance into the same RemoteViews a launcher gets,
 * over a stand-in wallpaper: every size each one takes on one picture, in the dark and light themes, and what they say
 * when there is nothing to show. The channels, guide and progress are [SampleData]'s, read by the widgets' own loaders.
 */
@RunWith(RobolectricTestRunner::class)
class WidgetScreenshots : Screenshots() {
    @Test fun dark() = sheet("widgets", WidgetLook(theme = ThemeMode.DARK))

    @Test fun light() = sheet("widgets-light", WidgetLook(theme = ThemeMode.LIGHT))

    /** Another accent, which the widgets follow as the app does. */
    @Test fun teal() = sheet("widgets-teal", WidgetLook(theme = ThemeMode.DARK, accent = Accent.TEAL))

    @Test
    fun empty() {
        val look = WidgetLook(theme = ThemeMode.DARK)
        draw(
            "widgets-empty",
            listOf(
                Placed(FULL, TALL) { FavouritesContent(FavouritesData(look, signedIn = true, tiles = emptyList())) },
                Placed(FULL, TALL) { ContinueContent(ContinueData(look, signedIn = true, cards = emptyList())) },
                Placed(FULL, TALL) { FavouritesContent(FavouritesData(look, signedIn = false, tiles = emptyList())) },
            ),
            look,
        )
    }

    private fun sheet(
        name: String,
        look: WidgetLook,
    ) {
        val (tiles, cards) =
            runBlocking(Dispatchers.IO) {
                val db = app.container.db
                val now = System.currentTimeMillis()
                // Eight favourites, so the grid fills; SampleData has two.
                (1L..8L).forEach { db.library().addFavourite(FavouriteEntity(it, now + it)) }
                val settings =
                    app.container.settings.settings
                        .first()
                FavouritesWidget.tiles(app, settings) to ContinueWidget.cards(app, settings)
            }
        val favourites = FavouritesData(look, signedIn = true, tiles = tiles)
        val continuing = ContinueData(look, signedIn = true, cards = cards)
        draw(
            name,
            listOf(
                Placed(FULL, TALL) { FavouritesContent(favourites) },
                Placed(SQUARE, SHORT, row = true) { FavouritesContent(favourites) },
                Placed(FULL - SQUARE - GAP, SHORT) { ContinueContent(continuing) },
                Placed(FULL, STRIP) { FavouritesContent(favourites) },
                Placed(FULL, TALL) { ContinueContent(continuing) },
            ),
            look,
        )
    }

    /** One widget at its size; [row] starts a row that the next one joins. */
    private class Placed(
        val width: Float,
        val height: Float,
        val row: Boolean = false,
        val content: @Composable () -> Unit,
    )

    @OptIn(ExperimentalGlanceApi::class)
    private fun draw(
        name: String,
        widgets: List<Placed>,
        look: WidgetLook,
    ) {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val density = activity.resources.displayMetrics.density

        fun px(dp: Float) = (dp * density).toInt()
        val page =
            LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable(GradientDrawable.Orientation.TL_BR, wallpaper(look))
                setPadding(px(EDGE), px(EDGE * 2), px(EDGE), px(EDGE))
            }
        var row: LinearLayout? = null
        widgets.forEach { placed ->
            val views =
                runBlocking(Dispatchers.IO) {
                    Drawn(look, placed.content).compose(app, size = DpSize(placed.width.dp, placed.height.dp))
                }
            val view: View = views.apply(activity, page)
            val frame = FrameLayout(activity).apply { addView(view, FrameLayout.LayoutParams(px(placed.width), px(placed.height))) }
            val open = row
            if (open != null) {
                open.addView(frame, LinearLayout.LayoutParams(px(placed.width), px(placed.height)).apply { marginStart = px(GAP) })
                row = null
            } else if (placed.row) {
                val newRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
                newRow.addView(frame, LinearLayout.LayoutParams(px(placed.width), px(placed.height)))
                page.addView(newRow, LinearLayout.LayoutParams(px(FULL), px(placed.height)).apply { bottomMargin = px(GAP) })
                row = newRow
            } else {
                page.addView(frame, LinearLayout.LayoutParams(px(placed.width), px(placed.height)).apply { bottomMargin = px(GAP) })
            }
        }
        activity.setContentView(page, ViewGroup.LayoutParams(px(PAGE_W), px(PAGE_H)))
        page.captureRoboImage("$name.png")
    }

    /** A plain gradient standing in for a wallpaper, dark or light to match. */
    private fun wallpaper(look: WidgetLook): IntArray = if (look.theme == ThemeMode.LIGHT) {
        intArrayOf(0xFFB9C8E8.toInt(), 0xFFE9D9F2.toInt())
    } else {
        intArrayOf(0xFF1D2B4A.toInt(), 0xFF3A1F4D.toInt())
    }

    /** A widget that draws [content] in [look]'s colours, as the real ones do after loading. */
    private class Drawn(
        private val look: WidgetLook,
        private val content: @Composable () -> Unit,
    ) : GlanceAppWidget() {
        override val sizeMode: SizeMode = SizeMode.Exact

        override suspend fun provideGlance(
            context: Context,
            id: GlanceId,
        ) {
            provideContent { WidgetTheme(look) { content() } }
        }
    }

    private companion object {
        const val PAGE_W = 411f
        const val PAGE_H = 891f
        const val EDGE = 16f
        const val GAP = 16f
        const val FULL = PAGE_W - EDGE * 2
        const val TALL = 250f
        const val SHORT = 130f
        const val SQUARE = 130f
        const val STRIP = 110f
    }
}
