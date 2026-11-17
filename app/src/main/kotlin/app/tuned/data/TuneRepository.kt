package app.tuned.data

import app.tuned.core.ChannelCopies
import app.tuned.core.EventChannels
import app.tuned.core.Search
import app.tuned.core.TuneCandidate
import app.tuned.core.Tuner
import app.tuned.data.db.ChannelHealthEntity
import app.tuned.data.db.TunedDatabase
import kotlinx.coroutines.flow.first

/** Auto-tune's data: the ranked channels for an event search, and what each channel did when it was tried. */
class TuneRepository(
    private val db: TunedDatabase,
    private val settings: SettingsRepository,
) {
    /** Channel ids, best first, for a search such as "UFC". Matches names and anything on now or within the hour. */
    suspend fun candidates(query: String): List<Long> {
        val text = query.trim()
        val normalized = Search.normalize(text).trim()
        if (normalized.isEmpty()) return emptyList()
        val s = settings.settings.first()
        val now = System.currentTimeMillis()
        val rows =
            db.items().tuneCandidates(
                query = text,
                normalized = normalized,
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
        val key = ChannelCopies.key(item.name) ?: return emptyList()
        val rows = db.items().copyCandidates(id, Search.match(key), s.hiddenKeys(), s.showAdult)
        val candidates =
            rows
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
        return Tuner.rank(candidates, System.currentTimeMillis()).map { it.id }
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

    private companion object {
        const val HOUR = 3_600_000L
    }
}
