package app.tuned.screenshots

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import app.tuned.TunedApp
import app.tuned.core.ContentKind
import app.tuned.core.Episodes
import app.tuned.core.Search
import app.tuned.data.AppSettings
import app.tuned.data.CaptionStyle
import app.tuned.data.StartTab
import app.tuned.data.ThemeMode
import app.tuned.data.db.FavouriteEntity
import app.tuned.data.db.HistoryEntity
import app.tuned.data.db.ItemEntity
import app.tuned.data.db.ProgrammeEntity
import app.tuned.ui.browse.HomeScreen
import app.tuned.ui.guide.GuideScreen
import app.tuned.ui.login.LoginScreen
import app.tuned.ui.settings.CaptionSettingsScreen
import app.tuned.ui.settings.RegionSettingsScreen
import app.tuned.ui.settings.SettingsScreen
import app.tuned.ui.shows.ShowScreen
import app.tuned.ui.theme.TunedTheme
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import coil3.asImage
import coil3.test.FakeImageLoaderEngine
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders each screen with made-up sample data, for the README and for reviewing UI changes. No real playlist,
 * login or stream address is involved. Run with `./gradlew :app:testDebugUnitTest -Pscreenshots`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w400dp-h860dp-xxhdpi")
class Screenshots {
    @get:Rule val compose = createComposeRule()

    private val app: TunedApp = ApplicationProvider.getApplicationContext()
    private val dark = AppSettings(theme = ThemeMode.DARK)

    @OptIn(DelicateCoilApi::class)
    @Before
    fun setUp() {
        // App startup may already have set WorkManager up; the test one only matters when it hasn't.
        runCatching {
            WorkManagerTestInitHelper.initializeTestWorkManager(app, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        }
        val engine = FakeImageLoaderEngine.Builder()
        art.forEach { (url, bitmap) -> engine.intercept(url, bitmap.asImage()) }
        SingletonImageLoader.setUnsafe(ImageLoader.Builder(app).components { add(engine.build()) }.build())
        runBlocking(Dispatchers.IO) { seed() }
    }

    @Test fun login() = shot("01-sign-in", ThemeMode.DARK, wait = "Sign in") { LoginScreen(onSignedIn = {}) }

    @Test fun live() = shot("02-live-tv", ThemeMode.DARK, wait = "CBC News Network") { Home(dark) }

    @Test fun liveLight() = shot(
        "03-live-tv-light",
        ThemeMode.LIGHT,
        wait = "CBC News Network",
    ) { Home(dark.copy(theme = ThemeMode.LIGHT)) }

    @Test fun autoTune() = shot("11-auto-tune", ThemeMode.DARK, wait = "Hockey Night", before = {
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("CBC News Network")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasSetTextAction()).performTextInput("hockey")
    }) { Home(dark) }

    @Test fun movies() = shot("04-movies", ThemeMode.DARK, wait = "Northern Lights") { Home(dark.copy(startTab = StartTab.MOVIES)) }

    @Test fun shows() = shot("05-shows", ThemeMode.DARK, wait = "Harbour Town") { Home(dark.copy(startTab = StartTab.SHOWS)) }

    @Test fun show() = shot("06-show", ThemeMode.DARK, wait = "Harbour Town") {
        ShowScreen(showKey = Episodes.showKey("Harbour Town"), title = "Harbour Town", onPlay = {}, onBack = {})
    }

    @Test fun guide() = shot("07-guide", ThemeMode.DARK, wait = "The National") { GuideScreen(itemId = 1, onPlay = {}, onBack = {}) }

    @Test fun settings() = shot("08-settings", ThemeMode.DARK, wait = "Captions") {
        SettingsScreen(settings = dark, onBack = {}, onOpenCaptions = {}, onOpenRegions = {}, onOpenFavouriteOrder = {}, onSignedOut = {})
    }

