package app.tuned.core

/** A live channel that might be showing what the person searched for, with what Tuned remembers about it. */
data class TuneCandidate(
    val id: Long,
    val name: String,
    val group: String,
    val region: String?,
    /** The search matched the channel's name (e.g. "US| UFC PPV 1"). */
    val nameMatch: Boolean,
    /** The search matched a programme on now or starting soon, from the guide. */
    val guideMatch: Boolean,
    /** When the channel last played, or 0. */
    val okAt: Long = 0,
    /** When the channel last failed to play, or 0. */
    val deadAt: Long = 0,
    /** The picture height it last played at, or 0 if unknown. */
    val height: Int = 0,
)

/**
 * Auto-tune (Melvin, 2026-11-17: "i really just want to watch the first good quality stream that's in english").
 * Orders the channels an event search found, so the player can try them one at a time and stop at the first that
 * plays: channels that failed recently go last, then English before other languages, then what played before, then
 * the best picture.
 */
object Tuner {
    /** A channel that failed within this long is tried last. Streams come back, so it is not skipped for good. */
    const val DEAD_FOR_MS = 6 * 60 * 60_000L

    /** A channel that played within this long counts as known-good. */
    const val OK_FOR_MS = 7 * 24 * 60 * 60_000L

    /** Region tags whose channels are in English. */
    val englishRegions = setOf("US", "UK", "CA", "EN", "AU", "IE", "NZ")

    private val uhd = Regex("""\b(4K|UHD|2160P?)\b""", RegexOption.IGNORE_CASE)
    private val fhd = Regex("""\b(FHD|1080[PI]?)\b""", RegexOption.IGNORE_CASE)
    private val hd = Regex("""\b(HD|720P?)\b""", RegexOption.IGNORE_CASE)
    private val low = Regex("""\b(SD|LQ|LOW|480P?|576P?|360P?)\b""", RegexOption.IGNORE_CASE)

    /** Language words in a name or category that say a channel isn't English, whatever its country tag. */
    private val otherLanguage =
        Regex(
            """\b(FRENCH|FRANÇAIS|FRANCAIS|ESPAÑOL|ESPANOL|SPANISH|LATINO|ARABIC|DEUTSCH|GERMAN|ITALIANO|PORTUGU[EÊ]S|POLSKI|TÜRK|TURK|HINDI|URDU|DUBBED|DUB)\b""",
            RegexOption.IGNORE_CASE,
        )

    /** 3 for 4K, 2 for full HD, 1 for HD or unknown, 0 for SD; the measured height wins over the name. */
    fun quality(candidate: TuneCandidate): Int = when {
        candidate.height >= 2_000 -> 4
        candidate.height >= 1_000 -> 3
        candidate.height >= 700 -> 2
        candidate.height in 1..699 -> 0
        else -> qualityFromName("${candidate.name} ${candidate.group}")
    }

    fun qualityFromName(text: String): Int = when {
        uhd.containsMatchIn(text) -> 4
        fhd.containsMatchIn(text) -> 3
        hd.containsMatchIn(text) -> 2
        low.containsMatchIn(text) -> 0
        else -> 1
    }

    /** 1 for English, 0 for unknown, -1 for another language. */
    fun english(candidate: TuneCandidate): Int {
        val text = "${candidate.name} ${candidate.group}"
        return when {
            otherLanguage.containsMatchIn(text) -> -1
            candidate.region == null -> 0
            candidate.region in englishRegions -> 1
            else -> -1
        }
    }

    /** Best first. Stable, so equal channels keep the playlist's order. */
    fun rank(
        candidates: List<TuneCandidate>,
        now: Long,
    ): List<TuneCandidate> = candidates.sortedWith(
        compareBy<TuneCandidate> { recentlyDead(it, now) }
            .thenByDescending { english(it) }
            .thenByDescending { it.okAt > 0 && now - it.okAt < OK_FOR_MS && it.okAt > it.deadAt }
            .thenByDescending { it.guideMatch }
            .thenByDescending { quality(it) }
            .thenByDescending { it.nameMatch },
    )

    private fun recentlyDead(
        c: TuneCandidate,
        now: Long,
    ): Boolean = c.deadAt > c.okAt && now - c.deadAt < DEAD_FOR_MS
}
