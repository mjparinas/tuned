package app.tuned.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import app.tuned.core.Stalker
import app.tuned.diagnostics.AppLog
import app.tuned.diagnostics.attempt
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class Credentials(
    /** The Xtream server, or a Stalker portal's address. */
    val server: String,
    /** The Xtream username, or a Stalker portal's MAC address. */
    val username: String,
    val password: String,
    /** Optional overrides for plain M3U providers (no Xtream login). Blank means "use the Xtream URL". */
    val playlistUrl: String = "",
    val guideUrl: String = "",
    /** More guide links (Settings › Guide), read after the main one to fill channels it lacks. Encrypted: they often carry a key. */
    val extraGuides: List<String> = emptyList(),
    /** The name the person gave it ("Living room", "Sports"). Blank shows [detail] instead. */
    val name: String = "",
) {
    enum class Kind { Xtream, Link, File, Stalker }

    val kind: Kind
        get() =
            when {
                playlistUrl.isBlank() -> Kind.Xtream
                playlistUrl.startsWith(FILE_SCHEME) -> Kind.File
                playlistUrl == Stalker.MARKER -> Kind.Stalker
                else -> Kind.Link
            }

    /** Same provider and account: switching between these keeps the cached channels. */
    fun samePlaylist(other: Credentials) = server == other.server && username == other.username && playlistUrl == other.playlistUrl

    /** Where it comes from, without the password: the account and server, the link's host, or the file's name. */
    val detail: String
        get() =
            when (kind) {
                Kind.Xtream -> "$username @ ${server.substringAfter("://")}"
                Kind.Link -> playlistUrl.substringAfter("://").substringBefore('/').substringBefore('?')
                Kind.File -> name.ifBlank { playlistUrl.substringAfterLast('/') }
                Kind.Stalker -> server.substringAfter("://").substringBefore('/')
            }

    /** How the playlist is listed: its name, or where it comes from. */
    val label: String
        get() = name.ifBlank { detail }

    companion object {
        /** Playlists picked from a file are copied into the app and read from here. */
        const val FILE_SCHEME = "file:"

        /** A Stalker portal signed in to with a MAC address (`core/Stalker`); it has no password. */
        fun stalker(
            portal: String,
            mac: String,
            guideUrl: String = "",
            name: String = "",
        ) = Credentials(portal, mac, "", Stalker.MARKER, guideUrl, name = name)
    }
}

/** An OpenSubtitles.com sign-in. */
data class SubtitleAccount(
    val username: String,
    val password: String,
)

/**
 * Keeps the login until the person signs out (Melvin, 2026-11-17). The values are encrypted with an AES-GCM key
 * that lives in the Android Keystore and never leaves it, so a copied preferences file is useless on its own.
 * The app also opts out of backups (AndroidManifest.xml), so the login never reaches a cloud backup.
 */