    @Test fun captions() = shot("09-captions", ThemeMode.DARK, wait = "Captions") {
        CaptionSettingsScreen(settings = dark.copy(captions = CaptionStyle(enabled = true)), onBack = {})
    }

    @Test fun regions() = shot("10-countries", ThemeMode.DARK, wait = "Canada") {
        RegionSettingsScreen(settings = dark.copy(hiddenRegions = setOf("DE", "EXYU")), onBack = {})
    }

    @Composable
    private fun Home(settings: AppSettings) =
        HomeScreen(settings, onPlay = {}, onOpenShow = { _, _ -> }, onOpenGuide = {}, onOpenSettings = {}, onTune = {}, onSignedOut = {})

    private fun shot(
        name: String,
        theme: ThemeMode,
        wait: String,
        before: () -> Unit = {},
        content: @Composable () -> Unit,
    ) {
        compose.setContent { TunedTheme(mode = theme) { content() } }
        before()
        val shown =
            runCatching {
                compose.waitUntil(10_000) { compose.onAllNodes(hasText(wait, substring = true)).fetchSemanticsNodes().isNotEmpty() }
            }
        compose.waitForIdle()
        // Captured either way, so a failure can be seen.
        compose.onRoot().captureRoboImage("$name.png")
        shown.getOrThrow()
    }

    private suspend fun seed() {
        val db = app.container.db
        db.clearAllTables()
        val now = System.currentTimeMillis()
        val items = mutableListOf<ItemEntity>()
        var order = 0

        fun item(
            id: Long,
            kind: ContentKind,
            name: String,
            group: String,
            region: String?,
            logo: String? = null,
            tvgId: String? = null,
            show: String? = null,
            season: Int? = null,
            episode: Int? = null,
        ) = items.add(
            ItemEntity(
                id = id,
                kind = kind.code,
                name = name,
                searchName = Search.normalize(name),
                groupName = group,
                region = region,
                adult = false,
                logo = logo,
                url = "http://example.invalid/$id",
                tvgId = tvgId,
                showKey = show?.let(Episodes::showKey),
                showName = show,
                season = season,
                episode = episode,
                sortOrder = order++,
            ),
        )

        channels.forEachIndexed { i, (name, group, region) ->
            item(i + 1L, ContentKind.LIVE, name, group, region, logo = "logo:$name", tvgId = "ch$i")
        }
        movies.forEachIndexed { i, (name, group) -> item(1_000L + i, ContentKind.MOVIE, name, group, "CA", logo = "poster:$name") }
        var id = 2_000L
        shows.forEach { (show, group, seasons) ->
            for (s in 1..seasons) {
                for (e in 1..8) {
                    item(
                        id++,
                        ContentKind.SERIES,
                        "$show S0${s}E0$e",
                        group,
                        "CA",
                        logo = "poster:$show",
                        show = show,
                        season = s,
                        episode = e,
                    )
                }
            }
        }
        db.items().insertAll(items)

        val hour = 3_600_000L
        val start = now - now % (hour / 2) - hour / 2
        db.programmes().insertAll(
            channels.indices.flatMap { i ->
                schedule.indices.map { slot ->
                    val (title, description) = schedule[(slot + i) % schedule.size]
                    ProgrammeEntity(
                        channelId = "ch$i",
                        start = start + slot * hour,
                        stop = start + (slot + 1) * hour,
                        title = title,
                        description = description,
                    )
                }
            },
        )
        db.library().addFavourite(FavouriteEntity(1, now))
        db.library().addFavourite(FavouriteEntity(3, now))
        db.library().saveProgress(HistoryEntity(2_000, positionMs = 2_400_000, durationMs = 2_700_000, watchedAt = now))
        db.library().saveProgress(HistoryEntity(2_001, positionMs = 900_000, durationMs = 2_700_000, watchedAt = now))
    }

