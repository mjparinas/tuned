package app.tuned.data

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey

/**
 * One stored setting: its key, and how it reads from and writes to [AppSettings]. A new setting is one field in
 * [AppSettings] and one line in [SETTING_FIELDS], so two features adding settings never edit the same lines.
 */
internal class SettingField<S : Any, T>(
    val key: Preferences.Key<S>,
    private val get: AppSettings.() -> T,
    private val set: AppSettings.(T) -> AppSettings,
    private val toStored: (T) -> S,
    private val fromStored: (S) -> T?,
    /** Belongs to this phone (sync times, the open tab), so a backup leaves it out. */
    val deviceOnly: Boolean = false,
) {
    /** A missing or unreadable value keeps what [s] has, which is the default. */
    fun read(
        p: Preferences,
        s: AppSettings,
    ): AppSettings = p[key]?.let(fromStored)?.let { s.set(it) } ?: s

    fun write(
        p: MutablePreferences,
        s: AppSettings,
    ) {
        p[key] = toStored(s.get())
    }

    fun notBackedUp() = SettingField(key, get, set, toStored, fromStored, deviceOnly = true)
}

private fun <T : Any> plain(
    key: Preferences.Key<T>,
    get: AppSettings.() -> T,
    set: AppSettings.(T) -> AppSettings,
) = SettingField(key, get, set, { it }, { it })

private fun bool(
    name: String,
    get: AppSettings.() -> Boolean,
    set: AppSettings.(Boolean) -> AppSettings,
) = plain(booleanPreferencesKey(name), get, set)

private fun int(
    name: String,
    get: AppSettings.() -> Int,
    set: AppSettings.(Int) -> AppSettings,
) = plain(intPreferencesKey(name), get, set)

private fun long(
    name: String,
    get: AppSettings.() -> Long,
    set: AppSettings.(Long) -> AppSettings,
) = plain(longPreferencesKey(name), get, set)

private fun float(
    name: String,
    get: AppSettings.() -> Float,
    set: AppSettings.(Float) -> AppSettings,
) = plain(floatPreferencesKey(name), get, set)

private fun string(
    name: String,
    get: AppSettings.() -> String,
    set: AppSettings.(String) -> AppSettings,
) = plain(stringPreferencesKey(name), get, set)

private fun stringSet(
    name: String,
    get: AppSettings.() -> Set<String>,
    set: AppSettings.(Set<String>) -> AppSettings,
) = plain(stringSetPreferencesKey(name), get, set)

/** Stored by name; a name this version doesn't know (from a newer one) reads as the default. */
private inline fun <reified E : Enum<E>> choice(
    name: String,
    noinline get: AppSettings.() -> E,
    noinline set: AppSettings.(E) -> AppSettings,
) = SettingField(stringPreferencesKey(name), get, set, { it.name }, { n -> enumValues<E>().firstOrNull { it.name == n } })

private fun AppSettings.cc(change: CaptionStyle.() -> CaptionStyle) = copy(captions = captions.change())

/**
 * Every stored setting, grouped by area. Add a new one inside its area rather than at the end, so features in
 * different areas don't touch the same lines. Key names are on people's phones and in backups: never rename one.
 * `showAdult` isn't here: it lives in memory only (see [SettingsRepository]).
 */
