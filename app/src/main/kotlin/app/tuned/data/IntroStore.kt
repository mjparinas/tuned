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
}
