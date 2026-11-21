package com.tunedplayer.app.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * Joins two versions of the sync copy that both changed since they last agreed (docs/decisions/accounts.md, "Playlists
 * and settings on the website"): this device's and the backup's, which the website or another device changed. Melvin,
 * 2026-11-20: the website should do "everything that the android app can do, and it should sync with the app and vice
 * versa". Whole-copy "newer wins" would let a TV that only added to its watch history wipe a playlist just added on the
 * website, so the copy is joined part by part against [base], the version both last agreed on: a part only one side
 * changed takes that side, and only a part both changed falls back to the newer side. Playlists, settings, intros and
 * profiles are joined item by item, so adding a playlist on the website and changing a setting on the TV both stay.
 */
object CopyMerge {
    /** Marks a copy the website made from nothing: it holds only playlists and settings, so it joins on an empty base. */
    const val PARTIAL = "partial"

    /**
     * The joined copy. [remoteWins] decides a part both sides changed differently: true when the backup's change is the
     * newer one (or this device has never synced, which takes the backup over a fresh device's defaults).
     */
    fun merge(
        base: JsonObject,
        local: JsonObject,
        remote: JsonObject,
        remoteWins: Boolean,
    ): JsonObject {
        val keys = (local.keys + remote.keys + base.keys) - PARTIAL
        val out = LinkedHashMap<String, JsonElement>()
        for (key in keys) {
            val joined =
                pick(empty(base[key]), empty(local[key]), empty(remote[key]), remoteWins) { b, l, r ->
                    when (key) {
                        "playlists" -> keyed(b, l, r, remoteWins, ::playlistKey)
                        "profiles" -> keyed(b, l, r, remoteWins) { it.field("id") }
                        "settings", "intros" -> fields(b as? JsonObject, l as? JsonObject, r as? JsonObject, remoteWins)
                        else -> if (remoteWins) r else l
                    }
                }
            if (joined != null) out[key] = joined
        }
        return JsonObject(out)
    }

    /** Whether two copies hold the same, ignoring the website's [PARTIAL] mark and parts that are only empty. */
    fun same(
        a: JsonObject,
        b: JsonObject,
    ): Boolean = tidy(a) == tidy(b)

    private fun tidy(o: JsonObject) = o.filter { (k, v) -> k != PARTIAL && empty(v) != null }

    /** A saved playlist's identity, as `Credentials.samePlaylist` has it: the server, the username and the link. */
    private fun playlistKey(e: JsonElement) = listOf("server", "username", "playlistUrl").joinToString("\n") { e.field(it) }

    private fun JsonElement.field(name: String): String = ((this as? JsonObject)?.get(name) as? JsonPrimitive)?.let {
        if (it is JsonNull) "" else it.jsonPrimitive.content
    } ?: ""

    /** The three-way rule: equal sides agree, one side unchanged takes the other, and both changed asks [conflict]. */
    private inline fun pick(
        b: JsonElement?,
        l: JsonElement?,
        r: JsonElement?,
        remoteWins: Boolean,
        conflict: (JsonElement?, JsonElement?, JsonElement?) -> JsonElement?,
    ): JsonElement? = when {
        l == r -> l

        l == b -> r

        r == b -> l

        // One side removed a part the other changed: the newer change stands.
        l == null || r == null -> if (remoteWins) r else l

        else -> conflict(b, l, r)
    }

    /** Lists of items with an identity: each item joined on its own; the backup's order, then this device's additions. */
    private fun keyed(
        b: JsonElement?,
        l: JsonElement?,
        r: JsonElement?,
        remoteWins: Boolean,
        key: (JsonElement) -> String,
    ): JsonElement? {
        val base = (b as? JsonArray).orEmpty().associateBy(key)
        val local = (l as? JsonArray).orEmpty().associateBy(key)
        val remote = (r as? JsonArray).orEmpty().associateBy(key)
        val order = (remote.keys + local.keys + base.keys).distinct()
        val items = order.mapNotNull { k -> pick(base[k], local[k], remote[k], remoteWins) { _, li, ri -> if (remoteWins) ri else li } }
        return if (items.isEmpty()) null else JsonArray(items)
    }

    /** Maps (settings by key, intros by show): each entry joined on its own. */
    private fun fields(
        b: JsonObject?,
        l: JsonObject?,
        r: JsonObject?,
        remoteWins: Boolean,
    ): JsonElement? {
        val keys = (r.orEmpty().keys + l.orEmpty().keys + b.orEmpty().keys).toSortedSet()
        val out = LinkedHashMap<String, JsonElement>()
        for (k in keys) {
            pick(b?.get(k), l?.get(k), r?.get(k), remoteWins) { _, li, ri -> if (remoteWins) ri else li }?.let { out[k] = it }
        }
        return if (out.isEmpty()) null else JsonObject(out)
    }

    /** A part left out, null, or empty counts as one thing: the app leaves empty lists out of its copy. */
    private fun empty(e: JsonElement?): JsonElement? = when (e) {
        null, JsonNull -> null
        is JsonArray -> e.takeIf { it.isNotEmpty() }
        is JsonObject -> e.takeIf { it.isNotEmpty() }
        else -> e
    }
}
