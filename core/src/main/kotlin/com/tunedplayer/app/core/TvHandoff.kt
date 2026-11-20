package com.tunedplayer.app.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECFieldFp
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Signing in on a TV from a phone (docs/decisions/onboarding.md). The TV makes a one-time key pair and shows a code
 * and a QR link whose fragment carries the public key; the phone seals what it sends with that key, so the relay in
 * `devices/` only ever holds a box it can't open. The same format is written by the phone page
 * (`devices/public/tv/seal.js`), and a test opens a box sealed there.
 *
 * Sealing: ECDH on P-256 with a fresh key on the sending side, HKDF-SHA256 (salt: the sender's then the TV's public
 * key, info [INFO]) to an AES-256-GCM key, the code as associated data. A box is `1 ‖ sender key (65) ‖ nonce (12) ‖
 * ciphertext and tag`. P-256 rather than X25519 because every Android version Tuned runs on, every Samsung TV's
 * WebCrypto and every phone browser has it.
 */
object TvHandoff {
    /** Digits and capitals without the look-alikes 0, 1, I, L and O. The relay makes the codes. */
    const val ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"
    const val CODE_LENGTH = 8

    /** How long a code lasts on the relay; the TV makes a new one when it runs out. */
    const val CODE_SECONDS = 600

    /** The largest box the relay takes, before base64. A sync copy is gzipped first, so this is a lot of favourites. */
    const val MAX_BOX_BYTES = 512 * 1024

    private const val VERSION: Byte = 1
    private const val POINT_BYTES = 65
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128
    private const val CHECK_DIGITS = 10_000
    private const val INFO = "tuned tv sign-in v1"

    /** What a sealed box holds, after its first byte: JSON as it is, or gzipped. */
    private const val PLAIN: Byte = 0
    private const val GZIP: Byte = 1

