package com.tunedplayer.app.core

import com.tunedplayer.app.core.ReleaseNotes.Change
import com.tunedplayer.app.core.ReleaseNotes.Kind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReleaseNotesTest {
    private val notes =
        """
        ## What's Changed
        * fix: the guide skipped a day after midnight (#140) by @mjparinas in https://github.com/mjparinas/tuned/pull/140
        * feat: keep playing in the background, with a setting to turn it off (#150) by @mjparinas in https://github.com/mjparinas/tuned/pull/150
        * build(deps): bump the gradle group with 3 updates by @dependabot[bot] in https://github.com/mjparinas/tuned/pull/141
        * Bump actions/checkout from 4 to 5 by @dependabot[bot] in https://github.com/mjparinas/tuned/pull/142
        * perf: Shows open faster by @mjparinas in https://github.com/mjparinas/tuned/pull/143
        * feat(player)!: tunnelled playback on Android TV by @mjparinas in https://github.com/mjparinas/tuned/pull/149
        * docs: decision record by @mjparinas in https://github.com/mjparinas/tuned/pull/144
        * Promote staging to main (2026-11-18) by @mjparinas in https://github.com/mjparinas/tuned/pull/145

        ## New Contributors
        * @someone made their first contribution in https://github.com/mjparinas/tuned/pull/146

        **Full Changelog**: https://github.com/mjparinas/tuned/compare/v1.9.0...v1.10.0
        """.trimIndent()

    @Test
    fun sortsChangesByKindAndCleansThem() {
        assertEquals(
            listOf(
                Change(Kind.NEW, "Keep playing in the background, with a setting to turn it off"),
                Change(Kind.NEW, "Tunnelled playback on Android TV"),
                Change(Kind.IMPROVED, "Shows open faster"),
                Change(Kind.FIXED, "The guide skipped a day after midnight"),
            ),
            ReleaseNotes.parse(notes),
        )
    }

    @Test
    fun countsEachKind() {
        val counts = ReleaseNotes.counts(ReleaseNotes.parse(notes))
        assertEquals(mapOf(Kind.NEW to 2, Kind.IMPROVED to 1, Kind.FIXED to 1), counts)
    }

    @Test
    fun plainTitlesAreImprovementsAndEmptyNotesAreEmpty() {
        assertEquals(listOf(Change(Kind.IMPROVED, "Smoother posters")), ReleaseNotes.parse("- smoother posters"))
        assertTrue(ReleaseNotes.parse("").isEmpty())
        assertTrue(ReleaseNotes.parse("**Full Changelog**: https://example.com").isEmpty())
    }
}
