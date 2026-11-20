package com.tunedplayer.app.core

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * The parental PIN is stored only as a salted PBKDF2 hash (`v1:<salt>:<hash>`, base64), so reading the app's settings
 * file doesn't reveal it. A PIN is 4 to 8 digits.
 */
object PinHash {
    private const val ITERATIONS = 120_000
    private const val BITS = 256
    private const val SALT_BYTES = 16
    private val digits = Regex("""\d{4,8}""")

    fun isValid(pin: String): Boolean = digits.matches(pin)

    fun create(
        pin: String,
        random: SecureRandom = SecureRandom(),
    ): String {
        require(isValid(pin)) { "A PIN is 4 to 8 digits" }
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        return "v1:${b64(salt)}:${b64(derive(pin, salt))}"
    }

    fun verify(
        pin: String,
        stored: String,
    ): Boolean {
        val parts = stored.split(':')
        if (parts.size != 3 || parts[0] != "v1" || !isValid(pin)) return false
        val salt = runCatching { Base64.getDecoder().decode(parts[1]) }.getOrNull() ?: return false
        val expected = runCatching { Base64.getDecoder().decode(parts[2]) }.getOrNull() ?: return false
        return MessageDigest.isEqual(derive(pin, salt), expected)
    }

    private fun derive(
        pin: String,
        salt: ByteArray,
    ): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, BITS)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
}
