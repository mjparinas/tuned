package com.tunedplayer.app.data

/**
 * Where a refresh is, for the numbered steps on the first download, the bar above the lists and the notification
 * (Melvin, 2026-11-20: "if you know how large the download is, you should show me the real progress and how much and
 * total size. Then if there are other steps, it should mention those as well").
 */
data class SyncProgress(
    val step: Step,
    /** Bytes downloaded or read from the downloaded file, or rows saved. */
    val done: Long = 0,
    /** The bytes or rows [done] counts towards, or -1 when that isn't known (an indeterminate bar). */
    val total: Long = -1,
    /** Channels or programmes read so far, or -1 when the step doesn't count them. */
    val rows: Long = -1,
    /** Every step this refresh takes, in order; [step] is one of them. */
    val steps: List<Step> = listOf(step),
    /** When this [step] began ([System.currentTimeMillis]), for estimating how much longer it has left; 0 when unknown. */
    val startedAt: Long = 0,
) {
    /** In the order a refresh runs them. Names are only passed between the worker and the screens, never stored. */
    enum class Step {
        DownloadPlaylist,
        ReadPlaylist,
        SavePlaylist,
        Details,
        DownloadGuide,
        ReadGuide,
        SaveGuide,
        Library,
    }

    /** 0 to 1 while the size is known, otherwise null (an indeterminate bar). */
    val fraction: Float? get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null

    /** This step's number, from 1. */
    val number: Int get() = steps.indexOf(step).let { if (it < 0) steps.size + 1 else it + 1 }

    /** How many steps there are, counting this one even when the plan left it out. */
    val count: Int get() = if (step in steps) steps.size else steps.size + 1

    /** Not started yet: a download that hasn't heard from the server. */
    val waiting: Boolean get() = done == 0L && total < 0 && rows < 0

    companion object {
        /**
         * The steps a refresh takes. A file picked from the device isn't downloaded; only an Xtream login has catch-up
         * and film details to fetch; only a portal has a separate library of films and shows; and [guide] is false when
         * there is no guide to read.
         */
        fun plan(
            fromFile: Boolean,
            xtream: Boolean,
            portal: Boolean,
            guide: Boolean,
        ): List<Step> = buildList {
            if (!fromFile) add(Step.DownloadPlaylist)
            add(Step.ReadPlaylist)
            add(Step.SavePlaylist)
            if (xtream) add(Step.Details)
            if (guide) addAll(GUIDE)
            if (portal) add(Step.Library)
        }

        /**
         * The steps a full refresh of [login] takes. It has a guide to read from the same sources the refresh reads: a
         * portal's own, an Xtream server's, a guide link, or the one a plain playlist's header named ([headerGuide]).
         */
        fun planFor(
            login: Credentials?,
            headerGuide: Boolean,
        ): List<Step> = plan(
            fromFile = login?.kind == Credentials.Kind.File,
            xtream = login?.kind == Credentials.Kind.Xtream,
            portal = login?.kind == Credentials.Kind.Stalker,
            guide = login != null && hasGuide(login, headerGuide),
        )

        private fun hasGuide(
            login: Credentials,
            headerGuide: Boolean,
        ): Boolean = when (login.kind) {
            Credentials.Kind.Stalker, Credentials.Kind.Xtream -> true
            else -> login.guideUrl.isNotBlank() || login.extraGuides.isNotEmpty() || headerGuide
        }

        val GUIDE = listOf(Step.DownloadGuide, Step.ReadGuide, Step.SaveGuide)
    }
}
