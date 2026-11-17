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
import app.tuned.core.PinHash
import app.tuned.core.Placeholders
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
    val showAdult: Boolean = false,
    val playlistRefreshHours: Int = 12,
    val guideRefreshHours: Int = 6,
    /** How long before a programme starts its reminder goes off. */
    val reminderLeadMinutes: Int = 5,
    val guideDays: Int = 3,
    val unmeteredOnly: Boolean = false,
    val buffer: BufferMode = BufferMode.BALANCED,
    val audioLanguage: String = "",
    val resumePlayback: Boolean = true,
    val autoPlayNext: Boolean = true,
    /** Pull down on a list to refresh the playlist (Melvin, 2026-11-17: off, so a scroll never starts a download). */
    val pullToRefresh: Boolean = false,
    /** Leave out live channels that failed in two channel scans (Settings › Channel health). */
    val hideDeadChannels: Boolean = true,
    /** Leave out empty event slots ("NO EVENT STREAMING"); see [Placeholders]. Melvin, 2026-11-17: hide them. */
    val hidePlaceholders: Boolean = true,
    val pictureInPicture: Boolean = true,
    val playerGestures: Boolean = true,
    val autoReconnect: Boolean = true,
    val landscapePlayback: Boolean = true,
    /** The player's aspect button; the last choice sticks. */
    val videoFit: VideoFit = VideoFit.FIT,
    val controlsTimeoutMs: Int = 3_000,
    /** Auto-tune moves on when a channel shows no picture within this long. */
    val tuneTimeoutMs: Int = 10_000,
    /** Auto-tune skips channels below this picture height (0 takes any picture). */
    val tuneMinHeight: Int = 0,
    val seekBackMs: Long = 10_000,
    val seekForwardMs: Long = 30_000,
    /** How far "Skip intro" jumps on a show it hasn't learned yet; 0 hides the button. */
    val skipIntroMs: Long = 85_000,
    val userAgent: String = "",
    /** The person's own TMDB key (v3 key or v4 read token) for ratings and trailers; blank turns them off. */
    val tmdbKey: String = "",
    val showGuideInList: Boolean = true,
    /** Look for a newer release on GitHub at start (copies from Play update through Play either way). */
    val checkUpdates: Boolean = true,
    val captions: CaptionStyle = CaptionStyle(),
    val lastPlaylistSync: Long = 0,
    val lastGuideSync: Long = 0,
    val lastSyncError: String = "",
    /** The Xtream server's time zone, read at each refresh; catch-up links are in its local time. */
    val serverTimezone: String = "",
    /** Settings › Developer: keep a detailed log and show the log viewer, for finding what went wrong. */
    val developerMode: Boolean = false,
)

