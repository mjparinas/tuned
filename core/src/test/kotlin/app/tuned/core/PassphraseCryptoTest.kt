package app.tuned.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PassphraseCryptoTest {
    private val plain = """{"server":"http://tv.example","password":"hunter22"}""".toByteArray()

    @Test
    fun opensWithTheSamePassphrase() {
        val sealed = PassphraseCrypto.seal(plain, "correct horse".toCharArray(), rounds = 1_000)
        assertContentEquals(plain, PassphraseCrypto.open(sealed, "correct horse".toCharArray()))
    }

    @Test
    fun theLoginIsNotReadableInTheFile() {
        val sealed = String(PassphraseCrypto.seal(plain, "correct horse".toCharArray(), rounds = 1_000), Charsets.ISO_8859_1)
        assertFalse("hunter22" in sealed)
    }

    @Test
    fun aWrongPassphraseOrADamagedFileIsRefused() {
        val sealed = PassphraseCrypto.seal(plain, "correct horse".toCharArray(), rounds = 1_000)
        assertFailsWith<PassphraseCrypto.WrongPassphrase> { PassphraseCrypto.open(sealed, "wrong horse".toCharArray()) }
        val damaged = sealed.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertFailsWith<PassphraseCrypto.WrongPassphrase> { PassphraseCrypto.open(damaged, "correct horse".toCharArray()) }
        // Tampering with the header (say, lowering the rounds) breaks the tag too.
        val header = sealed.copyOf().also { it[10] = (it[10].toInt() xor 1).toByte() }
        assertFailsWith<Exception> { PassphraseCrypto.open(header, "correct horse".toCharArray()) }
    }

    @Test
    fun otherFilesAreNotBackups() {
        assertFailsWith<PassphraseCrypto.NotOurs> { PassphraseCrypto.open("#EXTM3U\n".toByteArray(), "x".toCharArray()) }
    }

    @Test
    fun aPlainSyncCopyIsNotSealed() {
        assertTrue(PassphraseCrypto.isSealed(PassphraseCrypto.seal("x".toByteArray(), "correct horse".toCharArray(), rounds = 1_000)))
        assertFalse(PassphraseCrypto.isSealed("{\"version\":1}".toByteArray()))
        assertFalse(PassphraseCrypto.isSealed(ByteArray(0)))
    }
}
