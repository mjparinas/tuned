package com.tunedplayer.app.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A phone signed in to a Tuned account seals a one-time code for it into what Send to a TV sends
 * (docs/decisions/accounts.md), so the TV joins the same account without a second sign-in. It sits beside the sync
 * copy's own fields, which a TV that doesn't know it ignores; a backup never carries one.
 */
object HandoffAccount {
    private const val KEY = "accountLink"

    /** [copy] (the sync copy, JSON) with [link] added; the copy unchanged when [link] is null. */
    fun withLink(
        copy: ByteArray,
        link: String?,
    ): ByteArray {
        if (link == null) return copy
        val root = Json.parseToJsonElement(copy.decodeToString()).jsonObject
        return JsonObject(root + (KEY to JsonPrimitive(link))).toString().toByteArray()
    }

    /** The code a phone sealed into [copy], or null when it sent none. */
    fun link(copy: ByteArray): String? = runCatching {
        Json
            .parseToJsonElement(copy.decodeToString())
            .jsonObject[KEY]
            ?.jsonPrimitive
            ?.contentOrNull
            ?.takeIf { it.isNotBlank() }
    }.getOrNull()
}
