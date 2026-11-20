package com.tunedplayer.app.data

import android.util.Xml
import com.tunedplayer.app.core.CatchUp
import com.tunedplayer.app.core.GuideFixes
import com.tunedplayer.app.core.GuideShift
import com.tunedplayer.app.core.Programme
import com.tunedplayer.app.core.XmltvParser
import com.tunedplayer.app.data.db.GuideChannelEntity
import com.tunedplayer.app.data.db.GuideWriter
import com.tunedplayer.app.data.db.ProgrammeEntity
import com.tunedplayer.app.data.db.TunedDatabase
import kotlinx.coroutines.flow.Flow
import java.io.InputStream

/**
 * One guide refresh: the provider's guide, then any extra guide links, read into one new guide that replaces the old
 * at the end ([GuideWriter]). A channel takes its programmes from the first guide that has it, so an extra guide only
 * fills gaps. The whole guide is moved by [offsetMinutes]; a channel with a playlist `tvg-shift` ([GuideShift]) or its
 * own fix ([GuideFixes]) gets its own copy, moved by that too.
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

    /** Each guide channel to read, and the ids to write its programmes under, with how far to move each copy. */
    private lateinit var targets: Map<String, List<Pair<String, Long>>>

    private val pastFrom = HashMap<String, Long>()
    private var from = 0L
    private var until = 0L

    /** False when there are no live channels to fill, and nothing is read. */
    suspend fun begin(): Boolean {
        val copies = HashMap<String, MutableList<Pair<String, Long>>>()
        for (id in db.items().liveTvgIds()) {
            copies.getOrPut(GuideShift.base(id)) { ArrayList(1) } += id to GuideShift.offset(id)
        }
        // A fix on a channel with a tvg-shift reads the unshifted guide entry and keeps the shift on top of its own.
        val fixes =
            db.guideFixes().all().mapNotNull { f ->
                val source = f.guideId ?: f.playlistTvgId ?: return@mapNotNull null
                GuideFixes.Fix(f.itemId, GuideShift.base(source), f.offsetMinutes) to GuideShift.offset(source)
            }
        val shifts = fixes.associate { (fix, shift) -> GuideFixes.fixedId(fix.itemId) to shift }
        for ((source, list) in GuideFixes.targets(fixes.map { it.first })) {
            copies.getOrPut(source) { ArrayList(list.size) } += list.map { (id, by) -> id to by + (shifts[id] ?: 0) }
        }
        targets = copies
        if (targets.isEmpty()) return false
        val now = System.currentTimeMillis()
        from = now - HOUR
        until = now + days * DAY
        // Channels with catch-up keep their past programmes for as long as the server keeps the recordings.
        val archive = db.catchUp().all().associate { it.streamId to it.days }
        if (archive.isNotEmpty()) {
            for (link in db.items().liveLinks()) {
                val keep = archive[CatchUp.key(link.url)] ?: continue
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
                keep = { id, start, stop -> wanted(id, start, stop, before) },
                onChannel = { id, name -> channels.putIfAbsent(id, name) },
            )
        for (p in programmes) take(p, saved)
        flush()
    }

    /** Reads programmes from another source than XMLTV (a Stalker portal's own guide), the same way as [read]. */
    suspend fun read(
        programmes: Flow<Programme>,
        saved: (Int) -> Unit,
    ) {
        val before = HashSet(filled)
        programmes.collect { p -> if (wanted(p.channelId, p.start, p.stop, before)) take(p, saved) }
        flush()
    }

    /** A channel no earlier guide filled, and a programme inside the window for one of its copies. */
    private fun wanted(
        id: String,
        start: Long,
        stop: Long,
        before: Set<String>,
    ): Boolean = id !in before && targets[id]?.any { (to, by) -> inWindow(to, start + shift + by, stop + shift + by) } == true

    private suspend fun take(
        p: Programme,
        saved: (Int) -> Unit,
    ) {
        filled += p.channelId
        for ((to, by) in targets[p.channelId].orEmpty()) {
            if (inWindow(to, p.start + shift + by, p.stop + shift + by)) add(to, p, shift + by)
        }
        if (batch.size >= BATCH) {
            writer.add(batch.toList())
            batch.clear()
            saved(count)
        }
    }

    private suspend fun flush() {
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
