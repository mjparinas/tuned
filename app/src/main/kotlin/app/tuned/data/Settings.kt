package app.tuned.data

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.tuned.core.ChannelScan
import app.tuned.core.KidsContent
import app.tuned.core.PinHash
import app.tuned.core.Placeholders
import app.tuned.core.RoundTheClock
import app.tuned.diagnostics.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.IOException

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** The colour of buttons, switches and highlights; violet is the brand colour (docs/decisions/branding.md). */
enum class Accent { VIOLET, BLUE, TEAL, GREEN, ORANGE, PINK, RED }

/** How the picture fills the screen: whole picture with bars, cropped to fill, or stretched. */
enum class VideoFit { FIT, ZOOM, STRETCH }

enum class StartTab { LIVE, MOVIES, SHOWS, LAST }

/** How much video to hold before and during playback: quick starts or fewer stalls. */
enum class BufferMode(
    val minMs: Int,
    val maxMs: Int,
    val startMs: Int,
) {
    FAST_START(5_000, 20_000, 800),
    BALANCED(15_000, 50_000, 1_500),
    SMOOTH(30_000, 120_000, 3_000),
}

enum class CaptionEdge { NONE, OUTLINE, DROP_SHADOW, RAISED, DEPRESSED }

enum class CaptionFont { DEFAULT, SANS, SERIF, MONO, CASUAL }

/** Caption look. Colours are ARGB. */
data class CaptionStyle(
    val enabled: Boolean = false,
    val language: String = "",
    val textScale: Float = 1f,
    val textColor: Int = 0xFFFFFFFF.toInt(),
    val backgroundColor: Int = 0xFF000000.toInt(),
    val backgroundOpacity: Float = 0.6f,
    val windowColor: Int = 0x00000000,
    val edge: CaptionEdge = CaptionEdge.OUTLINE,
    val edgeColor: Int = 0xFF000000.toInt(),
    val font: CaptionFont = CaptionFont.DEFAULT,
    val bold: Boolean = false,
    /** Ignore the colours and fonts a stream brings, so captions always look like the settings. */
    val overrideStreamStyle: Boolean = true,
    /** Distance from the bottom of the video, as a fraction of its height. */
    val bottomPadding: Float = 0.08f,
)

