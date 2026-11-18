package app.tuned.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Who's watching (docs/decisions/profiles.md). A kids profile sees kids and family categories only. */
@Entity(tableName = "profiles")
data class ProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Blank for the first profile until it's named; shown as "Me". */
    val name: String,
    /** An index into the accent colours, for the round badge. */
    val colour: Int,
    val kids: Boolean,
    /** A [app.tuned.core.PinHash] when the profile is locked, else null. */
    val pinHash: String?,
    val createdAt: Long,
)

/** The one row saying which profile is in use. Queries read it in SQL, so a switch refreshes every list at once. */
@Entity(tableName = "current_profile")
data class CurrentProfileEntity(
    @PrimaryKey val slot: Int = 0,
    val profileId: Long,
)

object Profiles {
    /** The profile every install starts with, and the one older favourites and history belong to. */
    const val FIRST = 1L
}
