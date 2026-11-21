package com.tunedplayer.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

private val Context.browseFilters by preferencesDataStore(name = "browse_filters")

/**
 * The filters chosen on Live TV, Movies and Shows, kept per profile until changed, even after the app is closed
 * (Melvin, 2026-11-21: "Filters should persist until changed, even when the app is closed"). Each profile's choices are
 * one encoded string ([com.tunedplayer.app.ui.browse.SavedFilters]). This device only: not in Settings or the backup,
 * since another device's playlist can have other categories. Reads and writes run off the screen thread.
 */
class BrowseFilterStore(
    private val context: Context,
) {
    suspend fun get(profileId: Long): String? = withContext(Dispatchers.IO) { context.browseFilters.data.first()[key(profileId)] }

    suspend fun set(
        profileId: Long,
        encoded: String,
    ) {
        withContext(Dispatchers.IO) { context.browseFilters.edit { it[key(profileId)] = encoded } }
    }

    /** A profile removed: its filters go with it. */
    suspend fun forget(profileId: Long) {
        withContext(Dispatchers.IO) { context.browseFilters.edit { it.remove(key(profileId)) } }
    }

    /** Signing out ([AppReset]): every profile's filters are forgotten. */
    suspend fun clear() {
        withContext(Dispatchers.IO) { context.browseFilters.edit { it.clear() } }
    }

    private fun key(profileId: Long) = stringPreferencesKey("profile_$profileId")
}
