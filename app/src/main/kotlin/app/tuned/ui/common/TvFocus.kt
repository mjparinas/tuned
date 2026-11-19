package app.tuned.ui.common

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import app.tuned.core.ListPaging
import app.tuned.diagnostics.attempt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Where a TV remote's focus is in one list or grid, so it can be put back and moved a page at a time (Melvin,
 * 2026-11-19: "Optimize TV for remotes"):
 *
 * - When the list opens, focus lands on the item last focused (kept across leaving and coming back), or the first
 *   item, so the first press of an arrow moves from somewhere visible instead of nothing.
 * - When a pane beside the list closes, or a page opened from it comes back, focus returns to the item that opened it.
 * - The page and channel keys jump a screenful ([ListPaging]).
 * - Back while scrolled down goes to the top of the list first, as on other TV apps; the next Back leaves.
 *
 * It does nothing on phones and tablets, where touch never focuses a list item.
 */
@Stable
class TvFocus internal constructor(
    private val saved: MutableState<Any?>,
    val enabled: Boolean,
) {
    private sealed interface Target {
        data class Key(
            val key: Any,
        ) : Target

        data object First : Target
    }

    private var target by mutableStateOf<Target?>(null)

    /** The lazy key of the item holding focus, or null while focus is elsewhere. */
    internal var focusedKey by mutableStateOf<Any?>(null)
        private set

    /** Keys of the items on screen that can take focus, so paging never lands on a row above them (Continue watching). */
    internal val items = HashSet<Any>()

    /** Whether any item has shown yet; the list's rows come from the database a moment after the screen opens. */
    internal var shown by mutableStateOf(false)

    /** Asks for the item last focused, or the first. Returns what it asked for, so [expire] can let it go. */
    internal fun restore(): Any? {
        if (!enabled) return null
        return (saved.value?.let { Target.Key(it) } ?: Target.First).also { target = it }
    }

    internal fun restoreFirst(): Any? {
        if (!enabled) return null
        return Target.First.also { target = it }
    }

    /**
     * Lets go of a [restore] no item took up in time (the item is off screen), so it can't pull focus away later when
     * that item scrolls into view while the person is busy elsewhere.
     */
    internal fun expire(asked: Any?) {
        if (asked != null && target === asked) target = null
    }

    internal fun focusKey(key: Any) {
        target = Target.Key(key)
    }

    internal fun claims(
        key: Any,
        first: Boolean,
    ): Boolean = when (val t = target) {
        null -> false
        is Target.Key -> t.key == key
        Target.First -> first
    }

    internal fun done() {
        target = null
    }

    internal fun focused(
        key: Any,
        on: Boolean,
    ) {
        if (on) {
            saved.value = key
            focusedKey = key
        } else if (focusedKey == key) {
            focusedKey = null
        }
    }
}

/**
 * A [TvFocus] for one list. [returnKey] changes when focus should go back to the list, such as a pane beside it
 * closing; the list's first showing (and each return to its screen) does that too.
 */
@Composable
fun rememberTvFocus(returnKey: Any? = Unit): TvFocus {
    val tv = isTelevision()
    val saved = rememberSaveable { mutableStateOf<Any?>(null) }
    val focus = remember(tv) { TvFocus(saved, tv) }
    LaunchedEffect(focus, returnKey) {
        val asked = focus.restore()
        // The window starts once the rows are there, so a slow first load from the database still gets focus.
        snapshotFlow { focus.shown }.first { it }
        delay(CLAIM_WINDOW_MS)
        focus.expire(asked)
    }
    return focus
}

/**
 * The modifier for one item of the list, by its lazy [key]; [first] for the list's first item. It takes focus when
 * [TvFocus] asks for this item and remembers it while focused. Put it before the item's clickable, so it reads that
 * clickable's focus.
 */
@Composable
fun tvFocusItem(
    focus: TvFocus,
    key: Any,
    first: Boolean,
): Modifier {
    if (!focus.enabled) return Modifier
    val requester = remember { FocusRequester() }
    DisposableEffect(focus, key) {
        focus.items += key
        focus.shown = true
        onDispose { focus.items -= key }
    }
    val mine = focus.claims(key, first)
    LaunchedEffect(mine) {
        if (mine) {
            attempt(TAG, "Couldn't focus the list item") { requester.requestFocus() }
            focus.done()
        }
    }
    return Modifier
        .focusRequester(requester)
        .onFocusChanged { focus.focused(key, it.hasFocus) }
}