/** Hidden countries, hidden groups and the channel filters together: the one list every query filters by. */
fun AppSettings.hiddenKeys(): List<String> {
    val filters = listOfNotNull(ChannelScan.HIDDEN_KEY.takeIf { hideDeadChannels }, Placeholders.HIDDEN_KEY.takeIf { hidePlaceholders })
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
        combine(context.storedPreferences.map(::read), adultUnlocked) { s, unlocked -> s.copy(showAdult = unlocked) }
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

    /** Unlocks adult categories when [pin] matches. */
    suspend fun unlockAdult(pin: String): Boolean {
        val stored = context.storedPreferences.first()[K.adultPin] ?: return false
        val ok = withContext(Dispatchers.Default) { PinHash.verify(pin, stored) }
        if (ok) adultUnlocked.value = true
        return ok
    }

    fun lockAdult() {
        adultUnlocked.value = false
    }

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

    @Suppress("CyclomaticComplexMethod") // one `?:` per setting, nothing to untangle
    private fun read(p: Preferences): AppSettings {
        val d = AppSettings()
        val c = d.captions
        return AppSettings(
            theme = enumOr(p[K.theme], d.theme),
            dynamicColour = p[K.dynamicColour] ?: d.dynamicColour,
            pureBlack = p[K.pureBlack] ?: d.pureBlack,
            accent = enumOr(p[K.accent], d.accent),
            startTab = enumOr(p[K.startTab], d.startTab),
            lastTab = p[K.lastTab] ?: d.lastTab,
            hiddenRegions = p[K.hiddenRegions] ?: d.hiddenRegions,
            hiddenGroups = p[K.hiddenGroups] ?: d.hiddenGroups,
            playlistRefreshHours = p[K.playlistRefreshHours] ?: d.playlistRefreshHours,
            guideRefreshHours = p[K.guideRefreshHours] ?: d.guideRefreshHours,
            reminderLeadMinutes = p[K.reminderLeadMinutes] ?: d.reminderLeadMinutes,
            guideDays = p[K.guideDays] ?: d.guideDays,
            unmeteredOnly = p[K.unmeteredOnly] ?: d.unmeteredOnly,
            buffer = enumOr(p[K.buffer], d.buffer),
            audioLanguage = p[K.audioLanguage] ?: d.audioLanguage,
            resumePlayback = p[K.resumePlayback] ?: d.resumePlayback,
            autoPlayNext = p[K.autoPlayNext] ?: d.autoPlayNext,
            pullToRefresh = p[K.pullToRefresh] ?: d.pullToRefresh,
            hideDeadChannels = p[K.hideDeadChannels] ?: d.hideDeadChannels,
            hidePlaceholders = p[K.hidePlaceholders] ?: d.hidePlaceholders,
            pictureInPicture = p[K.pictureInPicture] ?: d.pictureInPicture,
            playerGestures = p[K.playerGestures] ?: d.playerGestures,
            autoReconnect = p[K.autoReconnect] ?: d.autoReconnect,
            landscapePlayback = p[K.landscapePlayback] ?: d.landscapePlayback,
            videoFit = enumOr(p[K.videoFit], d.videoFit),
            controlsTimeoutMs = p[K.controlsTimeoutMs] ?: d.controlsTimeoutMs,
            tuneTimeoutMs = p[K.tuneTimeoutMs] ?: d.tuneTimeoutMs,
            tuneMinHeight = p[K.tuneMinHeight] ?: d.tuneMinHeight,
            seekBackMs = p[K.seekBackMs] ?: d.seekBackMs,
            seekForwardMs = p[K.seekForwardMs] ?: d.seekForwardMs,
            skipIntroMs = p[K.skipIntroMs] ?: d.skipIntroMs,
            userAgent = p[K.userAgent] ?: d.userAgent,
            tmdbKey = p[K.tmdbKey] ?: d.tmdbKey,
            showGuideInList = p[K.showGuideInList] ?: d.showGuideInList,
            checkUpdates = p[K.checkUpdates] ?: d.checkUpdates,
            captions =
                CaptionStyle(
                    enabled = p[K.ccEnabled] ?: c.enabled,
                    language = p[K.ccLanguage] ?: c.language,
                    textScale = p[K.ccScale] ?: c.textScale,
                    textColor = p[K.ccText] ?: c.textColor,
                    backgroundColor = p[K.ccBackground] ?: c.backgroundColor,
                    backgroundOpacity = p[K.ccBackgroundOpacity] ?: c.backgroundOpacity,
                    windowColor = p[K.ccWindow] ?: c.windowColor,
                    edge = enumOr(p[K.ccEdge], c.edge),
                    edgeColor = p[K.ccEdgeColor] ?: c.edgeColor,
                    font = enumOr(p[K.ccFont], c.font),
                    bold = p[K.ccBold] ?: c.bold,
                    overrideStreamStyle = p[K.ccOverride] ?: c.overrideStreamStyle,
                    bottomPadding = p[K.ccBottom] ?: c.bottomPadding,
                ),
            lastPlaylistSync = p[K.lastPlaylistSync] ?: d.lastPlaylistSync,
            lastGuideSync = p[K.lastGuideSync] ?: d.lastGuideSync,
            lastSyncError = p[K.lastSyncError] ?: d.lastSyncError,
            serverTimezone = p[K.serverTimezone] ?: d.serverTimezone,
            developerMode = p[K.developerMode] ?: d.developerMode,
        )
    }

    private fun write(
        p: MutablePreferences,
        s: AppSettings,
    ) {
        p[K.theme] = s.theme.name
        p[K.dynamicColour] = s.dynamicColour
        p[K.pureBlack] = s.pureBlack
        p[K.accent] = s.accent.name
        p[K.startTab] = s.startTab.name
        p[K.lastTab] = s.lastTab
        p[K.hiddenRegions] = s.hiddenRegions
        p[K.hiddenGroups] = s.hiddenGroups
        p[K.playlistRefreshHours] = s.playlistRefreshHours
        p[K.guideRefreshHours] = s.guideRefreshHours
        p[K.reminderLeadMinutes] = s.reminderLeadMinutes
        p[K.guideDays] = s.guideDays
        p[K.unmeteredOnly] = s.unmeteredOnly
        p[K.buffer] = s.buffer.name
        p[K.audioLanguage] = s.audioLanguage
        p[K.resumePlayback] = s.resumePlayback
        p[K.autoPlayNext] = s.autoPlayNext
        p[K.pullToRefresh] = s.pullToRefresh
        p[K.hideDeadChannels] = s.hideDeadChannels
        p[K.hidePlaceholders] = s.hidePlaceholders
        p[K.pictureInPicture] = s.pictureInPicture
        p[K.playerGestures] = s.playerGestures
        p[K.autoReconnect] = s.autoReconnect
        p[K.landscapePlayback] = s.landscapePlayback
        p[K.videoFit] = s.videoFit.name
        p[K.controlsTimeoutMs] = s.controlsTimeoutMs
        p[K.tuneTimeoutMs] = s.tuneTimeoutMs
        p[K.tuneMinHeight] = s.tuneMinHeight
        p[K.seekBackMs] = s.seekBackMs
        p[K.seekForwardMs] = s.seekForwardMs
        p[K.skipIntroMs] = s.skipIntroMs
        p[K.userAgent] = s.userAgent
        p[K.tmdbKey] = s.tmdbKey
        p[K.showGuideInList] = s.showGuideInList
        p[K.checkUpdates] = s.checkUpdates
        val c = s.captions
        p[K.ccEnabled] = c.enabled
        p[K.ccLanguage] = c.language
        p[K.ccScale] = c.textScale
        p[K.ccText] = c.textColor
        p[K.ccBackground] = c.backgroundColor
        p[K.ccBackgroundOpacity] = c.backgroundOpacity
        p[K.ccWindow] = c.windowColor
        p[K.ccEdge] = c.edge.name
        p[K.ccEdgeColor] = c.edgeColor
        p[K.ccFont] = c.font.name
        p[K.ccBold] = c.bold
        p[K.ccOverride] = c.overrideStreamStyle
        p[K.ccBottom] = c.bottomPadding
        p[K.lastPlaylistSync] = s.lastPlaylistSync
        p[K.lastGuideSync] = s.lastGuideSync
        p[K.lastSyncError] = s.lastSyncError
        p[K.serverTimezone] = s.serverTimezone
        p[K.developerMode] = s.developerMode
    }

    private inline fun <reified E : Enum<E>> enumOr(
        name: String?,
        default: E,
    ): E = name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default

    private object K {
        val theme = stringPreferencesKey("theme")
        val dynamicColour = booleanPreferencesKey("dynamic_colour")
        val pureBlack = booleanPreferencesKey("pure_black")
        val accent = stringPreferencesKey("accent")
        val startTab = stringPreferencesKey("start_tab")
        val lastTab = intPreferencesKey("last_tab")
        val hiddenRegions = stringSetPreferencesKey("hidden_regions")
        val hiddenGroups = stringSetPreferencesKey("hidden_groups")
        val adultPin = stringPreferencesKey("adult_pin")
        val playlistRefreshHours = intPreferencesKey("playlist_refresh_hours")
        val guideRefreshHours = intPreferencesKey("guide_refresh_hours")
        val reminderLeadMinutes = intPreferencesKey("reminder_lead_minutes")
        val guideDays = intPreferencesKey("guide_days")
        val unmeteredOnly = booleanPreferencesKey("unmetered_only")
        val buffer = stringPreferencesKey("buffer")
        val audioLanguage = stringPreferencesKey("audio_language")
        val resumePlayback = booleanPreferencesKey("resume_playback")
        val autoPlayNext = booleanPreferencesKey("auto_play_next")
        val pullToRefresh = booleanPreferencesKey("pull_to_refresh")
        val hideDeadChannels = booleanPreferencesKey("hide_dead_channels")
        val hidePlaceholders = booleanPreferencesKey("hide_placeholders")
        val pictureInPicture = booleanPreferencesKey("picture_in_picture")
        val playerGestures = booleanPreferencesKey("player_gestures")
        val autoReconnect = booleanPreferencesKey("auto_reconnect")
        val landscapePlayback = booleanPreferencesKey("landscape_playback")
        val videoFit = stringPreferencesKey("video_fit")
        val controlsTimeoutMs = intPreferencesKey("controls_timeout_ms")
        val tuneTimeoutMs = intPreferencesKey("tune_timeout_ms")
        val tuneMinHeight = intPreferencesKey("tune_min_height")
        val seekBackMs = longPreferencesKey("seek_back_ms")
        val seekForwardMs = longPreferencesKey("seek_forward_ms")
        val skipIntroMs = longPreferencesKey("skip_intro_ms")
        val userAgent = stringPreferencesKey("user_agent")
        val tmdbKey = stringPreferencesKey("tmdb_key")
        val showGuideInList = booleanPreferencesKey("show_guide_in_list")
        val checkUpdates = booleanPreferencesKey("check_updates")
        val ccEnabled = booleanPreferencesKey("cc_enabled")
        val ccLanguage = stringPreferencesKey("cc_language")
        val ccScale = floatPreferencesKey("cc_scale")
        val ccText = intPreferencesKey("cc_text")
        val ccBackground = intPreferencesKey("cc_background")
        val ccBackgroundOpacity = floatPreferencesKey("cc_background_opacity")
        val ccWindow = intPreferencesKey("cc_window")
        val ccEdge = stringPreferencesKey("cc_edge")
        val ccEdgeColor = intPreferencesKey("cc_edge_color")
        val ccFont = stringPreferencesKey("cc_font")
        val ccBold = booleanPreferencesKey("cc_bold")
        val ccOverride = booleanPreferencesKey("cc_override")
        val ccBottom = floatPreferencesKey("cc_bottom")
        val lastPlaylistSync = longPreferencesKey("last_playlist_sync")
        val lastGuideSync = longPreferencesKey("last_guide_sync")
        val lastSyncError = stringPreferencesKey("last_sync_error")
        val serverTimezone = stringPreferencesKey("server_timezone")
        val developerMode = booleanPreferencesKey("developer_mode")
    }

    private companion object {
        val DEVICE_ONLY =
            setOf(
                K.lastPlaylistSync.name,
                K.lastGuideSync.name,
                K.lastSyncError.name,
                K.lastTab.name,
                K.serverTimezone.name,
                K.developerMode.name,
            )
    }
}
