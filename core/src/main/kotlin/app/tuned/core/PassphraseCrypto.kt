package app.tuned.core

import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Seals the sync copy with a passphrase the person chooses, because it holds their logins. AES-256-GCM with a key
 * from PBKDF2-SHA256 (a fresh salt each time); GCM's tag means a wrong passphrase or damaged data is caught, never
 * half-read. The "TUNEDBK" magic dates from backup files and stays, so copies already in Drive still open.
 * Layout: "TUNEDBK" + version byte, rounds (4 bytes), salt (16), IV (12), then the sealed bytes.
 */
object PassphraseCrypto {
    class WrongPassphrase : Exception("The passphrase doesn't open this")

    class NotOurs : Exception("This isn't sealed by Tuned")

    const val MIN_PASSPHRASE = 8
    private val MAGIC = "TUNEDBK".toByteArray()
    private const val VERSION: Byte = 1
    private const val ROUNDS = 310_000
    private const val MAX_ROUNDS = 10_000_000
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val KEY_BITS = 256
    private val random = SecureRandom()

    fun seal(
        plain: ByteArray,
        passphrase: CharArray,
        rounds: Int = ROUNDS,
    ): ByteArray {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(passphrase, salt, rounds), GCMParameterSpec(TAG_BITS, iv))
        val header =
            ByteBuffer
                .allocate(MAGIC.size + 1 + 4)
                .put(MAGIC)
                .put(VERSION)
                .putInt(rounds)
                .array()
        cipher.updateAAD(header)
        return header + salt + iv + cipher.doFinal(plain)
    }

    fun open(
        sealed: ByteArray,
        passphrase: CharArray,
    ): ByteArray {
        val headerSize = MAGIC.size + 1 + 4
        val rounds = roundsOf(sealed) ?: throw NotOurs()
        val salt = sealed.copyOfRange(headerSize, headerSize + SALT_BYTES)
        val iv = sealed.copyOfRange(headerSize + SALT_BYTES, headerSize + SALT_BYTES + IV_BYTES)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(passphrase, salt, rounds), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(sealed.copyOfRange(0, headerSize))
        return try {
            cipher.doFinal(sealed, headerSize + SALT_BYTES + IV_BYTES, sealed.size - headerSize - SALT_BYTES - IV_BYTES)
        } catch (e: GeneralSecurityException) {
            throw WrongPassphrase().initCause(e)
        }
    }

    /** The key-stretching rounds from the header, or null when [sealed] isn't something this version can read. */
    private fun roundsOf(sealed: ByteArray): Int? {
        val headerSize = MAGIC.size + 1 + 4
        if (sealed.size < headerSize + SALT_BYTES + IV_BYTES || !sealed.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) return null
        val buffer = ByteBuffer.wrap(sealed, MAGIC.size, 5)
        if (buffer.get() != VERSION) return null
        return buffer.getInt().takeIf { it in 1..MAX_ROUNDS }
    }

    private fun key(
        passphrase: CharArray,
        salt: ByteArray,
        rounds: Int,
    ): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, rounds, KEY_BITS)
        try {
            val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return SecretKeySpec(bytes, "AES")
        } finally {
            spec.clearPassword()
        }
    }
}