class CredentialStore(
    context: Context,
) {
    private val prefs = context.getSharedPreferences("tuned.credentials", Context.MODE_PRIVATE)

    @Volatile private var cached: Credentials? = null

    fun load(): Credentials? {
        cached?.let { return it }
        val blob = prefs.getString(KEY_BLOB, null) ?: return null
        val loaded = attempt(TAG, "Couldn't read the saved login") { decode(decrypt(blob)) }.getOrNull()
        cached = loaded
        loaded?.let(::maskInLogs)
        return loaded
    }

    fun save(credentials: Credentials) {
        prefs.edit { putString(KEY_BLOB, encrypt(encode(credentials))) }
        cached = credentials
        maskInLogs(credentials)
    }

    // A portal's marker isn't a secret, and masking it would hide every channel link in the log.
    private fun maskInLogs(c: Credentials) =
        AppLog.setSecrets(c.password, c.username, c.playlistUrl.takeUnless { c.kind == Credentials.Kind.Stalker }.orEmpty(), c.guideUrl)

    /** Signing out forgets every saved playlist too. */
    fun clear() {
        prefs.edit { clear() }
        cached = null
        AppLog.setSecrets()
    }

    /** Every playlist signed in to on this device, oldest first, kept encrypted like the login in use. */
    fun saved(): List<Credentials> {
        val blob = prefs.getString(KEY_SAVED, null) ?: return listOfNotNull(load())
        val all =
            attempt(TAG, "Couldn't read the saved playlists") { decrypt(blob).split(LINE).filter { it.isNotEmpty() }.map(::decode) }
                .getOrDefault(emptyList())
        return all.ifEmpty { listOfNotNull(load()) }
    }

    /** Adds [credentials] to the saved playlists, or updates the one for the same account where it stands. */
    fun remember(credentials: Credentials) {
        val all = saved()
        val existing = all.firstOrNull { it.samePlaylist(credentials) }
        if (existing == null) {
            write(all + credentials)
        } else {
            val updated = credentials.copy(name = credentials.name.ifBlank { existing.name })
            write(all.map { if (it.samePlaylist(credentials)) updated else it })
        }
    }

    fun forget(credentials: Credentials) = write(saved().filterNot { it.samePlaylist(credentials) })

    /**
     * The optional OpenSubtitles.com account (Settings › Playback › Subtitles), which raises the daily download limit. Kept
     * encrypted like the login, and forgotten on sign-out with it.
     */
    fun subtitleAccount(): SubtitleAccount? {
        val blob = prefs.getString(KEY_SUBTITLES, null) ?: return null
        return attempt(TAG, "Couldn't read the OpenSubtitles account") {
            val parts = decrypt(blob).split(SEP).map { String(Base64.decode(it, Base64.NO_WRAP)) }
            SubtitleAccount(parts[0], parts[1])
        }.getOrNull()
    }

    fun saveSubtitleAccount(account: SubtitleAccount?) {
        prefs.edit {
            if (account == null) {
                remove(KEY_SUBTITLES)
            } else {
                val plain =
                    listOf(account.username, account.password).joinToString(SEP) {
                        Base64.encodeToString(it.toByteArray(), Base64.NO_WRAP)
                    }
                putString(KEY_SUBTITLES, encrypt(plain))
            }
        }
    }

    private fun write(list: List<Credentials>) {
        prefs.edit { putString(KEY_SAVED, encrypt(list.joinToString(LINE, transform = ::encode))) }
    }

    /** Gives a saved playlist a new name, the one in use included. */
    fun rename(
        credentials: Credentials,
        name: String,
    ) {
        val renamed = credentials.copy(name = name.trim())
        write(saved().map { if (it.samePlaylist(credentials)) renamed else it })
        if (load()?.samePlaylist(credentials) == true) save(renamed)
    }

    private fun encode(c: Credentials): String = listOf(
        c.server,
        c.username,
        c.password,
        c.playlistUrl,
        c.guideUrl,
        c.extraGuides.joinToString(LINE),
        c.name,
    ).joinToString(SEP) { Base64.encodeToString(it.toByteArray(), Base64.NO_WRAP) }

    private fun decode(text: String): Credentials {
        val parts = text.split(SEP).map { String(Base64.decode(it, Base64.NO_WRAP)) }
        val extra = parts.getOrElse(5) { "" }.split(LINE).filter { it.isNotBlank() }
        val name = parts.getOrElse(6) { "" }
        return Credentials(parts[0], parts[1], parts[2], parts.getOrElse(3) { "" }, parts.getOrElse(4) { "" }, extra, name)
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec
                .Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val sealed = cipher.iv + cipher.doFinal(plain.toByteArray())
        return Base64.encodeToString(sealed, Base64.NO_WRAP)
    }

    private fun decrypt(blob: String): String {
        val bytes = Base64.decode(blob, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, IV_BYTES))
        return String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES))
    }

    private companion object {
        const val TAG = "Login"
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "tuned.credentials"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val KEY_BLOB = "blob"
        const val KEY_SAVED = "saved"
        const val KEY_SUBTITLES = "opensubtitles"
        const val LINE = "\n"
        const val SEP = ":"
    }
}