    private companion object {
        val channels =
            listOf(
                Triple("CBC News Network", "CA | News", "CA"),
                Triple("CTV", "CA | General", "CA"),
                Triple("Global", "CA | General", "CA"),
                Triple("TSN 1", "CA | Sports", "CA"),
                Triple("Sportsnet One", "CA | Sports", "CA"),
                Triple("Télé-Québec", "QC | Général", "QC"),
                Triple("BBC One", "UK | General", "UK"),
                Triple("Sky News", "UK | News", "UK"),
                Triple("PBS", "US | General", "US"),
                Triple("Discovery", "US | Documentary", "US"),
                Triple("Das Erste", "DE | Allgemein", "DE"),
                Triple("RTS 1", "EXYU | Opšte", "EXYU"),
            )

        val movies =
            listOf(
                "Northern Lights" to "Drama",
                "The Long Portage" to "Adventure",
                "Glass Harbour" to "Thriller",
                "Maple & Ash" to "Comedy",
                "Last Train to Moose Jaw" to "Drama",
                "Signal Fire" to "Thriller",
                "Tidewater" to "Adventure",
                "The Quiet Season" to "Drama",
                "Prairie Static" to "Sci-fi",
            )

        val shows =
            listOf(
                Triple("Harbour Town", "Drama", 3),
                Triple("The Night Desk", "Crime", 2),
                Triple("Snowline", "Thriller", 1),
                Triple("Backyard Science", "Kids", 2),
                Triple("Kitchen Rules", "Reality", 1),
                Triple("Signal & Noise", "Sci-fi", 2),
            )

        val schedule =
            listOf(
                "The National" to "The day's top stories from across Canada and around the world.",
                "Morning Live" to "News, weather and traffic to start your day.",
                "Hockey Night" to "Toronto at Montréal, live from the Bell Centre.",
                "Planet Wild" to "A season on the tundra with a family of Arctic foxes.",
                "Late Edition" to "Interviews and a look at tomorrow's headlines.",
                "Weekend Kitchen" to "Three dishes from one pantry, in under an hour.",
            )

        private val palette = listOf(0xFF6D4AFF, 0xFF1FB8A0, 0xFFE0546B, 0xFFF0A23B, 0xFF3B7BE0, 0xFF8A5A3C, 0xFF2E9E5B, 0xFFB04FD0)

        /** Made-up posters and logos: a gradient with the title on it, so no real artwork is used. */
        val art: Map<String, Bitmap> by lazy {
            val posters = (movies.map { it.first } + shows.map { it.first }).mapIndexed { i, t -> "poster:$t" to tile(t, i, 300, 450, 34f) }
            val logos = channels.mapIndexed { i, c -> "logo:${c.first}" to tile(initials(c.first), i, 160, 160, 56f) }
            (posters + logos).toMap()
        }

        private fun initials(name: String) = name
            .split(' ', '-')
            .filter { it.isNotBlank() }
            .take(2)
            .joinToString("") { it.take(1) }

        private fun tile(
            text: String,
            index: Int,
            w: Int,
            h: Int,
            size: Float,
        ): Bitmap {
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val a = palette[index % palette.size].toInt()
            val b = palette[(index + 3) % palette.size].toInt()
            canvas.drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), a, b, Shader.TileMode.CLAMP) })
            val paint =
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = android.graphics.Color.WHITE
                    textSize = size
                    typeface = Typeface.DEFAULT_BOLD
                    textAlign = Paint.Align.CENTER
                }
            val lines = wrap(text, paint, w * 0.85f)
            val top = h / 2f - (lines.size - 1) * size * 0.6f
            lines.forEachIndexed { i, line -> canvas.drawText(line, w / 2f, top + i * size * 1.2f + size / 3, paint) }
            return bitmap
        }

        private fun wrap(
            text: String,
            paint: Paint,
            width: Float,
        ): List<String> = text.split(' ').fold(mutableListOf<String>()) { lines, word ->
            val last = lines.lastOrNull()
            if (last != null && paint.measureText("$last $word") <= width) lines[lines.size - 1] = "$last $word" else lines.add(word)
            lines
        }
    }
}
