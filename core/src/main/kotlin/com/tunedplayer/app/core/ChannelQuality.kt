package com.tunedplayer.app.core

/**
 * The picture quality a channel's name gives (Melvin, 2026-11-20: "add them all"): providers mark it with a word in
 * the name ("TSN 1 FHD", "Sky Sports Main Event UHD", "CNN HD", "ESPN 720p"). Matched like [LiveTopic], through the
 * full-text index on names ([fullText]), so 40,000 channels are narrowed by one index lookup. Words are whole, so
 * "hd" isn't "fhd" and a channel named "Channel 4" isn't 4K. A name with two marks ("HD 1080p") is in both.
 */
enum class ChannelQuality(
    private val words: List<String>,
) {
    UHD(listOf("4k", "uhd", "2160p", "8k")),
    FHD(listOf("fhd", "1080p", "1080i", "fullhd", "full hd")),
    HD(listOf("hd", "720p", "hdtv")),
    SD(listOf("sd", "480p", "576p", "576i", "480i", "lq")),
    ;

    /** The full-text query on `items_fts` (normalized names) for channels named with one of the words. */
    val fullText: String = words.joinToString(" OR ") { if (' ' in it) "\"$it\"" else it }

    /** Whether a channel [name] carries one of the words, by the same rules as [fullText]. */
    fun matches(name: String): Boolean {
        val tokens = Search.normalize(name).split(' ').filter { it.isNotEmpty() }
        return words.any { word -> if (' ' in word) tokens.windowed(2).any { it == word.split(' ') } else word in tokens }
    }
}