/** The page keys and Back-to-top for a list ([LazyListState]); see [TvFocus]. Put it on the list. */
@Composable
fun tvPagedList(
    focus: TvFocus,
    state: LazyListState,
): Modifier = tvPaged(
    focus = focus,
    visible = { state.layoutInfo.visibleItemsInfo.map { it.index to it.key } },
    count = { state.layoutInfo.totalItemsCount },
    scrolled = state.firstVisibleItemIndex > 0,
    scrollTo = { state.scrollToItem(it) },
)

/** The page keys and Back-to-top for a grid ([LazyGridState]); see [TvFocus]. Put it on the grid. */
@Composable
fun tvPagedGrid(
    focus: TvFocus,
    state: LazyGridState,
): Modifier = tvPaged(
    focus = focus,
    visible = { state.layoutInfo.visibleItemsInfo.map { it.index to it.key } },
    count = { state.layoutInfo.totalItemsCount },
    scrolled = state.firstVisibleItemIndex > 0,
    scrollTo = { state.scrollToItem(it) },
)

@Composable
private fun tvPaged(
    focus: TvFocus,
    /** Index and key of each item on screen, in order. */
    visible: () -> List<Pair<Int, Any>>,
    count: () -> Int,
    scrolled: Boolean,
    scrollTo: suspend (Int) -> Unit,
): Modifier {
    if (!focus.enabled) return Modifier
    val scope = rememberCoroutineScope()
    // Only while focus is in this list, so Back still closes a pane or a dialog first.
    BackHandler(enabled = scrolled && focus.focusedKey != null) {
        scope.launch {
            scrollTo(0)
            val asked = focus.restoreFirst()
            delay(CLAIM_WINDOW_MS)
            focus.expire(asked)
        }
    }
    return Modifier.onPreviewKeyEvent { event ->
        val direction = ListPaging.direction(event.key.nativeKeyCode)
        val key = focus.focusedKey
        if (direction == 0 || key == null) return@onPreviewKeyEvent false
        if (event.type == KeyEventType.KeyDown) {
            val onScreen = visible().filter { (_, k) -> k in focus.items }
            val from = onScreen.firstOrNull { (_, k) -> k == key }?.first
            if (from != null) {
                val to = ListPaging.target(from, direction, onScreen.size, count())
                scope.launch {
                    scrollTo(to)
                    // The item at the new spot, or the nearest one after it that can take focus, once the list has laid
                    // it out (which can be a frame after the scroll).
                    val next =
                        withTimeoutOrNull(CLAIM_WINDOW_MS) {
                            snapshotFlow { visible().firstOrNull { (i, k) -> i >= to && k in focus.items }?.second }
                                .filterNotNull()
                                .first()
                        }
                    next?.let { focus.focusKey(it) }
                }
            }
        }
        true
    }
}

/**
 * Where focus lands when a page opens on a TV, such as Play on a film's page, so the first press of OK does the
 * likely thing. [startFocus] puts it on one element; the first one to appear within a moment of the page opening
 * takes it, and one that appears later (scrolled to) never steals it.
 */
@Stable
class StartFocus internal constructor(
    val enabled: Boolean,
) {
    internal val opened = System.currentTimeMillis()
    internal var taken = false
}

@Composable
fun rememberStartFocus(): StartFocus {
    val tv = isTelevision()
    return remember(tv) { StartFocus(tv) }
}

/** The modifier for the element [start] focuses; see [StartFocus]. Put it before the element's clickable. */
@Composable
fun startFocus(start: StartFocus): Modifier {
    if (!start.enabled) return Modifier
    val requester = remember { FocusRequester() }
    LaunchedEffect(start) {
        if (!start.taken && System.currentTimeMillis() - start.opened < CLAIM_WINDOW_MS) {
            start.taken = attempt(TAG, "Couldn't focus the page's first button") { requester.requestFocus() }.isSuccess
        }
    }
    return Modifier.focusRequester(requester)
}

private const val TAG = "TvFocus"

/** How long after a page opens focus may still be put on its item; after that the person's own moves win. */
private const val CLAIM_WINDOW_MS = 1_500L
