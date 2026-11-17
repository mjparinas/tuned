package app.tuned.work

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.graphics.drawable.toBitmap
import androidx.core.net.toUri
import androidx.tvprovider.media.tv.PreviewChannel
import androidx.tvprovider.media.tv.PreviewChannelHelper
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.tuned.R
import app.tuned.container
import app.tuned.core.ContentKind
import app.tuned.data.db.ContinueRow
import app.tuned.data.db.FavouriteRow
import app.tuned.data.hiddenKeys
import app.tuned.diagnostics.attempt
import app.tuned.diagnostics.logFailures
import app.tuned.ui.ACTION_PLAY
import app.tuned.ui.EXTRA_PLAY_ID
import app.tuned.ui.MainActivity
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit
import androidx.tvprovider.media.tv.TvContractCompat.PreviewProgramColumns as Columns

/**
 * The Android TV home screen (from the app audit): films and episodes in progress in the system's "Continue watching"
 * row (Watch Next), and favourite channels as Tuned's own row. Each card opens the player through [MainActivity].
 * Phones have neither, so nothing runs there. The ids Android gave each card are kept, so an update changes the cards
 * in place and takes away the ones no longer in progress.
 */
class TvHome(
    private val context: Context,
) {
    private val helper = PreviewChannelHelper(context)
    private val prefs = context.getSharedPreferences("tv_home", Context.MODE_PRIVATE)

    suspend fun update() {
        val c = context.container
        val s = c.settings.settings.first()
        val hidden = s.hiddenKeys()
        val items = c.db.items()
        val inProgress =
            (
                items.continueWatching(ContentKind.MOVIE.code, hidden, s.showAdult).first() +
                    items.continueWatching(ContentKind.SERIES.code, hidden, s.showAdult).first()
            ).take(MAX_CARDS)
        updateWatchNext(inProgress)
        updateFavourites(c.db.library().favouriteChannels().first().take(MAX_CARDS))
    }

    /** Signing out: every card points at channels and films that are gone. */
    fun clear() {
        ids(WATCH_NEXT).values.forEach(::deleteWatchNext)
        ids(PROGRAMS).values.forEach { attempt(TAG, "Couldn't remove a home screen card") { helper.deletePreviewProgram(it) } }
        prefs.edit { clear() }
    }

    private fun updateWatchNext(rows: List<ContinueRow>) {
        val old = ids(WATCH_NEXT)
        val now = System.currentTimeMillis()
        val kept = HashMap<Long, Long>()
        rows.forEachIndexed { i, row ->
            val program =
                WatchNextProgram
                    .Builder()
                    .setType(if (row.showName != null) Columns.TYPE_TV_EPISODE else Columns.TYPE_MOVIE)
                    .setWatchNextType(TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE)
                    .setLastEngagementTimeUtcMillis(now - i) // keeps the app's order
                    .setLastPlaybackPositionMillis(row.positionMs.toInt())
                    .setDurationMillis(row.durationMs.toInt())
                    .setTitle(row.showName ?: row.name)
                    .setEpisodeTitle(if (row.showName != null) row.name else null)
                    .setPosterArtUri(row.logo?.toUri())
                    .setIntentUri(playUri(row.id))
                    .setInternalProviderId(row.id.toString())
                    .build()
            val existing = old[row.id]
            val updated = existing != null && attempt(TAG, UPDATE_FAILED) { helper.updateWatchNextProgram(program, existing) }.isSuccess
            val id = if (updated && existing != null) existing else helper.publishWatchNextProgram(program)
            kept[row.id] = id
        }
        old.filterKeys { it !in kept }.values.forEach(::deleteWatchNext)
        save(WATCH_NEXT, kept)
    }

    private fun updateFavourites(rows: List<FavouriteRow>) {
        val channel = channelId() ?: return
        ids(PROGRAMS).values.forEach { attempt(TAG, "Couldn't remove a home screen card") { helper.deletePreviewProgram(it) } }
        val kept = HashMap<Long, Long>()
        rows.forEachIndexed { i, row ->
            val program =
                PreviewProgram
                    .Builder()
                    .setChannelId(channel)
                    .setType(Columns.TYPE_CHANNEL)
                    .setTitle(row.name)
                    .setPosterArtUri(row.logo?.toUri())
                    .setPosterArtAspectRatio(Columns.ASPECT_RATIO_16_9)
                    .setLive(true)
                    .setIntentUri(playUri(row.id))
                    .setInternalProviderId(row.id.toString())
                    .setWeight(rows.size - i)
                    .build()
            kept[row.id] = helper.publishPreviewProgram(program)
        }
        save(PROGRAMS, kept)
    }

    /** Tuned's own row on the home screen, made once. */
    private fun channelId(): Long? {
        val saved = prefs.getLong(CHANNEL, -1)
        if (saved >= 0 && helper.getPreviewChannel(saved) != null) return saved
        val logo = ContextCompat.getDrawable(context, R.mipmap.ic_launcher)?.toBitmap() ?: return null
        val channel =
            PreviewChannel
                .Builder()
                .setDisplayName(context.getString(R.string.tv_home_favourites))
                .setAppLinkIntentUri(Intent(context, MainActivity::class.java).toUri(Intent.URI_INTENT_SCHEME).toUri())
                .setLogo(logo)
                .build()
        val id = helper.publishDefaultChannel(channel)
        prefs.edit { putLong(CHANNEL, id) }
        return id
    }

    private fun playUri(id: Long) = Intent(context, MainActivity::class.java)
        .setAction(ACTION_PLAY)
        .putExtra(EXTRA_PLAY_ID, id)
        .toUri(Intent.URI_INTENT_SCHEME)
        .toUri()

    private fun deleteWatchNext(id: Long) {
        attempt(TAG, "Couldn't remove a Watch Next card") {
            context.contentResolver.delete(TvContractCompat.buildWatchNextProgramUri(id), null, null)
        }
    }

    private fun ids(key: String): Map<Long, Long> = prefs
        .getStringSet(key, emptySet())
        .orEmpty()
        .mapNotNull { entry ->
            val (item, program) = entry.split('=').takeIf { it.size == 2 } ?: return@mapNotNull null
            val i = item.toLongOrNull() ?: return@mapNotNull null
            val p = program.toLongOrNull() ?: return@mapNotNull null
            i to p
        }.toMap()

    private fun save(
        key: String,
        ids: Map<Long, Long>,
    ) = prefs.edit { putStringSet(key, ids.map { (i, p) -> "$i=$p" }.toSet()) }

    companion object {
        private const val TAG = "TvHome"
        private const val UPDATE_FAILED = "Couldn't update a Watch Next card"
        private const val MAX_CARDS = 20
        private const val WATCH_NEXT = "watch_next"
        private const val PROGRAMS = "programs"
        private const val CHANNEL = "channel"
        private const val NAME = "tv-home"
        private const val DELAY_S = 3L

        fun isTv(context: Context): Boolean = context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)

        /** Brings the cards up to date soon: after playing, after a favourite changes, and when the app opens. */
        fun schedule(context: Context) {
            if (!isTv(context)) return
            val request =
                OneTimeWorkRequestBuilder<TvHomeWorker>()
                    .setInitialDelay(DELAY_S, TimeUnit.SECONDS)
                    .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }
}

class TvHomeWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = logFailures("TvHome", failed = { Result.failure() }) {
        if (applicationContext.container.credentials.load() != null) TvHome(applicationContext).update()
        Result.success()
    }
}
