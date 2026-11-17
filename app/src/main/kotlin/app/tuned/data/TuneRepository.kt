package app.tuned.data

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
        val candidates =
            rows.map {
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
        return Tuner.rank(candidates, now).map { it.id }
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
