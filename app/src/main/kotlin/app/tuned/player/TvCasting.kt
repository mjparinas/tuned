package app.tuned.player

import android.content.Context
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import app.tuned.diagnostics.attempt
import com.google.android.gms.cast.CastMediaControlIntent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * True while a TV (Chromecast or DLNA) is picked to play Tuned's stream. The cast carries on while the app is in the
 * background, and the TV's stream is the account's one connection, so downloads and channel scans keep waiting until the
 * cast ends (Melvin, 2026-11-18: "the app should prioritize the stream and have the downloads wait"). Started by the
 * player on the main thread, the only place a cast can begin, so nothing extra runs at app start.
 */
class TvCasting(
    private val context: Context,
    /** The cast ended: downloads carry on. */
    private val onEnded: () -> Unit,
) {
    private val state = MutableStateFlow(false)
    val active: StateFlow<Boolean> = state
    private var router: MediaRouter? = null

    private val selector =
        MediaRouteSelector
            .Builder()
            .addControlCategory(CastMediaControlIntent.categoryForCast(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID))
            .addControlCategory(DlnaRouteProvider.CATEGORY)
            .build()

    private val callback =
        object : MediaRouter.Callback() {
            override fun onRouteSelected(
                router: MediaRouter,
                route: MediaRouter.RouteInfo,
                reason: Int,
            ) = check()

            override fun onRouteUnselected(
                router: MediaRouter,
                route: MediaRouter.RouteInfo,
                reason: Int,
            ) = check()

            override fun onRouteChanged(
                router: MediaRouter,
                route: MediaRouter.RouteInfo,
            ) = check()
        }

    /** Main thread only; once is enough. */
    fun watch() {
        if (router != null) return
        router =
            attempt("Cast", "Couldn't watch for a TV") {
                MediaRouter.getInstance(context).also { it.addCallback(selector, callback, 0) }
            }.getOrNull()
        check()
    }

    private fun check() {
        val selected = router?.selectedRoute
        val onTv = selected != null && !selected.isDefault && selected.matchesSelector(selector)
        val was = state.value
        state.value = onTv
        if (was && !onTv) onEnded()
    }
}
