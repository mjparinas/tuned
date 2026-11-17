package app.tuned.data

import android.util.Xml
import app.tuned.core.CatchUp
import app.tuned.core.GuideFixes
import app.tuned.core.Programme
import app.tuned.core.XmltvParser
import app.tuned.data.db.GuideChannelEntity
import app.tuned.data.db.GuideWriter
import app.tuned.data.db.ProgrammeEntity
import app.tuned.data.db.TunedDatabase
import java.io.InputStream

/**
 * One guide refresh: the provider's guide, then any extra guide links, read into one new guide that replaces the old
 * at the end ([GuideWriter]). A channel takes its programmes from the first guide that has it, so an extra guide only
 * fills gaps. The whole guide is moved by [offsetMinutes], and channels with their own fix get their copies
 * ([GuideFixes]).
 */
class GuideImport(
    private val db: TunedDatabase,
    private val days: Int,
    offsetMinutes: Int,
) {
    private val shift = offsetMinutes * GuideFixes.MINUTE
    private val writer = GuideWriter(db)
    private val batch = ArrayList<ProgrammeEntity>(BATCH)
    private val filled = HashSet<String>()
    private val channels = LinkedHashMap<String, String>()
    private var count = 0
    private lateinit var wanted: Set<String>
    private lateinit var targets: Map<String, List<Pair<String, Long>>>
    private val pastFrom = HashMap<String, Long>()
    private var from = 0L
    private var until = 0L

    /** False when there are no live channels to fill, and nothing is read. */
    suspend fun begin(): Boolean {
        val fixes =
            db.guideFixes().all().mapNotNull { f ->
                (f.guideId ?: f.playlistTvgId)?.let { GuideFixes.Fix(f.itemId, it, f.offsetMinutes) }
            }
        targets = GuideFixes.targets(fixes)
        wanted = db.items().liveTvgIds().toHashSet() + targets.keys
        if (wanted.isEmpty()) return false
        val now = System.currentTimeMillis()
        from = now - HOUR
        until = now + days * DAY
        // Channels with catch-up keep their past programmes for as long as the server keeps the recordings.
        val archive = db.catchUp().all().associate { it.streamId to it.days }
        if (archive.isNotEmpty()) {
            for (link in db.items().liveLinks()) {
                val keep = CatchUp.streamId(link.url)?.let(archive::get) ?: continue
                pastFrom.merge(link.tvgId, CatchUp.keepFrom(now, keep), ::minOf)
            }
        }
        // Written beside the old guide and swapped in at the end, so searching and browsing never wait on it.
        writer.begin()
        return true
    }

    /** Reads one guide file. Channels an earlier guide already filled are skipped. */
    suspend fun read(
        input: InputStream,
        saved: (Int) -> Unit,
    ) {
        val parser = Xml.newPullParser()
        parser.setInput(input, null)
        val before = HashSet(filled)
        val programmes =
            XmltvParser.programmes(
                parser,
                keep = { id, start, stop -> id in wanted && id !in before && inWindow(id, start + shift, stop + shift) },
                onChannel = { id, name -> channels.putIfAbsent(id, name) },
            )
        for (p in programmes) {
            filled += p.channelId
            add(p.channelId, p, shift)
            targets[p.channelId]?.forEach { (fixedId, extra) -> add(fixedId, p, shift + extra) }
            if (batch.size >= BATCH) {
                writer.add(batch.toList())
                batch.clear()
                saved(count)
            }
        }
        writer.add(batch.toList())
        batch.clear()
    }

    /** Swaps the new guide in; returns how many programmes it holds (0 keeps the old guide). */
    suspend fun commit(): Int {
        val total = writer.commit()
        if (total > 0) db.guideFixes().replaceChannels(channels.map { (id, name) -> GuideChannelEntity(id, name) })
        return total
    }

    fun discard() = writer.discard()

    private fun inWindow(
        id: String,
        start: Long,
        stop: Long,
    ): Boolean = stop > (pastFrom[id] ?: from) && start < until

    private fun add(
        channelId: String,
        p: Programme,
        by: Long,
    ) {
        batch +=
            ProgrammeEntity(
                channelId = channelId,
                start = p.start + by,
                stop = p.stop + by,
                title = p.title,
                description = p.description,
            )
        count++
    }

    private companion object {
        const val BATCH = 1_000
        const val HOUR = 3_600_000L
        const val DAY = 24 * HOUR
    }
}
