package app.tuned.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.tuned.core.IntroSkip
import kotlinx.coroutines.flow.first

private val Context.intros by preferencesDataStore(name = "intros")

/** Each show's learned intro ([IntroSkip.learn]), by show key. Small, and kept apart from the settings and backups. */
class IntroStore(
    private val context: Context,
) {
    suspend fun get(showKey: String): IntroSkip.Intro? = IntroSkip.decode(context.intros.data.first()[stringPreferencesKey(showKey)])

    suspend fun set(
        showKey: String,
        intro: IntroSkip.Intro,
    ) {
        context.intros.edit { it[stringPreferencesKey(showKey)] = IntroSkip.encode(intro) }
    }

    /** Forgets one show's intro, when an episode's skips by hand turn out to be fast-forwarding. */
    suspend fun remove(showKey: String) {
        context.intros.edit { it.remove(stringPreferencesKey(showKey)) }
    }

    /** Signing out: every learned intro is forgotten. */
    suspend fun clear() {
        context.intros.edit { it.clear() }
    }

    /** Every learned intro as stored, by show key, for sync between devices ([SyncCopyRepository]). */
    suspend fun all(): Map<String, String> = context.intros.data
        .first()
        .asMap()
        .mapKeys { it.key.name }
        .mapNotNull { (k, v) -> (v as? String)?.let { k to it } }
        .toMap()

    /** Puts another device's [all] in place of this one's. */
    suspend fun replaceAll(intros: Map<String, String>) {
        context.intros.edit { prefs ->
            prefs.clear()
            intros.forEach { (k, v) -> prefs[stringPreferencesKey(k)] = v }
        }
    }
}
