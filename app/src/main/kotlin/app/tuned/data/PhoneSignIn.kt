package app.tuned.data

import app.tuned.core.PlaylistLink
import app.tuned.core.XtreamUrls

/**
 * Taking what a phone sent a TV (docs/decisions/onboarding.md): a copy from the phone app (playlists, settings,
 * favourites, lists, watch later) or one playlist from the phone page. Nothing is saved until the person presses OK on
 * the TV, so someone who reads the code off the screen can't add a playlist unseen.
 */
class PhoneSignIn(
    private val credentials: CredentialStore,
    private val syncCopy: SyncCopyRepository,
    private val session: PlaylistSession,
    private val premium: () -> Boolean,
) {
    /**
     * Takes [plain], a copy the TV has opened. A TV with nothing on it yet takes everything, as a new device taking a
     * Drive sync copy does; a TV already in use only adds the playlists, so its own favourites and settings stay. The
     * first playlist (the one in use on the phone) becomes the one in use. Without Premium, only that one is kept.
     * Returns false when there was no playlist to use.
     */
    suspend fun take(plain: ByteArray): Boolean {
        val playlists = clean(syncCopy.summary(plain).playlists)
        val first = playlists.firstOrNull() ?: return false
        val fresh = credentials.load() == null && credentials.saved().isEmpty()
        val keep = if (premium()) playlists else listOf(first)
        if (fresh) syncCopy.applySyncCopy(plain)
        // The copy's own logins were saved as they came; replace them with the cleaned ones, and drop any extras.
        if (fresh) credentials.saved().forEach { saved -> if (keep.none { it.samePlaylist(saved) }) credentials.forget(saved) }
        keep.drop(1).forEach(credentials::remember)
        session.useLogin(first)
        return true
    }

    companion object {
        /**
         * The phone page's playlists made the way the app's own forms make them: a server gets `http://` and loses a
         * trailing slash, and a pasted `get.php` link becomes a proper Xtream login. Playlist files never come from
         * a phone. Anything that isn't a playlist is dropped.
         */
        fun clean(playlists: List<Credentials>): List<Credentials> = playlists.mapNotNull { login ->
            when {
                login.playlistUrl.isBlank() -> {
                    login
                        .takeIf { it.server.isNotBlank() && it.username.isNotBlank() }
                        ?.copy(server = XtreamUrls.normalizeServer(login.server), username = login.username.trim())
                }

                login.kind == Credentials.Kind.File -> {
                    null
                }

                // A MAC portal comes only from the phone app, already made by its form.
                login.kind == Credentials.Kind.Stalker -> {
                    login.takeIf { it.server.isNotBlank() && it.username.isNotBlank() }
                }

                else -> {
                    when (val link = PlaylistLink.parse(login.playlistUrl)) {
                        null -> {
                            null
                        }

                        is PlaylistLink.Xtream -> {
                            Credentials(
                                link.server,
                                link.username,
                                link.password,
                                guideUrl = login.guideUrl,
                                name = login.name,
                            )
                        }

                        is PlaylistLink.M3u -> {
                            login.copy(server = "", username = "", password = "", playlistUrl = link.url)
                        }
                    }
                }
            }
        }
    }
}
