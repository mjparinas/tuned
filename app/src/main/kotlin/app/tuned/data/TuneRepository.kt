package app.tuned.data

import app.tuned.core.ChannelCopies
import app.tuned.core.EventChannels
import app.tuned.core.Search
import app.tuned.core.TuneCandidate
import app.tuned.core.Tuner
import app.tuned.data.db.ChannelHealthEntity
import app.tuned.data.db.ItemEntity
import app.tuned.data.db.TunedDatabase
import kotlinx.coroutines.flow.first

/** Auto-tune's data: the ranked channels for an event search, and what each channel did when it was tried. */
class TuneRepository(
    private val db: TunedDatabase,
    private val settings: SettingsRepository,
) {
    /** Channel ids, best first, for a search such as "UFC". Matches names and anything on now or within the hour. */
    suspend fun candidates(query: String): List<Long> {
        val words = Tuner.words(Search.normalize(query))
        if (words.isEmpty()) return emptyList()
        val s = settings.settings.first()
        val now = System.currentTimeMillis()
        val rows =
            db.items().tuneCandidates(
                w1 = words[0],
                w2 = words.getOrElse(1) { "" },
                w3 = words.getOrElse(2) { "" },
                from = now,
                to = now + HOUR,
                hidden = s.hiddenKeys(),
                adult = s.showAdult,
            )
        // Hidden channels stay out, except sports, PPV and event slots: an empty slot may carry the event searched for.
        val kept = rows.filter { !it.held || EventChannels.matches(it.name, it.groupName) }
        val candidates =
            kept.map {
                TuneCandidate(
                    id = it.id,
                    name = it.name,
                    group = it.groupName,
                    region = it.region,
                    nameMatch = it.nameMatch,
                    guideMatch = it.guideMatch,
                    okAt = it.okAt ?: 0,
                    deadAt = it.deadAt ?: 0,
                    height = it.height ?: 0,
                )
            }
        // A held channel the guide doesn't place the event on is a long shot: tried after everything else.
        val longShots = kept.filter { it.held && !it.guideMatch }.mapTo(HashSet()) { it.id }
        val (likely, rest) =
            Tuner
                .rank(candidates, now)
                .map { it.id }
                .partition { it !in longShots }
        return likely + rest
    }

    /**
     * Backup copies of live channel [id], best first: the same channel under another name ("US| ESPN FHD" for "ESPN
     * HD", see [ChannelCopies]), from the same country, ranked like auto-tune (recently dead last, then what played
     * before, then the sharpest picture). Empty when the setting is off or the name is too short to match safely.
     */
    suspend fun copies(id: Long): List<Long> {
        val s = settings.settings.first()
        if (!s.backupCopies) return emptyList()
        val item = db.items().get(id) ?: return emptyList()
        return Tuner.rank(copyCandidates(item, s), System.currentTimeMillis()).map { it.id }
    }

    /**
     * For the HQ button (Melvin, 2026-11-18): the channel itself and the best copy of each other picture quality
     * ([ChannelCopies.picture]), sharpest first. Fewer than two means there is nothing to switch to. Unlike [copies]
     * this doesn't need backup copies switched on, since the person picks the copy themselves.
     */
    suspend fun qualityCopies(id: Long): List<QualityCopy> {
        val s = settings.settings.first()
        val item = db.items().get(id) ?: return emptyList()
        val own = ChannelCopies.picture(item.name)
        val others =
            Tuner
                .rank(copyCandidates(item, s), System.currentTimeMillis())
                .map { QualityCopy(it.id, it.name, ChannelCopies.picture(it.name)) }
                .filter { it.picture != own }
                .distinctBy { it.picture }
        if (others.isEmpty()) return emptyList()
        return (others + QualityCopy(item.id, item.name, own)).sortedByDescending { it.picture }
    }

    private suspend fun copyCandidates(
        item: ItemEntity,
        s: AppSettings,
    ): List<TuneCandidate> {
        val key = ChannelCopies.key(item.name) ?: return emptyList()
        val rows = db.items().copyCandidates(item.id, Search.match(key), s.hiddenKeys(), s.showAdult)
        return rows
            .filter { ChannelCopies.key(it.name) == key && ChannelCopies.sameRegion(it.region, item.region) }
            .map {
                TuneCandidate(
                    id = it.id,
                    name = it.name,
                    group = it.groupName,
                    region = it.region,
                    nameMatch = true,
                    guideMatch = false,
                    okAt = it.okAt ?: 0,
                    deadAt = it.deadAt ?: 0,
                    height = it.height ?: 0,
                )
            }
    }

    suspend fun played(
        id: Long,
        height: Int,
    ) {
        val old = db.library().health(id)
        db.library().saveHealth(ChannelHealthEntity(id, System.currentTimeMillis(), old?.deadAt ?: 0, height))
    }

    suspend fun failed(id: Long) {
        val old = db.library().health(id)
        db.library().saveHealth(ChannelHealthEntity(id, old?.okAt ?: 0, System.currentTimeMillis(), old?.height ?: 0))
    }

    /** A copy of a channel and how sharp its picture is ([ChannelCopies.picture]). */
    data class QualityCopy(
        val id: Long,
        val name: String,
        val picture: Int,
    )

    private companion object {
        const val HOUR = 3_600_000L
    }
}
