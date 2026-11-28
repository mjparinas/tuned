package com.tunedplayer.app.data.db

import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.room.InvalidationTracker
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import com.tunedplayer.app.diagnostics.AppLog
import com.tunedplayer.app.diagnostics.attempt
import com.tunedplayer.app.diagnostics.launchSafely
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * One browse list as [QuickPagingSource] reads it: its rows ([sql], without an order), the sort ([orderBy]) and the
 * filters ([args], bound by [SqlArgs]). Every sort of a list counts the same, so the count is kept by [name] and
 * [args] only; [clock] names the argument that only moves on with time (the guide's "now"), whose last count is still
 * a good guess when it ticks.
 */
class QuickQuery<T : Any>(
    val name: String,
    val sql: String,
    val orderBy: String,
    val args: Map<String, Any?>,
    /** The tables the list reads: a change to one starts it over and makes its count a guess. */
    val tables: Array<String>,
    val clock: String? = null,
    /** A quicker `SELECT COUNT(*)` of the same rows, or null to count [sql] itself. */
    private val countSql: String? = null,
    val read: suspend (QuickDao, SupportSQLiteQuery) -> List<T>,
) {
    /** What decides how many rows there are. */
    val countKey: List<Any?> = listOf(name, args)

    /** [countKey] without [clock]: the same list a few minutes on. */
    val familyKey: List<Any?> = listOf(name, args - clock.orEmpty())

    fun rows(
        limit: Int,
        offset: Int,
    ): SupportSQLiteQuery = raw("$sql $orderBy LIMIT :limit OFFSET :offset", args + mapOf("limit" to limit, "offset" to offset))

    fun count(): SupportSQLiteQuery = raw(countSql ?: "SELECT COUNT(*) FROM ($sql)", args)

    private fun raw(
        text: String,
        values: Map<String, Any?>,
    ): SupportSQLiteQuery {
        val (bound, array) = SqlArgs.bind(text, values)
        return SimpleSQLiteQuery(bound, array)
    }
}

/**
 * How long each browse list is, counted in the background ([QuickPagingSource]). One per screen, so going back to a
 * tab, a filter or a sort already counted puts its whole length (and the fast scrollbar) up at once. Any change to
 * the tables the lists read makes every count a guess again ([QUICK_TABLES]): still used for the list's length while
 * the new count runs, and corrected if it was wrong. The last counts are also kept in [saved] (in the cache folder,
 * which signing out empties), so the lists have their whole length from the first frame after the app opens, and the
 * count that follows only starts a list over if the playlist changed meanwhile.
 */
class ListCounts(
    private val db: TunedDatabase,
    internal val scope: CoroutineScope,
    private val saved: File? = null,
) {
    private class Counted(
        val count: Int,
        val tables: Array<String>,
    )

    private val exact = ConcurrentHashMap<List<Any?>, Counted>()
    private val latest = ConcurrentHashMap<List<Any?>, Int>()
    private val versions = QUICK_TABLES.associateWith { AtomicLong() }
    private val watching = AtomicBoolean(false)

    /** Counts from before the app opened, by [QuickQuery.familyKey]'s hash: guesses only, the newest last. */
    private val remembered = LinkedHashMap<Int, Int>(REMEMBERED, LOAD_FACTOR, true)
    private val loaded = AtomicBoolean(false)
    private val saving = AtomicBoolean(false)

    private val changes =
        object : InvalidationTracker.Observer(QUICK_TABLES) {
            override fun onInvalidated(tables: Set<String>) = changed(tables)
        }

    /** [tables] changed: the counts that read them are guesses from now on. Safe to hear twice. */
    fun changed(tables: Set<String>) {
        tables.forEach { versions[it]?.incrementAndGet() }
        exact.entries.removeIf { (_, counted) -> counted.tables.any { it in tables } }
    }

    /** Starts noticing changes; called from a list's first page, which runs off the screen thread. */
    internal fun watch() {
        load()
        if (!watching.compareAndSet(false, true)) return
        val added = attempt(TAG, "Couldn't watch the lists' tables") { db.invalidationTracker.addObserver(changes) }
        if (added.isFailure) {
            watching.set(false)
            return
        }
        scope.coroutineContext[Job]?.invokeOnCompletion { db.stopWatching(changes) }
    }

    /** The count for [query]'s filters, if nothing has changed since it was taken. */
    fun exact(query: QuickQuery<*>): Int? = exact[query.countKey]?.count

    /** The last count of the same list, with these filters or a few minutes ago: a guess while it is recounted. */
    fun guess(query: QuickQuery<*>): Int? =
        exact(query) ?: latest[query.familyKey] ?: synchronized(remembered) { remembered[query.familyKey.hashCode()] }

    /** Notes a count, unless [query]'s tables changed while it was taken ([taken] is its [stamp] from before). */
    fun record(
        query: QuickQuery<*>,
        count: Int,
        taken: Long,
    ) {
        latest[query.familyKey] = count
        if (stamp(query) == taken) exact[query.countKey] = Counted(count, query.tables)
        val changed = synchronized(remembered) { remembered.put(query.familyKey.hashCode(), count) != count }
        if (changed) saveSoon()
    }

    /** Reads the counts from before the app opened, once; off the screen thread (a list's first page calls it). */
    private fun load() {
        val file = saved ?: return
        if (!loaded.compareAndSet(false, true)) return
        val lines = attempt(TAG, "Couldn't read the saved list lengths") { if (file.exists()) file.readLines() else emptyList() }
        val read =
            lines.getOrDefault(emptyList()).mapNotNull { line ->
                val (key, count) = line.split('\t').takeIf { it.size == 2 } ?: return@mapNotNull null
                (key.toIntOrNull() ?: return@mapNotNull null) to (count.toIntOrNull() ?: return@mapNotNull null)
            }
        // Counts taken since the app opened are newer than the file's.
        synchronized(remembered) { read.forEach { (key, count) -> remembered.putIfAbsent(key, count) } }
    }

    /** Writes the counts a moment after they change, so a run of counts is one write. */
    private fun saveSoon() {
        val file = saved ?: return
        if (!saving.compareAndSet(false, true)) return
        scope.launchSafely(TAG, onError = { saving.set(false) }) {
            delay(SAVE_DELAY_MS)
            saving.set(false)
            val text =
                synchronized(remembered) {
                    while (remembered.size > REMEMBERED) remembered.remove(remembered.keys.first())
                    remembered.entries.joinToString("\n") { (key, count) -> "$key\t$count" }
                }
            withContext(Dispatchers.IO) {
                attempt(TAG, "Couldn't save the list lengths") {
                    val temp = File(file.path + ".tmp")
                    temp.writeText(text)
                    if (!temp.renameTo(file)) temp.delete()
                }
            }
        }
    }

    /** How many times [query]'s tables have changed, to tell whether a count taken since still holds. */
    fun stamp(query: QuickQuery<*>): Long = query.tables.sumOf { versions[it]?.get() ?: 0L }

    private companion object {
        const val TAG = "ListCounts"
        const val REMEMBERED = 256
        const val LOAD_FACTOR = 0.75f
        const val SAVE_DELAY_MS = 2_000L
    }
}

/**
 * A browse list that shows its first rows at once and counts the rest in the background (Melvin, 2026-11-28:
 * "Instead of loading all channels, can we progressively load them?"). Room's own paged lists count every matching
 * row before the first page, a full pass over the playlist (157 ms against 0.5 ms for the first 120 rows on a desktop
 * with 180,000 items, and several times that on a TV box), so a big playlist showed placeholders for seconds.
 *
 * The first page needs no count: the list is as long as what has loaded and grows as it scrolls. When the count
 * lands, the list starts over where it is, now with its full length, so the fast scrollbar covers it all. A list
 * counted before (going back to a tab, a sort, a filter) uses its last count straight away, and a change to the
 * tables only re-reads the rows on screen while the new count runs. Failures are logged and shown as the list's
 * error, never a crash.
 */
class QuickPagingSource<T : Any>(
    private val db: TunedDatabase,
    private val counts: ListCounts,
    private val query: QuickQuery<T>,
) : PagingSource<Int, T>() {
    private val registered = AtomicBoolean(false)
    private val watcher = Watcher(db, counts, query.tables, WeakReference(this))

    @Volatile private var counting: Job? = null

    init {
        registerInvalidatedCallback {
            counting?.cancel()
            if (registered.get()) db.stopWatching(watcher)
        }
    }

    override val jumpingSupported: Boolean get() = true

    override fun getRefreshKey(state: PagingState<Int, T>): Int? =
        state.anchorPosition?.let { maxOf(0, it - state.config.initialLoadSize / 2) }

    @Suppress("TooGenericExceptionCaught") // any failure becomes the list's error state, logged, instead of a crash
    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, T> = try {
        offMain {
            watch()
            when (params) {
                is LoadParams.Refresh -> {
                    refresh(params.key ?: 0, params.loadSize)
                }

                is LoadParams.Prepend -> {
                    val end = params.key
                    val start = maxOf(0, end - params.loadSize)
                    page(start, end - start)
                }

                is LoadParams.Append -> {
                    page(params.key, params.loadSize)
                }
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        AppLog.w(TAG, "Couldn't read ${query.name}", e)
        LoadResult.Error(e)
    }

    private fun watch() {
        counts.watch()
        if (invalid || !registered.compareAndSet(false, true)) return
        db.invalidationTracker.addObserver(watcher)
    }

    private suspend fun rows(
        limit: Int,
        offset: Int,
    ): List<T> = if (limit <= 0) emptyList() else query.read(db.quick(), query.rows(limit, offset))

    private suspend fun page(
        start: Int,
        limit: Int,
    ): LoadResult<Int, T> {
        val rows = rows(limit, start)
        return LoadResult.Page(
            data = rows,
            prevKey = start.takeIf { it > 0 },
            nextKey = if (rows.size < limit) null else start + rows.size,
        )
    }

    private suspend fun refresh(
        key: Int,
        size: Int,
    ): LoadResult<Int, T> {
        val known = counts.exact(query)
        val guess = known ?: counts.guess(query)
        var start = if (guess != null && key >= guess) maxOf(0, guess - size) else maxOf(0, key)
        val stamp = counts.stamp(query)
        var rows = rows(size, start)
        if (rows.isEmpty() && start > 0) {
            // The list got shorter than where it was: start from the top.
            start = 0
            rows = rows(size, 0)
        }
        val complete = rows.size < size
        if (complete) counts.record(query, start + rows.size, stamp)
        val after =
            when {
                complete -> 0
                guess != null -> maxOf(0, guess - start - rows.size)
                else -> LoadResult.Page.COUNT_UNDEFINED
            }
        if (!complete && known == null) countLater(shown = guess)
        return LoadResult.Page(
            data = rows,
            prevKey = start.takeIf { it > 0 },
            nextKey = if (complete) null else start + rows.size,
            itemsBefore = start,
            itemsAfter = after,
        )
    }

    /** Counts the list in the background; starts it over with its full length unless [shown] was already right. */
    private fun countLater(shown: Int?) {
        counting?.cancel()
        counting =
            counts.scope.launchSafely(TAG) {
                val stamp = counts.stamp(query)
                val total =
                    attempt(TAG, "Couldn't count ${query.name}") {
                        offMain { db.quick().count(query.count()) }
                    }.getOrNull() ?: return@launchSafely
                counts.record(query, total, stamp)
                if (total != shown) invalidate()
            }
    }

    /** Starts the list over when its tables change; lets go once the list is gone. */
    private class Watcher(
        private val db: TunedDatabase,
        private val counts: ListCounts,
        tables: Array<String>,
        private val source: WeakReference<QuickPagingSource<*>>,
    ) : InvalidationTracker.Observer(tables) {
        override fun onInvalidated(tables: Set<String>) {
            // Before the list starts over, so its next first page doesn't take an old count for a fresh one.
            counts.changed(tables)
            val list = source.get()
            if (list == null) db.stopWatching(this) else list.invalidate()
        }
    }

    private companion object {
        const val TAG = "QuickPagingSource"
    }
}

/**
 * Stops [observer] on Room's own thread: removing one changes the database's triggers, which Room asks to keep off the
 * screen thread, and a list is often let go from it.
 */
private fun TunedDatabase.stopWatching(observer: InvalidationTracker.Observer) {
    attempt("QuickPagingSource", "Couldn't stop watching a list") {
        queryExecutor.execute {
            attempt("QuickPagingSource", "Couldn't stop watching a list") { invalidationTracker.removeObserver(observer) }
        }
    }
}
