package app.tuned.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class BackupCryptoTest {
    private val plain = """{"server":"http://tv.example","password":"hunter22"}""".toByteArray()

    @Test
    fun opensWithTheSamePassphrase() {
        val sealed = BackupCrypto.seal(plain, "correct horse".toCharArray(), rounds = 1_000)
        assertContentEquals(plain, BackupCrypto.open(sealed, "correct horse".toCharArray()))
    }

    @Test
    fun theLoginIsNotReadableInTheFile() {
        val sealed = String(BackupCrypto.seal(plain, "correct horse".toCharArray(), rounds = 1_000), Charsets.ISO_8859_1)
        assertFalse("hunter22" in sealed)
    }

    @Test
    fun aWrongPassphraseOrADamagedFileIsRefused() {
        val sealed = BackupCrypto.seal(plain, "correct horse".toCharArray(), rounds = 1_000)
        assertFailsWith<BackupCrypto.WrongPassphrase> { BackupCrypto.open(sealed, "wrong horse".toCharArray()) }
        val damaged = sealed.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertFailsWith<BackupCrypto.WrongPassphrase> { BackupCrypto.open(damaged, "correct horse".toCharArray()) }
        // Tampering with the header (say, lowering the rounds) breaks the tag too.
        val header = sealed.copyOf().also { it[10] = (it[10].toInt() xor 1).toByte() }
        assertFailsWith<Exception> { BackupCrypto.open(header, "correct horse".toCharArray()) }
    }

    @Test
    fun otherFilesAreNotBackups() {
        assertFailsWith<BackupCrypto.NotABackup> { BackupCrypto.open("#EXTM3U\n".toByteArray(), "x".toCharArray()) }
    }
}