data class AppSettings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColour: Boolean = false,
    val pureBlack: Boolean = false,
    val accent: Accent = Accent.VIOLET,
    val startTab: StartTab = StartTab.LIVE,
    val lastTab: Int = 0,
    val hiddenRegions: Set<String> = emptySet(),
    /** Hidden groups, as [app.tuned.core.HiddenGroups] keys. */
    val hiddenGroups: Set<String> = emptySet(),
    /** Teams, leagues and fighters followed on Live Events ("Canucks", "UFC"), as typed. */
    val followedEvents: Set<String> = emptySet(),
    val showAdult: Boolean = false,
    val playlistRefreshHours: Int = 12,
    val guideRefreshHours: Int = 6,
    /** How long before a programme starts its reminder goes off. */
    val reminderLeadMinutes: Int = 5,
    val guideDays: Int = 3,
    /** Moves the whole guide, for one that runs early or late (Settings › Guide). */
    val guideOffsetMinutes: Int = 0,
    val unmeteredOnly: Boolean = false,
    val buffer: BufferMode = BufferMode.BALANCED,
    val audioLanguage: String = "",
    val resumePlayback: Boolean = true,
    val autoPlayNext: Boolean = true,
    /** Pull down on a list to refresh the playlist (Melvin, 2026-11-17: off, so a scroll never starts a download). */
    val pullToRefresh: Boolean = false,
    /** Leave out live channels that failed in two channel scans (Settings › Channel health). */
    val hideDeadChannels: Boolean = true,
    /** Hide a channel at once when it won't play while the internet and the service work (Settings › Channel health). */
    val hideFailedOnPlay: Boolean = true,
    /** When a live channel dies, switch to another copy of it, such as "ESPN FHD" for "ESPN HD" (Settings › Player). */
    val backupCopies: Boolean = true,
    /** Switch the display to the video's frame rate where the screen offers it (Settings › Player). */
    val matchFrameRate: Boolean = true,
    /** Minutes of a live channel kept on the device to pause and rewind; 0 turns it off (Settings › Player). */
    val liveRewindMinutes: Int = 30,
    /** Leave out empty event slots ("NO EVENT STREAMING"); see [Placeholders]. Melvin, 2026-11-17: hide them. */
    val hidePlaceholders: Boolean = true,
    /** Leave out round-the-clock "24/7" channels, films and shows; see [RoundTheClock]. Melvin, 2026-11-17 and 11-18. Off by default. */
    val hide247: Boolean = false,
    val pictureInPicture: Boolean = true,
    val playerGestures: Boolean = true,
    val autoReconnect: Boolean = true,
    val landscapePlayback: Boolean = true,
    /** The player's aspect button; the last choice sticks. */
    val videoFit: VideoFit = VideoFit.FIT,
    val controlsTimeoutMs: Int = 3_000,
    /** Auto-tune moves on when a channel shows no picture within this long. */
    val tuneTimeoutMs: Int = 8_000,
    /** Auto-tune skips channels below this picture height (0 takes any picture). */
    val tuneMinHeight: Int = 0,
    val seekBackMs: Long = 10_000,
    val seekForwardMs: Long = 30_000,
    /** How far "Skip intro" jumps on a show it hasn't learned yet; 0 hides the button. */
    val skipIntroMs: Long = 85_000,
    val userAgent: String = "",
    /** With captions on, a film or episode with no subtitles of its own fetches the best match by itself. */
    val autoSubtitles: Boolean = true,
    val showGuideInList: Boolean = true,
    /** Live TV as a grid guide (channels down the side, time across the top) instead of a list. */
    val liveGrid: Boolean = false,
    /** Numbers beside live channels, for typing one on the remote (Settings › Lists). */
    val channelNumbers: Boolean = true,
    /** Keep the scroll bar on Live TV, Movies and Shows on screen, instead of only while the list moves (Settings › Lists). */
    val alwaysShowScrollbar: Boolean = false,
    /** Open straight into the last live channel at launch, as a cable box does (Settings › Start on the last channel). */
    val startOnLastChannel: Boolean = false,
    /** Look for a newer release on GitHub at start (copies from Play update through Play either way). */
    val checkUpdates: Boolean = true,
    val captions: CaptionStyle = CaptionStyle(),
    val lastPlaylistSync: Long = 0,
    val lastGuideSync: Long = 0,
    val lastSyncError: String = "",
    /** The Xtream server's time zone, read at each refresh; catch-up links are in its local time. */
    val serverTimezone: String = "",
    /** Show "Who's watching?" when the app opens, while there's more than one profile. */
    val askProfileAtStart: Boolean = true,
    /**
     * The profile in use, mirrored here from the database by [ProfileRepository] so the first frame knows it without
     * opening the database. A kids profile leaves out adult and grown-up categories.
     */
    val profileId: Long = 1,
    val kidsProfile: Boolean = false,
    val profileCount: Int = 1,
    /** Settings › Developer: keep a detailed log and show the log viewer, for finding what went wrong. */
    val developerMode: Boolean = false,
)

/** Hidden countries, hidden groups and the channel filters together: the one list every query filters by. */
fun AppSettings.hiddenKeys(): List<String> {
    val filters =
        listOfNotNull(
            ChannelScan.HIDDEN_KEY.takeIf { hideDeadChannels },
            Placeholders.HIDDEN_KEY.takeIf { hidePlaceholders },
            RoundTheClock.HIDDEN_KEY.takeIf { hide247 },
            KidsContent.HIDDEN_KEY.takeIf { kidsProfile },
        )
    return (hiddenRegions + hiddenGroups + filters).sorted()
}

/** A damaged settings file starts over from the defaults instead of crashing every launch. */
private val Context.dataStore by preferencesDataStore(
    name = "settings",
    corruptionHandler =
        ReplaceFileCorruptionHandler { error ->
            AppLog.e("Settings", "The settings file was damaged and has been reset", error)
            emptyPreferences()
        },
)

/** The stored settings; a file that can't be read reads as the defaults (and is logged) rather than crashing. */
private val Context.storedPreferences: Flow<Preferences>
    get() =
        dataStore.data.catch { error ->
            if (error !is IOException) throw error
            AppLog.e("Settings", "Couldn't read the settings", error)
            emit(emptyPreferences())
        }