    /** A TV's one-time keys. [publicKey] is the uncompressed point (WebCrypto's "raw" form). */
    class Keys internal constructor(
        val publicKey: ByteArray,
        internal val privateKey: PrivateKey,
    ) {
        companion object {
            /** Keys from a PKCS #8 private key and its raw public key; for tests and for a TV that kept them. */
            fun of(
                pkcs8: ByteArray,
                publicKey: ByteArray,
            ): Keys = Keys(publicKey, KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(pkcs8)))
        }
    }

    /** Where to send: the code and the TV's public key, from a QR link or the relay. */
    data class Target(
        val code: String,
        val publicKey: ByteArray,
    ) {
        override fun equals(other: Any?) = other is Target && code == other.code && publicKey.contentEquals(other.publicKey)

        override fun hashCode() = 31 * code.hashCode() + publicKey.contentHashCode()
    }

    /** Thrown for a box that isn't ours, was changed on the way, or was sealed for another code or TV. */
    class NotOpened(
        cause: Throwable? = null,
    ) : GeneralSecurityException("Not a box for this TV", cause)

    fun newKeys(random: SecureRandom = SecureRandom()): Keys {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"), random)
        val pair = generator.generateKeyPair()
        return Keys(raw(pair.public as ECPublicKey), pair.private)
    }

    /**
     * The code as the relay keeps it (eight characters from [ALPHABET]), from what someone typed: any case, with
     * spaces or a dash. Null when it can't be one.
     */
    fun normalizeCode(text: String): String? {
        val code = text.uppercase().filter { it.isLetterOrDigit() }
        return code.takeIf { it.length == CODE_LENGTH && it.all { c -> c in ALPHABET } }
    }

    /** "K7QF-29MD", easier to read off a TV. */
    fun displayCode(code: String): String = if (code.length == CODE_LENGTH) "${code.take(4)}-${code.drop(4)}" else code

    /** The QR link: the phone page (or the app, through an app link), with the code and key after `#`. */
    fun link(
        base: String,
        code: String,
        publicKey: ByteArray,
    ): String = "${base.trimEnd('/')}/tv#$code.${b64(publicKey)}"

    /**
     * The target in a QR link (`https://…/tv#CODE.KEY`) or the page's app link (`tuned://tv#CODE.KEY`). Null for
     * anything else, or a key that isn't a point on the curve.
     */
    fun parseLink(link: String): Target? {
        val fragment = link.substringAfter('#', "")
        val code = normalizeCode(fragment.substringBefore('.'))
        val key = runCatching { unb64(fragment.substringAfter('.', "")) }.getOrNull()
        return if (code != null && key != null && validKey(key)) Target(code, key) else null
    }

    /**
     * Four digits worked out from the TV's key, shown on the TV and on the phone when the code was typed: if they
     * match, the key the relay handed over is the TV's own.
     */
    fun checkDigits(publicKey: ByteArray): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(publicKey)
        val value = BigInteger(1, hash.copyOf(4)).toLong() % CHECK_DIGITS
        return value.toString().padStart(4, '0')
    }

    /** JSON ready to seal: gzipped when that's smaller, which a sync copy always is. */
    fun pack(json: ByteArray): ByteArray {
        val zipped = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(json) } }.toByteArray()
        return if (zipped.size < json.size) byteArrayOf(GZIP) + zipped else byteArrayOf(PLAIN) + json
    }

    /** The JSON a [pack]ed payload holds. */
    fun unpack(payload: ByteArray): ByteArray {
        val body = payload.copyOfRange(minOf(1, payload.size), payload.size)
        val json =
            when (payload.firstOrNull()) {
                PLAIN -> Result.success(body)
                GZIP -> runCatching { GZIPInputStream(ByteArrayInputStream(body)).use { it.readBytes() } }
                else -> Result.failure(NotOpened())
            }
        return json.getOrElse { throw NotOpened(it) }
    }

    /** Seals [plain] for the TV at [target]. */
    fun seal(
        plain: ByteArray,
        target: Target,
        random: SecureRandom = SecureRandom(),
    ): ByteArray {
        val tv = publicKey(target.publicKey) ?: throw NotOpened()
        val mine = newKeys(random)
        val key = derive(agree(mine.privateKey, tv), mine.publicKey, target.publicKey)
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(target.code.toByteArray())
        return byteArrayOf(VERSION) + mine.publicKey + nonce + cipher.doFinal(plain)
    }

    /** Opens a box sealed for [keys] under [code], or throws [NotOpened]. */
    fun open(
        box: ByteArray,
        code: String,
        keys: Keys,
    ): ByteArray {
        val head = 1 + POINT_BYTES + NONCE_BYTES
        val sender = box.copyOfRange(minOf(1, box.size), minOf(1 + POINT_BYTES, box.size))
        val nonce = box.copyOfRange(minOf(1 + POINT_BYTES, box.size), minOf(head, box.size))
        val senderKey = publicKey(sender)
        if (box.size <= head || box[0] != VERSION || senderKey == null) throw NotOpened()
        return try {
            val key = derive(agree(keys.privateKey, senderKey), sender, keys.publicKey)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            cipher.updateAAD(code.toByteArray())
            cipher.doFinal(box, head, box.size - head)
        } catch (e: GeneralSecurityException) {
            throw NotOpened(e)
        }
    }

    fun b64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    fun unb64(text: String): ByteArray = Base64.getUrlDecoder().decode(text.trimEnd('='))

    private fun agree(
        mine: PrivateKey,
        theirs: ECPublicKey,
    ): ByteArray = KeyAgreement.getInstance("ECDH").run {
        init(mine)
        doPhase(theirs, true)
        generateSecret()
    }

    /** HKDF-SHA256 (RFC 5869) to 32 bytes: one block of expand is enough. */
    private fun derive(
        shared: ByteArray,
        senderKey: ByteArray,
        tvKey: ByteArray,
    ): ByteArray {
        val extract = Mac.getInstance("HmacSHA256")
        extract.init(SecretKeySpec(senderKey + tvKey, "HmacSHA256"))
        val prk = extract.doFinal(shared)
        val expand = Mac.getInstance("HmacSHA256")
        expand.init(SecretKeySpec(prk, "HmacSHA256"))
        return expand.doFinal(INFO.toByteArray() + byteArrayOf(1))
    }

    private val params: ECParameterSpec by lazy {
        AlgorithmParameters.getInstance("EC").run {
            init(ECGenParameterSpec("secp256r1"))
            getParameterSpec(ECParameterSpec::class.java)
        }
    }

    private fun raw(key: ECPublicKey): ByteArray = byteArrayOf(4) + fixed(key.w.affineX) + fixed(key.w.affineY)

    private fun fixed(n: BigInteger): ByteArray {
        val bytes = n.toByteArray()
        return when {
            bytes.size == 32 -> bytes
            bytes.size > 32 -> bytes.copyOfRange(bytes.size - 32, bytes.size)
            else -> ByteArray(32 - bytes.size) + bytes
        }
    }

    /** True for an uncompressed point on P-256, so a made-up key can't be used to learn the TV's. */
    fun validKey(raw: ByteArray): Boolean = publicKey(raw) != null

    private fun publicKey(raw: ByteArray): ECPublicKey? {
        if (raw.size != POINT_BYTES || raw[0] != 4.toByte()) return null
        val x = BigInteger(1, raw.copyOfRange(1, 33))
        val y = BigInteger(1, raw.copyOfRange(33, POINT_BYTES))
        val curve = params.curve
        val p = (curve.field as ECFieldFp).p
        if (x >= p || y >= p) return null
        val left = y.modPow(BigInteger.valueOf(2), p)
        val right =
            x
                .modPow(BigInteger.valueOf(3), p)
                .add(curve.a.multiply(x))
                .add(curve.b)
                .mod(p)
        if (left != right) return null
        return runCatching {
            KeyFactory
                .getInstance(
                    "EC",
                ).generatePublic(ECPublicKeySpec(ECPoint(x, y), params)) as ECPublicKey
        }.getOrNull()
    }
}
