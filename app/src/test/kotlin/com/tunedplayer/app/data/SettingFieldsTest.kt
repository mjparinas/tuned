package com.tunedplayer.app.data

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.tunedplayer.app.core.TitleSort
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SettingFieldsTest {
    private fun read(p: Preferences) = SETTING_FIELDS.fold(AppSettings()) { s, f -> f.read(p, s) }

    /** Every field differs from its default, so a field that isn't stored, or is stored under another's key, fails. */
    private val changed =
        AppSettings(
            theme = ThemeMode.DARK,
            dynamicColour = true,
            pureBlack = true,
            accent = Accent.TEAL,
            lighterMode = LighterMode.ON,
            startTab = StartTab.SHOWS,
            lastTab = 2,
            hiddenRegions = setOf("FR"),
            hiddenGroups = setOf("g"),
            pinnedGroups = "g:0:UK Sports",
            appShortcuts = false,
            followedEvents = setOf("Canucks"),
            playlistRefreshHours = 24,
            guideRefreshHours = 3,
            reminderLeadMinutes = 10,
            guideDays = 7,
            guideOffsetMinutes = -60,
            unmeteredOnly = true,
            refreshRequestedAt = 7,
            keepPlaylistCopies = false,
            buffer = BufferMode.SMOOTH,
            audioLanguage = "fr",
            resumePlayback = false,
            autoPlayNext = false,
            pullToRefresh = true,
            hideDeadChannels = false,
            hideFailedOnPlay = false,
            backupCopies = false,
            matchFrameRate = false,
            audioDelayMs = -150,
            tunneledPlayback = true,
            liveRewindMinutes = 60,
            recordPaddingMinutes = 5,
            hidePlaceholders = false,
            hide247 = true,
            pictureInPicture = false,
            backgroundPlay = false,
            mediaButtons = false,
            playerGestures = false,
            autoReconnect = false,
            landscapePlayback = false,
            videoFit = VideoFit.ZOOM,
            controlsTimeoutMs = 5_000,
            tuneTimeoutMs = 15_000,
            tuneMinHeight = 720,
            seekBackMs = 5_000,
            seekForwardMs = 60_000,
            skipIntroMs = 30_000,
            userAgent = "ua",
            autoSubtitles = false,
            showGuideInList = false,
            liveGrid = true,
            movieSort = TitleSort.Order.ADDED,
            showSort = TitleSort.Order.RATING,
            channelNumbers = false,
            startOnLastChannel = true,
            checkUpdates = false,
            usageStats = UsageChoice.OFF,
            captions =
                CaptionStyle(
                    enabled = true,
                    language = "en",
                    textScale = 1.5f,
                    textColor = 1,
                    backgroundColor = 2,
                    backgroundOpacity = 0.3f,
                    windowColor = 3,
                    edge = CaptionEdge.RAISED,
                    edgeColor = 4,
                    font = CaptionFont.MONO,
                    bold = true,
                    overrideStreamStyle = false,
                    bottomPadding = 0.2f,
                ),
            lastPlaylistSync = 5,
            lastGuideSync = 6,
            lastSyncError = "e",
            serverTimezone = "America/Vancouver",
            askProfileAtStart = false,
            profileId = 9,
            kidsProfile = true,
            profileCount = 3,
            profilesSeen = "[]",
        )

    @Test
    fun `every setting is saved and read back`() {
        val p = mutablePreferencesOf()
        SETTING_FIELDS.forEach { it.write(p, changed) }
        assertEquals(changed, read(p))
    }

    @Test
    fun `nothing stored reads as the defaults`() {
        assertEquals(AppSettings(), read(emptyPreferences()))
    }

    @Test
    fun `keys are unique`() {
        val names = SETTING_FIELDS.map { it.key.name }
        assertEquals(names.size, names.toSet().size)
    }

    @Test
    fun `an unknown choice from a newer version reads as the default`() {
        val p = mutablePreferencesOf(stringPreferencesKey("theme") to "SEPIA")
        assertEquals(ThemeMode.SYSTEM, read(p).theme)
    }

    /** These names are on people's phones and in their backups; renaming one silently resets that setting. */
    @Test
    fun `stored key names never change`() {
        val names = SETTING_FIELDS.map { it.key.name }.toSet()
        assertTrue(names.containsAll(KNOWN_KEYS), "missing: ${KNOWN_KEYS - names}")
    }
}

/** Every key name ever shipped. Add new ones; never remove or rename one. */
private val KNOWN_KEYS =
    setOf(
        "theme",
        "dynamic_colour",
        "pure_black",
        "accent",
        "lighter_mode",
        "start_tab",
        "last_tab",
        "hidden_regions",
        "hidden_groups",
        "pinned_groups",
        "app_shortcuts",
        "followed_events",
        "playlist_refresh_hours",
        "guide_refresh_hours",
        "reminder_lead_minutes",
        "guide_days",
        "guide_offset_minutes",
        "unmetered_only",
        "refresh_requested_at",
        "keep_playlist_copies",
        "buffer",
        "audio_language",
        "resume_playback",
        "auto_play_next",
        "pull_to_refresh",
        "hide_dead_channels",
        "hide_failed_on_play",
        "backup_copies",
        "match_frame_rate",
        "audio_delay_ms",
        "tunneled_playback",
        "live_rewind_minutes",
        "record_padding_minutes",
        "hide_placeholders",
        "hide_247",
        "picture_in_picture",
        "mini_player",
        "background_play",
        "media_buttons",
        "player_gestures",
        "auto_reconnect",
        "landscape_playback",
        "video_fit",
        "controls_timeout_ms",
        "tune_timeout_ms",
        "tune_min_height",
        "seek_back_ms",
        "seek_forward_ms",
        "skip_intro_ms",
        "user_agent",
        "show_guide_in_list",
        "live_grid",
        "movie_sort",
        "show_sort",
        "channel_numbers",
        "always_show_scrollbar",
        "start_on_last_channel",
        "check_updates",
        "usage_stats",
        "cc_enabled",
        "cc_language",
        "cc_scale",
        "cc_text",
        "cc_background",
        "cc_background_opacity",
        "cc_window",
        "cc_edge",
        "cc_edge_color",
        "cc_font",
        "cc_bold",
        "cc_override",
        "cc_bottom",
        "auto_subtitles",
        "last_playlist_sync",
        "last_guide_sync",
        "last_sync_error",
        "server_timezone",
        "time_zone",
        "ask_profile_at_start",
        "profile_id",
        "kids_profile",
        "profile_count",
        "profiles_seen",
    )
