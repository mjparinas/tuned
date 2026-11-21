package com.tunedplayer.app.data

import com.tunedplayer.app.data.db.ProfileEntity
import com.tunedplayer.app.diagnostics.attempt
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * The profiles as "Who's watching?" last listed them, kept with the settings ([AppSettings.profilesSeen]) so the
 * picker draws at once on a cold start, before the database answers (Melvin, 2026-11-20: 2.2.0 took 20 to 30 seconds
 * to reach it on his TV). [ProfileRepository] writes it each time the profiles change; the database's list replaces it
 * as soon as it arrives. A PIN is kept as the same salted hash the database keeps, never the PIN.
 */
object ProfilesSeen {
    private const val TAG = "Profiles"
    private val json = Json { ignoreUnknownKeys = true }
    private val rows = ListSerializer(Row.serializer())

    @Serializable
    private data class Row(
        val id: Long,
        val name: String,
        val colour: Int,
        val kids: Boolean,
        val pinHash: String? = null,
        val createdAt: Long = 0,
        val avatar: String? = null,
    )

    fun encode(profiles: List<ProfileEntity>): String = json.encodeToString(
        rows,
        profiles.map { Row(it.id, it.name, it.colour, it.kids, it.pinHash, it.createdAt, it.avatar) },
    )

    /** The saved list, or empty when there is none or it can't be read (the picker then waits for the database). */
    fun decode(text: String): List<ProfileEntity> {
        if (text.isBlank()) return emptyList()
        return attempt(TAG, "Couldn't read the saved profiles") {
            json.decodeFromString(rows, text).map { ProfileEntity(it.id, it.name, it.colour, it.kids, it.pinHash, it.createdAt, it.avatar) }
        }.getOrNull().orEmpty()
    }
}
