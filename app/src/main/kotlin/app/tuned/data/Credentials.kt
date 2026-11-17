package app.tuned.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class Credentials(
    val server: String,
    val username: String,
    val password: String,
    /** Optional overrides for plain M3U providers (no Xtream login). Blank means "use the Xtream URL". */
    val playlistUrl: String = "",
    val guideUrl: String = "",
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
        val loaded = runCatching { decode(decrypt(blob)) }.getOrNull()
        cached = loaded
        return loaded
    }

    fun save(credentials: Credentials) {
        prefs.edit { putString(KEY_BLOB, encrypt(encode(credentials))) }
        cached = credentials
    }

    fun clear() {
        prefs.edit { clear() }
        cached = null
    }

    private fun encode(c: Credentials): String = listOf(c.server, c.username, c.password, c.playlistUrl, c.guideUrl)
        .joinToString(SEP) { Base64.encodeToString(it.toByteArray(), Base64.NO_WRAP) }

    private fun decode(text: String): Credentials {
        val parts = text.split(SEP).map { String(Base64.decode(it, Base64.NO_WRAP)) }
        return Credentials(parts[0], parts[1], parts[2], parts.getOrElse(3) { "" }, parts.getOrElse(4) { "" })
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
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "tuned.credentials"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val KEY_BLOB = "blob"
        const val SEP = ":"
    }
}
