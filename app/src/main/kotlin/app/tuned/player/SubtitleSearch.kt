package app.tuned.player

import android.app.AlertDialog
import android.util.TypedValue
import android.view.View
import android.widget.ImageButton
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import app.tuned.R
import app.tuned.core.ContentKind
import app.tuned.core.OpenSubtitles
import app.tuned.data.AppSettings
import app.tuned.data.SubtitleRepository
import app.tuned.data.db.ItemEntity
import app.tuned.diagnostics.attempt
import app.tuned.diagnostics.launchSafely
import java.io.File
import java.util.Locale

/**
 * Subtitle search (Melvin, 2026-11-17): for films and episodes whose stream has no subtitles, the player's subtitle
 * search button lists matching files from OpenSubtitles.com and adds the one picked. With captions on, a video that
 * has none fetches the best match by itself (Settings › Subtitles). The button shows only in builds with the key.
 * Downloaded files are kept by item, so the same film shows them again with no download.
 */
@OptIn(UnstableApi::class)
class SubtitleSearch(
    private val activity: ComponentActivity,
    private val repository: SubtitleRepository,
    private val player: () -> ExoPlayer?,
    private val queue: () -> List<ItemEntity>,
    private val settings: () -> AppSettings,
    private val casting: () -> Boolean,
    private val say: (String) -> Unit,
) {
    /** The film or episode playing now. */
    private fun current(): ItemEntity? = player()
        ?.currentMediaItem
        ?.mediaId
        ?.toLongOrNull()
        ?.let { id -> queue().firstOrNull { it.id == id } }

    /**
     * [mediaItems] (one per item in [items]) with the subtitles fetched before for each, so they come back with their
     * film with no download; when [startId] has some, captions turn on in their language.
     */
    suspend fun withSaved(
        exo: ExoPlayer,
        items: List<ItemEntity>,
        startId: Long,
        mediaItems: List<MediaItem>,
    ): List<MediaItem> {
        if (items.none(::onDemand)) return mediaItems
        val saved = repository.saved(items.map { it.id })
        saved[startId]?.let { file ->
            exo.trackSelectionParameters =
                exo.trackSelectionParameters
                    .buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .setPreferredTextLanguage(languageOf(file))
                    .build()
        }
        return mediaItems.mapIndexed { i, media ->
            val file = items.getOrNull(i)?.let { saved[it.id] } ?: return@mapIndexed media
            media.buildUpon().setSubtitleConfigurations(listOf(track(file))).build()
        }
    }

    /** Items already searched for by themselves this time, so a video with nothing to find is asked about once. */
    private val tried = mutableSetOf<Long>()

    /** Whether this build has an OpenSubtitles key; the button stays hidden without one. */
    private val hasKey = repository.hasKey()

    /** Shown with the controls for films and episodes, in builds with the key. */
    val button: ImageButton =
        ImageButton(activity).apply {
            setImageResource(R.drawable.ic_player_subtitle_search)
            contentDescription = activity.getString(R.string.subtitle_search)
            val ripple = TypedValue()
            activity.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true)
            setBackgroundResource(ripple.resourceId)
            val pad = (BUTTON_PADDING_DP * activity.resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            visibility = View.GONE
            setOnClickListener { pick() }
        }

    private fun showButton() {
        button.visibility = if (hasKey && current()?.let(::onDemand) == true) View.VISIBLE else View.GONE
    }

    /** The toolbar button: search, list, and add the one picked. */
    fun pick() {
        val item = current()?.takeIf(::onDemand) ?: return
        if (casting()) {
            say(activity.getString(R.string.subtitle_search_not_casting))
            return
        }
        say(activity.getString(R.string.subtitle_search_searching))
        activity.lifecycleScope.launchSafely(TAG) {
            val search = repository.searchFor(item)
            val results =
                try {
                    repository.search(search)
                } catch (e: SubtitleRepository.Failed) {
                    say(problem(e.problem))
                    return@launchSafely
                }
            if (results.isEmpty()) {
                say(activity.getString(R.string.subtitle_search_none))
                return@launchSafely
            }
            choose(item, search, results.take(MAX_RESULTS))
        }
    }

    private fun choose(
        item: ItemEntity,
        search: OpenSubtitles.Search,
        results: List<OpenSubtitles.Result>,
    ) {
        if (activity.isFinishing) return
        val labels = results.map(::label).toTypedArray()
        AlertDialog
            .Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(R.string.subtitle_search)
            .setItems(labels) { _, which -> fetch(item, search, results[which], quiet = false) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun fetch(
        item: ItemEntity,
        search: OpenSubtitles.Search,
        result: OpenSubtitles.Result,
        quiet: Boolean,
    ) {
        if (!quiet) say(activity.getString(R.string.subtitle_search_downloading))
        activity.lifecycleScope.launchSafely(TAG) {
            val file =
                try {
                    repository.download(item.id, result, search.language)
                } catch (e: SubtitleRepository.Failed) {
                    if (!quiet || e.problem == SubtitleRepository.Problem.LIMIT) say(problem(e.problem))
                    return@launchSafely
                }
            val p = player() ?: return@launchSafely
            if (current()?.id != item.id) return@launchSafely // moved on to another episode meanwhile
            add(p, file, OpenSubtitles.trackLanguage(search.language))
            say(activity.getString(R.string.subtitle_search_added))
        }
    }

    /** Watches each video's tracks: with captions on and none in the stream, the best match is fetched by itself. */
    val listener =
        object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) {
                showButton()
                if (tracks.groups.isEmpty()) return
                val item = current()?.takeIf(::onDemand) ?: return
                val s = settings()
                if (!hasKey || !s.autoSubtitles || !s.captions.enabled || casting() || item.id in tried) return
                if (tracks.groups.any { it.type == C.TRACK_TYPE_TEXT }) return
                tried += item.id
                activity.lifecycleScope.launchSafely(TAG) {
                    val search = repository.searchFor(item)
                    val best =
                        attempt(TAG, "Subtitle search failed") { repository.search(search).firstOrNull() }.getOrNull()
                            ?: return@launchSafely
                    fetch(item, search, best, quiet = true)
                }
            }
        }

    private fun label(r: OpenSubtitles.Result): String {
        val language = Locale.forLanguageTag(r.language).displayLanguage.ifBlank { r.language }
        val parts =
            listOfNotNull(
                language,
                activity.resources.getQuantityString(R.plurals.subtitle_search_downloads, r.downloads, r.downloads),
                activity.getString(R.string.subtitle_search_hearing_impaired).takeIf { r.hearingImpaired },
                activity.getString(R.string.subtitle_search_machine).takeIf { r.machine },
            )
        return r.release + "\n" + parts.joinToString(" · ")
    }

    private fun problem(p: SubtitleRepository.Problem): String = activity.getString(
        when (p) {
            SubtitleRepository.Problem.NO_KEY -> R.string.subtitle_search_no_key
            SubtitleRepository.Problem.BAD_KEY -> R.string.subtitle_search_bad_key
            SubtitleRepository.Problem.BAD_ACCOUNT -> R.string.subtitle_search_bad_account
            SubtitleRepository.Problem.LIMIT -> R.string.subtitle_search_limit
            SubtitleRepository.Problem.OFFLINE -> R.string.subtitle_search_offline
        },
    )

    private fun onDemand(item: ItemEntity) = ContentKind.fromCode(item.kind) != ContentKind.LIVE

    companion object {
        private const val MAX_RESULTS = 30
        private const val BUTTON_PADDING_DP = 12
        private const val TAG = "SubtitleSearch"

        /** A downloaded file as a subtitle track of its video, chosen by default. */
        fun track(
            file: File,
            language: String = languageOf(file),
        ): MediaItem.SubtitleConfiguration = MediaItem.SubtitleConfiguration
            .Builder(file.toUri())
            .setMimeType(MimeTypes.APPLICATION_SUBRIP)
            .setLanguage(language)
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .build()

        /** The language a kept file was saved with ("42-fr.srt" is French). */
        fun languageOf(file: File): String = file.nameWithoutExtension.substringAfter('-', OpenSubtitles.DEFAULT_LANGUAGE)

        /**
         * Adds [file] to the video playing now and turns captions on in its language. Media3 reads side tracks only as
         * a video loads, so it reloads at the same spot; the old connection closes before the next opens.
         */
        fun add(
            player: ExoPlayer,
            file: File,
            language: String,
        ) {
            val index = player.currentMediaItemIndex
            val items =
                (0 until player.mediaItemCount).map { i ->
                    val item = player.getMediaItemAt(i)
                    if (i == index) item.buildUpon().setSubtitleConfigurations(listOf(track(file, language))).build() else item
                }
            if (items.isEmpty()) return
            player.trackSelectionParameters =
                player.trackSelectionParameters
                    .buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .setPreferredTextLanguage(language)
                    .build()
            player.setMediaItems(items, index, player.currentPosition)
            player.prepare()
        }
    }
}
