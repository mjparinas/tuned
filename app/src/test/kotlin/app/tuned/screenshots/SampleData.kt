package app.tuned.screenshots

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import app.tuned.core.ContentKind
import app.tuned.core.Episodes
import app.tuned.core.Search
import app.tuned.data.db.FavouriteEntity
import app.tuned.data.db.HistoryEntity
import app.tuned.data.db.ItemEntity
import app.tuned.data.db.ProgrammeEntity
import app.tuned.data.db.ShowGroups
import app.tuned.data.db.TunedDatabase
import coil3.Image
import coil3.asImage

/**
 * A made-up playlist for screenshots: channels, films and shows with invented names and drawn artwork, never a real
 * provider, channel brand or film (docs/decisions/premium.md: store screenshots come from a made-up demo playlist).
 */
object SampleData {
    private const val HOUR = 3_600_000L
    private const val EPISODES = 8

    suspend fun seed(db: TunedDatabase) {
        db.clearAllTables()
        val now = System.currentTimeMillis()
        val items = mutableListOf<ItemEntity>()

        fun item(
            id: Long,
            kind: ContentKind,
            name: String,
            group: String,
            region: String?,
            logo: String?,
            tvgId: String? = null,
            show: String? = null,
            season: Int? = null,
            episode: Int? = null,
            number: Int = 0,
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
                sortOrder = items.size,
                number = number,
            ),
        )

        channels.forEachIndexed { i, (name, group, region) ->
            item(i + 1L, ContentKind.LIVE, name, group, region, logo = logo(name), tvgId = "ch$i", number = i + 1)
        }
        movies.forEachIndexed { i, (name, group) -> item(MOVIE_IDS + i, ContentKind.MOVIE, name, group, "CA", poster(name)) }
        var id = SHOW_IDS
        shows.forEach { (show, group, seasons) ->
            for (s in 1..seasons) {
                for (e in 1..EPISODES) {
                    item(id++, ContentKind.SERIES, "$show S0${s}E0$e", group, "CA", poster(show), show = show, season = s, episode = e)
                }
            }
        }
        db.items().insertAll(items)
        ShowGroups.rebuild(db)

        val start = now - now % (HOUR / 2) - HOUR / 2
        db.programmes().insertAll(
            channels.indices.flatMap { i ->
                schedule.indices.map { slot ->
                    val (title, description) = schedule[(slot + i) % schedule.size]
                    ProgrammeEntity(
                        channelId = "ch$i",
                        start = start + slot * HOUR,
                        stop = start + (slot + 1) * HOUR,
                        title = title,
                        description = description,
                    )
                }
            },
        )
        db.library().addFavourite(FavouriteEntity(1, now))
        db.library().addFavourite(FavouriteEntity(4, now))
        db.library().saveProgress(HistoryEntity(SHOW_IDS, positionMs = 2_400_000, durationMs = 2_700_000, watchedAt = now))
        db.library().saveProgress(HistoryEntity(SHOW_IDS + 1, positionMs = 900_000, durationMs = 2_700_000, watchedAt = now - 1))
        db.library().saveProgress(HistoryEntity(MOVIE_IDS, positionMs = 1_800_000, durationMs = 6_600_000, watchedAt = now - 2))
    }

    const val MOVIE_IDS = 1_000L
    const val SHOW_IDS = 2_000L

    val channels =
        listOf(
            Triple("Maple News 24", "CA | News", "CA"),
            Triple("Northern One", "CA | General", "CA"),
            Triple("Lakeshore TV", "CA | General", "CA"),
            Triple("Rink Sports 1", "CA | Sports", "CA"),
            Triple("Rink Sports 2", "CA | Sports", "CA"),
            Triple("Télé Fleuve", "QC | Général", "QC"),
            Triple("Thames One", "UK | General", "UK"),
            Triple("Harbour News", "UK | News", "UK"),
            Triple("Prairie Public", "US | General", "US"),
            Triple("Wild Planet", "US | Documentary", "US"),
            Triple("Rheinwelle", "DE | Allgemein", "DE"),
            Triple("Adria 1", "EXYU | Opšte", "EXYU"),
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
            "The National Hour" to "The day's top stories from across the country and around the world.",
            "Morning Live" to "News, weather and traffic to start your day.",
            "Hockey Night" to "Lakeside at Riverton, live from the Harbour Arena.",
            "Planet Wild" to "A season on the tundra with a family of Arctic foxes.",
            "Late Edition" to "Interviews and a look at tomorrow's headlines.",
            "Weekend Kitchen" to "Three dishes from one pantry, in under an hour.",
        )

    private fun logo(name: String) = "https://art.example.invalid/logo/${Search.normalize(name).replace(' ', '-')}.png"

    private fun poster(name: String) = "https://art.example.invalid/poster/${Search.normalize(name).replace(' ', '-')}.png"

    private val palette = listOf(0xFF6D4AFF, 0xFF1FB8A0, 0xFFE0546B, 0xFFF0A23B, 0xFF3B7BE0, 0xFF8A5A3C, 0xFF2E9E5B, 0xFFB04FD0)

    /** Drawn posters and logos, by the address the sample playlist gives them: a gradient with the title on it. */
    val art: Map<String, Image> by lazy {
        val posters = (movies.map { it.first } + shows.map { it.first }).mapIndexed { i, t -> poster(t) to tile(t, i, 300, 450, 34f) }
        val logos = channels.mapIndexed { i, c -> logo(c.first) to tile(initials(c.first), i, 160, 160, 56f) }
        (posters + logos).associate { (key, bitmap) -> key to bitmap.asImage() }
    }

    private fun initials(name: String) = name
        .split(' ', '-')
        .filter { it.isNotBlank() }
        .take(2)
        .joinToString("") { it.take(1) }

    @Suppress("MagicNumber") // Drawing sizes for made-up artwork.
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
                color = Color.WHITE
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
    ): List<String> = text.split(' ').fold(mutableListOf()) { lines, word ->
        val last = lines.lastOrNull()
        if (last != null && paint.measureText("$last $word") <= width) lines[lines.size - 1] = "$last $word" else lines.add(word)
        lines
    }
}