internal val SETTING_FIELDS: List<SettingField<*, *>> =
    listOf(
        // Appearance
        choice("theme", { theme }) { copy(theme = it) },
        bool("dynamic_colour", { dynamicColour }) { copy(dynamicColour = it) },
        bool("pure_black", { pureBlack }) { copy(pureBlack = it) },
        choice("accent", { accent }) { copy(accent = it) },
        // Start and lists
        choice("start_tab", { startTab }) { copy(startTab = it) },
        int("last_tab", { lastTab }) { copy(lastTab = it) }.notBackedUp(),
        bool("show_guide_in_list", { showGuideInList }) { copy(showGuideInList = it) },
        bool("live_grid", { liveGrid }) { copy(liveGrid = it) },
        bool("channel_numbers", { channelNumbers }) { copy(channelNumbers = it) },
        bool("always_show_scrollbar", { alwaysShowScrollbar }) { copy(alwaysShowScrollbar = it) },
        bool("start_on_last_channel", { startOnLastChannel }) { copy(startOnLastChannel = it) },
        bool("pull_to_refresh", { pullToRefresh }) { copy(pullToRefresh = it) },
        // What's hidden
        stringSet("hidden_regions", { hiddenRegions }) { copy(hiddenRegions = it) },
        stringSet("hidden_groups", { hiddenGroups }) { copy(hiddenGroups = it) },
        bool("hide_dead_channels", { hideDeadChannels }) { copy(hideDeadChannels = it) },
        bool("hide_failed_on_play", { hideFailedOnPlay }) { copy(hideFailedOnPlay = it) },
        bool("hide_placeholders", { hidePlaceholders }) { copy(hidePlaceholders = it) },
        bool("hide_247", { hide247 }) { copy(hide247 = it) },
        // Live events
        stringSet("followed_events", { followedEvents }) { copy(followedEvents = it) },
        // Refresh and guide
        int("playlist_refresh_hours", { playlistRefreshHours }) { copy(playlistRefreshHours = it) },
        int("guide_refresh_hours", { guideRefreshHours }) { copy(guideRefreshHours = it) },
        int("reminder_lead_minutes", { reminderLeadMinutes }) { copy(reminderLeadMinutes = it) },
        int("guide_days", { guideDays }) { copy(guideDays = it) },
        int("guide_offset_minutes", { guideOffsetMinutes }) { copy(guideOffsetMinutes = it) },
        bool("unmetered_only", { unmeteredOnly }) { copy(unmeteredOnly = it) },
        long("last_playlist_sync", { lastPlaylistSync }) { copy(lastPlaylistSync = it) }.notBackedUp(),
        long("last_guide_sync", { lastGuideSync }) { copy(lastGuideSync = it) }.notBackedUp(),
        string("last_sync_error", { lastSyncError }) { copy(lastSyncError = it) }.notBackedUp(),
        string("server_timezone", { serverTimezone }) { copy(serverTimezone = it) }.notBackedUp(),
        // Player
        choice("buffer", { buffer }) { copy(buffer = it) },
        string("audio_language", { audioLanguage }) { copy(audioLanguage = it) },
        bool("resume_playback", { resumePlayback }) { copy(resumePlayback = it) },
        bool("auto_play_next", { autoPlayNext }) { copy(autoPlayNext = it) },
        bool("backup_copies", { backupCopies }) { copy(backupCopies = it) },
        bool("match_frame_rate", { matchFrameRate }) { copy(matchFrameRate = it) },
        int("live_rewind_minutes", { liveRewindMinutes }) { copy(liveRewindMinutes = it) },
        bool("picture_in_picture", { pictureInPicture }) { copy(pictureInPicture = it) },
        bool("player_gestures", { playerGestures }) { copy(playerGestures = it) },
        bool("auto_reconnect", { autoReconnect }) { copy(autoReconnect = it) },
        bool("landscape_playback", { landscapePlayback }) { copy(landscapePlayback = it) },
        choice("video_fit", { videoFit }) { copy(videoFit = it) },
        int("controls_timeout_ms", { controlsTimeoutMs }) { copy(controlsTimeoutMs = it) },
        long("seek_back_ms", { seekBackMs }) { copy(seekBackMs = it) },
        long("seek_forward_ms", { seekForwardMs }) { copy(seekForwardMs = it) },
        long("skip_intro_ms", { skipIntroMs }) { copy(skipIntroMs = it) },
        // Auto-tune
        int("tune_timeout_ms", { tuneTimeoutMs }) { copy(tuneTimeoutMs = it) },
        int("tune_min_height", { tuneMinHeight }) { copy(tuneMinHeight = it) },
        // Captions
        bool("cc_enabled", { captions.enabled }) { cc { copy(enabled = it) } },
        string("cc_language", { captions.language }) { cc { copy(language = it) } },
        float("cc_scale", { captions.textScale }) { cc { copy(textScale = it) } },
        int("cc_text", { captions.textColor }) { cc { copy(textColor = it) } },
        int("cc_background", { captions.backgroundColor }) { cc { copy(backgroundColor = it) } },
        float("cc_background_opacity", { captions.backgroundOpacity }) { cc { copy(backgroundOpacity = it) } },
        int("cc_window", { captions.windowColor }) { cc { copy(windowColor = it) } },
        choice("cc_edge", { captions.edge }) { cc { copy(edge = it) } },
        int("cc_edge_color", { captions.edgeColor }) { cc { copy(edgeColor = it) } },
        choice("cc_font", { captions.font }) { cc { copy(font = it) } },
        bool("cc_bold", { captions.bold }) { cc { copy(bold = it) } },
        bool("cc_override", { captions.overrideStreamStyle }) { cc { copy(overrideStreamStyle = it) } },
        float("cc_bottom", { captions.bottomPadding }) { cc { copy(bottomPadding = it) } },
        bool("auto_subtitles", { autoSubtitles }) { copy(autoSubtitles = it) },
        // Ratings, network and updates
        string("user_agent", { userAgent }) { copy(userAgent = it) },
        bool("check_updates", { checkUpdates }) { copy(checkUpdates = it) },
        // Profiles: mirrored from the database, which a backup doesn't hold
        bool("ask_profile_at_start", { askProfileAtStart }) { copy(askProfileAtStart = it) },
        long("profile_id", { profileId }) { copy(profileId = it) }.notBackedUp(),
        bool("kids_profile", { kidsProfile }) { copy(kidsProfile = it) }.notBackedUp(),
        int("profile_count", { profileCount }) { copy(profileCount = it) }.notBackedUp(),
        // Developer
        bool("developer_mode", { developerMode }) { copy(developerMode = it) }.notBackedUp(),
    )