/** Every setting in one DataStore. Screens read [settings] and write through [update]. */
class SettingsRepository(
    private val context: Context,
) {
    /**
     * Adult categories show only while unlocked with the parental PIN, and only until the app closes: the unlock lives
     * in memory, never on disk, so `showAdult` always starts false.
     */
    private val adultUnlocked = MutableStateFlow(false)

    val settings: Flow<AppSettings> =
        combine(context.storedPreferences.map(::read), adultUnlocked) { s, unlocked -> s.copy(showAdult = unlocked && !s.kidsProfile) }
            .distinctUntilChanged()

    /** Whether a parental PIN has been set. */
    val hasAdultPin: Flow<Boolean> =
        context.storedPreferences
            .map { !it[K.adultPin].isNullOrEmpty() }
            .distinctUntilChanged()

    /** Sets (or replaces) the PIN and unlocks. The caller has already checked the old PIN, if there was one. */
    suspend fun setAdultPin(pin: String) {
        val hash = withContext(Dispatchers.Default) { PinHash.create(pin) }
        context.dataStore.edit { it[K.adultPin] = hash }
        adultUnlocked.value = true
    }

    /** Unlocks adult categories when [pin] matches. A kids profile never unlocks them. */
    suspend fun unlockAdult(pin: String): Boolean {
        val ok = checkAdultPin(pin)
        if (ok) adultUnlocked.value = true
        return ok
    }

    /** Whether [pin] is the parental PIN, without unlocking anything. */
    suspend fun checkAdultPin(pin: String): Boolean {
        val stored = context.storedPreferences.first()[K.adultPin] ?: return false
        return withContext(Dispatchers.Default) { PinHash.verify(pin, stored) }
    }

    fun lockAdult() {
        adultUnlocked.value = false
    }

    /** Records the profile in use (see [AppSettings.profileId]); only [ProfileRepository] calls this. */
    suspend fun mirrorProfile(
        id: Long,
        kids: Boolean,
        count: Int,
    ) = update { it.copy(profileId = id, kidsProfile = kids, profileCount = count) }

    /**
     * Every stored setting by its key, for a backup. Sync times and the open tab are left out: they belong to this phone.
     * Reads the file directly, so a backup fails loudly rather than saving the defaults.
     */
    suspend fun snapshot(): Map<String, Any> = context.dataStore.data
        .first()
        .asMap()
        .mapKeys { it.key.name }
        .filterKeys { it !in DEVICE_ONLY }

    /** Puts back what [snapshot] saved; values of a type the app doesn't store are skipped. */
    @Suppress("UNCHECKED_CAST") // a Set read back from a backup only ever holds the strings it was saved with
    suspend fun restore(values: Map<String, Any>) {
        context.dataStore.edit { prefs ->
            for ((name, value) in values.filterKeys { it !in DEVICE_ONLY }) {
                when (value) {
                    is Boolean -> prefs[booleanPreferencesKey(name)] = value
                    is Int -> prefs[intPreferencesKey(name)] = value
                    is Long -> prefs[longPreferencesKey(name)] = value
                    is Float -> prefs[floatPreferencesKey(name)] = value
                    is String -> prefs[stringPreferencesKey(name)] = value
                    is Set<*> -> prefs[stringSetPreferencesKey(name)] = value as Set<String>
                }
            }
        }
    }

    suspend fun update(change: (AppSettings) -> AppSettings) {
        context.dataStore.edit { prefs -> write(prefs, change(read(prefs))) }
    }

    private fun read(p: Preferences): AppSettings = SETTING_FIELDS.fold(AppSettings()) { s, field -> field.read(p, s) }

    private fun write(
        p: MutablePreferences,
        s: AppSettings,
    ) {
        SETTING_FIELDS.forEach { it.write(p, s) }
        p.remove(K.oldTmdbKey)
    }

    private object K {
        val adultPin = stringPreferencesKey("adult_pin")

        /** The per-device TMDB key from before it was built in; deleted on the next save, so it leaves backups too. */
        val oldTmdbKey = stringPreferencesKey("tmdb_key")
    }

    private companion object {
        /** Sync times, the open tab, developer mode and the profile mirror belong to this phone; a backup leaves them out. */
        val DEVICE_ONLY = SETTING_FIELDS.filter { it.deviceOnly }.map { it.key.name }.toSet()
    }
}
