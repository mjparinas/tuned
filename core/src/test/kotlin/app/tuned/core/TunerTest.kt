package app.tuned.core

import kotlin.test.Test
import kotlin.test.assertEquals

class TunerTest {
    private val now = 1_800_000_000_000L
    private val hour = 3_600_000L

    private fun c(
        id: Long,
        name: String,
        group: String = "",
        region: String? = null,
        guide: Boolean = false,
        okAt: Long = 0,
        deadAt: Long = 0,
        height: Int = 0,
    ) = TuneCandidate(id, name, group, region, nameMatch = !guide, guideMatch = guide, okAt = okAt, deadAt = deadAt, height = height)

    private fun order(vararg candidates: TuneCandidate) = Tuner.rank(candidates.toList(), now).map { it.id }

    @Test
    fun englishBeforeOtherLanguages() {
        assertEquals(
            listOf(2L, 3L, 1L),
            order(c(1, "FR| UFC 320 FHD", region = "FR"), c(2, "US| UFC 320", region = "US"), c(3, "UFC 320 HD")),
        )
    }

    @Test
    fun languageWordsOverrideAnEnglishCountryTag() {
        assertEquals(listOf(2L, 1L), order(c(1, "US| UFC 320 (Spanish)", region = "US"), c(2, "US| UFC 320", region = "US")))
    }

    @Test
    fun betterPictureFirstAmongEqualChannels() {
        assertEquals(
            listOf(3L, 2L, 4L, 1L),
            order(
                c(1, "US| PPV 1 SD", region = "US"),
                c(2, "US| PPV 2 FHD", region = "US"),
                c(3, "US| PPV 3 4K", region = "US"),
                c(4, "US| PPV 4", region = "US"),
            ),
        )
    }

    @Test
    fun recentlyDeadGoesLastAndComesBackLater() {
        val dead = c(1, "US| PPV 1 4K", region = "US", deadAt = now - hour)
        val fine = c(2, "US| PPV 2 SD", region = "US")
        assertEquals(listOf(2L, 1L), order(dead, fine))
        val longAgo = dead.copy(deadAt = now - Tuner.DEAD_FOR_MS - hour)
        assertEquals(listOf(1L, 2L), order(longAgo, fine))
    }

    @Test
    fun knownGoodBeforeUnknown() {
        assertEquals(
            listOf(2L, 1L),
            order(c(1, "US| PPV 1 FHD", region = "US"), c(2, "US| PPV 2", region = "US", okAt = now - hour, height = 720)),
        )
    }

    @Test
    fun guideMatchBeforeNameOnly() {
        assertEquals(listOf(2L, 1L), order(c(1, "US| UFC Fight Pass", region = "US"), c(2, "US| PPV 7", region = "US", guide = true)))
    }

    @Test
    fun measuredHeightBeatsTheName() {
        assertEquals(0, Tuner.quality(c(1, "US| PPV FHD", height = 576)))
        assertEquals(3, Tuner.quality(c(1, "US| PPV SD", height = 1080)))
        assertEquals(2, Tuner.qualityFromName("Sky Sports Main Event HD"))
        assertEquals(3, Tuner.qualityFromName("TSN 1 FHD"))
        assertEquals(1, Tuner.qualityFromName("TSN 1"))
    }

    @Test
    fun usAndCanadaBeforeOtherEnglishThenOtherCountries() {
        assertEquals(
            listOf(3L, 4L, 2L, 5L, 1L),
            order(
                c(1, "FR| UFC 335 4K", region = "FR"),
                c(2, "UK| UFC 335 4K", region = "UK"),
                c(3, "US| UFC 335", region = "US"),
                c(4, "CA| UFC 335 SD", region = "CA"),
                c(5, "UFC 335 FHD"),
            ),
        )
    }

    @Test
    fun eventWordsMatchInAnyOrderAndWithOrWithoutSpaces() {
        assertEquals(listOf("ufc", "335"), Tuner.words(Search.normalize("UFC 335")))
        assertEquals(listOf("ufc", "335"), Tuner.words(Search.normalize("ufc335")))
        assertEquals(listOf("canelo", "boxing"), Tuner.words(Search.normalize("Canelo boxing canelo")))
        assertEquals(3, Tuner.words("a bb ccc dddd").size)
        assertEquals(listOf("dddd", "ccc", "bb"), Tuner.words("a bb ccc dddd"))
        assertEquals(emptyList<String>(), Tuner.words(Search.normalize("  ")))
    }
}
