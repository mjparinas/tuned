package com.tunedplayer.app.core

/**
 * The pictures a profile can wear on "Who's watching?" (docs/decisions/profiles.md). Each is an emoji drawn on a
 * gradient, so they cost nothing to download and look sharp at any size. A profile without one shows its first letter.
 *
 * The [premium] set needs Tuned Premium to pick (docs/decisions/premium.md), but a profile keeps the one it has if
 * Premium ends: taking someone's picture away would only annoy them.
 *
 * [key] is stored in the database, so it never changes. Emoji are from Unicode 10 or older, which every Android the
 * app runs on (8.0 and up) can draw.
 */
data class Avatar(
    val key: String,
    val glyph: String,
    val premium: Boolean,
)

object Avatars {
    val free: List<Avatar> =
        listOf(
            "fox" to "🦊",
            "panda" to "🐼",
            "tiger" to "🐯",
            "lion" to "🦁",
            "frog" to "🐸",
            "monkey" to "🐵",
            "penguin" to "🐧",
            "dog" to "🐶",
            "cat" to "🐱",
            "koala" to "🐨",
            "bear" to "🐻",
            "rabbit" to "🐰",
        ).map { (key, glyph) -> Avatar(key, glyph, premium = false) }

    val premium: List<Avatar> =
        listOf(
            "dragon" to "🐉",
            "unicorn" to "🦄",
            "trex" to "🦖",
            "owl" to "🦉",
            "shark" to "🦈",
            "octopus" to "🐙",
            "rocket" to "🚀",
            "alien" to "👽",
            "robot" to "🤖",
            "wizard" to "🧙",
            "crown" to "👑",
            "diamond" to "💎",
        ).map { (key, glyph) -> Avatar(key, glyph, premium = true) }

    val all: List<Avatar> = free + premium

    private val byKey = all.associateBy { it.key }

    /** The avatar stored as [key], or null for none (the profile's letter) or one this version doesn't know. */
    fun find(key: String?): Avatar? = key?.let { byKey[it] }

    /**
     * Whether a profile now wearing [current] may switch to [key] (null is the letter). Free avatars and the letter
     * always may; a Premium one needs Premium, unless it is the one the profile already has.
     */
    fun canPick(
        key: String?,
        current: String?,
        premiumUnlocked: Boolean,
    ): Boolean {
        val avatar = find(key) ?: return key == null
        return !avatar.premium || premiumUnlocked || key == current
    }
}
